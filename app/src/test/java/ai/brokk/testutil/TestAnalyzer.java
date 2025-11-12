package ai.brokk.testutil;

import ai.brokk.IProject;
import ai.brokk.analyzer.*;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.LintResult;
import ai.brokk.analyzer.LintingProvider;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SkeletonProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    public List<CodeUnit> getTopLevelDeclarations(ProjectFile file) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Language> languages() {
        throw new UnsupportedOperationException();
    }

    @Override
    public IAnalyzer update(Set<ProjectFile> changedFiles) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IAnalyzer update() {
        throw new UnsupportedOperationException();
    }

    @Override
    public IProject getProject() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<CodeUnit> getAllDeclarations() {
        return allClasses;
    }

    public Map<String, List<CodeUnit>> getMethodsMap() {
        return methodsMap;
    }

    @Override
    public Set<CodeUnit> searchDefinitions(@Nullable String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return Set.of();
        }
        if (".*".equals(pattern)) {
            return Stream.concat(
                            allClasses.stream(), methodsMap.values().stream().flatMap(List::stream))
                    .collect(Collectors.toSet());
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

        return Stream.concat(matchingClasses.stream(), matchingMethods.stream()).collect(Collectors.toSet());
    }

    @Override
    public List<String> importStatementsOf(ProjectFile file) {
        return List.of();
    }

    @Override
    public Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, Range range) {
        return Optional.empty();
    }

    @Override
    public Optional<CodeUnit> getDefinition(String fqName) {
        return Optional.empty(); // Return empty for test purposes
    }

    @Override
    public Set<CodeUnit> getDeclarations(ProjectFile file) {
        return Set.of(); // Return empty set for test purposes
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        return Map.of(); // Return empty map for test purposes
    }

    @Override
    public Optional<String> getSkeleton(CodeUnit cu) {
        return Optional.empty(); // Return empty for test purposes
    }

    @Override
    public Optional<String> getSkeletonHeader(CodeUnit classUnit) {
        return Optional.empty(); // Return empty for test purposes
    }

    @Override
    public LintResult lintFiles(List<ProjectFile> files) {
        return lintBehavior.apply(files);
    }
}
