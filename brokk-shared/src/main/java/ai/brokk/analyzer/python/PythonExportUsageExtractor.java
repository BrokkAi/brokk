package ai.brokk.analyzer.python;

import static ai.brokk.analyzer.python.Constants.nodeField;
import static ai.brokk.analyzer.python.Constants.nodeType;
import static org.treesitter.PythonNodeType.ASSIGNMENT;
import static org.treesitter.PythonNodeType.ATTRIBUTE;
import static org.treesitter.PythonNodeType.CALL;
import static org.treesitter.PythonNodeType.CLASS_DEFINITION;
import static org.treesitter.PythonNodeType.FUNCTION_DEFINITION;
import static org.treesitter.PythonNodeType.GENERIC_TYPE;
import static org.treesitter.PythonNodeType.IDENTIFIER;
import static org.treesitter.PythonNodeType.IMPORT_FROM_STATEMENT;
import static org.treesitter.PythonNodeType.IMPORT_STATEMENT;
import static org.treesitter.PythonNodeType.STRING_CONTENT;
import static org.treesitter.PythonNodeType.SUBSCRIPT;
import static org.treesitter.PythonNodeType.TYPED_PARAMETER;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.analyzer.SourceContent;
import ai.brokk.analyzer.usages.ExportIndex;
import ai.brokk.analyzer.usages.ImportBinder;
import ai.brokk.analyzer.usages.LocalUsageEvent;
import ai.brokk.analyzer.usages.LocalUsageInference;
import ai.brokk.analyzer.usages.ReceiverTargetRef;
import ai.brokk.analyzer.usages.ReferenceCandidate;
import ai.brokk.analyzer.usages.ReferenceKind;
import ai.brokk.analyzer.usages.ResolvedReceiverCandidate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.treesitter.PythonNodeField;
import org.treesitter.TSNode;

public final class PythonExportUsageExtractor {
    private static final String SELF_NAME = "self";
    private static final String SELF_ATTRIBUTE_PREFIX = SELF_NAME + ".";
    private static final int MAX_WALK_NODES = 100_000;
    private static final int MAX_ATTRIBUTE_CHAIN_DEPTH = 512;

    private PythonExportUsageExtractor() {}

    public static ExportIndex computeExportIndex(
            PythonAnalyzer analyzer,
            ProjectFile file,
            TSNode root,
            SourceContent source,
            ImportBinder binder,
            List<ExportIndex.ReexportStar> reexportStars) {
        var exports = new LinkedHashMap<String, ExportIndex.ExportEntry>();
        for (CodeUnit cu : analyzer.getTopLevelDeclarations(file)) {
            if (cu.isClass() || cu.isFunction()) {
                exports.put(cu.identifier(), new ExportIndex.LocalExport(cu.identifier()));
            }
        }
        staticAllNames(root, source).forEach(name -> exports.putIfAbsent(name, new ExportIndex.LocalExport(name)));
        binder.bindings().keySet().stream()
                .filter(name -> !exports.containsKey(name))
                .forEach(name -> exports.put(name, new ExportIndex.LocalExport(name)));
        return new ExportIndex(Map.copyOf(exports), reexportStars, Set.of(), classMembers(analyzer, file));
    }

    public static Set<ReferenceCandidate> computeUsageCandidates(
            ProjectFile file, TSNode root, SourceContent source, ImportBinder binder, Set<String> localExportNames) {
        var candidates = new LinkedHashSet<ReferenceCandidate>();
        CodeUnit enclosing = CodeUnit.module(file, "", "_module_");
        var scopes = new ArrayDeque<Set<String>>();
        scopes.addLast(new LinkedHashSet<>());
        collectUsageCandidates(root, source, binder, localExportNames, enclosing, scopes, candidates);
        return Set.copyOf(candidates);
    }

