package ai.brokk.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.Service;
import org.junit.jupiter.api.Test;

class ModelBenchmarkDataWithTestingTest {

    private static final String TEST_MODEL = "gpt-5";
    private static final Service.ReasoningLevel TEST_REASONING = Service.ReasoningLevel.DEFAULT;

    @Test
    void hasBenchmarkData_true_for_boundary_0_tokens() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 0);
        // 0 tokens is in a benchmarked range, so hasBenchmarkData should be true
        assertTrue(rateResult.hasBenchmarkData(), "Token count 0 has benchmark data");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(TEST_MODEL, TEST_REASONING, 0),
                rateResult.successRate(),
                "Success rate should match getSuccessRate()");
    }

    @Test
    void hasBenchmarkData_true_for_boundary_4096_tokens() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 4096);
        assertTrue(rateResult.hasBenchmarkData(), "Token count 4096 has benchmark data");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(TEST_MODEL, TEST_REASONING, 4096),
                rateResult.successRate(),
                "Success rate should match getSuccessRate()");
    }

    @Test
    void hasBenchmarkData_true_for_boundary_131071_tokens() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 131071);
        assertTrue(rateResult.hasBenchmarkData(), "Token count 131071 has benchmark data");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(TEST_MODEL, TEST_REASONING, 131071),
                rateResult.successRate(),
                "Success rate should match getSuccessRate()");
    }

    @Test
    void hasBenchmarkData_true_for_131072_tokens() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 131072);
        // gpt-5 has benchmark data for the 65-131k range, so 131072 is covered
        assertTrue(rateResult.hasBenchmarkData(), "Token count 131072 has benchmark data");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(TEST_MODEL, TEST_REASONING, 131072),
                rateResult.successRate(),
                "Success rate should still be provided");
    }

    @Test
    void hasBenchmarkData_true_for_200000_tokens() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 200000);
        // gpt-5 has benchmark data that covers larger token ranges
        assertTrue(rateResult.hasBenchmarkData(), "Token count 200000 has benchmark data");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(TEST_MODEL, TEST_REASONING, 200000),
                rateResult.successRate(),
                "Success rate should still be provided");
    }

    @Test
    void hasBenchmarkData_true_for_1000000_tokens() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 1_000_000);
        // Even large token counts have benchmark data if the model has been tested in that range
        assertTrue(rateResult.hasBenchmarkData(), "Token count 1000000 has benchmark data");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(TEST_MODEL, TEST_REASONING, 1_000_000),
                rateResult.successRate(),
                "Success rate should still be provided");
    }

    @Test
    void hasBenchmarkData_false_for_unknown_model() {
        // Test with a model that has no benchmark data
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting("unknown-model", TEST_REASONING, 50000);
        assertFalse(rateResult.hasBenchmarkData(), "Unknown model should not have benchmark data");
        assertEquals(-1, rateResult.successRate(), "Unknown model should return -1 for success rate");
    }

    @Test
    void successRate_correct_for_tested_case_gpt5_default_20k() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 20000);
        assertTrue(rateResult.hasBenchmarkData(), "Token count 20000 has benchmark data");
        assertEquals(93, rateResult.successRate(), "gpt-5 DEFAULT @20k should be 93%");
    }

    @Test
    void successRate_correct_for_tested_case_gpt5_default_50k() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 50000);
        assertTrue(rateResult.hasBenchmarkData(), "Token count 50000 has benchmark data");
        assertEquals(71, rateResult.successRate(), "gpt-5 DEFAULT @50k should be 71%");
    }

    @Test
    void successRate_correct_for_tested_case_gemini_25pro() {
        var rateResult =
                ModelBenchmarkData.getSuccessRateWithTesting("gemini-2.5-pro", Service.ReasoningLevel.DEFAULT, 50000);
        assertTrue(rateResult.hasBenchmarkData(), "Token count 50000 has benchmark data");
        assertEquals(94, rateResult.successRate(), "gemini-2.5-pro DEFAULT @50k should be 94%");
    }

    @Test
    void getSuccessRateWithTesting_modelConfig_overload_hasBenchmarkData_true() {
        var config = new Service.ModelConfig(TEST_MODEL, TEST_REASONING);
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(config, 50000);
        assertTrue(rateResult.hasBenchmarkData(), "Token count 50000 has benchmark data");
        assertEquals(71, rateResult.successRate(), "gpt-5 DEFAULT @50k should be 71%");
    }

    @Test
    void getSuccessRateWithTesting_modelConfig_overload_hasBenchmarkData_true_large_tokens() {
        var config = new Service.ModelConfig(TEST_MODEL, TEST_REASONING);
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(config, 200000);
        assertTrue(rateResult.hasBenchmarkData(), "Token count 200000 has benchmark data");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(config, 200000),
                rateResult.successRate(),
                "Success rate should still be provided");
    }

    @Test
    void hasBenchmarkData_based_on_successRate() {
        // hasBenchmarkData should be true when successRate != -1
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 131071);
        assertTrue(rateResult.hasBenchmarkData(), "Should have benchmark data when success rate is not -1");
        assertTrue(rateResult.successRate() != -1, "Success rate should not be -1");
    }

    @Test
    void hasBenchmarkData_false_when_no_data() {
        // Test case where we know there's no benchmark data
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting("nonexistent-model", TEST_REASONING, 50000);
        assertFalse(rateResult.hasBenchmarkData(), "Should not have benchmark data for nonexistent model");
        assertEquals(-1, rateResult.successRate(), "Success rate should be -1 when no data");
    }
}
