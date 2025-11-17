package ai.brokk.github;

import ai.brokk.ExceptionReporter;
import ai.brokk.GitHubAuth;
import ai.brokk.IContextManager;
import ai.brokk.MainProject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class BackgroundGitHubAuth {
    private static final Logger logger = LogManager.getLogger(BackgroundGitHubAuth.class);

    private static @Nullable CompletableFuture<Void> currentAuthFuture;
    private static @Nullable GitHubDeviceFlowService currentService;
    private static @Nullable ScheduledExecutorService currentExecutor;
    private static final Object authLock = new Object();

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
            executor.shutdown();
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

        // Clean up after completion
        synchronized (authLock) {
            currentAuthFuture = null;
            currentService = null;

            var executor = currentExecutor;
            if (executor != null) {
                executor.shutdown();
                currentExecutor = null;
            }
        }
    }
}
