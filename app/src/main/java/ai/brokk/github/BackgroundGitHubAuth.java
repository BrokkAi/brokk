package ai.brokk.github;

import ai.brokk.ExceptionReporter;
import ai.brokk.GitHubAuth;
import ai.brokk.IContextManager;
import ai.brokk.MainProject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class BackgroundGitHubAuth {
    private static final Logger logger = LogManager.getLogger(BackgroundGitHubAuth.class);

    private static @Nullable CompletableFuture<Void> currentAuthFuture;
    private static @Nullable GitHubDeviceFlowService currentService;
    private static @Nullable ScheduledExecutorService currentExecutor;
    private static final Object authLock = new Object();

    // Injectable factory seam for testing
    static Supplier<GitHubDeviceFlowService> serviceFactory = null;

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
            // Cancel any existing background authentication
            cancelCurrentAuthUnsafe();

            // Create new service for this authentication with dedicated scheduler
            var executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BackgroundGitHubAuth-Scheduler");
                t.setDaemon(true);
                return t;
            });
            currentExecutor = executor;

            // Create service using injected factory or default constructor
            var service = (serviceFactory != null)
                    ? serviceFactory.get()
                    : new GitHubDeviceFlowService(GitHubAuthConfig.getClientId(), executor);
            currentService = service;

            // Start polling in background with cleanup via whenComplete
            var authFuture = service
                    .pollForToken(deviceCodeResponse.deviceCode(), deviceCodeResponse.interval())
                    .thenAccept(BackgroundGitHubAuth::handleAuthResult)
                    .whenComplete((result, throwable) -> {
                        // Cleanup after this auth attempt completes (success, error, or cancellation)
                        cleanupAuthAttempt(executor, throwable);
                    });

            currentAuthFuture = authFuture;
        }
    }

    private static void cleanupAuthAttempt(ScheduledExecutorService executor, @Nullable Throwable throwable) {
        if (throwable != null && !(throwable instanceof java.util.concurrent.CancellationException)) {
            logger.error("Background GitHub authentication failed", throwable);
        }

        synchronized (authLock) {
            // Clear service and future references
            currentService = null;
            currentAuthFuture = null;

            // Shutdown only the executor that was associated with this attempt
            try {
                executor.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down background auth executor", e);
            }

            // Clear currentExecutor if it matches the one we're shutting down
            if (currentExecutor == executor) {
                currentExecutor = null;
            }
        }
    }

    public static void cancelCurrentAuth() {
        synchronized (authLock) {
            cancelCurrentAuthUnsafe();
        }
    }

    private static void cancelCurrentAuthUnsafe() {
        if (currentAuthFuture != null && !currentAuthFuture.isDone()) {
            logger.info("Cancelling existing background GitHub authentication");
            currentAuthFuture.cancel(true);
        }

        currentService = null;

        // Capture executor reference and clear it before shutdown to avoid races
        var executor = currentExecutor;
        currentExecutor = null;

        if (executor != null) {
            try {
                executor.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down background auth executor during cancel", e);
            }
        }

        currentAuthFuture = null;
    }

    public static boolean isAuthInProgress() {
        synchronized (authLock) {
            return currentAuthFuture != null && !currentAuthFuture.isDone();
        }
    }

    private static void handleAuthResult(DeviceFlowModels.TokenPollResponse tokenResponse) {
        switch (tokenResponse.result()) {
            case SUCCESS -> {
                var token = tokenResponse.token();
                if (token != null && token.accessToken() != null) {
                    logger.info("Background GitHub authentication successful");
                    MainProject.setGitHubToken(token.accessToken());

                    // Validate the token immediately to ensure it works
                    if (GitHubAuth.validateStoredToken()) {
                        logger.info("GitHub token validated successfully");
                        GitHubAuth.invalidateInstance();
                    } else {
                        logger.error("GitHub token validation failed, clearing token");
                        MainProject.setGitHubToken("");
                    }
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
        // Cleanup is now handled by whenComplete() in startBackgroundAuth()
    }
}
