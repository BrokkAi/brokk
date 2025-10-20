package io.github.jbellis.brokk.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jbellis.brokk.Service;
import org.junit.jupiter.api.Test;

class ModelBenchmarkDataTest {

    @Test
    void unknownModel_returns100() {
        int result = ModelBenchmarkData.getSuccessRate("some-unknown-model", Service.ReasoningLevel.DEFAULT, 50000);
        assertEquals(100, result, "Unknown model should return 100% success rate");
    }

    @Test
    void gemini25pro_default_ranges() {
        int r1 = ModelBenchmarkData.getSuccessRate("gemini-2.5-pro", Service.ReasoningLevel.DEFAULT, 50000);
        assertEquals(93, r1, "gemini-2.5-pro DEFAULT @50k should be 93%");

        int r2 = ModelBenchmarkData.getSuccessRate("gemini-2.5-pro", Service.ReasoningLevel.DEFAULT, 70000);
        assertEquals(33, r2, "gemini-2.5-pro DEFAULT @70k should be 33%");
    }

    @Test
    void gemini25pro_disable_ranges() {
        int r1 = ModelBenchmarkData.getSuccessRate("gemini-2.5-pro", Service.ReasoningLevel.DISABLE, 50000);
        assertEquals(81, r1, "gemini-2.5-pro DISABLE @50k should be 81%");

        int r2 = ModelBenchmarkData.getSuccessRate("gemini-2.5-pro", Service.ReasoningLevel.DISABLE, 70000);
        assertEquals(25, r2, "gemini-2.5-pro DISABLE @70k should be 25%");
    }

    @Test
    void gpt5_default_and_disable() {
        int r1 = ModelBenchmarkData.getSuccessRate("gpt-5", Service.ReasoningLevel.DEFAULT, 20000);
        assertEquals(100, r1, "gpt-5 DEFAULT @20k should be 100%");

        int r2 = ModelBenchmarkData.getSuccessRate("gpt-5", Service.ReasoningLevel.DEFAULT, 50000);
        assertEquals(84, r2, "gpt-5 DEFAULT @50k should be 84%");

        int r3 = ModelBenchmarkData.getSuccessRate("gpt-5", Service.ReasoningLevel.DISABLE, 50000);
        assertEquals(68, r3, "gpt-5 DISABLE @50k should be 68%");

        int r4 = ModelBenchmarkData.getSuccessRate("gpt-5", Service.ReasoningLevel.DISABLE, 120000);
        assertEquals(0, r4, "gpt-5 DISABLE @120k should be 0%");
    }

    @Test
    void claude_default_normalizes_to_medium() {
        int result = ModelBenchmarkData.getSuccessRate("claude-4-opus", Service.ReasoningLevel.DEFAULT, 20000);
        assertEquals(100, result, "claude-4-opus DEFAULT @20k should normalize to MEDIUM and return 100%");
    }

    @Test
    void nothink_suffix_sets_disable_via_config_overload() {
        var config = new Service.ModelConfig("gpt-5-nothink", Service.ReasoningLevel.DEFAULT);
        int result = ModelBenchmarkData.getSuccessRate(config, 50000);
        assertEquals(68, result, "gpt-5-nothink should strip suffix, use DISABLE reasoning, and return 68% @50k");
    }

    @Test
    void out_of_range_tokenCount_returns100() {
        int r1 = ModelBenchmarkData.getSuccessRate("gpt-5", Service.ReasoningLevel.DEFAULT, 1_000_000_000);
        assertEquals(100, r1, "Token count outside defined ranges should return 100%");

        int r2 = ModelBenchmarkData.getSuccessRate("gemini-2.5-pro", Service.ReasoningLevel.DEFAULT, 100);
        assertEquals(100, r2, "Token count below 4k should return 100%");
    }

    @Test
    void boundary_inclusive_checks() {
        int result = ModelBenchmarkData.getSuccessRate("gpt-5-mini", Service.ReasoningLevel.DEFAULT, 4096);
        assertEquals(100, result, "gpt-5-mini DEFAULT @4096 (lower bound of RANGE_4K_8K) should be 100%");
    }
}
