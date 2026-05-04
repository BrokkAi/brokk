package ai.brokk.analyzer.python;

import static ai.brokk.analyzer.python.Constants.nodeField;
import static ai.brokk.analyzer.python.Constants.nodeType;
import static org.treesitter.PythonNodeType.ASSIGNMENT;
import static org.treesitter.PythonNodeType.ATTRIBUTE;
import static org.treesitter.PythonNodeType.CALL;
import static org.treesitter.PythonNodeType.CLASS_DEFINITION;
import static org.treesitter.PythonNodeType.FUNCTION_DEFINITION;
import static org.treesitter.PythonNodeType.IDENTIFIER;
import static org.treesitter.PythonNodeType.TYPED_PARAMETER;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportInfo;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.treesitter.PythonNodeField;
import org.treesitter.TSNode;

public final class PythonExportUsageExtractor {
    private static final Splitter COMMA_SPLITTER =
            Splitter.on(',').trimResults().omitEmptyStrings();
    private static final Pattern FROM_IMPORT_PATTERN =
            Pattern.compile("^from\\s+([A-Za-z_][\\w.]*|\\.+[A-Za-z_][\\w.]*|\\.+)\\s+import\\s+(.+)$");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^import\\s+(.+)$");
    private static final Pattern STATIC_STRING_PATTERN = Pattern.compile("[\"']([A-Za-z_]\\w*)[\"']");
    private static final Pattern SIMPLE_TYPE_TOKEN_PATTERN = Pattern.compile("[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)?");

    private PythonExportUsageExtractor() {}

    public static ExportIndex computeExportIndex(
            PythonAnalyzer analyzer, ProjectFile file, String source, List<ImportInfo> imports) {
        var exports = new LinkedHashMap<String, ExportIndex.ExportEntry>();
        for (CodeUnit cu : analyzer.getTopLevelDeclarations(file)) {
            if (cu.kind() == CodeUnitType.CLASS || cu.kind() == CodeUnitType.FUNCTION) {
                exports.put(cu.identifier(), new ExportIndex.LocalExport(cu.identifier()));
            }
        }
        staticAllNames(source).forEach(name -> exports.putIfAbsent(name, new ExportIndex.LocalExport(name)));
        computeImportBinder(imports).bindings().keySet().stream()
                .filter(name -> !exports.containsKey(name))
                .forEach(name -> exports.put(name, new ExportIndex.LocalExport(name)));
        return new ExportIndex(Map.copyOf(exports), List.of(), Set.of(), classMembers(analyzer, file));
    }

    public static ImportBinder computeImportBinder(List<ImportInfo> imports) {
        var bindings = new LinkedHashMap<String, ImportBinder.ImportBinding>();
        for (ImportInfo info : imports) {
            bindImport(info.rawSnippet())
                    .forEach(binding -> bindings.put(binding.localName(), binding.importBinding()));
        }
        return new ImportBinder(Map.copyOf(bindings));
    }

    public static Set<ReferenceCandidate> computeUsageCandidates(ProjectFile file, TSNode root, String source) {
        var candidates = new LinkedHashSet<ReferenceCandidate>();
        CodeUnit enclosing = CodeUnit.module(file, "", "_module_");
        walk(root, node -> {
            if (isIdentifier(node) && !insideImport(node)) {
                String identifier = text(node, source);
                if (!identifier.isBlank()) {
                    candidates.add(new ReferenceCandidate(
                            identifier, null, null, false, ReferenceKind.STATIC_REFERENCE, rangeOf(node), enclosing));
                }
            }

            if (nodeType(ATTRIBUTE).equals(node.getType())) {
                TSNode object = node.getChildByFieldName(nodeField(PythonNodeField.OBJECT));
                TSNode attribute = node.getChildByFieldName(nodeField(PythonNodeField.ATTRIBUTE));
                if (object != null && attribute != null && isIdentifier(object) && isIdentifier(attribute)) {
                    candidates.add(new ReferenceCandidate(
                            text(attribute, source),
                            text(object, source),
                            null,
                            false,
                            ReferenceKind.STATIC_REFERENCE,
                            rangeOf(attribute),
                            enclosing));
                }
            }
        });
        return Set.copyOf(candidates);
    }

    public static Set<ResolvedReceiverCandidate> computeResolvedReceiverCandidates(
            PythonAnalyzer analyzer, ProjectFile file, TSNode root, String source, ImportBinder binder) {
        CodeUnit enclosing = CodeUnit.module(file, "", "_module_");
        var events = new ArrayList<LocalUsageEvent>();
        collectLocalUsageEvents(analyzer, file, root, source, binder, enclosing, new LinkedHashSet<>(), events);
        return LocalUsageInference.infer(events);
    }

    private record Binding(String localName, ImportBinder.ImportBinding importBinding) {}