    public static Set<ResolvedReceiverCandidate> computeResolvedReceiverCandidates(
            PythonAnalyzer analyzer, ProjectFile file, TSNode root, SourceContent source, ImportBinder binder) {
        CodeUnit enclosing = CodeUnit.module(file, "", "_module_");
        var events = new ArrayList<LocalUsageEvent>();
        var shadows = new ArrayDeque<Set<String>>();
        shadows.addLast(new LinkedHashSet<>());
        collectLocalUsageEvents(analyzer, file, root, source, binder, enclosing, shadows, events);
        return LocalUsageInference.infer(events);
    }

    private static void collectLocalUsageEvents(
            PythonAnalyzer analyzer,
            ProjectFile file,
            TSNode node,
            SourceContent source,
            ImportBinder binder,
            CodeUnit enclosing,
            Deque<Set<String>> locallyShadowedNames,
            List<LocalUsageEvent> events) {
        String type = node.getType();

        if (nodeType(TYPED_PARAMETER).equals(type)) {
            handleTypedParameter(analyzer, file, node, source, binder, locallyShadowedNames, events);
        } else if (nodeType(ASSIGNMENT).equals(type)) {
            handleAssignment(analyzer, file, node, source, binder, locallyShadowedNames, events);
        } else if (nodeType(CLASS_DEFINITION).equals(type)
                || nodeType(FUNCTION_DEFINITION).equals(type)) {
            handleLocalDeclaration(node, source, locallyShadowedNames, events);
            events.add(new LocalUsageEvent.EnterScope());
            locallyShadowedNames.addLast(new LinkedHashSet<>());
            declareParameters(node, source, events);
            if (nodeType(CLASS_DEFINITION).equals(type)) {
                seedInstanceAttributeAnnotationsInClass(
                        analyzer, file, node, source, binder, locallyShadowedNames, events);
            }
            traverseChildren(analyzer, file, node, source, binder, enclosing, locallyShadowedNames, events);
            locallyShadowedNames.removeLast();
            events.add(new LocalUsageEvent.ExitScope());
            return;
        } else if (nodeType(ATTRIBUTE).equals(type)) {
            handleReceiverAccess(node, source, enclosing, events);
        }

        traverseChildren(analyzer, file, node, source, binder, enclosing, locallyShadowedNames, events);
    }

    private static void traverseChildren(
            PythonAnalyzer analyzer,
            ProjectFile file,
            TSNode node,
            SourceContent source,
            ImportBinder binder,
            CodeUnit enclosing,
            Deque<Set<String>> locallyShadowedNames,
            List<LocalUsageEvent> events) {
        for (TSNode child : node.getNamedChildren()) {
            if (child != null) {
                collectLocalUsageEvents(analyzer, file, child, source, binder, enclosing, locallyShadowedNames, events);
            }
        }
    }

    private static void handleTypedParameter(
            PythonAnalyzer analyzer,
            ProjectFile file,
            TSNode node,
            SourceContent source,
            ImportBinder binder,
            Deque<Set<String>> locallyShadowedNames,
            List<LocalUsageEvent> events) {
        TSNode nameNode = node.getChildByFieldName(nodeField(PythonNodeField.NAME));
        if (nameNode == null) {
            nameNode = firstNamedIdentifier(node);
        }
        TSNode typeNode = node.getChildByFieldName(nodeField(PythonNodeField.TYPE));
        if (typeNode == null) {
            typeNode = namedChildAfter(node, nameNode);
        }
        if (nameNode == null || typeNode == null || !isIdentifier(nameNode)) {
            return;
        }
        String name = text(nameNode, source);
        events.add(new LocalUsageEvent.DeclareSymbol(name));
        Set<ReceiverTargetRef> targets =
                receiverTargetsFromType(analyzer, file, typeNode, source, binder, locallyShadowedNames);
        if (!targets.isEmpty()) {
            events.add(new LocalUsageEvent.SeedSymbol(name, targets));
        }
    }

