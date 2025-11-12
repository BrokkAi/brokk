package ai.brokk.analyzer.java;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.JavaAnalyzer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class JavaTypeAnalyzer {

    private JavaTypeAnalyzer() {}

    /**
     * Resolves direct supertypes from already-cached raw names.
     * rawSupertypeNames must preserve Java order: superclass first (if any), then interfaces.
     */
    public static List<CodeUnit> compute(
            List<String> rawSupertypeNames,
            String currentPackage,
            Set<CodeUnit> resolvedImports,
            Function<String, Optional<CodeUnit>> getDefinition,
            Function<String, List<CodeUnit>> searchDefinitions) {

        return rawSupertypeNames.stream()
                .flatMap(rawName ->
                        resolveSupertype(rawName, currentPackage, resolvedImports, getDefinition, searchDefinitions))
                .toList();
    }

    /**
     * Resolves a single supertype name to a CodeUnit using resolved imports and global search.
     *
     * @param rawName the raw supertype name from cached data (may include generic type arguments)
     * @param currentPackage the package of the class being analyzed
     * @param resolvedImports the set of CodeUnits resolved from import statements
     * @param getDefinition function to perform an exact-match search for a CodeUnit
     * @param searchDefinitions function to search for CodeUnits globally by pattern
     * @return a stream containing the resolved CodeUnit, or empty if not found
     */
    private static Stream<CodeUnit> resolveSupertype(
            String rawName,
            String currentPackage,
            Set<CodeUnit> resolvedImports,
            Function<String, Optional<CodeUnit>> getDefinition,
            Function<String, List<CodeUnit>> searchDefinitions) {
        String name = JavaAnalyzer.stripGenericTypeArguments(rawName).trim();
        if (name.isEmpty()) {
            return Stream.empty();
        }

        // If name is fully qualified (contains dot), try it directly
        if (name.contains(".")) {
            Optional<CodeUnit> found =
                    getDefinition.apply(name).stream().filter(CodeUnit::isClass).findFirst();
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
            Optional<CodeUnit> foundInPackage = getDefinition.apply(samePackageCandidate).stream()
                    .filter(CodeUnit::isClass)
                    .findFirst();
            if (foundInPackage.isPresent()) {
                return Stream.of(foundInPackage.get());
            }
        }

        // Fallback: global search by simple name pattern
        String pattern = "\\b%s\\b".formatted(Pattern.quote(simple));
        Optional<CodeUnit> foundGlobal = searchDefinitions.apply(pattern).stream()
                .filter(CodeUnit::isClass)
                .findFirst();
        return foundGlobal.stream();
    }
}
