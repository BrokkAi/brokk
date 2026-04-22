package ai.brokk.analyzer.javascript;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportInfo;
import ai.brokk.analyzer.JsTsAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceContent;
import ai.brokk.analyzer.typescript.TypeScriptTreeSitterNodeTypes;
import ai.brokk.analyzer.usages.ExportIndex;
import ai.brokk.analyzer.usages.ImportBinder;
import ai.brokk.analyzer.usages.ReferenceCandidate;
import ai.brokk.analyzer.usages.ReferenceKind;
import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;
import org.treesitter.TSPoint;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;

/**
 * Static helper for JS/TS exported-symbol usages analysis.
 *
 * <p>Keep this logic out of {@link JsTsAnalyzer} so the analyzer remains primarily responsible for
 * maintaining project-level state and caches, not analysis feature implementations.
 */
public final class JsTsExportUsageExtractor {

    private static final Pattern EXPORT_NAMED_FROM =
            Pattern.compile("export\\s*\\{([^}]*)\\}\\s*from\\s*['\\\"]([^'\\\"]+)['\\\"]");
    private static final Pattern EXPORT_STAR_FROM =
            Pattern.compile("export\\s*\\*\\s*from\\s*['\\\"]([^'\\\"]+)['\\\"]");
    private static final Pattern EXPORT_DIRECT =
            Pattern.compile("\\bexport\\s+(const|let|var|function|class)\\s+([A-Za-z_$][\\w$]*)\\b");

    private JsTsExportUsageExtractor() {}

    public static ExportIndex computeExportIndex(JsTsAnalyzer analyzer, TSNode root, SourceContent sc) {
        var exportsByName = new java.util.HashMap<String, ExportIndex.ExportEntry>();
        var exportStars = new java.util.ArrayList<ExportIndex.ReexportStar>();
        var extendsEdges = new java.util.LinkedHashSet<ExportIndex.ClassExtendsEdge>();

        // Tree-sitter best-effort extraction.
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

        try (TSQuery query = new TSQuery(analyzer.tsLanguage(), queryStr);
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
            // best-effort; fall back to regex parsing below
        }

        // Regex fallback: keep re-exports robust across subtle grammar differences.
        String text = sc.text();

        var reexportNamed = EXPORT_NAMED_FROM.matcher(text);
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

        var reexportStar = EXPORT_STAR_FROM.matcher(text);
        while (reexportStar.find()) {
            exportStars.add(new ExportIndex.ReexportStar(reexportStar.group(1)));
        }

        var directExport = EXPORT_DIRECT.matcher(text);
        while (directExport.find()) {
            String name = directExport.group(2);
            exportsByName.putIfAbsent(name, new ExportIndex.LocalExport(name));
        }

        return new ExportIndex(Map.copyOf(exportsByName), List.copyOf(exportStars), Set.copyOf(extendsEdges));
    }

    public static ImportBinder computeImportBinder(List<ImportInfo> importInfos) {
        if (importInfos.isEmpty()) {
            return ImportBinder.empty();
        }

        var bindings = new java.util.HashMap<String, ImportBinder.ImportBinding>();
        for (ImportInfo info : importInfos) {
            String raw = info.rawSnippet();
            Optional<String> modulePathOpt = JsTsAnalyzer.extractModulePathFromImport(raw);
            if (modulePathOpt.isEmpty()) {
                continue;
            }
            String moduleSpecifier = modulePathOpt.get();

            // Side-effect import: no bindings.
            if (info.identifier() == null && info.alias() == null) {
                continue;
            }

            if (info.isWildcard()) {
                if (info.alias() == null) continue;
                bindings.put(
                        info.alias(),
                        new ImportBinder.ImportBinding(moduleSpecifier, ImportBinder.ImportKind.NAMESPACE, "*"));
                continue;
            }

            String identifier = info.identifier();
            String alias = info.alias();

            if ("default".equals(identifier) && alias != null) {
                bindings.put(
                        alias,
                        new ImportBinder.ImportBinding(moduleSpecifier, ImportBinder.ImportKind.DEFAULT, "default"));
                continue;
            }

            if (identifier != null) {
                String local = alias != null ? alias : identifier;
                bindings.put(
                        local,
                        new ImportBinder.ImportBinding(moduleSpecifier, ImportBinder.ImportKind.NAMED, identifier));
            }
        }

        return new ImportBinder(Map.copyOf(bindings));
    }

