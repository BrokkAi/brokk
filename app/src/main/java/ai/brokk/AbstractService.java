package ai.brokk;

import static java.lang.Math.max;
import static java.lang.Math.min;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Abstract base for Service. Contains model configuration, metadata, and non-network logic.
 * Anything that makes an HTTP request must remain in the concrete Service class.
 */
public abstract class AbstractService implements ExceptionReporter.ReportingService {

    // Constants and configuration
    public static final String TOP_UP_URL = "https://brokk.ai/dashboard";
    public static float MINIMUM_PAID_BALANCE = 0.20f;
    public static float LOW_BALANCE_WARN_AT = 2.00f;

    public static final long FLEX_FIRST_TOKEN_TIMEOUT_SECONDS = 15L * 60L; // 15 minutes
    public static final long DEFAULT_FIRST_TOKEN_TIMEOUT_SECONDS = 2L * 60L; // 2 minutes
    public static final long NEXT_TOKEN_TIMEOUT_SECONDS = DEFAULT_FIRST_TOKEN_TIMEOUT_SECONDS;

    public static final boolean GLOBAL_FORCE_TOOL_EMULATION = true;

    public static final String UNAVAILABLE = "AI is unavailable";

    // Model name constants
    public static final String GPT_5 = "gpt-5";
    public static final String GEMINI_2_5_PRO = "gemini-2.5-pro";
    public static final String GEMINI_2_0_FLASH = "gemini-2.0-flash";
    public static final String GEMINI_2_5_FLASH = "gemini-2.5-flash";
    public static final String GPT_5_MINI = "gpt-5-mini";

    // these models are defined for low-latency use cases that don't require high intelligence
    private static final Set<String> SYSTEM_ONLY_MODELS = Set.of("gemini-2.0-flash-lite", "gpt-4.1-nano");

    protected final Logger logger = LogManager.getLogger(AbstractService.class);
    protected final ObjectMapper objectMapper = new ObjectMapper();

    // display name -> location
    protected Map<String, String> modelLocations = Map.of(UNAVAILABLE, "not_a_model");
    // location -> model info (inner map is also immutable)
    protected Map<String, Map<String, Object>> modelInfoMap = Map.of();

    // Default models - instance fields
    protected StreamingChatModel quickModel = new UnavailableStreamingModel();
    protected StreamingChatModel quickestModel = new UnavailableStreamingModel();
    protected StreamingChatModel quickEditModel = new UnavailableStreamingModel();
    protected SpeechToTextModel sttModel = new UnavailableSTT();

    public AbstractService(IProject project) {
        // Intentionally minimal: no network calls here
    }

    public abstract float getUserBalance() throws IOException;

    public abstract void sendFeedback(
            String category, String feedbackText, boolean includeDebugLog, @Nullable File screenshotFile)
            throws IOException;

    public interface Provider {
        AbstractService get();

        void reinit(IProject project);
    }

    // Helper record to store model name and reasoning level for checking
    public record ModelConfig(String name, ReasoningLevel reasoning, ProcessingTier tier) {
        public ModelConfig(String name, ReasoningLevel reasoning) {
            this(name, reasoning, ProcessingTier.DEFAULT);
        }

        public ModelConfig(String name) {
            this(name, ReasoningLevel.DEFAULT);
        }

        public static ModelConfig from(StreamingChatModel model, AbstractService svc) {
            var canonicalName = svc.nameOf(model);
            var tier = AbstractService.getProcessingTier(model);

            ReasoningLevel reasoning = ReasoningLevel.DEFAULT;
            if (model instanceof OpenAiStreamingChatModel om) {
                var params = om.defaultRequestParameters();
                var effort = params == null ? null : params.reasoningEffort();
                if (effort != null && !effort.isBlank()) {
                    reasoning = ReasoningLevel.fromString(effort, ReasoningLevel.DEFAULT);
                }
            }

            return new ModelConfig(canonicalName, reasoning, tier);
        }
    }

