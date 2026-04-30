package ai.brokk.executor.routers;

import ai.brokk.IAppContextManager;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.staticanalysis.StaticAnalysisLeadExpansionService;
import ai.brokk.executor.staticanalysis.StaticAnalysisSeedDtos;
import ai.brokk.executor.staticanalysis.StaticAnalysisSeedService;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class StaticAnalysisRouter implements SimpleHttpServer.CheckedHttpHandler {
    private final StaticAnalysisSeedService seedService;
    private final StaticAnalysisLeadExpansionService expansionService;
    private final ExecutorService expansionExecutor;

    public StaticAnalysisRouter(IAppContextManager contextManager) {
        this(new StaticAnalysisSeedService(contextManager), new StaticAnalysisLeadExpansionService(contextManager));
    }

    StaticAnalysisRouter(StaticAnalysisSeedService seedService, StaticAnalysisLeadExpansionService expansionService) {
        this.seedService = seedService;
        this.expansionService = expansionService;
        this.expansionExecutor = Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "StaticAnalysisLeadExpansion");
            thread.setDaemon(true);
            return thread;
        });
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

        if (normalizedPath.equals("/v1/static-analysis/lead-expansion")) {
            if (!"POST".equals(method)) {
                RouterUtil.sendMethodNotAllowed(exchange);
                return;
            }
            handleLeadExpansionRequest(exchange);
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

    private void handleLeadExpansionRequest(HttpExchange exchange) throws IOException {
        var request = RouterUtil.parseJsonOr400(
                exchange, StaticAnalysisSeedDtos.LeadExpansionRequest.class, "/v1/static-analysis/lead-expansion");
        if (request == null) return;

        var normalized = request.normalized();
        if (normalized.scanId().isBlank()) {
            RouterUtil.sendValidationError(exchange, "scanId is required");
            return;
        }
        if (normalized.maxResults() < 1 || normalized.maxResults() > 100) {
            RouterUtil.sendValidationError(exchange, "maxResults must be between 1 and 100");
            return;
        }
        if (normalized.maxDurationMs() < 1 || normalized.maxDurationMs() > 120_000) {
            RouterUtil.sendValidationError(exchange, "maxDurationMs must be between 1 and 120000");
            return;
        }
        if (normalized.knownFiles().isEmpty() && normalized.frontierFiles().isEmpty()) {
            SimpleHttpServer.sendJsonResponse(
                    exchange,
                    new StaticAnalysisSeedDtos.Response(
                            normalized.scanId(),
                            StaticAnalysisSeedDtos.PHASE_STATIC_SEED,
                            "skipped",
                            List.of(),
                            List.of(),
                            List.of(new StaticAnalysisSeedDtos.Event(
                                    UUID.randomUUID().toString(),
                                    normalized.scanId(),
                                    StaticAnalysisSeedDtos.PHASE_STATIC_SEED,
                                    "skipped",
                                    List.of("usage_analysis"),
                                    List.of(),
                                    null,
                                    null,
                                    new StaticAnalysisSeedDtos.Outcome(
                                            "STATIC_SEED_EXPANSION_NO_INPUTS",
                                            "No known or frontier files were provided for usage expansion.",
                                            0,
                                            List.of()),
                                    List.of()))));
            return;
        }

        var future = expansionExecutor.submit(() -> expansionService.expandLeads(normalized));
        try {
            SimpleHttpServer.sendJsonResponse(
                    exchange, future.get(normalized.maxDurationMs() + 1_000L, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            SimpleHttpServer.sendJsonResponse(exchange, expansionFailure(normalized.scanId(), "interrupted"));
        } catch (ExecutionException e) {
            SimpleHttpServer.sendJsonResponse(exchange, expansionFailure(normalized.scanId(), failureReason(e)));
        } catch (TimeoutException e) {
            future.cancel(true);
            SimpleHttpServer.sendJsonResponse(exchange, expansionFailure(normalized.scanId(), "timed out"));
        }
    }

    private static String failureReason(ExecutionException e) {
        var cause = e.getCause();
        if (cause == null) {
            return e.toString();
        }
        var message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    private static StaticAnalysisSeedDtos.Response expansionFailure(String scanId, String reason) {
        return new StaticAnalysisSeedDtos.Response(
                scanId,
                StaticAnalysisSeedDtos.PHASE_STATIC_SEED,
                "failed",
                List.of(),
                List.of(),
                List.of(new StaticAnalysisSeedDtos.Event(
                        UUID.randomUUID().toString(),
                        scanId,
                        StaticAnalysisSeedDtos.PHASE_STATIC_SEED,
                        "failed",
                        List.of("usage_analysis"),
                        List.of(),
                        null,
                        null,
                        new StaticAnalysisSeedDtos.Outcome(
                                "STATIC_SEED_EXPANSION_ERROR", "Usage expansion failed: " + reason, 0, List.of()),
                        List.of())));
    }
}
