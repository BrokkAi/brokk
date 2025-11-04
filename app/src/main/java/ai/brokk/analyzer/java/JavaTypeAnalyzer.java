package ai.brokk.analyzer.java;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.TSQueryLoader;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;

public class JavaTypeAnalyzer {

    private static final String languageName = "java";

    private JavaTypeAnalyzer() {}

    @SuppressWarnings("unused")
    public static List<CodeUnit> compute(
            CodeUnit cu,
            TSTree tree,
            String src,
            TSLanguage language,
            BiFunction<TSNode, String, String> textSlice,
            Function<String, List<CodeUnit>> searchDefinitions) {
        final TSNode root = tree.getRootNode();
        final String query = TSQueryLoader.loadTypeDeclarationsQuery(languageName);

        TSQuery q = new TSQuery(language, query);
        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(q, root);

        TSQueryMatch match = new TSQueryMatch();

        final Deque<String> packageParts = new ArrayDeque<>();
        final Deque<TSNode> typeNodeStack = new ArrayDeque<>();
        final Deque<String> typeNameStack = new ArrayDeque<>();

        final Set<String> superClassParts = new LinkedHashSet<>();
        final List<String> importPaths = new ArrayList<>();

        while (cursor.nextMatch(match)) {
            TSNode typeDeclNode = null;
            String currentTypeName = null;
            final ArrayList<TSNode> superNodes = new ArrayList<>();

            for (var cap : match.getCaptures()) {
                String name = q.getCaptureNameForId(cap.getIndex());
                switch (name) {
                    case "package.path" -> {
                        var parts = Splitter.on(".").splitToList(textSlice.apply(cap.getNode(), src));
                        packageParts.clear();
                        packageParts.addAll(parts);
                    }
                    case "import.path" -> {
                        String path = textSlice.apply(cap.getNode(), src).strip();
                        if (!path.isEmpty()) {
                            importPaths.add(path);
                        }
                    }
                    case "type.decl" -> typeDeclNode = cap.getNode();
                    case "type.name" -> currentTypeName = textSlice.apply(cap.getNode(), src);
                    case "type.super" -> superNodes.add(cap.getNode());
                }
            }

            // Skip non-type matches (e.g., package/import patterns)
            if (typeDeclNode == null) {
                continue;
            }

            // Reconcile stack with current declaration ancestry
            while (!typeNodeStack.isEmpty() && !isAncestor(typeNodeStack.getLast(), typeDeclNode)) {
                typeNodeStack.removeLast();
                typeNameStack.removeLast();
            }

            if (currentTypeName == null) {
                continue;
            }

            // Build FQN: package + ancestor type names + current type name
            var typeParts = new ArrayList<>(typeNameStack);
            typeParts.add(currentTypeName);

            String fqn;
            if (packageParts.isEmpty()) {
                fqn = Joiner.on(".").join(typeParts);
            } else {
                fqn = Joiner.on(".").join(packageParts) + "." + Joiner.on(".").join(typeParts);
            }

            // If this is the owner type, collect its super types
            if (cu.fqName().equals(fqn)) {
                superNodes.stream()
                        .map(n -> textSlice.apply(n, src))
                        .map(JavaAnalyzer::stripGenericTypeArguments)
                        .filter(t -> !t.isEmpty())
                        .forEach(superClassParts::add);
            }

            // Enter this type scope for subsequent nested types
            typeNodeStack.addLast(typeDeclNode);
            typeNameStack.addLast(currentTypeName);
        }

        // Build import resolution helpers
        Map<String, String> explicitImports = new LinkedHashMap<>(); // simpleName -> FQCN
        List<String> wildcardPackages = new ArrayList<>();
        for (String raw : importPaths) {
            String p = raw.strip();
            if (p.isEmpty()) continue;
            int lastDot = p.lastIndexOf('.');
            if (lastDot < 0) {
                // treat as a package wildcard root (unlikely but harmless)
                wildcardPackages.add(p);
                continue;
            }
            String last = p.substring(lastDot + 1);
            // Heuristic: if last segment starts uppercase, treat as explicit type import; otherwise treat as wildcard
            // package
            if (!last.isEmpty() && Character.isUpperCase(last.charAt(0))) {
                explicitImports.putIfAbsent(last, p);
            } else {
                wildcardPackages.add(p);
            }
        }

        final String currentPackage = cu.packageName();

        return superClassParts.stream()
                .flatMap(rawName -> {
                    String name =
                            JavaAnalyzer.stripGenericTypeArguments(rawName).trim();
                    if (name.isEmpty()) return java.util.stream.Stream.<CodeUnit>empty();

                    // Prepare candidates in priority order
                    List<String> candidates = new ArrayList<>();
                    String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;

                    if (name.contains(".")) candidates.add(name);

                    String explicit = explicitImports.get(simple);
                    if (explicit != null && !explicit.isBlank()) candidates.add(explicit);

                    for (String wp : wildcardPackages) {
                        candidates.add(wp + "." + simple);
                    }

                    if (!currentPackage.isBlank()) {
                        candidates.add(currentPackage + "." + simple);
                    }

                    // Implicit java.lang imports
                    candidates.add("java.lang." + simple);

                    // Fallbacks
                    candidates.add(simple);

                    for (String cand : candidates) {
                        String pattern = ".*(?<!\\w)" + Pattern.quote(cand) + "$";
                        Optional<CodeUnit> found = searchDefinitions.apply(pattern).stream()
                                .filter(CodeUnit::isClass)
                                .findFirst();
                        if (found.isPresent()) {
                            return Stream.of(found.get());
                        }
                    }
                    return Stream.empty();
                })
                .toList();
    }

    private static boolean isAncestor(@Nullable TSNode ancestor, TSNode node) {
        if (ancestor == null || ancestor.isNull()) {
            return false;
        }
        for (var p = node.getParent(); p != null && !p.isNull(); p = p.getParent()) {
            if (p.equals(ancestor)) return true;
        }
        return false;
    }
}
