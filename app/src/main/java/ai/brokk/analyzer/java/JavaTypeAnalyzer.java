package ai.brokk.analyzer.java;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.TSQueryLoader;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.treesitter.*;

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

        return superClassParts.stream()
                .flatMap(identifier ->
                        // TODO: Incorporate import information
                        searchDefinitions.apply(".*(?<!\\w)\\.?" + identifier + "$").stream()
                                .filter(CodeUnit::isClass)
                                .findFirst()
                                .stream())
                .toList();
    }

    private static boolean isAncestor(TSNode ancestor, TSNode node) {
        for (var p = node.getParent(); p != null; p = p.getParent()) {
            if (p.equals(ancestor)) return true;
        }
        return false;
    }
}