    public record PriceBand(
            long minTokensInclusive,
            long maxTokensInclusive, // Long.MAX_VALUE means "no upper limit"
            double inputCostPerToken,
            double cachedInputCostPerToken,
            double outputCostPerToken) {
        public boolean contains(long tokens) {
            return tokens >= minTokensInclusive && tokens <= maxTokensInclusive;
        }

        public String getDescription() {
            if (maxTokensInclusive == Long.MAX_VALUE) {
                return String.format("for prompts ≥ %,d tokens", minTokensInclusive);
            } else if (minTokensInclusive == 0) {
                return String.format("for prompts ≤ %,d tokens", maxTokensInclusive);
            } else {
                return String.format("for prompts %,d–%,d tokens", minTokensInclusive, maxTokensInclusive);
            }
        }
    }

    public record ModelPricing(List<PriceBand> bands) {
        public PriceBand bandFor(long tokens) {
            return bands.stream()
                    .filter(b -> b.contains(tokens))
                    .findFirst()
                    .orElse(bands.getLast()); // fallback to last band if no match
        }

        public double estimateCost(long uncachedInputTokens, long cachedTokens, long outputTokens) {
            var promptTokens = uncachedInputTokens + cachedTokens;
            var band = bandFor(promptTokens);
            return uncachedInputTokens * band.inputCostPerToken()
                    + cachedTokens * band.cachedInputCostPerToken()
                    + outputTokens * band.outputCostPerToken();
        }
    }

    public enum ProcessingTier {
        DEFAULT,
        PRIORITY,
        FLEX;

        @Override
        public String toString() {
            return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        }
    }

    public enum ReasoningLevel {
        DEFAULT,
        LOW,
        MEDIUM,
        HIGH,
        DISABLE;

        @Override
        public String toString() {
            return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        }

        public static ReasoningLevel fromString(@Nullable String value, ReasoningLevel defaultLevel) {
            if (value == null) {
                return defaultLevel;
            }
            try {
                return ReasoningLevel.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return defaultLevel;
            }
        }
    }

    /** Represents the parsed Brokk API key components. */
    public record KeyParts(UUID userId, String token) {}

    /** Represents a user-defined favorite model alias. */
    public record FavoriteModel(String alias, ModelConfig config) {}

    /**
     * Parses a Brokk API key of the form 'brk+<userId>+<token>'. The userId must be a valid UUID.
     * The `sk-` prefix is added implicitly to tokens.
     */
    public static KeyParts parseKey(String key) {
        var parts = Splitter.on(Pattern.compile("\\+")).splitToList(key);
        if (parts.size() != 3 || !"brk".equals(parts.get(0))) {
            throw new IllegalArgumentException(
                    "Key must have format `brk+<userId>+<token>`; found `%s`".formatted(key));
        }

        UUID userId;
        try {
            userId = UUID.fromString(parts.get(1));
        } catch (Exception e) {
            throw new IllegalArgumentException("User ID (part 2) must be a valid UUID", e);
        }

        return new KeyParts(userId, "sk-" + parts.get(2));
    }

    public ModelPricing getModelPricing(String modelName) {
        var location = modelLocations.get(modelName);
        if (location == null) {
            logger.warn("Location not found for model name {}, cannot get prices.", modelName);
            return new ModelPricing(List.of());
        }
        var info = getModelInfo(location);
        if (info == null) {
            logger.warn("Model info not found for location {}, cannot get prices.", location);
            return new ModelPricing(List.of());
        }

        Function<Object, Double> tryDouble = val -> {
            if (val instanceof Number n) return n.doubleValue();
            if (val instanceof String s) {
                try {
                    return Double.parseDouble(s);
                } catch (Exception e) {
                    return 0.0;
                }
            }
            return 0.0;
        };

        var inputCost = tryDouble.apply(info.get("input_cost_per_token"));
        var cachedInputCost = tryDouble.apply(info.get("cache_read_input_token_cost"));
        var outputCost = tryDouble.apply(info.get("output_cost_per_token"));

        var inputAbove200k = info.get("input_cost_per_token_above_200k_tokens");
        var cachedInputAbove200k = info.get("cache_read_input_token_cost_above_200k_tokens");
        var outputAbove200k = info.get("output_cost_per_token_above_200k_tokens");
        boolean hasAbove200k = inputAbove200k != null || cachedInputAbove200k != null || outputAbove200k != null;

        if (hasAbove200k) {
            var band1 = new PriceBand(0, 199_999, inputCost, cachedInputCost, outputCost);
            var band2 = new PriceBand(
                    200_000,
                    Long.MAX_VALUE,
                    inputAbove200k == null ? inputCost : tryDouble.apply(inputAbove200k),
                    cachedInputAbove200k == null ? cachedInputCost : tryDouble.apply(cachedInputAbove200k),
                    outputAbove200k == null ? outputCost : tryDouble.apply(outputAbove200k));
            return new ModelPricing(List.of(band1, band2));
        } else {
            var band = new PriceBand(0, Long.MAX_VALUE, inputCost, cachedInputCost, outputCost);
            return new ModelPricing(List.of(band));
        }
    }

