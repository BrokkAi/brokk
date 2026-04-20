package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.ICoreProject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
}
