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
        addModel("gemini-2.5-pro", Service.ReasoningLevel.DEFAULT, 93, 94, 50);
        addModel("gemini-2.5-pro", Service.ReasoningLevel.HIGH, 83, 70, 54);
        addModel("gemini-2.5-flash", Service.ReasoningLevel.DEFAULT, 90, 59, 17);
        addModel("gemini-2.5-flash", Service.ReasoningLevel.HIGH, 65, 64, 33);
        addModel("gemini-2.5-flash", Service.ReasoningLevel.DISABLE, 81, 61, 21);
        addModel("gemini-2.0-flash", Service.ReasoningLevel.DEFAULT, 21, 8, 0);
        addModel("gemini-2.5-flash-lite", Service.ReasoningLevel.DEFAULT, 0, 0, 0);

        // GPT models
        addModel("gpt-5", Service.ReasoningLevel.DEFAULT, 93, 71, 50);
        addModel("gpt-5", Service.ReasoningLevel.HIGH, 90, 77, 48);
        addModel("gpt-5-mini", Service.ReasoningLevel.DEFAULT, 90, 88, 34);
        addModel("gpt-5-mini", Service.ReasoningLevel.HIGH, 93, 88, 46);
        addModel("gpt-5-nano", Service.ReasoningLevel.DEFAULT, 70, 27, 33);
        addModel("gpt-5-nano", Service.ReasoningLevel.HIGH, 80, 70, 33);
        addModel("gpt-5-codex", Service.ReasoningLevel.DEFAULT, 53, 50, 33);

        // Claude models
        addModel("claude-4-sonnet", Service.ReasoningLevel.MEDIUM, 85, 51, 63);
        addModel("claude-4-sonnet", Service.ReasoningLevel.HIGH, 93, 53, 75);
        addModel("claude-4-sonnet", Service.ReasoningLevel.DISABLE, 100, 51, 52);
        addModel("claude-sonnet-4-5", Service.ReasoningLevel.MEDIUM, 85, 67, 67);
        addModel("claude-4-1-opus", Service.ReasoningLevel.MEDIUM, 85, 60, 54);
        addModel("claude-4-1-opus", Service.ReasoningLevel.HIGH, 93, 84, 69);
        addModel("claude-4-1-opus", Service.ReasoningLevel.DISABLE, 93, 68, 54);
        addModel("claude-haiku-4-5", Service.ReasoningLevel.MEDIUM, 85, 72, 50);
        addModel("claude-haiku-4-5", Service.ReasoningLevel.DISABLE, 85, 72, 50);

        // O models
        addModel("o3", Service.ReasoningLevel.DEFAULT, 85, 75, 34);
        addModel("o3", Service.ReasoningLevel.HIGH, 100, 63, 33);
        addModel("o4-mini", Service.ReasoningLevel.DEFAULT, 90, 44, 33);
        addModel("o4-mini", Service.ReasoningLevel.HIGH, 100, 61, 33);

        // DeepSeek models
        addModel("deepseek-v3.1", Service.ReasoningLevel.DEFAULT, 75, 44, 14);
        addModel("deepseek-v3.1-thinking", Service.ReasoningLevel.DEFAULT, 73, 26, 17);
        addModel("deepseek-v3.2", Service.ReasoningLevel.DEFAULT, 75, 70, 21);
        addModel("deepseek-v3.2-thinking", Service.ReasoningLevel.DEFAULT, 80, 76, 17);
        addModel("deepseek-R1", Service.ReasoningLevel.DEFAULT, 60, 17, 0);
        addModel("deepseek-v3", Service.ReasoningLevel.DEFAULT, 33, 17, 0);

        // GLM models
        addModel("glm-4.5", Service.ReasoningLevel.DEFAULT, 70, 52, 0);
        addModel("glm-4.5-air", Service.ReasoningLevel.DEFAULT, 60, 31, 21);
        addModel("glm-4.6", Service.ReasoningLevel.DEFAULT, 63, 72, 31);

        // Qwen models
        addModel("qwen3-coder", Service.ReasoningLevel.DEFAULT, 73, 57, 32);
        addModel("qwen3-coder-30b", Service.ReasoningLevel.DEFAULT, 69, 25, 0);
        addModel("qwen3-coder-fp8", Service.ReasoningLevel.DEFAULT, 53, 48, 21);
        addModel("qwen3-max", Service.ReasoningLevel.DEFAULT, 74, 57, 21);
        addModel("qwen3-next", Service.ReasoningLevel.DEFAULT, 49, 33, 0);

        // Grok models
        addModel("grok-3", Service.ReasoningLevel.DEFAULT, 73, 51, 17);
        addModel("grok-3-mini", Service.ReasoningLevel.DEFAULT, 39, 17, 0);
        addModel("grok-3-mini", Service.ReasoningLevel.HIGH, 20, 17, 0);
        addModel("grok-4-fast", Service.ReasoningLevel.DEFAULT, 73, 53, 33);
        addModel("grok-code-fast-1", Service.ReasoningLevel.DEFAULT, 60, 33, 21);

        // GPT-OSS models
        addModel("gpt-oss-120b", Service.ReasoningLevel.DEFAULT, 63, 42, 0);
        addModel("gpt-oss-20b", Service.ReasoningLevel.DEFAULT, 48, 0, 0);

        // Kimi model
        addModel("kimi-k2", Service.ReasoningLevel.DEFAULT, 71, 0, 0);
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
            // Try model name variants/fallbacks
            String fallbackModelName = tryModelNameVariants(modelName, normalizedLevel);
            if (fallbackModelName != null) {
                ModelKey fallbackKey = new ModelKey(fallbackModelName, normalizedLevel);
                rangeData = BENCHMARK_DATA.get(fallbackKey);
            }

            // If still not found, try reasoning level fallback
            if (rangeData == null) {
                // Try fallback for untested combinations
                // For Claude models, fall back to MEDIUM (what was actually benchmarked)
                // For other models, fall back to DEFAULT
                Service.ReasoningLevel fallbackLevel =
                        modelName.contains("claude") ? Service.ReasoningLevel.MEDIUM : Service.ReasoningLevel.DEFAULT;

                if (!normalizedLevel.equals(fallbackLevel)) {
                    ModelKey fallbackKey = new ModelKey(modelName, fallbackLevel);
                    rangeData = BENCHMARK_DATA.get(fallbackKey);
                    if (rangeData != null) {
                        // Found fallback level; use it but this is an approximate combination
                        Integer successRate = rangeData.get(range);
                        return successRate != null ? successRate : -1;
                    }
                }
            }

            // If still not found, return -1
            if (rangeData == null) {
                return -1;
            }
        }

        Integer successRate = rangeData.get(range);
        return successRate != null ? successRate : -1;
    }

    /**
     * Tries to find a matching model name in the benchmark data by applying common transformations.
     * Returns the matched model name from BENCHMARK_DATA, or null if no match found.
     */
    @Nullable
    private static String tryModelNameVariants(String modelName, Service.ReasoningLevel reasoningLevel) {
        // Normalize model name for lookup: "qwen-3-coder-480b" -> "qwen3-coder"
        // 1. Remove hyphens between name and version number: qwen-3 -> qwen3
        String normalized = modelName.replaceFirst("-(\\d)", "$1");

        // 2. Strip size suffixes: -480b, -30b, -70b, etc.
        normalized = normalized.replaceAll("-\\d+[bB]$", "");

        // Try the normalized name if different from original
        if (!normalized.equals(modelName)) {
            ModelKey testKey = new ModelKey(normalized, reasoningLevel);
            if (BENCHMARK_DATA.containsKey(testKey)) {
                return normalized;
            }
        }

        return null;
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
