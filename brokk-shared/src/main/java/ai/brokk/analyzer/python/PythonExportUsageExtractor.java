package ai.brokk.analyzer.python;

import static ai.brokk.analyzer.python.Constants.nodeField;
import static ai.brokk.analyzer.python.Constants.nodeType;
import static org.treesitter.PythonNodeType.ATTRIBUTE;
import static org.treesitter.PythonNodeType.IDENTIFIER;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportInfo;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.analyzer.usages.ExportIndex;
import ai.brokk.analyzer.usages.ImportBinder;
import ai.brokk.analyzer.usages.ReferenceCandidate;
import ai.brokk.analyzer.usages.ReferenceKind;
import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