    private static void handleAssignment(
            PythonAnalyzer analyzer,
            ProjectFile file,
            TSNode node,
            SourceContent source,
            ImportBinder binder,
            Deque<Set<String>> locallyShadowedNames,
            List<LocalUsageEvent> events) {
        TSNode left = node.getChildByFieldName(nodeField(PythonNodeField.LEFT));
        if (left == null) {
            left = node.getChildByFieldName(nodeField(PythonNodeField.NAME));
        }
        String localName = localSymbolName(left, source);
        if (localName.isBlank()) {
            return;
        }

        events.add(new LocalUsageEvent.DeclareSymbol(localName));

        TSNode typeNode = node.getChildByFieldName(nodeField(PythonNodeField.TYPE));
        if (typeNode != null) {
            Set<ReceiverTargetRef> targets =
                    receiverTargetsFromType(analyzer, file, typeNode, source, binder, locallyShadowedNames);
            if (!targets.isEmpty() && !localName.equals(text(typeNode, source).strip())) {
                events.add(new LocalUsageEvent.SeedSymbol(localName, targets));
                currentScope(locallyShadowedNames).add(localName);
                return;
            }
        }

        TSNode value = node.getChildByFieldName(nodeField(PythonNodeField.RIGHT));
        if (value == null) {
            value = node.getChildByFieldName(nodeField(PythonNodeField.VALUE));
        }
        if (value == null) {
            return;
        }

        if (isIdentifier(value)) {
            events.add(new LocalUsageEvent.AliasSymbol(localName, text(value, source)));
            currentScope(locallyShadowedNames).add(localName);
            return;
        }

        if (nodeType(CALL).equals(value.getType())) {
            TSNode function = value.getChildByFieldName(nodeField(PythonNodeField.FUNCTION));
            if (function == null && value.getNamedChildCount() > 0) {
                function = value.getNamedChild(0);
            }
            if (function != null) {
                String functionName = localSymbolName(function, source);
                if (!localName.equals(functionName)) {
                    receiverTargetFromTypeName(analyzer, file, function, source, binder, locallyShadowedNames, true)
                            .ifPresent(target -> events.add(new LocalUsageEvent.SeedSymbol(localName, Set.of(target))));
                }
            }
        }
        currentScope(locallyShadowedNames).add(localName);
    }

    private static void handleReceiverAccess(
            TSNode node, SourceContent source, CodeUnit enclosing, List<LocalUsageEvent> events) {
        TSNode object = node.getChildByFieldName(nodeField(PythonNodeField.OBJECT));
        TSNode attribute = node.getChildByFieldName(nodeField(PythonNodeField.ATTRIBUTE));
        if (object == null || attribute == null || !isIdentifier(attribute)) {
            return;
        }
        String receiverName = localSymbolName(object, source);
        if (receiverName.isBlank()) {
            return;
        }
        events.add(new LocalUsageEvent.ReceiverAccess(
                receiverName,
                text(attribute, source),
                isMethodCallAttribute(node) ? ReferenceKind.METHOD_CALL : ReferenceKind.FIELD_READ,
                rangeOf(attribute),
                enclosing));
    }

    private static void handleLocalDeclaration(
            TSNode node, SourceContent source, Deque<Set<String>> locallyShadowedNames, List<LocalUsageEvent> events) {
        TSNode name = node.getChildByFieldName(nodeField(PythonNodeField.NAME));
        if (name == null || !isIdentifier(name)) {
            return;
        }
        String localName = text(name, source);
        events.add(new LocalUsageEvent.DeclareSymbol(localName));
        currentScope(locallyShadowedNames).add(localName);
    }

    private static Set<ReceiverTargetRef> receiverTargetsFromType(
            PythonAnalyzer analyzer,
            ProjectFile file,
            TSNode typeNode,
            SourceContent source,
            ImportBinder binder,
            Deque<Set<String>> locallyShadowedNames) {
        var targets = new LinkedHashSet<ReceiverTargetRef>();
        collectTypeNames(typeNode, source).stream()
                .map(typeName ->
                        receiverTargetFromTypeName(analyzer, file, typeName, binder, locallyShadowedNames, true))
                .flatMap(Optional::stream)
                .forEach(targets::add);
        return Set.copyOf(targets);
    }

