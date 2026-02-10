package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.TestAnalyzer;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class MultiAnalyzerCapabilityTest {

    @Test
    void as_ReturnsMultiAnalyzer_ForImportAnalysis_WhenDelegateSupportsIt() {
        // Create a delegate that supports ImportAnalysisProvider
        TestAnalyzer delegate = new TestAnalyzer();
        // TestAnalyzer implements ImportAnalysisProvider and TypeHierarchyProvider

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
        TestAnalyzer delegate = new TestAnalyzer();
        MultiAnalyzer multi = new MultiAnalyzer(Map.of(Languages.PYTHON, delegate));

        Optional<TypeHierarchyProvider> provider = multi.as(TypeHierarchyProvider.class);
        assertTrue(
                provider.isPresent(),
                "MultiAnalyzer should return a provider for TypeHierarchy when delegate supports it");
        assertTrue(provider.get() == multi, "MultiAnalyzer should return itself as the provider");
    }

    @Test
    void as_ReturnsMultiAnalyzer_ForTypeAlias_WhenDelegateSupportsIt() {
        TestAnalyzer delegate = new TestAnalyzer();
        MultiAnalyzer multi = new MultiAnalyzer(Map.of(Languages.PYTHON, delegate));

        Optional<TypeAliasProvider> provider = multi.as(TypeAliasProvider.class);
        assertTrue(
                provider.isPresent(), "MultiAnalyzer should return a provider for TypeAlias when delegate supports it");
        assertTrue(provider.get() == multi, "MultiAnalyzer should return itself as the provider");
    }
}
