package ai.brokk.testutil;

import ai.brokk.analyzer.*;
import ai.brokk.project.IProject;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * Mock analyzer implementation for testing that provides minimal functionality to support fragment freezing and linting
 * without requiring a full CPG.
 */
public class TestAnalyzer
        implements IAnalyzer, TypeHierarchyProvider, ImportAnalysisProvider, TypeAliasProvider, TestDetectionProvider {
    private boolean supportsImportAnalysis = true;
    private boolean supportsTypeHierarchy = true;
    private boolean supportsTypeAlias = true;
    private boolean supportsTestDetection = true;

    private final Map<ProjectFile, Boolean> fileTestMarkers = new HashMap<>();
    private final List<CodeUnit> allClasses;
    private final Map<String, List<CodeUnit>> methodsMap;
    private final Map<CodeUnit, List<CodeUnit>> ancestorsMap = new HashMap<>();
    private final Map<CodeUnit, Integer> complexityMap = new HashMap<>();
    private final Map<CodeUnit, Integer> cognitiveComplexityMap = new HashMap<>();
    private final Map<CodeUnit, List<String>> displaySignatures = new HashMap<>();
    private final Map<CodeUnit, String> skeletons = new HashMap<>();
    private final Map<CodeUnit, String> sources = new HashMap<>();
    private final Map<CodeUnit, List<Range>> rangesByCodeUnit = new HashMap<>();
    private final Map<ProjectFile, List<ImportInfo>> importInfoByFile = new HashMap<>();
    private final Map<CodeUnit, Set<String>> relevantImportsByCodeUnit = new LinkedHashMap<>();
    private @Nullable IProject testProject;

    public TestAnalyzer(
            List<CodeUnit> allClasses, Map<String, List<CodeUnit>> methodsMap, @Nullable IProject testProject) {
        this.allClasses = allClasses;
        this.methodsMap = methodsMap;
        this.testProject = testProject;
    }

    public TestAnalyzer(List<CodeUnit> allClasses, Map<String, List<CodeUnit>> methodsMap) {
        this(allClasses, methodsMap, null);
    }

    public TestAnalyzer() {
        this(new ArrayList<>(), Map.of());
    }

    public void addDeclaration(CodeUnit cu) {
        this.allClasses.add(cu);
    }

    @Override
    public List<CodeUnit> getTopLevelDeclarations(ProjectFile file) {
        return allClasses.stream()
                .filter(cu -> cu.source().equals(file))
                .filter(cu -> cu.isClass() || cu.isModule() || cu.isFunction())
                .collect(Collectors.toList());
    }

    @Override
    public Set<ProjectFile> getAnalyzedFiles() {
        return allClasses.stream().map(CodeUnit::source).collect(Collectors.toSet());
    }

    @Override
    public List<Range> rangesOf(CodeUnit codeUnit) {
        return List.copyOf(rangesByCodeUnit.getOrDefault(codeUnit, List.of()));
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
        if (testProject == null) {
            throw new UnsupportedOperationException();
        }
        return testProject;
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

    private final Map<ProjectFile, List<String>> importStatementsByFile = new HashMap<>();

    public void setImportStatements(ProjectFile file, List<String> imports) {
        importStatementsByFile.put(file, imports);
    }

    @Override
    public List<String> importStatementsOf(ProjectFile file) {
        return List.copyOf(importStatementsByFile.getOrDefault(file, List.of()));
    }

    @Override
    public Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, Range range) {
        return enclosingCodeUnit(file, range.startLine(), range.endLine());
    }

    @Override
    public Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, int startLine, int endLine) {
        return allClasses.stream()
                .filter(cu -> cu.source().equals(file))
                .filter(cu -> rangesOf(cu).stream().anyMatch(r -> startLine >= r.startLine() && endLine <= r.endLine()))
                .min(Comparator.comparingInt(cu -> rangesOf(cu).stream()
                        .filter(r -> startLine >= r.startLine() && endLine <= r.endLine())
                        .mapToInt(r -> r.endLine() - r.startLine())
                        .min()
                        .orElse(Integer.MAX_VALUE)));
    }

    @Override
    public SequencedSet<CodeUnit> getDefinitions(String fqName) {
        var matches =
                allClasses.stream().filter(cu -> cu.fqName().equals(fqName)).collect(Collectors.toSet());
        return sortDefinitions(matches);
    }

    @Override
    public Set<CodeUnit> getDeclarations(ProjectFile file) {
        return allClasses.stream().filter(cu -> cu.source().equals(file)).collect(Collectors.toSet());
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        return Map.of(); // Return empty map for test purposes
    }

    @Override
    public Optional<String> getSource(CodeUnit codeUnit, boolean includeComments) {
        return Optional.ofNullable(sources.get(codeUnit));
    }

    @Override
    public Set<String> getSources(CodeUnit codeUnit, boolean includeComments) {
        String source = sources.get(codeUnit);
        return source != null ? Set.of(source) : Set.of();
    }

    @Override
    public Optional<String> getSkeleton(CodeUnit cu) {
        return Optional.ofNullable(skeletons.get(cu));
    }

    @Override
    public Optional<String> getSkeletonHeader(CodeUnit classUnit) {
        return Optional.empty();
    }

    @Override
    public List<String> getDisplaySignatures(CodeUnit codeUnit) {
        return List.copyOf(displaySignatures.getOrDefault(codeUnit, IAnalyzer.super.getDisplaySignatures(codeUnit)));
    }

    public void setDisplaySignatures(CodeUnit cu, List<String> signatures) {
        this.displaySignatures.put(cu, List.copyOf(signatures));
    }

    public void setSkeleton(CodeUnit cu, String skeleton) {
        this.skeletons.put(cu, skeleton);
    }

    public void setRanges(CodeUnit cu, List<Range> ranges) {
        this.rangesByCodeUnit.put(cu, List.copyOf(ranges));
    }

    public void setSource(CodeUnit cu, String source) {
        this.sources.put(cu, source);
    }

    @Override
    public List<CodeUnit> getDirectAncestors(CodeUnit cu) {
        return ancestorsMap.getOrDefault(cu, List.of());
    }

    @Override
    public Set<CodeUnit> getDirectDescendants(CodeUnit cu) {
        return Set.of();
    }

    @Override
    public Set<CodeUnit> importedCodeUnitsOf(ProjectFile file) {
        return Set.of();
    }

    @Override
    public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
        return Set.of();
    }

    @Override
    public List<ImportInfo> importInfoOf(ProjectFile file) {
        return List.copyOf(importInfoByFile.getOrDefault(file, List.of()));
    }

    public void setImportInfo(ProjectFile file, List<ImportInfo> infos) {
        importInfoByFile.put(file, List.copyOf(infos));
    }

    @Override
    public Set<String> relevantImportsFor(CodeUnit cu) {
        return Collections.unmodifiableSet(relevantImportsByCodeUnit.getOrDefault(cu, Set.of()));
    }

    public void setRelevantImports(CodeUnit cu, Set<String> imports) {
        relevantImportsByCodeUnit.put(cu, new LinkedHashSet<>(imports));
    }

    public void setDirectAncestors(CodeUnit cu, List<CodeUnit> ancestors) {
        this.ancestorsMap.put(cu, ancestors);
    }

    @Override
    public List<CodeUnit> getDirectChildren(CodeUnit cu) {
        return List.of();
    }

    @Override
    public int computeCyclomaticComplexity(CodeUnit cu) {
        return complexityMap.getOrDefault(cu, IAnalyzer.super.computeCyclomaticComplexity(cu));
    }

    public void setComplexity(CodeUnit cu, int complexity) {
        this.complexityMap.put(cu, complexity);
    }

    @Override
    public int computeCognitiveComplexity(CodeUnit cu) {
        return cognitiveComplexityMap.getOrDefault(cu, IAnalyzer.super.computeCognitiveComplexity(cu));
    }

    public void setCognitiveComplexity(CodeUnit cu, int complexity) {
        this.cognitiveComplexityMap.put(cu, complexity);
    }

    @Override
    public Optional<String> extractCallReceiver(String reference) {
        return Optional.empty();
    }

    @Override
    public boolean containsTests(ProjectFile file) {
        if (!supportsTestDetection) {
            throw new UnsupportedOperationException("Test detection capability is disabled in this TestAnalyzer");
        }
        return fileTestMarkers.getOrDefault(file, false);
    }

    public void setContainsTests(ProjectFile file, boolean containsTests) {
        fileTestMarkers.put(file, containsTests);
    }

    @Override
    public boolean isTypeAlias(CodeUnit cu) {
        return false;
    }

    public void setSupportsImportAnalysis(boolean supportsImportAnalysis) {
        this.supportsImportAnalysis = supportsImportAnalysis;
    }

    public void setSupportsTypeHierarchy(boolean supportsTypeHierarchy) {
        this.supportsTypeHierarchy = supportsTypeHierarchy;
    }

    public void setSupportsTypeAlias(boolean supportsTypeAlias) {
        this.supportsTypeAlias = supportsTypeAlias;
    }

    public void setSupportsTestDetection(boolean supportsTestDetection) {
        this.supportsTestDetection = supportsTestDetection;
    }

    @Override
    public <T extends CapabilityProvider> Optional<T> as(Class<T> capability) {
        if (capability == ImportAnalysisProvider.class && !supportsImportAnalysis) {
            return Optional.empty();
        }
        if (capability == TypeHierarchyProvider.class && !supportsTypeHierarchy) {
            return Optional.empty();
        }
        if (capability == TypeAliasProvider.class && !supportsTypeAlias) {
            return Optional.empty();
        }
        if (capability == TestDetectionProvider.class && !supportsTestDetection) {
            return Optional.empty();
        }
        return IAnalyzer.super.as(capability);
    }
}
