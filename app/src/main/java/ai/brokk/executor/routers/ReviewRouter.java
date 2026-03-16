package ai.brokk.executor.routers;

import ai.brokk.executor.JobReservation;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStore;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class ReviewRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(ReviewRouter.class);

    private final JobStore jobStore;
    private final JobRunner jobRunner;
    private final JobReservation jobReservation;
    private final CompletableFuture<Void> headlessInit;

    public ReviewRouter(
            JobStore jobStore,
            JobRunner jobRunner,
            JobReservation jobReservation,
            CompletableFuture<Void> headlessInit) {
        this.jobStore = jobStore;
        this.jobRunner = jobRunner;
        this.jobReservation = jobReservation;
        this.headlessInit = headlessInit;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        var path = exchange.getRequestURI().getPath();
        var method = exchange.getRequestMethod();

        if (path.equals("/v1/review/submit") && method.equals("POST")) {
            handleSubmitReview(exchange);
            return;
        }

        SimpleHttpServer.sendJsonResponse(exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
    }

    private void handleSubmitReview(HttpExchange exchange) throws IOException {
        var idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            RouterUtil.sendValidationError(exchange, "Idempotency-Key header is required");
            return;
        }

        var request = RouterUtil.parseJsonOr400(exchange, ReviewSubmitRequest.class, "/v1/review/submit");
        if (request == null) return;

        if (request.plannerModel() == null || request.plannerModel().isBlank()) {
            RouterUtil.sendValidationError(exchange, "plannerModel is required");
            return;
        }

        var tags = new HashMap<String, String>();
        tags.put("mode", "GUIDED_REVIEW");

        JobSpec.ModelOverrides noOverrides = null;
        var jobSpec = JobSpec.of(
                "Guided code review",
                false,
                false,
                request.plannerModel(),
                null,
                null,
                false,
                Map.copyOf(tags),
                noOverrides);

        try {
            var createResult = jobStore.createOrGetJob(idempotencyKey, jobSpec);
            var jobId = createResult.jobId();
            var isNewJob = createResult.isNewJob();

            var status = jobStore.loadStatus(jobId);
            var state = status != null ? status.state() : "queued";

            var response = new HashMap<String, Object>();
            response.put("jobId", jobId);
            response.put("state", state);

            if (isNewJob) {
                if (!jobReservation.tryReserve(jobId)) {
                    SimpleHttpServer.sendJsonResponse(
                            exchange, 409, ErrorPayload.of("JOB_IN_PROGRESS", "A job is currently executing"));
                    return;
                }
                try {
                    try {
                        headlessInit.get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        jobReservation.releaseIfOwner(jobId);
                        var payload = ErrorPayload.of("NOT_READY", "Executor is still initializing");
                        SimpleHttpServer.sendJsonResponse(exchange, 503, payload);
                        return;
                    }

                    jobRunner.runAsync(jobId, jobSpec).whenComplete((unused, throwable) -> {
                        jobReservation.releaseIfOwner(jobId);
                    });

                    SimpleHttpServer.sendJsonResponse(exchange, 201, response);
                } catch (Exception e) {
                    jobReservation.releaseIfOwner(jobId);
                    logger.error("Failed to start review job {}", jobId, e);
                    SimpleHttpServer.sendJsonResponse(
                            exchange, 500, ErrorPayload.internalError("Failed to start job", e));
                }
            } else {
                SimpleHttpServer.sendJsonResponse(exchange, 200, response);
            }
        } catch (Exception e) {
            logger.error("Failed to create review job", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to create job", e));
        }
    }

    private record ReviewSubmitRequest(@Nullable String plannerModel) {}
}
