package io.github.jbellis.brokk.testutil;

import io.github.jbellis.brokk.analyzer.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * Mock analyzer implementation for testing that provides minimal functionality to support fragment freezing and linting
 * without requiring a full CPG.
 */
public class TestAnalyzer implements IAnalyzer, SkeletonProvider, LintingProvider {
    private final List<CodeUnit> allClasses;
    private final Map<String, List<CodeUnit>> methodsMap;
    private Function<List<ProjectFile>, LintResult> lintBehavior = files -> new LintResult(List.of());

    public TestAnalyzer(List<CodeUnit> allClasses, Map<String, List<CodeUnit>> methodsMap) {
        this.allClasses = allClasses;
        this.methodsMap = methodsMap;
    }

    public TestAnalyzer() {
        this(List.of(), Map.of());
    }

    public void setLintBehavior(Function<List<ProjectFile>, LintResult> behavior) {
        this.lintBehavior = behavior;
    }

    @Override
    public List<CodeUnit> getAllDeclarations() {
        return allClasses;
    }

    public Map<String, List<CodeUnit>> getMethodsMap() {
        return methodsMap;
    }

    @Override
    public List<CodeUnit> searchDefinitions(@Nullable String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return List.of();
        }
        if (".*".equals(pattern)) {
            return Stream.concat(
                            allClasses.stream(), methodsMap.values().stream().flatMap(List::stream))
                    .toList();
        }

        var regex = "^(?i)" + pattern + "$";

        // Find matching classes
        var matchingClasses =
                allClasses.stream().filter(cu -> cu.fqName().matches(regex)).toList();

        // Find matching methods
        var matchingMethods = methodsMap.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream())
                .filter(cu -> cu.fqName().matches(regex))
                .toList();

        return Stream.concat(matchingClasses.stream(), matchingMethods.stream())
                .distinct()
                .toList();
    }

    @Override
    public Optional<CodeUnit> getDefinition(String fqName) {
        return Optional.empty(); // Return empty for test purposes
    }

    @Override
    public Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        return Set.of(); // Return empty set for test purposes
    }

    @Override
    public Optional<String> getSkeleton(String fqName) {
        return Optional.empty(); // Return empty for test purposes
    }

    @Override
    public Optional<String> getSkeletonHeader(String className) {
        return Optional.empty(); // Return empty for test purposes
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        return Map.of(); // Return empty map for test purposes
    }

    @Override
    public LintResult lintFiles(List<ProjectFile> files) {
        return lintBehavior.apply(files);
    }
}
