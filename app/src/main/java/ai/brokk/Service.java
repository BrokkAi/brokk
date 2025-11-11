package ai.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Concrete service that performs HTTP operations and initializes models.
 */
public class Service extends AbstractService implements ExceptionReporter.ReportingService {

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
            LogManager.getLogger(Service.class).info("User balance = {}, free‑tier = {}", balance, freeTier);
        } catch (IOException | IllegalArgumentException e) {
            LogManager.getLogger(Service.class)
                    .warn("Unable to fetch user balance for quick‑edit model selection: {}", e.getMessage());
        }

        if (freeTier) {
            LogManager.getLogger(Service.class)
                    .info("Free tier detected – using quickModel for quick‑edit operations.");
            quickEditModel = quickModel;
        } else {
            var qe = getModel(new ModelConfig("cerebras/gpt-oss-120b", ReasoningLevel.DEFAULT));
            quickEditModel = qe == null ? quickModel : qe;
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
            LogManager.getLogger(Service.class)
                    .warn("No suitable transcription model found via LiteLLM proxy. STT will be unavailable.");
            sttModel = new UnavailableSTT();
        } else {
            LogManager.getLogger(Service.class).info("Found transcription model at {}", sttLocation);
            sttModel = new OpenAIStt(sttLocation);
        }
    }

    @Override
    public float getUserBalance() throws IOException {
        return getUserBalance(MainProject.getBrokkKey());
    }

    /**
     * Fetches the user's balance for the given Brokk API key.
     */
    public static float getUserBalance(String key) throws IOException {
        parseKey(key); // Throws IllegalArgumentException if key is malformed

        String url = MainProject.getServiceUrl() + "/api/payments/balance-lookup/" + key;
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                if (response.code() == 401) {
                    throw new IllegalArgumentException("Invalid Brokk Key (Unauthorized from server): " + errorBody);
                }
                throw new IOException("Failed to fetch user balance: " + response.code() + " - " + errorBody);
            }
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

    public static void validateKey(String key) throws IOException {
        parseKey(key);
        getUserBalance(key);
    }

    /**
     * Fetches available models from the LLM proxy, populates the provided maps, and applies filters.
     */
    protected void fetchAvailableModels(
            MainProject.DataRetentionPolicy policy,
            Map<String, String> locationsTarget,
            Map<String, Map<String, Object>> infoTarget)
            throws IOException {
        locationsTarget.clear();
        infoTarget.clear();

        String baseUrl = MainProject.getProxyUrl();
        boolean isBrokk = MainProject.getProxySetting() != MainProject.LlmProxySetting.LOCALHOST;
        boolean isFreeTierOnly = false;

        var authHeader = "Bearer dummy-key";
        if (isBrokk) {
            String brokkKey = MainProject.getBrokkKey();
            if (brokkKey.isEmpty()) {
                LogManager.getLogger(Service.class)
                        .warn("Brokk API key is empty, cannot fetch models from Brokk proxy");
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
            LogManager.getLogger(Service.class).debug("Models info: {}", responseBody);
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
                                modelInfo.put(key, value.toString());
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
                    infoTarget.put(modelLocation, immutableModelInfo);
                    LogManager.getLogger(Service.class)
                            .debug(
                                    "Discovered model: {} -> {} with info {})",
                                    modelName,
                                    modelLocation,
                                    immutableModelInfo);

                    if ("chat".equals(immutableModelInfo.get("mode"))
                            || "responses".equals(immutableModelInfo.get("mode"))) {
                        locationsTarget.put(modelName, modelLocation);
                        LogManager.getLogger(Service.class)
                                .debug("Added chat model {} to available locations.", modelName);
                    } else {
                        LogManager.getLogger(Service.class)
                                .debug(
                                        "Skipping model {} (mode: {}) from available locations.",
                                        modelName,
                                        immutableModelInfo.get("mode"));
                    }
                }
            }

            LogManager.getLogger(Service.class).info("Discovered {} models eligible for use.", locationsTarget.size());
        }
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
                    LogManager.getLogger(Service.class).warn("Failed to gzip debug log, skipping: {}", e.getMessage());
                }
            } else {
                LogManager.getLogger(Service.class).debug("Debug log not found at {}", debugLogPath);
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
            LogManager.getLogger(Service.class).debug("Feedback sent successfully");
        }
    }

    /**
     * Reports a client exception to the Brokk server for monitoring and debugging purposes.
     */
    @Override
    public JsonNode reportClientException(String stacktrace, String clientVersion) throws IOException {
        String brokkKey = MainProject.getBrokkKey();

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
            LogManager.getLogger(Service.class).debug("Exception reported successfully to server: {}", responseBody);
            return objectMapper.readTree(responseBody);
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
                    .addFormDataPart("model", modelLocation)
                    .addFormDataPart("language", "en")
                    .addFormDataPart("response_format", "json");

            RequestBody requestBody = builder.build();

            String proxyUrl = MainProject.getProxyUrl();
            String endpoint = proxyUrl + "/audio/transcriptions";

            var authHeader = "Bearer dummy-key";
            if (MainProject.getProxySetting() != MainProject.LlmProxySetting.LOCALHOST) {
                var kp = parseKey(MainProject.getBrokkKey());
                authHeader = "Bearer " + kp.token();
            }

            Request request = new Request.Builder()
                    .url(endpoint)
                    .header("Authorization", authHeader)
                    .post(requestBody)
                    .build();

            logger.debug("Sending STT request to {}", endpoint);

            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                String bodyStr = response.body() != null ? response.body().string() : "";
                logger.debug("Received STT response, status = {}", response.code());

                if (!response.isSuccessful()) {
                    logger.error("Proxied STT call failed with status {}: {}", response.code(), bodyStr);
                    throw new IOException("Proxied STT call failed with status " + response.code() + ": " + bodyStr);
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
}
