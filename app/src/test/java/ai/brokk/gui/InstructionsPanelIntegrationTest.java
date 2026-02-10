package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.Service;
import ai.brokk.gui.components.ModelBenchmarkData;
import ai.brokk.gui.components.TokenUsageBar;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for warning level computation logic in InstructionsPanel.updateTokenCostIndicator().
 * Verifies that warning levels are correctly determined based on token count and success rate.
 */
class InstructionsPanelIntegrationTest {

    /**
     * Computes the warning level based on token count and success rate.
     * This replicates the logic from InstructionsPanel.updateTokenCostIndicator().
     */
    private TokenUsageBar.WarningLevel computeWarningLevel(int approxTokens, int successRate, boolean isTested) {
        if (!isTested) {
            // Untested (extrapolated) token count — always warn RED
            return TokenUsageBar.WarningLevel.RED;
        } else if (successRate == -1) {
            // Unknown/untested combination: don't warn
            return TokenUsageBar.WarningLevel.NONE;
        } else if (successRate < 30) {
            return TokenUsageBar.WarningLevel.RED;
        } else if (successRate < 50) {
            return TokenUsageBar.WarningLevel.YELLOW;
        } else {
            return TokenUsageBar.WarningLevel.NONE;
        }
    }

    @Test
    void extrapolated_exceeding131k_setsRed() {
        // Setup: 131,072 tokens (beyond tested max of 131,071)
        int approxTokens = 131_072;
        var rateResult =
                ModelBenchmarkData.getSuccessRateWithTesting("gpt-5", Service.ReasoningLevel.DEFAULT, approxTokens);

        // Verify: isTested should be false (extrapolated)
        assertEquals(false, rateResult.isTested(), "Token count 131,072 should be marked as not tested");

        // Compute warning level
        var warningLevel = computeWarningLevel(approxTokens, rateResult.successRate(), rateResult.isTested());

        // Assert: RED warning for extrapolated data
        assertEquals(
                TokenUsageBar.WarningLevel.RED,
                warningLevel,
                "Token count 131,072 (beyond tested range) should produce RED warning");
    }

    @Test
    void tested_highSuccess93_setsNone() {
        // Setup: gpt-5 DEFAULT @20k tokens has 93% success rate
        int approxTokens = 20_000;
        var rateResult =
                ModelBenchmarkData.getSuccessRateWithTesting("gpt-5", Service.ReasoningLevel.DEFAULT, approxTokens);

        // Verify: within tested range
        assertEquals(true, rateResult.isTested(), "Token count 20,000 should be marked as tested");
        assertEquals(93, rateResult.successRate(), "gpt-5 DEFAULT @20k should be 93% success rate");

        // Compute warning level
        var warningLevel = computeWarningLevel(approxTokens, rateResult.successRate(), rateResult.isTested());

        // Assert: NONE warning (success rate >= 50%)
        assertEquals(
                TokenUsageBar.WarningLevel.NONE,
                warningLevel,
                "93% success rate should produce NONE warning (threshold >= 50%)");
    }

    @Test
    void tested_mediumSuccess34_setsYellow() {
        // Setup: gpt-5-mini DEFAULT @70k tokens has 34% success rate
        int approxTokens = 70_000;
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(
                "gpt-5-mini", Service.ReasoningLevel.DEFAULT, approxTokens);

        // Verify: within tested range
        assertEquals(true, rateResult.isTested(), "Token count 70,000 should be marked as tested");
        assertEquals(34, rateResult.successRate(), "gpt-5-mini DEFAULT @70k should be 34% success rate");

        // Compute warning level
        var warningLevel = computeWarningLevel(approxTokens, rateResult.successRate(), rateResult.isTested());

        // Assert: YELLOW warning (30% <= rate < 50%)
        assertEquals(
                TokenUsageBar.WarningLevel.YELLOW,
                warningLevel,
                "34% success rate should produce YELLOW warning (30% <= rate < 50%)");
    }

    @Test
    void tested_lowSuccess17_setsRed() {
        // Setup: gemini-2.5-flash DEFAULT @70k tokens has 17% success rate
        int approxTokens = 70_000;
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(
                "gemini-2.5-flash", Service.ReasoningLevel.DEFAULT, approxTokens);

        // Verify: within tested range
        assertEquals(true, rateResult.isTested(), "Token count 70,000 should be marked as tested");
        assertEquals(17, rateResult.successRate(), "gemini-2.5-flash DEFAULT @70k should be 17% success rate");

        // Compute warning level
        var warningLevel = computeWarningLevel(approxTokens, rateResult.successRate(), rateResult.isTested());

        // Assert: RED warning (success rate < 30%)
        assertEquals(
                TokenUsageBar.WarningLevel.RED,
                warningLevel,
                "17% success rate should produce RED warning (rate < 30%)");
    }

    @Test
    void boundary_131071_withSuccess50_setsNone() {
        // Setup: gpt-5 DEFAULT @131071 tokens (exact boundary, still tested)
        // gpt-5 DEFAULT @131071 falls in 65K-131K range: 50% success rate is at threshold for NONE
        int approxTokens = 131_071;
        var rateResult =
                ModelBenchmarkData.getSuccessRateWithTesting("gpt-5", Service.ReasoningLevel.DEFAULT, approxTokens);

        // Verify: at boundary, should be tested
        assertEquals(true, rateResult.isTested(), "Token count 131,071 should be marked as tested (at boundary)");
        assertEquals(50, rateResult.successRate(), "gpt-5 DEFAULT @131071 should be 50% success rate");

        // Compute warning level
        var warningLevel = computeWarningLevel(approxTokens, rateResult.successRate(), rateResult.isTested());

        // Assert: NONE warning (success rate >= 50% threshold)
        assertEquals(
                TokenUsageBar.WarningLevel.NONE,
                warningLevel,
                "At boundary 131,071 tokens (tested), 50% success should produce NONE warning (>= 50% threshold)");
    }

    @Test
    void extrapolated_200k_setsRed() {
        // Setup: 200,000 tokens (well beyond tested range)
        int approxTokens = 200_000;
        var rateResult =
                ModelBenchmarkData.getSuccessRateWithTesting("gpt-5", Service.ReasoningLevel.DEFAULT, approxTokens);

        // Verify: isTested should be false (extrapolated)
        assertEquals(false, rateResult.isTested(), "Token count 200,000 should be marked as not tested");

        // Compute warning level
        var warningLevel = computeWarningLevel(approxTokens, rateResult.successRate(), rateResult.isTested());

        // Assert: RED warning for extrapolated data
        assertEquals(
                TokenUsageBar.WarningLevel.RED,
                warningLevel,
                "Token count 200,000 (beyond tested range) should produce RED warning");
    }
}