    private static List<Binding> bindImport(String rawSnippet) {
        String statement = rawSnippet.strip();
        var fromMatcher = FROM_IMPORT_PATTERN.matcher(statement);
        if (fromMatcher.matches()) {
            String moduleSpecifier = fromMatcher.group(1);
            String importedPart = stripParenthesized(fromMatcher.group(2));
            if ("*".equals(importedPart.strip())) {
                return List.of();
            }
            var bindings = new ArrayList<Binding>();
            for (String part : COMMA_SPLITTER.split(importedPart)) {
                Binding binding = namedBinding(moduleSpecifier, part);
                if (binding != null) {
                    bindings.add(binding);
                }
            }
            return List.copyOf(bindings);
        }

        var importMatcher = IMPORT_PATTERN.matcher(statement);
        if (importMatcher.matches()) {
            var bindings = new ArrayList<Binding>();
            for (String part : COMMA_SPLITTER.split(importMatcher.group(1))) {
                Binding binding = moduleBinding(part);
                if (binding != null) {
                    bindings.add(binding);
                }
            }
            return List.copyOf(bindings);
        }

        return List.of();
    }

    private static @Nullable Binding namedBinding(String moduleSpecifier, String rawPart) {
        String part = rawPart.strip();
        if (part.isBlank() || "*".equals(part)) {
            return null;
        }
        String importedName;
        String localName;
        String[] aliasParts = part.split("\\s+as\\s+", 2);
        importedName = aliasParts[0].strip();
        localName = aliasParts.length == 2 ? aliasParts[1].strip() : importedName;
        if (importedName.isBlank() || localName.isBlank()) {
            return null;
        }
        return new Binding(
                localName,
                new ImportBinder.ImportBinding(moduleSpecifier, ImportBinder.ImportKind.NAMED, importedName));
    }

    private static @Nullable Binding moduleBinding(String rawPart) {
        String part = rawPart.strip();
        if (part.isBlank()) {
            return null;
        }
        String moduleSpecifier;
        String localName;
        String[] aliasParts = part.split("\\s+as\\s+", 2);
        moduleSpecifier = aliasParts[0].strip();
        localName = aliasParts.length == 2 ? aliasParts[1].strip() : firstModuleSegment(moduleSpecifier);
        if (moduleSpecifier.isBlank() || localName.isBlank()) {
            return null;
        }
        return new Binding(
                localName, new ImportBinder.ImportBinding(moduleSpecifier, ImportBinder.ImportKind.NAMESPACE, null));
    }