    private static Set<String> collectTypeNames(TSNode typeNode, SourceContent source) {
        var names = new LinkedHashSet<String>();
        collectTypeNames(typeNode, source, names);
        return Set.copyOf(names);
    }

    private static void collectTypeNames(TSNode node, SourceContent source, Set<String> names) {
        if (isIdentifier(node)) {
            names.add(text(node, source));
            return;
        }
        if (nodeType(ATTRIBUTE).equals(node.getType())) {
            List<String> chain = attributeChain(node, source);
            if (!chain.isEmpty()) {
                names.add(String.join(".", chain));
            }
            return;
        }
        if (nodeType(SUBSCRIPT).equals(node.getType()) || nodeType(GENERIC_TYPE).equals(node.getType())) {
            collectReceiverCompatibleSubscriptTypeNames(node, source, names);
            return;
        }
        for (TSNode child : node.getNamedChildren()) {
            if (child != null) {
                collectTypeNames(child, source, names);
            }
        }
    }

    private static void collectReceiverCompatibleSubscriptTypeNames(
            TSNode node, SourceContent source, Set<String> names) {
        List<TSNode> children = node.getNamedChildren();
        if (children.isEmpty()) {
            return;
        }
        String wrapper = typeExpressionName(children.getFirst(), source);
        if (isContainerTypeWrapper(wrapper)) {
            return;
        }
        if (!isTransparentTypeWrapper(wrapper)) {
            names.add(wrapper);
            return;
        }
        children.stream().skip(1).forEach(child -> collectTypeNames(child, source, names));
    }

    private static String typeExpressionName(TSNode node, SourceContent source) {
        if (isIdentifier(node)) {
            return text(node, source);
        }
        if (nodeType(ATTRIBUTE).equals(node.getType())) {
            return String.join(".", attributeChain(node, source));
        }
        return "";
    }

    private static boolean isTransparentTypeWrapper(String typeName) {
        String shortName = shortTypeName(typeName);
        return shortName.equals("Optional") || shortName.equals("Union");
    }

    private static boolean isContainerTypeWrapper(String typeName) {
        return Set.of("list", "List", "dict", "Dict", "set", "Set", "tuple", "Tuple", "Iterable", "Sequence")
                .contains(shortTypeName(typeName));
    }

    private static String shortTypeName(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        return lastDot >= 0 ? typeName.substring(lastDot + 1) : typeName;
    }

    private static Optional<ReceiverTargetRef> receiverTargetFromTypeName(
            PythonAnalyzer analyzer,
            ProjectFile file,
            TSNode typeNode,
            SourceContent source,
            ImportBinder binder,
            Deque<Set<String>> locallyShadowedNames,
            boolean instanceReceiver) {
        return receiverTargetFromTypeName(
                analyzer, file, text(typeNode, source), binder, locallyShadowedNames, instanceReceiver);
    }