    public static List<ImportInfo> extractImportInfos(JsTsAnalyzer analyzer, TSNode importNode, SourceContent sc) {
        String rawSnippet = sc.substringFrom(importNode).strip();
        if (rawSnippet.isEmpty()) {
            return List.of();
        }
        // v1: Derive structured bindings from the raw snippet. This keeps importInfoOf(...) as the single pipeline
        // source for both import graph resolution and usage binder construction, without requiring a TS typechecker.
        return fallbackImportInfos(rawSnippet);
    }

    private static List<ImportInfo> fallbackImportInfos(String rawSnippet) {
        String normalized = rawSnippet.replace('\n', ' ').replace('\r', ' ').strip();

        // Side-effect import
        if (normalized.startsWith("import \"") || normalized.startsWith("import '")) {
            return List.of(new ImportInfo(rawSnippet, false, null, null));
        }

        if (normalized.startsWith("import type ")) {
            normalized = "import " + normalized.substring("import type ".length());
        }

        var expanded = new ArrayList<ImportInfo>();

        var nsMatcher = Pattern.compile("\\bimport\\s+\\*\\s+as\\s+([A-Za-z_$][\\w$]*)\\b").matcher(normalized);
        if (nsMatcher.find()) {
            expanded.add(new ImportInfo(rawSnippet, true, null, nsMatcher.group(1)));
        }

        var defaultMatcher =
                Pattern.compile("\\bimport\\s+([A-Za-z_$][\\w$]*)\\b\\s*(,|from\\b)").matcher(normalized);
        if (defaultMatcher.find()) {
            expanded.add(new ImportInfo(rawSnippet, false, "default", defaultMatcher.group(1)));
        }

        var namedMatcher = Pattern.compile("\\{([^}]*)\\}").matcher(normalized);
        if (namedMatcher.find()) {
            String inside = namedMatcher.group(1);
            for (String item : Splitter.on(',').trimResults().omitEmptyStrings().splitToList(inside)) {
                String part = item.strip();
                if (part.isEmpty()) continue;
                if (part.startsWith("type ")) {
                    part = part.substring("type ".length()).strip();
                }
                int asIdx = part.indexOf(" as ");
                String identifier = asIdx >= 0 ? part.substring(0, asIdx).strip() : part;
                String alias = asIdx >= 0 ? part.substring(asIdx + " as ".length()).strip() : null;
                if (!identifier.isEmpty()) {
                    expanded.add(new ImportInfo(rawSnippet, false, identifier, alias));
                }
            }
        }

        if (expanded.isEmpty()) {
            expanded.add(new ImportInfo(rawSnippet, false, null, null));
        }

        return List.copyOf(expanded);
    }

