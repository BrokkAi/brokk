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
    void isTested_true_for_boundary_0_tokens() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 0);
        assertTrue(rateResult.isTested(), "Token count 0 should be marked as tested");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(TEST_MODEL, TEST_REASONING, 0),
                rateResult.successRate(),
                "Success rate should match getSuccessRate()");
    }

    @Test
    void isTested_true_for_boundary_4096_tokens() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 4096);
        assertTrue(rateResult.isTested(), "Token count 4096 should be marked as tested");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(TEST_MODEL, TEST_REASONING, 4096),
                rateResult.successRate(),
                "Success rate should match getSuccessRate()");
    }

    @Test
    void isTested_true_for_boundary_131071_tokens() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 131071);
        assertTrue(rateResult.isTested(), "Token count 131071 should be marked as tested");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(TEST_MODEL, TEST_REASONING, 131071),
                rateResult.successRate(),
                "Success rate should match getSuccessRate()");
    }

    @Test
    void isTested_false_for_out_of_range_131072_tokens() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 131072);
        assertFalse(rateResult.isTested(), "Token count 131072 should be marked as not tested");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(TEST_MODEL, TEST_REASONING, 131072),
                rateResult.successRate(),
                "Success rate should still be provided");
    }

    @Test
    void isTested_false_for_out_of_range_200000_tokens() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 200000);
        assertFalse(rateResult.isTested(), "Token count 200000 should be marked as not tested");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(TEST_MODEL, TEST_REASONING, 200000),
                rateResult.successRate(),
                "Success rate should still be provided");
    }

    @Test
    void isTested_false_for_out_of_range_1000000_tokens() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 1_000_000);
        assertFalse(rateResult.isTested(), "Token count 1000000 should be marked as not tested");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(TEST_MODEL, TEST_REASONING, 1_000_000),
                rateResult.successRate(),
                "Success rate should still be provided");
    }

    @Test
    void successRate_correct_for_tested_case_gpt5_default_20k() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 20000);
        assertTrue(rateResult.isTested(), "Token count 20000 should be marked as tested");
        assertEquals(93, rateResult.successRate(), "gpt-5 DEFAULT @20k should be 93%");
    }

    @Test
    void successRate_correct_for_tested_case_gpt5_default_50k() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 50000);
        assertTrue(rateResult.isTested(), "Token count 50000 should be marked as tested");
        assertEquals(71, rateResult.successRate(), "gpt-5 DEFAULT @50k should be 71%");
    }

    @Test
    void successRate_correct_for_tested_case_gemini_25pro() {
        var rateResult =
                ModelBenchmarkData.getSuccessRateWithTesting("gemini-2.5-pro", Service.ReasoningLevel.DEFAULT, 50000);
        assertTrue(rateResult.isTested(), "Token count 50000 should be marked as tested");
        assertEquals(94, rateResult.successRate(), "gemini-2.5-pro DEFAULT @50k should be 94%");
    }

    @Test
    void getSuccessRateWithTesting_modelConfig_overload_isTested_true() {
        var config = new Service.ModelConfig(TEST_MODEL, TEST_REASONING);
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(config, 50000);
        assertTrue(rateResult.isTested(), "Token count 50000 should be marked as tested");
        assertEquals(71, rateResult.successRate(), "gpt-5 DEFAULT @50k should be 71%");
    }

    @Test
    void getSuccessRateWithTesting_modelConfig_overload_isTested_false() {
        var config = new Service.ModelConfig(TEST_MODEL, TEST_REASONING);
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(config, 200000);
        assertFalse(rateResult.isTested(), "Token count 200000 should be marked as not tested");
        assertEquals(
                ModelBenchmarkData.getSuccessRate(config, 200000),
                rateResult.successRate(),
                "Success rate should still be provided");
    }

    @Test
    void isTested_boundary_at_131071_inclusive() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 131071);
        assertTrue(rateResult.isTested(), "Token count 131071 should be included in tested range");
    }

    @Test
    void isTested_boundary_at_131072_exclusive() {
        var rateResult = ModelBenchmarkData.getSuccessRateWithTesting(TEST_MODEL, TEST_REASONING, 131072);
        assertFalse(rateResult.isTested(), "Token count 131072 should be outside tested range");
    }
}
