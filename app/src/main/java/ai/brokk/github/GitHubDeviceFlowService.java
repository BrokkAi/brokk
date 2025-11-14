package ai.brokk.github;

import ai.brokk.util.Json;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GitHubDeviceFlowService {
    private static final Logger logger = LogManager.getLogger(GitHubDeviceFlowService.class);
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final int DEFAULT_POLL_INTERVAL = 5;

    private final String clientId;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService executor;

    public GitHubDeviceFlowService(String clientId, ScheduledExecutorService executor) {
        this.clientId = clientId;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.executor = executor;
    }

    public DeviceFlowModels.DeviceCodeResponse requestDeviceCode() throws DeviceFlowException {
        return requestDeviceCode("repo");
    }

    public DeviceFlowModels.DeviceCodeResponse requestDeviceCode(String scope) throws DeviceFlowException {
        logger.debug("Requesting device code from GitHub with scope: {}", scope);

        var formBody = new FormBody.Builder()
                .add("client_id", clientId)
                .add("scope", scope)
                .build();

        var request = new Request.Builder()
                .url(DEVICE_CODE_URL)
                .post(formBody)
                .header("Accept", "application/json")
                .build();

        try (var response = httpClient.newCall(request).execute()) {
            logger.debug("GitHub device code response: status={}, message={}", response.code(), response.message());
            logger.debug("Response headers: {}", response.headers());

            var responseBody = response.body();
            if (responseBody == null) {
                logger.error("Received null response body from GitHub device code endpoint");
                throw new DeviceFlowException(
                        DeviceFlowException.ErrorType.INVALID_RESPONSE, "Empty response from device code request");
            }

            var responseText = responseBody.string();
            logger.debug("GitHub device code response received (length: {} chars)", responseText.length());

            if (!response.isSuccessful()) {
                logger.error(
                        "GitHub device code request failed with status {}: <response body redacted>", response.code());
                handleHttpError(response.code(), responseText);
            }

            try {
                var deviceCodeResponse = Json.fromJson(responseText, DeviceFlowModels.DeviceCodeResponse.class);
                logger.info("Successfully obtained device code, expires in {} seconds", deviceCodeResponse.expiresIn());
                return deviceCodeResponse;
            } catch (Exception e) {
                logger.error("Failed to parse device code response: <response body redacted>", e);
                throw new DeviceFlowException(
                        DeviceFlowException.ErrorType.INVALID_RESPONSE,
                        "Failed to parse device code response: " + e.getMessage(),
                        e);
            }
        } catch (IOException e) {
            logger.error("Network error during device code request", e);
            throw new DeviceFlowException(
                    DeviceFlowException.ErrorType.NETWORK_ERROR,
                    "Network error during device code request: " + e.getMessage(),
                    e);
        }
    }

    public CompletableFuture<DeviceFlowModels.TokenPollResponse> pollForToken(String deviceCode, int pollInterval) {
        logger.debug("Starting token polling for device code with interval {} seconds", pollInterval);

        var future = new CompletableFuture<DeviceFlowModels.TokenPollResponse>();
        var pollTask = new Runnable() {
            private int attempts = 0;
            private int currentInterval = pollInterval;

            @Override
            public void run() {
                attempts++;
                logger.debug("Token poll attempt {} with interval {}", attempts, currentInterval);

                try {
                    var pollResponse = pollOnce(deviceCode);

                    switch (pollResponse.result()) {
                        case SUCCESS -> {
                            logger.info("Device flow authentication successful");
                            future.complete(pollResponse);
                            return;
                        }
                        case DENIED, EXPIRED, ERROR -> {
                            logger.warn(
                                    "Device flow failed with result: {} - {}",
                                    pollResponse.result(),
                                    pollResponse.errorMessage());
                            future.complete(pollResponse);
                            return;
                        }
                        case PENDING -> {
                            logger.debug("Authentication still pending, continuing to poll");
                            executor.schedule(this, currentInterval, TimeUnit.SECONDS);
                        }
                        case SLOW_DOWN -> {
                            currentInterval += 5;
                            logger.warn(
                                    "Rate limited by GitHub, increasing poll interval to {} seconds", currentInterval);
                            executor.schedule(this, currentInterval, TimeUnit.SECONDS);
                        }
                    }
                } catch (DeviceFlowException e) {
                    logger.error("Error during token polling", e);
                    var errorResponse = new DeviceFlowModels.TokenPollResponse(
                            DeviceFlowModels.TokenPollResult.ERROR,
                            null,
                            e.getMessage() != null ? e.getMessage() : "Unknown error");
                    future.complete(errorResponse);
                }
            }
        };

        executor.schedule(pollTask, 0, TimeUnit.SECONDS);
        return future;
    }

    private DeviceFlowModels.TokenPollResponse pollOnce(String deviceCode) throws DeviceFlowException {
        var formBody = new FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build();

        var request = new Request.Builder()
                .url(ACCESS_TOKEN_URL)
                .post(formBody)
                .header("Accept", "application/json")
                .build();

        try (var response = httpClient.newCall(request).execute()) {
            logger.debug("Received HTTP {} response from token polling", response.code());
            var responseBody = response.body();
            if (responseBody == null) {
                throw new DeviceFlowException(
                        DeviceFlowException.ErrorType.INVALID_RESPONSE, "Empty response from token poll");
            }

            var responseText = responseBody.string();
            logger.debug("Token poll response received (length: {} chars)", responseText.length());

            // First, check if the response contains error information (regardless of status code)
            try {
                var jsonResponse = Json.getMapper().readTree(responseText);
                var error = jsonResponse.get("error");
                if (error != null) {
                    logger.debug("Response contains error field, parsing as error response");
                    var errorType = error.asText();
                    logger.debug("Parsed error type: {}", errorType);
                    var result =
                            switch (errorType) {
                                case "authorization_pending" -> {
                                    logger.debug("Returning PENDING result for authorization_pending");
                                    yield new DeviceFlowModels.TokenPollResponse(
                                            DeviceFlowModels.TokenPollResult.PENDING, null, "Authorization pending");
                                }
                                case "access_denied" ->
                                    new DeviceFlowModels.TokenPollResponse(
                                            DeviceFlowModels.TokenPollResult.DENIED, null, "User denied authorization");
                                case "expired_token" ->
                                    new DeviceFlowModels.TokenPollResponse(
                                            DeviceFlowModels.TokenPollResult.EXPIRED, null, "Device code expired");
                                case "slow_down" -> {
                                    logger.warn("GitHub API requests are being made too frequently, slowing down");
                                    yield new DeviceFlowModels.TokenPollResponse(
                                            DeviceFlowModels.TokenPollResult.SLOW_DOWN,
                                            null,
                                            "Rate limited, slowing down");
                                }
                                default ->
                                    throw new DeviceFlowException(
                                            DeviceFlowException.ErrorType.INVALID_RESPONSE,
                                            "Unknown error response: " + errorType);
                            };
                    logger.debug("Returning result: {}", result.result());
                    return result;
                }
            } catch (Exception e) {
                logger.warn("Could not parse response as JSON for error checking: <response body redacted>");
                // Fall through to try parsing as successful response
            }

            if (!response.isSuccessful()) {
                handleHttpError(response.code(), responseText);
            }

            try {
                logger.debug("Attempting to parse successful token response");
                var tokenResponse = Json.fromJson(responseText, DeviceFlowModels.TokenResponse.class);
                logger.info(
                        "Successfully parsed token response, access_token present: {}",
                        tokenResponse.accessToken() != null);
                return new DeviceFlowModels.TokenPollResponse(
                        DeviceFlowModels.TokenPollResult.SUCCESS, tokenResponse, "Success");
            } catch (Exception e) {
                logger.error("Failed to parse token response: <response body redacted>", e);
                throw new DeviceFlowException(
                        DeviceFlowException.ErrorType.INVALID_RESPONSE,
                        "Failed to parse token response: " + e.getMessage(),
                        e);
            }
        } catch (IOException e) {
            logger.error("Network error during token polling", e);
            throw new DeviceFlowException(
                    DeviceFlowException.ErrorType.NETWORK_ERROR,
                    "Network error during token polling: " + e.getMessage(),
                    e);
        }
    }

    public CompletableFuture<String> authenticateWithDeviceFlow() {
        return authenticateWithDeviceFlow(true);
    }

    public CompletableFuture<String> authenticateWithDeviceFlow(boolean openBrowser) {
        logger.info("Starting GitHub device flow authentication");

        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        var deviceCodeResponse = requestDeviceCode();

                        if (deviceCodeResponse.hasCompleteUri()) {
                            logger.info("Please visit: {}", deviceCodeResponse.getPreferredVerificationUri());
                        } else {
                            logger.info(
                                    "Please visit: {} and enter code: {}",
                                    deviceCodeResponse.getPreferredVerificationUri(),
                                    deviceCodeResponse.userCode());
                        }

                        if (openBrowser) {
                            openVerificationUrl(deviceCodeResponse.getPreferredVerificationUri());
                        }

                        var tokenResponse = pollForToken(
                                        deviceCodeResponse.deviceCode(),
                                        Math.max(deviceCodeResponse.interval(), DEFAULT_POLL_INTERVAL))
                                .get();

                        if (tokenResponse.result() == DeviceFlowModels.TokenPollResult.SUCCESS
                                && tokenResponse.token() != null) {
                            logger.info("Device flow authentication completed successfully");
                            return tokenResponse.token().accessToken();
                        } else {
                            throw new RuntimeException("Authentication failed: " + tokenResponse.errorMessage());
                        }
                    } catch (Exception e) {
                        logger.error("Device flow authentication failed", e);
                        throw new RuntimeException("Device flow authentication failed: " + e.getMessage(), e);
                    }
                },
                executor);
    }

    private void openVerificationUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                logger.info("Opened browser to {}", url);
            } else {
                logger.warn("Desktop browsing not supported, please manually open: {}", url);
            }
        } catch (Exception e) {
            logger.warn("Failed to open browser: {}", e.getMessage());
        }
    }

    private void handleHttpError(int statusCode, String responseText) throws DeviceFlowException {
        logger.warn("HTTP error {}: {}", statusCode, responseText);

        DeviceFlowException.ErrorType errorType =
                switch (statusCode) {
                    case 429 -> DeviceFlowException.ErrorType.RATE_LIMITED;
                    case 400, 401, 403 -> DeviceFlowException.ErrorType.INVALID_RESPONSE;
                    case 500, 502, 503, 504 -> DeviceFlowException.ErrorType.SERVER_ERROR;
                    default -> DeviceFlowException.ErrorType.UNKNOWN;
                };

        throw new DeviceFlowException(errorType, String.format("HTTP %d: %s", statusCode, responseText));
    }

    public void shutdown() {
        logger.debug("Shutting down GitHubDeviceFlowService");
    }
}
