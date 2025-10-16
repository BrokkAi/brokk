package io.github.jbellis.brokk.analyzer.java;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.JavaAnalyzer;
import io.github.jbellis.brokk.analyzer.TSQueryLoader;
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
        final Set<String> superClassParts = new LinkedHashSet<>();
        final Set<String> importPaths = new LinkedHashSet<>();

        while (cursor.nextMatch(match)) {
            TSNode owner = null;
            final ArrayList<TSNode> superNodes = new ArrayList<>();
            final ArrayList<TSNode> importNodes = new ArrayList<>();
            final Stack<String> pathParts = new Stack<>();

            for (var cap : match.getCaptures()) {
                String name = q.getCaptureNameForId(cap.getIndex());
                switch (name) {
                    case "package.path" -> {
                        var parts = Splitter.on(".").splitToList(textSlice.apply(cap.getNode(), src));
                        pathParts.addAll(parts);
                    }
                    case "type.name" -> {
                        var typeName = textSlice.apply(cap.getNode(), src);
                        var typeFn = Joiner.on("").join(pathParts) + "." + typeName;
                        if (cu.fqName().equals(typeFn)) {
                            owner = cap.getNode();
                        } else {
                            // We are entering a possibly nested type
                            pathParts.push(typeName);
                        }
                    }
                    case "type.super" -> superNodes.add(cap.getNode());
                    case "import.path" -> importNodes.add(cap.getNode());
                }
            }

            if (owner == null) {
                continue;
            } else {
                // todo: Figure out if we've exited the class or we are going deeper
                pathParts.pop();
            }

            // Should try resolve these to full names if possible
            superNodes.stream()
                    .map(n -> textSlice.apply(n, src))
                    .map(JavaAnalyzer::stripGenericTypeArguments)
                    .filter(t -> !t.isEmpty())
                    .forEach(superClassParts::add);
            importNodes.stream()
                    .map(n -> textSlice.apply(n, src))
                    .filter(t -> !t.isEmpty())
                    .forEach(importPaths::add);
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
}
