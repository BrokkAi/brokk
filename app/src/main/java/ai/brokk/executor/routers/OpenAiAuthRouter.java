package ai.brokk.executor.routers;

import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.openai.OpenAiOAuthService;
import ai.brokk.project.MainProject;
import com.sun.net.httpserver.HttpExchange;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

/**
 * Router for OpenAI OAuth flow endpoints.
 */
@NullMarked
public final class OpenAiAuthRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(OpenAiAuthRouter.class);

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        var path = exchange.getRequestURI().getPath();
        var normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;

        if (normalizedPath.equals("/v1/openai/oauth/start")) {
            if (!RouterUtil.ensureMethod(exchange, "POST")) {
                return;
            }
            try {
                OpenAiOAuthService.startAuthorization(null);
                SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "started"));
            } catch (Exception e) {
                logger.error("Error starting OpenAI OAuth flow", e);
                SimpleHttpServer.sendJsonResponse(
                        exchange, 500, ErrorPayload.internalError("Failed to start OpenAI OAuth", e));
            }
        } else if (normalizedPath.equals("/v1/openai/oauth/status")) {
            if (!RouterUtil.ensureMethod(exchange, "GET")) {
                return;
            }
            boolean connected = MainProject.isOpenAiCodexOauthConnected();
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("connected", connected));
        } else {
            SimpleHttpServer.sendJsonResponse(exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
        }
    }
}