    private static Optional<ReceiverTargetRef> receiverTargetFromTypeName(
            PythonAnalyzer analyzer,
            ProjectFile file,
            String typeName,
            ImportBinder binder,
            Deque<Set<String>> locallyShadowedNames,
            boolean instanceReceiver) {
        String stripped = typeName.strip();
        if (stripped.isBlank()) {
            return Optional.empty();
        }

        int dot = stripped.indexOf('.');
        if (dot > 0) {
            String qualifier = longestNamespaceQualifier(stripped, binder).orElse(stripped.substring(0, dot));
            String exportedName = stripped.substring(qualifier.length() + 1);
            ImportBinder.ImportBinding binding = binder.bindings().get(qualifier);
            if (binding == null || binding.kind() != ImportBinder.ImportKind.NAMESPACE || exportedName.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ReceiverTargetRef(
                    binding.moduleSpecifier(), exportedName, instanceReceiver, instanceReceiver ? 0.9 : 1.0, null));
        }

        if (isShadowedByNestedScope(locallyShadowedNames, stripped)) {
            return Optional.empty();
        }

        ImportBinder.ImportBinding binding = binder.bindings().get(stripped);
        Optional<ReceiverTargetRef> imported = receiverTarget(binding, instanceReceiver);
        if (imported.isPresent()) {
            return imported;
        }

        boolean localDeclaration = analyzer.getTopLevelDeclarations(file).stream()
                .anyMatch(cu -> (cu.isClass() || cu.isFunction()) && stripped.equals(cu.identifier()));
        if (localDeclaration) {
            return Optional.of(
                    new ReceiverTargetRef(null, stripped, instanceReceiver, instanceReceiver ? 0.9 : 1.0, file));
        }
        return Optional.empty();
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

    private static void collectUsageCandidates(
            TSNode node,
            SourceContent source,
            ImportBinder binder,
            Set<String> localExportNames,
            CodeUnit enclosing,
            Deque<Set<String>> scopes,
            Set<ReferenceCandidate> candidates) {
        String type = node.getType();
        if (nodeType(IMPORT_STATEMENT).equals(type)
                || nodeType(IMPORT_FROM_STATEMENT).equals(type)) {
            return;
        }

        if (nodeType(FUNCTION_DEFINITION).equals(type)
                || nodeType(CLASS_DEFINITION).equals(type)) {
            TSNode name = node.getChildByFieldName(nodeField(PythonNodeField.NAME));
            if (name != null && isIdentifier(name)) {
                currentScope(scopes).add(text(name, source));
            }
            scopes.addLast(new LinkedHashSet<>());
            declareParameterNames(node, source, scopes);
            collectUsageCandidateChildrenExcept(
                    node, name, source, binder, localExportNames, enclosing, scopes, candidates);
            scopes.removeLast();
            return;
        }

        if (nodeType(ASSIGNMENT).equals(type)) {
            TSNode typeNode = node.getChildByFieldName(nodeField(PythonNodeField.TYPE));
            if (typeNode != null) {
                collectUsageCandidates(typeNode, source, binder, localExportNames, enclosing, scopes, candidates);
            }
            TSNode value = node.getChildByFieldName(nodeField(PythonNodeField.RIGHT));
            if (value == null) {
                value = node.getChildByFieldName(nodeField(PythonNodeField.VALUE));
            }
            if (value != null) {
                collectUsageCandidates(value, source, binder, localExportNames, enclosing, scopes, candidates);
            }
            TSNode left = node.getChildByFieldName(nodeField(PythonNodeField.LEFT));
            if (left == null) {
                left = node.getChildByFieldName(nodeField(PythonNodeField.NAME));
            }
            String localName = localSymbolName(left, source);
            if (!localName.isBlank()) {
                currentScope(scopes).add(localName);
            }
            return;
        }

        if (nodeType(ATTRIBUTE).equals(type)) {
            if (isNestedAttribute(node)) {
                return;
            }
            emitAttributeCandidates(node, source, binder, localExportNames, enclosing, scopes, candidates);
            return;
        }

        if (isIdentifier(node)) {
            String identifier = text(node, source);
            if (binder.bindings().containsKey(identifier) && !isShadowed(scopes, identifier)) {
                candidates.add(new ReferenceCandidate(
                        identifier, null, null, false, ReferenceKind.STATIC_REFERENCE, rangeOf(node), enclosing));
            } else if (localExportNames.contains(identifier) && !isShadowedByNestedScope(scopes, identifier)) {
                candidates.add(new ReferenceCandidate(
                        identifier, null, null, false, ReferenceKind.STATIC_REFERENCE, rangeOf(node), enclosing));
            }
            return;
        }

        collectUsageCandidateChildren(node, source, binder, localExportNames, enclosing, scopes, candidates);
    }

    private static void collectUsageCandidateChildren(
            TSNode node,
            SourceContent source,
            ImportBinder binder,
            Set<String> localExportNames,
            CodeUnit enclosing,
            Deque<Set<String>> scopes,
            Set<ReferenceCandidate> candidates) {
        for (TSNode child : node.getNamedChildren()) {
            if (child != null) {
                collectUsageCandidates(child, source, binder, localExportNames, enclosing, scopes, candidates);
            }
        }
    }

    private static void collectUsageCandidateChildrenExcept(
            TSNode node,
            @Nullable TSNode excluded,
            SourceContent source,
            ImportBinder binder,
            Set<String> localExportNames,
            CodeUnit enclosing,
            Deque<Set<String>> scopes,
            Set<ReferenceCandidate> candidates) {
        for (TSNode child : node.getNamedChildren()) {
            if (child != null && !sameNode(child, excluded)) {
                collectUsageCandidates(child, source, binder, localExportNames, enclosing, scopes, candidates);
            }
        }
    }

    private static void emitAttributeCandidates(
            TSNode node,
            SourceContent source,
            ImportBinder binder,
            Set<String> localExportNames,
            CodeUnit enclosing,
            Deque<Set<String>> scopes,
            Set<ReferenceCandidate> candidates) {
        List<String> chain = attributeChain(node, source);
        if (chain.size() < 2) {
            return;
        }
        String first = chain.getFirst();
        if (isShadowed(scopes, first)
                && !(localExportNames.contains(first) && !isShadowedByNestedScope(scopes, first))) {
            return;
        }
        if (chain.size() == 2) {
            ImportBinder.ImportBinding binding = binder.bindings().get(first);
            candidates.add(new ReferenceCandidate(
                    chain.get(1),
                    binding != null ? first : null,
                    binding != null ? null : first,
                    false,
                    ReferenceKind.STATIC_REFERENCE,
                    rangeOf(node),
                    enclosing));
            return;
        }
        for (int prefixLength = chain.size() - 1; prefixLength >= 1; prefixLength--) {
            String qualifier = String.join(".", chain.subList(0, prefixLength));
            ImportBinder.ImportBinding binding = binder.bindings().get(qualifier);
            if (binding != null && binding.kind() == ImportBinder.ImportKind.NAMESPACE) {
                List<String> remainder = chain.subList(prefixLength, chain.size());
                candidates.add(new ReferenceCandidate(
                        remainder.getLast(),
                        qualifier,
                        remainder.size() == 2 ? remainder.getFirst() : null,
                        false,
                        ReferenceKind.STATIC_REFERENCE,
                        rangeOf(node),
                        enclosing));
                return;
            }
        }
        ImportBinder.ImportBinding firstBinding = binder.bindings().get(first);
        if (firstBinding != null && chain.size() == 3) {
            candidates.add(new ReferenceCandidate(
                    chain.getLast(),
                    first,
                    chain.get(1),
                    false,
                    ReferenceKind.STATIC_REFERENCE,
                    rangeOf(node),
                    enclosing));
        }
    }

    private static void seedInstanceAttributeAnnotationsInClass(
            PythonAnalyzer analyzer,
            ProjectFile file,
            TSNode classNode,
            SourceContent source,
            ImportBinder binder,
            Deque<Set<String>> locallyShadowedNames,
            List<LocalUsageEvent> events) {
        walk(classNode, node -> {
            if (!nodeType(ASSIGNMENT).equals(node.getType())) {
                return;
            }
            TSNode left = node.getChildByFieldName(nodeField(PythonNodeField.LEFT));
            if (left == null) {
                left = node.getChildByFieldName(nodeField(PythonNodeField.NAME));
            }
            String localName = localSymbolName(left, source);
            if (!isSelfAttributeName(localName)) {
                return;
            }
            TSNode typeNode = node.getChildByFieldName(nodeField(PythonNodeField.TYPE));
            if (typeNode == null) {
                return;
            }
            Set<ReceiverTargetRef> targets =
                    receiverTargetsFromType(analyzer, file, typeNode, source, binder, locallyShadowedNames);
            if (!targets.isEmpty()) {
                events.add(new LocalUsageEvent.SeedSymbol(localName, targets));
            }
        });
    }

    private static void declareParameters(TSNode node, SourceContent source, List<LocalUsageEvent> events) {
        TSNode parameters = node.getChildByFieldName(nodeField(PythonNodeField.PARAMETERS));
        if (parameters == null) {
            return;
        }
        collectParameterNames(parameters, source).forEach(name -> events.add(new LocalUsageEvent.DeclareSymbol(name)));
    }

    private static void declareParameterNames(TSNode node, SourceContent source, Deque<Set<String>> scopes) {
        TSNode parameters = node.getChildByFieldName(nodeField(PythonNodeField.PARAMETERS));
        if (parameters == null) {
            return;
        }
        currentScope(scopes).addAll(collectParameterNames(parameters, source));
    }

    private static Set<String> collectParameterNames(TSNode parameters, SourceContent source) {
        var names = new LinkedHashSet<String>();
        walk(parameters, node -> {
            TSNode name = node.getChildByFieldName(nodeField(PythonNodeField.NAME));
            if (name != null && isIdentifier(name)) {
                names.add(text(name, source));
                return;
            }
            TSNode parent = node.getParent();
            if (isIdentifier(node) && parent != null && sameNode(parent, parameters)) {
                names.add(text(node, source));
            }
        });
        return Set.copyOf(names);
    }

    private static List<String> attributeChain(TSNode node, SourceContent source) {
        var reversed = new ArrayList<String>();
        TSNode current = node;
        for (int depth = 0; depth < MAX_ATTRIBUTE_CHAIN_DEPTH; depth++) {
            if (isIdentifier(current)) {
                reversed.add(text(current, source));
                break;
            }
            if (!nodeType(ATTRIBUTE).equals(current.getType())) {
                return List.of();
            }
            TSNode attribute = current.getChildByFieldName(nodeField(PythonNodeField.ATTRIBUTE));
            if (attribute == null || !isIdentifier(attribute)) {
                return List.of();
            }
            reversed.add(text(attribute, source));
            TSNode object = current.getChildByFieldName(nodeField(PythonNodeField.OBJECT));
            if (object == null) {
                return List.of();
            }
            current = object;
        }
        if (reversed.isEmpty() || !isIdentifier(current)) {
            return List.of();
        }
        var chain = new ArrayList<String>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            chain.add(reversed.get(i));
        }
        return List.copyOf(chain);
    }

