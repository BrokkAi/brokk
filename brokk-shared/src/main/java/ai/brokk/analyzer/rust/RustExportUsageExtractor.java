package ai.brokk.analyzer.rust;

import static ai.brokk.analyzer.rust.Constants.nodeField;
import static ai.brokk.analyzer.rust.Constants.nodeType;
import static java.util.Objects.requireNonNull;
import static org.treesitter.RustNodeType.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.RustAnalyzer;
import ai.brokk.analyzer.SourceContent;
import ai.brokk.analyzer.usages.ExportIndex;
import ai.brokk.analyzer.usages.ImportBinder;
import ai.brokk.analyzer.usages.LocalUsageEvent;
import ai.brokk.analyzer.usages.LocalUsageInference;
import ai.brokk.analyzer.usages.ReceiverTargetRef;
import ai.brokk.analyzer.usages.ReferenceCandidate;
import ai.brokk.analyzer.usages.ReferenceKind;
import ai.brokk.analyzer.usages.ResolvedReceiverCandidate;
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
        var reexportStars = new ArrayList<ExportIndex.ReexportStar>();
        var heritageEdges = new LinkedHashSet<ExportIndex.HeritageEdge>();
        var classMembers = new LinkedHashSet<ExportIndex.ClassMember>();
        collectExports(root, source, exports, reexportStars, heritageEdges, classMembers);
        return new ExportIndex(
                Map.copyOf(exports), List.copyOf(reexportStars), Set.copyOf(heritageEdges), Set.copyOf(classMembers));
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
                .forEach(spec -> {
                    if (spec.wildcard()) {
                        expandWildcardImport(analyzer, file, spec, localTopLevelNames, bindings);
                        return;
                    }
                    bindUseSpec(analyzer, file, spec, localTopLevelNames, bindings);
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

    public static Set<ResolvedReceiverCandidate> computeResolvedReceiverCandidates(
            RustAnalyzer analyzer, ProjectFile file, TSNode root, SourceContent source, ImportBinder binder) {
        CodeUnit fallbackEnclosing = analyzer.enclosingCodeUnit(file, rangeOf(root))
                .orElse(CodeUnit.module(file, analyzer.packageNameOf(file), "_module_"));
        var events = new ArrayList<LocalUsageEvent>();
        Map<String, List<String>> typeAliases = collectTypeAliases(root, source);
        collectLocalUsageEvents(root, analyzer, file, source, binder, typeAliases, fallbackEnclosing, events);
        return LocalUsageInference.infer(events);
    }

    private static void collectExports(
            TSNode node,
            SourceContent source,
            Map<String, ExportIndex.ExportEntry> exports,
            List<ExportIndex.ReexportStar> reexportStars,
            Set<ExportIndex.HeritageEdge> heritageEdges,
            Set<ExportIndex.ClassMember> classMembers) {
        if (node == null) {
            return;
        }
        String type = node.getType();
        if (nodeType(USE_DECLARATION).equals(type)) {
            collectReexports(node, source, exports, reexportStars);
            return;
        }
        if (isExportableItem(node) && isItemLevelDeclaration(node) && isPublic(node)) {
            localNameOf(node, source).ifPresent(name -> exports.put(name, new ExportIndex.LocalExport(name)));
        }
        if (nodeType(IMPL_ITEM).equals(type)) {
            collectImplMembers(node, source, heritageEdges, classMembers);
        }
        if (nodeType(TRAIT_ITEM).equals(type)) {
            collectTraitMembers(node, source, classMembers);
        }
        if (nodeType(ENUM_ITEM).equals(type) && isPublic(node)) {
            collectEnumVariantMembers(node, source, classMembers);
        }
        for (TSNode child : node.getNamedChildren()) {
            collectExports(child, source, exports, reexportStars, heritageEdges, classMembers);
        }
    }

    private static void collectReexports(
            TSNode useDeclaration,
            SourceContent source,
            Map<String, ExportIndex.ExportEntry> exports,
            List<ExportIndex.ReexportStar> reexportStars) {
        if (!isPublic(useDeclaration)) {
            return;
        }
        useSpecsOf(useDeclaration, source).forEach(spec -> {
            if (spec.wildcard()) {
                String moduleSpecifier = stripWildcardSegment(spec.path());
                if (!moduleSpecifier.isBlank()) {
                    reexportStars.add(new ExportIndex.ReexportStar(moduleSpecifier));
                }
                return;
            }
            splitModuleAndName(spec.path()).ifPresent(parts -> {
                String exportName = spec.alias() != null ? spec.alias() : parts.importedName();
                exports.put(exportName, new ExportIndex.ReexportedNamed(parts.moduleSpecifier(), parts.importedName()));
            });
        });
    }

    private static boolean isExportableItem(TSNode node) {
        String type = node.getType();
        return nodeType(STRUCT_ITEM).equals(type)
                || nodeType(ENUM_ITEM).equals(type)
                || nodeType(TRAIT_ITEM).equals(type)
                || nodeType(MOD_ITEM).equals(type)
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
        if (nodeType(DECLARATION_LIST).equals(parentType)) {
            TSNode owner = parent.getParent();
            return owner != null && nodeType(MOD_ITEM).equals(owner.getType());
        }
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

    private static void collectImplMembers(
            TSNode implItem,
            SourceContent source,
            Set<ExportIndex.HeritageEdge> heritageEdges,
            Set<ExportIndex.ClassMember> classMembers) {
        Optional<String> owner = typeNameOf(implItem.getChildByFieldName(nodeField(RustNodeField.TYPE)), source);
        if (owner.isEmpty()) {
            return;
        }
        Optional<String> trait = typeNameOf(implItem.getChildByFieldName(nodeField(RustNodeField.TRAIT)), source);
        trait.ifPresent(traitName -> heritageEdges.add(new ExportIndex.HeritageEdge(owner.orElseThrow(), traitName)));

        TSNode body = implItem.getChildByFieldName(nodeField(RustNodeField.BODY));
        for (TSNode child : (body == null ? implItem : body).getNamedChildren()) {
            memberOf(child, source).ifPresent(member -> {
                if (trait.isPresent() || isPublic(child)) {
                    classMembers.add(new ExportIndex.ClassMember(
                            owner.orElseThrow(), member.name(), member.staticMember(), member.kind()));
                }
            });
        }
    }

    private static void collectEnumVariantMembers(
            TSNode enumItem, SourceContent source, Set<ExportIndex.ClassMember> classMembers) {
        Optional<String> owner = localNameOf(enumItem, source);
        if (owner.isEmpty()) {
            return;
        }
        for (TSNode child : enumItem.getNamedChildren()) {
            collectEnumVariantMembers(child, source, owner.orElseThrow(), classMembers);
        }
    }

    private static void collectEnumVariantMembers(
            TSNode node, SourceContent source, String ownerName, Set<ExportIndex.ClassMember> classMembers) {
        if (nodeType(ENUM_VARIANT).equals(node.getType())) {
            localNameOf(node, source)
                    .ifPresent(name ->
                            classMembers.add(new ExportIndex.ClassMember(ownerName, name, true, CodeUnitType.FIELD)));
            return;
        }
        for (TSNode child : node.getNamedChildren()) {
            collectEnumVariantMembers(child, source, ownerName, classMembers);
        }
    }

    private static void collectTraitMembers(
            TSNode traitItem, SourceContent source, Set<ExportIndex.ClassMember> classMembers) {
        Optional<String> owner = localNameOf(traitItem, source);
        if (owner.isEmpty()) {
            return;
        }
        TSNode body = traitItem.getChildByFieldName(nodeField(RustNodeField.BODY));
        for (TSNode child : (body == null ? traitItem : body).getNamedChildren()) {
            memberOf(child, source).ifPresent(member -> {
                classMembers.add(new ExportIndex.ClassMember(
                        owner.orElseThrow(), member.name(), member.staticMember(), member.kind()));
                if (!member.staticMember()) {
                    classMembers.add(
                            new ExportIndex.ClassMember(owner.orElseThrow(), member.name(), true, member.kind()));
                }
            });
        }
    }

    private static Optional<Member> memberOf(TSNode node, SourceContent source) {
        String type = node.getType();
        if (!nodeType(FUNCTION_ITEM).equals(type)
                && !nodeType(FUNCTION_SIGNATURE_ITEM).equals(type)
                && !nodeType(CONST_ITEM).equals(type)
                && !nodeType(TYPE_ITEM).equals(type)) {
            return Optional.empty();
        }
        return localNameOf(node, source).map(name -> new Member(name, !hasSelfParameter(node), memberKindOf(node)));
    }

    private static CodeUnitType memberKindOf(TSNode node) {
        return nodeType(FUNCTION_ITEM).equals(node.getType())
                        || nodeType(FUNCTION_SIGNATURE_ITEM).equals(node.getType())
                ? CodeUnitType.FUNCTION
                : CodeUnitType.FIELD;
    }

    private static boolean hasSelfParameter(TSNode node) {
        TSNode parameters = node.getChildByFieldName(nodeField(RustNodeField.PARAMETERS));
        return containsNodeType(parameters, nodeType(SELF_PARAMETER));
    }

    private static boolean containsNodeType(@Nullable TSNode node, String nodeType) {
        if (node == null) {
            return false;
        }
        if (nodeType.equals(node.getType())) {
            return true;
        }
        for (TSNode child : node.getNamedChildren()) {
            if (containsNodeType(child, nodeType)) {
                return true;
            }
        }
        return false;
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

    private static void collectLocalUsageEvents(
            TSNode node,
            RustAnalyzer analyzer,
            ProjectFile file,
            SourceContent source,
            ImportBinder binder,
            Map<String, List<String>> typeAliases,
            CodeUnit fallbackEnclosing,
            List<LocalUsageEvent> events) {
        if (node == null || nodeType(USE_DECLARATION).equals(node.getType())) {
            return;
        }

        boolean scope = isLocalScope(node);
        if (scope) {
            events.add(new LocalUsageEvent.EnterScope());
            collectParameterEvents(node, analyzer, file, source, binder, typeAliases, events);
        }

        if (nodeType(LET_DECLARATION).equals(node.getType())) {
            collectLetDeclarationEvent(node, analyzer, file, source, binder, typeAliases, events);
        } else if (nodeType(FIELD_EXPRESSION).equals(node.getType())) {
            collectReceiverAccessEvent(node, analyzer, file, source, fallbackEnclosing, events);
        }

        for (TSNode child : node.getNamedChildren()) {
            collectLocalUsageEvents(child, analyzer, file, source, binder, typeAliases, fallbackEnclosing, events);
        }

        if (scope) {
            events.add(new LocalUsageEvent.ExitScope());
        }
    }

    private static boolean isLocalScope(TSNode node) {
        String type = node.getType();
        return nodeType(FUNCTION_ITEM).equals(type) || nodeType(BLOCK).equals(type);
    }

    private static void collectParameterEvents(
            TSNode scope,
            RustAnalyzer analyzer,
            ProjectFile file,
            SourceContent source,
            ImportBinder binder,
            Map<String, List<String>> typeAliases,
            List<LocalUsageEvent> events) {
        if (!nodeType(FUNCTION_ITEM).equals(scope.getType())) {
            return;
        }
        TSNode parameters = scope.getChildByFieldName(nodeField(RustNodeField.PARAMETERS));
        if (parameters == null) {
            return;
        }
        for (TSNode parameter : parameters.getNamedChildren()) {
            Optional<String> name = firstIdentifierName(
                            parameter.getChildByFieldName(nodeField(RustNodeField.PATTERN)), source)
                    .or(() ->
                            firstIdentifierName(parameter.getChildByFieldName(nodeField(RustNodeField.NAME)), source));
            Optional<ReceiverTargetRef> target = receiverTargetForType(
                    analyzer,
                    file,
                    parameter.getChildByFieldName(nodeField(RustNodeField.TYPE)),
                    source,
                    binder,
                    typeAliases);
            if (name.isPresent() && target.isPresent()) {
                events.add(new LocalUsageEvent.SeedSymbol(name.orElseThrow(), Set.of(target.orElseThrow())));
            }
        }
    }

    private static void collectLetDeclarationEvent(
            TSNode letDeclaration,
            RustAnalyzer analyzer,
            ProjectFile file,
            SourceContent source,
            ImportBinder binder,
            Map<String, List<String>> typeAliases,
            List<LocalUsageEvent> events) {
        Optional<String> localName =
                firstIdentifierName(letDeclaration.getChildByFieldName(nodeField(RustNodeField.PATTERN)), source);
        if (localName.isEmpty()) {
            return;
        }
        String name = localName.orElseThrow();
        TSNode type = letDeclaration.getChildByFieldName(nodeField(RustNodeField.TYPE));
        Optional<ReceiverTargetRef> typedTarget =
                receiverTargetForType(analyzer, file, type, source, binder, typeAliases);
        if (typedTarget.isPresent()) {
            events.add(new LocalUsageEvent.SeedSymbol(name, Set.of(typedTarget.orElseThrow())));
            return;
        }

        TSNode value = letDeclaration.getChildByFieldName(nodeField(RustNodeField.VALUE));
        Optional<ReceiverTargetRef> constructedTarget =
                receiverTargetForConstructor(analyzer, file, value, source, binder, typeAliases);
        if (constructedTarget.isPresent()) {
            events.add(new LocalUsageEvent.SeedSymbol(name, Set.of(constructedTarget.orElseThrow())));
            return;
        }

        Optional<String> alias = aliasSourceName(value, source);
        if (alias.isPresent()) {
            events.add(new LocalUsageEvent.AliasSymbol(name, alias.orElseThrow()));
        } else {
            events.add(new LocalUsageEvent.DeclareSymbol(name));
        }
    }

    private static void collectReceiverAccessEvent(
            TSNode fieldExpression,
            RustAnalyzer analyzer,
            ProjectFile file,
            SourceContent source,
            CodeUnit fallbackEnclosing,
            List<LocalUsageEvent> events) {
        Optional<String> receiver =
                firstIdentifierName(fieldExpression.getChildByFieldName(nodeField(RustNodeField.VALUE)), source);
        Optional<String> field = fieldNameOf(fieldExpression, source);
        if (receiver.isEmpty() || field.isEmpty()) {
            return;
        }
        ReferenceKind kind = nodeType(CALL_EXPRESSION).equals(parentType(fieldExpression))
                ? ReferenceKind.METHOD_CALL
                : ReferenceKind.FIELD_READ;
        events.add(new LocalUsageEvent.ReceiverAccess(
                receiver.orElseThrow(),
                field.orElseThrow(),
                kind,
                rangeOf(fieldExpression),
                enclosing(analyzer, file, fieldExpression, fallbackEnclosing)));
    }

    private static Optional<ReceiverTargetRef> receiverTargetForConstructor(
            RustAnalyzer analyzer,
            ProjectFile file,
            @Nullable TSNode value,
            SourceContent source,
            ImportBinder binder,
            Map<String, List<String>> typeAliases) {
        if (value == null) {
            return Optional.empty();
        }
        if (nodeType(STRUCT_EXPRESSION).equals(value.getType())) {
            return receiverTargetForType(
                    analyzer,
                    file,
                    value.getChildByFieldName(nodeField(RustNodeField.NAME)),
                    source,
                    binder,
                    typeAliases);
        }
        if (nodeType(CALL_EXPRESSION).equals(value.getType())) {
            TSNode function = value.getChildByFieldName(nodeField(RustNodeField.FUNCTION));
            if (function == null) {
                return Optional.empty();
            }
            if (nodeType(SCOPED_IDENTIFIER).equals(function.getType())
                    || nodeType(SCOPED_TYPE_IDENTIFIER).equals(function.getType())) {
                List<String> segments = pathSegmentsPreservingKeywords(function, source);
                if (segments.size() >= 2 && "new".equals(segments.getLast())) {
                    return receiverTargetForSegments(analyzer, file, segments.subList(0, segments.size() - 1), binder);
                }
            }
            return receiverTargetForType(analyzer, file, function, source, binder, typeAliases);
        }
        return Optional.empty();
    }

    private static Optional<ReceiverTargetRef> receiverTargetForType(
            RustAnalyzer analyzer,
            ProjectFile file,
            @Nullable TSNode type,
            SourceContent source,
            ImportBinder binder,
            Map<String, List<String>> typeAliases) {
        if (isNonConcreteReceiverType(type, source)) {
            return Optional.empty();
        }
        List<String> segments = pathSegmentsPreservingKeywords(type, source);
        if (segments.size() == 1) {
            List<String> aliased = typeAliases.getOrDefault(segments.getFirst(), List.of());
            if (!aliased.isEmpty()) {
                segments = aliased;
            }
        }
        return receiverTargetForSegments(analyzer, file, segments, binder);
    }

    private static boolean isNonConcreteReceiverType(@Nullable TSNode type, SourceContent source) {
        if (type == null) {
            return false;
        }
        String textType = type.getType();
        String text = source.substringFrom(type).strip();
        if (text.startsWith("impl ") || text.contains("dyn ")) {
            return true;
        }
        if ("impl_trait_type".equals(textType)
                || nodeType(DYNAMIC_TYPE).equals(textType)
                || nodeType(BOUNDED_TYPE).equals(textType)) {
            return true;
        }
        for (TSNode child : type.getNamedChildren()) {
            if (isNonConcreteReceiverType(child, source)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<ReceiverTargetRef> receiverTargetForSegments(
            RustAnalyzer analyzer, ProjectFile file, List<String> rawSegments, ImportBinder binder) {
        var segments =
                rawSegments.stream().filter(segment -> !"self".equals(segment)).toList();
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        String exportedName = segments.getLast();
        String first = segments.getFirst();
        ImportBinder.ImportBinding binding = binder.bindings().get(first);
        if (binding != null && binding.importedName() != null) {
            return Optional.of(new ReceiverTargetRef(
                    binding.moduleSpecifier(),
                    binding.importedName(),
                    true,
                    binding.kind() == ImportBinder.ImportKind.NAMED ? 1.0 : 0.9,
                    null));
        }
        if (segments.size() >= 2) {
            String moduleSpecifier = String.join("::", segments.subList(0, segments.size() - 1));
            return Optional.of(new ReceiverTargetRef(moduleSpecifier, exportedName, true, 0.95, null));
        }
        if (analyzer.exportIndexOf(file).exportsByName().containsKey(exportedName)) {
            return Optional.of(new ReceiverTargetRef(null, exportedName, true, 1.0, file));
        }
        return Optional.empty();
    }

    private static Optional<String> aliasSourceName(@Nullable TSNode value, SourceContent source) {
        if (value == null) {
            return Optional.empty();
        }
        String type = value.getType();
        if (nodeType(IDENTIFIER).equals(type) || nodeType(TYPE_IDENTIFIER).equals(type)) {
            return Optional.of(source.substringFrom(value).strip()).filter(s -> !s.isBlank());
        }
        return Optional.empty();
    }

    private static Optional<String> firstIdentifierName(@Nullable TSNode node, SourceContent source) {
        if (node == null) {
            return Optional.empty();
        }
        String type = node.getType();
        if (nodeType(IDENTIFIER).equals(type) || nodeType(TYPE_IDENTIFIER).equals(type)) {
            return Optional.of(source.substringFrom(node).strip()).filter(s -> !s.isBlank());
        }
        for (TSNode child : node.getNamedChildren()) {
            Optional<String> found = firstIdentifierName(child, source);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> fieldNameOf(TSNode fieldExpression, SourceContent source) {
        for (TSNode child : fieldExpression.getNamedChildren()) {
            if (nodeType(FIELD_IDENTIFIER).equals(child.getType())) {
                return Optional.of(source.substringFrom(child).strip()).filter(s -> !s.isBlank());
            }
        }
        return Optional.empty();
    }

    private static String parentType(TSNode node) {
        TSNode parent = node.getParent();
        return parent == null ? "" : requireNonNull(parent.getType());
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

    private static Optional<String> typeNameOf(@Nullable TSNode node, SourceContent source) {
        List<String> segments = pathSegmentsPreservingKeywords(node, source).stream()
                .filter(segment -> !isRustPathKeyword(segment))
                .toList();
        return segments.isEmpty() ? Optional.empty() : Optional.of(segments.getLast());
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

    private static Map<String, List<String>> collectTypeAliases(TSNode root, SourceContent source) {
        var aliases = new LinkedHashMap<String, List<String>>();
        collectTypeAliases(root, source, aliases);
        return Map.copyOf(aliases);
    }

    private static void collectTypeAliases(TSNode node, SourceContent source, Map<String, List<String>> aliases) {
        if (nodeType(TYPE_ITEM).equals(node.getType())) {
            Optional<String> name = localNameOf(node, source);
            List<String> target = typeAliasTargetSegments(node, source);
            if (name.isPresent() && !target.isEmpty()) {
                aliases.put(name.orElseThrow(), target);
            }
            return;
        }
        for (TSNode child : node.getNamedChildren()) {
            collectTypeAliases(child, source, aliases);
        }
    }

    private static List<String> typeAliasTargetSegments(TSNode typeItem, SourceContent source) {
        TSNode name = typeItem.getChildByFieldName(nodeField(RustNodeField.NAME));
        TSNode target = typeItem.getChildByFieldName(nodeField(RustNodeField.TYPE));
        if (target != null && (name == null || target.getStartByte() != name.getStartByte())) {
            return pathSegmentsPreservingKeywords(target, source);
        }
        for (TSNode child : typeItem.getNamedChildren()) {
            if (name != null && child.getStartByte() <= name.getStartByte()) {
                continue;
            }
            if (!nodeType(VISIBILITY_MODIFIER).equals(child.getType())) {
                List<String> segments = pathSegmentsPreservingKeywords(child, source);
                if (!segments.isEmpty()) {
                    return segments;
                }
            }
        }
        return List.of();
    }

    private static void bindUseSpec(
            RustAnalyzer analyzer,
            ProjectFile file,
            UseSpec spec,
            Set<String> localTopLevelNames,
            Map<String, ImportBinder.ImportBinding> bindings) {
        String localName = spec.localName();
        if (localName == null || localTopLevelNames.contains(localName)) {
            return;
        }
        if (analyzer.resolveRustModuleOutcome(file, spec.path()).resolved().isPresent()) {
            bindings.put(
                    localName, new ImportBinder.ImportBinding(spec.path(), ImportBinder.ImportKind.NAMESPACE, null));
            return;
        }
        splitModuleAndName(spec.path())
                .ifPresent(parts -> bindings.put(
                        localName,
                        new ImportBinder.ImportBinding(
                                parts.moduleSpecifier(), ImportBinder.ImportKind.NAMED, parts.importedName())));
    }

    private static void expandWildcardImport(
            RustAnalyzer analyzer,
            ProjectFile file,
            UseSpec spec,
            Set<String> localTopLevelNames,
            Map<String, ImportBinder.ImportBinding> bindings) {
        String moduleSpecifier = stripWildcardSegment(spec.path());
        if (moduleSpecifier.isBlank()) {
            return;
        }
        analyzer.resolveRustModuleOutcome(file, moduleSpecifier).resolved().ifPresent(moduleFile -> {
            for (String exportName :
                    analyzer.exportIndexOf(moduleFile).exportsByName().keySet()) {
                if (!localTopLevelNames.contains(exportName)) {
                    bindings.putIfAbsent(
                            exportName,
                            new ImportBinder.ImportBinding(moduleSpecifier, ImportBinder.ImportKind.NAMED, exportName));
                }
            }
        });
    }

    private static String stripWildcardSegment(String rustPath) {
        return rustPath.endsWith("::*") ? rustPath.substring(0, rustPath.length() - 3) : rustPath;
    }

    public record UseSpec(String path, @Nullable String alias, @Nullable String localName, boolean wildcard) {}

    private record Member(String name, boolean staticMember, CodeUnitType kind) {}

    private record PathParts(String moduleSpecifier, String importedName) {}
}
