package ai.brokk.analyzer.java;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.TSQueryLoader;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
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

    /**
     * Computes the direct supertypes of a given CodeUnit by analyzing the AST and resolving type names
     * using the provided resolved imports and global search function.
     *
     * @param cu the CodeUnit to analyze (typically a class)
     * @param tree the Tree-sitter AST for the file containing the CodeUnit
     * @param src the source code text
     * @param language the Tree-sitter language instance
     * @param textSlice function to extract text from AST nodes
     * @param resolvedImports resolved CodeUnits from import statements for this file
     * @param searchDefinitions function to search for CodeUnits globally by pattern
     * @return list of resolved supertype CodeUnits
     */
    @SuppressWarnings("unused")
    public static List<CodeUnit> compute(
            CodeUnit cu,
            TSTree tree,
            String src,
            TSLanguage language,
            BiFunction<TSNode, String, String> textSlice,
            Set<CodeUnit> resolvedImports,
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
                        String pkgText = textSlice.apply(cap.getNode(), src);
                        // Split by dots to get package parts
                        List<String> parts = Splitter.on(".").splitToList(pkgText);
                        packageParts.clear();
                        for (String part : parts) {
                            if (!part.isEmpty()) {
                                packageParts.add(part);
                            }
                        }
                    }
                    case "type.decl" -> typeDeclNode = cap.getNode();
                    case "type.name" -> currentTypeName = textSlice.apply(cap.getNode(), src);
                    case "type.super" -> superNodes.add(cap.getNode());
                }
            }

            // Skip non-type matches (e.g., package patterns)
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

        final String currentPackage = cu.packageName();

        return superClassParts.stream()
                .flatMap(rawName -> resolveSupertype(rawName, currentPackage, resolvedImports, searchDefinitions))
                .toList();
    }

    /**
     * Resolves a single supertype name to a CodeUnit using resolved imports and global search.
     *
     * @param rawName the raw supertype name from the AST (may include generic type arguments)
     * @param currentPackage the package of the class being analyzed
     * @param resolvedImports the set of CodeUnits resolved from import statements
     * @param searchDefinitions function to search for CodeUnits globally by pattern
     * @return a stream containing the resolved CodeUnit, or empty if not found
     */
    private static Stream<CodeUnit> resolveSupertype(
            String rawName,
            String currentPackage,
            Set<CodeUnit> resolvedImports,
            Function<String, List<CodeUnit>> searchDefinitions) {
        String name = JavaAnalyzer.stripGenericTypeArguments(rawName).trim();
        if (name.isEmpty()) {
            return Stream.empty();
        }

        // If name is fully qualified (contains dot), try it directly
        if (name.contains(".")) {
            Optional<CodeUnit> found = searchDefinitions.apply(name).stream()
                    .filter(CodeUnit::isClass)
                    .findFirst();
            if (found.isPresent()) {
                return Stream.of(found.get());
            }
        }

        // Extract simple name (last component after any dots)
        String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;

        // Try to find in resolved imports by simple name
        Optional<CodeUnit> foundInImports = resolvedImports.stream()
                .filter(cu -> cu.isClass() && cu.identifier().equals(simple))
                .findFirst();
        if (foundInImports.isPresent()) {
            return Stream.of(foundInImports.get());
        }

        // Try same package as current class
        if (!currentPackage.isBlank()) {
            String samePackageCandidate = currentPackage + "." + simple;
            Optional<CodeUnit> foundInPackage = searchDefinitions.apply(samePackageCandidate).stream()
                    .filter(CodeUnit::isClass)
                    .findFirst();
            if (foundInPackage.isPresent()) {
                return Stream.of(foundInPackage.get());
            }
        }

        // Try implicit java.lang import
        String javaLangCandidate = "java.lang." + simple;
        Optional<CodeUnit> foundInJavaLang = searchDefinitions.apply(javaLangCandidate).stream()
                .filter(CodeUnit::isClass)
                .findFirst();
        if (foundInJavaLang.isPresent()) {
            return Stream.of(foundInJavaLang.get());
        }

        // Fallback: global search by simple name pattern
        String pattern = ".*(?<!\\w)" + Pattern.quote(simple) + "$";
        Optional<CodeUnit> foundGlobal = searchDefinitions.apply(pattern).stream()
                .filter(CodeUnit::isClass)
                .findFirst();
        return foundGlobal.stream();
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
