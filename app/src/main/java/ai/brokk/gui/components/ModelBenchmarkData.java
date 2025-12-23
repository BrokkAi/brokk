package ai.brokk.gui.components;

import ai.brokk.Service;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public class ModelBenchmarkData {

    public enum TokenRange {
        RANGE_16K_32K(0, 32767),
        RANGE_32K_65K(32768, 65535),
        RANGE_65K_131K(65536, 131071);

        private final int minTokens;
        private final int maxTokens;

        TokenRange(int minTokens, int maxTokens) {
            this.minTokens = minTokens;
            this.maxTokens = maxTokens;
        }

        @Nullable
        public static TokenRange fromTokenCount(int tokenCount) {
            for (TokenRange range : values()) {
                if (tokenCount >= range.minTokens && tokenCount <= range.maxTokens) {
                    return range;
                }
            }
            return null;
        }
    }

    public record ModelKey(String modelName, Service.ReasoningLevel reasoningLevel) {}

    public record SuccessRateResult(int successRate, boolean isTested) {}

    private static final Map<ModelKey, Map<TokenRange, Integer>> BENCHMARK_DATA = new HashMap<>();

    static {
        // Gemini models
        addModel("gemini-3-pro-preview", Service.ReasoningLevel.DEFAULT, 100, 94, 80);
        addModel("gemini-2.5-pro", Service.ReasoningLevel.DEFAULT, 93, 94, 50);
        addModel("gemini-2.5-pro", Service.ReasoningLevel.HIGH, 83, 70, 54);
        addModel("gemini-2.5-flash", Service.ReasoningLevel.DEFAULT, 90, 59, 17);
        addModel("gemini-2.5-flash", Service.ReasoningLevel.HIGH, 65, 64, 33);
        addModel("gemini-2.5-flash", Service.ReasoningLevel.DISABLE, 81, 61, 21);
        addModel("gemini-2.0-flash", Service.ReasoningLevel.DEFAULT, 21, 8, 0);
        addModel("gemini-2.5-flash-lite", Service.ReasoningLevel.DEFAULT, 0, 0, 0);

        // GPT models
        addModel("gpt-5.1", Service.ReasoningLevel.DEFAULT, 100, 100, 67);
        addModel("gpt-5", Service.ReasoningLevel.DEFAULT, 93, 71, 50);
        addModel("gpt-5", Service.ReasoningLevel.HIGH, 90, 77, 48);
        addModel("gpt-5-mini", Service.ReasoningLevel.DEFAULT, 90, 88, 34);
        addModel("gpt-5-mini", Service.ReasoningLevel.HIGH, 93, 88, 46);
        addModel("gpt-5-nano", Service.ReasoningLevel.DEFAULT, 70, 27, 33);
        addModel("gpt-5-nano", Service.ReasoningLevel.HIGH, 80, 70, 33);
        addModel("gpt-5-codex", Service.ReasoningLevel.DEFAULT, 53, 50, 33);

        // Claude models
        addModel("claude-4-1-opus", Service.ReasoningLevel.MEDIUM, 85, 60, 54);
        addModel("claude-4-1-opus", Service.ReasoningLevel.HIGH, 93, 84, 69);
        addModel("claude-4-1-opus", Service.ReasoningLevel.DISABLE, 93, 68, 54);
        addModel("claude-haiku-4-5", Service.ReasoningLevel.DISABLE, 85, 75, 33);
        addModel("claude-haiku-4-5", Service.ReasoningLevel.MEDIUM, 85, 75, 33);
        addModel("claude-sonnet-4-5", Service.ReasoningLevel.MEDIUM, 100, 60, 52);
        addModel("claude-opus-4-5", Service.ReasoningLevel.MEDIUM, 85, 84, 54);

        // GLM models
        addModel("zai-glm-4.6", Service.ReasoningLevel.DEFAULT, 85, 90, 50);

        // Grok models
        addModel("grok-4-fast-reasoning", Service.ReasoningLevel.DEFAULT, 73, 53, 33);
        addModel("grok-code-fast-1", Service.ReasoningLevel.DEFAULT, 73, 50, 21);

        // GPT-OSS models
        addModel("gpt-oss-120b", Service.ReasoningLevel.DEFAULT, 63, 42, 0);
    }

    private static void addModel(
            String modelName,
            Service.ReasoningLevel reasoning,
            Integer rate16k32k,
            Integer rate32k65k,
            Integer rate65k131k) {
        ModelKey key = new ModelKey(modelName, reasoning);
        Map<TokenRange, Integer> rangeData = new HashMap<>();

        rangeData.put(TokenRange.RANGE_16K_32K, rate16k32k);
        rangeData.put(TokenRange.RANGE_32K_65K, rate32k65k);
        rangeData.put(TokenRange.RANGE_65K_131K, rate65k131k);

        if (!rangeData.isEmpty()) {
            BENCHMARK_DATA.put(key, rangeData);
        }
    }

    public static int getSuccessRate(String modelName, Service.ReasoningLevel reasoningLevel, int tokenCount) {
        TokenRange range = TokenRange.fromTokenCount(tokenCount);

        // Handle out-of-range token counts
        if (range == null) {
            if (tokenCount > 131071) {
                // Above maximum tested range: use the highest range's data
                range = TokenRange.RANGE_65K_131K;
            } else {
                // Below minimum tested range: use smallest range data
                range = TokenRange.RANGE_16K_32K;
            }
        }

        Service.ReasoningLevel normalizedLevel = reasoningLevel;
        if (modelName.contains("claude") && reasoningLevel == Service.ReasoningLevel.DEFAULT) {
            normalizedLevel = Service.ReasoningLevel.MEDIUM;
        }

        ModelKey key = new ModelKey(modelName, normalizedLevel);
        Map<TokenRange, Integer> rangeData = BENCHMARK_DATA.get(key);
        if (rangeData == null) {
            // Try fallback for untested combinations
            // For Claude models, fall back to MEDIUM (what was actually benchmarked)
            // For other models, fall back to DEFAULT
            var fallbackLevel =
                    modelName.contains("claude") ? Service.ReasoningLevel.MEDIUM : Service.ReasoningLevel.DEFAULT;

            if (!normalizedLevel.equals(fallbackLevel)) {
                var fallbackKey = new ModelKey(modelName, fallbackLevel);
                rangeData = BENCHMARK_DATA.get(fallbackKey);
                if (rangeData != null) {
                    Integer successRate = rangeData.get(range);
                    return successRate != null ? successRate : -1;
                }
            }

            return -1;
        }

        Integer successRate = rangeData.get(range);
        return successRate != null ? successRate : -1;
    }

    public static int getSuccessRate(Service.ModelConfig config, int tokenCount) {
        String modelName = config.name();
        Service.ReasoningLevel reasoningLevel = config.reasoning();

        if (modelName.contains("-nothink")) {
            modelName = modelName.replace("-nothink", "");
            reasoningLevel = Service.ReasoningLevel.DISABLE;
        }

        // Strip provider prefix (e.g., "cerebras/qwen3-coder" → "qwen3-coder")
        // to match benchmark data keys which don't include provider prefixes
        if (modelName.contains("/")) {
            modelName = modelName.substring(modelName.indexOf('/') + 1);
        }

        return getSuccessRate(modelName, reasoningLevel, tokenCount);
    }

    public static SuccessRateResult getSuccessRateWithTesting(
            String modelName, Service.ReasoningLevel reasoningLevel, int tokenCount) {
        int successRate = getSuccessRate(modelName, reasoningLevel, tokenCount);
        boolean isTested = tokenCount <= 131071;
        return new SuccessRateResult(successRate, isTested);
    }

    public static SuccessRateResult getSuccessRateWithTesting(Service.ModelConfig config, int tokenCount) {
        int successRate = getSuccessRate(config, tokenCount);
        boolean isTested = tokenCount <= 131071;
        return new SuccessRateResult(successRate, isTested);
    }
}
