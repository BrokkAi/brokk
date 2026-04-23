package ai.brokk.analyzer.javascript;

import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.ARROW_FUNCTION;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.CALL_EXPRESSION;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.CLASS_DECLARATION;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.FUNCTION_DECLARATION;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.IMPORT_DECLARATION;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.METHOD_DEFINITION;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.RETURN_STATEMENT;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.VARIABLE_DECLARATOR;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.ImportInfo;
import ai.brokk.analyzer.JsTsAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceContent;
import ai.brokk.analyzer.typescript.TypeScriptTreeSitterNodeTypes;
import ai.brokk.analyzer.usages.ExportIndex;
import ai.brokk.analyzer.usages.ImportBinder;
import ai.brokk.analyzer.usages.LocalUsageEvent;
import ai.brokk.analyzer.usages.LocalUsageInference;
import ai.brokk.analyzer.usages.ReceiverTargetRef;
import ai.brokk.analyzer.usages.ReferenceCandidate;
import ai.brokk.analyzer.usages.ReferenceKind;
import ai.brokk.analyzer.usages.ResolvedReceiverCandidate;
import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
    private static final Logger logger = LogManager.getLogger(JsTsExportUsageExtractor.class);

    private record ScopeKey(int startByte, int endByte, String type) {}

    private record ShadowingIndex(Map<ScopeKey, Set<String>> localNamesByScope) {}

    private static final String IMPORT_STATEMENT = "import_statement";
    private static final String CLASS_BODY = "class_body";
    private static final String CLASS_HERITAGE = "class_heritage";
    private static final String EXTENDS_CLAUSE = "extends_clause";
    private static final String IMPLEMENTS_CLAUSE = "implements_clause";
    private static final String TYPE_ALIAS_DECLARATION = "type_alias_declaration";
    private static final String TYPE_ANNOTATION = "type_annotation";
    private static final String TYPE_ARGUMENTS = "type_arguments";
    private static final String TYPE_QUERY = "type_query";
    private static final String PROPERTY_IDENTIFIER = "property_identifier";
    private static final String PUBLIC_FIELD_DEFINITION = "public_field_definition";
    private static final String ABSTRACT_METHOD_SIGNATURE = "abstract_method_signature";
    private static final String METHOD_SIGNATURE = "method_signature";
    private static final String OBJECT_TYPE = "object_type";
    private static final String THIS = "this";
    private static final String TYPE_IDENTIFIER = "type_identifier";
    private static final String NESTED_TYPE_IDENTIFIER = "nested_type_identifier";
    private static final String NEW_EXPRESSION = "new_expression";
    private static final String MEMBER_EXPRESSION = "member_expression";
    private static final String OBJECT_PATTERN = "object_pattern";
    private static final String ARRAY_PATTERN = "array_pattern";
    private static final String PAIR_PATTERN = "pair_pattern";
    private static final String SHORTHAND_PROPERTY_IDENTIFIER_PATTERN = "shorthand_property_identifier_pattern";
    private static final String STATEMENT_BLOCK = "statement_block";
    private static final String FORMAL_PARAMETERS = "formal_parameters";
    private static final String OBJECT = "object";
    private static final String PROPERTY = "property";
    private static final String CONSTRUCTOR = "constructor";
    private static final String FUNCTION = "function";
    private static final String VALUE = "value";
    private static final String STATIC = "static";

    private static final Pattern EXPORT_NAMED_FROM =
            Pattern.compile("export\\s*\\{([^}]*)\\}\\s*from\\s*['\\\"]([^'\\\"]+)['\\\"]");
    private static final Pattern EXPORT_STAR_FROM =
            Pattern.compile("export\\s*\\*\\s*from\\s*['\\\"]([^'\\\"]+)['\\\"]");
    private static final Pattern EXPORT_DIRECT =
            Pattern.compile("\\bexport\\s+(const|let|var|function|class)\\s+([A-Za-z_$][\\w$]*)\\b");
    private static final Pattern IDENTIFIER_TOKEN = Pattern.compile("[A-Za-z_$][\\w$]*");

    private JsTsExportUsageExtractor() {}

    public static ExportIndex computeExportIndex(JsTsAnalyzer analyzer, TSNode root, SourceContent sc) {
        var exportsByName = new java.util.HashMap<String, ExportIndex.ExportEntry>();
        var exportStars = new java.util.ArrayList<ExportIndex.ReexportStar>();
        var heritageEdges = new java.util.LinkedHashSet<ExportIndex.HeritageEdge>();
        var classMembers = new java.util.LinkedHashSet<ExportIndex.ClassMember>();

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
                    heritageEdges.add(new ExportIndex.HeritageEdge(
                            sc.substringFrom(className).strip(),
                            sc.substringFrom(classExtends).strip()));
                }
            }
        } catch (Exception e) {
            logger.debug("Tree-sitter export index extraction failed; falling back to regex parsing", e);
        }

        collectClassMetadata(root, sc, heritageEdges, classMembers);

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

        return new ExportIndex(
                Map.copyOf(exportsByName),
                List.copyOf(exportStars),
                Set.copyOf(heritageEdges),
                Set.copyOf(classMembers));
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

        var nsMatcher = Pattern.compile("\\bimport\\s+\\*\\s+as\\s+([A-Za-z_$][\\w$]*)\\b")
                .matcher(normalized);
        if (nsMatcher.find()) {
            expanded.add(new ImportInfo(rawSnippet, true, null, nsMatcher.group(1)));
        }

        var defaultMatcher = Pattern.compile("\\bimport\\s+([A-Za-z_$][\\w$]*)\\b\\s*(,|from\\b)")
                .matcher(normalized);
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
                String alias =
                        asIdx >= 0 ? part.substring(asIdx + " as ".length()).strip() : null;
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
        ShadowingIndex shadowingIndex = buildShadowingIndex(root, sc);
        CodeUnit fallbackEnclosing = analyzer.enclosingCodeUnit(file, new IAnalyzer.Range(0, 0, 0, 0, 0))
                .orElseGet(() -> CodeUnit.module(file, "", file.getFileName()));

        try {
            collectReferenceCandidates(
                    root, analyzer, file, sc, bindings, shadowingIndex, fallbackEnclosing, candidates);
        } catch (Exception e) {
            logger.debug("Tree-sitter export usage candidate extraction failed for {}", file, e);
            return Set.of();
        }

        logger.debug("JS/TS export usage candidates for {}: {}", file, candidates);

        return Set.copyOf(candidates);
    }

    public static Set<ResolvedReceiverCandidate> computeResolvedReceiverCandidates(
            JsTsAnalyzer analyzer, ProjectFile file, TSNode root, SourceContent sc, ImportBinder binder) {
        var bindings = binder.bindings();
        if (bindings.isEmpty()) {
            return Set.of();
        }

        CodeUnit fallbackEnclosing = analyzer.enclosingCodeUnit(file, new IAnalyzer.Range(0, 0, 0, 0, 0))
                .orElseGet(() -> CodeUnit.module(file, "", file.getFileName()));

        try {
            List<LocalUsageEvent> events = computeLocalUsageEvents(analyzer, file, root, sc, binder, fallbackEnclosing);
            return LocalUsageInference.infer(events);
        } catch (Exception e) {
            logger.debug("Local receiver inference extraction failed for {}", file, e);
            return Set.of();
        }
    }

    private static void collectReferenceCandidates(
            TSNode node,
            JsTsAnalyzer analyzer,
            ProjectFile file,
            SourceContent sc,
            Map<String, ImportBinder.ImportBinding> bindings,
            ShadowingIndex shadowingIndex,
            CodeUnit fallbackEnclosing,
            Set<ReferenceCandidate> candidates) {
        if (node == null) {
            return;
        }

        if (isTypeContextRoot(node)) {
            collectTypeReferenceCandidates(
                    node, analyzer, file, sc, bindings, shadowingIndex, fallbackEnclosing, candidates);
        }
        addIdentifierCandidate(node, analyzer, file, sc, bindings, shadowingIndex, fallbackEnclosing, candidates);
        addMemberCandidate(node, analyzer, file, sc, bindings, shadowingIndex, fallbackEnclosing, candidates);

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null) {
                collectReferenceCandidates(
                        child, analyzer, file, sc, bindings, shadowingIndex, fallbackEnclosing, candidates);
            }
        }
    }

    private static void collectTypeReferenceCandidates(
            TSNode node,
            JsTsAnalyzer analyzer,
            ProjectFile file,
            SourceContent sc,
            Map<String, ImportBinder.ImportBinding> bindings,
            ShadowingIndex shadowingIndex,
            CodeUnit fallbackEnclosing,
            Set<ReferenceCandidate> candidates) {
        if (node == null) {
            return;
        }
        if (node.getChildCount() == 0) {
            String text = sc.substringFrom(node).strip();
            if (bindings.containsKey(text)
                    && IDENTIFIER_TOKEN.matcher(text).matches()
                    && !isWithinImportStatement(node)
                    && !isDeclarationIdentifier(node)
                    && !isShadowed(node, text, shadowingIndex)) {
                addCandidate(
                        candidates,
                        analyzer,
                        file,
                        node,
                        fallbackEnclosing,
                        text,
                        null,
                        null,
                        false,
                        ReferenceKind.TYPE_REFERENCE);
            }
            return;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child != null) {
                collectTypeReferenceCandidates(
                        child, analyzer, file, sc, bindings, shadowingIndex, fallbackEnclosing, candidates);
            }
        }
    }

    private static void addIdentifierCandidate(
            TSNode node,
            JsTsAnalyzer analyzer,
            ProjectFile file,
            SourceContent sc,
            Map<String, ImportBinder.ImportBinding> bindings,
            ShadowingIndex shadowingIndex,
            CodeUnit fallbackEnclosing,
            Set<ReferenceCandidate> candidates) {
        if (!isIdentifierLike(node)) {
            return;
        }

        String identifier = sc.substringFrom(node).strip();
        if (!bindings.containsKey(identifier) || isWithinImportStatement(node) || isDeclarationIdentifier(node)) {
            return;
        }
        if (isShadowed(node, identifier, shadowingIndex)) {
            return;
        }

        if (isTypeReferenceNode(node)) {
            addCandidate(
                    candidates,
                    analyzer,
                    file,
                    node,
                    fallbackEnclosing,
                    identifier,
                    null,
                    null,
                    false,
                    ReferenceKind.TYPE_REFERENCE);
            return;
        }

        TSNode parent = node.getParent();
        if (parent != null
                && CALL_EXPRESSION.equals(parent.getType())
                && node.equals(parent.getChildByFieldName("function"))) {
            if (analyzer.isAccessExpression(file, node.getStartByte(), node.getEndByte())) {
                addCandidate(
                        candidates,
                        analyzer,
                        file,
                        node,
                        fallbackEnclosing,
                        identifier,
                        null,
                        null,
                        false,
                        ReferenceKind.METHOD_CALL);
            }
            return;
        }

        if (!analyzer.isAccessExpression(file, node.getStartByte(), node.getEndByte())) {
            return;
        }
        addCandidate(
                candidates,
                analyzer,
                file,
                node,
                fallbackEnclosing,
                identifier,
                null,
                null,
                false,
                ReferenceKind.STATIC_REFERENCE);
    }

    private static void addMemberCandidate(
            TSNode node,
            JsTsAnalyzer analyzer,
            ProjectFile file,
            SourceContent sc,
            Map<String, ImportBinder.ImportBinding> bindings,
            ShadowingIndex shadowingIndex,
            CodeUnit fallbackEnclosing,
            Set<ReferenceCandidate> candidates) {
        if (!MEMBER_EXPRESSION.equals(node.getType())) {
            return;
        }

        TSNode objectNode = node.getChildByFieldName("object");
        TSNode propertyNode = node.getChildByFieldName("property");
        if (objectNode == null && node.getNamedChildCount() > 0) {
            objectNode = node.getNamedChild(0);
        }
        if (propertyNode == null && node.getNamedChildCount() > 1) {
            propertyNode = node.getNamedChild(1);
        }
        if (objectNode == null || propertyNode == null || isDeclarationIdentifier(propertyNode)) {
            return;
        }

        if (!analyzer.isAccessExpression(file, propertyNode.getStartByte(), propertyNode.getEndByte())) {
            return;
        }

        String property = sc.substringFrom(propertyNode).strip();
        if (property.isEmpty()) {
            return;
        }

        boolean isMethodCall = isMethodCallMember(node);

        if (THIS.equals(objectNode.getType())
                || "this".equals(sc.substringFrom(objectNode).strip())) {
            String ownerClassName = enclosingClassName(node, sc);
            if (ownerClassName != null && isExportedClass(analyzer, file, ownerClassName)) {
                addCandidate(
                        candidates,
                        analyzer,
                        file,
                        propertyNode,
                        fallbackEnclosing,
                        property,
                        null,
                        ownerClassName,
                        true,
                        isMethodCall ? ReferenceKind.METHOD_CALL : ReferenceKind.FIELD_READ);
            }
            return;
        }

        if (isIdentifierLike(objectNode)) {
            String qualifier = sc.substringFrom(objectNode).strip();
            ImportBinder.ImportBinding binding = bindings.get(qualifier);
            if (binding == null || isShadowed(objectNode, qualifier, shadowingIndex)) {
                return;
            }

            if (binding.kind() == ImportBinder.ImportKind.NAMESPACE) {
                addCandidate(
                        candidates,
                        analyzer,
                        file,
                        propertyNode,
                        fallbackEnclosing,
                        property,
                        qualifier,
                        null,
                        false,
                        isMethodCall ? ReferenceKind.METHOD_CALL : ReferenceKind.FIELD_READ);
            }
            return;
        }

        if (MEMBER_EXPRESSION.equals(objectNode.getType())) {
            TSNode namespaceNode = objectNode.getChildByFieldName("object");
            TSNode classNode = objectNode.getChildByFieldName("property");
            if (namespaceNode != null
                    && classNode != null
                    && isIdentifierLike(namespaceNode)
                    && isIdentifierLike(classNode)) {
                String qualifier = sc.substringFrom(namespaceNode).strip();
                ImportBinder.ImportBinding binding = bindings.get(qualifier);
                if (binding != null
                        && binding.kind() == ImportBinder.ImportKind.NAMESPACE
                        && !isShadowed(namespaceNode, qualifier, shadowingIndex)) {
                    addCandidate(
                            candidates,
                            analyzer,
                            file,
                            propertyNode,
                            fallbackEnclosing,
                            property,
                            qualifier,
                            sc.substringFrom(classNode).strip(),
                            false,
                            isMethodCall ? ReferenceKind.METHOD_CALL : ReferenceKind.FIELD_READ);
                }
            }
            return;
        }

        if (NEW_EXPRESSION.equals(objectNode.getType())) {
            TSNode ctorNode = objectNode.getChildByFieldName("constructor");
            if (ctorNode != null
                    && isIdentifierLike(ctorNode)
                    && bindings.containsKey(sc.substringFrom(ctorNode).strip())
                    && !isShadowed(ctorNode, sc.substringFrom(ctorNode).strip(), shadowingIndex)) {
                addCandidate(
                        candidates,
                        analyzer,
                        file,
                        propertyNode,
                        fallbackEnclosing,
                        property,
                        sc.substringFrom(ctorNode).strip(),
                        null,
                        true,
                        isMethodCall ? ReferenceKind.METHOD_CALL : ReferenceKind.FIELD_READ);
            }
        }
    }

    private static List<LocalUsageEvent> computeLocalUsageEvents(
            JsTsAnalyzer analyzer,
            ProjectFile file,
            TSNode root,
            SourceContent sc,
            ImportBinder binder,
            CodeUnit fallbackEnclosing) {
        var events = new ArrayList<LocalUsageEvent>();
        binder.bindings()
                .forEach((name, binding) -> receiverTarget(binding, false).ifPresent(target -> {
                    events.add(new LocalUsageEvent.DeclareSymbol(name));
                    events.add(new LocalUsageEvent.SeedSymbol(name, Set.of(target)));
                }));
        var returnTargetsByFunction = computeSimpleReturnTargets(root, sc, binder.bindings());
        collectLocalUsageEvents(
                root, analyzer, file, sc, binder.bindings(), returnTargetsByFunction, fallbackEnclosing, events, false);
        return List.copyOf(events);
    }

    private static void collectLocalUsageEvents(
            TSNode node,
            JsTsAnalyzer analyzer,
            ProjectFile file,
            SourceContent sc,
            Map<String, ImportBinder.ImportBinding> bindings,
            Map<String, Set<ReceiverTargetRef>> returnTargetsByFunction,
            CodeUnit fallbackEnclosing,
            List<LocalUsageEvent> events,
            boolean insideFunctionLike) {
        if (node == null) {
            return;
        }

        boolean openedScope = opensLocalScope(node, insideFunctionLike);
        boolean childFunctionLike = isFunctionLike(node);
        if (openedScope) {
            events.add(new LocalUsageEvent.EnterScope());
            if (childFunctionLike) {
                emitParameterEvents(node, sc, bindings, events);
                emitThisEvents(node, analyzer, file, sc, events);
            }
        }

        emitLocalDeclarationEvents(node, sc, bindings, returnTargetsByFunction, events);
        emitReceiverAccessEvent(node, analyzer, file, sc, bindings, fallbackEnclosing, events);

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null) {
                collectLocalUsageEvents(
                        child,
                        analyzer,
                        file,
                        sc,
                        bindings,
                        returnTargetsByFunction,
                        fallbackEnclosing,
                        events,
                        childFunctionLike);
            }
        }

        if (openedScope) {
            events.add(new LocalUsageEvent.ExitScope());
        }
    }

    private static boolean opensLocalScope(TSNode node, boolean insideFunctionLike) {
        String type = node.getType();
        if (isFunctionLike(node)) {
            return true;
        }
        return STATEMENT_BLOCK.equals(type) && insideFunctionLike;
    }

    private static boolean isFunctionLike(TSNode node) {
        String type = node.getType();
        return FUNCTION_DECLARATION.equals(type)
                || ARROW_FUNCTION.equals(type)
                || METHOD_DEFINITION.equals(type)
                || CONSTRUCTOR.equals(type)
                || "function".equals(type);
    }

    private static void emitParameterEvents(
            TSNode functionNode,
            SourceContent sc,
            Map<String, ImportBinder.ImportBinding> bindings,
            List<LocalUsageEvent> events) {
        TSNode parameters = functionNode.getChildByFieldName("parameters");
        if (parameters == null || !FORMAL_PARAMETERS.equals(parameters.getType())) {
            return;
        }
        var seenNames = new HashSet<String>();
        walk(parameters, node -> {
            if (!isIdentifierLike(node) || isWithinTypeAnnotation(node, parameters)) {
                return;
            }
            String localName = sc.substringFrom(node).strip();
            if (!seenNames.add(localName)) {
                return;
            }
            events.add(new LocalUsageEvent.DeclareSymbol(localName));
            TSNode parameterNode = node.getParent() != null ? node.getParent() : node;
            Set<ReceiverTargetRef> targets = receiverTargetsFromTypeAnnotation(
                    findFirstDescendant(parameterNode, TYPE_ANNOTATION), sc, bindings, true);
            if (!targets.isEmpty()) {
                events.add(new LocalUsageEvent.SeedSymbol(localName, targets));
            }
        });
    }

    private static void emitThisEvents(
            TSNode functionNode,
            JsTsAnalyzer analyzer,
            ProjectFile file,
            SourceContent sc,
            List<LocalUsageEvent> events) {
        if (!isMethodLikeFunction(functionNode)) {
            return;
        }
        String ownerClassName = enclosingClassName(functionNode, sc);
        if (ownerClassName == null || !isExportedClass(analyzer, file, ownerClassName)) {
            return;
        }
        events.add(new LocalUsageEvent.DeclareSymbol(THIS));
        events.add(new LocalUsageEvent.SeedSymbol(
                THIS, Set.of(new ReceiverTargetRef(null, ownerClassName, true, 0.96, file))));
    }

    private static boolean isMethodLikeFunction(TSNode functionNode) {
        TSNode parent = functionNode.getParent();
        return parent != null && METHOD_DEFINITION.equals(parent.getType());
    }

    private static void emitLocalDeclarationEvents(
            TSNode node,
            SourceContent sc,
            Map<String, ImportBinder.ImportBinding> bindings,
            Map<String, Set<ReceiverTargetRef>> returnTargetsByFunction,
            List<LocalUsageEvent> events) {
        if (!VARIABLE_DECLARATOR.equals(node.getType())) {
            return;
        }
        TSNode nameNode = node.getChildByFieldName("name");
        if (nameNode == null) {
            return;
        }
        var localNames = new LinkedHashSet<String>();
        collectDeclaredLocalNames(nameNode, sc, localNames);
        if (localNames.isEmpty()) {
            return;
        }
        localNames.forEach(name -> events.add(new LocalUsageEvent.DeclareSymbol(name)));

        Set<ReceiverTargetRef> typeTargets =
                receiverTargetsFromTypeAnnotation(node.getChildByFieldName("type"), sc, bindings, true);
        if (!typeTargets.isEmpty() && localNames.size() == 1) {
            events.add(new LocalUsageEvent.SeedSymbol(localNames.iterator().next(), typeTargets));
        }

        TSNode valueNode = node.getChildByFieldName(VALUE);
        if (valueNode == null || localNames.size() != 1) {
            return;
        }
        String localName = localNames.iterator().next();

        if (isIdentifierLike(valueNode)) {
            events.add(new LocalUsageEvent.AliasSymbol(
                    localName, sc.substringFrom(valueNode).strip()));
            return;
        }

        if (NEW_EXPRESSION.equals(valueNode.getType())) {
            TSNode ctorNode = valueNode.getChildByFieldName(CONSTRUCTOR);
            if (ctorNode != null && isIdentifierLike(ctorNode)) {
                ImportBinder.ImportBinding binding =
                        bindings.get(sc.substringFrom(ctorNode).strip());
                receiverTarget(binding, true)
                        .ifPresent(target -> events.add(new LocalUsageEvent.SeedSymbol(localName, Set.of(target))));
                return;
            }
        }

        if (CALL_EXPRESSION.equals(valueNode.getType())) {
            TSNode calleeNode = valueNode.getChildByFieldName(FUNCTION);
            if (calleeNode == null && valueNode.getNamedChildCount() > 0) {
                calleeNode = valueNode.getNamedChild(0);
            }
            if (calleeNode != null && isIdentifierLike(calleeNode)) {
                Set<ReceiverTargetRef> returnTargets =
                        returnTargetsByFunction.get(sc.substringFrom(calleeNode).strip());
                if (returnTargets != null && !returnTargets.isEmpty()) {
                    events.add(new LocalUsageEvent.SeedSymbol(localName, returnTargets));
                }
            }
        }
    }

    private static void emitReceiverAccessEvent(
            TSNode node,
            JsTsAnalyzer analyzer,
            ProjectFile file,
            SourceContent sc,
            Map<String, ImportBinder.ImportBinding> bindings,
            CodeUnit fallbackEnclosing,
            List<LocalUsageEvent> events) {
        if (!MEMBER_EXPRESSION.equals(node.getType())) {
            return;
        }

        TSNode objectNode = node.getChildByFieldName(OBJECT);
        TSNode propertyNode = node.getChildByFieldName(PROPERTY);
        if (objectNode == null && node.getNamedChildCount() > 0) {
            objectNode = node.getNamedChild(0);
        }
        if (propertyNode == null && node.getNamedChildCount() > 1) {
            propertyNode = node.getNamedChild(1);
        }
        if (objectNode == null
                || propertyNode == null
                || !isIdentifierLike(propertyNode)
                || isDeclarationIdentifier(propertyNode)
                || isWithinImportStatement(objectNode)) {
            return;
        }

        if (THIS.equals(objectNode.getType())) {
            IAnalyzer.Range range = rangeOf(propertyNode);
            CodeUnit enclosing = analyzer.enclosingCodeUnit(file, range).orElse(fallbackEnclosing);
            events.add(new LocalUsageEvent.ReceiverAccess(
                    THIS,
                    sc.substringFrom(propertyNode).strip(),
                    isMethodCallMember(node) ? ReferenceKind.METHOD_CALL : ReferenceKind.FIELD_READ,
                    range,
                    enclosing));
            return;
        }

        if (!isIdentifierLike(objectNode)) {
            return;
        }

        String receiverName = sc.substringFrom(objectNode).strip();
        if (bindings.containsKey(receiverName)
                && bindings.get(receiverName).kind() == ImportBinder.ImportKind.NAMESPACE) {
            return;
        }
        if (!analyzer.isAccessExpression(file, propertyNode.getStartByte(), propertyNode.getEndByte())) {
            return;
        }

        IAnalyzer.Range range = rangeOf(propertyNode);
        CodeUnit enclosing = analyzer.enclosingCodeUnit(file, range).orElse(fallbackEnclosing);
        events.add(new LocalUsageEvent.ReceiverAccess(
                receiverName,
                sc.substringFrom(propertyNode).strip(),
                isMethodCallMember(node) ? ReferenceKind.METHOD_CALL : ReferenceKind.FIELD_READ,
                range,
                enclosing));
    }

    private static Set<ReceiverTargetRef> receiverTargetsFromTypeAnnotation(
            @Nullable TSNode typeNode,
            SourceContent sc,
            Map<String, ImportBinder.ImportBinding> bindings,
            boolean instanceReceiver) {
        if (typeNode == null) {
            return Set.of();
        }
        var targets = new LinkedHashSet<ReceiverTargetRef>();
        collectReceiverTargetsFromType(typeNode, sc, bindings, instanceReceiver, targets, new HashSet<>());
        return Set.copyOf(targets);
    }

    private static Map<String, Set<ReceiverTargetRef>> computeSimpleReturnTargets(
            TSNode root, SourceContent sc, Map<String, ImportBinder.ImportBinding> bindings) {
        var returnTargetsByFunction = new java.util.HashMap<String, Set<ReceiverTargetRef>>();
        walk(root, node -> {
            if (!FUNCTION_DECLARATION.equals(node.getType())) {
                return;
            }
            TSNode nameNode = node.getChildByFieldName("name");
            if (nameNode == null || !isIdentifierLike(nameNode)) {
                return;
            }
            Set<ReceiverTargetRef> targets = simpleReturnTargets(node, sc, bindings);
            if (!targets.isEmpty()) {
                returnTargetsByFunction.put(sc.substringFrom(nameNode).strip(), targets);
            }
        });
        return Map.copyOf(returnTargetsByFunction);
    }

    private static Set<ReceiverTargetRef> simpleReturnTargets(
            TSNode functionNode, SourceContent sc, Map<String, ImportBinder.ImportBinding> bindings) {
        var targets = new LinkedHashSet<ReceiverTargetRef>();
        walk(functionNode, node -> {
            if (!RETURN_STATEMENT.equals(node.getType())) {
                return;
            }
            TSNode valueNode = node.getChildByFieldName(VALUE);
            if (valueNode == null && node.getNamedChildCount() > 0) {
                valueNode = node.getNamedChild(0);
            }
            if (valueNode == null) {
                return;
            }
            if (NEW_EXPRESSION.equals(valueNode.getType())) {
                TSNode ctorNode = valueNode.getChildByFieldName(CONSTRUCTOR);
                if (ctorNode == null && valueNode.getNamedChildCount() > 0) {
                    ctorNode = valueNode.getNamedChild(0);
                }
                if (ctorNode != null && isIdentifierLike(ctorNode)) {
                    receiverTarget(bindings.get(sc.substringFrom(ctorNode).strip()), true)
                            .ifPresent(targets::add);
                }
                return;
            }
            if (isIdentifierLike(valueNode)) {
                receiverTarget(bindings.get(sc.substringFrom(valueNode).strip()), true)
                        .ifPresent(targets::add);
            }
        });
        return Set.copyOf(targets);
    }

    private static ShadowingIndex buildShadowingIndex(TSNode root, SourceContent sc) {
        var localNamesByScope = new java.util.HashMap<ScopeKey, Set<String>>();
        collectShadowingIndex(root, sc, localNamesByScope, false);
        collectShadowingBindings(root, sc, localNamesByScope);
        return new ShadowingIndex(Collections.unmodifiableMap(localNamesByScope));
    }

    private static void collectShadowingIndex(
            TSNode node, SourceContent sc, Map<ScopeKey, Set<String>> localNamesByScope, boolean insideFunctionLike) {
        if (node == null) {
            return;
        }

        boolean functionLike = isFunctionLike(node);
        boolean opensScope = functionLike || (STATEMENT_BLOCK.equals(node.getType()) && insideFunctionLike);
        if (opensScope) {
            localNamesByScope.put(scopeKey(node), new HashSet<>());
        }

        boolean childInsideFunction = insideFunctionLike || functionLike;
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null) {
                collectShadowingIndex(child, sc, localNamesByScope, childInsideFunction);
            }
        }
    }

    private static void collectShadowingBindings(
            TSNode node, SourceContent sc, Map<ScopeKey, Set<String>> localNamesByScope) {
        if (node == null) {
            return;
        }

        if (FORMAL_PARAMETERS.equals(node.getType())) {
            addParameterBindingNames(sc, localNamesByScope, nearestShadowScope(node), node);
        }

        if (isShadowingDeclarationNode(node)) {
            TSNode nameNode = node.getChildByFieldName("name");
            if (nameNode != null) {
                addLocalNames(sc, localNamesByScope, nearestShadowScope(node), nameNode);
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null) {
                collectShadowingBindings(child, sc, localNamesByScope);
            }
        }
    }

    private static boolean isShadowingDeclarationNode(TSNode node) {
        String type = node.getType();
        return VARIABLE_DECLARATOR.equals(type)
                || FUNCTION_DECLARATION.equals(type)
                || CLASS_DECLARATION.equals(type)
                || TypeScriptTreeSitterNodeTypes.INTERFACE_DECLARATION.equals(type)
                || TYPE_ALIAS_DECLARATION.equals(type);
    }

    private static void addLocalNames(
            SourceContent sc,
            Map<ScopeKey, Set<String>> localNamesByScope,
            @Nullable TSNode scopeNode,
            TSNode bindingNode) {
        if (scopeNode == null) {
            return;
        }
        Set<String> localNames = localNamesByScope.get(scopeKey(scopeNode));
        if (localNames == null) {
            return;
        }
        collectBoundNames(bindingNode, sc, localNames);
    }

    private static void addParameterBindingNames(
            SourceContent sc,
            Map<ScopeKey, Set<String>> localNamesByScope,
            @Nullable TSNode scopeNode,
            TSNode parametersNode) {
        if (scopeNode == null) {
            return;
        }
        for (TSNode parameter : parametersNode.getNamedChildren()) {
            if (parameter == null) {
                continue;
            }
            TSNode bindingNode = parameterBindingNode(parameter);
            if (bindingNode != null) {
                addLocalNames(sc, localNamesByScope, scopeNode, bindingNode);
            }
        }
    }

    private static @Nullable TSNode parameterBindingNode(TSNode parameterNode) {
        TSNode bindingNode = parameterNode.getChildByFieldName("pattern");
        if (bindingNode != null) {
            return bindingNode;
        }
        bindingNode = parameterNode.getChildByFieldName("name");
        if (bindingNode != null) {
            return bindingNode;
        }
        for (TSNode child : parameterNode.getNamedChildren()) {
            if (child == null) {
                continue;
            }
            String type = child.getType();
            if (TYPE_ANNOTATION.equals(type) || TypeScriptTreeSitterNodeTypes.ACCESSIBILITY_MODIFIER.equals(type)) {
                continue;
            }
            return child;
        }
        return null;
    }

    private static void collectBoundNames(TSNode node, SourceContent sc, Set<String> localNames) {
        if (node == null) {
            return;
        }
        if (isIdentifierLike(node)) {
            String localName = sc.substringFrom(node).strip();
            if (!localName.isEmpty()) {
                localNames.add(localName);
            }
            return;
        }
        for (TSNode child : node.getNamedChildren()) {
            if (child != null) {
                collectBoundNames(child, sc, localNames);
            }
        }
    }

    private static boolean isShadowed(TSNode node, String identifierName, ShadowingIndex shadowingIndex) {
        TSNode current = node.getParent();
        while (current != null) {
            Set<String> localNames = shadowingIndex.localNamesByScope().get(scopeKey(current));
            if (localNames != null && localNames.contains(identifierName)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static ScopeKey scopeKey(TSNode node) {
        return new ScopeKey(
                node.getStartByte(),
                node.getEndByte(),
                Optional.ofNullable(node.getType()).orElse(""));
    }

    private static @Nullable TSNode nearestShadowScope(TSNode node) {
        TSNode current = node.getParent();
        while (current != null) {
            TSNode parent = current.getParent();
            boolean insideFunctionLike = parent != null && isFunctionLike(parent);
            if (isFunctionLike(current) || (STATEMENT_BLOCK.equals(current.getType()) && insideFunctionLike)) {
                return current;
            }
            current = parent;
        }
        return null;
    }

    private static void collectReceiverTargetsFromType(
            TSNode node,
            SourceContent sc,
            Map<String, ImportBinder.ImportBinding> bindings,
            boolean instanceReceiver,
            Set<ReceiverTargetRef> targets,
            Set<String> visitedNames) {
        if (node == null) {
            return;
        }
        if (isIdentifierLike(node)) {
            String identifier = sc.substringFrom(node).strip();
            if (visitedNames.add(identifier)) {
                receiverTarget(bindings.get(identifier), instanceReceiver).ifPresent(targets::add);
            }
            return;
        }
        for (TSNode child : node.getNamedChildren()) {
            if (child != null) {
                collectReceiverTargetsFromType(child, sc, bindings, instanceReceiver, targets, visitedNames);
            }
        }
    }

    private static Optional<ReceiverTargetRef> receiverTarget(
            @Nullable ImportBinder.ImportBinding binding, boolean instanceReceiver) {
        if (binding == null || binding.kind() == ImportBinder.ImportKind.NAMESPACE || binding.importedName() == null) {
            return Optional.empty();
        }
        return Optional.of(new ReceiverTargetRef(
                binding.moduleSpecifier(),
                binding.importedName(),
                instanceReceiver,
                instanceReceiver ? 0.95 : 1.0,
                null));
    }

    private static @Nullable TSNode findFirstDescendant(TSNode node, String type) {
        if (node == null) {
            return null;
        }
        if (type.equals(node.getType())) {
            return node;
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child == null) {
                continue;
            }
            TSNode match = findFirstDescendant(child, type);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static boolean isWithinTypeAnnotation(TSNode node, TSNode stopNode) {
        TSNode current = node;
        while (current != null && !current.equals(stopNode)) {
            if (TYPE_ANNOTATION.equals(current.getType())) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static void addCandidate(
            Set<ReferenceCandidate> candidates,
            JsTsAnalyzer analyzer,
            ProjectFile file,
            TSNode node,
            CodeUnit fallbackEnclosing,
            String identifier,
            @Nullable String qualifier,
            @Nullable String ownerIdentifier,
            boolean instanceReceiver,
            ReferenceKind kind) {
        IAnalyzer.Range range = rangeOf(node);
        CodeUnit enclosing = analyzer.enclosingCodeUnit(file, range).orElse(fallbackEnclosing);
        candidates.add(new ReferenceCandidate(
                identifier, qualifier, ownerIdentifier, instanceReceiver, kind, range, enclosing));
    }

    private static void collectClassMetadata(
            TSNode root,
            SourceContent sc,
            Set<ExportIndex.HeritageEdge> heritageEdges,
            Set<ExportIndex.ClassMember> classMembers) {
        walk(root, node -> {
            String nodeType = node.getType();
            if (CLASS_DECLARATION.equals(nodeType)
                    || TypeScriptTreeSitterNodeTypes.INTERFACE_DECLARATION.equals(nodeType)) {
                collectHeritageEdges(node, sc, heritageEdges);
            }
            if (CLASS_DECLARATION.equals(nodeType)) {
                collectClassMembers(node, sc, classMembers);
            }
        });
    }

    private static void collectHeritageEdges(
            TSNode classLikeNode, SourceContent sc, Set<ExportIndex.HeritageEdge> heritageEdges) {
        TSNode nameNode = classLikeNode.getChildByFieldName("name");
        if (nameNode == null) {
            return;
        }
        String childName = sc.substringFrom(nameNode).strip();
        walk(classLikeNode, node -> {
            if (!CLASS_HERITAGE.equals(node.getType())
                    && !EXTENDS_CLAUSE.equals(node.getType())
                    && !IMPLEMENTS_CLAUSE.equals(node.getType())) {
                return;
            }
            collectReferencedTypeNames(node, sc, childName, heritageEdges);
        });
    }

    private static void collectReferencedTypeNames(
            TSNode node, SourceContent sc, String childName, Set<ExportIndex.HeritageEdge> heritageEdges) {
        if (isIdentifierLike(node)) {
            String parentName = sc.substringFrom(node).strip();
            if (!parentName.isEmpty()) {
                heritageEdges.add(new ExportIndex.HeritageEdge(childName, parentName));
            }
            return;
        }
        for (TSNode child : node.getNamedChildren()) {
            if (child != null) {
                collectReferencedTypeNames(child, sc, childName, heritageEdges);
            }
        }
    }

    private static void collectClassMembers(
            TSNode classNode, SourceContent sc, Set<ExportIndex.ClassMember> classMembers) {
        TSNode nameNode = classNode.getChildByFieldName("name");
        if (nameNode == null) {
            return;
        }
        String className = sc.substringFrom(nameNode).strip();
        for (TSNode child : classNode.getNamedChildren()) {
            if (child == null || !CLASS_BODY.equals(child.getType())) {
                continue;
            }
            for (TSNode member : child.getNamedChildren()) {
                if (member == null || !isClassMemberNode(member)) {
                    continue;
                }
                TSNode memberNameNode = member.getChildByFieldName("name");
                if (memberNameNode == null || !isIdentifierLike(memberNameNode)) {
                    continue;
                }
                classMembers.add(new ExportIndex.ClassMember(
                        className,
                        sc.substringFrom(memberNameNode).strip(),
                        hasModifier(member, STATIC),
                        memberKindOf(member)));
            }
        }
    }

    private static CodeUnitType memberKindOf(TSNode member) {
        String type = member.getType();
        if (METHOD_DEFINITION.equals(type) || ABSTRACT_METHOD_SIGNATURE.equals(type) || METHOD_SIGNATURE.equals(type)) {
            return CodeUnitType.FUNCTION;
        }
        return CodeUnitType.FIELD;
    }

    private static boolean isClassMemberNode(TSNode node) {
        String type = node.getType();
        return METHOD_DEFINITION.equals(type)
                || PUBLIC_FIELD_DEFINITION.equals(type)
                || ABSTRACT_METHOD_SIGNATURE.equals(type)
                || METHOD_SIGNATURE.equals(type);
    }

    private static boolean hasModifier(TSNode node, String modifierType) {
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && modifierType.equals(child.getType())) {
                return true;
            }
        }
        return false;
    }

    private interface NodeVisitor {
        void visit(TSNode node);
    }

    private static void walk(TSNode node, NodeVisitor visitor) {
        if (node == null) {
            return;
        }
        visitor.visit(node);
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null) {
                walk(child, visitor);
            }
        }
    }

    private static boolean isWithinImportStatement(TSNode node) {
        TSNode current = node;
        while (current != null) {
            String type = current.getType();
            if (IMPORT_STATEMENT.equals(type)
                    || IMPORT_DECLARATION.equals(type)
                    || TypeScriptTreeSitterNodeTypes.IMPORT_DECLARATION.equals(type)) {
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
        TSNode nameNode = parent.getChildByFieldName("name");
        if (nameNode == null && parent.getNamedChildCount() > 0) {
            nameNode = parent.getNamedChild(0);
        }
        if (VARIABLE_DECLARATOR.equals(type) && identifierNode.equals(nameNode)) {
            return true;
        }
        if (FUNCTION_DECLARATION.equals(type) && identifierNode.equals(nameNode)) {
            return true;
        }
        if (CLASS_DECLARATION.equals(type) && identifierNode.equals(nameNode)) {
            return true;
        }
        if (TypeScriptTreeSitterNodeTypes.INTERFACE_DECLARATION.equals(type) && identifierNode.equals(nameNode)) {
            return true;
        }
        if (TYPE_ALIAS_DECLARATION.equals(type) && identifierNode.equals(nameNode)) {
            return true;
        }
        return false;
    }

    private static boolean isTypeReferenceNode(TSNode node) {
        if (!isIdentifierLike(node)) {
            return false;
        }
        TSNode current = node.getParent();
        while (current != null) {
            String type = current.getType();
            if (TYPE_ANNOTATION.equals(type)
                    || TYPE_ALIAS_DECLARATION.equals(type)
                    || TYPE_ARGUMENTS.equals(type)
                    || TYPE_QUERY.equals(type)
                    || IMPLEMENTS_CLAUSE.equals(type)
                    || EXTENDS_CLAUSE.equals(type)) {
                return true;
            }
            if (CALL_EXPRESSION.equals(type) || MEMBER_EXPRESSION.equals(type) || OBJECT_TYPE.equals(type)) {
                return false;
            }
            current = current.getParent();
        }
        return false;
    }

    private static boolean isTypeContextRoot(TSNode node) {
        String type = node.getType();
        return TYPE_ANNOTATION.equals(type)
                || TYPE_ALIAS_DECLARATION.equals(type)
                || TYPE_ARGUMENTS.equals(type)
                || TYPE_QUERY.equals(type)
                || CLASS_HERITAGE.equals(type)
                || IMPLEMENTS_CLAUSE.equals(type)
                || EXTENDS_CLAUSE.equals(type);
    }

    private static void collectDeclaredLocalNames(@Nullable TSNode node, SourceContent sc, Set<String> localNames) {
        if (node == null) {
            return;
        }
        String type = node.getType();
        if (isIdentifierLike(node) || SHORTHAND_PROPERTY_IDENTIFIER_PATTERN.equals(type)) {
            String localName = sc.substringFrom(node).strip();
            if (!localName.isEmpty()) {
                localNames.add(localName);
            }
            return;
        }
        if (PAIR_PATTERN.equals(type)) {
            collectDeclaredLocalNames(node.getChildByFieldName(VALUE), sc, localNames);
            return;
        }
        if (!OBJECT_PATTERN.equals(type) && !ARRAY_PATTERN.equals(type)) {
            return;
        }
        for (TSNode child : node.getNamedChildren()) {
            if (child != null) {
                collectDeclaredLocalNames(child, sc, localNames);
            }
        }
    }

    private static @Nullable String enclosingClassName(TSNode node, SourceContent sc) {
        TSNode current = node;
        while (current != null) {
            if (CLASS_DECLARATION.equals(current.getType())) {
                TSNode nameNode = current.getChildByFieldName("name");
                return nameNode != null ? sc.substringFrom(nameNode).strip() : null;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isExportedClass(JsTsAnalyzer analyzer, ProjectFile file, String localClassName) {
        return analyzer.exportIndexOf(file).exportsByName().values().stream().anyMatch(export -> {
            if (export instanceof ExportIndex.LocalExport local) {
                return localClassName.equals(local.localName());
            }
            return export instanceof ExportIndex.DefaultExport localDefault
                    && localDefault.localName() != null
                    && localClassName.equals(localDefault.localName());
        });
    }

    private static boolean isIdentifierLike(TSNode node) {
        String type = node.getType();
        return TypeScriptTreeSitterNodeTypes.IDENTIFIER.equals(type)
                || PROPERTY_IDENTIFIER.equals(type)
                || TYPE_IDENTIFIER.equals(type)
                || NESTED_TYPE_IDENTIFIER.equals(type);
    }

    private static boolean isMethodCallMember(TSNode memberExpression) {
        TSNode parent = memberExpression.getParent();
        return parent != null
                && CALL_EXPRESSION.equals(parent.getType())
                && memberExpression.equals(parent.getChildByFieldName("function"));
    }

    private static IAnalyzer.Range rangeOf(TSNode node) {
        TSPoint start = node.getStartPoint();
        TSPoint end = node.getEndPoint();
        return new IAnalyzer.Range(
                node.getStartByte(), node.getEndByte(), start.getRow(), end.getRow(), node.getStartByte());
    }

    public static Map<ProjectFile, Set<ProjectFile>> buildReverseReexportIndex(JsTsAnalyzer jsTs) {
        var reverse = new java.util.HashMap<ProjectFile, Set<ProjectFile>>();
        for (ProjectFile file : jsTs.getAnalyzedFiles()) {
            if (!isJsTs(file)) {
                continue;
            }

            ExportIndex idx = jsTs.exportIndexOf(file);
            for (ExportIndex.ExportEntry entry : idx.exportsByName().values()) {
                if (entry instanceof ExportIndex.ReexportedNamed named) {
                    jsTs.resolveEsmModule(file, named.moduleSpecifier())
                            .ifPresent(target -> reverse.computeIfAbsent(target, ignored -> new LinkedHashSet<>())
                                    .add(file));
                }
            }
            for (ExportIndex.ReexportStar star : idx.reexportStars()) {
                jsTs.resolveEsmModule(file, star.moduleSpecifier())
                        .ifPresent(target -> reverse.computeIfAbsent(target, ignored -> new LinkedHashSet<>())
                                .add(file));
            }
        }
        return Map.copyOf(reverse);
    }

    public static Map<JsTsAnalyzer.ReverseExportSeedKey, Set<JsTsAnalyzer.ExportSeed>> buildReverseExportSeedIndex(
            JsTsAnalyzer jsTs) {
        var reverse = new java.util.HashMap<JsTsAnalyzer.ReverseExportSeedKey, Set<JsTsAnalyzer.ExportSeed>>();
        for (ProjectFile file : jsTs.getAnalyzedFiles()) {
            if (!isJsTs(file)) {
                continue;
            }

            ExportIndex idx = jsTs.exportIndexOf(file);
            ImportBinder binder = jsTs.importBinderOf(file);
            for (Map.Entry<String, ExportIndex.ExportEntry> export :
                    idx.exportsByName().entrySet()) {
                String exportedName = export.getKey();
                ExportIndex.ExportEntry entry = export.getValue();
                if (entry instanceof ExportIndex.ReexportedNamed named) {
                    jsTs.resolveEsmModule(file, named.moduleSpecifier()).ifPresent(target -> reverse.computeIfAbsent(
                                    new JsTsAnalyzer.ReverseExportSeedKey(target, named.importedName()),
                                    ignored -> new LinkedHashSet<>())
                            .add(new JsTsAnalyzer.ExportSeed(file, exportedName)));
                    continue;
                }
                if (!(entry instanceof ExportIndex.LocalExport local)) {
                    continue;
                }
                ImportBinder.ImportBinding binding = binder.bindings().get(local.localName());
                if (binding == null || binding.importedName() == null) {
                    continue;
                }
                String importedName = binding.importedName();
                jsTs.resolveEsmModule(file, binding.moduleSpecifier()).ifPresent(target -> reverse.computeIfAbsent(
                                new JsTsAnalyzer.ReverseExportSeedKey(target, importedName),
                                ignored -> new LinkedHashSet<>())
                        .add(new JsTsAnalyzer.ExportSeed(file, exportedName)));
            }
        }
        return Map.copyOf(reverse);
    }

    public static void ensureImportReverseIndexPopulated(JsTsAnalyzer jsTs, ImportAnalysisProvider provider)
            throws InterruptedException {
        for (ProjectFile file : jsTs.getAnalyzedFiles()) {
            if (!isJsTs(file)) {
                continue;
            }
            provider.importedCodeUnitsOf(file);
        }
    }

    public static Map<String, Set<String>> buildHeritageIndex(JsTsAnalyzer jsTs) {
        var edges = new java.util.HashMap<String, Set<String>>();
        for (ProjectFile file : jsTs.getAnalyzedFiles()) {
            if (!isJsTs(file)) {
                continue;
            }
            ExportIndex idx = jsTs.exportIndexOf(file);
            for (ExportIndex.HeritageEdge edge : idx.heritageEdges()) {
                for (String childKey : resolveHeritageClassKeys(jsTs, file, edge.childName())) {
                    var parents =
                            edges.computeIfAbsent(childKey, ignored -> new LinkedHashSet<String>());
                    parents.addAll(resolveHeritageClassKeys(jsTs, file, edge.parentName()));
                }
            }
        }
        return Map.copyOf(edges);
    }

    private static Set<String> resolveHeritageClassKeys(JsTsAnalyzer jsTs, ProjectFile file, String className) {
        var resolved = new LinkedHashSet<String>();
        jsTs.getDeclarations(file).stream()
                .filter(cu -> cu.kind() == ai.brokk.analyzer.CodeUnitType.CLASS)
                .filter(cu -> cu.identifier().equals(className))
                .map(JsTsExportUsageExtractor::qualifiedClassKey)
                .forEach(resolved::add);

        ImportBinder.ImportBinding binding = jsTs.importBinderOf(file).bindings().get(className);
        if (binding != null && binding.importedName() != null) {
            JsTsAnalyzer.ResolutionOutcome imported = jsTs.resolveEsmModuleOutcome(file, binding.moduleSpecifier());
            imported.resolved().ifPresent(importedFile -> {
                if (binding.kind() == ImportBinder.ImportKind.DEFAULT) {
                    resolved.addAll(jsTs.getDeclarations(importedFile).stream()
                            .filter(cu -> cu.kind() == ai.brokk.analyzer.CodeUnitType.CLASS)
                            .filter(cu -> "default".equals(cu.identifier()) || className.equals(cu.identifier()))
                            .map(JsTsExportUsageExtractor::qualifiedClassKey)
                            .toList());
                } else {
                    resolved.addAll(jsTs.getDeclarations(importedFile).stream()
                            .filter(cu -> cu.kind() == ai.brokk.analyzer.CodeUnitType.CLASS)
                            .filter(cu -> cu.identifier().equals(binding.importedName()))
                            .map(JsTsExportUsageExtractor::qualifiedClassKey)
                            .toList());
                }
            });
        }

        if (resolved.isEmpty()) {
            resolved.add(qualifiedClassKey(file, className));
        }
        return Set.copyOf(resolved);
    }

    private static String qualifiedClassKey(ai.brokk.analyzer.CodeUnit codeUnit) {
        return qualifiedClassKey(codeUnit.source(), codeUnit.identifier());
    }

    private static String qualifiedClassKey(ProjectFile file, String className) {
        return file.getRelPath().normalize() + ":" + className;
    }

    private static boolean isJsTs(ProjectFile file) {
        Language lang = Languages.fromExtension(file.extension());
        return lang.contains(Languages.JAVASCRIPT) || lang.contains(Languages.TYPESCRIPT);
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
