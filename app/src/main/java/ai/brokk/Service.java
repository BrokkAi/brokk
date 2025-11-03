package ai.brokk;

import static java.lang.Math.max;
import static java.lang.Math.min;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Manages dynamically loaded models via LiteLLM. This is intended to be immutable -- we handle changes by wrapping this
 * in a ServiceWrapper that knows how to reload the Service.
 */
public class Service implements IExceptionReportingService {
    public static final String TOP_UP_URL = "https://brokk.ai/dashboard";
    public static float MINIMUM_PAID_BALANCE = 0.20f;
    public static float LOW_BALANCE_WARN_AT = 2.00f;

    public static final long FLEX_FIRST_TOKEN_TIMEOUT_SECONDS = 15L * 60L; // 15 minutes
    public static final long DEFAULT_FIRST_TOKEN_TIMEOUT_SECONDS = 2L * 60L; // 2 minutes
    // we tried setting this to 60s but the transition from thinking to final response can get long
    public static final long NEXT_TOKEN_TIMEOUT_SECONDS = DEFAULT_FIRST_TOKEN_TIMEOUT_SECONDS;

    public static final boolean GLOBAL_FORCE_TOOL_EMULATION = true;

    // Helper record to store model name and reasoning level for checking
    public record ModelConfig(String name, ReasoningLevel reasoning, ProcessingTier tier) {
        public ModelConfig(String name, ReasoningLevel reasoning) {
            this(name, reasoning, ProcessingTier.DEFAULT);
        }

        public ModelConfig(String name) {
            this(name, ReasoningLevel.DEFAULT);
        }

        public static ModelConfig from(StreamingChatModel model, Service svc) {
            var canonicalName = svc.nameOf(model);
            var tier = Service.getProcessingTier(model);

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

        // Helper to get a double or 0.0 if null or wrong type
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
            // Two-tier pricing: <= 200k and > 200k tokens
            var band1 = new PriceBand(0, 199_999, inputCost, cachedInputCost, outputCost);
            var band2 = new PriceBand(
                    200_000,
                    Long.MAX_VALUE,
                    inputAbove200k == null ? inputCost : tryDouble.apply(inputAbove200k),
                    cachedInputAbove200k == null ? cachedInputCost : tryDouble.apply(cachedInputAbove200k),
                    outputAbove200k == null ? outputCost : tryDouble.apply(outputAbove200k));
            return new ModelPricing(List.of(band1, band2));
        } else {
            // Single-tier pricing
            var band = new PriceBand(0, Long.MAX_VALUE, inputCost, cachedInputCost, outputCost);
            return new ModelPricing(List.of(band));
        }
    }

    public enum ProcessingTier {
        DEFAULT,
        PRIORITY,
        FLEX;

        @Override
        public String toString() {
            // Capitalize first letter for display
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
            // Capitalize first letter for display
            return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        }

        /** Converts a String to a ReasoningLevel, falling back to the provided default. */
        public static ReasoningLevel fromString(String value, ReasoningLevel defaultLevel) {
            try {
                return ReasoningLevel.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return defaultLevel; // Fallback to provided default if string is invalid
            }
        }
    }

    /** Represents the parsed Brokk API key components. */
    public record KeyParts(UUID userId, String token) {}

    /** Represents a user-defined favorite model alias. */
    public record FavoriteModel(String alias, ModelConfig config) {}

