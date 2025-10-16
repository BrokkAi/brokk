package io.github.jbellis.brokk.analyzer.java;

import static io.github.jbellis.brokk.analyzer.JavaAnalyzer.stripGenericTypeArguments;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.TSQueryLoader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        Set<String> superClassParts = new LinkedHashSet<>();

        while (cursor.nextMatch(match)) {
            TSNode owner = null;
            TSNode superNode = null;
            TSNode interfacesNode = null;

            for (var cap : match.getCaptures()) {
                String name = q.getCaptureNameForId(cap.getIndex());
                if ("class.name".equals(name)) {
                    owner = cap.getNode();
                } else if ("class.super".equals(name)) {
                    superNode = cap.getNode();
                } else if ("class.interface".equals(name)) {
                    interfacesNode = cap.getNode();
                }
            }

            // TODO: Make sure owner == CodeUnit.shortName or whatever
            if (owner == null) {
                continue;
            }

            if (superNode != null) {
                String t = stripGenericTypeArguments(textSlice.apply(superNode, src));
                if (!t.isEmpty()) superClassParts.add(t);
            }
            if (interfacesNode != null) {
                String seg = stripGenericTypeArguments(textSlice.apply(interfacesNode, src));
                if (!seg.isEmpty()) superClassParts.add(seg);
            }
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