    private static Optional<String> longestNamespaceQualifier(String typeName, ImportBinder binder) {
        int dot = typeName.lastIndexOf('.');
        while (dot > 0) {
            String qualifier = typeName.substring(0, dot);
            ImportBinder.ImportBinding binding = binder.bindings().get(qualifier);
            if (binding != null && binding.kind() == ImportBinder.ImportKind.NAMESPACE) {
                return Optional.of(qualifier);
            }
            dot = typeName.lastIndexOf('.', dot - 1);
        }
        return Optional.empty();
    }

    private static boolean isShadowed(Deque<Set<String>> scopes, String name) {
        var descending = scopes.descendingIterator();
        while (descending.hasNext()) {
            if (descending.next().contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isShadowedByNestedScope(Deque<Set<String>> scopes, String name) {
        var descending = scopes.descendingIterator();
        int remaining = scopes.size();
        while (descending.hasNext()) {
            Set<String> scope = descending.next();
            if (remaining > 1 && scope.contains(name)) {
                return true;
            }
            remaining--;
        }
        return false;
    }

    private static Set<String> currentScope(Deque<Set<String>> scopes) {
        return scopes.getLast();
    }

    private static boolean isMethodCallAttribute(TSNode attributeNode) {
        TSNode parent = attributeNode.getParent();
        return parent != null && nodeType(CALL).equals(parent.getType());
    }

    private static boolean isNestedAttribute(TSNode attributeNode) {
        TSNode parent = attributeNode.getParent();
        return parent != null && nodeType(ATTRIBUTE).equals(parent.getType());
    }

    private static Set<String> staticAllNames(TSNode root, SourceContent source) {
        var names = new LinkedHashSet<String>();
        walk(root, node -> {
            if (!nodeType(ASSIGNMENT).equals(node.getType())) {
                return;
            }
            TSNode left = node.getChildByFieldName(nodeField(PythonNodeField.LEFT));
            if (left == null) {
                left = node.getChildByFieldName(nodeField(PythonNodeField.NAME));
            }
            if (!"__all__".equals(localSymbolName(left, source))) {
                return;
            }
            TSNode value = node.getChildByFieldName(nodeField(PythonNodeField.RIGHT));
            if (value == null) {
                value = node.getChildByFieldName(nodeField(PythonNodeField.VALUE));
            }
            if (value == null) {
                return;
            }
            walk(value, child -> {
                if (!nodeType(STRING_CONTENT).equals(child.getType())) {
                    return;
                }
                String name = text(child, source);
                if (isPythonIdentifierName(name)) {
                    names.add(name);
                }
            });
        });
        return Set.copyOf(names);
    }

    private static boolean isPythonIdentifierName(String name) {
        if (name.isBlank() || !isPythonIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!isPythonIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPythonIdentifierStart(char ch) {
        return ch == '_' || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }

    private static boolean isPythonIdentifierPart(char ch) {
        return isPythonIdentifierStart(ch) || (ch >= '0' && ch <= '9');
    }

    private static boolean isSelfAttributeName(String localName) {
        return localName.startsWith(SELF_ATTRIBUTE_PREFIX);
    }

    private static Set<ExportIndex.ClassMember> classMembers(PythonAnalyzer analyzer, ProjectFile file) {
        var members = new LinkedHashSet<ExportIndex.ClassMember>();
        for (CodeUnit cu : analyzer.getDeclarations(file)) {
            if (!cu.isFunction() && !cu.isField()) {
                continue;
            }
            String ownerName = ownerNameOf(cu);
            if (!ownerName.isEmpty()) {
                members.add(new ExportIndex.ClassMember(ownerName, cu.identifier(), false, cu.kind()));
            }
        }
        return Set.copyOf(members);
    }

    private static String ownerNameOf(CodeUnit codeUnit) {
        String shortName = codeUnit.shortName();
        int lastDot = shortName.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        return shortName.substring(0, lastDot);
    }

    private static boolean isIdentifier(TSNode node) {
        return nodeType(IDENTIFIER).equals(node.getType());
    }

    private static String localSymbolName(@Nullable TSNode node, SourceContent source) {
        if (node == null) {
            return "";
        }
        return String.join(".", attributeChain(node, source));
    }

    private static @Nullable TSNode firstNamedIdentifier(TSNode node) {
        for (TSNode child : node.getNamedChildren()) {
            if (child != null && isIdentifier(child)) {
                return child;
            }
        }
        return null;
    }

    private static @Nullable TSNode namedChildAfter(TSNode node, @Nullable TSNode previous) {
        if (previous == null) {
            return null;
        }
        for (TSNode child : node.getNamedChildren()) {
            if (child == null || child.getStartByte() <= previous.getEndByte()) {
                continue;
            }
            return child;
        }
        return null;
    }

    private static boolean sameNode(TSNode left, @Nullable TSNode right) {
        return right != null && left.getStartByte() == right.getStartByte() && left.getEndByte() == right.getEndByte();
    }

    private static IAnalyzer.Range rangeOf(TSNode node) {
        return new IAnalyzer.Range(
                node.getStartByte(),
                node.getEndByte(),
                node.getStartPoint().getRow(),
                node.getEndPoint().getRow(),
                node.getStartByte());
    }

    private static String text(TSNode node, SourceContent source) {
        return source.substringFrom(node);
    }

    private static void walk(TSNode node, java.util.function.Consumer<TSNode> consumer) {
        var stack = new ArrayDeque<TSNode>();
        stack.addLast(node);
        int visited = 0;
        while (!stack.isEmpty() && visited < MAX_WALK_NODES) {
            TSNode current = stack.removeLast();
            visited++;
            consumer.accept(current);
            List<TSNode> children = current.getNamedChildren();
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.addLast(children.get(i));
            }
        }
    }
}