    public static Set<ReferenceCandidate> computeExportUsageCandidates(
            JsTsAnalyzer analyzer, ProjectFile file, TSNode root, SourceContent sc, ImportBinder binder) {
        var bindings = binder.bindings();
        if (bindings.isEmpty()) return Set.of();

        var candidates = new LinkedHashSet<ReferenceCandidate>();
        CodeUnit fallbackEnclosing = analyzer.enclosingCodeUnit(file, new IAnalyzer.Range(0, 0, 0, 0, 0))
                .orElseGet(() -> CodeUnit.module(file, "", file.getFileName()));

        String queryStr =
                """
            (call_expression function: (identifier) @call.id)
            (member_expression
              object: (identifier) @member.obj
              property: (_) @member.prop)
            (identifier) @id
            """;

        try (TSQuery query = new TSQuery(analyzer.tsLanguage(), queryStr);
                TSQueryCursor cursor = new TSQueryCursor()) {
            cursor.exec(query, root, sc.text());
            TSQueryMatch match = new TSQueryMatch();
            while (cursor.nextMatch(match)) {
                for (TSQueryCapture cap : match.getCaptures()) {
                    String capName = query.getCaptureNameForId(cap.getIndex());
                    TSNode node = cap.getNode();
                    if (node == null) continue;

                    if ("call.id".equals(capName)) {
                        String id = sc.substringFrom(node).strip();
                        if (!bindings.containsKey(id)) continue;
                        if (isWithinImportStatement(node)) continue;
                        if (!analyzer.isAccessExpression(file, node.getStartByte(), node.getEndByte())) continue;
                        if (isShadowedInEnclosingFunction(analyzer, node, id, sc)) continue;

                        IAnalyzer.Range range = rangeOf(node);
                        CodeUnit enclosing =
                                analyzer.enclosingCodeUnit(file, range).orElse(fallbackEnclosing);
                        candidates.add(new ReferenceCandidate(id, null, ReferenceKind.METHOD_CALL, range, enclosing));
                        continue;
                    }

                    if ("id".equals(capName)) {
                        String id = sc.substringFrom(node).strip();
                        if (!bindings.containsKey(id)) continue;
                        if (isWithinImportStatement(node)) continue;
                        if (isDeclarationIdentifier(node)) continue;

                        TSNode parent = node.getParent();
                        if (parent != null
                                && "call_expression".equals(parent.getType())
                                && node.equals(parent.getChildByFieldName("function"))) {
                            continue;
                        }

                        if (!analyzer.isAccessExpression(file, node.getStartByte(), node.getEndByte())) continue;
                        if (isShadowedInEnclosingFunction(analyzer, node, id, sc)) continue;

                        IAnalyzer.Range range = rangeOf(node);
                        CodeUnit enclosing =
                                analyzer.enclosingCodeUnit(file, range).orElse(fallbackEnclosing);
                        candidates.add(
                                new ReferenceCandidate(id, null, ReferenceKind.STATIC_REFERENCE, range, enclosing));
                    }
                }

                // member_expression: only count `NS.foo` where NS is a namespace import.
                TSNode objNode = null;
                TSNode propNode = null;
                for (TSQueryCapture cap : match.getCaptures()) {
                    String capName = query.getCaptureNameForId(cap.getIndex());
                    if ("member.obj".equals(capName)) objNode = cap.getNode();
                    else if ("member.prop".equals(capName)) propNode = cap.getNode();
                }
                if (objNode != null && propNode != null) {
                    String qualifier = sc.substringFrom(objNode).strip();
                    ImportBinder.ImportBinding binding = bindings.get(qualifier);
                    if (binding != null && binding.kind() == ImportBinder.ImportKind.NAMESPACE) {
                        String property = sc.substringFrom(propNode).strip();
                        if (!analyzer.isAccessExpression(file, propNode.getStartByte(), propNode.getEndByte())) {
                            continue;
                        }
                        IAnalyzer.Range range = rangeOf(propNode);
                        CodeUnit enclosing =
                                analyzer.enclosingCodeUnit(file, range).orElse(fallbackEnclosing);
                        candidates.add(new ReferenceCandidate(
                                property, qualifier, ReferenceKind.METHOD_CALL, range, enclosing));
                    }
                }
            }
        } catch (Exception e) {
            // best-effort: empty set
            return Set.of();
        }

        return Set.copyOf(candidates);
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

    private static boolean isShadowedInEnclosingFunction(
            JsTsAnalyzer analyzer, TSNode usageNode, String identifierName, SourceContent sc) {
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

        try (TSQuery query = new TSQuery(analyzer.tsLanguage(), queryStr);
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
            String type = Optional.ofNullable(current.getType()).orElse("").toLowerCase(Locale.ROOT);
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

    private static IAnalyzer.Range rangeOf(TSNode node) {
        TSPoint start = node.getStartPoint();
        TSPoint end = node.getEndPoint();
        return new IAnalyzer.Range(
                node.getStartByte(), node.getEndByte(), start.getRow(), end.getRow(), node.getStartByte());
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
}
