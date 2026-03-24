package ai.brokk.github;

import ai.brokk.ExceptionReporter;
import ai.brokk.GitHubAuth;
import ai.brokk.IContextManager;
import ai.brokk.concurrent.ExecutorsUtil;
import ai.brokk.project.MainProject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class BackgroundGitHubAuth {
    private static final Logger logger = LogManager.getLogger(BackgroundGitHubAuth.class);

    public record AuthStatus(String state, String message) {}

    private static AuthStatus status = new AuthStatus("IDLE", "");
    private static @Nullable CompletableFuture<Void> currentAuthFuture;
    private static @Nullable GitHubDeviceFlowService currentService;
    private static @Nullable ScheduledExecutorService currentExecutor;
    private static final Object authLock = new Object();

    public static AuthStatus currentStatus() {
        synchronized (authLock) {
            return status;
        }
    }

    static {
        // Register shutdown hook to clean up background auth on app exit
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            logger.debug("Shutdown hook: Cancelling background GitHub authentication");
                            cancelCurrentAuth();
                        },
                        "GitHubAuth-Shutdown"));
    }

    public static void startBackgroundAuth(
            DeviceFlowModels.DeviceCodeResponse deviceCodeResponse, IContextManager contextManager) {
        logger.info("Starting background GitHub authentication");

        synchronized (authLock) {
            status = new AuthStatus("PENDING", "Waiting for user authorization...");
            // Cancel any existing background authentication
            cancelCurrentAuthUnsafe();

            // Create new service for this authentication with dedicated scheduler
            var executor = Executors.newSingleThreadScheduledExecutor(
                    ExecutorsUtil.createNamedThreadFactory("BackgroundGitHubAuth-Scheduler"));
            currentExecutor = executor;
            currentService = new GitHubDeviceFlowService(GitHubAuthConfig.getClientId(), executor);

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
    }

    public static void cancelCurrentAuth() {
        synchronized (authLock) {
            status = new AuthStatus("CANCELLED", "Authentication cancelled by user.");
            cancelCurrentAuthUnsafe();
        }
    }

    private static void cancelCurrentAuthUnsafe() {
        if (currentAuthFuture != null && !currentAuthFuture.isDone()) {
            logger.info("Cancelling existing background GitHub authentication");
            currentAuthFuture.cancel(true);
        }

        currentService = null;

        var executor = currentExecutor;
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    logger.warn("Background GitHub auth executor did not terminate in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            currentExecutor = null;
        }

        currentAuthFuture = null;
    }

    public static boolean isAuthInProgress() {
        synchronized (authLock) {
            return currentAuthFuture != null && !currentAuthFuture.isDone();
        }
    }

    private static void handleAuthResult(DeviceFlowModels.TokenPollResponse tokenResponse) {
        synchronized (authLock) {
            switch (tokenResponse.result()) {
                case SUCCESS -> {
                    var token = tokenResponse.token();
                    if (token != null && token.accessToken() != null) {
                        logger.info("Background GitHub authentication successful");
                        MainProject.setGitHubToken(token.accessToken());

                        // Validate the token immediately to ensure it works
                        var validationResult = GitHubAuth.validateStoredTokenWithResult();
                        switch (validationResult) {
                            case VALID -> {
                                logger.info("GitHub token validated successfully");
                                GitHubAuth.invalidateInstance();
                                status = new AuthStatus("SUCCESS", "Successfully connected to GitHub.");
                            }
                            case INVALID -> {
                                logger.error("GitHub token is invalid (401), clearing token");
                                MainProject.setGitHubToken("");
                                status = new AuthStatus("ERROR", "Received invalid token from GitHub.");
                            }
                            case TRANSIENT_ERROR -> {
                                logger.warn("Could not validate GitHub token due to transient error, keeping token");
                                GitHubAuth.invalidateInstance();
                                status = new AuthStatus("SUCCESS", "Connected (validation deferred).");
                            }
                        }
                    } else {
                        logger.error("Background GitHub authentication returned null token");
                        status = new AuthStatus("ERROR", "GitHub returned an empty token.");
                    }
                }
                case DENIED -> {
                    logger.info("Background GitHub authentication denied by user");
                    status = new AuthStatus("DENIED", "Authentication was denied by the user.");
                }
                case EXPIRED -> {
                    logger.info("Background GitHub authentication code expired");
                    status = new AuthStatus("EXPIRED", "The authentication code has expired.");
                }
                case ERROR -> {
                    logger.error("Background GitHub authentication error: {}", tokenResponse.errorMessage());
                    status = new AuthStatus("ERROR", tokenResponse.errorMessage());
                }
                case SLOW_DOWN -> {
                    logger.warn("Background GitHub authentication received SLOW_DOWN in final result (unexpected)");
                    // This shouldn't happen as SLOW_DOWN is handled in polling loop
                }
                default -> {
                    RuntimeException ex = new IllegalStateException(
                            "Background GitHub authentication unexpected result: " + tokenResponse.result());
                    logger.error("Background GitHub authentication unexpected result: {}", tokenResponse.result());
                    ExceptionReporter.tryReportException(ex);
                }
            }

            // Clean up after completion
            currentAuthFuture = null;
            currentService = null;

            var executor = currentExecutor;
            if (executor != null) {
                executor.shutdownNow();
                try {
                    executor.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                currentExecutor = null;
            }
        }
    }
}
