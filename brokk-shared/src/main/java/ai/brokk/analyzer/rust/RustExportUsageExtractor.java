package ai.brokk.analyzer.rust;

import static ai.brokk.analyzer.rust.Constants.nodeField;
import static ai.brokk.analyzer.rust.Constants.nodeType;
import static org.treesitter.RustNodeType.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.RustAnalyzer;
import ai.brokk.analyzer.SourceContent;
import ai.brokk.analyzer.usages.ExportIndex;
import ai.brokk.analyzer.usages.ImportBinder;
import ai.brokk.analyzer.usages.ReferenceCandidate;
import ai.brokk.analyzer.usages.ReferenceKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.treesitter.RustNodeField;
import org.treesitter.TSNode;

public final class RustExportUsageExtractor {
    private RustExportUsageExtractor() {}

    public static ExportIndex computeExportIndex(TSNode root, SourceContent source) {
        var exports = new LinkedHashMap<String, ExportIndex.ExportEntry>();
        collectExports(root, source, exports);
        return new ExportIndex(Map.copyOf(exports), List.of(), Set.of(), Set.of());
    }

    public static ImportBinder computeImportBinder(
            RustAnalyzer analyzer,
            ProjectFile file,
            TSNode root,
            SourceContent source,
            Set<String> localTopLevelNames) {
        var bindings = new LinkedHashMap<String, ImportBinder.ImportBinding>();
        collectUseDeclarations(root).stream()
                .flatMap(use -> useSpecsOf(use, source).stream())
                .filter(spec -> !spec.wildcard())
                .forEach(spec -> {
                    String localName = spec.localName();
                    if (localName == null || localTopLevelNames.contains(localName)) {
                        return;
                    }
                    if (analyzer.resolveRustModuleOutcome(file, spec.path())
                            .resolved()
                            .isPresent()) {
                        bindings.put(
                                localName,
                                new ImportBinder.ImportBinding(spec.path(), ImportBinder.ImportKind.NAMESPACE, null));
                        return;
                    }
                    splitModuleAndName(spec.path())
                            .ifPresent(parts -> bindings.put(
                                    localName,
                                    new ImportBinder.ImportBinding(
                                            parts.moduleSpecifier(),
                                            ImportBinder.ImportKind.NAMED,
                                            parts.importedName())));
                });
        return new ImportBinder(Map.copyOf(bindings));
    }

    public static List<UseSpec> useSpecsOf(TSNode useDeclaration, SourceContent source) {
        TSNode argument = useDeclaration.getChildByFieldName(nodeField(RustNodeField.ARGUMENT));
        if (argument == null) {
            return List.of();
        }
        var specs = new ArrayList<UseSpec>();
        collectUseSpecs(argument, source, List.of(), specs);
        return List.copyOf(specs);
    }

    public static Set<ReferenceCandidate> computeUsageCandidates(
            RustAnalyzer analyzer,
            ProjectFile file,
            TSNode root,
            SourceContent source,
            ImportBinder binder,
            Set<String> localExportNames) {
        CodeUnit fallbackEnclosing = analyzer.enclosingCodeUnit(file, rangeOf(root))
                .orElse(CodeUnit.module(file, analyzer.packageNameOf(file), "_module_"));
        var candidates = new LinkedHashSet<ReferenceCandidate>();
        collectUsageCandidates(root, analyzer, file, source, binder, localExportNames, fallbackEnclosing, candidates);
        return Set.copyOf(candidates);
    }

    private static void collectExports(
            TSNode node, SourceContent source, Map<String, ExportIndex.ExportEntry> exports) {
        if (node == null) {
            return;
        }
        String type = node.getType();
        if (nodeType(USE_DECLARATION).equals(type)) {
            collectReexports(node, source, exports);
            return;
        }
        if (isExportableItem(node) && isItemLevelDeclaration(node) && isPublic(node)) {
            localNameOf(node, source).ifPresent(name -> exports.put(name, new ExportIndex.LocalExport(name)));
        }
        for (TSNode child : node.getNamedChildren()) {
            collectExports(child, source, exports);
        }
    }

    private static void collectReexports(
            TSNode useDeclaration, SourceContent source, Map<String, ExportIndex.ExportEntry> exports) {
        if (!isPublic(useDeclaration)) {
            return;
        }
        useSpecsOf(useDeclaration, source).stream()
                .filter(spec -> !spec.wildcard())
                .forEach(spec -> splitModuleAndName(spec.path()).ifPresent(parts -> {
                    String exportName = spec.alias() != null ? spec.alias() : parts.importedName();
                    exports.put(
                            exportName, new ExportIndex.ReexportedNamed(parts.moduleSpecifier(), parts.importedName()));
                }));
    }

    private static boolean isExportableItem(TSNode node) {
        String type = node.getType();
        return nodeType(STRUCT_ITEM).equals(type)
                || nodeType(ENUM_ITEM).equals(type)
                || nodeType(TRAIT_ITEM).equals(type)
                || nodeType(FUNCTION_ITEM).equals(type)
                || nodeType(CONST_ITEM).equals(type)
                || nodeType(STATIC_ITEM).equals(type)
                || nodeType(TYPE_ITEM).equals(type);
    }