    /** Returns the display name for a given model instance */
    public String nameOf(StreamingChatModel model) {
        var location = model.defaultRequestParameters().modelName();
        return modelLocations.entrySet().stream()
                .filter(entry -> entry.getValue().equals(location))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Model location not found in known models: " + location));
    }

    /**
     * Gets a map of available model names to their full location strings, suitable for display.
     * Filters out internal/utility models like flash-lite.
     */
    public Map<String, String> getAvailableModels() {
        return modelLocations.entrySet().stream()
                .filter(e -> !SYSTEM_ONLY_MODELS.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Retrieves the maximum output tokens for the given model location.
     */
    private int getMaxOutputTokens(String location) {
        var info = getModelInfo(location);

        Integer value;
        if (info == null || !info.containsKey("max_output_tokens")) {
            logger.warn("max_output_tokens not found for model location: {}", location);
            value = 8192;
        } else {
            value = (Integer) info.get("max_output_tokens");
        }

        int ceiling = min(value, getMaxInputTokens(location) / 8);
        int floor = min(8192, value);
        return max(floor, ceiling);
    }

    /**
     * Retrieves the maximum input tokens for the given model.
     */
    public int getMaxInputTokens(StreamingChatModel model) {
        var location = model.defaultRequestParameters().modelName();
        return getMaxInputTokens(location);
    }

    private int getMaxInputTokens(String location) {
        var info = getModelInfo(location);
        if (info == null || !info.containsKey("max_input_tokens")) {
            logger.warn("max_input_tokens not found for model location: {}", location);
            return 65536;
        }
        var value = info.get("max_input_tokens");
        assert value instanceof Integer;
        return (Integer) value;
    }

    /**
     * Retrieves the maximum concurrent requests for the given model name. Returns null if unavailable.
     */
    public @Nullable Integer getMaxConcurrentRequests(StreamingChatModel model) {
        var location = model.defaultRequestParameters().modelName();
        var info = getModelInfo(location);
        if (info == null || !info.containsKey("max_concurrent_requests")) {
            return null;
        }
        return (Integer) info.get("max_concurrent_requests");
    }

    /** Retrieves the tokens per minute for the given model. Returns null if unavailable. */
    public @Nullable Integer getTokensPerMinute(StreamingChatModel model) {
        var location = model.defaultRequestParameters().modelName();
        var info = getModelInfo(location);
        if (info == null || !info.containsKey("tokens_per_minute")) {
            return null;
        }
        return (Integer) info.get("tokens_per_minute");
    }

    public boolean supportsToolChoiceRequired(StreamingChatModel model) {
        var modelName = nameOf(model);
        var location = modelLocations.get(modelName);
        if (location == null) {
            logger.warn("Location not found for model name {}, assuming no tool_choice=required support", modelName);
            return false;
        }
        var info = getModelInfo(location);
        if (info == null || !info.containsKey("supports_tool_choice")) {
            return false;
        }

        return (Boolean) info.get("supports_tool_choice");
    }

    public boolean supportsProcessingTier(String modelName) {
        var location = modelLocations.get(modelName);
        if (location == null) {
            logger.warn("Location not found for model name {}, assuming no reasoning-disable support.", modelName);
            return false;
        }
        return location.equals("openai/gpt-5")
                || location.equals("openai/gpt-5-mini"); // TODO move this into a yaml field
    }

    public boolean supportsReasoningDisable(String modelName) {
        var location = modelLocations.get(modelName);
        if (location == null) {
            logger.warn("Location not found for model name {}, assuming no reasoning-disable support.", modelName);
            return false;
        }
        var info = getModelInfo(location);
        if (info == null) {
            logger.warn("Model info not found for location {}, assuming no reasoning-disable support.", location);
            return false;
        }
        var v = info.get("supports_reasoning_disable");
        return v instanceof Boolean b && b;
    }

    public boolean supportsReasoningEffort(String modelName) {
        var location = modelLocations.get(modelName);
        if (location == null) {
            logger.warn("Location not found for model name {}, assuming no reasoning effort support.", modelName);
            return false;
        }
        return supportsReasoningEffortInternal(location);
    }

    private boolean supportsReasoningEffortInternal(String location) {
        var info = getModelInfo(location);
        if (info == null) {
            logger.warn("Model info not found for location {}, assuming no reasoning effort support.", location);
            return false;
        }

        return switch (info.get("supported_openai_params")) {
            case List<?> list -> list.stream().map(Object::toString).anyMatch("reasoning_effort"::equals);
            case null, default -> false;
        };
    }

    private boolean supportsReasoning(String location) {
        var info = getModelInfo(location);
        if (info == null) {
            logger.trace("Model info not found for location {}, assuming no reasoning support.", location);
            return false;
        }
        var supports = info.get("supports_reasoning");
        return supports instanceof Boolean boolVal && boolVal;
    }

    /** Retrieves or creates a StreamingChatModel for the given configuration. */
    public @Nullable StreamingChatModel getModel(
            ModelConfig config, @Nullable OpenAiChatRequestParameters.Builder parametersOverride) {
        @Nullable String location = modelLocations.get(config.name);
        logger.trace(
                "Creating new model instance for '{}' at location '{}' with reasoning '{}' via LiteLLM",
                config.name,
                location,
                config.reasoning);
        if (location == null) {
            logger.error("Location not found for model name: {}", config.name);
            return null;
        }

        var params = OpenAiChatRequestParameters.builder();

        String baseUrl = MainProject.getProxyUrl();
        var builder = OpenAiStreamingChatModel.builder()
                .logRequests(true)
                .logResponses(true)
                .strictJsonSchema(true)
                .baseUrl(baseUrl)
                .timeout(Duration.ofSeconds(
                        config.tier == ProcessingTier.FLEX
                                ? FLEX_FIRST_TOKEN_TIMEOUT_SECONDS
                                : Math.max(DEFAULT_FIRST_TOKEN_TIMEOUT_SECONDS, NEXT_TOKEN_TIMEOUT_SECONDS)));
        if (config.tier != ProcessingTier.DEFAULT) {
            builder.serviceTier(config.tier.toString().toLowerCase(Locale.ROOT));
        }
        params = params.maxCompletionTokens(getMaxOutputTokens(location));

        if (MainProject.getProxySetting() == MainProject.LlmProxySetting.LOCALHOST) {
            builder = builder.apiKey("dummy-key");
            params = params.user("e99a7c42-faf6-4139-9537-874c76928da4");
        } else {
            var kp = parseKey(MainProject.getBrokkKey());
            builder = builder.apiKey(kp.token()).customHeaders(Map.of("Authorization", "Bearer " + kp.token()));
            params = params.user(kp.userId().toString());
        }

        params = params.modelName(location);

        logger.trace("Applying reasoning effort {} to model {}", config.reasoning, config.name);
        if (supportsReasoningEffort(config.name) && config.reasoning != ReasoningLevel.DEFAULT) {
            params = params.reasoningEffort(config.reasoning.name().toLowerCase(Locale.ROOT));
        }
        if (parametersOverride != null) {
            params = params.overrideWith(parametersOverride.build());
        }
        builder.defaultRequestParameters(params.build());

        return builder.build();
    }

    public @Nullable StreamingChatModel getModel(String modelName) {
        return getModel(new ModelConfig(modelName, ReasoningLevel.DEFAULT));
    }

    public @Nullable StreamingChatModel getModel(ModelConfig config) {
        return getModel(config, null);
    }

    public boolean supportsJsonSchema(StreamingChatModel model) {
        var location = model.defaultRequestParameters().modelName();
        var info = getModelInfo(location);

        if (location.contains("gemini")) {
            return false;
        }

        if (location.contains("gpt-5")) {
            return false;
        }

        if (info == null) {
            logger.warn("Model info not found for location {}, assuming no JSON schema support.", location);
            return false;
        }
        var b = info.get("supports_response_schema");
        return b instanceof Boolean boolVal && boolVal;
    }

    public boolean isLazy(StreamingChatModel model) {
        String modelName = nameOf(model);
        return !(modelName.contains("sonnet") || modelName.contains("gemini-2.5-pro"));
    }

    public boolean requiresEmulatedTools(StreamingChatModel model) {
        if (Boolean.getBoolean("brokk.devmode")) {
            boolean force = MainProject.getForceToolEmulation();
            logger.debug("Dev mode enabled; requiresEmulatedTools overridden by setting: {}", force);
            return force;
        }

        var location = model.defaultRequestParameters().modelName();

        var info = getModelInfo(location);
        if (info == null) {
            logger.warn("Model info not found for location {}, assuming tool emulation required.", location);
            return true;
        }

        if (GLOBAL_FORCE_TOOL_EMULATION) {
            return true;
        }

        var b = info.get("supports_function_calling");
        return !(b instanceof Boolean bVal) || !bVal;
    }

    protected @Nullable Map<String, Object> getModelInfo(String location) {
        try {
            return modelInfoMap.get(location);
        } catch (NullPointerException e) {
            return null;
        }
    }

    public boolean supportsParallelCalls(StreamingChatModel model) {
        var location = model.defaultRequestParameters().modelName();
        var info = getModelInfo(location);
        if (info == null) {
            logger.warn("Model info not found for location {}, assuming no parallel tool call support.", location);
            return false;
        }

        return switch (info.get("supported_openai_params")) {
            case List<?> list -> list.stream().map(Object::toString).anyMatch("parallel_tool_calls"::equals);
            case null, default -> false;
        };
    }

    public boolean isReasoning(StreamingChatModel model) {
        var location = model.defaultRequestParameters().modelName();
        var supportsReasoning = supportsReasoning(location);
        if (!supportsReasoningEffortInternal(location)) {
            return supportsReasoning;
        }
        if (!(model instanceof OpenAiStreamingChatModel om)) {
            return false;
        }

        var effort = om.defaultRequestParameters().reasoningEffort();
        var locationLower = location.toLowerCase(Locale.ROOT);
        var isDisable = (locationLower.contains("sonnet") || locationLower.contains("opus"))
                ? effort == null || "disable".equalsIgnoreCase(effort)
                : "disable".equalsIgnoreCase(effort);
        return !isDisable;
    }

    public boolean isReasoning(ModelConfig config) {
        var modelName = config.name();
        var location = modelLocations.get(modelName);
        if (location == null) {
            return false;
        }

        var supportsReasoning = supportsReasoning(location);
        if (!supportsReasoningEffort(modelName)) {
            return supportsReasoning;
        }

        if (config.reasoning() == ReasoningLevel.DISABLE) {
            return false;
        }

        var lowerName = modelName.toLowerCase(Locale.ROOT);
        if (!lowerName.contains("sonnet")) {
            return true;
        }
        return config.reasoning() != ReasoningLevel.DEFAULT;
    }

    public boolean usesThinkTags(StreamingChatModel model) {
        var location = model.defaultRequestParameters().modelName();
        var info = getModelInfo(location);
        if (info == null) {
            logger.warn("Model info not found for location {}, assuming no think-tag usage.", location);
            return false;
        }
        var v = info.get("uses_think_tags");
        return v instanceof Boolean b && b;
    }

    public boolean supportsVision(StreamingChatModel model) {
        var location = model.defaultRequestParameters().modelName();
        var info = getModelInfo(location);
        if (info == null) {
            logger.warn("Model info not found for location {}, assuming no vision support.", location);
            return false;
        }

        var supports = info.get("supports_vision");
        return supports instanceof Boolean boolVal && boolVal;
    }

    /** Returns the configured processing tier for the given model (defaults to DEFAULT). */
    public static ProcessingTier getProcessingTier(StreamingChatModel model) {
        if (model instanceof OpenAiStreamingChatModel om) {
            var tier = om.defaultRequestParameters().serviceTier();
            if (tier == null) {
                return ProcessingTier.DEFAULT;
            }
            var normalized = tier.toLowerCase(Locale.ROOT);
            if ("flex".equals(normalized)) {
                return ProcessingTier.FLEX;
            } else if ("priority".equals(normalized)) {
                return ProcessingTier.PRIORITY;
            } else {
                return ProcessingTier.DEFAULT;
            }
        }
        return ProcessingTier.DEFAULT;
    }

    /**
     * Returns true if the named model is marked as eligible for the free tier.
     */
    public boolean isFreeTier(String modelName) {
        var location = modelLocations.get(modelName);
        if (location == null) {
            logger.warn("Location not found for model name {}, assuming not free-tier.", modelName);
            return false;
        }
        var info = getModelInfo(location);
        if (info == null) {
            logger.warn("Model info not found for location {}, assuming not free-tier.", location);
            return false;
        }
        var v = info.get("free_tier_eligible");
        return v instanceof Boolean b && b;
    }

    public StreamingChatModel quickestModel() {
        return quickestModel;
    }

    public StreamingChatModel quickModel() {
        return quickModel;
    }

    public StreamingChatModel quickEditModel() {
        return quickEditModel;
    }

    public SpeechToTextModel sttModel() {
        return sttModel;
    }

    public StreamingChatModel getScanModel() {
        var modelName = modelLocations.containsKey(GEMINI_2_5_FLASH) ? GEMINI_2_5_FLASH : GPT_5_MINI;
        var model = getModel(new ModelConfig(modelName, ReasoningLevel.DEFAULT));
        if (model == null) {
            logger.error("Failed to get scan model '{}'", modelName);
            return new UnavailableStreamingModel();
        }
        return model;
    }

    public boolean hasSttModel() {
        return !(sttModel instanceof UnavailableSTT);
    }

    public boolean isOnline() {
        boolean hasUsableModel = modelLocations.keySet().stream().anyMatch(name -> !UNAVAILABLE.equals(name));
        boolean quickModelAvailable = !(quickModel instanceof UnavailableStreamingModel);
        return hasUsableModel && quickModelAvailable;
    }

    /** Interface for speech-to-text operations. */
    public interface SpeechToTextModel {
        String transcribe(java.nio.file.Path audioFile, java.util.Set<String> symbols) throws java.io.IOException;
    }

    /** Stubbed STT model when speech-to-text is unavailable. */
    public static class UnavailableSTT implements SpeechToTextModel {
        @Override
        public String transcribe(java.nio.file.Path audioFile, java.util.Set<String> symbols) {
            return "Speech-to-text is unavailable (no suitable model found via proxy or connection failed).";
        }
    }

    /** Unavailable StreamingChatModel stub. */
    public static class UnavailableStreamingModel implements StreamingChatModel {
        public UnavailableStreamingModel() {}

        public void generate(String userMessage, StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new dev.langchain4j.model.output.Response<>(new AiMessage(UNAVAILABLE)));
        }

        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new dev.langchain4j.model.output.Response<>(new AiMessage(UNAVAILABLE)));
        }

        public void generate(
                List<ChatMessage> messages,
                List<ToolSpecification> toolSpecifications,
                StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new dev.langchain4j.model.output.Response<>(new AiMessage(UNAVAILABLE)));
        }

        public void generate(
                List<ChatMessage> messages,
                ToolSpecification toolSpecification,
                StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new dev.langchain4j.model.output.Response<>(new AiMessage(UNAVAILABLE)));
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof UnavailableStreamingModel;
        }
    }
}
