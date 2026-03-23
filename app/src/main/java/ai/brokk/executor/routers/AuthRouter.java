package ai.brokk.executor.routers;

import ai.brokk.BrokkAuthValidation;
import ai.brokk.ContextManager;
import ai.brokk.Service;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.project.MainProject;
import com.sun.net.httpserver.HttpExchange;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Router for Brokk auth-related endpoints.
 */
public final class AuthRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(AuthRouter.class);

    private final ContextManager contextManager;
    private final Supplier<String> keySupplier;
    private final Function<String, BrokkAuthValidation> validator;

    public AuthRouter(ContextManager contextManager) {
        this(contextManager, MainProject::getBrokkKey, Service::validateBrokkAuth);
    }

    AuthRouter(
            ContextManager contextManager,
            Supplier<String> keySupplier,
            Function<String, BrokkAuthValidation> validator) {
        this.contextManager = contextManager;
        this.keySupplier = keySupplier;
        this.validator = validator;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        var path = exchange.getRequestURI().getPath();
        var normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;

        if (!normalizedPath.equals("/v1/auth/validate")) {
            SimpleHttpServer.sendJsonResponse(exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
            return;
        }

        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }

        try {
            var key = keySupplier.get();
            var validation = validator.apply(key);
            contextManager.getIo().brokkAuthValidationUpdated(validation);

            var payload = new HashMap<String, Object>();
            payload.put("state", validation.state().name());
            payload.put("valid", validation.valid());
            payload.put("subscribed", validation.subscribed());
            payload.put("hasBalance", validation.hasBalance());
            payload.put("balanceDisplay", validation.balanceDisplay());
            payload.put("message", validation.message());
            if (validation.hasBalance()) {
                payload.put("balance", validation.balance());
            }
            SimpleHttpServer.sendJsonResponse(exchange, payload);
        } catch (Exception e) {
            logger.error("Error handling GET /v1/auth/validate", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to validate Brokk API key", e));
        }
    }
}
