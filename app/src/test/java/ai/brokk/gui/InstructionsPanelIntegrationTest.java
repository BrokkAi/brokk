package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.Service;
import ai.brokk.gui.components.ModelBenchmarkData;
import ai.brokk.gui.components.TokenUsageBar;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for warning level computation logic in InstructionsPanel.updateTokenCostIndicator().
 * Verifies that warning levels are correctly determined based on whether tokens are within model limit
 * and whether benchmark data is available.
 */
class InstructionsPanelIntegrationTest {

    /**
     * Computes the warning level based on model limit and benchmark data.
     * This replicates the logic from InstructionsPanel.updateTokenCostIndicator().
     */
    private TokenUsageBar.WarningLevel computeWarningLevel(
            boolean withinModelLimit, boolean hasBenchmarkData, int successRate) {
        if (!withinModelLimit) {
            // Exceeds model's supported input tokens — warn RED
            return TokenUsageBar.WarningLevel.RED;
        } else if (!hasBenchmarkData) {
            // No benchmark data at this token count; do not warn
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
    void exceedsModelLimit_setsRed() {
        // Setup: tokens exceed model limit (regardless of benchmark data)
        boolean withinModelLimit = false;
        boolean hasBenchmarkData = true; // doesn't matter
        int successRate = 93; // doesn't matter

        var warningLevel = computeWarningLevel(withinModelLimit, hasBenchmarkData, successRate);

        // Assert: RED warning when exceeding model limit
        assertEquals(TokenUsageBar.WarningLevel.RED, warningLevel, "Exceeding model limit should produce RED warning");
    }

    @Test
    void withinLimit_noBenchmarkData_setsNone() {
        // Setup: within model limit but no benchmark data
        boolean withinModelLimit = true;
        boolean hasBenchmarkData = false;
        int successRate = -1; // unknown

        var warningLevel = computeWarningLevel(withinModelLimit, hasBenchmarkData, successRate);

        // Assert: NONE warning when no benchmark data (we don't know if it's good or bad)
        assertEquals(TokenUsageBar.WarningLevel.NONE, warningLevel, "No benchmark data should produce NONE warning");
    }

    @Test
    void tested_highSuccess93_setsNone() {
        // Setup: gpt-5 DEFAULT @20k tokens has 93% success rate
        int approxTokens = 20_000;
        int maxTokens = 200_000; // well within limit
        var rateResult =
                ModelBenchmarkData.getSuccessRateWithTesting("gpt-5", Service.ReasoningLevel.DEFAULT, approxTokens);

        // Verify: has benchmark data
        assertTrue(rateResult.hasBenchmarkData(), "Token count 20,000 should have benchmark data");
        assertEquals(93, rateResult.successRate(), "gpt-5 DEFAULT @20k should be 93% success rate");

        // Compute warning level
        var warningLevel =
                computeWarningLevel(approxTokens <= maxTokens, rateResult.hasBenchmarkData(), rateResult.successRate());

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
        int maxTokens = 200_000; // well within limit
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(
                "gpt-5-mini", Service.ReasoningLevel.DEFAULT, approxTokens);

        // Verify: has benchmark data
        assertTrue(rateResult.hasBenchmarkData(), "Token count 70,000 should have benchmark data");
        assertEquals(34, rateResult.successRate(), "gpt-5-mini DEFAULT @70k should be 34% success rate");

        // Compute warning level
        var warningLevel =
                computeWarningLevel(approxTokens <= maxTokens, rateResult.hasBenchmarkData(), rateResult.successRate());

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
        int maxTokens = 200_000; // well within limit
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(
                "gemini-2.5-flash", Service.ReasoningLevel.DEFAULT, approxTokens);

        // Verify: has benchmark data
        assertTrue(rateResult.hasBenchmarkData(), "Token count 70,000 should have benchmark data");
        assertEquals(17, rateResult.successRate(), "gemini-2.5-flash DEFAULT @70k should be 17% success rate");

        // Compute warning level
        var warningLevel =
                computeWarningLevel(approxTokens <= maxTokens, rateResult.hasBenchmarkData(), rateResult.successRate());

        // Assert: RED warning (success rate < 30%)
        assertEquals(
                TokenUsageBar.WarningLevel.RED,
                warningLevel,
                "17% success rate should produce RED warning (rate < 30%)");
    }

    @Test
    void boundary_131071_withSuccess50_setsNone() {
        // Setup: gpt-5 DEFAULT @131071 tokens
        // gpt-5 DEFAULT @131071 falls in 65K-131K range: 50% success rate is at threshold for NONE
        int approxTokens = 131_071;
        int maxTokens = 200_000; // well within limit
        var rateResult =
                ModelBenchmarkData.getSuccessRateWithTesting("gpt-5", Service.ReasoningLevel.DEFAULT, approxTokens);

        // Verify: has benchmark data
        assertTrue(rateResult.hasBenchmarkData(), "Token count 131,071 should have benchmark data");
        assertEquals(50, rateResult.successRate(), "gpt-5 DEFAULT @131071 should be 50% success rate");

        // Compute warning level
        var warningLevel =
                computeWarningLevel(approxTokens <= maxTokens, rateResult.hasBenchmarkData(), rateResult.successRate());

        // Assert: NONE warning (success rate >= 50% threshold)
        assertEquals(
                TokenUsageBar.WarningLevel.NONE,
                warningLevel,
                "At 131,071 tokens, 50% success should produce NONE warning (>= 50% threshold)");
    }

    @Test
    void extrapolated_200k_setsRed() {
        // Setup: 200,000 tokens exceeds model limit (e.g., 128k model)
        int approxTokens = 200_000;
        int maxTokens = 128_000;
        var rateResult =
                ModelBenchmarkData.getSuccessRateWithTesting("gpt-5", Service.ReasoningLevel.DEFAULT, approxTokens);

        // Even if we have benchmark data, exceeding model limit is RED
        boolean withinModelLimit = approxTokens <= maxTokens;

        // Compute warning level
        var warningLevel =
                computeWarningLevel(withinModelLimit, rateResult.hasBenchmarkData(), rateResult.successRate());

        // Assert: RED warning for exceeding model limit
        assertEquals(
                TokenUsageBar.WarningLevel.RED,
                warningLevel,
                "Token count 200,000 exceeding 128k model limit should produce RED warning");
    }

    @Test
    void testCerebrasAbove131kWithinModelLimit_notRedAndTooltipTested() {
        int approxTokens = 200_000; // above 131,071 but within many large-context models

        // Simulate within-limit case for Cerebras (models typically support large contexts)
        boolean withinModelLimit = true;
        int successRate = -1; // unknown for this model/token combo
        boolean hasBenchmarkData = false; // no data for this combo

        var warningLevel = computeWarningLevel(withinModelLimit, hasBenchmarkData, successRate);
        // Within model limit with no benchmark data should be NONE
        assertEquals(TokenUsageBar.WarningLevel.NONE, warningLevel, "Within model limit with no data should be NONE");

        // Build a config for a Cerebras model and compute tooltip
        var config = new Service.ModelConfig("cerebras/qwen3-coder", Service.ReasoningLevel.DEFAULT);
        String tooltip = TokenUsageBar.computeWarningTooltip(
                withinModelLimit, config, warningLevel, successRate, approxTokens, "<html>context</html>");

        assertNotNull(tooltip, "Tooltip should not be null");
        // When within limit, tooltip should not include 'exceeds' messaging
        assertFalse(
                tooltip.toLowerCase().contains("exceeds"),
                "Tooltip should reflect within-limit status (no 'exceeds').");
        // Tooltip should include some reasonable content (the base passed content)
        assertTrue(tooltip.toLowerCase().contains("context"), "Tooltip should include base context content.");
    }

    @Test
    void testCerebrasModelWithProviderPrefix_findsBenchmarkData() {
        // Verify that models with provider prefixes (e.g., "cerebras/qwen3-coder")
        // correctly match benchmark data stored without prefixes (e.g., "qwen3-coder")
        var config = new Service.ModelConfig("cerebras/qwen3-coder", Service.ReasoningLevel.DEFAULT);
        int tokenCount = 50_000; // Within RANGE_32K_65K

        int successRate = ModelBenchmarkData.getSuccessRate(config, tokenCount);

        // qwen3-coder has benchmark data: (73, 57, 32) for ranges (16-32k, 32-65k, 65-131k)
        // At 50k tokens, should return the 32-65k range value: 57
        assertEquals(57, successRate, "Cerebras model with provider prefix should find benchmark data");
    }

    @Test
    void testCerebrasModelWithSizeSuffix_findsBenchmarkData() {
        // Verify that models with size suffixes (e.g., "cerebras/qwen-3-coder-480b")
        // correctly match benchmark data by normalizing to base model name ("qwen3-coder")
        var config = new Service.ModelConfig("cerebras/qwen-3-coder-480b", Service.ReasoningLevel.DEFAULT);
        int tokenCount = 56_862; // Within RANGE_32K_65K

        int successRate = ModelBenchmarkData.getSuccessRate(config, tokenCount);

        // qwen-3-coder-480b should normalize to qwen3-coder
        // qwen3-coder has benchmark data: (73, 57, 32) for ranges (16-32k, 32-65k, 65-131k)
        // At 56862 tokens, should return the 32-65k range value: 57
        assertEquals(57, successRate, "Cerebras model with size suffix should find benchmark data via normalization");
    }
}
