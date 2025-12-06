package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.gui.AgentsMdInferrer.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the numeric decision logic in AgentsMdInferrer.decideTierByCounts.
 * These tests do not require creating ContextManager, Chrome, or StreamingChatModel.
 */
public class AgentsMdInferrerTest {

    @Test
    @DisplayName("direct summarize when total within budget")
    void directSummarizeWhenTotalWithinBudget() {
        long totalTokens = 900;
        long summariesTokens = 800;
        int safetyBudget = 1000;

        Tier tier = AgentsMdInferrer.decideTierByCounts(totalTokens, summariesTokens, safetyBudget);

        assertEquals(Tier.DIRECT_SUMMARIZE, tier);
    }

    @Test
    @DisplayName("summarize then SearchAgent when only summaries fit")
    void summarizeThenSearchAgentWhenOnlySummariesFit() {
        long totalTokens = 5000;
        long summariesTokens = 900;
        int safetyBudget = 1000;

        Tier tier = AgentsMdInferrer.decideTierByCounts(totalTokens, summariesTokens, safetyBudget);

        assertEquals(Tier.SUMMARIZE_THEN_SEARCHAGENT, tier);
    }

    @Test
    @DisplayName("SearchAgent with file list when nothing fits")
    void searchAgentWithFileListWhenNothingFits() {
        long totalTokens = 8000;
        long summariesTokens = 3000;
        int safetyBudget = 1000;

        Tier tier = AgentsMdInferrer.decideTierByCounts(totalTokens, summariesTokens, safetyBudget);

        assertEquals(Tier.SEARCHAGENT_WITH_FILE_LIST, tier);
    }

    @Test
    @DisplayName("handles zero and negative values gracefully")
    void handlesZeroAndNegativeValuesGracefully() {
        // totalTokens == 0 should be treated as within budget
        assertEquals(
                Tier.DIRECT_SUMMARIZE,
                AgentsMdInferrer.decideTierByCounts(0L, 0L, 1000),
                "Zero total tokens should choose DIRECT_SUMMARIZE");

        // negative totalTokens should still be treated as <= budget
        assertEquals(
                Tier.DIRECT_SUMMARIZE,
                AgentsMdInferrer.decideTierByCounts(-1L, 0L, 1000),
                "Negative total tokens should choose DIRECT_SUMMARIZE");
    }
}
