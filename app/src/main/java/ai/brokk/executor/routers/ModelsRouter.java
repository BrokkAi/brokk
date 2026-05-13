package ai.brokk.executor.routers;

import ai.brokk.AbstractService.ModelTokenBudget;
import ai.brokk.ContextManager;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import com.sun.net.httpserver.HttpExchange;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
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
            var path = exchange.getRequestURI().getPath();
            if (path.startsWith("/v1/models/") && path.endsWith("/budget")) {
                handleBudgetLookup(exchange, path);
                return;
            }
            if (!path.equals("/v1/models")) {
                SimpleHttpServer.sendJsonResponse(
                        exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Model endpoint not found"));
                return;
            }

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
                        addBudgetFields(model, service.getModelTokenBudget(name).orElse(null));
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

    private void handleBudgetLookup(HttpExchange exchange, String path) throws Exception {
        var encodedName = path.substring("/v1/models/".length(), path.length() - "/budget".length());
        if (encodedName.isBlank()) {
            RouterUtil.sendValidationError(exchange, "model name is required");
            return;
        }

        var modelName = URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
        var service = contextManager.getService();
        var budget = service.getAvailableModels().containsKey(modelName)
                ? service.getModelTokenBudget(modelName)
                : Optional.<ModelTokenBudget>empty();
        if (budget.isEmpty()) {
            SimpleHttpServer.sendJsonResponse(
                    exchange,
                    404,
                    ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Token budget not found for model: " + modelName));
            return;
        }

        var response = new HashMap<String, Object>();
        response.put("model", modelName);
        addBudgetFields(response, budget.get());
        SimpleHttpServer.sendJsonResponse(exchange, response);
    }

    private static void addBudgetFields(Map<String, Object> model, @Nullable ModelTokenBudget budget) {
        model.put("budgetAvailable", budget != null);
        model.put("tokensEstimated", budget != null && budget.tokensEstimated());
        if (budget == null) {
            return;
        }
        model.put("maxInputTokens", budget.maxInputTokens());
        model.put("maxOutputTokens", budget.maxOutputTokens());
    }
}
