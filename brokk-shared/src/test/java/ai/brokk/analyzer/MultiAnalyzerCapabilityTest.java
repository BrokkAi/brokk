package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.ICoreProject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MultiAnalyzerCapabilityTest {

    @Test
    void as_ReturnsMultiAnalyzer_ForImportAnalysis_WhenDelegateSupportsIt() {
        IAnalyzer delegate = new SupportsImportAnalysis();

        MultiAnalyzer multi = new MultiAnalyzer(Map.of(Languages.JAVA, delegate));

        Optional<ImportAnalysisProvider> provider = multi.as(ImportAnalysisProvider.class);
        assertTrue(provider.isPresent(), "MultiAnalyzer should return a provider when a delegate supports it");
        assertTrue(provider.get() == multi, "MultiAnalyzer should return itself as the provider");
    }

    @Test
    void as_ReturnsEmpty_WhenNoDelegateSupportsCapability() {
        // MultiAnalyzer with no delegates
        MultiAnalyzer multi = new MultiAnalyzer(Map.of());

        Optional<ImportAnalysisProvider> provider = multi.as(ImportAnalysisProvider.class);
        assertTrue(provider.isEmpty(), "MultiAnalyzer should return empty when no delegates support the capability");
    }

    @Test
    void as_ReturnsMultiAnalyzer_ForTypeHierarchy_WhenDelegateSupportsIt() {
        IAnalyzer delegate = new SupportsTypeHierarchy();
        MultiAnalyzer multi = new MultiAnalyzer(Map.of(Languages.PYTHON, delegate));

        Optional<TypeHierarchyProvider> provider = multi.as(TypeHierarchyProvider.class);
        assertTrue(
                provider.isPresent(),
                "MultiAnalyzer should return a provider for TypeHierarchy when delegate supports it");
        assertTrue(provider.get() == multi, "MultiAnalyzer should return itself as the provider");
    }

    @Test
    void as_ReturnsMultiAnalyzer_ForTypeAlias_WhenDelegateSupportsIt() {
        IAnalyzer delegate = new SupportsTypeAlias();
        MultiAnalyzer multi = new MultiAnalyzer(Map.of(Languages.PYTHON, delegate));

        Optional<TypeAliasProvider> provider = multi.as(TypeAliasProvider.class);
        assertTrue(
                provider.isPresent(), "MultiAnalyzer should return a provider for TypeAlias when delegate supports it");
        assertTrue(provider.get() == multi, "MultiAnalyzer should return itself as the provider");
    }

    @Test
    void findExceptionHandlingSmells_DelegatesToFileLanguageAnalyzer(@TempDir Path root) {
        var javaFile = new ProjectFile(root.toAbsolutePath().normalize(), "src/App.java");
        var pythonFile = new ProjectFile(root.toAbsolutePath().normalize(), "src/app.py");
        var javaFinding = new IAnalyzer.ExceptionHandlingSmell(
                javaFile, "App.run", "Exception", 5, 0, List.of("empty catch body"), "catch (Exception e) {}");
        var pythonFinding = new IAnalyzer.ExceptionHandlingSmell(
                pythonFile, "app.run", "Exception", 5, 0, List.of("empty except body"), "except Exception:");
        var javaAnalyzer = new QualitySmellAnalyzer(List.of(javaFinding), List.of());
        var pythonAnalyzer = new QualitySmellAnalyzer(List.of(pythonFinding), List.of());
        var multi = new MultiAnalyzer(Map.of(Languages.JAVA, javaAnalyzer, Languages.PYTHON, pythonAnalyzer));

        var findings = multi.findExceptionHandlingSmells(javaFile, IAnalyzer.ExceptionSmellWeights.defaults());

        assertEquals(List.of(javaFinding), findings);
        assertEquals(List.of(javaFile), javaAnalyzer.exceptionFiles);
        assertTrue(pythonAnalyzer.exceptionFiles.isEmpty());
    }

    @Test
    void findStructuralCloneSmells_DelegatesSingleFileToFileLanguageAnalyzer(@TempDir Path root) {
        var javaFile = new ProjectFile(root.toAbsolutePath().normalize(), "src/App.java");
        var peerFile = new ProjectFile(root.toAbsolutePath().normalize(), "src/Peer.java");
        var pythonFile = new ProjectFile(root.toAbsolutePath().normalize(), "src/app.py");
        var javaFinding = new IAnalyzer.CloneSmell(
                javaFile, "App.run", peerFile, "Peer.run", 90, 20, List.of("shared structure"), "run();", "run();");
        var pythonFinding = new IAnalyzer.CloneSmell(
                pythonFile, "app.run", pythonFile, "app.other", 90, 20, List.of("shared structure"), "run()", "run()");
        var javaAnalyzer = new QualitySmellAnalyzer(List.of(), List.of(javaFinding));
        var pythonAnalyzer = new QualitySmellAnalyzer(List.of(), List.of(pythonFinding));
        var multi = new MultiAnalyzer(Map.of(Languages.JAVA, javaAnalyzer, Languages.PYTHON, pythonAnalyzer));

        var findings = multi.findStructuralCloneSmells(javaFile, IAnalyzer.CloneSmellWeights.defaults());

        assertEquals(List.of(javaFinding), findings);
        assertEquals(List.of(List.of(javaFile)), javaAnalyzer.cloneFileGroups);
        assertTrue(pythonAnalyzer.cloneFileGroups.isEmpty());
    }

    @Test
    void findStructuralCloneSmells_DelegatesFileGroupsByLanguage(@TempDir Path root) {
        var javaFile = new ProjectFile(root.toAbsolutePath().normalize(), "src/App.java");
        var javaPeerFile = new ProjectFile(root.toAbsolutePath().normalize(), "src/Peer.java");
        var pythonFile = new ProjectFile(root.toAbsolutePath().normalize(), "src/app.py");
        var pythonPeerFile = new ProjectFile(root.toAbsolutePath().normalize(), "src/peer.py");
        var javaFinding = new IAnalyzer.CloneSmell(
                javaFile, "App.run", javaPeerFile, "Peer.run", 90, 20, List.of("shared structure"), "run();", "run();");
        var pythonFinding = new IAnalyzer.CloneSmell(
                pythonFile,
                "app.run",
                pythonPeerFile,
                "peer.run",
                88,
                18,
                List.of("shared structure"),
                "run()",
                "run()");
        var javaAnalyzer = new QualitySmellAnalyzer(List.of(), List.of(javaFinding));
        var pythonAnalyzer = new QualitySmellAnalyzer(List.of(), List.of(pythonFinding));
        var multi = new MultiAnalyzer(Map.of(Languages.JAVA, javaAnalyzer, Languages.PYTHON, pythonAnalyzer));

        var findings = multi.findStructuralCloneSmells(
                List.of(javaFile, pythonFile, javaPeerFile, pythonPeerFile), IAnalyzer.CloneSmellWeights.defaults());

        assertEquals(List.of(javaFinding, pythonFinding), findings);
        assertEquals(List.of(List.of(javaFile, javaPeerFile)), javaAnalyzer.cloneFileGroups);
        assertEquals(List.of(List.of(pythonFile, pythonPeerFile)), pythonAnalyzer.cloneFileGroups);
    }

    @Test
    void commentDensityByFqName_SkipsUnsupportedDefinitions(@TempDir Path root) {
        var pythonFile = new ProjectFile(root.toAbsolutePath().normalize(), "src/a.py");
        var jsFile = new ProjectFile(root.toAbsolutePath().normalize(), "src/z.js");
        var pythonUnit = CodeUnit.fn(pythonFile, "", "shared.symbol");
        var jsUnit = CodeUnit.fn(jsFile, "", "shared.symbol");
        var jsStats = new CommentDensityStats("shared.symbol", "src/z.js", 1, 2, 3, 1, 2, 3);
        var pythonAnalyzer = new CommentDensityAnalyzer(List.of(pythonUnit), Map.of());
        var jsAnalyzer = new CommentDensityAnalyzer(List.of(jsUnit), Map.of(jsUnit, jsStats));
        var multi = new MultiAnalyzer(Map.of(Languages.PYTHON, pythonAnalyzer, Languages.JAVASCRIPT, jsAnalyzer));

        Optional<CommentDensityStats> stats = multi.commentDensity("shared.symbol");

        assertEquals(Optional.of(jsStats), stats);
    }

    @Test
    void commentDensityByFqName_UsesDefinitionOrderingForDuplicates(@TempDir Path root) {
        var javaFile = new ProjectFile(root.toAbsolutePath().normalize(), "src/A.java");
        var jsFile = new ProjectFile(root.toAbsolutePath().normalize(), "src/B.js");
        var javaUnit = CodeUnit.fn(javaFile, "", "shared.symbol");
        var jsUnit = CodeUnit.fn(jsFile, "", "shared.symbol");
        var javaStats = new CommentDensityStats("shared.symbol", "src/A.java", 1, 0, 3, 1, 0, 3);
        var jsStats = new CommentDensityStats("shared.symbol", "src/B.js", 0, 1, 3, 0, 1, 3);
        var javaAnalyzer = new CommentDensityAnalyzer(List.of(javaUnit), Map.of(javaUnit, javaStats));
        var jsAnalyzer = new CommentDensityAnalyzer(List.of(jsUnit), Map.of(jsUnit, jsStats));
        var multi = new MultiAnalyzer(Map.of(Languages.JAVA, javaAnalyzer, Languages.JAVASCRIPT, jsAnalyzer));

        Optional<CommentDensityStats> stats = multi.commentDensity("shared.symbol");

        assertEquals(Optional.of(javaStats), stats);
    }

    private abstract static class BaseStubAnalyzer implements IAnalyzer {
        @Override
        public List<CodeUnit> getTopLevelDeclarations(ProjectFile file) {
            return List.of();
        }

        @Override
        public Set<Language> languages() {
            return Set.of();
        }

        @Override
        public IAnalyzer update(Set<ProjectFile> changedFiles) {
            return this;
        }

        @Override
        public IAnalyzer update() {
            return this;
        }

        @Override
        public ICoreProject getProject() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CodeUnit> getAllDeclarations() {
            return List.of();
        }

        @Override
        public Set<CodeUnit> getDeclarations(ProjectFile file) {
            return Set.of();
        }

        @Override
        public java.util.SequencedSet<CodeUnit> getDefinitions(String fqName) {
            return new java.util.LinkedHashSet<>();
        }

        @Override
        public List<CodeUnit> getDirectChildren(CodeUnit cu) {
            return List.of();
        }

        @Override
        public Optional<String> extractCallReceiver(String reference) {
            return Optional.empty();
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
        public Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, int startLine, int endLine) {
            return Optional.empty();
        }

        @Override
        public List<Range> rangesOf(CodeUnit codeUnit) {
            return List.of();
        }

        @Override
        public Optional<String> getSkeleton(CodeUnit cu) {
            return Optional.empty();
        }

        @Override
        public Optional<String> getSkeletonHeader(CodeUnit classUnit) {
            return Optional.empty();
        }

        @Override
        public Optional<String> getSource(CodeUnit codeUnit, boolean includeComments) {
            return Optional.empty();
        }

        @Override
        public Set<String> getSources(CodeUnit codeUnit, boolean includeComments) {
            return Set.of();
        }
    }

    private static final class SupportsImportAnalysis extends BaseStubAnalyzer implements ImportAnalysisProvider {
        @Override
        public Set<CodeUnit> importedCodeUnitsOf(ProjectFile file) {
            return Set.of();
        }

        @Override
        public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
            return Set.of();
        }
    }

    private static final class SupportsTypeHierarchy extends BaseStubAnalyzer implements TypeHierarchyProvider {
        @Override
        public List<CodeUnit> getDirectAncestors(CodeUnit cu) {
            return List.of();
        }

        @Override
        public Set<CodeUnit> getDirectDescendants(CodeUnit cu) {
            return Set.of();
        }
    }

    private static final class SupportsTypeAlias extends BaseStubAnalyzer implements TypeAliasProvider {
        @Override
        public boolean isTypeAlias(CodeUnit cu) {
            return false;
        }
    }

    private static final class QualitySmellAnalyzer extends BaseStubAnalyzer {
        final List<ProjectFile> exceptionFiles = new ArrayList<>();
        final List<List<ProjectFile>> cloneFileGroups = new ArrayList<>();

        private final List<IAnalyzer.ExceptionHandlingSmell> exceptionFindings;
        private final List<IAnalyzer.CloneSmell> cloneFindings;

        QualitySmellAnalyzer(
                List<IAnalyzer.ExceptionHandlingSmell> exceptionFindings, List<IAnalyzer.CloneSmell> cloneFindings) {
            this.exceptionFindings = exceptionFindings;
            this.cloneFindings = cloneFindings;
        }

        @Override
        public List<IAnalyzer.ExceptionHandlingSmell> findExceptionHandlingSmells(
                ProjectFile file, IAnalyzer.ExceptionSmellWeights weights) {
            exceptionFiles.add(file);
            return exceptionFindings.stream()
                    .filter(finding -> finding.file().equals(file))
                    .toList();
        }

        @Override
        public List<IAnalyzer.CloneSmell> findStructuralCloneSmells(
                ProjectFile file, IAnalyzer.CloneSmellWeights weights) {
            cloneFileGroups.add(List.of(file));
            return cloneFindings.stream()
                    .filter(finding ->
                            finding.file().equals(file) || finding.peerFile().equals(file))
                    .toList();
        }

        @Override
        public List<IAnalyzer.CloneSmell> findStructuralCloneSmells(
                List<ProjectFile> files, IAnalyzer.CloneSmellWeights weights) {
            cloneFileGroups.add(List.copyOf(files));
            return cloneFindings.stream()
                    .filter(finding -> files.contains(finding.file()) && files.contains(finding.peerFile()))
                    .toList();
        }
    }

    private static final class CommentDensityAnalyzer extends BaseStubAnalyzer {
        private final List<CodeUnit> definitions;
        private final Map<CodeUnit, CommentDensityStats> statsByUnit;

        CommentDensityAnalyzer(List<CodeUnit> definitions, Map<CodeUnit, CommentDensityStats> statsByUnit) {
            this.definitions = List.copyOf(definitions);
            this.statsByUnit = Map.copyOf(statsByUnit);
        }

        @Override
        public java.util.SequencedSet<CodeUnit> getDefinitions(String fqName) {
            return definitions.stream()
                    .filter(cu -> cu.fqName().equals(fqName))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        @Override
        public Optional<CommentDensityStats> commentDensity(CodeUnit cu) {
            return Optional.ofNullable(statsByUnit.get(cu));
        }
    }
}
