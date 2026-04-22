package ai.brokk.analyzer.javascript;

import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.ARROW_FUNCTION;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.CALL_EXPRESSION;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.CLASS_DECLARATION;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.FUNCTION_DECLARATION;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.IMPORT_DECLARATION;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.METHOD_DEFINITION;
import static ai.brokk.analyzer.javascript.JavaScriptTreeSitterNodeTypes.VARIABLE_DECLARATOR;

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
    private static final String IMPORT_STATEMENT = "import_statement";
    private static final String CLASS_BODY = "class_body";
    private static final String CLASS_HERITAGE = "class_heritage";
    private static final String EXTENDS_CLAUSE = "extends_clause";
    private static final String IMPLEMENTS_CLAUSE = "implements_clause";
    private static final String TYPE_ALIAS_DECLARATION = "type_alias_declaration";
    private static final String TYPE_ANNOTATION = "type_annotation";
    private static final String TYPE_ARGUMENTS = "type_arguments";
    private static final String PROPERTY_IDENTIFIER = "property_identifier";
    private static final String PUBLIC_FIELD_DEFINITION = "public_field_definition";
    private static final String ABSTRACT_METHOD_SIGNATURE = "abstract_method_signature";
    private static final String METHOD_SIGNATURE = "method_signature";
    private static final String OBJECT_TYPE = "object_type";
    private static final String TYPE_IDENTIFIER = "type_identifier";
    private static final String NESTED_TYPE_IDENTIFIER = "nested_type_identifier";
    private static final String NEW_EXPRESSION = "new_expression";
    private static final String MEMBER_EXPRESSION = "member_expression";
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
        CodeUnit fallbackEnclosing = analyzer.enclosingCodeUnit(file, new IAnalyzer.Range(0, 0, 0, 0, 0))
                .orElseGet(() -> CodeUnit.module(file, "", file.getFileName()));

        try {
            collectReferenceCandidates(root, analyzer, file, sc, bindings, fallbackEnclosing, candidates);
        } catch (Exception e) {
            logger.debug("Tree-sitter export usage candidate extraction failed for {}", file, e);
            return Set.of();
        }

        return Set.copyOf(candidates);
    }

    private static void collectReferenceCandidates(
            TSNode node,
            JsTsAnalyzer analyzer,
            ProjectFile file,
            SourceContent sc,
            Map<String, ImportBinder.ImportBinding> bindings,
            CodeUnit fallbackEnclosing,
            Set<ReferenceCandidate> candidates) {
        if (node == null) {
            return;
        }

        if (isTypeContextRoot(node)) {
            collectTypeReferenceCandidates(node, analyzer, file, sc, bindings, fallbackEnclosing, candidates);
        }
        addIdentifierCandidate(node, analyzer, file, sc, bindings, fallbackEnclosing, candidates);
        addMemberCandidate(node, analyzer, file, sc, bindings, fallbackEnclosing, candidates);

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null) {
                collectReferenceCandidates(child, analyzer, file, sc, bindings, fallbackEnclosing, candidates);
            }
        }
    }

    private static void collectTypeReferenceCandidates(
            TSNode node,
            JsTsAnalyzer analyzer,
            ProjectFile file,
            SourceContent sc,
            Map<String, ImportBinder.ImportBinding> bindings,
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
                    && !isShadowedInEnclosingFunction(analyzer, node, text, sc)) {
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
                collectTypeReferenceCandidates(child, analyzer, file, sc, bindings, fallbackEnclosing, candidates);
            }
        }
    }

    private static void addIdentifierCandidate(
            TSNode node,
            JsTsAnalyzer analyzer,
            ProjectFile file,
            SourceContent sc,
            Map<String, ImportBinder.ImportBinding> bindings,
            CodeUnit fallbackEnclosing,
            Set<ReferenceCandidate> candidates) {
        if (!isIdentifierLike(node)) {
            return;
        }

        String identifier = sc.substringFrom(node).strip();
        if (!bindings.containsKey(identifier) || isWithinImportStatement(node) || isDeclarationIdentifier(node)) {
            return;
        }
        if (isShadowedInEnclosingFunction(analyzer, node, identifier, sc)) {
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
            CodeUnit fallbackEnclosing,
            Set<ReferenceCandidate> candidates) {
        if (!MEMBER_EXPRESSION.equals(node.getType())) {
            return;
        }

        TSNode objectNode = node.getChildByFieldName("object");
        TSNode propertyNode = node.getChildByFieldName("property");
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

        if (isIdentifierLike(objectNode)) {
            String qualifier = sc.substringFrom(objectNode).strip();
            ImportBinder.ImportBinding binding = bindings.get(qualifier);
            if (binding == null || isShadowedInEnclosingFunction(analyzer, objectNode, qualifier, sc)) {
                return;
            }

            ReferenceKind kind = isMethodCall ? ReferenceKind.METHOD_CALL : ReferenceKind.FIELD_READ;
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
                        kind);
                return;
            }

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
                    kind);
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
                        && !isShadowedInEnclosingFunction(analyzer, namespaceNode, qualifier, sc)) {
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
            if (ctorNode != null && isIdentifierLike(ctorNode)) {
                String qualifier = sc.substringFrom(ctorNode).strip();
                if (bindings.containsKey(qualifier)
                        && !isShadowedInEnclosingFunction(analyzer, ctorNode, qualifier, sc)) {
                    addCandidate(
                            candidates,
                            analyzer,
                            file,
                            propertyNode,
                            fallbackEnclosing,
                            property,
                            qualifier,
                            null,
                            true,
                            isMethodCall ? ReferenceKind.METHOD_CALL : ReferenceKind.FIELD_READ);
                }
            }
        }
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
                        className, sc.substringFrom(memberNameNode).strip(), hasModifier(member, STATIC)));
            }
        }
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
        if (VARIABLE_DECLARATOR.equals(type) && identifierNode.equals(parent.getChildByFieldName("name"))) {
            return true;
        }
        if (FUNCTION_DECLARATION.equals(type) && identifierNode.equals(parent.getChildByFieldName("name"))) {
            return true;
        }
        if (CLASS_DECLARATION.equals(type) && identifierNode.equals(parent.getChildByFieldName("name"))) {
            return true;
        }
        if (TypeScriptTreeSitterNodeTypes.INTERFACE_DECLARATION.equals(type)
                && identifierNode.equals(parent.getChildByFieldName("name"))) {
            return true;
        }
        if (TYPE_ALIAS_DECLARATION.equals(type) && identifierNode.equals(parent.getChildByFieldName("name"))) {
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
                || CLASS_HERITAGE.equals(type)
                || IMPLEMENTS_CLAUSE.equals(type)
                || EXTENDS_CLAUSE.equals(type);
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
            logger.debug("Tree-sitter shadowing query failed; falling back to text scan", e);
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
            String type = Optional.ofNullable(current.getType()).orElse("");
            if (FUNCTION_DECLARATION.equals(type)
                    || "function".equals(type)
                    || ARROW_FUNCTION.equals(type)
                    || METHOD_DEFINITION.equals(type)) {
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
