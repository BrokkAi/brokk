package io.github.jbellis.brokk.testutil;

import io.github.jbellis.brokk.analyzer.*;
import java.nio.file.Path;
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
public class MockAnalyzer implements IAnalyzer, UsagesProvider, SkeletonProvider, LintingProvider {
    private final ProjectFile mockFile;
    private final List<CodeUnit> allClasses;
    private final Map<String, List<CodeUnit>> methodsMap;
    private Function<List<ProjectFile>, LintResult> lintBehavior = files -> new LintResult(List.of());

    public MockAnalyzer(Path rootDir) {
        this.mockFile = new ProjectFile(rootDir, "MockFile.java");
        this.allClasses = List.of(
                CodeUnit.cls(mockFile, "a.b", "Do"),
                CodeUnit.cls(mockFile, "a.b", "Do.Re"),
                CodeUnit.cls(mockFile, "a.b", "Do.Re.Sub"), // nested inside Re
                CodeUnit.cls(mockFile, "x.y", "Zz"),
                CodeUnit.cls(mockFile, "w.u", "Zz"),
                CodeUnit.cls(mockFile, "test", "CamelClass"),
                CodeUnit.cls(mockFile, "a.b", "Architect"));
        this.methodsMap = Map.ofEntries(
                Map.entry(
                        "a.b.Do",
                        List.of(CodeUnit.fn(mockFile, "a.b", "Do.foo"), CodeUnit.fn(mockFile, "a.b", "Do.bar"))),
                Map.entry("a.b.Do.Re", List.of(CodeUnit.fn(mockFile, "a.b", "Do.Re.baz"))),
                Map.entry("a.b.Do.Re.Sub", List.of(CodeUnit.fn(mockFile, "a.b", "Do.Re.Sub.qux"))),
                Map.entry("x.y.Zz", List.of()),
                Map.entry("w.u.Zz", List.of()),
                Map.entry("test.CamelClass", List.of(CodeUnit.fn(mockFile, "test", "CamelClass.someMethod"))));
    }

    public void setLintBehavior(Function<List<ProjectFile>, LintResult> behavior) {
        this.lintBehavior = behavior;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public List<CodeUnit> getUses(String fqName) {
        return List.of(); // Return empty list for test purposes
    }

    @Override
    public List<CodeUnit> getAllDeclarations() {
        return allClasses;
    }

    @Override
    public List<CodeUnit> getMembersInClass(String fqClass) {
        return methodsMap.getOrDefault(fqClass, List.of());
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
