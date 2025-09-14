package io.github.jbellis.brokk.github;

import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.MainProject;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Singleton service for managing GitHub authentication in the background. Allows authentication to continue after
 * dialog is closed.
 */
public class BackgroundGitHubAuth {
    private static final Logger logger = LogManager.getLogger(BackgroundGitHubAuth.class);

    private static @Nullable CompletableFuture<Void> currentAuthFuture;
    private static @Nullable GitHubDeviceFlowService currentService;

    /**
     * Start background authentication with the given device code response. Cancels any existing background
     * authentication.
     */
    public static void startBackgroundAuth(DeviceFlowModels.DeviceCodeResponse deviceCodeResponse) {
        logger.info("Starting background GitHub authentication");

        // Cancel any existing background authentication
        cancelCurrentAuth();

        // Create new service for this authentication
        currentService = new GitHubDeviceFlowService(getClientId());

        // Start polling in background
        currentAuthFuture = currentService
                .pollForToken(deviceCodeResponse.deviceCode(), deviceCodeResponse.interval())
                .thenAccept(tokenResponse -> {
                    handleAuthResult(tokenResponse);
                })
                .exceptionally(throwable -> {
                    logger.error("Background GitHub authentication failed", throwable);
                    // Silent failure - no user notification
                    return null;
                });
    }

    /** Cancel any currently running background authentication. */
    public static void cancelCurrentAuth() {
        if (currentAuthFuture != null && !currentAuthFuture.isDone()) {
            logger.info("Cancelling existing background GitHub authentication");
            currentAuthFuture.cancel(true);
        }

        var service = currentService;
        if (service != null) {
            CompletableFuture.runAsync(() -> service.shutdown());
            currentService = null;
        }

        currentAuthFuture = null;
    }

    /** Check if background authentication is currently running. */
    public static boolean isAuthInProgress() {
        return currentAuthFuture != null && !currentAuthFuture.isDone();
    }

    private static void handleAuthResult(DeviceFlowModels.TokenPollResponse tokenResponse) {
        switch (tokenResponse.result()) {
            case SUCCESS -> {
                var token = tokenResponse.token();
                if (token != null && token.accessToken() != null) {
                    logger.info("Background GitHub authentication successful");
                    MainProject.setGitHubToken(token.accessToken());
                    GitHubAuth.invalidateInstance();
                } else {
                    logger.error("Background GitHub authentication returned null token");
                }
            }
            case DENIED -> {
                logger.info("Background GitHub authentication denied by user");
                // Silent failure - no user notification
            }
            case EXPIRED -> {
                logger.info("Background GitHub authentication code expired");
                // Silent failure - no user notification
            }
            case ERROR -> {
                logger.error("Background GitHub authentication error: {}", tokenResponse.errorMessage());
                // Silent failure - no user notification
            }
            default -> {
                logger.error("Background GitHub authentication unexpected result: {}", tokenResponse.result());
            }
        }

        // Clean up after completion
        currentAuthFuture = null;
        var service = currentService;
        if (service != null) {
            CompletableFuture.runAsync(() -> service.shutdown());
            currentService = null;
        }
    }

    private static String getClientId() {
        // Use same client ID logic as GitHubAuthDialog
        var clientId = System.getenv("BROKK_GITHUB_CLIENT_ID");
        if (clientId != null && !clientId.isBlank()) {
            return clientId;
        }
        return "Iv23liZ3oStCdzu0xkHI";
    }
}
