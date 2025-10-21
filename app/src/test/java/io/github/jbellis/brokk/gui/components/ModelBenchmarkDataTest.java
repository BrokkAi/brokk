package io.github.jbellis.brokk.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jbellis.brokk.Service;
import org.junit.jupiter.api.Test;

class ModelBenchmarkDataTest {

    @Test
    void unknownModel_returnsUnknown() {
        int result = ModelBenchmarkData.getSuccessRate("some-unknown-model", Service.ReasoningLevel.DEFAULT, 50000);
        assertEquals(-1, result, "Unknown model should return -1 (unknown)");
    }

    @Test
    void gemini25pro_default_ranges() {
        int r1 = ModelBenchmarkData.getSuccessRate("gemini-2.5-pro", Service.ReasoningLevel.DEFAULT, 50000);
        assertEquals(94, r1, "gemini-2.5-pro DEFAULT @50k should be 94%");

        int r2 = ModelBenchmarkData.getSuccessRate("gemini-2.5-pro", Service.ReasoningLevel.DEFAULT, 70000);
        assertEquals(50, r2, "gemini-2.5-pro DEFAULT @70k should be 50%");
    }

    @Test
    void gemini25flash_disable_ranges() {
        int r1 = ModelBenchmarkData.getSuccessRate("gemini-2.5-flash", Service.ReasoningLevel.DISABLE, 50000);
        assertEquals(61, r1, "gemini-2.5-flash DISABLE @50k should be 61%");

        int r2 = ModelBenchmarkData.getSuccessRate("gemini-2.5-flash", Service.ReasoningLevel.DISABLE, 70000);
        assertEquals(21, r2, "gemini-2.5-flash DISABLE @70k should be 21%");
    }

    @Test
    void gpt5_default_and_high() {
        int r1 = ModelBenchmarkData.getSuccessRate("gpt-5", Service.ReasoningLevel.DEFAULT, 20000);
        assertEquals(93, r1, "gpt-5 DEFAULT @20k should be 93%");

        int r2 = ModelBenchmarkData.getSuccessRate("gpt-5", Service.ReasoningLevel.DEFAULT, 50000);
        assertEquals(71, r2, "gpt-5 DEFAULT @50k should be 71%");

        int r3 = ModelBenchmarkData.getSuccessRate("gpt-5", Service.ReasoningLevel.HIGH, 50000);
        assertEquals(77, r3, "gpt-5 HIGH @50k should be 77%");

        int r4 = ModelBenchmarkData.getSuccessRate("gpt-5", Service.ReasoningLevel.HIGH, 70000);
        assertEquals(48, r4, "gpt-5 HIGH @70k should be 48%");
    }

    @Test
    void claude_default_normalizes_to_medium() {
        int result = ModelBenchmarkData.getSuccessRate("claude-4-1-opus", Service.ReasoningLevel.DEFAULT, 20000);
        assertEquals(85, result, "claude-4-1-opus DEFAULT @20k should normalize to MEDIUM and return 85%");
    }

    @Test
    void nothink_suffix_sets_disable_via_config_overload() {
        var config = new Service.ModelConfig("claude-4-1-opus-nothink", Service.ReasoningLevel.DEFAULT);
        int result = ModelBenchmarkData.getSuccessRate(config, 50000);
        assertEquals(
                68, result, "claude-4-1-opus-nothink should strip suffix, use DISABLE reasoning, and return 68% @50k");
    }

    @Test
    void out_of_range_tokenCount_usesExtrapolation() {
        int r1 = ModelBenchmarkData.getSuccessRate("gpt-5", Service.ReasoningLevel.DEFAULT, 1_000_000_000);
        assertEquals(50, r1, "Token count above max range should use highest range data");

        int r2 = ModelBenchmarkData.getSuccessRate("gemini-2.5-pro", Service.ReasoningLevel.DEFAULT, 100);
        assertEquals(93, r2, "Token count below 32k should use 16K-32K data");
    }

    @Test
    void boundary_inclusive_checks() {
        int result = ModelBenchmarkData.getSuccessRate("glm-4.6", Service.ReasoningLevel.DEFAULT, 100);
        assertEquals(63, result, "glm-4.6 DEFAULT @100 (16K-32K range) should be 63%");
    }
}