    /**
     * Parses a Brokk API key of the form 'brk+<userId>+<proToken>+<freeToken>'. The userId must be a valid UUID. The
     * `sk-` prefix is added implicitly to tokens.
     *
     * @param key the raw key string
     * @return KeyParts containing userId, proToken, and freeToken
     * @throws IllegalArgumentException if the key is invalid
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

        // Tokens no longer have sk- prefix in the raw key, prepend it here
        return new KeyParts(userId, "sk-" + parts.get(2));
    }

    private final Logger logger = LogManager.getLogger(Service.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    // Share OkHttpClient across instances for efficiency
    // Model name constants
    public static final String GPT_5 = "gpt-5";

    public static final String GEMINI_2_5_PRO = "gemini-2.5-pro";
    public static final String GEMINI_2_0_FLASH = "gemini-2.0-flash";
    public static final String GEMINI_2_5_FLASH = "gemini-2.5-flash";
    public static final String GPT_5_MINI = "gpt-5-mini";

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    public static final String UNAVAILABLE = "AI is unavailable";

    // these models are defined for low-ltency use cases that don't require high intelligence,
    // they are not suitable for writing code
    private static final Set<String> SYSTEM_ONLY_MODELS = Set.of("gemini-2.0-flash-lite", "gpt-4.1-nano");

    // display name -> location
    private final Map<String, String> modelLocations;
    // location -> model info (inner map is also immutable)
    private final Map<String, Map<String, Object>> modelInfoMap;

    // Default models - now instance fields
    private final StreamingChatModel quickModel;
    private final StreamingChatModel quickestModel;
    private final StreamingChatModel quickEditModel;
    private final SpeechToTextModel sttModel;

    public Service(IProject project) {
        // Get and handle data retention policy
        var policy = project.getDataRetentionPolicy();
        if (policy == MainProject.DataRetentionPolicy.UNSET) {
            logger.warn(
                    "Data Retention Policy is UNSET for project {}. Defaulting to MINIMAL.",
                    project.getRoot().getFileName());
            policy = MainProject.DataRetentionPolicy.MINIMAL;
        }

        String proxyUrl = MainProject.getProxyUrl();
        logger.info("Initializing models using policy: {} and proxy: {}", policy, proxyUrl);

        var tempModelLocations = new ConcurrentHashMap<String, String>();
        var tempModelInfoMap = new ConcurrentHashMap<String, Map<String, Object>>();

        try {
            fetchAvailableModels(policy, tempModelLocations, tempModelInfoMap);
        } catch (IOException e) {
            logger.error("Failed to connect to LiteLLM at {} or parse response: {}", proxyUrl, e.getMessage(), e);
            // tempModelLocations and tempModelInfoMap will be cleared by fetchAvailableModels in this case
        }

        if (tempModelLocations.isEmpty()) {
            logger.warn("No chat models available");
            tempModelLocations.put(UNAVAILABLE, "not_a_model");
        }

        this.modelLocations = Map.copyOf(tempModelLocations);
        this.modelInfoMap = Map.copyOf(tempModelInfoMap);

        // these should always be available
        var qm = getModel(new ModelConfig(GEMINI_2_0_FLASH, ReasoningLevel.DEFAULT));
        quickModel = qm == null ? new UnavailableStreamingModel() : qm;

        // Determine whether the user is on a free tier (balance < MINIMUM_PAID_BALANCE)
        boolean freeTier = false;
        try {
            float balance = getUserBalance();
            freeTier = balance < MINIMUM_PAID_BALANCE;
            logger.info("User balance = {}, free‑tier = {}", balance, freeTier);
        } catch (IOException | IllegalArgumentException e) {
            // If we cannot determine balance (network error or invalid/missing key), assume paid tier
            // to avoid silently disabling the Cerebras model
            logger.warn("Unable to fetch user balance for quick‑edit model selection: {}", e.getMessage());
        }

        // If on free tier, fall back to the quickModel; otherwise attempt to load the Cerebras model
        if (freeTier) {
            logger.info("Free tier detected – using quickModel for quick‑edit operations.");
            quickEditModel = quickModel;
        } else {
            var qe = getModel(new ModelConfig("cerebras/gpt-oss-120b", ReasoningLevel.DEFAULT));
            quickEditModel = qe == null ? qm : qe;
        }

        // hard‑code quickest temperature to 0 so that Quick Context inference is reproducible
        var qqm = getModel(
                new ModelConfig("gemini-2.0-flash-lite", ReasoningLevel.DEFAULT),
                OpenAiChatRequestParameters.builder().temperature(0.0));
        quickestModel = qqm == null ? new UnavailableStreamingModel() : qqm;

        // STT model initialization
        var sttLocation = modelInfoMap.entrySet().stream()
                .filter(entry -> "audio_transcription".equals(entry.getValue().get("mode")))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);

        if (sttLocation == null) {
            logger.warn("No suitable transcription model found via LiteLLM proxy. STT will be unavailable.");
            sttModel = new UnavailableSTT();
        } else {
            logger.info("Found transcription model at {}", sttLocation);
            sttModel = new OpenAIStt(sttLocation);
        }
    }

    /** Returns the display name for a given model instance */
    public String nameOf(StreamingChatModel model) {
        // langchain4j "name" corresponds to our "location"
        var location = model.defaultRequestParameters().modelName();
        // Find the key (display name) in the modelLocations map corresponding to the location value
        return modelLocations.entrySet().stream()
                .filter(entry -> entry.getValue().equals(location))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Model location not found in known models: " + location));
    }

