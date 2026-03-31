package ai.brokk;

import ai.brokk.project.AbstractProject;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.util.Environment;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Concrete service that performs HTTP operations and initializes models.
 */
public class Service extends AbstractService implements ExceptionReporter.ReportingService {

    private static final Logger log = LogManager.getLogger(Service.class);

    // Share OkHttpClient across instances for efficiency
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    public Service(IProject project) {
        super(project);

        // Get and handle data retention policy
        var policy = project.getDataRetentionPolicy();
        if (policy == MainProject.DataRetentionPolicy.UNSET) {
            LogManager.getLogger(Service.class)
                    .warn(
                            "Data Retention Policy is UNSET for project {}. Defaulting to MINIMAL.",
                            project.getRoot().getFileName());
            policy = MainProject.DataRetentionPolicy.MINIMAL;
        }

        String proxyUrl = MainProject.getProxyUrl();
        LogManager.getLogger(Service.class)
                .info("Initializing models using policy: {} and proxy: {}", policy, proxyUrl);

        var tempModelLocations = new ConcurrentHashMap<String, String>();
        var tempModelInfoMap = new ConcurrentHashMap<String, Map<String, Object>>();

        try {
            fetchAvailableModels(policy, tempModelLocations, tempModelInfoMap);
        } catch (IOException e) {
            LogManager.getLogger(Service.class)
                    .error("Failed to connect to LiteLLM at {} or parse response: {}", proxyUrl, e.getMessage(), e);
            // tempModelLocations and tempModelInfoMap will be cleared by fetchAvailableModels in this case
        }

        if (tempModelLocations.isEmpty()) {
            LogManager.getLogger(Service.class).warn("No chat models available");
            tempModelLocations.put(UNAVAILABLE, "not_a_model");
        }

        this.modelInfoMap = Map.copyOf(tempModelInfoMap);
        this.modelLocations = Map.copyOf(tempModelLocations);

        // STT model initialization — custom endpoints (Ollama, LM Studio, etc.) don't support /audio/transcriptions
        if (MainProject.isCustomProvider()) {
            LogManager.getLogger(Service.class).info("Custom endpoint selected — speech-to-text is not available.");
            sttModel = new UnavailableSTT();
        } else {
            var sttModelName = modelInfoMap.entrySet().stream()
                    .filter(entry ->
                            "audio_transcription".equals(entry.getValue().get("mode")))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);

            if (sttModelName == null) {
                LogManager.getLogger(Service.class)
                        .warn("No suitable transcription model found via LiteLLM proxy. STT will be unavailable.");
                sttModel = new UnavailableSTT();
            } else {
                LogManager.getLogger(Service.class).info("Found transcription model: {}", sttModelName);
                sttModel = new OpenAIStt(sttModelName);
            }
        }
    }

    @Override
    public float getUserBalance() throws IOException {
        if (MainProject.isCustomProvider()) {
            return Float.MAX_VALUE; // unlimited for custom endpoints
        }
        return getUserBalance(MainProject.getBrokkKey());
    }

    /**
     * Fetches the user's balance and subscription status for the given Brokk API key.
     *
     * @param key the Brokk API key
     * @return BalanceInfo containing balance and subscription status
     * @throws IllegalArgumentException if key is malformed or unauthorized
     * @throws IOException if network error or unexpected response format
     */
    @Blocking
    public static BalanceInfo getBalanceInfo(String key) throws IOException {
        parseKey(key);

        var url = MainProject.getServiceUrl() + "/api/telemetry";

        var telemetryRequest = new TelemetryRequest(
                BuildInfo.version,
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                System.getProperty("java.version"),
                Map.of("client_type", Environment.getClientType()));

        var mapper = new ObjectMapper();
        var jsonBody = mapper.writeValueAsString(telemetryRequest);
        var body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        var request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + key)
                .post(body)
                .build();

        try (Response response = BrokkHttp.execute(request)) {
            if (!response.isSuccessful()) {
                var errorBody = response.body() != null ? response.body().string() : "(no body)";
                if (response.code() == 401) {
                    throw new IllegalArgumentException("Invalid Brokk Key (Unauthorized from server): " + errorBody);
                }
                throw new ServiceHttpException(response.code(), errorBody, "Failed to fetch user balance");
            }

            String responseBody = response.body() != null ? response.body().string() : "{}";
            JsonNode rootNode = mapper.readTree(responseBody);

            float balance;
            if (rootNode.has("available_balance")
                    && rootNode.get("available_balance").isNumber()) {
                balance = rootNode.get("available_balance").floatValue();
            } else if (rootNode.isNumber()) {
                balance = rootNode.floatValue();
            } else {
                try {
                    var balanceResponse = mapper.readValue(responseBody, BalanceResponse.class);
                    balance = balanceResponse.availableBalance();
                } catch (Exception e) {
                    throw new IOException("Unexpected balance response format: " + responseBody, e);
                }
            }

            boolean isSubscribed = false;
            if (rootNode.has("is_subscribed") && rootNode.get("is_subscribed").isBoolean()) {
                isSubscribed = rootNode.get("is_subscribed").asBoolean();
            }

            return new BalanceInfo(balance, isSubscribed);
        }
    }

    /**
     * Fetches only the user's balance for the given Brokk API key.
     */
    public static float getUserBalance(String key) throws IOException {
        return getBalanceInfo(key).balance();
    }

    /**
     * Checks if data sharing is allowed for the organization associated with the given Brokk API key. Defaults to true.
     */
    public static boolean getDataShareAllowed(String key) {
        try {
            parseKey(key);
        } catch (IllegalArgumentException e) {
            LogManager.getLogger(Service.class)
                    .debug("Invalid key format, cannot fetch data sharing status. Assuming allowed.", e);
            return true;
        }

        var url = MainProject.getServiceUrl() + "/api/users/check-data-sharing";
        var request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + key)
                .get()
                .build();

        try (Response response = BrokkHttp.execute(request)) {
            if (!response.isSuccessful()) {
                var errorBody = response.body() != null ? response.body().string() : "(no body)";
                LogManager.getLogger(Service.class)
                        .warn(
                                "Failed to fetch data sharing status (HTTP {}): {}. Assuming allowed.",
                                response.code(),
                                errorBody);
                return true;
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
                                    "Data sharing status response missing boolean field. Assuming allowed: {}",
                                    responseBody);
                    return true;
                }
            } catch (JsonProcessingException e) {
                LogManager.getLogger(Service.class)
                        .warn(
                                "Failed to parse data sharing status JSON response. Assuming allowed: {}",
                                responseBody,
                                e);
                return true;
            }
        } catch (IOException e) {
            LogManager.getLogger(Service.class)
                    .warn("IOException while fetching data sharing status. Assuming allowed.", e);
            return true;
        }
    }

    @Blocking
    public static void validateKey(String key) throws IOException {
        parseKey(key);
        getUserBalance(key);
    }

    @Blocking
    public static BrokkAuthValidation validateBrokkAuth(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return new BrokkAuthValidation(
                    BrokkAuthValidation.State.MISSING_KEY, false, false, false, 0f, "No Brokk API key configured.");
        }

        try {
            parseKey(key);
        } catch (IllegalArgumentException e) {
            return new BrokkAuthValidation(
                    BrokkAuthValidation.State.INVALID_KEY_FORMAT,
                    false,
                    false,
                    false,
                    0f,
                    exceptionMessageOr(e, "Invalid Brokk API key format."));
        }

        try {
            var info = getBalanceInfo(key);
            if (info.isSubscribed()) {
                return new BrokkAuthValidation(
                        BrokkAuthValidation.State.PAID_USER,
                        true,
                        true,
                        true,
                        info.balance(),
                        "Valid Brokk API key for a paid account.");
            }
            return new BrokkAuthValidation(
                    BrokkAuthValidation.State.FREE_USER,
                    true,
                    false,
                    true,
                    info.balance(),
                    "Valid Brokk API key for a free account.");
        } catch (IllegalArgumentException e) {
            return new BrokkAuthValidation(
                    BrokkAuthValidation.State.INVALID_KEY,
                    false,
                    false,
                    false,
                    0f,
                    exceptionMessageOr(e, "Invalid Brokk API key."));
        } catch (ServiceHttpException e) {
            if (isUnknownUserError(e)) {
                return new BrokkAuthValidation(
                        BrokkAuthValidation.State.UNKNOWN_USER,
                        false,
                        false,
                        false,
                        0f,
                        exceptionMessageOr(e, "Unknown Brokk user."));
            }
            return new BrokkAuthValidation(
                    BrokkAuthValidation.State.SERVICE_ERROR,
                    false,
                    false,
                    false,
                    0f,
                    exceptionMessageOr(e, "Brokk service error."));
        } catch (IOException e) {
            return new BrokkAuthValidation(
                    BrokkAuthValidation.State.NETWORK_ERROR,
                    false,
                    false,
                    false,
                    0f,
                    exceptionMessageOr(e, "Network error while validating Brokk API key."));
        }
    }

    private static String exceptionMessageOr(Exception exception, String fallback) {
        return Objects.requireNonNullElse(exception.getMessage(), fallback);
    }

    private static boolean isUnknownUserError(ServiceHttpException exception) {
        if (exception.getStatusCode() == 404) {
            return true;
        }

        String body = exception.getResponseBody();
        if (body == null || body.isBlank()) {
            return false;
        }
        body = body.toLowerCase(Locale.ROOT);
        return body.contains("unknown user")
                || body.contains("user not found")
                || body.contains("no such user")
                || body.contains("unknown account");
    }

    /**
     * Fetches available models from the LLM proxy, populates the provided maps, and applies filters.
     */
    protected void fetchAvailableModels(
            MainProject.DataRetentionPolicy policy,
            Map<String, String> locationsTarget,
            Map<String, Map<String, Object>> infoTarget)
            throws IOException {
        // For custom OpenAI-compatible endpoints, use a dedicated discovery path
        if (MainProject.isCustomProvider()) {
            fetchCustomEndpointModels(locationsTarget, infoTarget);
            return;
        }

        locationsTarget.clear();
        infoTarget.clear();

        String baseUrl = MainProject.getProxyUrl();
        boolean isBrokk = MainProject.getProxySetting() != MainProject.LlmProxySetting.LOCALHOST;
        boolean isFreeTierOnly = false;

        String url = baseUrl + "/model/info";
        if (isBrokk) {
            String brokkKey = MainProject.getBrokkKey();
            if (brokkKey.isEmpty()) {
                LogManager.getLogger(Service.class)
                        .warn("Brokk API key is empty, cannot fetch models from Brokk proxy");
                return;
            }
            var kp = parseKey(brokkKey);
            var userId = kp.userId().toString();
            url += "?user_id=" + URLEncoder.encode(userId, StandardCharsets.UTF_8);
        }
        Request request = BrokkHttp.proxyRequest().url(url).get().build();

        try (Response response = BrokkHttp.execute(request)) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                throw new ServiceHttpException(response.code(), errorBody, "Failed to fetch model info");
            }

            ResponseBody responseBodyObj = response.body();
            if (responseBodyObj == null) {
                throw new IOException("Received empty response body");
            }

            String responseBody = responseBodyObj.string();
            LogManager.getLogger(Service.class).trace("Models response: {}", responseBody);
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode dataNode = rootNode.path("data");

            if (!dataNode.isArray()) {
                LogManager.getLogger(Service.class)
                        .error("/model/info did not return a data array. No models discovered.");
                return;
            }

            float balance = 0f;
            if (isBrokk) {
                try {
                    balance = getUserBalance();
                    LogManager.getLogger(Service.class).info("User balance: {}", balance);
                } catch (IOException e) {
                    LogManager.getLogger(Service.class).error("Failed to retrieve user balance: {}", e.getMessage());
                }
                isFreeTierOnly = balance < MINIMUM_PAID_BALANCE;
            }

            for (JsonNode modelInfoNode : dataNode) {
                String modelName = modelInfoNode.path("model_name").asText();
                String modelLocation =
                        modelInfoNode.path("litellm_params").path("model").asText();

                JsonNode modelInfoData = modelInfoNode.path("model_info");

                if (!modelName.isBlank() && !modelLocation.isBlank()) {
                    Map<String, Object> modelInfo = new HashMap<>();
                    if (modelInfoData.isObject()) {
                        @SuppressWarnings("deprecation")
                        Iterator<Map.Entry<String, JsonNode>> fields = modelInfoData.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> field = fields.next();
                            String key = field.getKey();
                            JsonNode value = field.getValue();

                            if (value.isNull()) {
                                // skip nulls
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
                                try {
                                    var type = objectMapper
                                            .getTypeFactory()
                                            .constructCollectionType(List.class, String.class);
                                    List<String> paramsList = objectMapper.convertValue(value, type);
                                    modelInfo.put(key, paramsList);
                                } catch (IllegalArgumentException e) {
                                    LogManager.getLogger(Service.class)
                                            .error(
                                                    "Could not parse array for model {}: {}",
                                                    modelName,
                                                    value.toString(),
                                                    e);
                                }
                            } else if (value.isObject()) {
                                if ("pricing_tiers".equals(key)) {
                                    try {
                                        var pricingTiers = objectMapper.convertValue(value, PricingTiers.class);
                                        modelInfo.put(key, pricingTiers);
                                    } catch (IllegalArgumentException e) {
                                        LogManager.getLogger(Service.class)
                                                .warn(
                                                        "Could not parse pricing_tiers for model {}: {}",
                                                        modelName,
                                                        value.toString(),
                                                        e);
                                    }
                                } else {
                                    modelInfo.put(key, value.toString());
                                }
                            }
                        }
                    }
                    modelInfo.put("model_location", modelLocation);

                    boolean isPrivate = (Boolean) modelInfo.getOrDefault("is_private", false);
                    if (policy == MainProject.DataRetentionPolicy.MINIMAL && !isPrivate) {
                        LogManager.getLogger(Service.class)
                                .debug("Skipping non-private model {} due to MINIMAL policy", modelName);
                        continue;
                    }

                    var freeEligible = (Boolean) modelInfo.getOrDefault("free_tier_eligible", false);
                    if (isFreeTierOnly && !freeEligible) {
                        LogManager.getLogger(Service.class)
                                .debug("Skipping model {} - not eligible for free tier (low balance)", modelName);
                        continue;
                    }

                    var immutableModelInfo = Map.copyOf(modelInfo);
                    infoTarget.put(modelName, immutableModelInfo);
                    LogManager.getLogger(Service.class)
                            .trace(
                                    "Discovered model: {} -> {} with info {})",
                                    modelName,
                                    modelLocation,
                                    immutableModelInfo);

                    var mode = immutableModelInfo.get("mode");
                    if (mode == null || "chat".equals(mode) || "responses".equals(mode)) {
                        locationsTarget.put(modelName, modelLocation);
                        LogManager.getLogger(Service.class)
                                .debug("Added chat model {} to available locations.", modelName);
                    } else {
                        LogManager.getLogger(Service.class)
                                .debug("Skipping model {} (mode: {}) from available locations.", modelName, mode);
                    }
                }
            }

            LogManager.getLogger(Service.class)
                    .info(
                            "Discovered models {}",
                            locationsTarget.keySet().stream().sorted().toList());
        }
    }

    /**
     * Fetches models from a custom OpenAI-compatible endpoint.
     * Tries the standard GET /v1/models endpoint first, falls back to manual model name configuration.
     */
    private void fetchCustomEndpointModels(
            Map<String, String> locationsTarget, Map<String, Map<String, Object>> infoTarget) {
        locationsTarget.clear();
        infoTarget.clear();

        String baseUrl = MainProject.getCustomEndpointUrl();
        String apiKey = MainProject.getCustomEndpointApiKey();
        if (apiKey.isBlank()) {
            apiKey = CUSTOM_ENDPOINT_DUMMY_KEY;
        }

        // Try OpenAI-standard /v1/models (or /models if baseUrl already ends with /v1)
        String modelsUrl = baseUrl.endsWith("/v1") || baseUrl.endsWith("/v1/")
                ? baseUrl.replaceAll("/+$", "") + "/models"
                : baseUrl.replaceAll("/+$", "") + "/v1/models";

        var request = new Request.Builder()
                .url(modelsUrl)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();

        boolean discoveredViaApi = false;
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);
                JsonNode data = root.path("data");
                if (data.isArray()) {
                    for (JsonNode modelNode : data) {
                        String id = modelNode.path("id").asText("");
                        if (!id.isBlank()) {
                            locationsTarget.put(id, id);
                            discoveredViaApi = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogManager.getLogger(Service.class)
                    .info(
                            "Could not fetch models from {}: {} (falling back to manual config)",
                            modelsUrl,
                            e.getMessage());
        }

        // If no models discovered via API, use the manually configured model name
        if (!discoveredViaApi) {
            String manualModel = MainProject.getCustomEndpointModel();
            if (!manualModel.isBlank()) {
                locationsTarget.put(manualModel, manualModel);
            }
        }

        if (locationsTarget.isEmpty()) {
            LogManager.getLogger(Service.class)
                    .warn(
                            "No models discovered from custom endpoint {} and no manual model configured. "
                                    + "Configure a model name in Settings > Custom Endpoint, or ensure the endpoint is running.",
                            baseUrl);
        } else {
            LogManager.getLogger(Service.class).info("Custom endpoint models discovered: {}", locationsTarget.keySet());
        }

        // No model info from custom endpoints — the defaults in CUSTOM_MODEL_DEFAULTS will be used
    }

    /**
     * Sends feedback supplied by the GUI dialog to Brokk’s backend. Files are attached with the multipart field name
     * "attachment".
     */
    @Override
    public void sendFeedback(
            String category, String feedbackText, boolean includeDebugLog, @Nullable File screenshotFile)
            throws IOException {
        var kp = parseKey(MainProject.getBrokkKey());

        // Resolve version and environment, defaulting to "Unknown" if blank/null
        String version = BuildInfo.version;
        if (version == null || version.isBlank()) {
            version = "Unknown";
        }
        String environment = Environment.getOsDescription();
        if (environment == null || environment.isBlank()) {
            environment = "Unknown";
        }
        log.debug("Sending feedback with version={}, environment={}", version, environment);

        var bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("category", category)
                .addFormDataPart("feedback_text", feedbackText)
                .addFormDataPart("user_id", kp.userId().toString())
                .addFormDataPart("version", version)
                .addFormDataPart("environment", environment);

        if (includeDebugLog) {
            var debugLogPath =
                    Path.of(System.getProperty("user.home"), AbstractProject.BROKK_DIR, AbstractProject.DEBUG_LOG_FILE);
            var debugFile = debugLogPath.toFile();
            if (debugFile.exists()) {
                try {
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
                    log.warn("Failed to gzip debug log, skipping: {}", e.getMessage());
                }
            } else {
                log.debug("Debug log not found at {}", debugLogPath);
            }
        }

        if (screenshotFile != null && screenshotFile.exists()) {
            bodyBuilder.addFormDataPart(
                    "attachments",
                    screenshotFile.getName(),
                    RequestBody.create(screenshotFile, MediaType.parse("image/png")));
        }

        var request = new Request.Builder()
                .url(MainProject.getServiceUrl() + "/api/events/feedback")
                .post(bodyBuilder.build())
                .build();

        try (Response response = BrokkHttp.execute(request)) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                throw new ServiceHttpException(response.code(), errorBody, "Failed to send feedback");
            }
            log.debug("Feedback sent successfully");
        }
    }

    /**
     * Reports a client exception to the Brokk server for monitoring and debugging purposes.
     * The exception report JSON is fully constructed by ExceptionReporter; this method
     * just handles HTTP transport.
     */
    @Override
    public JsonNode reportClientException(JsonNode exceptionReport) throws IOException {
        String brokkKey = MainProject.getBrokkKey();

        RequestBody body = RequestBody.create(exceptionReport.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(MainProject.getServiceUrl() + "/api/client-exceptions/")
                .header("Authorization", "Bearer " + brokkKey)
                .post(body)
                .build();

        try (Response response = BrokkHttp.execute(request)) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                throw new ServiceHttpException(response.code(), errorBody, "Failed to report exception");
            }

            String responseBody = response.body() != null ? response.body().string() : "{}";
            LogManager.getLogger(Service.class).debug("Exception reported successfully to server: {}", responseBody);
            return objectMapper.readTree(responseBody);
        }
    }

    /**
     * Forwards OAuth callback parameters to the Brokk backend for Codex OAuth flow.
     *
     * @param callbackParams The query parameters received from the OAuth callback
     * @param verifier The PKCE code_verifier for this authorization attempt
     * @return null on success (2xx response), or an error message on failure
     */
    @Nullable
    public static String forwardCodexOauthCallbackToBackend(Map<String, String> callbackParams, String verifier) {
        String brokkKey = MainProject.getBrokkKey();

        var urlBuilder = new StringBuilder(MainProject.getServiceUrl());
        urlBuilder.append("/api/auth/codex-oauth/callback?");

        var queryParams = new StringBuilder();
        for (var entry : callbackParams.entrySet()) {
            if (!queryParams.isEmpty()) {
                queryParams.append("&");
            }
            queryParams
                    .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append("=")
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        if (!queryParams.isEmpty()) {
            queryParams.append("&");
        }
        queryParams.append("code_verifier=").append(URLEncoder.encode(verifier, StandardCharsets.UTF_8));

        urlBuilder.append(queryParams);

        String url = urlBuilder.toString();
        LogManager.getLogger(Service.class)
                .debug("Forwarding OAuth callback to backend: {}", url.replaceAll("code=[^&]+", "code=***"));

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + brokkKey)
                .get()
                .build();

        try (Response response = BrokkHttp.execute(request)) {
            int statusCode = response.code();
            String responseBody = response.body() != null ? response.body().string() : "";

            if (response.isSuccessful()) {
                LogManager.getLogger(Service.class).info("Backend OAuth call succeeded: status={}", statusCode);
                return null;
            } else {
                String truncatedBody =
                        responseBody.length() > 200 ? responseBody.substring(0, 200) + "..." : responseBody;
                LogManager.getLogger(Service.class)
                        .warn("Backend OAuth call failed: status={}, body={}", statusCode, truncatedBody);
                return "Backend authentication failed (HTTP " + statusCode + ")";
            }
        } catch (IOException e) {
            LogManager.getLogger(Service.class).error("Failed to call backend OAuth endpoint", e);
            return "Failed to communicate with Brokk backend: " + e.getMessage();
        }
    }

    /**
     * Disconnects the OpenAI Codex OAuth authorization by calling the backend DELETE endpoint.
     *
     * @return null on success (2xx response), or an error message on failure
     */
    @Nullable
    public static String disconnectCodexOauth() {
        String brokkKey = MainProject.getBrokkKey();

        String url = MainProject.getServiceUrl() + "/api/auth/codex-oauth/authorization";

        LogManager.getLogger(Service.class).debug("Disconnecting OpenAI Codex OAuth via DELETE {}", url);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + brokkKey)
                .delete()
                .build();

        try (Response response = BrokkHttp.execute(request)) {
            int statusCode = response.code();
            String responseBody = response.body() != null ? response.body().string() : "";

            if (response.isSuccessful()) {
                LogManager.getLogger(Service.class)
                        .info("OpenAI Codex OAuth disconnected successfully: status={}", statusCode);
                return null;
            } else {
                String truncatedBody =
                        responseBody.length() > 200 ? responseBody.substring(0, 200) + "..." : responseBody;
                LogManager.getLogger(Service.class)
                        .warn("Failed to disconnect OpenAI Codex OAuth: status={}, body={}", statusCode, truncatedBody);
                return "Failed to disconnect (HTTP " + statusCode + ")";
            }
        } catch (IOException e) {
            LogManager.getLogger(Service.class).error("Failed to call backend disconnect endpoint", e);
            return "Failed to communicate with Brokk backend: " + e.getMessage();
        }
    }

    /**
     * STT implementation using Whisper-compatible API via LiteLLM proxy. Uses OkHttp for multipart/form-data upload.
     */
    public class OpenAIStt implements SpeechToTextModel {
        private final Logger logger = LogManager.getLogger(OpenAIStt.class);
        private final String modelName; // e.g., "whisper-1"

        public OpenAIStt(String modelName) {
            this.modelName = modelName;
        }

        private MediaType getMediaTypeFromFileName(String fileName) {
            var extension = fileName.toLowerCase(Locale.ROOT);
            int dotIndex = extension.lastIndexOf('.');
            if (dotIndex > 0) {
                extension = extension.substring(dotIndex + 1);
            }

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
                    yield MediaType.get("application/octet-stream");
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
                    .addFormDataPart("model", modelName)
                    .addFormDataPart("language", "en")
                    .addFormDataPart("response_format", "json");

            RequestBody requestBody = builder.build();

            String proxyUrl = MainProject.getProxyUrl();
            String endpoint = proxyUrl + "/audio/transcriptions";

            Request request =
                    BrokkHttp.proxyRequest().url(endpoint).post(requestBody).build();

            logger.debug("Sending STT request to {}", endpoint);

            try (Response response = BrokkHttp.execute(request)) {
                String bodyStr = response.body() != null ? response.body().string() : "";
                logger.debug("Received STT response, status = {}", response.code());

                if (!response.isSuccessful()) {
                    logger.error("Proxied STT call failed with status {}: {}", response.code(), bodyStr);
                    throw new ServiceHttpException(response.code(), bodyStr, "Proxied STT call failed");
                }

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TelemetryRequest(
            @JsonProperty("app_version") String appVersion,
            String os,
            String platform,
            @JsonProperty("java_runtime") String javaRuntime,
            Map<String, Object> properties) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BalanceResponse(
            @JsonProperty("available_balance") float availableBalance,
            @JsonProperty("max_budget") float maxBudget,
            @JsonProperty("spend") float spend,
            String currency,
            @JsonProperty("monthly_credit_available") float monthlyCreditAvailable,
            @JsonProperty("is_subscribed") boolean isSubscribed) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteSessionMeta(
            String id,
            @JsonProperty("user_id") String userId,
            @JsonProperty("org_id") String orgId,
            String remote,
            String name,
            String sharing,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("modified_at") @Nullable String modifiedAt,
            @JsonProperty("deleted_at") @Nullable String deletedAt) {

        public long modifiedAtMillis() {
            return modifiedAt != null ? Instant.parse(modifiedAt).toEpochMilli() : 0;
        }

        public long deletedAtMillis() {
            return deletedAt != null ? Instant.parse(deletedAt).toEpochMilli() : 0;
        }

        public UUID uuid() {
            return UUID.fromString(id);
        }
    }

    // Separate mapper configured to ignore unknown properties
    private static final ObjectMapper SESSION_OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static @Nullable String sessionAuthHeader() {
        String key = MainProject.getBrokkKey();
        if (key.isBlank()) return null;
        return "Bearer " + key;
    }

    public static List<RemoteSessionMeta> listRemoteSessions(String remote) throws IOException {
        if (MainProject.isCustomProvider()) {
            return List.of();
        }
        var builder = BrokkHttp.sessionServiceRequest();
        if (builder == null || remote.isBlank()) {
            log.debug("Skipping listRemoteSessions: missing auth or remote");
            throw new IOException("listRemoteSessions failed: missing auth or remote");
        }
        String url = MainProject.getServiceUrl() + "/api/sessions?remote="
                + URLEncoder.encode(remote, StandardCharsets.UTF_8)
                + "&include_deleted=true";
        Request request = builder.url(url).get().build();
        try (Response response = BrokkHttp.execute(request)) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new ServiceHttpException(response.code(), body, "listRemoteSessions failed");
            }
            String body = response.body() != null ? response.body().string() : "[]";
            RemoteSessionMeta[] arr = SESSION_OBJECT_MAPPER.readValue(body, RemoteSessionMeta[].class);
            return Arrays.asList(arr);
        }
    }

    public static byte[] getRemoteSessionContent(UUID id) throws IOException {
        if (MainProject.isCustomProvider()) {
            return new byte[0];
        }
        var builder = BrokkHttp.sessionServiceRequest();
        if (builder == null) {
            throw new IOException("Missing Brokk key for remote session content fetch");
        }
        String url = MainProject.getServiceUrl() + "/api/sessions/" + id;
        Request request = builder.url(url).get().build();
        try (Response response = BrokkHttp.execute(request)) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new ServiceHttpException(response.code(), body, "getRemoteSessionContent failed");
            }
            return response.body() != null ? response.body().bytes() : new byte[0];
        }
    }

    public static RemoteSessionMeta writeRemoteSession(
            UUID id, String remote, String name, long modifiedAt, byte[] contentZip) throws IOException {
        if (MainProject.isCustomProvider()) {
            throw new IOException("Remote sessions are not available with a custom endpoint");
        }
        var builder = BrokkHttp.sessionServiceRequest();
        if (builder == null || remote.isBlank()) {
            throw new IOException("Missing auth or remote for writeRemoteSession");
        }
        String url = MainProject.getServiceUrl() + "/api/sessions";
        var bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("id", id.toString())
                .addFormDataPart("remote", remote)
                .addFormDataPart("name", name)
                .addFormDataPart("modified_at", String.valueOf(modifiedAt));
        bodyBuilder.addFormDataPart(
                "content", "session.zip", RequestBody.create(contentZip, MediaType.parse("application/zip")));
        Request request = builder.url(url).post(bodyBuilder.build()).build();
        try (Response response = BrokkHttp.execute(request)) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new ServiceHttpException(response.code(), respBody, "writeRemoteSession failed");
            }
            return SESSION_OBJECT_MAPPER.readValue(respBody, RemoteSessionMeta.class);
        }
    }

    public static RemoteSessionMeta updateSessionSharing(UUID id, String sharing) throws IOException {
        if (MainProject.isCustomProvider()) {
            throw new IOException("Remote sessions are not available with a custom endpoint");
        }
        var builder = BrokkHttp.sessionServiceRequest();
        if (builder == null) {
            throw new IOException("Missing auth for updateRemoteSessionSharing");
        }
        if (sharing.isBlank()) {
            throw new IllegalArgumentException("sharing must not be blank");
        }

        String url = MainProject.getServiceUrl() + "/api/sessions";
        var body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("id", id.toString())
                .addFormDataPart("sharing", sharing)
                .build();

        Request request = builder.url(url).post(body).build();
        try (Response response = BrokkHttp.execute(request)) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("updateRemoteSessionSharing failed: " + response.code() + " - " + respBody);
            }
            return SESSION_OBJECT_MAPPER.readValue(respBody, RemoteSessionMeta.class);
        }
    }

    public static void deleteRemoteSession(UUID id) throws IOException {
        if (MainProject.isCustomProvider()) {
            return;
        }
        var builder = BrokkHttp.sessionServiceRequest();
        if (builder == null) {
            throw new IOException("Missing auth for deleteRemoteSession");
        }
        String url = MainProject.getServiceUrl() + "/api/sessions/" + id;
        Request request = builder.url(url).delete().build();
        try (Response response = BrokkHttp.execute(request)) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new ServiceHttpException(response.code(), body, "deleteRemoteSession failed");
            }
        }
    }

    private static class BrokkHttp {
        public static Request.Builder proxyRequest() {
            var builder = new Request.Builder();
            var setting = MainProject.getProxySetting();
            String authHeader;
            if (setting == MainProject.LlmProxySetting.LOCALHOST) {
                authHeader = "Bearer dummy-key";
            } else if (setting == MainProject.LlmProxySetting.CUSTOM) {
                String apiKey = MainProject.getCustomEndpointApiKey();
                authHeader = "Bearer " + (apiKey.isBlank() ? CUSTOM_ENDPOINT_DUMMY_KEY : apiKey);
            } else {
                var kp = parseKey(MainProject.getBrokkKey());
                authHeader = "Bearer " + kp.token();
            }
            return builder.header("Authorization", authHeader);
        }

        public static @Nullable Request.Builder sessionServiceRequest() {
            String auth = sessionAuthHeader();
            if (auth == null) {
                return null;
            }
            return new Request.Builder().header("Authorization", auth);
        }

        public static Response execute(Request request) throws IOException {
            return httpClient.newCall(request).execute();
        }
    }
}
