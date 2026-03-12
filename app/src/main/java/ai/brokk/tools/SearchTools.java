package ai.brokk.tools;

import static ai.brokk.project.FileFilteringService.toUnixPath;
import static ai.brokk.util.Lines.truncateLine;
import static java.lang.Math.max;
import static java.lang.Math.min;

import ai.brokk.AnalyzerUtil;
import ai.brokk.Completions;
import ai.brokk.ContextManager;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragments;
import ai.brokk.git.CommitInfo;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.AbstractProject;
import ai.brokk.util.Lines;
import ai.brokk.util.Messages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Output;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;
import net.thisptr.jackson.jq.exception.JsonQueryException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Contains tool implementations related to code analysis and searching, designed to be registered with the
 * ToolRegistry.
 */
public class SearchTools {
    private static final Logger logger = LogManager.getLogger(SearchTools.class);
    private static final Pattern STRIP_PARAMS_PATTERN = Pattern.compile("(?<=\\w)\\([^)]*\\)$");

    private static final int FILE_SEARCH_LIMIT = 200;
    private static final int CLASS_COUNT_LIMIT = 10;

    private static final int SEARCH_FILE_CONTENTS_MAX_FILES_DEFAULT = 50;
    private static final int SEARCH_FILE_CONTENTS_MATCHES_PER_FILE_DEFAULT = 10;
    private static final int SEARCH_FILE_CONTENTS_MAX_CONTEXT_LINES = 5;

    private static final int SEARCH_FILE_CONTENTS_MAX_TOTAL_MATCHES = 500;
    private static final int SEARCH_FILE_CONTENTS_MAX_PARAMETER_VALUE = 500;

    private static final int SEARCH_TOOLS_PARALLELISM =
            max(2, Runtime.getRuntime().availableProcessors());

    private static final int XML_MAX_CHARS_PER_NODE = Lines.MAX_CHARS_PER_LINE;
    private static final int XML_SKIM_TOTAL_BUDGET_CHARS = 10 * XML_MAX_CHARS_PER_NODE;
    private static final int XML_ATTR_VALUE_MAX_CHARS = 256;

    private static final int JSON_SKIM_MAX_CHILDREN_PER_CONTAINER = 50;
    private static final int JSON_KEYS_PREVIEW_MAX = 10;
    private static final int JSON_STRING_PREVIEW_MAX_CHARS = 64;

    // Intentionally scoped to the lifetime of the program. SearchTools is used for background tool execution and
    // is not shut down.
    private static final ForkJoinPool searchToolsPool = new ForkJoinPool(
            SEARCH_TOOLS_PARALLELISM,
            pool -> {
                ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                t.setName("SearchTools-" + t.getPoolIndex());
                t.setDaemon(true);
                return t;
            },
            (t, e) -> logger.error("Uncaught exception in SearchTools worker thread {}", t.getName(), e),
            false);

