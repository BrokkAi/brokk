package ai.brokk;

import ai.brokk.project.IProject;
import ai.brokk.testutil.TestService;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ModelPricingTest {

    private static class ExposedTestService extends TestService {
        ExposedTestService(IProject project) {
            super(project);
        }

        void setModelInfoMap(Map<String, Map<String, Object>> modelInfoMap) {
            this.modelInfoMap = modelInfoMap;
        }
    }

    @Test
    void getModelPricing_usesCacheCreationCost_whenPresent() {
        ExposedTestService service = new ExposedTestService(new IProject() {});
        String modelName = "CacheModel";
        service.setModelInfoMap(Map.of(
                modelName,
                Map.of(
                        "input_cost_per_token", 10.0,
                        "cache_read_input_token_cost", 2.0,
                        "cache_creation_input_token_cost", 5.0,
                        "output_cost_per_token", 15.0)));

        var pricing = service.getModelPricing(modelName);
        double cost = pricing.getCostFor(100, 200, 50, 40);
        // (100 * 10.0) + (200 * 2.0) + (50 * 15.0) + (40 * 5.0)
        // 1000 + 400 + 750 + 200 = 2350
        Assertions.assertEquals(2350.0, cost, 0.0001);
    }

    @Test
    void getModelPricing_fallsBackToInputCost_whenCacheCreationMissing() {
        ExposedTestService service = new ExposedTestService(new IProject() {});
        String modelName = "FallbackModel";
        service.setModelInfoMap(Map.of(
                modelName,
                Map.of(
                        "input_cost_per_token", 10.0,
                        "cache_read_input_token_cost", 2.0,
                        "output_cost_per_token", 15.0)));

        var pricing = service.getModelPricing(modelName);
        double cost = pricing.getCostFor(100, 200, 50, 40);
        // cacheCreation falls back to inputCost (10.0)
        // (100 * 10.0) + (200 * 2.0) + (50 * 15.0) + (40 * 10.0)
        // 1000 + 400 + 750 + 400 = 2550
        Assertions.assertEquals(2550.0, cost, 0.0001);
    }

    @Test
    void getModelPricing_handlesAbove200kWithCacheCreation() {
        ExposedTestService service = new ExposedTestService(new IProject() {});
        String modelName = "TieredModel";
        service.setModelInfoMap(Map.of(
                modelName,
                Map.of(
                        "input_cost_per_token", 10.0,
                        "cache_read_input_token_cost", 2.0,
                        "cache_creation_input_token_cost", 5.0,
                        "output_cost_per_token", 15.0,
                        "input_cost_per_token_above_200k_tokens", 20.0,
                        "cache_creation_input_token_cost_above_200k_tokens", 12.0)));

        var pricing = service.getModelPricing(modelName);

        // Under 200k (prompt = 100 + 50 + 50 = 200)
        double costUnder = pricing.getCostFor(100, 50, 10, 50);
        // (100*10) + (50*2) + (10*15) + (50*5) = 1000 + 100 + 150 + 250 = 1500
        Assertions.assertEquals(1500.0, costUnder, 0.0001);

        // Over 200k (prompt = 150k + 50k + 50k = 250k)
        double costOver = pricing.getCostFor(150_000, 50_000, 10_000, 50_000);
        // input: 150k * 20.0 = 3,000,000
        // cached: 50k * 2.0 = 100,000 (no above_200k override provided, falls back to standard cached)
        // output: 10k * 15.0 = 150,000 (no above_200k override provided)
        // creation: 50k * 12.0 = 600,000
        // Total: 3,850,000
        Assertions.assertEquals(3_850_000.0, costOver, 0.0001);
    }
}
