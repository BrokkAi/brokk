package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.Service;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public class ModelBenchmarkData {

    public enum TokenRange {
        RANGE_4K_8K(4096, 8191),
        RANGE_8K_16K(8192, 16383),
        RANGE_16K_32K(16384, 32767),
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

    private static final Map<ModelKey, Map<TokenRange, Integer>> BENCHMARK_DATA = new HashMap<>();

    static {
        Map<TokenRange, Integer> rangeData;

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_32K_65K, 93);
        rangeData.put(TokenRange.RANGE_65K_131K, 33);
        BENCHMARK_DATA.put(new ModelKey("gemini-2.5-pro", Service.ReasoningLevel.DEFAULT), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_32K_65K, 81);
        rangeData.put(TokenRange.RANGE_65K_131K, 25);
        BENCHMARK_DATA.put(new ModelKey("gemini-2.5-pro", Service.ReasoningLevel.DISABLE), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_16K_32K, 100);
        rangeData.put(TokenRange.RANGE_32K_65K, 84);
        rangeData.put(TokenRange.RANGE_65K_131K, 25);
        BENCHMARK_DATA.put(new ModelKey("gpt-5", Service.ReasoningLevel.DEFAULT), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_16K_32K, 100);
        rangeData.put(TokenRange.RANGE_32K_65K, 68);
        rangeData.put(TokenRange.RANGE_65K_131K, 0);
        BENCHMARK_DATA.put(new ModelKey("gpt-5", Service.ReasoningLevel.DISABLE), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_16K_32K, 100);
        rangeData.put(TokenRange.RANGE_32K_65K, 50);
        rangeData.put(TokenRange.RANGE_65K_131K, 25);
        BENCHMARK_DATA.put(new ModelKey("claude-4-sonnet", Service.ReasoningLevel.MEDIUM), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_32K_65K, 92);
        rangeData.put(TokenRange.RANGE_65K_131K, 75);
        BENCHMARK_DATA.put(new ModelKey("claude-4-sonnet", Service.ReasoningLevel.HIGH), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_8K_16K, 100);
        rangeData.put(TokenRange.RANGE_16K_32K, 100);
        rangeData.put(TokenRange.RANGE_32K_65K, 87);
        rangeData.put(TokenRange.RANGE_65K_131K, 33);
        BENCHMARK_DATA.put(new ModelKey("claude-4-opus", Service.ReasoningLevel.MEDIUM), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_8K_16K, 100);
        rangeData.put(TokenRange.RANGE_16K_32K, 93);
        rangeData.put(TokenRange.RANGE_32K_65K, 31);
        rangeData.put(TokenRange.RANGE_65K_131K, 8);
        BENCHMARK_DATA.put(new ModelKey("gemini-2.0-pro", Service.ReasoningLevel.DEFAULT), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_8K_16K, 62);
        rangeData.put(TokenRange.RANGE_16K_32K, 20);
        rangeData.put(TokenRange.RANGE_32K_65K, 8);
        BENCHMARK_DATA.put(new ModelKey("gemini-2.0-pro", Service.ReasoningLevel.DISABLE), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_8K_16K, 100);
        rangeData.put(TokenRange.RANGE_16K_32K, 100);
        rangeData.put(TokenRange.RANGE_32K_65K, 62);
        rangeData.put(TokenRange.RANGE_65K_131K, 25);
        BENCHMARK_DATA.put(new ModelKey("gpt-4.1-turbo", Service.ReasoningLevel.DEFAULT), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_8K_16K, 87);
        rangeData.put(TokenRange.RANGE_16K_32K, 100);
        rangeData.put(TokenRange.RANGE_32K_65K, 43);
        rangeData.put(TokenRange.RANGE_65K_131K, 16);
        BENCHMARK_DATA.put(new ModelKey("gpt-4.1-turbo", Service.ReasoningLevel.DISABLE), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_8K_16K, 87);
        rangeData.put(TokenRange.RANGE_16K_32K, 50);
        rangeData.put(TokenRange.RANGE_32K_65K, 25);
        BENCHMARK_DATA.put(new ModelKey("gemini-2.0-flash", Service.ReasoningLevel.DEFAULT), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_8K_16K, 87);
        rangeData.put(TokenRange.RANGE_16K_32K, 80);
        rangeData.put(TokenRange.RANGE_32K_65K, 18);
        BENCHMARK_DATA.put(new ModelKey("claude-4-haiku", Service.ReasoningLevel.MEDIUM), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_4K_8K, 100);
        rangeData.put(TokenRange.RANGE_8K_16K, 75);
        BENCHMARK_DATA.put(new ModelKey("gpt-5-mini", Service.ReasoningLevel.DEFAULT), rangeData);

        rangeData = new HashMap<>();
        rangeData.put(TokenRange.RANGE_16K_32K, 100);
        BENCHMARK_DATA.put(new ModelKey("mistral-large", Service.ReasoningLevel.DEFAULT), rangeData);
    }

    public static int getSuccessRate(String modelName, Service.ReasoningLevel reasoningLevel, int tokenCount) {
        TokenRange range = TokenRange.fromTokenCount(tokenCount);
        if (range == null) {
            return 100;
        }

        Service.ReasoningLevel normalizedLevel = reasoningLevel;
        if (modelName.contains("claude") && reasoningLevel == Service.ReasoningLevel.DEFAULT) {
            normalizedLevel = Service.ReasoningLevel.MEDIUM;
        }

        ModelKey key = new ModelKey(modelName, normalizedLevel);
        Map<TokenRange, Integer> rangeData = BENCHMARK_DATA.get(key);
        if (rangeData == null) {
            return 100;
        }

        Integer successRate = rangeData.get(range);
        return successRate != null ? successRate : 100;
    }

    public static int getSuccessRate(Service.ModelConfig config, int tokenCount) {
        String modelName = config.name();
        Service.ReasoningLevel reasoningLevel = config.reasoning();

        if (modelName.contains("-nothink")) {
            modelName = modelName.replace("-nothink", "");
            reasoningLevel = Service.ReasoningLevel.DISABLE;
        }

        return getSuccessRate(modelName, reasoningLevel, tokenCount);
    }
}