    private static boolean isItemLevelDeclaration(TSNode node) {
        TSNode parent = node.getParent();
        if (parent == null) {
            return true;
        }
        String parentType = parent.getType();
        return !nodeType(DECLARATION_LIST).equals(parentType)
                && !nodeType(FIELD_DECLARATION_LIST).equals(parentType)
                && !nodeType(ENUM_VARIANT_LIST).equals(parentType);
    }

    private static boolean isPublic(TSNode node) {
        for (TSNode child : node.getChildren()) {
            if (nodeType(VISIBILITY_MODIFIER).equals(child.getType())) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> localNameOf(TSNode node, SourceContent source) {
        TSNode name = node.getChildByFieldName(nodeField(RustNodeField.NAME));
        return name == null
                ? Optional.empty()
                : Optional.of(source.substringFrom(name).strip()).filter(s -> !s.isBlank());
    }

    private static void collectUsageCandidates(
            TSNode node,
            RustAnalyzer analyzer,
            ProjectFile file,
            SourceContent source,
            ImportBinder binder,
            Set<String> localExportNames,
            CodeUnit fallbackEnclosing,
            Set<ReferenceCandidate> candidates) {
        if (node == null || nodeType(USE_DECLARATION).equals(node.getType())) {
            return;
        }
        if (isDeclarationName(node, source)) {
            return;
        }

        if (nodeType(SCOPED_IDENTIFIER).equals(node.getType())
                || nodeType(SCOPED_TYPE_IDENTIFIER).equals(node.getType())) {
            List<String> chain = pathSegments(node, source);
            if (chain.size() >= 2) {
                String first = chain.getFirst();
                candidates.add(new ReferenceCandidate(
                        chain.getLast(),
                        binder.bindings().containsKey(first) ? first : null,
                        binder.bindings().containsKey(first) ? null : first,
                        false,
                        ReferenceKind.STATIC_REFERENCE,
                        rangeOf(node),
                        enclosing(analyzer, file, node, fallbackEnclosing)));
            }
        }

        if (isReferenceIdentifier(node)) {
            String identifier = source.substringFrom(node).strip();
            if (binder.bindings().containsKey(identifier) || localExportNames.contains(identifier)) {
                candidates.add(new ReferenceCandidate(
                        identifier,
                        null,
                        null,
                        false,
                        referenceKindOf(node),
                        rangeOf(node),
                        enclosing(analyzer, file, node, fallbackEnclosing)));
            }
        }

        for (TSNode child : node.getNamedChildren()) {
            collectUsageCandidates(
                    child, analyzer, file, source, binder, localExportNames, fallbackEnclosing, candidates);
        }
    }

    private static boolean isReferenceIdentifier(TSNode node) {
        String type = node.getType();
        return nodeType(IDENTIFIER).equals(type) || nodeType(TYPE_IDENTIFIER).equals(type);
    }

    private static ReferenceKind referenceKindOf(TSNode node) {
        return nodeType(TYPE_IDENTIFIER).equals(node.getType())
                ? ReferenceKind.TYPE_REFERENCE
                : ReferenceKind.STATIC_REFERENCE;
    }

    private static CodeUnit enclosing(RustAnalyzer analyzer, ProjectFile file, TSNode node, CodeUnit fallback) {
        return analyzer.enclosingCodeUnit(file, rangeOf(node)).orElse(fallback);
    }

    private static boolean isDeclarationName(TSNode node, SourceContent source) {
        TSNode parent = node.getParent();
        if (parent == null || !isExportableItem(parent)) {
            return false;
        }
        TSNode name = parent.getChildByFieldName(nodeField(RustNodeField.NAME));
        return name != null
                && name.getStartByte() == node.getStartByte()
                && name.getEndByte() == node.getEndByte()
                && source.substringFrom(name).equals(source.substringFrom(node));
    }

    private static List<String> pathSegments(TSNode node, SourceContent source) {
        var segments = new ArrayList<String>();
        collectPathSegments(node, source, segments);
        segments.removeIf(RustExportUsageExtractor::isRustPathKeyword);
        return List.copyOf(segments);
    }

    private static IAnalyzer.Range rangeOf(TSNode node) {
        return new IAnalyzer.Range(
                node.getStartByte(),
                node.getEndByte(),
                node.getStartPoint().getRow(),
                node.getEndPoint().getRow(),
                node.getStartByte());
    }

    private static void collectUseSpecs(
            TSNode node, SourceContent source, List<String> prefixSegments, List<UseSpec> specs) {
        String type = node.getType();
        if (nodeType(SCOPED_USE_LIST).equals(type)) {
            List<String> path =
                    pathSegmentsPreservingKeywords(node.getChildByFieldName(nodeField(RustNodeField.PATH)), source);
            TSNode list = node.getChildByFieldName(nodeField(RustNodeField.LIST));
            var nextPrefix = concat(prefixSegments, path);
            if (list != null) {
                collectUseSpecs(list, source, nextPrefix, specs);
            }
            return;
        }
        if (nodeType(USE_LIST).equals(type)) {
            for (TSNode child : node.getNamedChildren()) {
                collectUseSpecs(child, source, prefixSegments, specs);
            }
            return;
        }
        if (nodeType(USE_AS_CLAUSE).equals(type)) {
            List<String> path =
                    pathSegmentsPreservingKeywords(node.getChildByFieldName(nodeField(RustNodeField.PATH)), source);
            TSNode aliasNode = node.getChildByFieldName(nodeField(RustNodeField.ALIAS));
            String alias =
                    aliasNode == null ? null : source.substringFrom(aliasNode).strip();
            addUseSpec(concat(prefixSegments, path), alias, false, specs);
            return;
        }
        if (nodeType(USE_WILDCARD).equals(type)) {
            List<String> path = pathSegmentsPreservingKeywords(node, source);
            addUseSpec(concat(concat(prefixSegments, path), List.of("*")), null, true, specs);
            return;
        }
        List<String> path = pathSegmentsPreservingKeywords(node, source);
        if (path.size() == 1 && "self".equals(path.getFirst()) && !prefixSegments.isEmpty()) {
            addUseSpec(prefixSegments, null, false, specs);
            return;
        }
        addUseSpec(concat(prefixSegments, path), null, false, specs);
    }

    private static void addUseSpec(
            List<String> segments, @Nullable String alias, boolean wildcard, List<UseSpec> specs) {
        if (segments.isEmpty()) {
            return;
        }
        String path = String.join("::", segments);
        String localName = wildcard ? null : alias != null ? alias : localNameOfUsePath(segments);
        specs.add(new UseSpec(path, alias, localName, wildcard));
    }

    private static String localNameOfUsePath(List<String> segments) {
        int last = segments.size() - 1;
        if ("self".equals(segments.get(last)) && last > 0) {
            return segments.get(last - 1);
        }
        return segments.get(last);
    }

    private static List<String> pathSegmentsPreservingKeywords(@Nullable TSNode node, SourceContent source) {
        var segments = new ArrayList<String>();
        collectPathSegments(node, source, segments);
        return List.copyOf(segments);
    }

    private static void collectPathSegments(@Nullable TSNode node, SourceContent source, List<String> segments) {
        if (node == null) {
            return;
        }
        String type = node.getType();
        if (nodeType(IDENTIFIER).equals(type)
                || nodeType(TYPE_IDENTIFIER).equals(type)
                || nodeType(CRATE).equals(type)
                || nodeType(SELF).equals(type)
                || nodeType(SUPER_).equals(type)) {
            segments.add(source.substringFrom(node).strip());
            return;
        }
        if (nodeType(SCOPED_IDENTIFIER).equals(type)
                || nodeType(SCOPED_TYPE_IDENTIFIER).equals(type)) {
            collectPathSegments(node.getChildByFieldName(nodeField(RustNodeField.PATH)), source, segments);
            collectPathSegments(node.getChildByFieldName(nodeField(RustNodeField.NAME)), source, segments);
            return;
        }
        if (nodeType(GENERIC_TYPE).equals(type)
                || nodeType(GENERIC_TYPE_WITH_TURBOFISH).equals(type)) {
            collectPathSegments(node.getChildByFieldName(nodeField(RustNodeField.TYPE)), source, segments);
            return;
        }
        for (TSNode child : node.getNamedChildren()) {
            collectPathSegments(child, source, segments);
        }
    }

    private static boolean isRustPathKeyword(String segment) {
        return "crate".equals(segment) || "self".equals(segment) || "super".equals(segment);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        var combined = new ArrayList<String>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private static List<TSNode> collectUseDeclarations(TSNode root) {
        var declarations = new ArrayList<TSNode>();
        collectUseDeclarations(root, declarations);
        return List.copyOf(declarations);
    }

    private static void collectUseDeclarations(TSNode node, List<TSNode> declarations) {
        if (node == null) {
            return;
        }
        if (nodeType(USE_DECLARATION).equals(node.getType())) {
            declarations.add(node);
            return;
        }
        for (TSNode child : node.getNamedChildren()) {
            collectUseDeclarations(child, declarations);
        }
    }

    private static Optional<PathParts> splitModuleAndName(String path) {
        int sep = path.lastIndexOf("::");
        if (sep < 0) {
            return Optional.empty();
        }
        String module = path.substring(0, sep).strip();
        String importedName = path.substring(sep + 2).strip();
        if (module.isBlank() || importedName.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new PathParts(module, importedName));
    }

    public record UseSpec(String path, @Nullable String alias, @Nullable String localName, boolean wildcard) {}

    private record PathParts(String moduleSpecifier, String importedName) {}
}
