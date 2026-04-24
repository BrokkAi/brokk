package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FileTargetHeuristicTest {

    @Test
    void looksLikeFileTarget_detectsLikelyFileInputs() {
        assertTrue(FileTargetHeuristic.looksLikeFileTarget("Missing.java"));
        assertTrue(FileTargetHeuristic.looksLikeFileTarget("src/main/java/A.java"));
        assertTrue(FileTargetHeuristic.looksLikeFileTarget("README.md"));
        assertTrue(FileTargetHeuristic.looksLikeFileTarget("*.py"));
    }

    @Test
    void looksLikeFileTarget_leavesClassLikeInputsAlone() {
        assertFalse(FileTargetHeuristic.looksLikeFileTarget("A"));
        assertFalse(FileTargetHeuristic.looksLikeFileTarget("ai.brokk.tools.SearchTools"));
        assertFalse(FileTargetHeuristic.looksLikeFileTarget("A."));
    }
}
