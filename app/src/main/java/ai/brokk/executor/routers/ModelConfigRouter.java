package ai.brokk.executor.routers;

import ai.brokk.AbstractService.ModelConfig;
import ai.brokk.AbstractService.ProcessingTier;
import ai.brokk.AbstractService.ReasoningLevel;
import ai.brokk.ContextManager;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.project.ModelProperties.ModelType;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ModelConfigRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(ModelConfigRouter.class);

    private final ContextManager contextManager;

    public ModelConfigRouter(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    private record UpdateModelConfigRequest(
            @Nullable String role, @Nullable String model, @Nullable String reasoning, @Nullable String tier) {}

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        switch (exchange.getRequestMethod()) {
            case "GET" -> handleGet(exchange);
            case "POST" -> handlePost(exchange);
            default -> RouterUtil.sendMethodNotAllowed(exchange);
        }
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        try {
            var project = contextManager.getProject();
            var architect = project.getModelConfig(ModelType.ARCHITECT);
            var code = project.getModelConfig(ModelType.CODE);
            SimpleHttpServer.sendJsonResponse(
                    exchange,
                    Map.of(
                            "architect", Map.of(
                                    "model", architect.name(),
                                    "reasoning", architect.reasoning().toString().toLowerCase(Locale.ROOT),
                                    "tier", architect.tier().toString().toLowerCase(Locale.ROOT)),
                            "code", Map.of(
                                    "model", code.name(),
                                    "reasoning", code.reasoning().toString().toLowerCase(Locale.ROOT),
                                    "tier", code.tier().toString().toLowerCase(Locale.ROOT))));
        } catch (Exception e) {
            logger.error("Error handling GET /v1/model-config", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to retrieve model config", e));
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        var request = RouterUtil.parseJsonOr400(exchange, UpdateModelConfigRequest.class, "/v1/model-config");
        if (request == null) {
            return;
        }

        var roleRaw = request.role();
        var modelRaw = request.model();
        if (roleRaw == null || roleRaw.isBlank()) {
            RouterUtil.sendValidationError(exchange, "role is required");
            return;
        }
        if (modelRaw == null || modelRaw.isBlank()) {
            RouterUtil.sendValidationError(exchange, "model is required");
            return;
        }

        final ModelType modelType;
        try {
            modelType = ModelType.valueOf(roleRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(exchange, "role must be CODE or ARCHITECT");
            return;
        }
        if (modelType != ModelType.CODE && modelType != ModelType.ARCHITECT) {
            RouterUtil.sendValidationError(exchange, "role must be CODE or ARCHITECT");
            return;
        }

        var project = contextManager.getProject();
        var current = project.getModelConfig(modelType);
        var reasoning = ReasoningLevel.fromString(request.reasoning(), current.reasoning());
        var tier = ProcessingTier.fromString(request.tier());
        if (tier == null) {
            tier = current.tier();
        }

        var config = new ModelConfig(modelRaw.trim(), reasoning, tier);

        try {
            project.setModelConfig(modelType, config);
            SimpleHttpServer.sendJsonResponse(
                    exchange,
                    Map.of(
                            "role", modelType.name(),
                            "model", config.name(),
                            "reasoning", config.reasoning().toString().toLowerCase(Locale.ROOT),
                            "tier", config.tier().toString().toLowerCase(Locale.ROOT)));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/model-config", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to update model config", e));
        }
    }
}