    private static void collectLocalUsageEvents(
            PythonAnalyzer analyzer,
            ProjectFile file,
            TSNode node,
            String source,
            ImportBinder binder,
            CodeUnit enclosing,
            Set<String> locallyShadowedNames,
            List<LocalUsageEvent> events) {
        String type = node.getType();

        if (nodeType(TYPED_PARAMETER).equals(type)) {
            handleTypedParameter(analyzer, file, node, source, binder, locallyShadowedNames, events);
        } else if (nodeType(ASSIGNMENT).equals(type)) {
            handleAssignment(analyzer, file, node, source, binder, locallyShadowedNames, events);
        } else if (nodeType(CLASS_DEFINITION).equals(type)
                || nodeType(FUNCTION_DEFINITION).equals(type)) {
            handleLocalDeclaration(node, source, locallyShadowedNames, events);
        } else if (nodeType(ATTRIBUTE).equals(type)) {
            handleReceiverAccess(node, source, enclosing, events);
        }

        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child != null) {
                collectLocalUsageEvents(analyzer, file, child, source, binder, enclosing, locallyShadowedNames, events);
            }
        }
    }

    private static void handleTypedParameter(
            PythonAnalyzer analyzer,
            ProjectFile file,
            TSNode node,
            String source,
            ImportBinder binder,
            Set<String> locallyShadowedNames,
            List<LocalUsageEvent> events) {
        TSNode nameNode = node.getChildByFieldName(nodeField(PythonNodeField.NAME));
        if (nameNode == null) {
            nameNode = firstNamedIdentifier(node);
        }
        TSNode typeNode = node.getChildByFieldName("type");
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
            String source,
            ImportBinder binder,
            Set<String> locallyShadowedNames,
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

        TSNode typeNode = node.getChildByFieldName("type");
        if (typeNode != null) {
            Set<ReceiverTargetRef> targets =
                    receiverTargetsFromType(analyzer, file, typeNode, source, binder, locallyShadowedNames);
            if (!targets.isEmpty() && !localName.equals(text(typeNode, source).strip())) {
                events.add(new LocalUsageEvent.SeedSymbol(localName, targets));
                locallyShadowedNames.add(localName);
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
            locallyShadowedNames.add(localName);
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
        locallyShadowedNames.add(localName);
    }

    private static void handleReceiverAccess(
            TSNode node, String source, CodeUnit enclosing, List<LocalUsageEvent> events) {
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
            TSNode node, String source, Set<String> locallyShadowedNames, List<LocalUsageEvent> events) {
        TSNode name = node.getChildByFieldName(nodeField(PythonNodeField.NAME));
        if (name == null || !isIdentifier(name)) {
            return;
        }
        String localName = text(name, source);
        events.add(new LocalUsageEvent.DeclareSymbol(localName));
        locallyShadowedNames.add(localName);
    }

    private static Set<ReceiverTargetRef> receiverTargetsFromType(
            PythonAnalyzer analyzer,
            ProjectFile file,
            TSNode typeNode,
            String source,
            ImportBinder binder,
            Set<String> locallyShadowedNames) {
        var targets = new LinkedHashSet<ReceiverTargetRef>();
        var matcher = SIMPLE_TYPE_TOKEN_PATTERN.matcher(text(typeNode, source));
        while (matcher.find()) {
            receiverTargetFromTypeName(analyzer, file, matcher.group(), binder, locallyShadowedNames, true)
                    .ifPresent(targets::add);
        }
        return Set.copyOf(targets);
    }

    private static Optional<ReceiverTargetRef> receiverTargetFromTypeName(
            PythonAnalyzer analyzer,
            ProjectFile file,
            TSNode typeNode,
            String source,
            ImportBinder binder,
            Set<String> locallyShadowedNames,
            boolean instanceReceiver) {
        return receiverTargetFromTypeName(
                analyzer, file, text(typeNode, source), binder, locallyShadowedNames, instanceReceiver);
    }

    private static Optional<ReceiverTargetRef> receiverTargetFromTypeName(
            PythonAnalyzer analyzer,
            ProjectFile file,
            String typeName,
            ImportBinder binder,
            Set<String> locallyShadowedNames,
            boolean instanceReceiver) {
        String stripped = typeName.strip();
        if (stripped.isBlank()) {
            return Optional.empty();
        }

        int dot = stripped.indexOf('.');
        if (dot > 0) {
            String qualifier = stripped.substring(0, dot);
            String exportedName = stripped.substring(dot + 1);
            ImportBinder.ImportBinding binding = binder.bindings().get(qualifier);
            if (binding == null || binding.kind() != ImportBinder.ImportKind.NAMESPACE || exportedName.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ReceiverTargetRef(
                    binding.moduleSpecifier(), exportedName, instanceReceiver, instanceReceiver ? 0.9 : 1.0, null));
        }

        if (locallyShadowedNames.contains(stripped)) {
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

    private static boolean isMethodCallAttribute(TSNode attributeNode) {
        TSNode parent = attributeNode.getParent();
        return parent != null && nodeType(CALL).equals(parent.getType());
    }

    private static String firstModuleSegment(String moduleSpecifier) {
        int dot = moduleSpecifier.indexOf('.');
        return dot >= 0 ? moduleSpecifier.substring(0, dot) : moduleSpecifier;
    }

    private static String stripParenthesized(String value) {
        String stripped = value.strip();
        if (stripped.startsWith("(") && stripped.endsWith(")")) {
            return stripped.substring(1, stripped.length() - 1).strip();
        }
        return stripped;
    }

    private static Set<String> staticAllNames(String source) {
        var names = new LinkedHashSet<String>();
        for (String line : source.lines().toList()) {
            String stripped = line.strip();
            if (!stripped.startsWith("__all__") || !stripped.contains("=")) {
                continue;
            }
            var matcher = STATIC_STRING_PATTERN.matcher(stripped.substring(stripped.indexOf('=') + 1));
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return Set.copyOf(names);
    }

    private static Set<ExportIndex.ClassMember> classMembers(PythonAnalyzer analyzer, ProjectFile file) {
        var members = new LinkedHashSet<ExportIndex.ClassMember>();
        for (CodeUnit cu : analyzer.getAllDeclarations()) {
            if (!cu.source().equals(file)) {
                continue;
            }
            if (cu.kind() != CodeUnitType.FUNCTION && cu.kind() != CodeUnitType.FIELD) {
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

    private static String localSymbolName(@Nullable TSNode node, String source) {
        if (node == null) {
            return "";
        }
        if (isIdentifier(node)) {
            return text(node, source);
        }
        if (!nodeType(ATTRIBUTE).equals(node.getType())) {
            return "";
        }
        TSNode object = node.getChildByFieldName(nodeField(PythonNodeField.OBJECT));
        TSNode attribute = node.getChildByFieldName(nodeField(PythonNodeField.ATTRIBUTE));
        if (attribute == null || !isIdentifier(attribute)) {
            return "";
        }
        String receiverName = localSymbolName(object, source);
        return receiverName.isBlank() ? "" : receiverName + "." + text(attribute, source);
    }

    private static @Nullable TSNode firstNamedIdentifier(TSNode node) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
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
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child == null || child.getStartByte() <= previous.getEndByte()) {
                continue;
            }
            return child;
        }
        return null;
    }

    private static boolean insideImport(TSNode node) {
        TSNode current = node;
        while (current.getParent() != null) {
            current = current.getParent();
            String type = current.getType();
            if ("import_statement".equals(type) || "import_from_statement".equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static IAnalyzer.Range rangeOf(TSNode node) {
        return new IAnalyzer.Range(
                node.getStartByte(),
                node.getEndByte(),
                node.getStartPoint().getRow(),
                node.getEndPoint().getRow(),
                node.getStartByte());
    }

    private static String text(TSNode node, String source) {
        return source.substring(node.getStartByte(), node.getEndByte());
    }

    private static void walk(TSNode node, java.util.function.Consumer<TSNode> consumer) {
        consumer.accept(node);
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child != null) {
                walk(child, consumer);
            }
        }
    }
}
