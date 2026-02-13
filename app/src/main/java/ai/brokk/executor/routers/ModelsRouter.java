package ai.brokk.executor.routers;

import ai.brokk.ContextManager;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

/**
 * Router for /v1/models endpoint.
 */
@NullMarked
public final class ModelsRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(ModelsRouter.class);

    private final ContextManager contextManager;

    public ModelsRouter(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }
        try {
            var service = contextManager.getService();
            var availableModels = service.getAvailableModels();
            var models = availableModels.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> {
                        var model = new HashMap<String, Object>();
                        var name = entry.getKey();
                        model.put("name", name);
                        model.put("location", entry.getValue());
                        model.put("supportsReasoningEffort", service.supportsReasoningEffort(name));
                        model.put("supportsReasoningDisable", service.supportsReasoningDisable(name));
                        return model;
                    })
                    .toList();

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("models", models));
        } catch (Exception e) {
            logger.error("Error handling GET /v1/models", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to retrieve models", e));
        }
    }
}
