package ai.brokk.tools;

import static ai.brokk.project.FileFilteringService.toUnixPath;
import static java.lang.Math.max;

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
import ai.brokk.util.Messages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Splitter;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
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
import org.w3c.dom.NodeList;

/**
 * Contains tool implementations related to code analysis and searching, designed to be registered with the
 * ToolRegistry.
 */
public class SearchTools {
    private static final Logger logger = LogManager.getLogger(SearchTools.class);
    private static final Pattern LINE_SPLIT = Pattern.compile("\\r?\\n");
    private static final Pattern STRIP_PARAMS_PATTERN = Pattern.compile("(?<=\\w)\\([^)]*\\)$");

    private static final int SEARCH_TOOLS_PARALLELISM =
            max(2, Runtime.getRuntime().availableProcessors());

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
                    .maximumSize(4 * Runtime.getRuntime().availableProcessors())
                    .build());

    private static final ThreadLocal<Cache<String, Pattern>> searchPatterns =
            ThreadLocal.withInitial(() -> Caffeine.newBuilder()
                    .maximumSize(64 * Runtime.getRuntime().availableProcessors())
                    .build());

    private static final ThreadLocal<ObjectMapper> jqMappers = ThreadLocal.withInitial(ObjectMapper::new);

    private static final ThreadLocal<Scope> jqScopes = ThreadLocal.withInitial(() -> {
        Scope rootScope = Scope.newEmptyScope();
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, rootScope);
        return rootScope;
    });

    private static final ThreadLocal<Cache<String, JsonQuery>> jqQueries =
            ThreadLocal.withInitial(() -> Caffeine.newBuilder()
                    .maximumSize(4 * Runtime.getRuntime().availableProcessors())
                    .build());

    private final IContextManager contextManager; // Needed for file operations

    public SearchTools(IContextManager contextManager) {
        this.contextManager = contextManager;
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
        return String.join("\n\n", allSkeletons);
    }

    // --- Tool Methods requiring analyzer

    @Tool(
            """
            Search for symbols (class/function/field/module definitions) using static analysis.
            ONLY returns symbol definitions (declarations).
            DO NOT use for usages/call sites/instantiation/access patterns — use scanUsages or findFilesContaining.
            Output is grouped by file, then by symbol kind within each file.

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
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning,
            @P("Include test files in results. Default: false.") boolean includeTests) {
        // Sanitize patterns: LLM might add `()` to symbols, Joern regex usually doesn't want that unless intentional.
        patterns = stripParams(patterns);
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search definitions: patterns list is empty");
        }
        if (reasoning.isBlank()) {
            // Tolerate missing reasoning for now, maybe make mandatory later
            logger.warn("Missing reasoning for searchSymbols call");
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

        // Group by file, then by kind within each file
        var fileGroups = allDefinitions.stream()
                .collect(Collectors.groupingBy(
                        cu -> toUnixPath(cu.source().toString()),
                        Collectors.groupingBy(cu -> cu.kind().name())));

        // Build output: sorted files, sorted kinds per file, sorted symbols per kind
        var result = new StringBuilder();
        fileGroups.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(fileEntry -> {
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

        return result.toString();
    }

    @Tool(
            """
            Returns the call sites where symbols are used and three examples of full call site source. Use this to discover how classes, methods, or fields are actually used throughout the codebase.
            Use this for questions like “how is X used/accessed/obtained/wired”.
            If you don’t know the fully qualified symbol name, call searchSymbols once to get it.
            """)
    public String scanUsages(
            @P("Fully qualified symbol names (package name, class name, optional member name) to find usages for")
                    List<String> symbols,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning,
            @P("Include call sites in test files in results.") boolean includeTests) {
        // Sanitize symbols: remove potential `(params)` suffix from LLM.
        symbols = stripParams(symbols);
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("Cannot search usages: symbols list is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for getUsages call");
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

        return String.join("\n\n", results);
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

        return result;
    }

    @Tool(
            """
                    Returns the full source code of classes.
                    This is expensive, so prefer requesting skeletons or method sources when possible.
                    Use this when you need the complete implementation details, or if you think multiple methods in the classes may be relevant.
                    """)
    public String getClassSources(
            @P("Fully qualified class names to retrieve the full source code for") List<String> classNames) {
        // Sanitize classNames: remove potential `(params)` suffix from LLM.
        classNames = stripParams(classNames);
        if (classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get class sources: class names list is empty");
        }

        StringBuilder result = new StringBuilder();
        Set<String> added = new HashSet<>();

        var analyzer = getAnalyzer();
        classNames.stream().distinct().filter(s -> !s.isBlank()).forEach(className -> {
            var cuOpt = analyzer.getDefinitions(className).stream()
                    .filter(CodeUnit::isClass)
                    .findFirst();
            if (cuOpt.isPresent()) {
                var cu = cuOpt.get();
                if (added.add(cu.fqName())) {
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
        });

        if (result.isEmpty()) {
            return "No sources found for classes: " + String.join(", ", classNames);
        }

        return result.toString();
    }

    @Tool(
            """
                    Returns the file paths (relative to the project root) where the specified symbols are defined.
                    Use this to locate where interesting symbols live,
                    then use getFileSummaries to get an overview of those files.
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

        StringBuilder result = new StringBuilder();
        result.append(String.join("\n", locationMappings));

        if (!notFound.isEmpty()) {
            result.append("\n\nNot found: ").append(String.join(", ", notFound));
        }

        return result.toString();
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

        return result.toString();
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
            @P("Maximum number of log entries to return (capped at 100).") int limit,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for getGitLog call");
        }

        var repo = contextManager.getRepo();

        // Cap limit at 100 and ensure at least 1
        int effectiveLimit = max(1, Math.min(limit, 100));

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
            return sb.toString();
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
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("Cannot search commit messages: pattern is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for searchGitCommitMessages call");
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

        List<CommitInfo> matchingCommits;
        try {
            matchingCommits = gitRepo.searchCommits(pattern);
        } catch (GitAPIException e) {
            logger.error("Error searching commit messages", e);
            return "Error searching commit messages: " + e.getMessage();
        }

        if (matchingCommits.isEmpty()) {
            return "No commit messages found matching pattern: " + pattern;
        }

        StringBuilder resultBuilder = new StringBuilder();
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

        return resultBuilder.toString();
    }

    // --- Text search tools

    @Tool(
            """
                    Returns file names (paths relative to the project root) whose text contents match Java regular expression patterns.
                    This is slower than searchSymbols but can find references to external dependencies and comment strings.
                    """)
    public String findFilesContaining(
            @P(
                            "Java-style regex patterns to search for within file contents. Unlike searchSymbols this does not automatically include any implicit anchors or case insensitivity.")
                    List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning)
            throws InterruptedException {
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search substrings: patterns list is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for findFilesContaining call");
        }

        logger.debug("Searching file contents for patterns: {}", patterns);

        final List<Pattern> compiledPatterns;
        try {
            compiledPatterns = compilePatterns(patterns);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }

        if (compiledPatterns.isEmpty()) {
            throw new IllegalArgumentException("No valid patterns provided");
        }

        var searchResult = findFilesContainingPatterns(
                compiledPatterns, contextManager.getProject().getAllFiles());
        var matchingFilenames = searchResult.matches().stream()
                .map(ProjectFile::toString)
                .sorted()
                .toList();

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

        var msg = "Files with content matching patterns: " + String.join(", ", matchingFilenames);
        if (!searchResult.errors().isEmpty()) {
            msg += " (warnings: errors occurred reading %d files; first: %s)"
                    .formatted(
                            searchResult.errors().size(), searchResult.errors().getFirst());
        }
        logger.debug(msg);
        return msg;
    }

    public record FindFilesContainingResult(Set<ProjectFile> matches, List<String> errors) {}

    private record FilePatternSearchResult(@Nullable ProjectFile match, @Nullable String error) {}

    public static FindFilesContainingResult findFilesContainingPatterns(
            List<Pattern> patterns, Set<ProjectFile> filesToSearch) throws InterruptedException {
        if (patterns.isEmpty()) {
            return new FindFilesContainingResult(Set.of(), List.of());
        }

        final List<FilePatternSearchResult> results;
        try {
            results = runInSearchToolsPool(() -> filesToSearch.parallelStream()
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
                            boolean matched = patterns.stream()
                                    .anyMatch(p -> p.matcher(fileContents).find());

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

    @Tool(
            """
            Searches for a regex pattern within file contents across files matching a glob pattern.
            Provides grep-like output with line numbers and optional context lines.
            """)
    public String searchFileContents(
            @P("Java-style regex pattern to search for.") String pattern,
            @P("Glob pattern for file paths (e.g., '**/AGENTS.md', 'src/**/*.java').") String filepath,
            @P("Number of context lines to show around each match (0-50).") int contextLines)
            throws InterruptedException {
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

        final List<ProjectFile> filesToSearch = files;

        int clampedContext = max(0, Math.min(contextLines, 50));
        final List<Pattern> compiledPatterns;
        try {
            compiledPatterns = compilePatterns(List.of(pattern));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }

        if (compiledPatterns.isEmpty()) {
            return "No valid patterns provided";
        }

        var lineSplitter = Splitter.on(LINE_SPLIT);

        final List<String> fileResults;
        try {
            fileResults = runInSearchToolsPool(() -> filesToSearch.parallelStream()
                    .map(file -> {
                        var contentOpt = file.read();
                        if (contentOpt.isEmpty()) return null;

                        List<String> lines = lineSplitter.splitToList(contentOpt.get());
                        List<Integer> matchLines = new ArrayList<>();

                        for (int i = 0; i < lines.size(); i++) {
                            for (var p : compiledPatterns) {
                                if (p.matcher(lines.get(i)).find()) {
                                    matchLines.add(i);
                                    break;
                                }
                            }
                        }

                        if (matchLines.isEmpty()) return null;

                        String filePath = file.toString().replace('\\', '/');
                        List<String> outLines = new ArrayList<>();
                        outLines.add(filePath);

                        Set<Integer> printed = new HashSet<>();
                        for (int matchIdx : matchLines) {
                            int start = max(0, matchIdx - clampedContext);
                            int end = Math.min(lines.size() - 1, matchIdx + clampedContext);

                            for (int i = start; i <= end; i++) {
                                if (printed.add(i)) {
                                    outLines.add("%d: %s".formatted(i + 1, lines.get(i)));
                                }
                            }
                        }

                        return String.join("\n", outLines) + "\n";
                    })
                    .filter(Objects::nonNull)
                    .map(Objects::requireNonNull)
                    .toList());
        } catch (RuntimeException e) {
            logger.error("Error searching file contents for '{}' in '{}'", pattern, filepath, e);
            return "Error searching file contents: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }

        if (fileResults.isEmpty()) {
            return "No matches found for pattern '" + pattern + "' in files matching '" + filepath + "'";
        }

        return String.join("\n", fileResults).trim();
    }

    @Tool(
            """
            Executes an XPath query against XML files.
            The tool is namespace-agnostic: simple element names like 'foo' are automatically rewritten
            to match elements regardless of their namespace prefix (using local-name() matching).
            Returns the text content of matching nodes.
            """)
    public String xpathQuery(
            @P("File path or glob pattern (e.g., 'pom.xml', '**/config/*.xml').") String filepath,
            @P("XPath expression to evaluate.") String xpath)
            throws InterruptedException {
        var project = contextManager.getProject();
        var files = Completions.expandPath(project, filepath).stream()
                .filter(ProjectFile.class::isInstance)
                .map(ProjectFile.class::cast)
                .filter(pf -> pf.isText() && "xml".equalsIgnoreCase(pf.extension()))
                .sorted()
                .toList();

        if (files.isEmpty()) {
            return "No XML files found matching: " + filepath;
        }

        String effectiveXpath = xpath;
        if (!xpath.contains("local-name()")) {
            // Rewrite simple steps: foo -> *[local-name()='foo']
            effectiveXpath = Pattern.compile("(?<=^|/)(?![@*])([a-zA-Z0-9_-]+)(?=$|/|\\[)")
                    .matcher(xpath)
                    .replaceAll("*[local-name()='$1']");
        }

        final String finalEffectiveXpath = effectiveXpath;

        List<String> fileResults = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        record XPathResult(@Nullable String output, @Nullable String error) {}

        final List<XPathResult> results;
        try {
            results = runInSearchToolsPool(() -> files.parallelStream()
                    .map(file -> {
                        try {
                            var contentOpt = file.read();
                            if (contentOpt.isEmpty()) return new XPathResult(null, null);

                            var docBuilder = TL_XML_DOC_BUILDER.get();
                            docBuilder.reset();

                            Document doc = docBuilder.parse(
                                    new ByteArrayInputStream(contentOpt.get().getBytes(StandardCharsets.UTF_8)));

                            XPathExpression xpe = getOrCompileXPathExpression(finalEffectiveXpath);
                            NodeList nodes = (NodeList) xpe.evaluate(doc, XPathConstants.NODESET);
                            if (nodes.getLength() == 0) return new XPathResult(null, null);

                            String header = "File: %s (%d matches)"
                                    .formatted(file.toString().replace('\\', '/'), nodes.getLength());
                            List<String> outLines = new ArrayList<>();
                            outLines.add(header);

                            for (int i = 0; i < nodes.getLength(); i++) {
                                String val = nodes.item(i).getTextContent().trim();
                                if (!val.isEmpty()) {
                                    outLines.add("  [%d]: %s".formatted(i + 1, val));
                                }
                            }

                            return new XPathResult(String.join("\n", outLines) + "\n", null);
                        } catch (Exception e) {
                            String message = e.getMessage() == null ? e.toString() : e.getMessage();
                            return new XPathResult(null, file + ": " + message);
                        }
                    })
                    .toList());
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            logger.error("Error executing XPath query", e);
            return "XPath query failed: " + message;
        }

        for (var res : results) {
            if (res.output() != null) fileResults.add(res.output());
            if (res.error() != null) errors.add(res.error());
        }

        if (fileResults.isEmpty()) {
            if (!errors.isEmpty()) {
                return "XPath query produced errors in %d of %d files: %s"
                        .formatted(errors.size(), files.size(), errors.getFirst());
            }
            return "No results for XPath query.";
        }

        return String.join("\n", fileResults).trim();
    }

    @Tool(
            """
            Executes a jq filter against JSON files using jackson-jq.
            Results are always returned as compact JSON strings.
            Multiple results are separated by newlines.
            """)
    public String jq(
            @P("File path or glob pattern (e.g., 'package.json', '**/data/*.json').") String filepath,
            @P("jq filter to apply.") String filter)
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

        List<String> fileResults = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        record JqResult(@Nullable String output, @Nullable String error) {}

        final List<JqResult> results;
        try {
            results = runInSearchToolsPool(() -> files.parallelStream()
                    .map(file -> {
                        try {
                            var contentOpt = file.read();
                            if (contentOpt.isEmpty()) return new JqResult(null, null);

                            var mapper = jqMappers.get();
                            var rootScope = jqScopes.get();

                            JsonNode node = mapper.readTree(contentOpt.get());
                            List<JsonNode> out = new ArrayList<>();
                            Output output = out::add;

                            compiledQuery.apply(Scope.newChildScope(rootScope), node, output);

                            if (out.isEmpty()) return new JqResult(null, null);

                            List<String> outLines = new ArrayList<>();
                            outLines.add("File: " + file.toString().replace('\\', '/'));
                            for (JsonNode res : out) {
                                outLines.add(mapper.writeValueAsString(res));
                            }

                            return new JqResult(String.join("\n", outLines) + "\n", null);
                        } catch (Exception e) {
                            String message = e.getMessage() == null ? e.toString() : e.getMessage();
                            return new JqResult(null, file + ": " + message);
                        }
                    })
                    .toList());
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            logger.error("Error executing jq filter", e);
            return "jq filter failed: " + message;
        }

        for (var res : results) {
            if (res.output() != null) fileResults.add(res.output());
            if (res.error() != null) errors.add(res.error());
        }

        if (fileResults.isEmpty()) {
            if (!errors.isEmpty()) {
                return "jq filter produced errors in %d of %d files: %s"
                        .formatted(errors.size(), files.size(), errors.getFirst());
            }
            return "No results for jq filter.";
        }

        return String.join("\n", fileResults).trim();
    }

    @Tool(
            """
                    Returns filenames (relative to the project root) that match the given Java regular expression patterns.
                    Use this to find configuration files, test data, or source files when you know part of their name.
                    """)
    public String findFilenames(
            @P("Java-style regex patterns to match against filenames.") List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search filenames: patterns list is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for findFilenames call");
        }

        logger.debug("Searching filenames for patterns: {}", patterns);

        final List<Pattern> compiledPatterns;
        try {
            compiledPatterns = compilePatterns(patterns);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }

        if (compiledPatterns.isEmpty()) {
            throw new IllegalArgumentException("No valid patterns provided");
        }

        var matchingFiles = contextManager.getProject().getAllFiles().stream()
                .map(ProjectFile::toString) // Use relative path from ProjectFile
                .filter(filePath -> {
                    // Normalise to forward slashes so regex like "frontend-mop/.*\\.svelte"
                    // work on Windows paths containing back-slashes.
                    String unixPath = toUnixPath(filePath);
                    for (Pattern pattern : compiledPatterns) {
                        if (pattern.matcher(unixPath).find()) {
                            return true;
                        }
                    }
                    return false;
                })
                .sorted()
                .toList();

        if (matchingFiles.isEmpty()) {
            return "No filenames found matching patterns: " + String.join(", ", patterns);
        }

        return "Matching filenames: " + String.join(", ", matchingFiles);
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

        return result.toString();
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

        return formatFilesInDirectory(contextManager.getProject().getAllFiles(), normalizedPath, directoryPath);
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
            @P("Directory path relative to the project root (e.g., '.', 'src/main/java').") String directoryPath,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        if (directoryPath.isBlank()) {
            throw new IllegalArgumentException("Directory path cannot be empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for skimDirectory call");
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

        StringBuilder sb = new StringBuilder();
        if (!subDirs.isEmpty()) {
            sb.append("<subdirectories>\n  ")
                    .append(subDirs.stream().sorted().collect(Collectors.joining(", ")))
                    .append("\n</subdirectories>\n\n");
        }

        int totalTokens = Messages.getApproximateTokens(sb.toString());
        int maxTokens = 12800; // ~10% of 128k
        boolean tooLarge = false;

        StringBuilder fileSummaries = new StringBuilder();
        children.sort(ProjectFile::compareTo);
        for (var file : children) {
            String identifiers = analyzer.summarizeSymbols(file);
            String content = identifiers.isBlank() ? "- (no symbols found)" : identifiers;
            String fileBlock = "<file path=\"" + file.toString().replace('\\', '/') + "\">\n" + content + "\n</file>\n";

            totalTokens += Messages.getApproximateTokens(fileBlock);
            if (totalTokens > maxTokens) {
                tooLarge = true;
                break;
            }
            fileSummaries.append(fileBlock);
        }

        if (tooLarge) {
            String fileList = children.stream().map(ProjectFile::getFileName).collect(Collectors.joining("\n"));
            return sb.append("The directory summary is too large. Files:\n")
                    .append(fileList)
                    .toString();
        }

        if (fileSummaries.isEmpty() && subDirs.isEmpty()) {
            return "No files or directories found in: " + directoryPath;
        }

        return sb.append(fileSummaries).toString();
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
