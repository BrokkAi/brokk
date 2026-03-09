package ai.brokk;

import static java.lang.Math.max;
import static java.lang.Math.min;

import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.project.ModelProperties.ModelType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.google.errorprone.annotations.Immutable;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
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

    public static final String UNAVAILABLE = "AI is unavailable";

    protected final Logger logger = LogManager.getLogger(AbstractService.class);
    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected final IProject project;

    // display name -> location (still used for STT and internal tracking)
    // display name -> location (still used for STT and internal tracking)
    protected Map<String, String> modelLocations = Map.of(UNAVAILABLE, "not_a_model");
    // model name -> model info (inner map is also immutable)
    protected Map<String, Map<String, Object>> modelInfoMap = Map.of();

    // Special models
    protected SpeechToTextModel sttModel = new UnavailableSTT();

    public AbstractService(IProject project) {
        // Intentionally minimal: no network calls here
        this.project = project;
    }

    public abstract float getUserBalance() throws IOException;

    public abstract void sendFeedback(
            String category, String feedbackText, boolean includeDebugLog, @Nullable File screenshotFile)
            throws IOException;

    public boolean supportsPrefixCache(StreamingChatModel model) {
        if (model instanceof OfflineStreamingModel) {
            // lets tests exercise cache pinning
            return true;
        }
        var name = nameOf(model);
        return (name.startsWith("gpt") || name.startsWith("gemini") || name.startsWith("deepseek"));
    }

    public interface Provider {
        AbstractService get();

        void reinit(IProject project);
    }

    // Helper record to store model name and reasoning level for checking
    @Immutable
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
            double outputCostPerToken,
            double cacheCreationCostPerToken) {
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

        public double getCostFor(long uncachedInputTokens, long cachedTokens, long outputTokens) {
            return getCostFor(uncachedInputTokens, cachedTokens, outputTokens, 0);
        }

        public double getCostFor(
                long uncachedInputTokens, long cachedTokens, long outputTokens, long cacheCreationTokens) {
            var promptTokens = uncachedInputTokens + cachedTokens + cacheCreationTokens;
            var band = bandFor(promptTokens);
            return uncachedInputTokens * band.inputCostPerToken()
                    + cachedTokens * band.cachedInputCostPerToken()
                    + outputTokens * band.outputCostPerToken()
                    + cacheCreationTokens * band.cacheCreationCostPerToken();
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

        public static ProcessingTier fromString(@Nullable String value) {
            if (value == null) {
                return DEFAULT;
            }
            if ("priority".equalsIgnoreCase(value)) {
                return PRIORITY;
            }
            if ("flex".equalsIgnoreCase(value)) {
                return FLEX;
            }
            return DEFAULT;
        }

        /** Returns the API string value, or null for DEFAULT (no tier override). */
        public @Nullable String toApiString() {
            return this == DEFAULT ? null : name().toLowerCase(Locale.ROOT);
        }

        /** Returns the cost multiplier label for display (e.g., "2x", "0.5x"), or empty for DEFAULT. */
        public String getMultiplierLabel() {
            return switch (this) {
                case PRIORITY -> "2x";
                case FLEX -> "0.5x";
                default -> "";
            };
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TierPricing(
            double input_cost_per_token,
            double output_cost_per_token,
            double cache_read_input_token_cost,
            @Nullable Double cache_creation_input_token_cost) {
        public double cacheCreationCost() {
            return cache_creation_input_token_cost != null ? cache_creation_input_token_cost : input_cost_per_token;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PricingTiers(
            @Nullable TierPricing standard,
            @Nullable TierPricing priority,
            @Nullable TierPricing flex,
            @Nullable TierPricing batch) {
        public boolean supportsPriority() {
            return priority != null;
        }

        public boolean supportsFlex() {
            return flex != null;
        }

        public boolean supportsBatch() {
            return batch != null;
        }

        public @Nullable TierPricing getTierPricing(ProcessingTier tier) {
            return switch (tier) {
                case PRIORITY -> priority;
                case FLEX -> flex;
                case DEFAULT -> standard;
            };
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
    public record KeyParts(UUID userId, String token) {
        private static final KeyParts DUMMY = new KeyParts(new UUID(0, 0), "dummy-key");
    }

    /** Represents a cost estimate with both the raw value and formatted string. */
    public record CostEstimate(double cost, String formatted) {}

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
        var info = getModelInfo(modelName);
        if (info.isEmpty()) {
            logger.warn("Model info not found for name {}, cannot get prices.", modelName);
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
        var cacheCreationCostVal = info.get("cache_creation_input_token_cost");
        var cacheCreationCost = cacheCreationCostVal == null ? inputCost : tryDouble.apply(cacheCreationCostVal);

        var inputAbove200k = info.get("input_cost_per_token_above_200k_tokens");
        var cachedInputAbove200k = info.get("cache_read_input_token_cost_above_200k_tokens");
        var outputAbove200k = info.get("output_cost_per_token_above_200k_tokens");
        var cacheCreationAbove200k = info.get("cache_creation_input_token_cost_above_200k_tokens");
        boolean hasAbove200k = inputAbove200k != null
                || cachedInputAbove200k != null
                || outputAbove200k != null
                || cacheCreationAbove200k != null;

        if (hasAbove200k) {
            var band1 = new PriceBand(0, 199_999, inputCost, cachedInputCost, outputCost, cacheCreationCost);
            var band2 = new PriceBand(
                    200_000,
                    Long.MAX_VALUE,
                    inputAbove200k == null ? inputCost : tryDouble.apply(inputAbove200k),
                    cachedInputAbove200k == null ? cachedInputCost : tryDouble.apply(cachedInputAbove200k),
                    outputAbove200k == null ? outputCost : tryDouble.apply(outputAbove200k),
                    cacheCreationAbove200k == null ? cacheCreationCost : tryDouble.apply(cacheCreationAbove200k));
            return new ModelPricing(List.of(band1, band2));
        } else {
            var band = new PriceBand(0, Long.MAX_VALUE, inputCost, cachedInputCost, outputCost, cacheCreationCost);
            return new ModelPricing(List.of(band));
        }
    }

    public ModelPricing getModelPricing(String modelName, ProcessingTier tier) {
        var info = getModelInfo(modelName);
        var pricingTiers = info.get("pricing_tiers");

        if (pricingTiers instanceof PricingTiers pt) {
            var tp = pt.getTierPricing(tier);
            if (tp != null) {
                var band = new PriceBand(
                        0,
                        Long.MAX_VALUE,
                        tp.input_cost_per_token(),
                        tp.cache_read_input_token_cost(),
                        tp.output_cost_per_token(),
                        tp.cacheCreationCost());
                return new ModelPricing(List.of(band));
            }
        }
        return getModelPricing(modelName);
    }

    /**
     * Estimates the cost for a request given the model config and input token count.
     * Assumes output is min(4000, inputTokens/2), plus 1000 for reasoning models.
     */
    public CostEstimate estimateCost(ModelConfig config, long inputTokens) {
        long estimatedOutputTokens = Math.min(4000, inputTokens / 2);
        if (isReasoning(config)) {
            estimatedOutputTokens += 1000;
        }
        return estimateCost(config, inputTokens, estimatedOutputTokens);
    }

    /**
     * Estimates the cost for a request with explicit output token count.
     */
    public CostEstimate estimateCost(ModelConfig config, long inputTokens, long outputTokens) {
        var pricing = getModelPricing(config.name(), config.tier());
        if (pricing.bands().isEmpty()) {
            return new CostEstimate(0, "");
        }

        double cost = pricing.getCostFor(inputTokens, 0, outputTokens);

        if (isFreeTier(config.name())) {
            return new CostEstimate(0, "$0.00 (Free Tier)");
        }
        return new CostEstimate(cost, String.format("$%.2f", cost));
    }

    /** Returns the display name for a given model instance */
    public String nameOf(StreamingChatModel model) {
        return model.defaultRequestParameters().modelName();
    }

    /**
     * Gets a map of available model names to their full location strings, suitable for display.
     * Filters out internal/utility models like flash-lite.
     */
    public Map<String, String> getAvailableModels() {
        boolean codexConnected = MainProject.isOpenAiCodexOauthConnected();
        return modelInfoMap.keySet().stream()
                .filter(name -> !UNAVAILABLE.equals(name))
                .filter(name -> !ModelProperties.SYSTEM_ONLY_MODELS.contains(name))
                .filter(name -> codexConnected || !isCodexModel(name))
                .collect(Collectors.toMap(name -> name, name -> modelLocations.getOrDefault(name, name)));
    }

    /**
     * Checks if the given model name corresponds to a Codex model.
     * Returns false if the model is unknown or does not have is_codex=true.
     */
    public boolean isCodexModel(String modelName) {
        var info = getModelInfo(modelName);
        var isCodex = info.get("is_codex");
        return isCodex instanceof Boolean b && b;
    }

    /**
     * Retrieves the maximum output tokens for the given model name.
     */
    private int getMaxOutputTokens(String modelName) {
        var info = getModelInfo(modelName);

        Integer value;
        if (!info.containsKey("max_output_tokens")) {
            logger.warn("max_output_tokens not found for model: {}", modelName);
            value = 8192;
        } else {
            value = (Integer) info.get("max_output_tokens");
        }

        int ceiling = min(value, getMaxInputTokens(modelName) / 8);
        int floor = min(8192, value);
        return max(floor, ceiling);
    }

    /**
     * Retrieves the maximum input tokens for the given model.
     */
    public int getMaxInputTokens(StreamingChatModel model) {
        return getMaxInputTokens(nameOf(model));
    }

    private int getMaxInputTokens(String modelName) {
        var info = getModelInfo(modelName);
        if (!info.containsKey("max_input_tokens")) {
            logger.warn("max_input_tokens not found for model: {}", modelName);
            return 65536;
        }
        var value = info.get("max_input_tokens");
        assert value instanceof Integer;
        return (Integer) value;
    }

    /**
     * Retrieves the maximum concurrent requests for the given model instance. Returns null if unavailable.
     */
    public @Nullable Integer getMaxConcurrentRequests(StreamingChatModel model) {
        var info = getModelInfo(nameOf(model));
        if (!info.containsKey("max_concurrent_requests")) {
            return null;
        }
        return (Integer) info.get("max_concurrent_requests");
    }

    /** Retrieves the tokens per minute for the given model. Returns null if unavailable. */
    public @Nullable Integer getTokensPerMinute(StreamingChatModel model) {
        var info = getModelInfo(nameOf(model));
        if (!info.containsKey("tokens_per_minute")) {
            return null;
        }
        return (Integer) info.get("tokens_per_minute");
    }

    public boolean supportsToolChoiceRequired(StreamingChatModel model) {
        var modelName = nameOf(model);
        var info = getModelInfo(modelName);
        if (!info.containsKey("supports_tool_choice")) {
            return false;
        }

        return (Boolean) info.get("supports_tool_choice");
    }

    public boolean supportsProcessingTier(String modelName) {
        var info = getModelInfo(modelName);
        var pricingTiers = info.get("pricing_tiers");
        if (!(pricingTiers instanceof PricingTiers pt)) {
            return false;
        }
        return pt.supportsPriority();
    }

    public boolean supportsReasoningDisable(String modelName) {
        var info = getModelInfo(modelName);
        if (info.isEmpty()) {
            logger.warn("Model info not found for name {}, assuming no reasoning-disable support.", modelName);
            return false;
        }
        var v = info.get("supports_reasoning_disable");
        return v instanceof Boolean b && b;
    }

    public boolean supportsReasoningEffort(String modelName) {
        var info = getModelInfo(modelName);
        if (info.isEmpty()) {
            logger.trace("Model info not found for name {}, assuming no reasoning effort support.", modelName);
            return false;
        }

        Object params = info.get("supported_openai_params");
        if (params instanceof List<?> list) {
            return list.stream().map(Object::toString).anyMatch("reasoning_effort"::equals);
        }
        return false;
    }

    private boolean supportsReasoning(String modelName) {
        var info = getModelInfo(modelName);
        if (info.isEmpty()) {
            logger.trace("Model info not found for name {}, assuming no reasoning support.", modelName);
            return false;
        }
        var supports = info.get("supports_reasoning");
        return supports instanceof Boolean boolVal && boolVal;
    }

    /** Retrieves or creates a StreamingChatModel for the given configuration. */
    public @Nullable StreamingChatModel getModel(
            ModelConfig config, @Nullable OpenAiChatRequestParameters.Builder parametersOverride) {
        logger.trace(
                "Creating new model instance for '{}' with reasoning '{}' via LiteLLM", config.name, config.reasoning);

        var params = OpenAiChatRequestParameters.builder();
        String baseUrl = MainProject.getProxyUrl();
        var split = config.name.split("/", -1);
        var shortName = split[split.length - 1];

        String brokkKey = MainProject.getBrokkKey();
        var kp = !brokkKey.isBlank() && brokkKey.contains("+") ? parseKey(brokkKey) : KeyParts.DUMMY;

        var builder = OpenAiStreamingChatModel.builder()
                .logRequests(true)
                .logResponses(true)
                .strictJsonSchema(true)
                .baseUrl(baseUrl)
                .apiKey(kp.token())
                // this is the only custom header we can set from the client, brokk-llm discards others;
                // in particular, anthropic-beta should be set by proxy.
                .customHeaders(Map.of("Authorization", "Bearer " + kp.token()))
                .promptCacheKey(shortName + kp.userId())
                .timeout(Duration.ofSeconds(
                        config.tier == ProcessingTier.FLEX
                                ? FLEX_FIRST_TOKEN_TIMEOUT_SECONDS
                                : Math.max(DEFAULT_FIRST_TOKEN_TIMEOUT_SECONDS, NEXT_TOKEN_TIMEOUT_SECONDS)));
        if (supportsProcessingTier(config.name)) {
            params = params.serviceTier(config.tier);
        }
        params = params.maxCompletionTokens(getMaxOutputTokens(config.name));
        params = params.user(kp.userId().toString());

        params = params.modelName(config.name);

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

    public @Nullable StreamingChatModel getModel(ModelConfig config) {
        return getModel(config, null);
    }

    public boolean supportsJsonSchema(StreamingChatModel model) {
        var name = nameOf(model);
        var info = getModelInfo(name);

        if (name.contains("gemini")) {
            return false;
        }

        if (name.contains("gpt-5")) {
            return false;
        }

        if (info.isEmpty()) {
            logger.warn("Model info not found for name {}, assuming no JSON schema support.", name);
            return false;
        }
        var b = info.get("supports_response_schema");
        return b instanceof Boolean boolVal && boolVal;
    }

    public boolean isLazy(StreamingChatModel model) {
        String modelName = nameOf(model);
        return modelName.contains("haiku") || modelName.contains("flash");
    }

    protected Map<String, Object> getModelInfo(String modelName) {
        var info = modelInfoMap.get(modelName);
        if (info != null) {
            return info;
        }
        // Fallback: some callers might pass the location instead of the name in legacy tests
        // or if modelInfoMap is keyed by location and we haven't reached Service.java yet.
        return modelInfoMap.values().stream()
                .filter(m -> modelName.equals(m.get("model_location")))
                .findFirst()
                .orElse(Map.of());
    }

    public boolean supportsParallelCalls(StreamingChatModel model) {
        var name = nameOf(model);
        var info = getModelInfo(name);
        if (info.isEmpty()) {
            logger.trace("Model info not found for name {}, assuming no parallel tool call support.", name);
            return false;
        }

        Object params = info.get("supported_openai_params");
        if (params instanceof List<?> list) {
            return list.stream().map(Object::toString).anyMatch("parallel_tool_calls"::equals);
        }
        return false;
    }

    public boolean supportsTemperature(String modelName) {
        var info = getModelInfo(modelName);
        if (info.isEmpty()) {
            logger.warn("Model info not found for name {}, assuming no temperature support.", modelName);
            return false;
        }

        Object params = info.get("supported_openai_params");
        if (params instanceof List<?> list) {
            return list.stream().map(Object::toString).anyMatch("temperature"::equals);
        }

        return false;
    }

    public boolean isReasoning(StreamingChatModel model) {
        var name = nameOf(model);
        var supportsReasoning = supportsReasoning(name);
        if (!supportsReasoningEffort(name)) {
            return supportsReasoning;
        }
        if (!(model instanceof OpenAiStreamingChatModel om)) {
            return false;
        }

        var effort = om.defaultRequestParameters().reasoningEffort();
        var lowerName = name.toLowerCase(Locale.ROOT);
        var isDisable = (lowerName.contains("sonnet") || lowerName.contains("opus"))
                ? effort == null || "disable".equalsIgnoreCase(effort)
                : "disable".equalsIgnoreCase(effort);
        return !isDisable;
    }

    public boolean isReasoning(ModelConfig config) {
        var modelName = config.name();
        var supportsReasoning = supportsReasoning(modelName);
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
        var name = nameOf(model);
        var info = getModelInfo(name);
        if (info.isEmpty()) {
            logger.warn("Model info not found for name {}, assuming no think-tag usage.", name);
            return false;
        }
        var v = info.get("uses_think_tags");
        return v instanceof Boolean b && b;
    }

    public boolean supportsVision(StreamingChatModel model) {
        var name = nameOf(model);
        var info = getModelInfo(name);
        if (info.isEmpty()) {
            logger.warn("Model info not found for name {}, assuming no vision support.", name);
            return false;
        }

        var supports = info.get("supports_vision");
        return supports instanceof Boolean boolVal && boolVal;
    }

    /** Returns the configured processing tier for the given model (defaults to DEFAULT). */
    public static ProcessingTier getProcessingTier(StreamingChatModel model) {
        if (model instanceof OpenAiStreamingChatModel om) {
            var tier = om.defaultRequestParameters().serviceTier();
            return tier != null ? tier : ProcessingTier.DEFAULT;
        }
        return ProcessingTier.DEFAULT;
    }

    /**
     * Returns true if the named model is marked as eligible for the free tier.
     */
    public boolean isFreeTier(String modelName) {
        var info = getModelInfo(modelName);
        if (info.isEmpty()) {
            logger.warn("Model info not found for name {}, assuming not free-tier.", modelName);
            return false;
        }
        var v = info.get("free_tier_eligible");
        return v instanceof Boolean b && b;
    }

    public boolean isFreeTier(StreamingChatModel model) {
        return isFreeTier(nameOf(model));
    }

    public StreamingChatModel quickestModel() {
        return getModel(ModelType.QUICKEST);
    }

    public StreamingChatModel summarizeModel() {
        return getModel(ModelType.SUMMARIZE);
    }

    public StreamingChatModel quickEditModel() {
        return getModel(ModelType.QUICK_EDIT);
    }

    public SpeechToTextModel sttModel() {
        return sttModel;
    }

    public StreamingChatModel getScanModel() {
        return getModel(ModelType.SCAN);
    }

    public StreamingChatModel getModel(ModelType type) {
        var cfg = project.getModelConfig(type);
        var model = getModel(cfg);
        if (model != null) {
            return model;
        }

        cfg = type.defaultConfig();
        model = getModel(cfg);
        if (model != null) {
            return model;
        }

        cfg = type.freeConfig();
        model = getModel(cfg);
        if (model != null) {
            return model;
        }

        return new OfflineStreamingModel();
    }

    public boolean hasSttModel() {
        return !(sttModel instanceof UnavailableSTT);
    }

    public boolean isOnline() {
        boolean hasUsableModel = modelLocations.keySet().stream().anyMatch(name -> !UNAVAILABLE.equals(name));
        boolean quickestModelAvailable = !(quickestModel() instanceof OfflineStreamingModel);
        return hasUsableModel && quickestModelAvailable;
    }

    /** Interface for speech-to-text operations. */
    public interface SpeechToTextModel {
        String transcribe(Path audioFile, Set<String> symbols) throws IOException;
    }

    /** Stubbed STT model when speech-to-text is unavailable. */
    public static class UnavailableSTT implements SpeechToTextModel {
        @Override
        public String transcribe(Path audioFile, Set<String> symbols) {
            return "Speech-to-text is unavailable (no suitable model found via proxy or connection failed).";
        }
    }

    /** Unavailable StreamingChatModel stub. */
    public static class OfflineStreamingModel implements StreamingChatModel {
        private final OpenAiChatRequestParameters params;

        public OfflineStreamingModel() {
            this.params = OpenAiChatRequestParameters.builder()
                    .modelName("offline_model")
                    .build();
        }

        @Override
        public OpenAiChatRequestParameters defaultRequestParameters() {
            return params;
        }

        public void generate(String userMessage, StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new Response<>(new AiMessage(UNAVAILABLE)));
        }

        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new Response<>(new AiMessage(UNAVAILABLE)));
        }

        public void generate(
                List<ChatMessage> messages,
                List<ToolSpecification> toolSpecifications,
                StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new Response<>(new AiMessage(UNAVAILABLE)));
        }

        public void generate(
                List<ChatMessage> messages,
                ToolSpecification toolSpecification,
                StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new Response<>(new AiMessage(UNAVAILABLE)));
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof OfflineStreamingModel;
        }
    }
}
