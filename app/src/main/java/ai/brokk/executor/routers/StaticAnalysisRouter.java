package ai.brokk.executor.routers;

import ai.brokk.IAppContextManager;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.staticanalysis.StaticAnalysisSeedDtos;
import ai.brokk.executor.staticanalysis.StaticAnalysisSeedService;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class StaticAnalysisRouter implements SimpleHttpServer.CheckedHttpHandler {
    private final StaticAnalysisSeedService seedService;

    public StaticAnalysisRouter(IAppContextManager contextManager) {
        this(new StaticAnalysisSeedService(contextManager));
    }

    StaticAnalysisRouter(StaticAnalysisSeedService seedService) {
        this.seedService = seedService;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        var method = exchange.getRequestMethod();
        var path = exchange.getRequestURI().getPath();
        var normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;

        if (normalizedPath.equals("/v1/static-analysis/seeds")) {
            if (!"POST".equals(method)) {
                RouterUtil.sendMethodNotAllowed(exchange);
                return;
            }
            handleSeedRequest(exchange);
            return;
        }

        SimpleHttpServer.sendJsonResponse(exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
    }

    private void handleSeedRequest(HttpExchange exchange) throws IOException {
        var request =
                RouterUtil.parseJsonOr400(exchange, StaticAnalysisSeedDtos.Request.class, "/v1/static-analysis/seeds");
        if (request == null) return;

        var normalized = request.normalized();
        if (normalized.scanId().isBlank()) {
            RouterUtil.sendValidationError(exchange, "scanId is required");
            return;
        }
        if (normalized.targetSeedCount() < 1 || normalized.targetSeedCount() > 100) {
            RouterUtil.sendValidationError(exchange, "targetSeedCount must be between 1 and 100");
            return;
        }
        if (normalized.maxDurationMs() < 1 || normalized.maxDurationMs() > 120_000) {
            RouterUtil.sendValidationError(exchange, "maxDurationMs must be between 1 and 120000");
            return;
        }

        SimpleHttpServer.sendJsonResponse(exchange, seedService.fetchSeeds(normalized));
    }
}
