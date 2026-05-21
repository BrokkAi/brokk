package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock(value = TreeSitterAnalyzer.ANALYZER_MAX_PARALLELISM_PROPERTY, mode = ResourceAccessMode.READ_WRITE)
final class TreeSitterAnalyzerTest {

    @Test
    void testConfiguredAnalyzerMaxParallelismDefaultsToConservativeCap() {
        String original = System.getProperty(TreeSitterAnalyzer.ANALYZER_MAX_PARALLELISM_PROPERTY);
        try {
            System.clearProperty(TreeSitterAnalyzer.ANALYZER_MAX_PARALLELISM_PROPERTY);
            assertEquals(4, TreeSitterAnalyzer.configuredAnalyzerMaxParallelism());
            assertEquals(4, TreeSitterAnalyzer.computeAnalyzerParallelism(16, 4, Integer.MAX_VALUE));
        } finally {
            restoreProperty(original);
        }
    }

    @Test
    void testConfiguredAnalyzerMaxParallelismHonorsOverride() {
        String original = System.getProperty(TreeSitterAnalyzer.ANALYZER_MAX_PARALLELISM_PROPERTY);
        try {
            System.setProperty(TreeSitterAnalyzer.ANALYZER_MAX_PARALLELISM_PROPERTY, "7");
            assertEquals(7, TreeSitterAnalyzer.configuredAnalyzerMaxParallelism());
            assertEquals(7, TreeSitterAnalyzer.computeAnalyzerParallelism(16, 7, Integer.MAX_VALUE));
        } finally {
            restoreProperty(original);
        }
    }

    @Test
    void testComputeAnalyzerParallelismClampsToWorkSize() {
        assertEquals(3, TreeSitterAnalyzer.computeAnalyzerParallelism(16, 8, 3));
        assertEquals(1, TreeSitterAnalyzer.computeAnalyzerParallelism(16, 8, 0));
    }

    private static void restoreProperty(String original) {
        if (original == null) {
            System.clearProperty(TreeSitterAnalyzer.ANALYZER_MAX_PARALLELISM_PROPERTY);
        } else {
            System.setProperty(TreeSitterAnalyzer.ANALYZER_MAX_PARALLELISM_PROPERTY, original);
        }
    }
}
