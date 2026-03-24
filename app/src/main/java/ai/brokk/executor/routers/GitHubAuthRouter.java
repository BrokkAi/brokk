package ai.brokk.executor.routers;

import ai.brokk.ContextManager;
import ai.brokk.GitHubAuth;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.github.BackgroundGitHubAuth;
import ai.brokk.github.DeviceFlowModels;
import ai.brokk.github.GitHubAuthConfig;
import ai.brokk.github.GitHubDeviceFlowService;
import ai.brokk.project.MainProject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles GitHub OAuth flow requests for the headless executor.
 */
public class GitHubAuthRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(GitHubAuthRouter.class);

    private final ContextManager contextManager;
    private final Supplier<DeviceFlowModels.DeviceCodeResponse> deviceCodeSupplier;
    private final BiConsumer<DeviceFlowModels.DeviceCodeResponse, ContextManager> authStarter;
    private final Supplier<Boolean> connectedSupplier;
    private final Supplier<Boolean> authInProgressSupplier;
    private final Supplier<BackgroundGitHubAuth.AuthStatus> statusSupplier;
    private final Supplier<String> usernameSupplier;
    private final Runnable logoutHook;

    public GitHubAuthRouter(ContextManager contextManager) {
        this(
                contextManager,
                () -> {
                    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                    try {
                        return new GitHubDeviceFlowService(GitHubAuthConfig.getClientId(), scheduler)
                                .requestDeviceCode();
                    } catch (Exception e) {
                        logger.error("Failed to request GitHub device code", e);
                        return null;
                    } finally {
                        scheduler.shutdown();
                        try {
                            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                                scheduler.shutdownNow();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                },
                BackgroundGitHubAuth::startBackgroundAuth,
                () -> !MainProject.getGitHubToken().isBlank(),
                BackgroundGitHubAuth::isAuthInProgress,
                BackgroundGitHubAuth::currentStatus,
                GitHubAuth::getAuthenticatedUsername,
                () -> {
                    BackgroundGitHubAuth.cancelCurrentAuth();
                    MainProject.setGitHubToken("");
                    GitHubAuth.invalidateInstance();
                });
    }

    // Testing constructor
    GitHubAuthRouter(
            ContextManager contextManager,
            Supplier<DeviceFlowModels.DeviceCodeResponse> deviceCodeSupplier,
            BiConsumer<DeviceFlowModels.DeviceCodeResponse, ContextManager> authStarter,
            Supplier<Boolean> connectedSupplier,
            Supplier<Boolean> authInProgressSupplier,
            Supplier<BackgroundGitHubAuth.AuthStatus> statusSupplier,
            Supplier<String> usernameSupplier,
            Runnable logoutHook) {
        this.contextManager = contextManager;
        this.deviceCodeSupplier = deviceCodeSupplier;
        this.authStarter = authStarter;
        this.connectedSupplier = connectedSupplier;
        this.authInProgressSupplier = authInProgressSupplier;
        this.statusSupplier = statusSupplier;
        this.usernameSupplier = usernameSupplier;
        this.logoutHook = logoutHook;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        try {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/v1/github/oauth/start" -> handleStart(exchange);
                case "/v1/github/oauth/status" -> handleStatus(exchange);
                case "/v1/github/oauth/authorization" -> handleAuthorization(exchange);
                default -> SimpleHttpServer.sendJsonResponse(
                        exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
            }
        } catch (Exception e) {
            logger.error("Error handling GitHub auth request", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError(e.getMessage(), e));
        }
    }

    private void handleStart(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;

        var response = deviceCodeSupplier.get();
        if (response == null) {
            SimpleHttpServer.sendJsonResponse(
                    exchange,
                    500,
                    ErrorPayload.of(ErrorPayload.Code.INTERNAL_ERROR, "Failed to initialize GitHub auth flow"));
            return;
        }
        authStarter.accept(response, contextManager);

        String preferredVerificationUri = response.getPreferredVerificationUri();
        boolean hasCompleteUri = !preferredVerificationUri.equals(response.verificationUri());

        SimpleHttpServer.sendJsonResponse(
                exchange,
                200,
                Map.of(
                        "status", "started",
                        "verificationUri", preferredVerificationUri,
                        "userCode", response.userCode(),
                        "expiresIn", response.expiresIn(),
                        "interval", response.interval(),
                        "hasCompleteUri", hasCompleteUri));
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) return;

        boolean connected = connectedSupplier.get();
        var status = statusSupplier.get();

        Map<String, Object> result = new HashMap<>();
        result.put("connected", connected);
        result.put("authInProgress", authInProgressSupplier.get());
        result.put("state", status.state());
        result.put("message", status.message());

        if (connected) {
            result.put("username", usernameSupplier.get());
        }

        SimpleHttpServer.sendJsonResponse(exchange, 200, result);
    }

    private void handleAuthorization(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "DELETE")) return;

        logoutHook.run();
        SimpleHttpServer.sendJsonResponse(exchange, 200, Map.of("status", "disconnected"));
    }
}