    private static final ThreadLocal<DocumentBuilder> TL_XML_DOC_BUILDER = ThreadLocal.withInitial(() -> {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            dbf.setNamespaceAware(false);
            return dbf.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException("Failed to create DocumentBuilder", e);
        }
    });

    private static final ThreadLocal<Cache<String, XPathExpression>> xpathExpressions =
            ThreadLocal.withInitial(() -> Caffeine.newBuilder()
                    .maximumSize(4L * Runtime.getRuntime().availableProcessors())
                    .build());

    private static final ThreadLocal<Cache<String, Pattern>> searchPatterns =
            ThreadLocal.withInitial(() -> Caffeine.newBuilder()
                    .maximumSize(64L * Runtime.getRuntime().availableProcessors())
                    .build());

    private static final ThreadLocal<ObjectMapper> jqMappers = ThreadLocal.withInitial(ObjectMapper::new);

    private static final ThreadLocal<Scope> jqScopes = ThreadLocal.withInitial(() -> {
        Scope rootScope = Scope.newEmptyScope();
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, rootScope);
        return rootScope;
    });

    private static final ThreadLocal<Cache<String, JsonQuery>> jqQueries =
            ThreadLocal.withInitial(() -> Caffeine.newBuilder()
                    .maximumSize(4L * Runtime.getRuntime().availableProcessors())
                    .build());

    private static final class RegexMatchOverflowException extends RuntimeException {
        private final String pattern;

        private RegexMatchOverflowException(String pattern, StackOverflowError cause) {
            super("Regex '%s' caused StackOverflowError during matching".formatted(pattern), cause);
            this.pattern = pattern;
        }

        private String pattern() {
            return pattern;
        }
    }

    private final IContextManager contextManager; // Needed for file operations
    private final AtomicLong researchTokens = new AtomicLong(0);

    public SearchTools(IContextManager contextManager) {
        this.contextManager = contextManager;
    }

    /**
     * Returns the number of research tokens accumulated since the last call to this method, and resets the counter to zero.
     */
    public long getAndClearResearchTokens() {
        return researchTokens.getAndSet(0);
    }

    private String recordResearchTokens(String output) {
        researchTokens.addAndGet(Messages.getApproximateTokens(output));
        return output;
    }

    // --- Sanitization Helper Methods
    // These methods strip trailing parentheses like "(params)" from symbol strings.
    // This is necessary because LLMs may incorrectly include them, but the underlying
    // code analysis tools expect clean FQNs or symbol names without parameter lists.

    private static List<String> stripParams(List<String> syms) {
        return syms.stream()
                .map(sym -> STRIP_PARAMS_PATTERN.matcher(sym).replaceFirst(""))
                .toList();
    }

    private IAnalyzer getAnalyzer() {
        return contextManager.getAnalyzerUninterrupted();
    }

    // --- Helper Methods

    private static XPathExpression getOrCompileXPathExpression(String xpath) {
        Cache<String, XPathExpression> cache = xpathExpressions.get();

        XPathExpression cached = cache.getIfPresent(xpath);
        if (cached != null) {
            return cached;
        }

        try {
            XPathExpression compiled = XPathFactory.newInstance().newXPath().compile(xpath);
            cache.put(xpath, compiled);
            return compiled;
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile XPath expression: " + xpath, e);
        }
    }

    private static String capXmlAttrValue(String value) {
        if (value.length() <= XML_ATTR_VALUE_MAX_CHARS) {
            return value;
        }
        int toTake = max(0, XML_ATTR_VALUE_MAX_CHARS - "[TRUNCATED]".length());
        return value.substring(0, toTake) + "[TRUNCATED]";
    }

    private static String localName(Node node) {
        String ln = node.getLocalName();
        if (ln != null && !ln.isBlank()) {
            return ln;
        }
        String name = node.getNodeName();
        int idx = name.indexOf(':');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    private static boolean isNameStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    private static boolean isNameChar(char c) {
        return isNameStart(c) || (c >= '0' && c <= '9');
    }

    private static int prevNonWhitespaceIndex(String s, int fromExclusive) {
        for (int i = fromExclusive; i >= 0; i--) {
            if (!Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static boolean isFunctionCallAhead(String xpath, int tokenEndExclusive) {
        int i = tokenEndExclusive;
        while (i < xpath.length() && Character.isWhitespace(xpath.charAt(i))) i++;
        return i < xpath.length() && xpath.charAt(i) == '(';
    }

    private static String rewriteNamespaceAgnosticXPath(String xpath) {
        if (xpath.isBlank()) return xpath;

        StringBuilder out = new StringBuilder(xpath.length() + 32);
        boolean inSingle = false;
        boolean inDouble = false;

        boolean attributeAxis = false;

        int i = 0;
        while (i < xpath.length()) {
            char c = xpath.charAt(i);

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                out.append(c);
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                out.append(c);
                i++;
                continue;
            }

            if (inSingle || inDouble) {
                out.append(c);
                i++;
                continue;
            }

            if (i + 2 <= xpath.length() && xpath.startsWith("::", i)) {
                out.append("::");
                i += 2;
                continue;
            }

            if (isNameStart(c)) {
                int start = i;
                int j = i + 1;
                while (j < xpath.length() && isNameChar(xpath.charAt(j))) j++;
                String token = xpath.substring(start, j);

                boolean isAxisToken = j + 2 <= xpath.length() && xpath.startsWith("::", j);
                if (isAxisToken) {
                    attributeAxis = "attribute".equals(token);
                    out.append(token).append("::");
                    i = j + 2;
                    continue;
                }

                int prevIdx = prevNonWhitespaceIndex(xpath, start - 1);
                char prev = prevIdx >= 0 ? xpath.charAt(prevIdx) : '\0';
                char prevPrev = prevIdx >= 1 ? xpath.charAt(prevIdx - 1) : '\0';

                boolean precededByAt = prev == '@';
                boolean precededByAxisSep = prev == ':' && prevPrev == ':';
                boolean inNodeTestContext =
                        start == 0 || prev == '/' || prev == '[' || prev == '(' || prev == ',' || precededByAxisSep;

                boolean hasPrefix = token.indexOf(':') >= 0;

                if (precededByAt || !inNodeTestContext || hasPrefix || isFunctionCallAhead(xpath, j) || attributeAxis) {
                    out.append(token);
                    i = j;
                    attributeAxis = false;
                    continue;
                }

                out.append("*[local-name()='").append(token).append("']");
                i = j;
                attributeAxis = false;
                continue;
            }

            out.append(c);
            i++;
        }

        return out.toString();
    }

    private static Document parseXmlDocument(ProjectFile file, String content) {
        try {
            DocumentBuilder builder = TL_XML_DOC_BUILDER.get();
            Document doc = builder.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            doc.normalizeDocument();
            return doc;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            throw new IllegalArgumentException("Failed to parse XML in %s: %s".formatted(file, message), e);
        }
    }

    private static boolean isSimpleJsonIdentifier(String key) {
        if (key.isEmpty()) return false;
        if (!isNameStart(key.charAt(0))) return false;
        for (int i = 1; i < key.length(); i++) {
            if (!isNameChar(key.charAt(i))) return false;
        }
        return true;
    }

    private static String escapeJsonString(String s, int maxChars) {
        String capped =
                s.length() <= maxChars ? s : s.substring(0, max(0, maxChars - "[TRUNCATED]".length())) + "[TRUNCATED]";
        StringBuilder out = new StringBuilder(capped.length() + 16);
        for (int i = 0; i < capped.length(); i++) {
            char c = capped.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String jsonChildPath(String parent, String key) {
        if (isSimpleJsonIdentifier(key)) {
            return "%s.%s".formatted(parent, key);
        }
        return "%s[\"%s\"]".formatted(parent, escapeJsonString(key, JSON_STRING_PREVIEW_MAX_CHARS));
    }

    private static String jsonChildPath(String parent, int index) {
        return "%s[%d]".formatted(parent, index);
    }

    private static String jsonTypeLabel(JsonNode node) {
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        if (node.isNull()) return "null";
        if (node.isBinary()) return "binary";
        return node.getNodeType().name().toLowerCase(Locale.ROOT);
    }

    private static String jsonSkimBfs(JsonNode root, int totalBudgetChars) {
        record Item(String path, JsonNode node) {}

        int budget = max(1, totalBudgetChars);
        ArrayDeque<Item> q = new ArrayDeque<>();
        q.add(new Item("$", root));

        List<String> lines = new ArrayList<>();
        int used = 0;
        boolean truncated = false;

        while (!q.isEmpty()) {
            Item it = q.removeFirst();
            JsonNode node = it.node();

            String type = jsonTypeLabel(node);
            String line;

            if (node.isObject()) {
                int fields = node.size();
                List<String> keys = new ArrayList<>(min(fields, JSON_KEYS_PREVIEW_MAX));
                int shown = 0;
                for (var itFields = node.fieldNames(); itFields.hasNext() && shown < JSON_KEYS_PREVIEW_MAX; ) {
                    keys.add(itFields.next());
                    shown++;
                }
                String keysText = keys.isEmpty()
                        ? "keys=[]"
                        : "keys=[%s]"
                                .formatted(keys.stream()
                                        .map(k -> "\"" + escapeJsonString(k, JSON_STRING_PREVIEW_MAX_CHARS) + "\"")
                                        .collect(Collectors.joining(", ")));

                int omitted = max(0, fields - shown);
                String omittedText = omitted > 0 ? " (+%d more)".formatted(omitted) : "";

                line = "%s type=object fields=%d %s%s".formatted(it.path(), fields, keysText, omittedText);
            } else if (node.isArray()) {
                int len = node.size();
                Map<String, Integer> hist = new HashMap<>();
                int toSample = min(len, JSON_SKIM_MAX_CHILDREN_PER_CONTAINER);
                for (int i = 0; i < toSample; i++) {
                    hist.merge(jsonTypeLabel(node.get(i)), 1, Integer::sum);
                }
                String histText = hist.isEmpty()
                        ? "elemTypes={}"
                        : "elemTypes={%s}"
                                .formatted(hist.entrySet().stream()
                                        .sorted(Map.Entry.comparingByKey())
                                        .map(e -> "%s:%d".formatted(e.getKey(), e.getValue()))
                                        .collect(Collectors.joining(", ")));

                String omittedText = len > toSample ? " (+%d more)".formatted(len - toSample) : "";
                line = "%s type=array len=%d %s%s".formatted(it.path(), len, histText, omittedText);
            } else if (node.isTextual()) {
                String txt = node.asText("");
                String preview = escapeJsonString(txt, JSON_STRING_PREVIEW_MAX_CHARS);
                line = "%s type=string len=%d preview=\"%s\"".formatted(it.path(), txt.length(), preview);
            } else if (node.isNumber()) {
                line = "%s type=number value=%s".formatted(it.path(), node.asText());
            } else if (node.isBoolean()) {
                line = "%s type=boolean value=%s".formatted(it.path(), node.asText());
            } else if (node.isNull()) {
                line = "%s type=null".formatted(it.path());
            } else {
                String value = node.asText("");
                line = "%s type=%s value=%s".formatted(it.path(), type, value);
            }

            line = truncateLine(line, 0, line.length());

            if (used + line.length() + 1 > budget) {
                truncated = true;
                break;
            }

            lines.add(line);
            used += line.length() + 1;

            if (node.isObject()) {
                int enqueued = 0;
                for (var fields = node.fields(); fields.hasNext(); ) {
                    if (enqueued >= JSON_SKIM_MAX_CHILDREN_PER_CONTAINER) break;
                    var e = fields.next();
                    q.add(new Item(jsonChildPath(it.path(), e.getKey()), e.getValue()));
                    enqueued++;
                }
            } else if (node.isArray()) {
                int toEnqueue = min(node.size(), JSON_SKIM_MAX_CHILDREN_PER_CONTAINER);
                for (int i = 0; i < toEnqueue; i++) {
                    q.add(new Item(jsonChildPath(it.path(), i), node.get(i)));
                }
            }
        }

        if (truncated) {
            lines.add("TRUNCATED: reached output budget");
        }

        return String.join("\n", lines);
    }

    private static String toOuterXml(Node node) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.INDENT, "no");

            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(node), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            throw new RuntimeException("Failed to render outer XML: " + message, e);
        }
    }

    private static int directTextLen(Node node) {
        if (node.getNodeType() != Node.ELEMENT_NODE) return 0;

        int total = 0;
        Node child = node.getFirstChild();
        while (child != null) {
            short t = child.getNodeType();
            if (t == Node.TEXT_NODE || t == Node.CDATA_SECTION_NODE) {
                String text = child.getNodeValue();
                if (text != null) {
                    total += text.strip().length();
                }
            }
            child = child.getNextSibling();
        }
        return total;
    }

    private static Map<String, Integer> elementChildHistogram(Node node) {
        if (node.getNodeType() != Node.ELEMENT_NODE) return Map.of();

        Map<String, Integer> counts = new HashMap<>();
        Node child = node.getFirstChild();
        while (child != null) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String ln = localName(child);
                counts.merge(ln, 1, Integer::sum);
            }
            child = child.getNextSibling();
        }
        return Map.copyOf(counts);
    }

    private static String computeNodePath(Node node) {
        List<Node> elems = new ArrayList<>();
        Node cur = node;
        while (cur != null) {
            if (cur.getNodeType() == Node.ELEMENT_NODE) {
                elems.add(cur);
            }
            cur = cur.getParentNode();
        }
        if (elems.isEmpty()) return "/";

        StringBuilder sb = new StringBuilder();
        for (int i = elems.size() - 1; i >= 0; i--) {
            Node e = elems.get(i);
            String name = localName(e);

            int idx = 1;
            Node parent = e.getParentNode();
            if (parent != null) {
                Node sib = parent.getFirstChild();
                while (sib != null) {
                    if (sib.getNodeType() == Node.ELEMENT_NODE
                            && sib != e
                            && localName(sib).equals(name)) {
                        idx++;
                    }
                    if (sib == e) {
                        break;
                    }
                    sib = sib.getNextSibling();
                }
            }
            sb.append('/').append(name).append('[').append(idx).append(']');
        }
        return sb.toString();
    }

    private static String xmlSkimBfs(Node root, int totalBudgetChars) {
        int budget = max(1, totalBudgetChars);
        ArrayDeque<Node> q = new ArrayDeque<>();
        q.add(root);

        List<String> lines = new ArrayList<>();
        int used = 0;
        boolean truncated = false;

        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            String path = computeNodePath(n);

            Map<String, Integer> hist = elementChildHistogram(n);
            String histText = hist.isEmpty()
                    ? "children={}"
                    : "children={%s}"
                            .formatted(hist.entrySet().stream()
                                    .sorted(Map.Entry.comparingByKey())
                                    .map(e -> "%s:%d".formatted(e.getKey(), e.getValue()))
                                    .collect(Collectors.joining(", ")));

            int textLen = directTextLen(n);

            String attrsText = "";
            if (n instanceof Element el) {
                NamedNodeMap attrs = el.getAttributes();
                if (attrs != null && attrs.getLength() > 0) {
                    Map<String, String> attrMap = new LinkedHashMap<>();
                    for (int i = 0; i < attrs.getLength(); i++) {
                        Node a = attrs.item(i);
                        if (a == null) continue;
                        String aName = localName(a);
                        String aVal = a.getNodeValue() == null ? "" : a.getNodeValue();
                        attrMap.put(aName, capXmlAttrValue(aVal));
                    }
                    attrsText = " attrs={%s}"
                            .formatted(attrMap.entrySet().stream()
                                    .map(e -> "%s=\"%s\"".formatted(e.getKey(), e.getValue()))
                                    .collect(Collectors.joining(", ")));
                }
            }

            String line = "%s <%s> textLen=%d %s%s".formatted(path, localName(n), textLen, histText, attrsText);
            line = truncateLine(line, 0, line.length());

            if (used + line.length() + 1 > budget) {
                truncated = true;
                break;
            }

            lines.add(line);
            used += line.length() + 1;

            Node child = n.getFirstChild();
            while (child != null) {
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    q.add(child);
                }
                child = child.getNextSibling();
            }
        }

        if (truncated) {
            lines.add("TRUNCATED: reached output budget");
        }

        return String.join("\n", lines);
    }

    public static List<Pattern> compilePatterns(List<String> patterns) {
        List<String> nonBlank = patterns.stream().filter(p -> !p.isBlank()).toList();
        if (nonBlank.isEmpty()) {
            return List.of();
        }

        Cache<String, Pattern> cache = searchPatterns.get();

        List<Pattern> compiled = new ArrayList<>(nonBlank.size());
        List<String> errors = new ArrayList<>();

        for (String pat : nonBlank) {
            try {
                Pattern cached = cache.getIfPresent(pat);
                if (cached != null) {
                    compiled.add(cached);
                    continue;
                }

                Pattern newlyCompiled = Pattern.compile(pat);
                cache.put(pat, newlyCompiled);
                compiled.add(newlyCompiled);
            } catch (StackOverflowError e) {
                errors.add("'%s': pattern is too complex (StackOverflowError)".formatted(pat));
            } catch (RuntimeException e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                errors.add("'%s': %s".formatted(pat, message));
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid regex pattern(s): " + String.join("; ", errors));
        }

        return List.copyOf(compiled);
    }

    private static List<Pattern> compilePatternsWithFlags(List<String> patterns, int flags) {
        List<String> nonBlank = patterns.stream().filter(p -> !p.isBlank()).toList();
        if (nonBlank.isEmpty()) {
            return List.of();
        }

        Cache<String, Pattern> cache = searchPatterns.get();

        List<Pattern> compiled = new ArrayList<>(nonBlank.size());
        List<String> errors = new ArrayList<>();

        for (String pat : nonBlank) {
            try {
                String cacheKey = pat + "::flags=" + flags;

                Pattern cached = cache.getIfPresent(cacheKey);
                if (cached != null) {
                    compiled.add(cached);
                    continue;
                }

                Pattern newlyCompiled = Pattern.compile(pat, flags);
                cache.put(cacheKey, newlyCompiled);
                compiled.add(newlyCompiled);
            } catch (StackOverflowError e) {
                errors.add("'%s': pattern is too complex (StackOverflowError)".formatted(pat));
            } catch (RuntimeException e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                errors.add("'%s': %s".formatted(pat, message));
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid regex pattern(s): " + String.join("; ", errors));
        }

        return List.copyOf(compiled);
    }

    @Tool(
            """
                    Retrieves summaries (class fields and method/function signatures) for all classes and top-level functions defined within specified project files.
                    Supports glob patterns: '*' matches files in a single directory, '**' matches files recursively.
                    This is a fast and efficient way to read multiple related files at once.
                    (But if you don't know where what you want is located, you should use searchSymbols instead.)
                    """)
    public String getFileSummaries(
            @P(
                            "List of file paths relative to the project root. Supports glob patterns (* for single directory, ** for recursive). E.g., ['src/main/java/com/example/util/*.java', 'tests/foo/**.py']")
                    List<String> filePaths) {
        if (filePaths.isEmpty()) {
            return "Cannot get summaries: file paths list is empty";
        }

        var project = contextManager.getProject();
        List<ProjectFile> projectFiles = filePaths.stream()
                .flatMap(pattern -> Completions.expandPath(project, pattern).stream())
                .filter(ProjectFile.class::isInstance)
                .map(ProjectFile.class::cast)
                .distinct()
                .sorted() // Sort for deterministic output order
                .toList();

        if (projectFiles.isEmpty()) {
            return "No project files found matching the provided patterns: " + String.join(", ", filePaths);
        }

        List<String> allSkeletons = new ArrayList<>();
        List<String> filesProcessed = new ArrayList<>(); // Still useful for the "not found" message
        for (var file : projectFiles) {
            var skeletonsInFile = getAnalyzer().getSkeletons(file);
            if (!skeletonsInFile.isEmpty()) {
                // Add all skeleton strings from this file to the list
                allSkeletons.addAll(skeletonsInFile.values());
                filesProcessed.add(file.toString());
            } else {
                logger.debug("No skeletons found in file: {}", file);
            }
        }

        if (allSkeletons.isEmpty()) {
            // filesProcessed will be empty if no skeletons were found in any matched file
            var processedFilesString = filesProcessed.isEmpty()
                    ? projectFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(", "))
                    : String.join(", ", filesProcessed);
            return "No class summaries found in the matched files: " + processedFilesString;
        }

        // Return the combined skeleton strings directly, joined by newlines
        return recordResearchTokens(String.join("\n\n", allSkeletons));
    }

    // --- Tool Methods requiring analyzer

    @Tool(
            """
            Default discovery tool for declarations in analyzed code, even when the exact name is unknown.
            Searches for symbols (class/function/field/module definitions); ONLY returns symbol definitions/declarations.
            DO NOT use for usages/call sites/instantiation/access patterns — use scanUsages for that.

            - kinds: CLASS, FUNCTION, FIELD, MODULE
            - FUNCTION may represent a member/instance/static method or a free/top-level function (varies by language/analyzer)
            - FIELD may represent a class/instance/static field or a top-level/module/global variable (varies by language/analyzer)
            - empty kind sections are omitted

            Examples:
            <file path="src/main/java/com/example/Foo.java">
            [CLASS]
            - com.example.Foo
            [FUNCTION]
            - com.example.Foo.bar
            </file>
            """)
    public String searchSymbols(
            @P(
                            "Case-insensitive regex patterns to search for code symbols. Since ^ and $ are implicitly included, YOU MUST use explicit wildcarding (e.g., .*Foo.*, Abstract.*, [a-z]*DAO) unless you really want exact matches.")
                    List<String> patterns,
            @P("Include test files in results.") boolean includeTests,
            @P("Maximum number of matching files to return (capped at 200).") int limit) {
        // Sanitize patterns: LLM might add `()` to symbols, Joern regex usually doesn't want that unless intentional.
        patterns = stripParams(patterns);
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search definitions: patterns list is empty");
        }

        var analyzer = getAnalyzer();
        Set<CodeUnit> allDefinitions = new HashSet<>();
        for (String pattern : patterns) {
            if (!pattern.isBlank()) {
                allDefinitions.addAll(analyzer.searchDefinitions(pattern));
            }
        }
        logger.trace("Raw definitions: {}", allDefinitions);

        if (!includeTests) {
            allDefinitions = allDefinitions.stream()
                    .filter(cu -> !ContextManager.isTestFile(cu.source(), analyzer))
                    .collect(Collectors.toSet());
        }

        if (allDefinitions.isEmpty()) {
            return "No definitions found for patterns: " + String.join(", ", patterns);
        }

        int effectiveLimit = min(limit <= 0 ? FILE_SEARCH_LIMIT : limit, FILE_SEARCH_LIMIT);

        // Group by file, then by kind within each file
        var fileGroups = allDefinitions.stream()
                .collect(Collectors.groupingBy(
                        cu -> toUnixPath(cu.source().toString()),
                        Collectors.groupingBy(cu -> cu.kind().name())));

        // Build output: sorted files (limited), sorted kinds per file, sorted symbols per kind
        var result = new StringBuilder();
        var sortedFileEntries = fileGroups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        boolean truncated = sortedFileEntries.size() > effectiveLimit;
        var limitedFileEntries =
                sortedFileEntries.stream().limit(effectiveLimit).toList();

        if (truncated) {
            result.append("### WARNING: Result limit reached (max ")
                    .append(effectiveLimit)
                    .append(" files). Showing first ")
                    .append(effectiveLimit)
                    .append(" files. Retrying the same tool call will return the same results.\n\n");
        }

        limitedFileEntries.forEach(fileEntry -> {
            var filePath = fileEntry.getKey();
            var kindGroups = fileEntry.getValue();

            result.append("<file path=\"").append(filePath).append("\">\n");

            // Emit kind sections in a stable order based on analyzer's CodeUnitType
            var kindOrder = List.of("CLASS", "FUNCTION", "FIELD", "MODULE");
            kindOrder.forEach(kind -> {
                var symbols = kindGroups.get(kind);
                if (symbols != null && !symbols.isEmpty()) {
                    result.append("[").append(kind).append("]\n");
                    symbols.stream().map(CodeUnit::fqName).distinct().sorted().forEach(fqn -> result.append("- ")
                            .append(fqn)
                            .append("\n"));
                }
            });

            result.append("</file>\n");
        });

        return recordResearchTokens(result.toString());
    }

    @Tool(
            """
            Default tool for how known, analyzed symbols are used/wired.
            Returns the call sites where symbols are used and three examples of full call site source.
            Use this for questions like “how is X used/accessed/obtained/wired”.
            If you don’t know the fully qualified symbol name, call searchSymbols once to get it.
            """)
    public String scanUsages(
            @P("Fully qualified symbol names (package name, class name, optional member name) to find usages for")
                    List<String> symbols,
            @P("Include call sites in test files in results.") boolean includeTests) {
        // Sanitize symbols: remove potential `(params)` suffix from LLM.
        symbols = stripParams(symbols);
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("Cannot search usages: symbols list is empty");
        }

        List<String> results = new ArrayList<>();
        for (String symbol : symbols) {
            if (symbol.isBlank()) continue;

            var fragment = new ContextFragments.UsageFragment(
                    contextManager, symbol, includeTests, ContextFragments.UsageMode.SAMPLE);
            String text = fragment.text().join();
            if (!text.isEmpty()) {
                results.add(text);
            }
        }

        if (results.isEmpty()) {
            return "No usages found for: " + String.join(", ", symbols);
        }
        return recordResearchTokens(String.join("\n\n", results));
    }

    @Tool(
            """
                    Returns a summary of classes' contents, including fields and method signatures.
                    Use this to understand class structures and APIs much faster than fetching full source code.
                    """)
    public String getClassSkeletons(
            @P("Fully qualified class names to get the skeleton structures for") List<String> classNames) {
        // Sanitize classNames: remove potential `(params)` suffix from LLM.
        classNames = stripParams(classNames);
        if (classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get skeletons: class names list is empty");
        }

        var result = classNames.stream()
                .distinct()
                .map(fqcn -> AnalyzerUtil.getSkeleton(getAnalyzer(), fqcn))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.joining("\n\n"));

        if (result.isEmpty()) {
            return "No classes found in: " + String.join(", ", classNames);
        }

        return recordResearchTokens(result);
    }

    @Tool(
            """
                    Returns the full source code of classes.
                    This is expensive, so prefer requesting skeletons or method sources when possible.
                    Use this when you need the complete implementation details, or if you think multiple methods in the classes may be relevant.
                    """)
    public String getClassSources(
            @P("Fully qualified class names to retrieve the full source code for; max 10") List<String> classNames) {
        // Sanitize classNames: remove potential `(params)` suffix from LLM.
        classNames = stripParams(classNames);
        if (classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get class sources: class names list is empty");
        }

        StringBuilder result = new StringBuilder();
        Set<String> added = new HashSet<>();
        int processedCount = 0;
        boolean truncated = false;

        var analyzer = getAnalyzer();
        List<String> distinctNames =
                classNames.stream().distinct().filter(s -> !s.isBlank()).toList();

        for (String className : distinctNames) {
            if (processedCount >= CLASS_COUNT_LIMIT) {
                truncated = true;
                break;
            }
            var cuOpt = analyzer.getDefinitions(className).stream()
                    .filter(CodeUnit::isClass)
                    .findFirst();
            if (cuOpt.isPresent()) {
                var cu = cuOpt.get();
                if (added.add(cu.fqName())) {
                    processedCount++;
                    var fragment = new ContextFragments.CodeFragment(contextManager, cu);
                    var text = fragment.text().join();
                    if (!text.isEmpty()) {
                        if (!result.isEmpty()) {
                            result.append("\n\n");
                        }
                        result.append(text);
                    }
                }
            }
        }

        if (result.isEmpty()) {
            return "No sources found for classes: " + String.join(", ", classNames);
        }

        String output = result.toString();
        if (truncated) {
            output = "### WARNING: Result limit reached (max " + CLASS_COUNT_LIMIT + " classes). Showing first "
                    + CLASS_COUNT_LIMIT + " class sources. "
                    + "Retrying the same tool call will return the same results.\n\n" + output;
        }

        return recordResearchTokens(output);
    }

    @Tool(
            """
                    Returns the file paths (relative to the project root) where the specified symbols are defined.
                    Accepts all symbol types: classes, methods, fields, and modules.
                    """)
    public String getSymbolLocations(
            @P("Fully qualified symbol names to locate (classes, methods, fields, or modules)") List<String> symbols) {
        // Sanitize symbols: remove potential `(params)` suffix from LLM.
        symbols = stripParams(symbols);
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("Cannot get symbol locations: symbols list is empty");
        }

        var analyzer = getAnalyzer();
        List<String> locationMappings = new ArrayList<>();
        List<String> notFound = new ArrayList<>();

        symbols.stream().distinct().filter(s -> !s.isBlank()).forEach(symbol -> {
            var cuOpt = analyzer.getDefinitions(symbol).stream().findFirst();
            if (cuOpt.isPresent()) {
                var cu = cuOpt.get();
                var filepath = cu.source().toString();
                locationMappings.add(symbol + " -> " + filepath);
            } else {
                notFound.add(symbol);
            }
        });

        if (locationMappings.isEmpty()) {
            return "No symbols found for: " + String.join(", ", symbols);
        }

        StringBuilder result = new StringBuilder();
        result.append(String.join("\n", locationMappings));

        if (!notFound.isEmpty()) {
            result.append("\n\nNot found: ").append(String.join(", ", notFound));
        }

        return recordResearchTokens(result.toString());
    }

    @Tool(
            """
                    Returns the full source code of specific methods or functions. Use this to examine the implementation of particular methods without retrieving the entire classes.
                    Note: Depending on the language/analyzer, "function" may represent either a member method or a free/top-level function.
                    """)
    public String getMethodSources(
            @P("Fully qualified method names (package name, class name, method name) to retrieve sources for")
                    List<String> methodNames) {
        // Sanitize methodNames: remove potential `(params)` suffix from LLM.
        methodNames = stripParams(methodNames);
        if (methodNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get method sources: method names list is empty");
        }

        StringBuilder result = new StringBuilder();
        Set<String> added = new HashSet<>();

        var analyzer = getAnalyzer();
        methodNames.stream().distinct().filter(s -> !s.isBlank()).forEach(methodName -> {
            var cuOpt = analyzer.getDefinitions(methodName).stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst();
            if (cuOpt.isPresent()) {
                var cu = cuOpt.get();
                if (added.add(cu.fqName())) {
                    Set<String> sources = analyzer.getSources(cu, true);
                    if (!sources.isEmpty()) {
                        if (!result.isEmpty()) {
                            result.append("\n\n");
                        }
                        result.append(String.join("\n\n", sources));
                    }
                }
            }
        });

        if (result.isEmpty()) {
            return "No sources found for methods: " + String.join(", ", methodNames);
        }

        return recordResearchTokens(result.toString());
    }

    @Tool(
            """
            Retrieves the git commit log for a file or directory path, showing the history of changes.
            Provides short commit hash, author, date, commit message, and file list for each entry.
            Tracks file renames to help follow code evolution across different filenames.
            Use an empty path to get the repository-wide commit log.
            """)
    public String getGitLog(
            @P("File or directory path relative to the project root. Use empty string for repository-wide log.")
                    String path,
            @P("Maximum number of log entries to return (capped at 100).") int limit) {

        var repo = contextManager.getRepo();

        // Cap limit at 100 and ensure at least 1
        int effectiveLimit = max(1, min(limit, 100));

        // Canonicalize: normalize the path, treat blank as empty
        String canonicalPathString = path.isBlank() ? "" : path.strip();
        if (!canonicalPathString.isEmpty()) {
            canonicalPathString =
                    Path.of(canonicalPathString).normalize().toString().replace('\\', '/');
        }

        try {
            // Check if it's a file for rename tracking
            boolean isFile = false;
            if (!canonicalPathString.isEmpty()) {
                var projectFile = new ProjectFile(contextManager.getProject().getRoot(), Path.of(canonicalPathString));
                isFile = java.nio.file.Files.isRegularFile(projectFile.absPath())
                        || repo.getTrackedFiles().contains(projectFile);
            }

            var sb = new StringBuilder();
            sb.append("<git_log");
            if (!canonicalPathString.isEmpty()) {
                sb.append(" path=\"").append(canonicalPathString).append("\"");
            }
            sb.append(">\n");

            if (isFile) {
                List<IGitRepo.FileHistoryEntry> entries = repo instanceof GitRepo gr
                        ? gr.getFileHistoryWithPaths(
                                new ProjectFile(contextManager.getProject().getRoot(), Path.of(canonicalPathString)))
                        : List.of();

                if (entries.isEmpty()) {
                    return "No history found for file: " + canonicalPathString;
                }

                ProjectFile previousPath = null;
                for (var entry : entries.stream().limit(effectiveLimit).toList()) {
                    appendCommitEntry(sb, repo, entry.commit(), entry.path(), previousPath);
                    previousPath = entry.path();
                }
            } else {
                var commits = repo.getGitLog(canonicalPathString, effectiveLimit);
                if (commits.isEmpty()) {
                    return "No history found for path: "
                            + (canonicalPathString.isEmpty() ? "(repo root)" : canonicalPathString);
                }

                for (var commit : commits) {
                    appendCommitEntry(sb, repo, commit, null, null);
                }
            }

            sb.append("</git_log>");
            return recordResearchTokens(sb.toString());
        } catch (GitAPIException e) {
            logger.error("Error retrieving git log for path '{}': {}", path, e.getMessage(), e);
            return "Error retrieving git log: " + e.getMessage();
        }
    }

    private void appendCommitEntry(
            StringBuilder sb,
            IGitRepo repo,
            CommitInfo commit,
            @Nullable ProjectFile currentPath,
            @Nullable ProjectFile nextPath) {
        var shortId = (repo instanceof GitRepo gr)
                ? gr.shortHash(commit.id())
                : commit.id().substring(0, 7);

        String fullMessage;
        try {
            fullMessage = (repo instanceof GitRepo gr) ? gr.getCommitFullMessage(commit.id()) : commit.message();
        } catch (GitAPIException e) {
            fullMessage = commit.message();
        }

        sb.append("<entry hash=\"").append(shortId).append("\"");
        sb.append(" author=\"").append(commit.author()).append("\"");
        sb.append(" date=\"").append(commit.date()).append("\"");
        if (currentPath != null) {
            sb.append(" path=\"").append(currentPath).append("\"");
        }
        sb.append(">\n");

        if (nextPath != null && !nextPath.equals(currentPath)) {
            sb.append("[RENAMED] ")
                    .append(currentPath)
                    .append(" -> ")
                    .append(nextPath)
                    .append("\n");
        }

        sb.append(fullMessage.strip()).append("\n");

        List<ProjectFile> changedFiles;
        try {
            changedFiles = CommitInfo.changedFiles((GitRepo) repo, commit.id());
        } catch (GitAPIException e) {
            logger.error("Error retrieving changed files for commit {}", commit.id(), e);
            changedFiles = List.of();
        }

        if (!changedFiles.isEmpty()) {
            String fileCdl = changedFiles.stream()
                    .map(ProjectFile::getFileName)
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining(", "));
            sb.append("Files: ").append(fileCdl).append("\n");
        }

        sb.append("</entry>\n");
    }

    @Tool(
            """
                    Search git commit messages using a Java regular expression.
                    Returns matching commits with their message and list of changed files.
                    If the list of files is extremely long, it will be summarized with respect to your explanation.
                    """)
    public String searchGitCommitMessages(
            @P("Java-style regex pattern to search for within commit messages.") String pattern,
            @P("Maximum number of matching commits to return (capped at 200).") int limit) {
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("Cannot search commit messages: pattern is empty");
        }

        var projectRoot = contextManager.getProject().getRoot();
        if (!GitRepoFactory.hasGitRepo(projectRoot)) {
            return "Cannot search commit messages: Git repository not found for this project.";
        }

        var repo = contextManager.getRepo();
        if (!(repo instanceof GitRepo gitRepo)) {
            return "Cannot search commit messages: git repo is not available as a GitRepo (was: "
                    + repo.getClass().getName() + ").";
        }

        int effectiveLimit = max(1, min(limit <= 0 ? FILE_SEARCH_LIMIT : limit, FILE_SEARCH_LIMIT));

        GitRepo.SearchCommitsResult searchResult;
        try {
            searchResult = gitRepo.searchCommits(pattern, effectiveLimit);
        } catch (GitAPIException e) {
            logger.error("Error searching commit messages", e);
            return "Error searching commit messages: " + e.getMessage();
        }

        List<CommitInfo> matchingCommits = searchResult.commits();
        if (matchingCommits.isEmpty()) {
            return "No commit messages found matching pattern: " + pattern;
        }

        boolean truncated = searchResult.truncated();

        StringBuilder resultBuilder = new StringBuilder();
        if (truncated) {
            resultBuilder
                    .append("### WARNING: Result limit reached (max ")
                    .append(effectiveLimit)
                    .append(" commits). Showing first ")
                    .append(effectiveLimit)
                    .append(" matching commits. ")
                    .append("Retrying the same tool call will return the same results.\n\n");
        }

        for (var commit : matchingCommits) {
            resultBuilder.append("<commit id=\"").append(commit.id()).append("\">\n");
            try {
                // Ensure we always close <message>
                resultBuilder.append("<message>\n");
                try {
                    resultBuilder.append(commit.message().stripIndent()).append("\n");
                } finally {
                    resultBuilder.append("</message>\n");
                }

                // Ensure we always close <edited_files>
                resultBuilder.append("<edited_files>\n");
                try {
                    List<ProjectFile> changedFilesList;
                    try {
                        changedFilesList = CommitInfo.changedFiles(gitRepo, commit.id());
                    } catch (GitAPIException e) {
                        logger.error("Error retrieving changed files for commit {}", commit.id(), e);
                        changedFilesList = List.of();
                    }
                    var changedFiles =
                            changedFilesList.stream().map(ProjectFile::toString).collect(Collectors.joining("\n"));
                    resultBuilder.append(changedFiles).append("\n");
                } finally {
                    resultBuilder.append("</edited_files>\n");
                }
            } finally {
                resultBuilder.append("</commit>\n");
            }
        }

        return recordResearchTokens(resultBuilder.toString());
    }

    // --- Text search tools

    @Tool(
            """
                    Use for locating files/assets by name/path, not for primary code discovery in analyzed languages.
                    Returns file names (paths relative to the project root) whose text contents match Java regular expression patterns.
                    Faster/cheaper than searchFileContents, since it only returns filenames and can stop as soon as it finds a match.
                    """)
    public String findFilesContaining(
            @P(
                            "Java-style regex patterns to search for within file contents. Unlike searchSymbols this does not automatically include any implicit anchors or case insensitivity.")
                    List<String> patterns,
            @P("Maximum number of files to return (capped at 200).") int limit)
            throws InterruptedException {
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search substrings: patterns list is empty");
        }

        logger.debug("Searching file contents for patterns: {}", patterns);

        final List<Pattern> compiledPatterns;
        try {
            compiledPatterns = compilePatterns(patterns);
        } catch (IllegalArgumentException e) {
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        if (compiledPatterns.isEmpty()) {
            throw new IllegalArgumentException("No valid patterns provided");
        }

        var searchResult = findFilesContainingPatterns(
                compiledPatterns, contextManager.getProject().getAllFiles());
        // sort to provide deterministic results because getAllFiles() is an unordered Set
        var allMatches = searchResult.matches().stream()
                .map(ProjectFile::toString)
                .sorted()
                .toList();

        int effectiveLimit = min(limit <= 0 ? FILE_SEARCH_LIMIT : limit, FILE_SEARCH_LIMIT);
        boolean truncated = allMatches.size() > effectiveLimit;
        var matchingFilenames = allMatches.stream().limit(effectiveLimit).toList();

        if (matchingFilenames.isEmpty()) {
            if (!searchResult.errors().isEmpty()) {
                return "No files found with content matching patterns: %s (errors occurred reading %d files; first: %s)"
                        .formatted(
                                String.join(", ", patterns),
                                searchResult.errors().size(),
                                searchResult.errors().getFirst());
            }
            return "No files found with content matching patterns: " + String.join(", ", patterns);
        }
        String prefix = "";
        if (truncated) {
            prefix = "### WARNING: Result limit reached (max " + effectiveLimit + " files). Showing first "
                    + effectiveLimit + " matches. " + "Retrying the same tool call will return the same results.\n\n";
        }

        var msg = prefix + "Files with content matching patterns: " + String.join(", ", matchingFilenames);
        if (!searchResult.errors().isEmpty()) {
            msg += " (warnings: errors occurred reading %d files; first: %s)"
                    .formatted(
                            searchResult.errors().size(), searchResult.errors().getFirst());
        }
        logger.debug(msg);
        return recordResearchTokens(msg);
    }

    public record FindFilesContainingResult(Set<ProjectFile> matches, List<String> errors) {}

    private record FilePatternSearchResult(@Nullable ProjectFile match, @Nullable String error) {}

    public static FindFilesContainingResult findFilesContainingPatterns(
            List<Pattern> patterns, Set<ProjectFile> filesToSearch) throws InterruptedException {
        if (patterns.isEmpty()) {
            return new FindFilesContainingResult(Set.of(), List.of());
        }

        var orderedFiles = filesToSearch.stream().sorted().toList();
        int batchSize = 2 * FILE_SEARCH_LIMIT;

        var results = new ArrayList<FilePatternSearchResult>(orderedFiles.size());

        for (int start = 0; start < orderedFiles.size(); start += batchSize) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted between file search batches");
            }
            var batch = orderedFiles.subList(start, min(start + batchSize, orderedFiles.size()));

            final List<FilePatternSearchResult> batchResults;
            try {
                batchResults = runInSearchToolsPool(() -> batch.parallelStream()
                        .map(file -> {
                            try {
                                if (!file.isText()) {
                                    return new FilePatternSearchResult(null, null);
                                }
                                var fileContentsOpt = file.read();
                                if (fileContentsOpt.isEmpty()) {
                                    return new FilePatternSearchResult(null, null);
                                }

                                String fileContents = fileContentsOpt.get();
                                boolean matched;
                                try {
                                    matched = patterns.stream().anyMatch(p -> findWithOverflowGuard(p, fileContents));
                                } catch (RegexMatchOverflowException e) {
                                    String message = "regex '%s' caused StackOverflowError".formatted(e.pattern());
                                    logger.warn("Regex stack overflow while searching file {}", file, e);
                                    return new FilePatternSearchResult(null, file + ": " + message);
                                }

                                return matched
                                        ? new FilePatternSearchResult(file, null)
                                        : new FilePatternSearchResult(null, null);
                            } catch (Exception e) {
                                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                                return new FilePatternSearchResult(null, file + ": " + message);
                            }
                        })
                        .toList());
            } catch (RuntimeException e) {
                logger.error("Error searching file contents", e);
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                return new FindFilesContainingResult(Set.of(), List.of(message));
            }

            results.addAll(batchResults);
        }

        var matches = results.stream()
                .map(FilePatternSearchResult::match)
                .filter(Objects::nonNull)
                .map(Objects::requireNonNull)
                .collect(Collectors.toSet());

        var errors = results.stream()
                .map(FilePatternSearchResult::error)
                .filter(Objects::nonNull)
                .map(Objects::requireNonNull)
                .toList();

        if (!errors.isEmpty()) {
            logger.warn("Errors searching file contents: {}", errors);
        }

        return new FindFilesContainingResult(matches, errors);
    }

    private record BatchResult<T>(List<T> results, List<String> errors, boolean truncatedByMaxFiles) {}

    private record IndexedResult<T>(int index, @Nullable T value, @Nullable String error) {}

    private <T> BatchResult<T> batchProcessFiles(
            List<ProjectFile> files, int maxFiles, BiFunction<ProjectFile, Integer, IndexedResult<T>> processor)
            throws InterruptedException {

        List<T> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean truncated = false;

        int batchSize = 2 * FILE_SEARCH_LIMIT;

        outer:
        for (int start = 0; start < files.size(); start += batchSize) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted during batch processing");
            }
            if (results.size() >= maxFiles) {
                truncated = true;
                break;
            }

            var batch = files.subList(start, min(start + batchSize, files.size()));
            final int batchOffset = start;

            List<IndexedResult<T>> batchResults = runInSearchToolsPool(() -> IntStream.range(0, batch.size())
                    .parallel()
                    .mapToObj(idx -> processor.apply(batch.get(idx), batchOffset + idx))
                    .sorted(Comparator.comparingInt(IndexedResult::index))
                    .toList());

            for (var res : batchResults) {
                if (res.error() != null) {
                    errors.add(res.error());
                }
                if (res.value() != null) {
                    if (results.size() >= maxFiles) {
                        truncated = true;
                        break outer;
                    }
                    results.add(res.value());
                }
            }
        }

        return new BatchResult<>(results, errors, truncated);
    }

    @Tool(
            """
            Fallback for literals, config, comments, external refs, and un-analyzed files; not the first choice for analyzed code entity discovery.
            Searches for a regex pattern within file contents across files matching a glob pattern.
            Provides grep-like output with line numbers and optional context lines.

            Notes:
            - This tool enforces a global limit of 500 total matching lines across all returned files.
            - If maxFiles * matchesPerFile is greater than 500, results may be truncated early.
            - matchesPerFile is the per-file hit count (matching lines), not the number of printed lines (context lines do not count).
            """)
    public String searchFileContents(
            @P("Java-style regex patterns to search for.") List<String> patterns,
            @P("Glob pattern for file paths (e.g., '**/AGENTS.md', 'src/**/*.java').") String filepath,
            @P("Case-insensitive matching (equivalent to Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).")
                    boolean caseInsensitive,
            @P("Enable multiline mode (equivalent to Pattern.MULTILINE, affecting ^ and $).") boolean multiline,
            @P("Number of context lines to show around each match (0-5).") int contextLines,
            @P("Maximum number of files to return results for. Capped at 500.") int maxFiles,
            @P("Maximum number of matching lines (hits) per file. Capped at 500.") int matchesPerFile)
            throws InterruptedException {
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search file contents: patterns list is empty");
        }

        var project = contextManager.getProject();
        var files = Completions.expandPath(project, filepath).stream()
                .filter(ProjectFile.class::isInstance)
                .map(ProjectFile.class::cast)
                .filter(BrokkFile::isText)
                .sorted()
                .toList();

        // Retry without leading **/ if no files found, to support matching root files
        if (files.isEmpty() && (filepath.startsWith("**/") || filepath.startsWith("**\\"))) {
            files = Completions.expandPath(project, filepath.substring(3)).stream()
                    .filter(ProjectFile.class::isInstance)
                    .map(ProjectFile.class::cast)
                    .filter(BrokkFile::isText)
                    .sorted()
                    .toList();
        }

        if (files.isEmpty()) {
            return "No text files found matching: " + filepath;
        }

        int clampedContext = max(0, min(contextLines, SEARCH_FILE_CONTENTS_MAX_CONTEXT_LINES));

        int effectiveMaxFiles = max(
                1,
                min(
                        maxFiles <= 0 ? SEARCH_FILE_CONTENTS_MAX_FILES_DEFAULT : maxFiles,
                        SEARCH_FILE_CONTENTS_MAX_PARAMETER_VALUE));

        int effectiveMatchesPerFile = max(
                1,
                min(
                        matchesPerFile <= 0 ? SEARCH_FILE_CONTENTS_MATCHES_PER_FILE_DEFAULT : matchesPerFile,
                        SEARCH_FILE_CONTENTS_MAX_PARAMETER_VALUE));

        int flags = 0;
        if (caseInsensitive) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        if (multiline) {
            flags |= Pattern.MULTILINE;
        }

        final List<Pattern> compiledPatterns;
        try {
            compiledPatterns = compilePatternsWithFlags(patterns, flags);
            if (compiledPatterns.isEmpty()) {
                return "No valid patterns provided";
            }
        } catch (IllegalArgumentException e) {
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        record FileHit(ProjectFile file, String output, int matches) {}

        String patternsLabel = String.join(", ", patterns);
        BatchResult<FileHit> batchResult;
        try {
            batchResult = batchProcessFiles(files, effectiveMaxFiles, (file, idx) -> {
                try {
                    var res = searchFileContentsInFile(file, compiledPatterns, clampedContext, effectiveMatchesPerFile);
                    if (res == null) return new IndexedResult<>(idx, null, null);
                    return new IndexedResult<>(idx, new FileHit(file, res.output(), res.matches()), null);
                } catch (RegexMatchOverflowException e) {
                    String message = "%s: regex '%s' caused StackOverflowError".formatted(file, e.pattern());
                    logger.warn("Regex stack overflow while searching file contents in {}", file, e);
                    return new IndexedResult<>(idx, null, message);
                }
            });
        } catch (RuntimeException e) {
            logger.error("Error searching file contents for '{}' in '{}'", patternsLabel, filepath, e);
            return "Error searching file contents: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }

        List<String> processingErrors = new ArrayList<>(batchResult.errors());
        List<String> fileBlocks = new ArrayList<>();
        int totalMatches = 0;
        boolean truncatedByTotalMatches = false;

        for (var hit : batchResult.results()) {
            if (totalMatches >= SEARCH_FILE_CONTENTS_MAX_TOTAL_MATCHES) {
                truncatedByTotalMatches = true;
                break;
            }

            int remaining = SEARCH_FILE_CONTENTS_MAX_TOTAL_MATCHES - totalMatches;
            if (hit.matches() <= remaining) {
                fileBlocks.add(hit.output());
                totalMatches += hit.matches();
            } else {
                if (remaining > 0) {
                    try {
                        var recomputed =
                                searchFileContentsInFile(hit.file(), compiledPatterns, clampedContext, remaining);
                        if (recomputed != null) {
                            fileBlocks.add(recomputed.output());
                            totalMatches += recomputed.matches();
                        }
                    } catch (RegexMatchOverflowException e) {
                        String message = "%s: regex '%s' caused StackOverflowError".formatted(hit.file(), e.pattern());
                        processingErrors.add(message);
                        logger.warn("Regex stack overflow while recomputing content search for {}", hit.file(), e);
                    }
                }
                truncatedByTotalMatches = true;
                break;
            }
        }

        if (fileBlocks.isEmpty()) {
            if (!processingErrors.isEmpty()) {
                return "No matches found for pattern(s) '%s' in files matching '%s' (errors occurred in %d files; first: %s)"
                        .formatted(patternsLabel, filepath, processingErrors.size(), processingErrors.getFirst());
            }
            return "No matches found for pattern(s) '" + patternsLabel + "' in files matching '" + filepath + "'";
        }

        var suffixLines = new ArrayList<String>(3);
        if (truncatedByTotalMatches) {
            suffixLines.add("TRUNCATED: reached global limit of %d total matches"
                    .formatted(SEARCH_FILE_CONTENTS_MAX_TOTAL_MATCHES));
        }
        if (batchResult.truncatedByMaxFiles()) {
            suffixLines.add("TRUNCATED: reached maxFiles=%d".formatted(effectiveMaxFiles));
        }
        if (!processingErrors.isEmpty()) {
            suffixLines.add("WARNINGS: errors occurred in %d files; first: %s"
                    .formatted(processingErrors.size(), processingErrors.getFirst()));
        }

        String output = String.join("\n\n", fileBlocks);
        if (!suffixLines.isEmpty()) {
            output += "\n\n" + String.join("\n", suffixLines);
        }

        return recordResearchTokens(output);
    }

    private record FileContentSearchResult(String output, int matches) {}

    private record LineRef(int lineNo, int startInclusive, int endExclusive) {}

    private static List<LineRef> computeLineRefs(String content) {
        if (content.isEmpty()) return List.of();

        int length = content.length();
        int lineNo = 1;
        int lineStart = 0;

        List<LineRef> refs = new ArrayList<>();

        int i = 0;
        while (i < length) {
            char c = content.charAt(i);
            if (c != '\n' && c != '\r') {
                i++;
                continue;
            }

            int lineEnd = i;
            int next = i + 1;
            if (c == '\r' && next < length && content.charAt(next) == '\n') {
                next++;
            }

            refs.add(new LineRef(lineNo, lineStart, lineEnd));

            lineNo++;
            lineStart = next;
            i = next;
        }

        if (lineStart < length) {
            refs.add(new LineRef(lineNo, lineStart, length));
        }

        return List.copyOf(refs);
    }

    private static int lineNoForOffset(int[] lineStartOffsets, int matchStartOffset) {
        int idx = Arrays.binarySearch(lineStartOffsets, matchStartOffset);
        if (idx >= 0) {
            return idx + 1;
        }
        int insertionPoint = -idx - 1;
        return insertionPoint;
    }

    @Nullable
    private static FileContentSearchResult searchFileContentsInFile(
            ProjectFile file, List<Pattern> patterns, int contextLines, int maxMatchesToTake) {
        var contentOpt = file.read();
        if (contentOpt.isEmpty()) {
            return null;
        }

        String content = contentOpt.get();
        if (content.isEmpty()) {
            return null;
        }

        List<LineRef> lineRefs = computeLineRefs(content);
        if (lineRefs.isEmpty()) {
            return null;
        }

        int[] lineStartOffsets =
                lineRefs.stream().mapToInt(LineRef::startInclusive).toArray();
        int lineCount = lineRefs.size();

        Map<Integer, Integer> firstMatchOffsetByLine = new HashMap<>();
        // Buffer the search slightly beyond maxMatchesToTake to account for context overlap and sorting
        int searchThreshold = maxMatchesToTake + contextLines + 1;

        for (Pattern pattern : patterns) {
            try {
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    int matchStart = matcher.start();
                    int ln = lineNoForOffset(lineStartOffsets, matchStart);
                    if (ln < 1 || ln > lineCount) continue;
                    firstMatchOffsetByLine.merge(ln, matchStart, Math::min);

                    if (firstMatchOffsetByLine.size() >= searchThreshold) {
                        break;
                    }
                }
            } catch (StackOverflowError e) {
                throw new RegexMatchOverflowException(pattern.pattern(), e);
            }
        }

        if (firstMatchOffsetByLine.isEmpty()) {
            return null;
        }

        List<Integer> hitLines = firstMatchOffsetByLine.keySet().stream()
                .sorted()
                .limit(maxMatchesToTake)
                .toList();

        int matchesTaken = hitLines.size();

        boolean[] toPrint = new boolean[lineCount + 1]; // 1-based indexing for convenience
        for (int hit : hitLines) {
            int from = max(1, hit - contextLines);
            int to = min(lineCount, hit + contextLines);
            for (int ln = from; ln <= to; ln++) {
                toPrint[ln] = true;
            }
        }

        List<String> outLines = new ArrayList<>();
        for (LineRef ref : lineRefs) {
            if (!toPrint[ref.lineNo()]) continue;
            outLines.add(
                    "%d: %s".formatted(ref.lineNo(), truncateLine(content, ref.startInclusive(), ref.endExclusive())));
        }

        boolean hitLimit = matchesTaken >= maxMatchesToTake;
        String matchCountLabel = hitLimit
                ? "first %d matches".formatted(matchesTaken)
                : "%d %s".formatted(matchesTaken, matchesTaken == 1 ? "match" : "matches");

        String filePath = file.toString().replace('\\', '/');
        String header = "%s [%s]".formatted(filePath, matchCountLabel);

        List<String> resultLines = new ArrayList<>(outLines.size() + 1);
        resultLines.add(header);
        resultLines.addAll(outLines);

        return new FileContentSearchResult(String.join("\n", resultLines), matchesTaken);
    }

    private record EffectiveLimits(int maxFiles, int matchesPerFile) {}

    private static EffectiveLimits clampMaxFilesAndMatchesPerFile(int maxFiles, int matchesPerFile) {
        int effectiveMaxFiles = max(
                1,
                min(
                        maxFiles <= 0 ? SEARCH_FILE_CONTENTS_MAX_FILES_DEFAULT : maxFiles,
                        SEARCH_FILE_CONTENTS_MAX_PARAMETER_VALUE));

        int effectiveMatchesPerFile = max(
                1,
                min(
                        matchesPerFile <= 0 ? SEARCH_FILE_CONTENTS_MATCHES_PER_FILE_DEFAULT : matchesPerFile,
                        SEARCH_FILE_CONTENTS_MAX_PARAMETER_VALUE));

        long product = (long) effectiveMaxFiles * (long) effectiveMatchesPerFile;
        if (product > SEARCH_FILE_CONTENTS_MAX_TOTAL_MATCHES) {
            effectiveMatchesPerFile = max(1, SEARCH_FILE_CONTENTS_MAX_TOTAL_MATCHES / effectiveMaxFiles);
        }

        return new EffectiveLimits(effectiveMaxFiles, effectiveMatchesPerFile);
    }

    @Tool(
            """
            Executes a jq filter against JSON files using jackson-jq.

            Output:
            - Most results are returned as compact JSON strings.
            - If a result is a JSON object or array whose rendered JSON would exceed MAX_CHARS_PER_LINE,
              the output falls back to a BFS structural skim of that result (capped to MAX_CHARS_PER_LINE),
              similar to xmlSkim.

            Notes:
            - maxFiles and matchesPerFile are capped at 500 each.
            - maxFiles * matchesPerFile is forced to be <= 500.
            """)
    public String jq(
            @P("File path or glob pattern (e.g., 'package.json', '**/data/*.json').") String filepath,
            @P("jq filter to apply.") String filter,
            @P("Maximum number of files to return results for. Capped at 500.") int maxFiles,
            @P("Maximum number of output values to return per file. Capped at 500.") int matchesPerFile)
            throws InterruptedException {
        var project = contextManager.getProject();
        var files = Completions.expandPath(project, filepath).stream()
                .filter(ProjectFile.class::isInstance)
                .map(ProjectFile.class::cast)
                .filter(pf -> pf.isText() && "json".equalsIgnoreCase(pf.extension()))
                .sorted()
                .toList();

        if (files.isEmpty()) {
            return "No JSON files found matching: " + filepath;
        }

        if (filter.isBlank()) {
            throw new IllegalArgumentException("Cannot jq: filter is empty");
        }

        EffectiveLimits limits = clampMaxFilesAndMatchesPerFile(maxFiles, matchesPerFile);

        final JsonQuery compiledQuery;
        try {
            Cache<String, JsonQuery> cache = jqQueries.get();
            JsonQuery cached = cache.getIfPresent(filter);
            if (cached != null) {
                compiledQuery = cached;
            } else {
                JsonQuery newlyCompiled = JsonQuery.compile(filter, Versions.JQ_1_6);
                cache.put(filter, newlyCompiled);
                compiledQuery = newlyCompiled;
            }
        } catch (JsonQueryException e) {
            logger.warn("Invalid jq filter: {}", e.getMessage(), e);
            return "Invalid jq filter: " + e.getMessage();
        }

        BatchResult<String> batchResult;
        try {
            batchResult = batchProcessFiles(files, limits.maxFiles(), (file, idx) -> {
                try {
                    var contentOpt = file.read();
                    if (contentOpt.isEmpty()) return new IndexedResult<>(idx, null, null);

                    var mapper = jqMappers.get();
                    var rootScope = jqScopes.get();

                    JsonNode node = mapper.readTree(contentOpt.get());
                    List<JsonNode> out = new ArrayList<>();
                    Output outputWriter = out::add;

                    compiledQuery.apply(Scope.newChildScope(rootScope), node, outputWriter);

                    if (out.isEmpty()) return new IndexedResult<>(idx, null, null);

                    int toTake = min(out.size(), limits.matchesPerFile());
                    boolean hitLimit = out.size() > toTake;
                    String matchCountLabel = hitLimit
                            ? "first %d matches".formatted(toTake)
                            : "%d %s".formatted(toTake, toTake == 1 ? "match" : "matches");

                    List<String> outLines = new ArrayList<>(toTake + 1);
                    outLines.add("File: %s (%s)".formatted(file.toString().replace('\\', '/'), matchCountLabel));
                    for (int i = 0; i < toTake; i++) {
                        JsonNode n = out.get(i);
                        String rendered = mapper.writeValueAsString(n);
                        if (n.isContainerNode() && rendered.length() > XML_MAX_CHARS_PER_NODE) {
                            outLines.add("[JSON_TOO_LARGE]");
                            String skim = jsonSkimBfs(n, XML_MAX_CHARS_PER_NODE);
                            outLines.addAll(List.of(skim.split("\n", -1)));
                        } else {
                            outLines.add(truncateLine(rendered, 0, rendered.length()));
                        }
                    }
                    return new IndexedResult<>(idx, String.join("\n", outLines) + "\n", null);
                } catch (Exception e) {
                    String message = e.getMessage() == null ? e.toString() : e.getMessage();
                    return new IndexedResult<>(idx, null, file + ": " + message);
                }
            });
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            logger.error("Error executing jq filter", e);
            return "jq filter failed: " + message;
        }

        if (batchResult.results().isEmpty()) {
            if (!batchResult.errors().isEmpty()) {
                return "jq filter produced errors in %d of %d files: %s"
                        .formatted(
                                batchResult.errors().size(),
                                files.size(),
                                batchResult.errors().getFirst());
            }
            return "No results for jq filter.";
        }

        String output = String.join("\n", batchResult.results()).trim();
        if (batchResult.truncatedByMaxFiles()) {
            output += "\n\nTRUNCATED: reached maxFiles=%d".formatted(limits.maxFiles());
        }

        return recordResearchTokens(output);
    }

    @Tool(
            """
            Skims XML files by walking the document breadth-first (BFS) and emitting compact structural summaries
            until an output budget is reached.

            Output is grouped by file:
            <file path="path/to/file.xml">
            /root[1] <root> textLen=0 children={item:2} attrs={id="x"}
            /root[1]/item[1] <item> textLen=5 children={} attrs={id="a"}
            </file>

            Notes:
            - Namespace-agnostic: element names are summarized using their local name (prefix is ignored).
            - Output is capped to a per-file budget of 10 * MAX_CHARS_PER_LINE characters.
            - Individual lines are capped to MAX_CHARS_PER_LINE.
            """)
    public String xmlSkim(
            @P("File path or glob pattern (e.g., 'pom.xml', '**/*.xml').") String filepath,
            @P("Maximum number of files to return results for. Capped at 500.") int maxFiles)
            throws InterruptedException {
        var project = contextManager.getProject();
        var files = Completions.expandPath(project, filepath).stream()
                .filter(ProjectFile.class::isInstance)
                .map(ProjectFile.class::cast)
                .filter(ProjectFile::isText)
                .filter(pf -> "xml".equalsIgnoreCase(pf.extension()))
                .sorted()
                .toList();

        if (files.isEmpty() && (filepath.startsWith("**/") || filepath.startsWith("**\\"))) {
            files = Completions.expandPath(project, filepath.substring(3)).stream()
                    .filter(ProjectFile.class::isInstance)
                    .map(ProjectFile.class::cast)
                    .filter(ProjectFile::isText)
                    .filter(pf -> "xml".equalsIgnoreCase(pf.extension()))
                    .sorted()
                    .toList();
        }

        if (files.isEmpty()) {
            return "No XML files found matching: " + filepath;
        }

        int effectiveMaxFiles = max(
                1,
                min(
                        maxFiles <= 0 ? SEARCH_FILE_CONTENTS_MAX_FILES_DEFAULT : maxFiles,
                        SEARCH_FILE_CONTENTS_MAX_PARAMETER_VALUE));

        BatchResult<String> batchResult;
        try {
            batchResult = batchProcessFiles(files, effectiveMaxFiles, (file, idx) -> {
                try {
                    var contentOpt = file.read();
                    if (contentOpt.isEmpty()) return new IndexedResult<>(idx, null, null);

                    Document doc = parseXmlDocument(file, contentOpt.get());
                    Node root = doc.getDocumentElement();
                    if (root == null) return new IndexedResult<>(idx, null, null);

                    String skim = xmlSkimBfs(root, XML_SKIM_TOTAL_BUDGET_CHARS);
                    String block = "<file path=\"%s\">\n%s\n</file>"
                            .formatted(file.toString().replace('\\', '/'), skim);
                    return new IndexedResult<>(idx, block, null);
                } catch (Exception e) {
                    String message = e.getMessage() == null ? e.toString() : e.getMessage();
                    return new IndexedResult<>(idx, null, file + ": " + message);
                }
            });
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            logger.error("Error executing xmlSkim", e);
            return "xmlSkim failed: " + message;
        }

        if (batchResult.results().isEmpty()) {
            if (!batchResult.errors().isEmpty()) {
                return "xmlSkim produced errors in %d of %d files: %s"
                        .formatted(
                                batchResult.errors().size(),
                                files.size(),
                                batchResult.errors().getFirst());
            }
            return "No results for xmlSkim.";
        }

        String output = String.join("\n\n", batchResult.results()).trim();
        if (batchResult.truncatedByMaxFiles()) {
            output += "\n\nTRUNCATED: reached maxFiles=%d".formatted(effectiveMaxFiles);
        }
        if (!batchResult.errors().isEmpty()) {
            output += "\n\nWARNINGS: errors occurred in %d files; first: %s"
                    .formatted(batchResult.errors().size(), batchResult.errors().getFirst());
        }

        return recordResearchTokens(output);
    }

    @Tool(
            """
            Selects nodes from XML files using an XPath selector, then extracts a specific view of each match.

            Namespace handling:
            - Namespace-agnostic: simple element names like 'foo' are automatically rewritten to match elements
              regardless of namespace prefix using local-name() matching.

            Output modes (output parameter):
            - TEXT: plain text content (node.getTextContent().strip())
            - NAME: element local name
            - PATH: computed local-name() path with sibling indexes (e.g., /project[1]/dependencies[1]/dependency[2])
            - ATTR: plain text single attribute value (requires attrName)
            - ATTRS: JSONL; one JSON object per match with {path, name, attrs}
            - XML: outer XML (outerXml). If the outer XML is longer than MAX_CHARS_PER_LINE, a BFS skim of that subtree
              is returned instead (capped at MAX_CHARS_PER_LINE).

            Notes:
            - maxFiles and matchesPerFile are capped at 500 each.
            - maxFiles * matchesPerFile is forced to be <= 500.
            """)
    public String xmlSelect(
            @P("File path or glob pattern (e.g., 'pom.xml', '**/*.xml').") String filepath,
            @P("XPath selector (namespace-agnostic rewrite is applied).") String xpath,
            @P("Extraction mode: TEXT, NAME, PATH, ATTR, ATTRS, XML.") String output,
            @P("Attribute name (required when output=ATTR; ignored otherwise).") String attrName,
            @P("Maximum number of files to return results for. Capped at 500.") int maxFiles,
            @P("Maximum number of matches to return per file. Capped at 500.") int matchesPerFile)
            throws InterruptedException {
        var project = contextManager.getProject();
        var files = Completions.expandPath(project, filepath).stream()
                .filter(ProjectFile.class::isInstance)
                .map(ProjectFile.class::cast)
                .filter(ProjectFile::isText)
                .filter(pf -> "xml".equalsIgnoreCase(pf.extension()))
                .sorted()
                .toList();

        if (files.isEmpty() && (filepath.startsWith("**/") || filepath.startsWith("**\\"))) {
            files = Completions.expandPath(project, filepath.substring(3)).stream()
                    .filter(ProjectFile.class::isInstance)
                    .map(ProjectFile.class::cast)
                    .filter(ProjectFile::isText)
                    .filter(pf -> "xml".equalsIgnoreCase(pf.extension()))
                    .sorted()
                    .toList();
        }

        if (files.isEmpty()) {
            return "No XML files found matching: " + filepath;
        }

        if (xpath.isBlank()) {
            throw new IllegalArgumentException("Cannot xmlSelect: xpath is empty");
        }

        String mode = output.strip().toUpperCase(Locale.ROOT);
        if ("ATTR".equals(mode) && attrName.isBlank()) {
            throw new IllegalArgumentException("Cannot xmlSelect: attrName is required when output=ATTR");
        }

        EffectiveLimits limits = clampMaxFilesAndMatchesPerFile(maxFiles, matchesPerFile);

        String rewritten = rewriteNamespaceAgnosticXPath(xpath);
        final XPathExpression compiled;
        try {
            compiled = getOrCompileXPathExpression(rewritten);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return "Invalid XPath: " + message;
        }

        BatchResult<String> batchResult;
        try {
            batchResult = batchProcessFiles(files, limits.maxFiles(), (file, idx) -> {
                try {
                    var contentOpt = file.read();
                    if (contentOpt.isEmpty()) return new IndexedResult<>(idx, null, null);

                    Document doc = parseXmlDocument(file, contentOpt.get());

                    NodeList nodes = (NodeList) compiled.evaluate(doc, XPathConstants.NODESET);
                    int total = nodes == null ? 0 : nodes.getLength();
                    if (total == 0) return new IndexedResult<>(idx, null, null);

                    int toTake = min(total, limits.matchesPerFile());
                    boolean hitLimit = total > toTake;
                    String matchCountLabel = hitLimit
                            ? "first %d matches".formatted(toTake)
                            : "%d %s".formatted(toTake, toTake == 1 ? "match" : "matches");

                    List<String> outLines = new ArrayList<>(toTake + 1);
                    outLines.add("File: %s (%s)".formatted(file.toString().replace('\\', '/'), matchCountLabel));

                    var mapper = jqMappers.get();

                    for (int i = 0; i < toTake; i++) {
                        Node n = nodes.item(i);
                        if (n == null) continue;

                        String path = computeNodePath(n);
                        String name = localName(n);

                        switch (mode) {
                            case "TEXT" -> {
                                String text = n.getTextContent() == null
                                        ? ""
                                        : n.getTextContent().strip();
                                String line = "%s: %s".formatted(path, text);
                                outLines.add(truncateLine(line, 0, line.length()));
                            }
                            case "NAME" -> outLines.add("%s: %s".formatted(path, name));
                            case "PATH" -> outLines.add(path);
                            case "ATTR" -> {
                                String value = "";
                                if (n.getNodeType() == Node.ELEMENT_NODE && n instanceof Element el) {
                                    value = el.hasAttribute(attrName) ? el.getAttribute(attrName) : "";
                                }
                                String line = "%s @%s=\"%s\"".formatted(path, attrName, value);
                                outLines.add(truncateLine(line, 0, line.length()));
                            }
                            case "ATTRS" -> {
                                Map<String, String> attrs = Map.of();
                                if (n.getNodeType() == Node.ELEMENT_NODE && n instanceof Element el) {
                                    NamedNodeMap nnm = el.getAttributes();
                                    if (nnm != null && nnm.getLength() > 0) {
                                        Map<String, String> m = new LinkedHashMap<>();
                                        for (int a = 0; a < nnm.getLength(); a++) {
                                            Node attr = nnm.item(a);
                                            if (attr == null) continue;
                                            String aName = localName(attr);
                                            String aVal = attr.getNodeValue() == null ? "" : attr.getNodeValue();
                                            m.put(aName, capXmlAttrValue(aVal));
                                        }
                                        attrs = Map.copyOf(m);
                                    }
                                }
                                Map<String, Object> obj = Map.of("path", path, "name", name, "attrs", attrs);
                                outLines.add(mapper.writeValueAsString(obj));
                            }
                            case "XML" -> {
                                String outerStr = toOuterXml(n);
                                if (n.getNodeType() == Node.ELEMENT_NODE
                                        && outerStr.length() > XML_MAX_CHARS_PER_NODE) {
                                    outLines.add("%s: [XML_TOO_LARGE]".formatted(path));
                                    String skim = xmlSkimBfs(n, XML_MAX_CHARS_PER_NODE);
                                    outLines.addAll(List.of(skim.split("\n", -1)));
                                } else {
                                    String line = "%s: %s".formatted(path, outerStr);
                                    outLines.add(truncateLine(line, 0, line.length()));
                                }
                            }
                            default -> throw new IllegalArgumentException("Invalid output mode: " + mode);
                        }
                    }

                    return new IndexedResult<>(idx, String.join("\n", outLines) + "\n", null);
                } catch (Exception e) {
                    String message = e.getMessage() == null ? e.toString() : e.getMessage();
                    return new IndexedResult<>(idx, null, file + ": " + message);
                }
            });
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            logger.error("Error executing xmlSelect", e);
            return "xmlSelect failed: " + message;
        }

        if (batchResult.results().isEmpty()) {
            if (!batchResult.errors().isEmpty()) {
                return "xmlSelect produced errors in %d of %d files: %s"
                        .formatted(
                                batchResult.errors().size(),
                                files.size(),
                                batchResult.errors().getFirst());
            }
            return "No results for xmlSelect.";
        }

        String outResult = String.join("\n", batchResult.results()).trim();
        if (batchResult.truncatedByMaxFiles()) {
            outResult += "\n\nTRUNCATED: reached maxFiles=%d".formatted(limits.maxFiles());
        }
        if (!batchResult.errors().isEmpty()) {
            outResult += "\n\nWARNINGS: errors occurred in %d files; first: %s"
                    .formatted(batchResult.errors().size(), batchResult.errors().getFirst());
        }

        return recordResearchTokens(outResult);
    }

    @Tool(
            """
                    Returns filenames (relative to the project root) that match the given Java regular expression patterns.
                    Matching is always case-insensitive.
                    Use this to find configuration files, test data, or source files when you know part of their name.
                    """)
    public String findFilenames(
            @P("Java-style regex patterns to match against filenames.") List<String> patterns,
            @P("Maximum number of filenames to return (capped at 200).") int limit) {
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search filenames: patterns list is empty");
        }

        logger.debug("Searching filenames for patterns: {}", patterns);

        final List<Pattern> compiledPatterns;
        try {
            List<Pattern> matchingPatterns = new ArrayList<>();
            List<String> compileErrors = new ArrayList<>();
            for (String pattern : patterns) {
                boolean hasBackslash = pattern.contains("\\");
                boolean normalizedCompiled = false;

                try {
                    matchingPatterns.addAll(compilePatternsWithFlags(
                            List.of(toUnixPath(pattern)), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                    normalizedCompiled = true;
                } catch (IllegalArgumentException e) {
                    if (!hasBackslash) {
                        compileErrors.add(
                                e.getMessage() != null
                                        ? e.getMessage()
                                        : e.getClass().getSimpleName());
                    }
                }

                if (hasBackslash) {
                    try {
                        matchingPatterns.addAll(compilePatternsWithFlags(
                                List.of(pattern), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                    } catch (IllegalArgumentException e) {
                        if (!normalizedCompiled) {
                            compileErrors.add(
                                    e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName());
                        }
                    }
                }
            }

            if (!compileErrors.isEmpty()) {
                throw new IllegalArgumentException(String.join("; ", compileErrors));
            }

            compiledPatterns = List.copyOf(matchingPatterns);
        } catch (IllegalArgumentException e) {
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        if (compiledPatterns.isEmpty()) {
            throw new IllegalArgumentException("No valid patterns provided");
        }

        final List<String> allMatches;
        try {
            allMatches = contextManager.getProject().getAllFiles().stream()
                    .map(ProjectFile::toString) // Use relative path from ProjectFile
                    .map(path -> toUnixPath(path))
                    .filter(filePath -> {
                        for (Pattern pattern : compiledPatterns) {
                            if (findWithOverflowGuard(pattern, filePath)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .distinct()
                    .sorted()
                    .toList();
        } catch (RegexMatchOverflowException e) {
            logger.warn("Regex stack overflow while searching filenames with pattern {}", e.pattern(), e);
            return "Regex pattern '%s' caused StackOverflowError during filename search".formatted(e.pattern());
        }

        int effectiveLimit = min(limit <= 0 ? FILE_SEARCH_LIMIT : limit, FILE_SEARCH_LIMIT);
        boolean truncated = allMatches.size() > effectiveLimit;
        var matchingFiles = allMatches.stream().limit(effectiveLimit).toList();

        if (matchingFiles.isEmpty()) {
            return "No filenames found matching patterns: " + String.join(", ", patterns);
        }

        String prefix = "";
        if (truncated) {
            prefix = "### WARNING: Result limit reached (max " + effectiveLimit + " filenames). Showing first "
                    + effectiveLimit + " matches. " + "Retrying the same tool call will return the same results.\n\n";
        }

        return recordResearchTokens(
                prefix + "Matching filenames by common prefix:\n" + formatFilenamesByPrefix(matchingFiles));
    }

    private static boolean findWithOverflowGuard(Pattern pattern, String input) {
        try {
            return pattern.matcher(input).find();
        } catch (StackOverflowError e) {
            throw new RegexMatchOverflowException(pattern.pattern(), e);
        }
    }

    private static String formatFilenamesByPrefix(List<String> matchingFiles) {
        Map<String, List<String>> grouped = matchingFiles.stream()
                .collect(Collectors.groupingBy(SearchTools::directoryPrefix, LinkedHashMap::new, Collectors.toList()));

        return grouped.entrySet().stream()
                .map(entry -> {
                    String groupPrefix = entry.getKey();
                    String groupFiles = entry.getValue().stream()
                            .map(SearchTools::basename)
                            .map(name -> "- " + name)
                            .collect(Collectors.joining("\n"));

                    if (groupPrefix.isEmpty()) {
                        return groupFiles;
                    }
                    return "# " + groupPrefix + "\n" + groupFiles;
                })
                .collect(Collectors.joining("\n\n"));
    }

    private static String directoryPrefix(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash > 0 ? path.substring(0, lastSlash) : "";
    }

    private static String basename(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    @Tool(
            """
                    Returns the full contents of the specified files. Use this after findFilenames, findFilesContaining or searchSymbols or when you need the content of a non-code file.
                    This can be expensive for large files.
                    """)
    public String getFileContents(
            @P("List of filenames (relative to project root) to retrieve contents for.") List<String> filenames) {
        if (filenames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get file contents: filenames list is empty");
        }

        logger.debug("Getting contents for files: {}", filenames);

        StringBuilder result = new StringBuilder();
        boolean anySuccess = false;

        for (String filename : filenames.stream().distinct().toList()) {
            try {
                var file = contextManager.toFile(filename); // Use contextManager
                if (!file.exists()) {
                    logger.debug("File not found or not a regular file: {}", file);
                    continue;
                }
                var contentOpt = file.read();
                if (contentOpt.isEmpty()) {
                    logger.debug("Skipping unreadable file: {}", filename);
                    continue;
                }
                var content = contentOpt.get();
                if (!result.isEmpty()) {
                    result.append("\n\n");
                }
                result.append(
                        """
                                ```%s
                                %s
                                ```
                                """
                                .stripIndent()
                                .formatted(filename, content));
                anySuccess = true;
            } catch (Exception e) {
                logger.error("Unexpected error getting content for {}", filename, e);
            }
        }

        if (!anySuccess) {
            return "None of the requested files could be read: " + String.join(", ", filenames);
        }

        return recordResearchTokens(result.toString());
    }

    /**
     * Formats files in a directory as a comma-separated string.
     * Public static to allow reuse by BuildAgent.
     */
    public static String formatFilesInDirectory(
            Collection<ProjectFile> allFiles, Path normalizedDirectoryPath, String originalDirectoryPath) {
        var files = allFiles.stream()
                .parallel()
                .filter(file -> file.getParent().equals(normalizedDirectoryPath))
                .sorted()
                .map(ProjectFile::toString)
                .collect(Collectors.joining(", "));

        if (files.isEmpty()) {
            return "No files found in directory: " + originalDirectoryPath;
        }

        return "Files in " + originalDirectoryPath + ": " + files;
    }

    // Only includes project files. Is this what we want?
    @Tool(
            """
                    Lists files within a specified directory relative to the project root.
                    Use '.' for the root directory.
                    """)
    public String listFiles(
            @P("Directory path relative to the project root (e.g., '.', 'src/main/java')") String directoryPath) {
        if (directoryPath.isBlank()) {
            throw new IllegalArgumentException("Directory path cannot be empty");
        }

        // Normalize path for filtering (remove leading/trailing slashes, handle '.')
        var normalizedPath = Path.of(directoryPath).normalize();

        logger.debug("Listing files for directory path: '{}' (normalized to `{}`)", directoryPath, normalizedPath);

        var result = formatFilesInDirectory(contextManager.getProject().getAllFiles(), normalizedPath, directoryPath);
        if (result.startsWith("No files found")) {
            return result;
        }
        return recordResearchTokens(result);
    }

    @Tool(
            """
                    Returns a hierarchical "bag of identifiers" summary for all files within a specified directory.
                    This provides a quick overview of class members and nested structures by listing names only;
                    it is significantly less detailed than getFileSummaries as it omits full signatures and field types.
                    Subdirectories are listed at the top.
                    If the summary of all files is too large, it returns only the file and directory names.
                    """)
    public String skimDirectory(
            @P("Directory path relative to the project root (e.g., '.', 'src/main/java').") String directoryPath) {
        if (directoryPath.isBlank()) {
            throw new IllegalArgumentException("Directory path cannot be empty");
        }

        var project = contextManager.getProject();
        var analyzer = getAnalyzer();
        var targetDir = Path.of(directoryPath).normalize();

        // Check if we're inside the dependencies directory - don't filter by gitignore there
        Path dependenciesPath = Path.of(AbstractProject.BROKK_DIR, AbstractProject.DEPENDENCIES_DIR);
        boolean isInDependencies = targetDir.startsWith(dependenciesPath);

        Path absTargetDir = project.getRoot().resolve(targetDir);
        File[] fsItems = absTargetDir.toFile().listFiles();

        if (fsItems == null || fsItems.length == 0) {
            return "No files or directories found in: " + directoryPath;
        }

        List<String> subDirs = new ArrayList<>();
        List<ProjectFile> children = new ArrayList<>();

        for (File item : fsItems) {
            String name = item.getName();
            if (!isInDependencies && project.isGitignored(targetDir.resolve(name))) {
                continue;
            }

            if (item.isDirectory()) {
                subDirs.add(name + "/");
            } else {
                children.add(new ProjectFile(project.getRoot(), targetDir.resolve(name)));
            }
        }

        if (subDirs.isEmpty() && children.isEmpty()) {
            return "No files or directories found in: " + directoryPath;
        }

        children.sort(ProjectFile::compareTo);
        subDirs.sort(String::compareTo);

        int MAX_TOKENS = 12_800; // ~10% of 128k

        // Try full skim (subdirs + symbol summaries)
        StringBuilder fullSkim = new StringBuilder();
        int fullTokens = 0;
        boolean fullTruncated = false;

        if (!subDirs.isEmpty()) {
            String subDirText = "<subdirectories>\n  " + String.join(", ", subDirs) + "\n</subdirectories>\n\n";
            fullTokens += Messages.getApproximateTokens(subDirText);
            fullSkim.append(subDirText);
        }

        for (var file : children) {
            String identifiers = analyzer.summarizeSymbols(file);
            String content = identifiers.isBlank() ? "- (no symbols found)" : identifiers;
            String fileBlock = "<file path=\"" + file.toString().replace('\\', '/') + "\">\n" + content + "\n</file>\n";

            int blockTokens = Messages.getApproximateTokens(fileBlock);
            if (fullTokens + blockTokens > MAX_TOKENS) {
                fullTruncated = true;
                break;
            }

            fullSkim.append(fileBlock);
            fullTokens += blockTokens;
        }

        if (!fullTruncated) {
            return recordResearchTokens(fullSkim.toString());
        }

        // Downgrade to filename-only list
        StringBuilder filenamesOnly = new StringBuilder();
        filenamesOnly.append("### WARNING: The symbol summary for this directory is too large for the token limit. "
                + "Switching to filename-only listing.\n\n");

        if (!subDirs.isEmpty()) {
            filenamesOnly
                    .append("<subdirectories>\n  ")
                    .append(String.join(", ", subDirs))
                    .append("\n</subdirectories>\n\n");
        }

        String filesCdl = children.stream().map(ProjectFile::toString).collect(Collectors.joining(", "));
        filenamesOnly.append("Files: ").append(filesCdl);

        String filenamesResult = filenamesOnly.toString();
        if (Messages.getApproximateTokens(filenamesResult) <= MAX_TOKENS) {
            return recordResearchTokens(filenamesResult);
        }

        // Final fallback: Too many files to even list names
        return """
                ### ERROR: Too many files to skim. \
                The directory contains %d subdirectories and %d files, which exceeds the display limit even without summaries. \
                Please try listing specific subdirectories or using search tools to narrow your scope.\
                """
                .formatted(subDirs.size(), children.size());
    }

    private static <T> T runInSearchToolsPool(Callable<T> callable) throws InterruptedException {
        Future<T> future = searchToolsPool.submit(callable);
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error er) {
                throw er;
            }
            throw new RuntimeException("Error executing SearchTools task", cause);
        }
    }
}