    public float getUserBalance() throws IOException {
        return getUserBalance(MainProject.getBrokkKey());
    }

    /**
     * Fetches the user's balance for the given Brokk API key.
     *
     * @param key The Brokk API key.
     * @return The user's balance.
     * @throws IOException If a network error occurs or the API response is invalid.
     * @throws IllegalArgumentException if the key is invalid.
     */
    public static float getUserBalance(String key) throws IOException {
        parseKey(key); // Throws IllegalArgumentException if key is malformed

        String url = MainProject.getServiceUrl() + "/api/payments/balance-lookup/" + key;
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                if (response.code() == 401) { // HTTP 401 Unauthorized
                    throw new IllegalArgumentException("Invalid Brokk Key (Unauthorized from server): " + errorBody);
                }
                // For other non-successful responses
                throw new IOException("Failed to fetch user balance: " + response.code() + " - " + errorBody);
            }
            // Successful response processing
            String responseBody = response.body() != null ? response.body().string() : "";
            var objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(responseBody);
            if (rootNode.has("available_balance")
                    && rootNode.get("available_balance").isNumber()) {
                return rootNode.get("available_balance").floatValue();
            } else if (rootNode.isNumber()) {
                return rootNode.floatValue();
            } else {
                throw new IOException("Unexpected balance response format: " + responseBody);
            }
        }
    }

    /**
     * Checks if data sharing is allowed for the organization associated with the given Brokk API key. Defaults to true
     * (sharing allowed) if the key is invalid or the request fails.
     *
     * @param key The Brokk API key.
     * @return True if data sharing is allowed or cannot be determined, false otherwise.
     */
    public static boolean getDataShareAllowed(String key) {
        try {
            parseKey(key); // Validate key format first
        } catch (IllegalArgumentException e) {
            // Invalid key format, cannot fetch org policy, assume allowed.
            LogManager.getLogger(Service.class)
                    .debug("Invalid key format, cannot fetch data sharing status. Assuming allowed.", e);
            return true;
        }

        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        String url = MainProject.getServiceUrl() + "/api/users/check-data-sharing?brokk_key=" + encodedKey;
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                LogManager.getLogger(Service.class)
                        .warn(
                                "Failed to fetch data sharing status (HTTP {}): {}. Assuming allowed.",
                                response.code(),
                                errorBody);
                return true; // Assume allowed if request fails
            }
            String responseBody = response.body() != null ? response.body().string() : "";
            try {
                JsonNode rootNode = new ObjectMapper().readValue(responseBody, JsonNode.class);
                if (rootNode.has("data_sharing_enabled")
                        && rootNode.get("data_sharing_enabled").isBoolean()) {
                    return rootNode.get("data_sharing_enabled").asBoolean();
                } else {
                    LogManager.getLogger(Service.class)
                            .warn(
                                    "Data sharing status response did not contain 'data_sharing_enabled' boolean field: {}. Assuming allowed.",
                                    responseBody);
                    return true; // Assume allowed if field is missing or not a boolean
                }
            } catch (JsonProcessingException e) {
                LogManager.getLogger(Service.class)
                        .warn(
                                "Failed to parse data sharing status JSON response: {}. Assuming allowed.",
                                responseBody,
                                e);
                return true; // Assume allowed if JSON parsing fails
            }
        } catch (IOException e) {
            LogManager.getLogger(Service.class)
                    .warn("IOException while fetching data sharing status. Assuming allowed.", e);
            return true; // Assume allowed if network error
        }
    }

    public static void validateKey(String key) throws IOException {
        parseKey(key);
        getUserBalance(key);
        // We don't need to validate getDataShareAllowed here as it has built-in fallbacks
        // and is not critical for key validation in the same way balance is.
    }

    /**
     * Fetches available models from the LLM proxy, populates the provided maps, and applies filters.
     *
     * @param policy The data retention policy.
     * @param locationsTarget The map to populate with model display names to locations.
     * @param infoTarget The map to populate with model locations to their info.
     * @throws IOException If network or parsing errors occur.
     */
    protected void fetchAvailableModels(
            MainProject.DataRetentionPolicy policy,
            Map<String, String> locationsTarget,
            Map<String, Map<String, Object>> infoTarget)
            throws IOException {
        locationsTarget.clear(); // Clear at the beginning of an attempt
        infoTarget.clear();

        String baseUrl = MainProject.getProxyUrl(); // Get full URL (including scheme) from project settings
        boolean isBrokk = MainProject.getProxySetting() != MainProject.LlmProxySetting.LOCALHOST;
        boolean isFreeTierOnly = false;

        var authHeader = "Bearer dummy-key";
        if (isBrokk) {
            String brokkKey = MainProject.getBrokkKey();
            if (brokkKey.isEmpty()) {
                logger.warn("Brokk API key is empty, cannot fetch models from Brokk proxy");
                return;
            }
            var kp = parseKey(brokkKey);
            authHeader = "Bearer " + kp.token();
        }
        Request request = new Request.Builder()
                .url(baseUrl + "/model/info")
                .header("Authorization", authHeader)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                throw new IOException("Failed to fetch model info: " + response.code() + " " + response.message()
                        + " - " + errorBody);
            }

            ResponseBody responseBodyObj = response.body();
            if (responseBodyObj == null) {
                throw new IOException("Received empty response body");
            }

            String responseBody = responseBodyObj.string();
            logger.debug("Models info: {}", responseBody);
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode dataNode = rootNode.path("data");

            if (!dataNode.isArray()) {
                logger.error("/model/info did not return a data array. No models discovered.");
                // Maps are already cleared, so just return
                return;
            }

            float balance = 0f;
            if (isBrokk) {
                try {
                    balance = getUserBalance();
                    logger.info("User balance: {}", balance);
                } catch (IOException e) {
                    logger.error("Failed to retrieve user balance: {}", e.getMessage());
                    // Decide how to handle failure - perhaps treat as low balance?
                    // For now, log and continue, assuming not low balance.
                }
                isFreeTierOnly = balance < MINIMUM_PAID_BALANCE; // Set instance field
            }

            for (JsonNode modelInfoNode : dataNode) {
                String modelName = modelInfoNode.path("model_name").asText();
                String modelLocation =
                        modelInfoNode.path("litellm_params").path("model").asText();

                // Process max_output_tokens from model_info
                JsonNode modelInfoData = modelInfoNode.path("model_info");

                // Store model location and max tokens
                if (!modelName.isBlank() && !modelLocation.isBlank()) {
                    // Process and store all model_info fields
                    Map<String, Object> modelInfo = new HashMap<>();
                    if (modelInfoData.isObject()) {
                        @SuppressWarnings("deprecation")
                        Iterator<Map.Entry<String, JsonNode>> fields = modelInfoData.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> field = fields.next();
                            String key = field.getKey();
                            JsonNode value = field.getValue();

                            // Convert JsonNode to appropriate Java type
                            // Do not add key if value is null, for Map.copyOf compatibility
                            if (value.isNull()) {
                                // modelInfo.put(key, null); // Skip null values
                            } else if (value.isBoolean()) {
                                modelInfo.put(key, value.asBoolean());
                            } else if (value.isInt()) {
                                modelInfo.put(key, value.asInt());
                            } else if (value.isLong()) {
                                modelInfo.put(key, value.asLong());
                            } else if (value.isDouble()) {
                                modelInfo.put(key, value.asDouble());
                            } else if (value.isTextual()) {
                                modelInfo.put(key, value.asText());
                            } else if (value.isArray()) {
                                // Special handling for supported_openai_params
                                try {
                                    var type = objectMapper
                                            .getTypeFactory()
                                            .constructCollectionType(List.class, String.class);
                                    List<String> paramsList = objectMapper.convertValue(value, type);
                                    modelInfo.put(key, paramsList);
                                } catch (IllegalArgumentException e) {
                                    logger.error(
                                            "Could not parse array for model {}: {}", modelName, value.toString(), e);
                                }
                            } else if (value.isObject()) {
                                // Convert objects to String representation
                                modelInfo.put(key, value.toString());
                            }
                        }
                    }
                    // Add model location to the info map
                    modelInfo.put("model_location", modelLocation);

                    // Apply data retention policy filter
                    boolean isPrivate = (Boolean) modelInfo.getOrDefault("is_private", false);
                    if (policy == MainProject.DataRetentionPolicy.MINIMAL && !isPrivate) {
                        logger.debug("Skipping non-private model {} due to MINIMAL data retention policy", modelName);
                        continue;
                    }

                    // Filter if low balance
                    var freeEligible = (Boolean) modelInfo.getOrDefault("free_tier_eligible", false);
                    if (isFreeTierOnly && !freeEligible) {
                        logger.debug("Skipping model {} - not eligible for free tier (low balance)", modelName);
                        continue;
                    }

                    // Store the immutable copy of model info
                    var immutableModelInfo = Map.copyOf(modelInfo);
                    infoTarget.put(modelLocation, immutableModelInfo);
                    logger.debug(
                            "Discovered model: {} -> {} with info {})", modelName, modelLocation, immutableModelInfo);

                    // Only add chat models (not STT) to the available locations for selection
                    if ("chat".equals(immutableModelInfo.get("mode"))
                            || "responses".equals(immutableModelInfo.get("mode"))) {
                        locationsTarget.put(modelName, modelLocation);
                        logger.debug("Added chat model {} to available locations.", modelName);
                    } else {
                        logger.debug(
                                "Skipping model {} (mode: {}) from available locations.",
                                modelName,
                                immutableModelInfo.get("mode"));
                    }
                }
            }

            logger.info("Discovered {} models eligible for use.", locationsTarget.size());
        }
    }

    /**
     * Gets a map of available model *names* to their full location strings, suitable for display in settings. Filters
     * out internal/utility models like flash-lite. e.g. "deepseek-v3" -> "deepseek/deepseek-chat"
     */
    public Map<String, String> getAvailableModels() {
        return modelLocations.entrySet().stream()
                .filter(e -> !SYSTEM_ONLY_MODELS.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Retrieves the maximum output tokens for the given model name. Returns a default value if the information is not
     * available.
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

        // Most providers subtract the max output tokens from the input token budget. Since we very infrequently
        // need the entire output token budget, and even when we might we can recover and retry starting from where
        // we left off, we limit the output tokens to 1/8 of the input token budget.
        // (Previously we hard-capped this at 32k, but gpt5-mini and gpt5-nano with reasoning=high will blow past that,
        // causing request failures. So now it's uncapped.)
        int ceiling = min(value, getMaxInputTokens(location) / 8);
        int floor = min(8192, value);
        return max(floor, ceiling);
    }

    /**
     * Retrieves the maximum input tokens for the given model name. Returns a default value if the information is not
     * available.
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
     * Retrieves the maximum concurrent requests for the given model name. Returns a default value of 1 if the
     * information is not available.
     */
    public @Nullable Integer getMaxConcurrentRequests(StreamingChatModel model) {
        var location = model.defaultRequestParameters().modelName();
        var info = getModelInfo(location);
        if (info == null || !info.containsKey("max_concurrent_requests")) {
            return null;
        }
        return (Integer) info.get("max_concurrent_requests");
    }

    /** Retrieves the tokens per second for the given model. Returns null if the information is not available. */
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

    /**
     * Returns true if the given model exposes the toggle to completely disable reasoning (independent of the usual
     * LOW/MEDIUM/HIGH levels).
     */
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

    /**
     * Checks if the model explicitly supports reasoning steps based on its metadata. This is distinct from
     * `supportsReasoningEffort` which checks for the Anthropic parameter.
     *
     * @param location The model location string.
     * @return True if the model info contains `"supports_reasoning": true`, false otherwise.
     */
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
    @Nullable
    public StreamingChatModel getModel(
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

        // default request parameters
        var params = OpenAiChatRequestParameters.builder();

        // We connect to LiteLLM using an OpenAiStreamingChatModel, specifying baseUrl
        // placeholder, LiteLLM manages actual keys
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
            // Non-Brokk proxy
            builder = builder.apiKey("dummy-key");
            params = params.user("e99a7c42-faf6-4139-9537-874c76928da4");
        } else {
            var kp = parseKey(MainProject.getBrokkKey());
            builder = builder.apiKey(kp.token()).customHeaders(Map.of("Authorization", "Bearer " + kp.token()));
            params = params.user(kp.userId().toString());
        }

        params = params.modelName(location);

        // Apply reasoning effort if not default and supported
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

    @Nullable
    public StreamingChatModel getModel(String modelName) {
        return getModel(new ModelConfig(modelName, ReasoningLevel.DEFAULT));
    }

    @Nullable
    public StreamingChatModel getModel(ModelConfig config) {
        return getModel(config, null);
    }

    public boolean supportsJsonSchema(StreamingChatModel model) {
        var location = model.defaultRequestParameters().modelName();
        var info = getModelInfo(location);

        if (location.contains("gemini")) {
            // buildToolCallsSchema can't build a valid properties map for `arguments` without `oneOf` schema support.
            // o3mini is fine with this but gemini models are not.
            return false;
        }

        // gpt-5 sucks at this
        if (location.contains("gpt-5")) {
            return false;
        }

        // default: believe the litellm metadata
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
        // Dev-mode override via Settings: respect checkbox only when -Dbrokk.devmode=true
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
            // something is broken in litellm world
            return true;
        }

        // if it doesn't support function calling then we need to emulate
        var b = info.get("supports_function_calling");
        return !(b instanceof Boolean bVal) || !bVal;
    }

    private @Nullable Map<String, Object> getModelInfo(String location) {
        try {
            return modelInfoMap.get(location);
        } catch (NullPointerException e) {
            // ImmutableMap throws NPE when key does not exist
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

        // Check if "parallel_tool_calls" is in the list of supported_openai_params
        return switch (info.get("supported_openai_params")) {
            case List<?> list -> list.stream().map(Object::toString).anyMatch("parallel_tool_calls"::equals);
            case null, default -> false;
        };
    }

    /**
     * Checks if the model is designated as a "reasoning" model based on its metadata. Reasoning models are expected to
     * perform "think" steps implicitly.
     *
     * @param model The model instance to check.
     * @return True if the model is configured for reasoning
     */
    public boolean isReasoning(StreamingChatModel model) {
        var location = model.defaultRequestParameters().modelName();
        var supportsReasoning = supportsReasoning(location);
        if (!supportsReasoningEffortInternal(location)) {
            // reasoning is permanently enabled or disabled for this model type
            return supportsReasoning;
        }
        if (!(model instanceof OpenAiStreamingChatModel om)) {
            return false;
        }

        var effort = om.defaultRequestParameters().reasoningEffort();
        // Everyone except Anthropic turns thinking on by default
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
            // reasoning is permanently enabled or disabled for this model type
            return supportsReasoning;
        }

        // If not Sonnet, all reasoning models default to enabling it. If Sonnet,
        // only consider it reasoning if the level is not DEFAULT.
        // Disable means explicitly no reasoning
        if (config.reasoning() == ReasoningLevel.DISABLE) {
            return false;
        }

        var lowerName = modelName.toLowerCase(Locale.ROOT);
        if (!lowerName.contains("sonnet")) {
            return true;
        }
        // For Sonnet, reasoning is ON only when level is not DEFAULT
        return config.reasoning() != ReasoningLevel.DEFAULT;
    }

    /**
     * Checks if the model uses <think> tags for reasoning instead of a separate channel.
     *
     * @param model The model instance to check.
     * @return True if the model info contains `"uses_think_tags": true`, false otherwise.
     */
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

    /**
     * Checks if the model supports vision (image) inputs based on its metadata.
     *
     * @param model The model instance to check.
     * @return True if the model info contains `"supports_vision": true`, false otherwise.
     */
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
     *
     * <p>This looks up the model location from the configured display name, then consults the model info map for the
     * "free_tier_eligible" boolean flag. Returns false if the model or metadata cannot be found.
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

    /** Returns the dedicated model for Quick‑Edit operations (Cerebras GPT‑OSS‑120B). */
    public StreamingChatModel quickEditModel() {
        return quickEditModel;
    }

    /** Returns the default speech-to-text model instance. */
    public SpeechToTextModel sttModel() {
        return sttModel;
    }

    /** Returns a model optimized for scanning tasks. */
    public StreamingChatModel getScanModel() {
        var modelName = modelLocations.containsKey(GEMINI_2_5_FLASH) ? GEMINI_2_5_FLASH : GPT_5_MINI;
        var model = getModel(new ModelConfig(modelName, ReasoningLevel.DEFAULT));
        if (model == null) {
            logger.error("Failed to get scan model '{}'", modelName);
            return new UnavailableStreamingModel();
        }
        return model;
    }

    /**
     * Convenience helper to check whether a real STT model is available.
     *
     * @return true if speech-to-text is available for use, false if using UnavailableSTT stub.
     */
    public boolean hasSttModel() {
        return !(sttModel instanceof UnavailableSTT);
    }

    /**
     * Checks whether the service is online and capable of handling chat requests.
     */
    public boolean isOnline() {
        boolean hasUsableModel = modelLocations.keySet().stream().anyMatch(name -> !UNAVAILABLE.equals(name));
        boolean quickModelAvailable = !(quickModel instanceof UnavailableStreamingModel);
        return hasUsableModel && quickModelAvailable;
    }

    /** Interface for speech-to-text operations. Can remain static as it's just an interface definition. */
    public interface SpeechToTextModel {
        /** Transcribes audio, with optional context symbols. */
        String transcribe(Path audioFile, Set<String> symbols) throws IOException;
    }

    /**
     * Stubbed STT model for when speech-to-text is unavailable. Can remain static as it has no dependency on Models
     * instance state.
     */
    public static class UnavailableSTT implements SpeechToTextModel {
        @Override
        public String transcribe(Path audioFile, Set<String> symbols) {
            return "Speech-to-text is unavailable (no suitable model found via proxy or connection failed).";
        }
    }

    /**
     * Sends feedback supplied by the GUI dialog to Brokk’s backend. Files are attached with the multipart field name
     * "attachment".
     */
    public void sendFeedback(
            String category, String feedbackText, boolean includeDebugLog, @Nullable File screenshotFile)
            throws IOException {
        // Get user ID from Brokk key
        var kp = parseKey(MainProject.getBrokkKey());

        var bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("category", category)
                .addFormDataPart("feedback_text", feedbackText)
                .addFormDataPart("user_id", kp.userId().toString());

        if (includeDebugLog) {
            var debugLogPath =
                    Path.of(System.getProperty("user.home"), AbstractProject.BROKK_DIR, AbstractProject.DEBUG_LOG_FILE);
            var debugFile = debugLogPath.toFile();
            if (debugFile.exists()) {
                try {
                    // Create a temporary gzipped version of the debug log
                    var gzippedFile = Files.createTempFile("debug", ".log.gz").toFile();
                    gzippedFile.deleteOnExit();

                    try (var fis = new FileInputStream(debugFile);
                            var fos = new FileOutputStream(gzippedFile);
                            var gzos = new GZIPOutputStream(fos)) {
                        fis.transferTo(gzos);
                    }

                    bodyBuilder.addFormDataPart(
                            "attachments",
                            "debug.log.gz",
                            RequestBody.create(gzippedFile, MediaType.parse("application/gzip")));
                } catch (IOException e) {
                    logger.warn("Failed to gzip debug log, skipping: {}", e.getMessage());
                }
            } else {
                logger.debug("Debug log not found at {}", debugLogPath);
            }
        }

        if (screenshotFile != null && screenshotFile.exists()) {
            bodyBuilder.addFormDataPart(
                    "attachments",
                    screenshotFile.getName(),
                    RequestBody.create(screenshotFile, MediaType.parse("image/png")));
        }

        var requestBuilder = new Request.Builder()
                .url(MainProject.getServiceUrl() + "/api/events/feedback")
                .post(bodyBuilder.build());

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to send feedback: " + response.code() + " - "
                        + (response.body() != null ? response.body().string() : "(no body)"));
            }
            logger.debug("Feedback sent successfully");
        }
    }

    /**
     * Reports a client exception to the Brokk server for monitoring and debugging purposes.
     *
     * @param stacktrace The formatted stacktrace string
     * @param clientVersion The client version (from BuildInfo.version)
     * @return The JSON response from the server
     * @throws IOException If the request fails
     * @throws IllegalArgumentException if the Brokk key is invalid
     */
    @Override
    public JsonNode reportClientException(String stacktrace, String clientVersion) throws IOException {
        // Get the full Brokk key - this endpoint may expect the full key as Bearer token
        String brokkKey = MainProject.getBrokkKey();

        // Build JSON request body
        var jsonBody = objectMapper.createObjectNode();
        jsonBody.put("stacktrace", stacktrace);
        jsonBody.put("client_version", clientVersion);

        RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url(MainProject.getServiceUrl() + "/api/client-exceptions/")
                .header("Authorization", "Bearer " + brokkKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                throw new IOException("Failed to report exception: " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body() != null ? response.body().string() : "{}";
            logger.debug("Exception reported successfully to server: {}", responseBody);
            return objectMapper.readTree(responseBody);
        }
    }

    public static class UnavailableStreamingModel implements StreamingChatModel {
        public UnavailableStreamingModel() {}

        // Removed @Override annotations that seemed to cause compile errors
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

    /**
     * STT implementation using Whisper-compatible API via LiteLLM proxy. Uses OkHttp for multipart/form-data upload.
     */
    public class OpenAIStt implements SpeechToTextModel {
        private final Logger logger = LogManager.getLogger(OpenAIStt.class);
        private final String modelLocation; // e.g., "openai/whisper-1"

        public OpenAIStt(String modelLocation) {
            this.modelLocation = modelLocation;
        }

        /**
         * Determines the MediaType based on file extension.
         *
         * @param fileName Name of the file
         * @return MediaType for the HTTP request
         */
        private MediaType getMediaTypeFromFileName(String fileName) {
            var extension = fileName.toLowerCase(Locale.ROOT);
            int dotIndex = extension.lastIndexOf('.');
            if (dotIndex > 0) {
                extension = extension.substring(dotIndex + 1);
            }

            // Supported formats may depend on the specific model/proxy endpoint
            return switch (extension) {
                case "flac" -> MediaType.get("audio/flac");
                case "mp3" -> MediaType.get("audio/mpeg");
                case "mp4", "m4a" -> MediaType.get("audio/mp4");
                case "mpeg", "mpga" -> MediaType.get("audio/mpeg");
                case "oga", "ogg" -> MediaType.get("audio/ogg");
                case "wav" -> MediaType.get("audio/wav");
                case "webm" -> MediaType.get("audio/webm");
                default -> {
                    logger.warn("Unsupported audio extension '{}', attempting application/octet-stream", extension);
                    yield MediaType.get("application/octet-stream"); // Guaranteed non-null fallback
                }
            };
        }

        @Override
        public String transcribe(Path audioFile, Set<String> symbols) throws IOException {
            logger.info("Beginning transcription via proxy for file: {}", audioFile);
            var file = audioFile.toFile();

            MediaType mediaType = getMediaTypeFromFileName(file.getName());
            RequestBody fileBody = RequestBody.create(file, mediaType);

            var builder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.getName(), fileBody)
                    .addFormDataPart("model", modelLocation)
                    .addFormDataPart("language", "en")
                    .addFormDataPart("response_format", "json");

            RequestBody requestBody = builder.build();

            // Determine endpoint and authentication
            String proxyUrl = MainProject.getProxyUrl();
            String endpoint = proxyUrl + "/audio/transcriptions";

            var authHeader = "Bearer dummy-key"; // Default for LOCALHOST (no auth)
            if (MainProject.getProxySetting() != MainProject.LlmProxySetting.LOCALHOST) {
                var kp = parseKey(MainProject.getBrokkKey());
                authHeader = "Bearer " + kp.token();
            }

            Request request = new Request.Builder()
                    .url(endpoint)
                    .header("Authorization", authHeader)
                    // Content-Type is set automatically by OkHttp for MultipartBody
                    .post(requestBody)
                    .build();

            logger.debug("Sending STT request to {}", endpoint);

            // Use the shared httpClient from the outer Models class
            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                String bodyStr = response.body() != null ? response.body().string() : "";
                logger.debug("Received STT response, status = {}", response.code());

                if (!response.isSuccessful()) {
                    logger.error("Proxied STT call failed with status {}: {}", response.code(), bodyStr);
                    throw new IOException("Proxied STT call failed with status " + response.code() + ": " + bodyStr);
                }

                // Parse JSON response
                try {
                    JsonNode node = objectMapper.readTree(bodyStr);
                    if (node.has("text")) {
                        String transcript = node.get("text").asText().trim();
                        logger.info("Transcription successful, text length={} chars", transcript.length());
                        return transcript;
                    } else {
                        logger.warn("No 'text' field found in proxied STT response: {}", bodyStr);
                        return "No transcription found in response.";
                    }
                } catch (Exception e) {
                    logger.error("Error parsing proxied STT JSON response: {}", e.getMessage());
                    throw new IOException("Error parsing proxied STT JSON response: " + e.getMessage(), e);
                }
            }
        }
    }
}
