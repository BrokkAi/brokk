package ai.brokk.executor.routers;

import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.openai.OpenAiOAuthService;
import com.sun.net.httpserver.HttpExchange;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

/**
 * Router for /v1/openai/oauth/start endpoint.
 */
@NullMarked
public final class OpenAiOAuthRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(OpenAiOAuthRouter.class);

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            OpenAiOAuthService.startAuthorization(null);
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "started"));
        } catch (Exception e) {
            logger.error("Error starting OpenAI OAuth flow", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to start OpenAI OAuth flow", e));
        }
    }
}
