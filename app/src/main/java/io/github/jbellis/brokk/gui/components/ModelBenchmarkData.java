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
        addModel("gemini-2.5-pro", Service.ReasoningLevel.DEFAULT, 50, 71, 93, 94, 50);
        addModel("gemini-2.5-pro", Service.ReasoningLevel.HIGH, 63, 100, 83, 70, 54);
        addModel("gemini-2.5-flash", Service.ReasoningLevel.DEFAULT, 39, 63, 90, 59, 17);
        addModel("gemini-2.5-flash", Service.ReasoningLevel.HIGH, 43, null, 65, 64, 33);
        addModel("gemini-2.5-flash", Service.ReasoningLevel.DISABLE, null, 51, 81, 61, 21);
        addModel("gemini-2.0-flash", Service.ReasoningLevel.DEFAULT, null, 32, 21, 8, null);
        
        addModel("gpt-5", Service.ReasoningLevel.DEFAULT, 63, 82, 93, 71, 50);
        addModel("gpt-5", Service.ReasoningLevel.HIGH, 39, 82, 90, 77, 48);
        addModel("gpt-5-mini", Service.ReasoningLevel.DEFAULT, null, 82, 90, 88, 34);
        addModel("gpt-5-mini", Service.ReasoningLevel.HIGH, 43, 82, 93, 88, 46);
        addModel("gpt-5-nano", Service.ReasoningLevel.DEFAULT, null, 75, 70, 27, 33);
        addModel("gpt-5-nano", Service.ReasoningLevel.HIGH, null, 50, 80, null, 33);
        addModel("gpt-5-codex", Service.ReasoningLevel.DEFAULT, null, null, 53, 50, 33);
        
        addModel("claude-4-sonnet", Service.ReasoningLevel.MEDIUM, 63, 63, 85, 51, 63);
        addModel("claude-4-sonnet", Service.ReasoningLevel.HIGH, 63, 63, 93, 53, 75);
        addModel("claude-4-sonnet", Service.ReasoningLevel.DISABLE, null, 57, 100, null, 52);
        addModel("claude-4.5-sonnet", Service.ReasoningLevel.MEDIUM, 63, 63, 85, 67, 67);
        addModel("claude-4.1-opus", Service.ReasoningLevel.MEDIUM, 63, 63, 85, 60, 54);
        addModel("claude-4.1-opus", Service.ReasoningLevel.HIGH, 63, 63, 93, 84, 69);
        addModel("claude-4.1-opus", Service.ReasoningLevel.DISABLE, 63, 63, 93, 68, 54);
        addModel("claude-4.5-haiku", Service.ReasoningLevel.MEDIUM, 100, null, 85, 72, 50);
        
        addModel("o3", Service.ReasoningLevel.DEFAULT, 43, 75, 85, 75, 34);
        addModel("o3", Service.ReasoningLevel.HIGH, null, 75, 100, 63, 33);
        addModel("o4-mini", Service.ReasoningLevel.DEFAULT, null, 100, 90, 44, 33);
        addModel("o4-mini", Service.ReasoningLevel.HIGH, 43, null, 100, 61, 33);
        
        addModel("deepseek-v3.1", Service.ReasoningLevel.DEFAULT, null, 32, 75, 44, 14);
        addModel("deepseek-v3.1", Service.ReasoningLevel.DEFAULT, null, 32, 73, 26, 17);
        addModel("deepseek-v3.2", Service.ReasoningLevel.DEFAULT, null, 57, 75, 70, 21);
        addModel("deepseek-v3.2", Service.ReasoningLevel.DEFAULT, null, 57, 80, 76, 17);
        addModel("deepseek-r1", Service.ReasoningLevel.DEFAULT, null, 63, 60, 17, null);
        addModel("deepseek-v3", Service.ReasoningLevel.DEFAULT, null, null, 33, 17, null);
        
        addModel("glm-4.5", Service.ReasoningLevel.DEFAULT, 43, 82, 70, 52, null);
        addModel("glm-4.5-air", Service.ReasoningLevel.DEFAULT, null, 57, 60, 31, 21);
        addModel("glm-4.6", Service.ReasoningLevel.DEFAULT, 100, 82, 63, 72, 31);
        
        addModel("qwen-3-coder", Service.ReasoningLevel.DEFAULT, 30, 65, 73, 57, 32);
        addModel("qwen-3-coder-30b", Service.ReasoningLevel.DEFAULT, null, 75, 69, 25, null);
        addModel("qwen-3-coder-fp8", Service.ReasoningLevel.DEFAULT, null, 50, 53, 48, 21);
        addModel("qwen-3-max", Service.ReasoningLevel.DEFAULT, null, 63, 74, 57, 21);
        addModel("qwen-3-next", Service.ReasoningLevel.DEFAULT, null, null, 49, 33, null);
        
        addModel("grok-3", Service.ReasoningLevel.DEFAULT, null, 51, 73, 51, 17);
        addModel("grok-3-mini", Service.ReasoningLevel.DEFAULT, null, 32, 39, 17, null);
        addModel("grok-3-mini", Service.ReasoningLevel.HIGH, null, 32, 20, 17, null);
        addModel("grok-4-fast", Service.ReasoningLevel.DEFAULT, 50, null, 73, 53, 33);
        addModel("grok-code-fast-1", Service.ReasoningLevel.DEFAULT, null, null, 60, 33, 21);
        
        addModel("gpt-oss-120b", Service.ReasoningLevel.DEFAULT, null, 75, 63, 42, null);
        addModel("gpt-oss-20b", Service.ReasoningLevel.DEFAULT, null, null, 48, null, null);
        
        addModel("kimi-k2", Service.ReasoningLevel.DEFAULT, null, null, 71, null, null);
    }

    private static void addModel(String modelName, Service.ReasoningLevel reasoning, 
                                  Integer rate4k8k, Integer rate8k16k, Integer rate16k32k, 
                                  Integer rate32k65k, Integer rate65k131k) {
        ModelKey key = new ModelKey(modelName, reasoning);
        Map<TokenRange, Integer> rangeData = new HashMap<>();
        
        if (rate4k8k != null) rangeData.put(TokenRange.RANGE_4K_8K, rate4k8k);
        if (rate8k16k != null) rangeData.put(TokenRange.RANGE_8K_16K, rate8k16k);
        if (rate16k32k != null) rangeData.put(TokenRange.RANGE_16K_32K, rate16k32k);
        if (rate32k65k != null) rangeData.put(TokenRange.RANGE_32K_65K, rate32k65k);
        if (rate65k131k != null) rangeData.put(TokenRange.RANGE_65K_131K, rate65k131k);
        
        if (!rangeData.isEmpty()) {
            BENCHMARK_DATA.put(key, rangeData);
        }
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
