package ai.brokk.analyzer;

import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.*;

import ai.brokk.analyzer.cache.AnalyzerCache;
import ai.brokk.analyzer.typescript.TypeScriptTreeSitterNodeTypes;
import ai.brokk.analyzer.usages.ExportIndex;
import ai.brokk.analyzer.usages.ImportBinder;
import ai.brokk.analyzer.usages.ReferenceCandidate;
import ai.brokk.analyzer.usages.ReferenceKind;
import ai.brokk.project.ICoreProject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Splitter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;
import org.treesitter.TSPoint;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TSTreeCursor;

/**
 * Shared base class for JavaScript and TypeScript analyzers.
 * Centralizes module resolution and import analysis logic.
 */
public abstract class JsTsAnalyzer extends TreeSitterAnalyzer implements ImportAnalysisProvider {

    protected record ModulePathKey(ProjectFile importingFile, String modulePath) {}

    protected static final List<String> KNOWN_EXTENSIONS = List.of(".js", ".jsx", ".ts", ".tsx");

    private static final Pattern ES6_IMPORT_PATTERN = Pattern.compile("from\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern ES6_SIDE_EFFECT_IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern CJS_REQUIRE_PATTERN = Pattern.compile("require\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Set<String> JS_LOG_BARE_NAMES = Set.of("log", "warn", "error", "exception");
    private static final Set<String> JS_LOG_RECEIVER_NAMES = Set.of("log", "logger", "console");
    private static final Set<String> JS_LOG_METHOD_NAMES = Set.of("log", "warn", "error", "exception");
    private static final Set<String> CLONE_AST_IDENTIFIER_TYPES = Set.copyOf(new HashSet<>(List.of(
            IDENTIFIER, TypeScriptTreeSitterNodeTypes.IDENTIFIER, TypeScriptTreeSitterNodeTypes.PROPERTY_IDENTIFIER)));
    private static final Set<String> CLONE_AST_STRING_TYPES = Set.copyOf(new HashSet<>(List.of(
            STRING,
            TEMPLATE_STRING,
            TypeScriptTreeSitterNodeTypes.STRING,
            TypeScriptTreeSitterNodeTypes.TEMPLATE_STRING)));
    private static final Set<String> CLONE_AST_NUMBER_TYPES =
            Set.copyOf(new HashSet<>(List.of(NUMBER, TypeScriptTreeSitterNodeTypes.NUMBER)));
    private static final Set<String> CLONE_AST_IGNORED_TYPES = Set.of(
            TypeScriptTreeSitterNodeTypes.ACCESSIBILITY_MODIFIER,
            TypeScriptTreeSitterNodeTypes.MODIFIERS,
            TypeScriptTreeSitterNodeTypes.TYPE_PARAMETERS);

    private final Cache<ModulePathKey, Optional<ProjectFile>> moduleResolutionCache =
            Caffeine.newBuilder().maximumSize(10_000).build();

    protected JsTsAnalyzer(ICoreProject project, Language language) {
        super(project, language);
    }

    protected JsTsAnalyzer(ICoreProject project, Language language, ProgressListener listener) {
        super(project, language, listener);
    }

    protected JsTsAnalyzer(ICoreProject project, Language language, AnalyzerState state, ProgressListener listener) {
        this(project, language, state, listener, null);
    }

    protected JsTsAnalyzer(
            ICoreProject project,
            Language language,
            AnalyzerState state,
            ProgressListener listener,
            @Nullable AnalyzerCache cache) {
        super(project, language, state, listener, cache);
    }

    /**
     * Computes and caches an index of exports for the given file, including ESM re-exports.
     */
    public ExportIndex exportIndexOf(ProjectFile file) {
        ExportIndex cached = cache().exportIndex().get(file);
        if (cached != null) {
            return cached;
        }

        ExportIndex computed = withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return ExportIndex.empty();
                    }
                    return withSource(file, sc -> extractExportIndex(root, sc), ExportIndex.empty());
                },
                ExportIndex.empty());

        cache().exportIndex().put(file, computed);
        return computed;
    }

    /**
     * Computes and caches a mapping of local import bindings for the given file.
     */
    public ImportBinder importBinderOf(ProjectFile file) {
        ImportBinder cached = cache().importBinder().get(file);
        if (cached != null) {
            return cached;
        }

        var importStatements = importStatementsOf(file);
        if (importStatements.isEmpty()) {
            var empty = ImportBinder.empty();
            cache().importBinder().put(file, empty);
            return empty;
        }

        var bindings = new java.util.HashMap<String, ImportBinder.ImportBinding>();

        for (String stmt : importStatements) {
            var modulePathOpt = extractModulePathFromImport(stmt);
            if (modulePathOpt.isEmpty()) {
                continue;
            }
            String moduleSpecifier = modulePathOpt.get();

            // ESM import binder parsing is intentionally "shallow" and flow-insensitive.
            // We only need stable local-binding -> imported-name mapping; CommonJS is a follow-up.
            String normalized = stmt.replace('\n', ' ').replace('\r', ' ').strip();

            // import type { Foo as Bar } from "./x";
            if (normalized.startsWith("import type ")) {
                normalized = "import " + normalized.substring("import type ".length());
            }

            // import * as NS from "./x";
            var nsMatcher = Pattern.compile("\\bimport\\s+\\*\\s+as\\s+([A-Za-z_$][\\w$]*)\\b")
                    .matcher(normalized);
            if (nsMatcher.find()) {
                String local = nsMatcher.group(1);
                bindings.put(
                        local, new ImportBinder.ImportBinding(moduleSpecifier, ImportBinder.ImportKind.NAMESPACE, "*"));
            }

            // import Default from "./x"; (possibly with , { named } following)
            var defaultMatcher = Pattern.compile("\\bimport\\s+([A-Za-z_$][\\w$]*)\\b\\s*(,|from\\b)")
                    .matcher(normalized);
            if (defaultMatcher.find()) {
                String local = defaultMatcher.group(1);
                bindings.put(
                        local,
                        new ImportBinder.ImportBinding(moduleSpecifier, ImportBinder.ImportKind.DEFAULT, "default"));
            }

            // import { foo as bar, baz } from "./x";
            var namedMatcher = Pattern.compile("\\bimport\\s*\\{([^}]*)\\}").matcher(normalized);
            if (namedMatcher.find()) {
                String inside = namedMatcher.group(1);
                for (String item :
                        Splitter.on(',').trimResults().omitEmptyStrings().splitToList(inside)) {
                    if (item.isEmpty()) continue;
                    if (item.startsWith("type ")) {
                        item = item.substring("type ".length()).strip();
                    }
                    String imported;
                    String local;
                    int asIdx = item.indexOf(" as ");
                    if (asIdx >= 0) {
                        imported = item.substring(0, asIdx).strip();
                        local = item.substring(asIdx + " as ".length()).strip();
                    } else {
                        imported = item;
                        local = item;
                    }
                    if (!imported.isEmpty() && !local.isEmpty()) {
                        bindings.put(
                                local,
                                new ImportBinder.ImportBinding(
                                        moduleSpecifier, ImportBinder.ImportKind.NAMED, imported));
                    }
                }
            }
        }

        var computed = new ImportBinder(Map.copyOf(bindings));
        cache().importBinder().put(file, computed);
        return computed;
    }

    /**
     * Extracts flow-insensitive candidates for exported-symbol usage analysis, based on the given import binder.
     */
    public Set<ReferenceCandidate> exportUsageCandidatesOf(ProjectFile file, ImportBinder binder) {
        Set<ReferenceCandidate> cached = cache().references().get(file);
        if (cached != null) {
            return cached;
        }

        if (binder.bindings().isEmpty()) {
            cache().references().put(file, Set.<ReferenceCandidate>of());
            return Set.<ReferenceCandidate>of();
        }

        Set<ReferenceCandidate> computed = withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) return Set.<ReferenceCandidate>of();
                    return withSource(
                            file,
                            sc -> extractExportUsageCandidates(file, root, sc, binder),
                            Set.<ReferenceCandidate>of());
                },
                Set.<ReferenceCandidate>of());

        cache().references().put(file, computed);
        return computed;
    }

    /**
     * Best-effort module resolution for JS/TS ESM specifiers.
     */
    public Optional<ProjectFile> resolveEsmModule(ProjectFile importingFile, String moduleSpecifier) {
        Path root = getProject().getRoot();
        Set<Path> absolutePaths =
                getProject().getAllFiles().stream().map(ProjectFile::absPath).collect(Collectors.toSet());
        return Optional.ofNullable(
                resolveJavaScriptLikeModulePath(root, absolutePaths, importingFile, moduleSpecifier));
    }

    private ExportIndex extractExportIndex(TSNode root, SourceContent sc) {
        var exportsByName = new java.util.HashMap<String, ExportIndex.ExportEntry>();
        var exportStars = new java.util.ArrayList<ExportIndex.ReexportStar>();
        var extendsEdges = new java.util.LinkedHashSet<ExportIndex.ClassExtendsEdge>();

        String queryStr =
                """
            (export_statement
              declaration: [
                (function_declaration name: (identifier) @export.local)
                (class_declaration name: (_) @export.local)
                (lexical_declaration (variable_declarator name: (identifier) @export.local))
                (variable_declaration (variable_declarator name: (identifier) @export.local))
              ])

            (export_statement
              (export_clause
                (export_specifier
                  name: (_) @export.spec.name
                  alias: (_)? @export.spec.alias))
              (string) @export.spec.source)

            (export_statement
              (export_clause
                (export_specifier
                  name: (_) @export.localspec.name
                  alias: (_)? @export.localspec.alias)))

            (export_statement
              "*" @export.star
              (string) @export.star.source)

            (export_statement
              "default" @export.default
              declaration: [
                (identifier) @export.default.name
                (class_declaration name: (_) @export.default.name)
                (function_declaration name: (identifier) @export.default.name)
              ]?)

            (class_declaration
              name: (_) @class.name
              (class_heritage
                (extends_clause value: (_) @class.extends)))
            """;

        try (TSQuery query = new TSQuery(getTSLanguage(), queryStr);
                TSQueryCursor cursor = new TSQueryCursor()) {
            cursor.exec(query, root, sc.text());
            TSQueryMatch match = new TSQueryMatch();
            while (cursor.nextMatch(match)) {
                TSNode exportLocal = null;
                TSNode specName = null;
                TSNode specAlias = null;
                TSNode specSource = null;
                TSNode localSpecName = null;
                TSNode localSpecAlias = null;
                TSNode starSource = null;
                boolean isDefault = false;
                TSNode defaultName = null;
                TSNode className = null;
                TSNode classExtends = null;

                for (TSQueryCapture cap : match.getCaptures()) {
                    String name = query.getCaptureNameForId(cap.getIndex());
                    TSNode node = cap.getNode();
                    if ("export.local".equals(name)) exportLocal = node;
                    else if ("export.spec.name".equals(name)) specName = node;
                    else if ("export.spec.alias".equals(name)) specAlias = node;
                    else if ("export.spec.source".equals(name)) specSource = node;
                    else if ("export.localspec.name".equals(name)) localSpecName = node;
                    else if ("export.localspec.alias".equals(name)) localSpecAlias = node;
                    else if ("export.star.source".equals(name)) starSource = node;
                    else if ("export.default".equals(name)) isDefault = true;
                    else if ("export.default.name".equals(name)) defaultName = node;
                    else if ("class.name".equals(name)) className = node;
                    else if ("class.extends".equals(name)) classExtends = node;
                }

                if (exportLocal != null) {
                    String localName = sc.substringFrom(exportLocal).strip();
                    exportsByName.put(localName, new ExportIndex.LocalExport(localName));
                }

                if (specName != null && specSource != null) {
                    String imported = sc.substringFrom(specName).strip();
                    String exported =
                            specAlias != null ? sc.substringFrom(specAlias).strip() : imported;
                    String module = unquote(sc.substringFrom(specSource).strip());
                    exportsByName.put(exported, new ExportIndex.ReexportedNamed(module, imported));
                }

                if (localSpecName != null) {
                    String localName = sc.substringFrom(localSpecName).strip();
                    String exported = localSpecAlias != null
                            ? sc.substringFrom(localSpecAlias).strip()
                            : localName;
                    exportsByName.put(exported, new ExportIndex.LocalExport(localName));
                }

                if (starSource != null) {
                    exportStars.add(new ExportIndex.ReexportStar(
                            unquote(sc.substringFrom(starSource).strip())));
                }

                if (isDefault) {
                    String localName =
                            defaultName != null ? sc.substringFrom(defaultName).strip() : null;
                    exportsByName.put("default", new ExportIndex.DefaultExport(localName));
                }

                if (className != null && classExtends != null) {
                    extendsEdges.add(new ExportIndex.ClassExtendsEdge(
                            sc.substringFrom(className).strip(),
                            sc.substringFrom(classExtends).strip()));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract export index for JS/TS file", e);
        }

        // Re-exports are worth handling with a shallow regex pass as well: the AST shape differs subtly across
        // TS/JS grammars and we only need stable name/module mapping for v1.
        String text = sc.text();

        var reexportNamed = Pattern.compile("export\\s*\\{([^}]*)\\}\\s*from\\s*['\\\"]([^'\\\"]+)['\\\"]")
                .matcher(text);
        while (reexportNamed.find()) {
            String inside = reexportNamed.group(1);
            String module = reexportNamed.group(2);
            for (String item : Splitter.on(',').trimResults().omitEmptyStrings().splitToList(inside)) {
                String part = item.strip();
                if (part.isEmpty()) continue;
                int asIdx = part.indexOf(" as ");
                String imported = asIdx >= 0 ? part.substring(0, asIdx).strip() : part;
                String exported =
                        asIdx >= 0 ? part.substring(asIdx + " as ".length()).strip() : imported;
                if (!imported.isEmpty() && !exported.isEmpty()) {
                    exportsByName.put(exported, new ExportIndex.ReexportedNamed(module, imported));
                }
            }
        }

        var reexportStar = Pattern.compile("export\\s*\\*\\s*from\\s*['\\\"]([^'\\\"]+)['\\\"]")
                .matcher(text);
        while (reexportStar.find()) {
            exportStars.add(new ExportIndex.ReexportStar(reexportStar.group(1)));
        }

        var directExport = Pattern.compile("\\bexport\\s+(const|let|var|function|class)\\s+([A-Za-z_$][\\w$]*)\\b")
                .matcher(text);
        while (directExport.find()) {
            String name = directExport.group(2);
            exportsByName.putIfAbsent(name, new ExportIndex.LocalExport(name));
        }

        return new ExportIndex(Map.copyOf(exportsByName), List.copyOf(exportStars), Set.copyOf(extendsEdges));
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private Set<ReferenceCandidate> extractExportUsageCandidates(
            ProjectFile file, TSNode root, SourceContent sc, ImportBinder binder) {
        var bindings = binder.bindings();
        if (bindings.isEmpty()) return Set.of();

        var candidates = new java.util.LinkedHashSet<ReferenceCandidate>();
        CodeUnit fallbackEnclosing = enclosingCodeUnit(file, new Range(0, 0, 0, 0, 0))
                .orElseGet(() -> CodeUnit.module(file, "", file.getFileName()));

        String queryStr =
                """
            (call_expression function: (identifier) @call.id)
            (call_expression function: (member_expression
              object: (identifier) @call.member.obj
              property: (_) @call.member.prop))
            (member_expression
              object: (identifier) @member.obj
              property: (_) @member.prop)
            (identifier) @id
            """;

        try (TSQuery query = new TSQuery(getTSLanguage(), queryStr);
                TSQueryCursor cursor = new TSQueryCursor()) {
            cursor.exec(query, root, sc.text());
            TSQueryMatch match = new TSQueryMatch();
            while (cursor.nextMatch(match)) {
                for (TSQueryCapture cap : match.getCaptures()) {
                    String name = query.getCaptureNameForId(cap.getIndex());
                    TSNode node = cap.getNode();
                    if (node == null) continue;

                    Range range = rangeOf(node);
                    var enclosing = enclosingCodeUnit(file, range).orElse(fallbackEnclosing);

                    if ("call.id".equals(name)) {
                        String id = sc.substringFrom(node).strip();
                        if (!bindings.containsKey(id)) continue;
                        if (isWithinImportStatement(node)) continue;
                        if (!isAccessExpression(file, node.getStartByte(), node.getEndByte())) continue;
                        if (isShadowedInEnclosingFunction(node, id, sc)) {
                            continue;
                        }
                        candidates.add(new ReferenceCandidate(id, null, ReferenceKind.METHOD_CALL, range, enclosing));
                    } else if ("call.member.obj".equals(name)) {
                        // handled by prop capture below
                    } else if ("call.member.prop".equals(name)) {
                        // Can't safely correlate captures across match without more structure; rely on
                        // member_expression capture.
                    } else if ("member.obj".equals(name) || "member.prop".equals(name)) {
                        // also handled by member_expression capture below
                    } else if ("id".equals(name)) {
                        String id = sc.substringFrom(node).strip();
                        if (!bindings.containsKey(id)) continue;
                        if (isWithinImportStatement(node)) continue;
                        if (isDeclarationIdentifier(node)) continue;
                        TSNode parent = node.getParent();
                        if (parent != null
                                && CALL_EXPRESSION.equals(parent.getType())
                                && node.equals(parent.getChildByFieldName("function"))) {
                            continue;
                        }
                        if (!isAccessExpression(file, node.getStartByte(), node.getEndByte())) continue;
                        if (isShadowedInEnclosingFunction(node, id, sc)) {
                            continue;
                        }
                        candidates.add(
                                new ReferenceCandidate(id, null, ReferenceKind.STATIC_REFERENCE, range, enclosing));
                    }
                }

                // Handle member_expression as a single unit for qualifier/property checks
                for (TSQueryCapture cap : match.getCaptures()) {
                    String name = query.getCaptureNameForId(cap.getIndex());
                    if (!"member.obj".equals(name)) continue;
                    TSNode objNode = cap.getNode();
                    if (objNode == null) continue;

                    String qualifier = sc.substringFrom(objNode).strip();
                    ImportBinder.ImportBinding binding = bindings.get(qualifier);
                    if (binding == null || binding.kind() != ImportBinder.ImportKind.NAMESPACE) continue;

                    TSNode memberExpr = objNode.getParent();
                    if (memberExpr == null || !MEMBER_EXPRESSION.equals(memberExpr.getType())) continue;
                    TSNode prop = memberExpr.getChildByFieldName("property");
                    if (prop == null) continue;
                    String property = sc.substringFrom(prop).strip();

                    if (!isAccessExpression(file, memberExpr.getStartByte(), memberExpr.getEndByte())) continue;

                    Range range = rangeOf(prop);
                    var enclosing = enclosingCodeUnit(file, range)
                            .orElseGet(() -> CodeUnit.module(file, "", file.getFileName()));
                    candidates.add(
                            new ReferenceCandidate(property, qualifier, ReferenceKind.METHOD_CALL, range, enclosing));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract JS/TS export usage candidates for {}", file, e);
        }

        return Set.copyOf(candidates);
    }

    private boolean isShadowedInEnclosingFunction(TSNode usageNode, String identifierName, SourceContent sc) {
        TSNode fn = nearestEnclosingFunctionLike(usageNode);
        if (fn == null) {
            return false;
        }

        // Best-effort lexical shadowing: prefer Tree-sitter, but fall back to a conservative text scan.
        String queryStr =
                """
            (formal_parameters (identifier) @shadow.name)
            (variable_declarator name: (identifier) @shadow.name)
            """;

        try (TSQuery query = new TSQuery(getTSLanguage(), queryStr);
                TSQueryCursor cursor = new TSQueryCursor()) {
            cursor.exec(query, fn, sc.text());
            TSQueryMatch match = new TSQueryMatch();
            while (cursor.nextMatch(match)) {
                for (TSQueryCapture cap : match.getCaptures()) {
                    String name = query.getCaptureNameForId(cap.getIndex());
                    if (!"shadow.name".equals(name)) continue;
                    TSNode node = cap.getNode();
                    if (node == null) continue;
                    String declared = sc.substringFrom(node).strip();
                    if (identifierName.equals(declared)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Fall through to text scan.
        }

        String fnText = sc.substringFrom(fn);
        return Pattern.compile("\\b(const|let|var)\\s+" + Pattern.quote(identifierName) + "\\b")
                        .matcher(fnText)
                        .find()
                || Pattern.compile("\\bfunction\\s+\\w+\\s*\\([^)]*\\b" + Pattern.quote(identifierName) + "\\b")
                        .matcher(fnText)
                        .find();
    }

    private static @Nullable TSNode nearestEnclosingFunctionLike(TSNode node) {
        TSNode current = node;
        while (current != null) {
            String type = current.getType();
            if ("function_declaration".equals(type)
                    || "function".equals(type)
                    || "arrow_function".equals(type)
                    || "method_definition".equals(type)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isDeclarationIdentifier(TSNode identifierNode) {
        TSNode parent = identifierNode.getParent();
        if (parent == null) return false;
        String type = parent.getType();
        if ("variable_declarator".equals(type) && identifierNode.equals(parent.getChildByFieldName("name"))) {
            return true;
        }
        if ("function_declaration".equals(type) && identifierNode.equals(parent.getChildByFieldName("name"))) {
            return true;
        }
        if ("class_declaration".equals(type) && identifierNode.equals(parent.getChildByFieldName("name"))) {
            return true;
        }
        return false;
    }

    private static boolean isWithinImportStatement(TSNode node) {
        TSNode current = node;
        while (current != null) {
            String type = current.getType();
            if ("import_statement".equals(type) || TypeScriptTreeSitterNodeTypes.IMPORT_DECLARATION.equals(type)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static Range rangeOf(TSNode node) {
        TSPoint start = node.getStartPoint();
        TSPoint end = node.getEndPoint();
        return new Range(node.getStartByte(), node.getEndByte(), start.getRow(), end.getRow(), node.getStartByte());
    }

    @Override
    public Optional<CommentDensityStats> commentDensity(CodeUnit cu) {
        checkStale("commentDensity");
        String ext = cu.source().extension();
        if (!"js".equals(ext) && !"jsx".equals(ext) && !"ts".equals(ext) && !"tsx".equals(ext)) {
            return Optional.empty();
        }
        Map<String, CommentLineBreakdown> counts = collectCommentLineBreakdown(cu.source(), COMMENT_NODE_TYPES);
        return Optional.of(buildRollUpStats(cu, counts));
    }

    @Override
    public List<CommentDensityStats> commentDensityByTopLevel(ProjectFile file) {
        checkStale("commentDensityByTopLevel");
        String ext = file.extension();
        if (!"js".equals(ext) && !"jsx".equals(ext) && !"ts".equals(ext) && !"tsx".equals(ext)) {
            return List.of();
        }
        Map<String, CommentLineBreakdown> counts = collectCommentLineBreakdown(file, COMMENT_NODE_TYPES);
        List<CommentDensityStats> rows = new ArrayList<>();
        for (CodeUnit top : getTopLevelDeclarations(file)) {
            rows.add(buildRollUpStats(top, counts));
        }
        return List.copyOf(rows);
    }

    @Override
    protected String buildCloneAstSignature(String source) {
        return withFreshTree(source, "", tree -> {
            TSNode root = tree.getRootNode();
            if (root == null) {
                return "";
            }
            SourceContent sourceContent = SourceContent.of(source);
            var labels = new ArrayList<String>();
            try (var cursor = new TSTreeCursor(root)) {
                while (true) {
                    TSNode node = cursor.currentNode();
                    if (node == null) {
                        break;
                    }
                    labels.add(normalizeJsTsAstLabel(node, sourceContent));
                    if (!gotoNextDepthFirst(cursor, true)) {
                        break;
                    }
                }
            }
            return String.join("|", labels);
        });
    }

    @Override
    protected int refineCloneSimilarityPercent(
            CloneCandidateData left, CloneCandidateData right, int tokenSimilarity, CloneSmellWeights weights) {
        if (left.astSignature().isBlank() || right.astSignature().isBlank()) {
            return tokenSimilarity;
        }
        int astSimilarity = computeAstRefinementSimilarityPercent(left.astSignature(), right.astSignature());
        if (astSimilarity == 0) {
            return tokenSimilarity;
        }
        if (astSimilarity < weights.astSimilarityPercent()) {
            return 0;
        }
        return Math.min(tokenSimilarity, astSimilarity);
    }

    private static String normalizeJsTsAstLabel(TSNode node, SourceContent sourceContent) {
        String type = Objects.toString(node.getType(), "");
        String text = sourceContent.substringFrom(node).strip();
        if (CLONE_AST_IDENTIFIER_TYPES.contains(type)) {
            return "ID";
        }
        if (CLONE_AST_STRING_TYPES.contains(type)) {
            return "STR";
        }
        if (CLONE_AST_NUMBER_TYPES.contains(type)) {
            return "NUM";
        }
        if (TypeScriptTreeSitterNodeTypes.TRUE.equals(text)
                || TypeScriptTreeSitterNodeTypes.FALSE.equals(text)
                || TRUE.equals(text)
                || FALSE.equals(text)) {
            return "BOOL";
        }
        if (CLONE_AST_IGNORED_TYPES.contains(type)) {
            return "IGN";
        }
        return "N:" + type;
    }

    @Override
    public List<ExceptionHandlingSmell> findExceptionHandlingSmells(ProjectFile file, ExceptionSmellWeights weights) {
        checkStale("findExceptionHandlingSmells");
        ExceptionSmellWeights resolvedWeights = weights != null ? weights : ExceptionSmellWeights.defaults();
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return List.of();
                    }
                    return withSource(
                            file,
                            source -> detectExceptionHandlingSmells(file, root, source, resolvedWeights),
                            List.of());
                },
                List.of());
    }

    @Override
    protected boolean containsTestMarkers(TSTree tree, SourceContent sourceContent) {
        TSNode root = tree.getRootNode();
        if (root == null) {
            return false;
        }
        var calls = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(CALL_EXPRESSION), calls);
        return calls.stream().anyMatch(call -> {
            String name = callExpressionName(call, sourceContent);
            return TEST_FUNCTION_NAMES.contains(name) && testCallback(call).isPresent();
        });
    }

    @Override
    public List<TestAssertionSmell> findTestAssertionSmells(ProjectFile file, TestAssertionWeights weights) {
        checkStale("findTestAssertionSmells");
        if (!containsTests(file)) {
            return List.of();
        }
        TestAssertionWeights resolvedWeights = weights != null ? weights : TestAssertionWeights.defaults();
        return withTreeOf(
                file,
                tree -> {
                    TSNode root = tree.getRootNode();
                    if (root == null) {
                        return List.of();
                    }
                    return withSource(
                            file, source -> detectTestAssertionSmells(file, root, source, resolvedWeights), List.of());
                },
                List.of());
    }

    private List<TestAssertionSmell> detectTestAssertionSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, TestAssertionWeights weights) {
        var calls = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(CALL_EXPRESSION), calls);
        var findings = new ArrayList<TestSmellCandidate>();
        calls.stream()
                .filter(call -> Set.of(TEST_FN_TEST, TEST_FN_IT).contains(callExpressionName(call, sourceContent)))
                .forEach(call -> testCallback(call)
                        .ifPresent(callback ->
                                analyzeTestCallback(file, call, callback, sourceContent, weights, findings)));
        return findings.stream()
                .sorted(TEST_SMELL_CANDIDATE_COMPARATOR)
                .map(TestSmellCandidate::smell)
                .toList();
    }

    private void analyzeTestCallback(
            ProjectFile file,
            TSNode testCall,
            TSNode callback,
            SourceContent sourceContent,
            TestAssertionWeights weights,
            List<TestSmellCandidate> out) {
        TSNode body = callback.getChildByFieldName(FIELD_BODY);
        if (body == null) {
            body = callback;
        }
        var calls = new ArrayList<TSNode>();
        collectNodesByType(body, Set.of(CALL_EXPRESSION), calls);
        List<AssertionSignal> assertions = calls.stream()
                .map(call -> assertionSignal(call, sourceContent, weights))
                .flatMap(Optional::stream)
                .toList();
        String enclosing = enclosingCodeUnit(
                        file,
                        testCall.getStartPoint().getRow(),
                        testCall.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        int assertionCount = assertions.size();
        if (assertionCount == 0) {
            addTestSmellCandidate(
                    file,
                    enclosing,
                    TEST_ASSERTION_KIND_NO_ASSERTIONS,
                    weights.noAssertionWeight(),
                    0,
                    List.of(TEST_ASSERTION_KIND_NO_ASSERTIONS),
                    sourceContent.substringFrom(testCall),
                    testCall.getStartByte(),
                    out);
            return;
        }
        assertions.stream()
                .filter(signal -> signal.baseScore() > 0)
                .forEach(signal -> addTestSmellCandidate(
                        file,
                        enclosing,
                        signal.kind(),
                        signal.baseScore(),
                        assertionCount,
                        signal.reasons(),
                        signal.excerpt(),
                        signal.startByte(),
                        out));
        boolean allShallow = assertions.stream().allMatch(AssertionSignal::shallow);
        if (allShallow) {
            int score = weights.shallowAssertionOnlyWeight()
                    - testMeaningfulAssertionCredit(assertions, weights, AssertionSignal::meaningful);
            if (score > 0) {
                addTestSmellCandidate(
                        file,
                        enclosing,
                        TEST_ASSERTION_KIND_SHALLOW_ONLY,
                        score,
                        assertionCount,
                        List.of(TEST_ASSERTION_KIND_SHALLOW_ONLY),
                        sourceContent.substringFrom(testCall),
                        testCall.getStartByte(),
                        out);
            }
        }
    }

    private Optional<AssertionSignal> assertionSignal(
            TSNode call, SourceContent sourceContent, TestAssertionWeights weights) {
        TSNode function = call.getChildByFieldName(FIELD_FUNCTION);
        if (function == null) {
            return Optional.empty();
        }
        if (MEMBER_EXPRESSION.equals(function.getType())) {
            String property = memberPropertyName(function, sourceContent);
            Optional<TSNode> expectArg = expectArgument(function, sourceContent);
            if (expectArg.isPresent() && EXPECT_TERMINAL_NAMES.contains(property)) {
                return Optional.of(classifyExpectAssertion(call, property, expectArg.get(), sourceContent, weights));
            }
            if (MOCK_VERIFY_TERMINAL_NAMES.contains(property)) {
                return Optional.of(new AssertionSignal(
                        TEST_ASSERTION_KIND_MOCK_VERIFICATION,
                        0,
                        false,
                        true,
                        call.getStartByte(),
                        List.of(),
                        sourceContent.substringFrom(call)));
            }
            if (ASSERT.equals(memberObjectName(function, sourceContent))) {
                return Optional.of(classifyAssertCall(call, property, sourceContent, weights));
            }
        }
        return Optional.empty();
    }

    private AssertionSignal classifyExpectAssertion(
            TSNode call, String property, TSNode expectArg, SourceContent sourceContent, TestAssertionWeights weights) {
        List<TSNode> args = argumentNodes(call);
        int score = 0;
        var reasons = new ArrayList<String>();
        boolean shallow = SHALLOW_EXPECT_TERMINAL_NAMES.contains(property);
        boolean meaningful = !shallow;
        String kind = TEST_ASSERTION_KIND_EXPECT;
        if ((TO_BE.equals(property) || TO_EQUAL.equals(property) || TO_STRICT_EQUAL.equals(property))
                && args.size() == 1) {
            TSNode actual = args.getFirst();
            if (isConstantExpression(expectArg) && isConstantExpression(actual)) {
                score += weights.constantEqualityWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_EQUALITY);
                kind = TEST_ASSERTION_KIND_CONSTANT_EQUALITY;
                meaningful = false;
            } else if (sameExpression(expectArg, actual, sourceContent)) {
                score += weights.tautologicalAssertionWeight();
                reasons.add(TEST_ASSERTION_KIND_SELF_COMPARISON);
                kind = TEST_ASSERTION_KIND_SELF_COMPARISON;
                meaningful = false;
            }
        }
        if (SNAPSHOT_EXPECT_TERMINAL_NAMES.contains(property)) {
            score += weights.overspecifiedLiteralWeight();
            reasons.add(TEST_ASSERTION_KIND_SNAPSHOT);
            kind = TEST_ASSERTION_KIND_SNAPSHOT;
            meaningful = false;
        }
        if (shallow) {
            score += weights.nullnessOnlyWeight();
            reasons.add(TEST_ASSERTION_KIND_NULLNESS_ONLY);
            kind = TEST_ASSERTION_KIND_NULLNESS_ONLY;
            meaningful = false;
        }
        if (containsOverspecifiedLiteral(args, sourceContent, weights)) {
            score += weights.overspecifiedLiteralWeight();
            reasons.add(TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL);
            kind = TEST_ASSERTION_KIND_OVERSPECIFIED_LITERAL;
        }
        return new AssertionSignal(
                kind,
                score,
                shallow,
                meaningful,
                call.getStartByte(),
                List.copyOf(reasons),
                sourceContent.substringFrom(call));
    }

    private AssertionSignal classifyAssertCall(
            TSNode call, String property, SourceContent sourceContent, TestAssertionWeights weights) {
        List<TSNode> args = argumentNodes(call);
        int score = 0;
        var reasons = new ArrayList<String>();
        boolean meaningful = true;
        String kind = TEST_ASSERTION_KIND_ASSERT;
        if ((TO_BE.equals(property) || TO_EQUAL.equals(property)) && args.size() >= 2) {
            TSNode expected = args.get(0);
            TSNode actual = args.get(1);
            if (isConstantExpression(expected) && isConstantExpression(actual)) {
                score += weights.constantEqualityWeight();
                reasons.add(TEST_ASSERTION_KIND_CONSTANT_EQUALITY);
                kind = TEST_ASSERTION_KIND_CONSTANT_EQUALITY;
                meaningful = false;
            } else if (sameExpression(expected, actual, sourceContent)) {
                score += weights.tautologicalAssertionWeight();
                reasons.add(TEST_ASSERTION_KIND_SELF_COMPARISON);
                kind = TEST_ASSERTION_KIND_SELF_COMPARISON;
                meaningful = false;
            }
        }
        return new AssertionSignal(
                kind,
                score,
                false,
                meaningful,
                call.getStartByte(),
                List.copyOf(reasons),
                sourceContent.substringFrom(call));
    }

    private static Optional<TSNode> testCallback(TSNode call) {
        return argumentNodes(call).stream()
                .filter(arg -> ARROW_FUNCTION.equals(arg.getType()) || FUNCTION_EXPRESSION.equals(arg.getType()))
                .findFirst();
    }

    private static String callExpressionName(TSNode call, SourceContent sourceContent) {
        TSNode function = call.getChildByFieldName(FIELD_FUNCTION);
        if (function == null) {
            return "";
        }
        if (IDENTIFIER.equals(function.getType())) {
            return sourceContent.substringFrom(function).strip();
        }
        if (MEMBER_EXPRESSION.equals(function.getType())) {
            return memberPropertyName(function, sourceContent);
        }
        return "";
    }

    private static String memberPropertyName(TSNode member, SourceContent sourceContent) {
        TSNode property = member.getChildByFieldName(FIELD_PROPERTY);
        return property == null ? "" : sourceContent.substringFrom(property).strip();
    }

    private static String memberObjectName(TSNode member, SourceContent sourceContent) {
        TSNode object = member.getChildByFieldName(FIELD_OBJECT);
        return object == null ? "" : sourceContent.substringFrom(object).strip();
    }

    private static Optional<TSNode> expectArgument(TSNode member, SourceContent sourceContent) {
        TSNode object = member.getChildByFieldName(FIELD_OBJECT);
        if (object == null || !CALL_EXPRESSION.equals(object.getType())) {
            return Optional.empty();
        }
        TSNode function = object.getChildByFieldName(FIELD_FUNCTION);
        if (function == null
                || !EXPECT.equals(sourceContent.substringFrom(function).strip())) {
            return Optional.empty();
        }
        return argumentNodes(object).stream().findFirst();
    }

    private static List<TSNode> argumentNodes(TSNode call) {
        TSNode arguments = call.getChildByFieldName(FIELD_ARGUMENTS);
        if (arguments == null) {
            arguments = firstNamedChildOfType(call, ARGUMENTS);
        }
        if (arguments == null) {
            return List.of();
        }
        var out = new ArrayList<TSNode>();
        for (int i = 0; i < arguments.getNamedChildCount(); i++) {
            TSNode child = arguments.getNamedChild(i);
            if (child != null) {
                out.add(child);
            }
        }
        return List.copyOf(out);
    }

    private static boolean isConstantExpression(TSNode node) {
        return CONSTANT_LITERAL_TYPES.contains(node.getType());
    }

    private static boolean sameExpression(TSNode left, TSNode right, SourceContent sourceContent) {
        return sourceContent
                .substringFrom(left)
                .strip()
                .equals(sourceContent.substringFrom(right).strip());
    }

    private static boolean containsOverspecifiedLiteral(
            List<TSNode> args, SourceContent sourceContent, TestAssertionWeights weights) {
        return args.stream()
                .anyMatch(arg -> Set.of(STRING, TEMPLATE_STRING).contains(arg.getType())
                        && sourceContent.substringFrom(arg).length() >= weights.largeLiteralLengthThreshold());
    }

    private static @Nullable TSNode firstNamedChildOfType(TSNode node, String type) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child != null && type.equals(child.getType())) {
                return child;
            }
        }
        return null;
    }

    private record AssertionSignal(
            String kind,
            int baseScore,
            boolean shallow,
            boolean meaningful,
            int startByte,
            List<String> reasons,
            String excerpt) {}

    private List<ExceptionHandlingSmell> detectExceptionHandlingSmells(
            ProjectFile file, TSNode root, SourceContent sourceContent, ExceptionSmellWeights weights) {
        var catches = new ArrayList<TSNode>();
        collectNodesByType(root, Set.of(CATCH_CLAUSE), catches);
        return catches.stream()
                .map(catchClause -> analyzeCatchClause(file, catchClause, sourceContent, weights))
                .flatMap(Optional::stream)
                .sorted(java.util.Comparator.comparingInt(ExceptionHandlingSmell::score)
                        .reversed()
                        .thenComparing(f -> f.file().toString())
                        .thenComparing(ExceptionHandlingSmell::enclosingFqName))
                .toList();
    }

    private Optional<ExceptionHandlingSmell> analyzeCatchClause(
            ProjectFile file, TSNode catchClause, SourceContent sourceContent, ExceptionSmellWeights weights) {
        TSNode bodyNode = catchClause.getChildByFieldName("body");
        if (bodyNode == null) {
            bodyNode = catchClause.getNamedChildren().stream()
                    .filter(child -> STATEMENT_BLOCK.equals(child.getType()))
                    .findFirst()
                    .orElse(null);
        }
        if (bodyNode == null) {
            return Optional.empty();
        }

        int bodyStatements = countBodyExpressions(bodyNode);
        String bodyText = sourceContent.substringFrom(bodyNode);
        boolean hasAnyComment = bodyText.contains("//") || bodyText.contains("/*");
        boolean emptyBody = bodyStatements == 0 && !hasAnyComment;
        boolean commentOnlyBody = bodyStatements == 0 && hasAnyComment;
        boolean smallBody = bodyStatements <= weights.smallBodyMaxStatements();
        boolean throwPresent = hasDescendantOfType(bodyNode, THROW_STATEMENT);
        boolean logOnly = bodyStatements == 1 && isLikelyLogOnlyBody(bodyNode, sourceContent) && !throwPresent;

        String catchType = extractCatchType(catchClause, sourceContent);
        int score = 0;
        var reasons = new ArrayList<String>();
        if (catchType.equals("<untyped>") || catchType.equals("any") || catchType.equals("<unknown>")) {
            score += weights.genericExceptionWeight();
            reasons.add("generic-catch:" + catchType);
        } else if (catchType.contains("Error") || catchType.contains("Exception")) {
            score += weights.genericRuntimeExceptionWeight();
            reasons.add("generic-catch:" + catchType);
        }
        if (emptyBody) {
            score += weights.emptyBodyWeight();
            reasons.add("empty-body");
        }
        if (commentOnlyBody) {
            score += weights.commentOnlyBodyWeight();
            reasons.add("comment-only-body");
        }
        if (smallBody) {
            score += weights.smallBodyWeight();
            reasons.add("small-body:" + bodyStatements);
        }
        if (logOnly) {
            score += weights.logOnlyWeight();
            reasons.add("log-only-body");
        }

        int creditStatements = Math.min(bodyStatements, Math.max(0, weights.meaningfulBodyStatementThreshold()));
        int bodyCredit = Math.max(0, weights.meaningfulBodyCreditPerStatement()) * creditStatements;
        if (bodyCredit > 0) {
            score -= bodyCredit;
            reasons.add("meaningful-body-credit:" + bodyCredit);
        }
        if (score <= 0) {
            return Optional.empty();
        }

        String enclosing = enclosingCodeUnit(
                        file,
                        catchClause.getStartPoint().getRow(),
                        catchClause.getEndPoint().getRow())
                .map(CodeUnit::fqName)
                .orElse(file.toString());
        return Optional.of(new ExceptionHandlingSmell(
                file,
                enclosing,
                catchType,
                score,
                bodyStatements,
                List.copyOf(reasons),
                compactCatchExcerpt(sourceContent.substringFrom(catchClause))));
    }

    private static int countBodyExpressions(TSNode bodyNode) {
        int expressions = 0;
        for (int i = 0; i < bodyNode.getNamedChildCount(); i++) {
            TSNode child = bodyNode.getNamedChild(i);
            if (child != null && CATCH_BODY_MEANINGFUL_STATEMENT_TYPES.contains(child.getType())) {
                expressions++;
            }
        }
        return expressions;
    }

    private static String extractCatchType(TSNode catchClause, SourceContent sourceContent) {
        TSNode parameterNode = catchClause.getChildByFieldName("parameter");
        if (parameterNode == null) {
            return "<untyped>";
        }
        String parameterText = sourceContent.substringFrom(parameterNode).strip();
        int colon = parameterText.indexOf(':');
        if (colon < 0) {
            return "<untyped>";
        }
        String type = parameterText.substring(colon + 1).strip();
        return type.isEmpty() ? "<untyped>" : type;
    }

    private static boolean isLikelyLogOnlyBody(TSNode bodyNode, SourceContent sourceContent) {
        TSNode statement = firstNonCommentNamedChild(bodyNode, COMMENT_NODE_TYPES);
        if (statement == null || !EXPRESSION_STATEMENT.equals(statement.getType())) {
            return false;
        }
        TSNode call = findFirstNamedDescendant(statement, CALL_EXPRESSION);
        if (call == null) {
            return false;
        }
        TSNode functionNode = call.getChildByFieldName("function");
        if (functionNode == null) {
            return false;
        }
        if (IDENTIFIER.equals(functionNode.getType())) {
            String bare = sourceContent.substringFrom(functionNode).strip().toLowerCase(Locale.ROOT);
            return JS_LOG_BARE_NAMES.contains(bare);
        }
        if (!MEMBER_EXPRESSION.equals(functionNode.getType())) {
            return false;
        }
        TSNode objectNode = functionNode.getChildByFieldName("object");
        TSNode propertyNode = functionNode.getChildByFieldName("property");
        if (objectNode == null || propertyNode == null) {
            return false;
        }
        String receiver = sourceContent.substringFrom(objectNode).strip().toLowerCase(Locale.ROOT);
        String method = sourceContent.substringFrom(propertyNode).strip().toLowerCase(Locale.ROOT);
        boolean loggerLikeReceiver = JS_LOG_RECEIVER_NAMES.contains(receiver)
                || JS_LOG_RECEIVER_NAMES.stream().anyMatch(name -> receiver.endsWith("." + name));
        boolean loggerLikeMethod = JS_LOG_METHOD_NAMES.contains(method);
        return loggerLikeReceiver && loggerLikeMethod;
    }

    private static String compactCatchExcerpt(String text) {
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        if (compact.length() <= 180) {
            return compact;
        }
        return compact.substring(0, 180) + "...";
    }

    private CommentDensityStats buildRollUpStats(CodeUnit cu, Map<String, CommentLineBreakdown> counts) {
        CommentLineBreakdown own = counts.getOrDefault(cu.fqName(), new CommentLineBreakdown(0, 0));
        int span = rangesOf(cu).stream()
                .mapToInt(r -> r.endLine() - r.startLine() + 1)
                .sum();
        String path = cu.source().toString();
        if (!cu.isClass()) {
            return new CommentDensityStats(
                    cu.fqName(),
                    path,
                    own.headerLines(),
                    own.inlineLines(),
                    span,
                    own.headerLines(),
                    own.inlineLines(),
                    span);
        }
        int rh = own.headerLines();
        int ri = own.inlineLines();
        int rs = span;
        for (CodeUnit ch : getDirectChildren(cu)) {
            CommentDensityStats child = buildRollUpStats(ch, counts);
            rh += child.rolledUpHeaderCommentLines();
            ri += child.rolledUpInlineCommentLines();
            rs += child.rolledUpSpanLines();
        }
        return new CommentDensityStats(cu.fqName(), path, own.headerLines(), own.inlineLines(), span, rh, ri, rs);
    }

    @Override
    public Set<CodeUnit> importedCodeUnitsOf(ProjectFile file) {
        return performImportedCodeUnitsOf(file);
    }

    @Override
    public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
        return performReferencingFilesOf(file);
    }

    @Override
    public Set<String> relevantImportsFor(CodeUnit cu) {
        return getSource(cu, false)
                .map(source -> {
                    Set<String> codeIdentifiers = extractTypeIdentifiers(source);
                    List<ImportInfo> imports = importInfoOf(cu.source());

                    return imports.stream()
                            .filter(imp -> importMatchesAnyIdentifier(imp.rawSnippet(), codeIdentifiers))
                            .map(ImportInfo::rawSnippet)
                            .collect(Collectors.toSet());
                })
                .orElseGet(Set::of);
    }

    private boolean importMatchesAnyIdentifier(String importStatement, Set<String> codeIdentifiers) {
        Set<String> importIdentifiers = extractIdentifiersFromImport(importStatement);
        for (String id : importIdentifiers) {
            if (codeIdentifiers.contains(id)) {
                return true;
            }
        }
        return false;
    }

    public abstract Set<String> extractIdentifiersFromImport(String importStatement);

    @Override
    protected void extractImports(
            Map<String, TSNode> capturedNodesForMatch, SourceContent sourceContent, List<ImportInfo> localImportInfos) {
        // Handle ES6 imports
        TSNode importNode = capturedNodesForMatch.get(CaptureNames.IMPORT_DECLARATION);

        if (importNode != null) {
            String rawSnippet = sourceContent.substringFrom(importNode).strip();
            if (!rawSnippet.isEmpty()) {
                localImportInfos.add(new ImportInfo(rawSnippet, false, null, null));
            }
        }

        // CommonJS require extraction
        extractCommonJsRequireImport(capturedNodesForMatch, sourceContent, localImportInfos);
    }

    @Override
    protected FileAnalysisAccumulator createModulesFromImports(
            ProjectFile file,
            List<String> localImportStatements,
            TSNode rootNode,
            String modulePackageName,
            FileAnalysisAccumulator acc) {
        if (localImportStatements.isEmpty()) {
            return acc;
        }

        String moduleShortName = file.getFileName();
        CodeUnit moduleCU = CodeUnit.module(file, modulePackageName, moduleShortName);

        if (acc.getByFqName(moduleCU.fqName()) != null) {
            return acc;
        }

        String importBlockSignature = String.join("\n", localImportStatements);
        var moduleRange = new Range(
                rootNode.getStartByte(),
                rootNode.getEndByte(),
                rootNode.getStartPoint().getRow(),
                rootNode.getEndPoint().getRow(),
                rootNode.getStartByte());

        acc.addTopLevel(moduleCU)
                .addSignature(moduleCU, importBlockSignature)
                .addRange(moduleCU, moduleRange)
                .setHasBody(moduleCU, true)
                .addSymbolIndex(moduleCU.identifier(), moduleCU)
                .addSymbolIndex(moduleCU.shortName(), moduleCU);
        return acc;
    }

    @Override
    protected Set<CodeUnit> resolveImports(ProjectFile file, List<String> importStatements) {
        Path root = getProject().getRoot();
        Set<Path> absolutePaths =
                getProject().getAllFiles().stream().map(ProjectFile::absPath).collect(Collectors.toSet());

        var resolved = importStatements.stream()
                .map(JsTsAnalyzer::extractModulePathFromImport)
                .flatMap(Optional::stream)
                .map(path -> moduleResolutionCache.get(
                        new ModulePathKey(file, path),
                        key -> Optional.ofNullable(resolveJavaScriptLikeModulePath(root, absolutePaths, file, path))))
                .flatMap(Optional::stream)
                .flatMap(resolvedFile -> {
                    Set<CodeUnit> decls = getDeclarations(resolvedFile);
                    if (!decls.isEmpty()) {
                        return decls.stream();
                    }
                    // Preserve module dependency edges for barrel files / re-export-only modules.
                    return Stream.of(CodeUnit.module(resolvedFile, "", resolvedFile.getFileName())
                            .withSynthetic(true));
                })
                .collect(Collectors.toSet());

        return Set.copyOf(resolved);
    }

    @Override
    public boolean couldImportFile(List<ImportInfo> imports, ProjectFile target) {
        for (ImportInfo imp : imports) {
            Optional<String> modulePathOpt = extractModulePathFromImport(imp.rawSnippet());
            if (modulePathOpt.isEmpty()) {
                continue;
            }

            String modulePath = modulePathOpt.get();

            // External/node_modules imports (not starting with . or ..) cannot be project files
            if (!modulePath.startsWith("./") && !modulePath.startsWith("../")) {
                continue;
            }

            if (couldModulePathMatchTarget(modulePath, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a relative module path could resolve to the given target file.
     * This is a conservative text-based check that may have false positives but no false negatives.
     *
     * @param modulePath the module path from the import (e.g., "./utils/helper", "../models/User")
     * @param target the target ProjectFile to check
     * @return true if the module path could potentially resolve to the target
     */
    private boolean couldModulePathMatchTarget(String modulePath, ProjectFile target) {
        // Normalize the module path by removing leading ./ and ../ segments
        // We keep track of ".." but for matching purposes we just need the semantic path
        String normalizedPath = modulePath;
        while (normalizedPath.startsWith("./") || normalizedPath.startsWith("../")) {
            if (normalizedPath.startsWith("./")) {
                normalizedPath = normalizedPath.substring(2);
            } else if (normalizedPath.startsWith("../")) {
                normalizedPath = normalizedPath.substring(3);
            }
        }

        if (normalizedPath.isEmpty()) {
            return false;
        }

        // Get the target's relative path as a string with forward slashes
        String targetPath = target.getRelPath().toString().replace('\\', '/');

        // Check if the target path ends with the normalized import path
        // We need to check several variations:
        // 1. Direct match with extension: utils/helper matches utils/helper.ts
        // 2. Index file match: utils matches utils/index.ts

        // Strip known extensions from the import path if present
        String importBasePath = normalizedPath;
        for (String ext : KNOWN_EXTENSIONS) {
            if (importBasePath.endsWith(ext)) {
                importBasePath = importBasePath.substring(0, importBasePath.length() - ext.length());
                break;
            }
        }

        // Strip extension from target to get base path
        String targetBasePath = targetPath;
        for (String ext : KNOWN_EXTENSIONS) {
            if (targetBasePath.endsWith(ext)) {
                targetBasePath = targetBasePath.substring(0, targetBasePath.length() - ext.length());
                break;
            }
        }

        // Check 1: Direct path match (e.g., "utils/helper" matches "src/utils/helper")
        if (targetBasePath.endsWith(importBasePath)) {
            // Make sure we're matching a complete path segment
            int matchStart = targetBasePath.length() - importBasePath.length();
            if (matchStart == 0 || targetBasePath.charAt(matchStart - 1) == '/') {
                return true;
            }
        }

        // Check 2: Index file match (e.g., "utils" matches "utils/index")
        if (targetBasePath.endsWith("/index")) {
            String dirPath = targetBasePath.substring(0, targetBasePath.length() - "/index".length());
            if (dirPath.endsWith(importBasePath)) {
                int matchStart = dirPath.length() - importBasePath.length();
                if (matchStart == 0 || dirPath.charAt(matchStart - 1) == '/') {
                    return true;
                }
            }
        }

        return false;
    }

    protected static Optional<String> extractModulePathFromImport(String importStatement) {
        Matcher es6Matcher = ES6_IMPORT_PATTERN.matcher(importStatement);
        if (es6Matcher.find()) return Optional.of(es6Matcher.group(1));

        Matcher sideEffectMatcher = ES6_SIDE_EFFECT_IMPORT_PATTERN.matcher(importStatement);
        if (sideEffectMatcher.find()) return Optional.of(sideEffectMatcher.group(1));

        Matcher cjsMatcher = CJS_REQUIRE_PATTERN.matcher(importStatement);
        if (cjsMatcher.find()) return Optional.of(cjsMatcher.group(1));

        return Optional.empty();
    }

    protected static @Nullable ProjectFile resolveJavaScriptLikeModulePath(
            Path projectRoot, Set<Path> absolutePaths, ProjectFile importingFile, String modulePath) {
        if (!modulePath.startsWith("./") && !modulePath.startsWith("../")) {
            return null;
        }

        Path parentDir = importingFile.absPath().getParent();
        if (parentDir == null) return null;

        Path resolvedPath = parentDir.resolve(modulePath).normalize();
        String fileName = resolvedPath.getFileName().toString();

        if (KNOWN_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
            if (absolutePaths.contains(resolvedPath) && resolvedPath.startsWith(projectRoot)) {
                return new ProjectFile(projectRoot, projectRoot.relativize(resolvedPath));
            }
        }

        String baseName = fileName;
        for (String ext : KNOWN_EXTENSIONS) {
            if (baseName.endsWith(ext)) {
                baseName = baseName.substring(0, baseName.length() - ext.length());
                break;
            }
        }
        Path basePath = resolvedPath.resolveSibling(baseName);

        List<String> fileExtensions =
                Stream.concat(Stream.of(""), KNOWN_EXTENSIONS.stream()).toList();
        for (String ext : fileExtensions) {
            Path candidatePath = ext.isEmpty() ? basePath : basePath.resolveSibling(baseName + ext);
            if (absolutePaths.contains(candidatePath) && candidatePath.startsWith(projectRoot)) {
                return new ProjectFile(projectRoot, projectRoot.relativize(candidatePath));
            }
        }

        List<String> indexFiles = List.of("index.js", "index.jsx", "index.ts", "index.tsx");
        for (String indexFile : indexFiles) {
            Path candidatePath = resolvedPath.resolve(indexFile);
            if (absolutePaths.contains(candidatePath) && candidatePath.startsWith(projectRoot)) {
                return new ProjectFile(projectRoot, projectRoot.relativize(candidatePath));
            }
        }

        return null;
    }

    protected static void extractCommonJsRequireImport(
            Map<String, TSNode> capturedNodesForMatch, SourceContent sourceContent, List<ImportInfo> localImportInfos) {
        // Check for both legacy and new split-query capture names
        TSNode requireCallNode = capturedNodesForMatch.get(REQUIRE_CALL_CAPTURE_NAME);
        if (requireCallNode == null) {
            requireCallNode = capturedNodesForMatch.get("module.require_call");
        }

        if (requireCallNode == null) {
            return;
        }

        // Identify the require function identifier to verify it's a 'require' call
        TSNode requireFuncNode = capturedNodesForMatch.get(REQUIRE_FUNC_CAPTURE_NAME);
        if (requireFuncNode == null) {
            requireFuncNode = capturedNodesForMatch.get("_require_func");
        }
        if (requireFuncNode == null) {
            requireFuncNode = capturedNodesForMatch.get("require_func");
        }

        boolean isRequire = false;
        if (requireFuncNode != null) {
            String funcName = sourceContent.substringFrom(requireFuncNode).strip();
            isRequire = "require".equals(funcName);
        } else {
            String text = sourceContent.substringFrom(requireCallNode).trim();
            isRequire = text.startsWith("require") || text.contains("require(");
        }

        if (isRequire) {
            TSNode nodeToCapture = requireCallNode;

            // Search upwards for the containing statement to capture the full 'require' assignment/usage
            TSNode current = requireCallNode;
            while (current != null) {
                String type = current.getType();
                if ("lexical_declaration".equals(type)
                        || "variable_declaration".equals(type)
                        || "expression_statement".equals(type)
                        || "variable_declarator".equals(type)) {
                    nodeToCapture = current;
                    // If we found a declarator, try one more step for the full declaration
                    TSNode parent = current.getParent();
                    if (parent != null
                            && Optional.ofNullable(parent.getType()).orElse("").contains("declaration")) {
                        nodeToCapture = parent;
                    }
                    break;
                }
                if ("program".equals(type)) {
                    break;
                }
                current = current.getParent();
            }

            String requireText = sourceContent.substringFrom(nodeToCapture).strip();
            if (!requireText.isEmpty()) {
                localImportInfos.add(new ImportInfo(requireText, false, null, null));
            }
        }
    }

    @Override
    protected boolean isConstructor(
            CodeUnit candidate, @Nullable CodeUnit enclosingClass, @Nullable String captureName) {
        return "constructor".equals(candidate.identifier());
    }

    @Override
    public int computeCyclomaticComplexity(CodeUnit cu) {
        Integer result = withTreeOf(
                cu.source(),
                tree -> {
                    List<Range> ranges = rangesOf(cu);
                    if (ranges.isEmpty()) return 1;

                    Range firstRange = ranges.getFirst();
                    TSNode root = tree.getRootNode();
                    if (root == null) return 1;
                    TSNode cuNode = root.getDescendantForByteRange(firstRange.startByte(), firstRange.endByte());

                    if (cuNode == null) return 1;

                    int complexity = 1;
                    Deque<TSNode> stack = new ArrayDeque<>();
                    stack.push(cuNode);

                    while (!stack.isEmpty()) {
                        TSNode node = stack.pop();
                        String type = node.getType();

                        if (type != null) {
                            switch (type) {
                                case IF_STATEMENT,
                                        FOR_STATEMENT,
                                        FOR_IN_STATEMENT,
                                        WHILE_STATEMENT,
                                        DO_STATEMENT,
                                        CATCH_CLAUSE,
                                        TERNARY_EXPRESSION -> complexity++;
                                case SWITCH_CASE -> {
                                    // Increment for 'case ...:', but not for 'default:'
                                    if (node.getChildByFieldName("value") != null) {
                                        complexity++;
                                    }
                                }
                                case BINARY_EXPRESSION -> {
                                    TSNode operatorNode = node.getChildByFieldName("operator");
                                    if (operatorNode != null) {
                                        String operator = operatorNode.getType(); // In JS/TS grammar,
                                        // operators are often
                                        // their own types
                                        if (operator != null
                                                && (operator.equals("&&")
                                                        || operator.equals("||")
                                                        || operator.equals("??"))) {
                                            complexity++;
                                        }
                                    }
                                }
                                default -> {}
                            }
                        }

                        for (int i = 0; i < node.getChildCount(); i++) {
                            TSNode child = node.getChild(i);
                            if (child != null) {
                                stack.push(child);
                            }
                        }
                    }

                    return complexity;
                },
                1);
        return result != null ? result : 1;
    }
}
