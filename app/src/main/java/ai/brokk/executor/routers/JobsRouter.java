package ai.brokk.executor.routers;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.brokk.ContextManager;
import ai.brokk.context.ContextFragment;
import ai.brokk.executor.JobReservation;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.executor.jobs.PrReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Router for /v1/jobs endpoints.
 */
@NullMarked
public final class JobsRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(JobsRouter.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final ai.brokk.AbstractService.ReasoningLevel[] REASONING_LEVEL_VALUES =
            ai.brokk.AbstractService.ReasoningLevel.values();
    private static final Set<String> ALLOWED_REASONING_LEVELS =
            Arrays.stream(REASONING_LEVEL_VALUES).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    private static final String ALLOWED_REASONING_LEVELS_LIST =
            Arrays.stream(REASONING_LEVEL_VALUES).map(Enum::name).collect(Collectors.joining(", "));

    final ContextManager contextManager;
    private final JobStore jobStore;
    private final JobRunner jobRunner;
    private final JobReservation jobReservation;
    private final CompletableFuture<Void> headlessInit;

    public JobsRouter(
            ContextManager contextManager,
            JobStore jobStore,
            JobRunner jobRunner,
            JobReservation jobReservation,
            CompletableFuture<Void> headlessInit) {
        this.contextManager = contextManager;
        this.jobStore = jobStore;
        this.jobRunner = jobRunner;
        this.jobReservation = jobReservation;
        this.headlessInit = headlessInit;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        var path = exchange.getRequestURI().getPath();
        var method = exchange.getRequestMethod();

        if (path.equals("/v1/jobs") && method.equals("POST")) {
            handlePostJobs(exchange);
            return;
        }

        if (path.equals("/v1/jobs/issue") && method.equals("POST")) {
            handlePostIssueJob(exchange);
            return;
        }

        if (path.equals("/v1/jobs/pr-review") && method.equals("POST")) {
            handlePostPrReviewJob(exchange);
            return;
        }

        var jobId = RouterUtil.extractJobIdFromPath(path);
        if (jobId == null || jobId.isBlank()) {
            SimpleHttpServer.sendJsonResponse(
                    exchange, 400, ErrorPayload.of(ErrorPayload.Code.BAD_REQUEST, "Invalid job path"));
            return;
        }

        if (path.equals("/v1/jobs/" + jobId) && method.equals("GET")) {
            handleGetJob(exchange, jobId);
            return;
        }

        if (path.equals("/v1/jobs/" + jobId + "/events") && method.equals("GET")) {
            handleGetJobEvents(exchange, jobId);
            return;
        }

        if (path.equals("/v1/jobs/" + jobId + "/cancel") && method.equals("POST")) {
            handleCancelJob(exchange, jobId);
            return;
        }

        if (path.equals("/v1/jobs/" + jobId + "/diff") && method.equals("GET")) {
            handleGetJobDiff(exchange, jobId);
            return;
        }

        SimpleHttpServer.sendJsonResponse(exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
    }

    private void handlePostJobs(HttpExchange exchange) throws IOException {
        var idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            RouterUtil.sendValidationError(exchange, "Idempotency-Key header is required");
            return;
        }
        var sessionIdStr = exchange.getRequestHeaders().getFirst("X-Session-Id");
        UUID sessionIdHeader = null;
        if (sessionIdStr != null && !sessionIdStr.isBlank()) {
            try {
                sessionIdHeader = UUID.fromString(sessionIdStr);
            } catch (IllegalArgumentException e) {
                RouterUtil.sendValidationError(exchange, "Invalid Session-Id format: must be a valid UUID");
                return;
            }
        }
        var githubToken = exchange.getRequestHeaders().getFirst("X-Github-Token");

        var request = RouterUtil.parseJsonOr400(exchange, JobSpecRequest.class, "/v1/jobs");
        if (request == null) return;

        var plannerModel =
                Objects.requireNonNullElse(request.plannerModel(), "").strip();
        if (plannerModel.isBlank()) {
            RouterUtil.sendValidationError(exchange, "plannerModel is required");
            return;
        }

        var tags = request.tags() != null ? new HashMap<>(request.tags()) : new HashMap<String, String>();
        if (sessionIdHeader != null) tags.put("session_id", sessionIdHeader.toString());
        if (githubToken != null && !githubToken.isBlank()) tags.put("github_token", githubToken);

        var overrides = validateModelOverrides(exchange, request);
        if (overrides == null) return;

        var validJobContextTexts = validateContextTexts(exchange, request);
        if (validJobContextTexts == null && (request.contextText() != null || request.context() != null)) return;

        boolean isIssueMode = tags.getOrDefault("mode", "").equalsIgnoreCase("ISSUE");
        boolean skipVerificationFlag = isIssueMode && Boolean.TRUE.equals(request.skipVerification());

        var jobSpec = new JobSpec(
                request.taskInput(),
                request.autoCommit(),
                request.autoCompress(),
                plannerModel,
                request.scanModel(),
                request.codeModel(),
                Objects.requireNonNullElse(request.preScan(), false),
                Map.copyOf(tags),
                null,
                null,
                overrides.reasoningLevel(),
                overrides.reasoningLevelCode(),
                overrides.temperature(),
                overrides.temperatureCode(),
                skipVerificationFlag,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);

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
                if (awaitHeadlessInitOrRespond(exchange, jobId)) return;

                var contextTextFragmentIds = new ArrayList<String>();
                if (validJobContextTexts != null && !validJobContextTexts.isEmpty()) {
                    for (var txt : validJobContextTexts) {
                        contextManager.addPastedTextFragment(txt);
                        var fragments = contextManager.liveContext().getAllFragmentsInDisplayOrder();
                        for (int i = fragments.size() - 1; i >= 0; i--) {
                            var f = fragments.get(i);
                            if (f.getType() == ContextFragment.FragmentType.PASTE_TEXT) {
                                if (!contextTextFragmentIds.contains(f.id())) contextTextFragmentIds.add(f.id());
                                break;
                            }
                        }
                    }
                    response.put("contextTextFragmentIds", contextTextFragmentIds);
                }
                executeJobAsync(jobId, jobSpec, contextTextFragmentIds);

                // Auto-rename session if it has a default name (best effort)
                if (sessionIdHeader != null) {
                    maybeAutoRenameSession(sessionIdHeader, request.taskInput());
                }

                SimpleHttpServer.sendJsonResponse(exchange, 201, response);
            } catch (Exception e) {
                jobReservation.releaseIfOwner(jobId);
                logger.error("Failed to start job {}", jobId, e);
                SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to start job", e));
            }
        } else {
            SimpleHttpServer.sendJsonResponse(exchange, 200, response);
        }
    }

    private void handlePostIssueJob(HttpExchange exchange) throws IOException {
        var idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            RouterUtil.sendValidationError(exchange, "Idempotency-Key header is required");
            return;
        }

        var request = RouterUtil.parseJsonOr400(exchange, IssueJobRequest.class, "/v1/jobs/issue");
        if (request == null) return;

        if (request.owner() == null || request.owner().isBlank()) {
            RouterUtil.sendValidationError(exchange, "owner is required");
            return;
        }
        if (request.repo() == null || request.repo().isBlank()) {
            RouterUtil.sendValidationError(exchange, "repo is required");
            return;
        }
        if (request.issueNumber() <= 0) {
            RouterUtil.sendValidationError(exchange, "valid issueNumber is required");
            return;
        }
        if (request.githubToken() == null || request.githubToken().isBlank()) {
            RouterUtil.sendValidationError(exchange, "githubToken is required");
            return;
        }
        if (request.plannerModel() == null || request.plannerModel().isBlank()) {
            RouterUtil.sendValidationError(exchange, "plannerModel is required");
            return;
        }

        int maxAttempts =
                Objects.requireNonNullElse(request.maxIssueFixAttempts(), JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);
        if (maxAttempts <= 0) {
            RouterUtil.sendValidationError(exchange, "maxIssueFixAttempts must be a positive integer");
            return;
        }

        String buildSettingsJson =
                request.buildSettings() != null ? OBJECT_MAPPER.writeValueAsString(request.buildSettings()) : "";
        var jobSpec = JobSpec.ofIssue(
                request.plannerModel(),
                request.codeModel() != null ? request.codeModel().strip() : null,
                request.githubToken(),
                request.owner(),
                request.repo(),
                request.issueNumber(),
                buildSettingsJson,
                maxAttempts,
                Boolean.TRUE.equals(request.skipVerification()));

        var createResult = jobStore.createOrGetJob(idempotencyKey, jobSpec);
        var jobId = createResult.jobId();
        if (createResult.isNewJob()) {
            if (!jobReservation.tryReserve(jobId)) {
                SimpleHttpServer.sendJsonResponse(
                        exchange, 409, ErrorPayload.of("JOB_IN_PROGRESS", "A job is currently executing"));
                return;
            }
            try {
                if (awaitHeadlessInitOrRespond(exchange, jobId)) return;
                executeJobAsync(jobId, jobSpec, List.of());
                SimpleHttpServer.sendJsonResponse(exchange, 201, Map.of("jobId", jobId, "state", "queued"));
            } catch (Exception e) {
                jobReservation.releaseIfOwner(jobId);
                logger.error("Failed to start issue job {}", jobId, e);
                SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to start job", e));
            }
        } else {
            SimpleHttpServer.sendJsonResponse(exchange, 200, Map.of("jobId", jobId, "state", "queued"));
        }
    }

    private void handlePostPrReviewJob(HttpExchange exchange) throws IOException {
        var idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            RouterUtil.sendValidationError(exchange, "Idempotency-Key header is required");
            return;
        }
        var request = RouterUtil.parseJsonOr400(exchange, PrReviewJobRequest.class, "/v1/jobs/pr-review");
        if (request == null) return;

        if (request.owner() == null || request.owner().isBlank()) {
            RouterUtil.sendValidationError(exchange, "owner is required");
            return;
        }
        if (request.repo() == null || request.repo().isBlank()) {
            RouterUtil.sendValidationError(exchange, "repo is required");
            return;
        }
        if (request.prNumber() <= 0) {
            RouterUtil.sendValidationError(exchange, "prNumber must be a positive integer");
            return;
        }
        if (request.githubToken() == null || request.githubToken().isBlank()) {
            RouterUtil.sendValidationError(exchange, "githubToken is required");
            return;
        }
        if (request.plannerModel() == null || request.plannerModel().isBlank()) {
            RouterUtil.sendValidationError(exchange, "plannerModel is required");
            return;
        }

        var jobSpec = JobSpec.ofPrReview(
                request.plannerModel(),
                request.githubToken(),
                request.owner(),
                request.repo(),
                request.prNumber(),
                PrReviewService.Severity.normalize(request.severityThreshold()));
        var createResult = jobStore.createOrGetJob(idempotencyKey, jobSpec);
        var jobId = createResult.jobId();
        if (createResult.isNewJob()) {
            if (!jobReservation.tryReserve(jobId)) {
                SimpleHttpServer.sendJsonResponse(
                        exchange, 409, ErrorPayload.of("JOB_IN_PROGRESS", "A job is currently executing"));
                return;
            }
            try {
                if (awaitHeadlessInitOrRespond(exchange, jobId)) return;
                executeJobAsync(jobId, jobSpec, List.of());
                SimpleHttpServer.sendJsonResponse(exchange, 201, Map.of("jobId", jobId, "state", "queued"));
            } catch (Exception e) {
                jobReservation.releaseIfOwner(jobId);
                logger.error("Failed to start PR review job {}", jobId, e);
                SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to start job", e));
            }
        } else {
            SimpleHttpServer.sendJsonResponse(exchange, 200, Map.of("jobId", jobId, "state", "queued"));
        }
    }

    private void handleGetJob(HttpExchange exchange, String jobId) throws IOException {
        var status = jobStore.loadStatus(jobId);
        if (status == null) {
            SimpleHttpServer.sendJsonResponse(exchange, 404, ErrorPayload.jobNotFound(jobId));
            return;
        }
        SimpleHttpServer.sendJsonResponse(exchange, status);
    }

    private void handleGetJobEvents(HttpExchange exchange, String jobId) throws IOException {
        var queryParams = RouterUtil.parseQueryParams(exchange.getRequestURI().getQuery());
        long afterSeq = -1;
        try {
            if (queryParams.containsKey("after")) afterSeq = Long.parseLong(queryParams.get("after"));
        } catch (NumberFormatException e) {
            RouterUtil.sendValidationError(exchange, "Invalid 'after' parameter");
            return;
        }
        int limit = 100;
        try {
            if (queryParams.containsKey("limit")) limit = Math.min(1000, Integer.parseInt(queryParams.get("limit")));
        } catch (NumberFormatException e) {
            RouterUtil.sendValidationError(exchange, "Invalid 'limit' parameter");
            return;
        }

        var events = jobStore.readEvents(jobId, afterSeq, limit);
        SimpleHttpServer.sendJsonResponse(
                exchange,
                Map.of(
                        "events",
                        events,
                        "nextAfter",
                        events.isEmpty() ? afterSeq : events.getLast().seq()));
    }

    private void handleCancelJob(HttpExchange exchange, String jobId) throws IOException {
        try {
            jobRunner.cancel(jobId);
            exchange.sendResponseHeaders(202, 0);
            exchange.close();
        } catch (Exception e) {
            logger.error("Error handling POST /v1/jobs/{}/cancel", jobId, e);
            var error = ErrorPayload.internalError("Failed to cancel job", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    private void handleGetJobDiff(HttpExchange exchange, String jobId) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }
        if (jobStore.loadStatus(jobId) == null) {
            SimpleHttpServer.sendJsonResponse(exchange, 404, ErrorPayload.jobNotFound(jobId));
            return;
        }
        try {
            var diff = contextManager.getProject().getRepo().diff();
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            var bytes = diff.getBytes(UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            exchange.close();
        } catch (Exception e) {
            // Broadened catch to handle GitAPIException or UnsupportedOperationException
            logger.warn("Failed to get diff for job {}: {}", jobId, e.toString());
            SimpleHttpServer.sendJsonResponse(
                    exchange, 409, ErrorPayload.of("NO_GIT", "Git is not available in this workspace"));
        }
    }

    private boolean awaitHeadlessInitOrRespond(HttpExchange exchange, String jobId) throws IOException {
        try {
            headlessInit.get(30, TimeUnit.SECONDS);
            return false;
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            jobReservation.releaseIfOwner(jobId);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            int code = (e instanceof ExecutionException) ? 500 : 503;
            var payload = (e instanceof ExecutionException)
                    ? ErrorPayload.internalError(
                            "Executor initialization failed", Objects.requireNonNullElse(e.getCause(), e))
                    : ErrorPayload.of("NOT_READY", "Executor is still initializing");
            SimpleHttpServer.sendJsonResponse(exchange, code, payload);
            return true;
        }
    }

    private void maybeAutoRenameSession(UUID sessionId, String taskInput) {
        try {
            var sm = contextManager.getProject().getSessionManager();
            sm.autoRenameIfDefault(sessionId, taskInput);
        } catch (Exception e) {
            logger.warn("Failed to initiate auto-rename for session {}: {}", sessionId, e.getMessage());
        }
    }

    private void executeJobAsync(String jobId, JobSpec jobSpec, List<String> seededTextFragmentIds) {
        jobRunner.runAsync(jobId, jobSpec).whenComplete((unused, throwable) -> {
            try {
                if (!seededTextFragmentIds.isEmpty()) {
                    var idSet = new HashSet<>(seededTextFragmentIds);
                    var toDrop = contextManager
                            .liveContext()
                            .allFragments()
                            .filter(f -> idSet.contains(f.id()))
                            .toList();
                    if (!toDrop.isEmpty()) contextManager.drop(toDrop);
                }
            } catch (Exception e) {
                logger.warn("Cleanup failed for job {}: {}", jobId, e.toString());
            } finally {
                jobReservation.releaseIfOwner(jobId);
            }
        });
    }

    private JobSpec.@Nullable ModelOverrides validateModelOverrides(HttpExchange exchange, JobSpecRequest request)
            throws IOException {
        String rl = null;
        if (request.reasoningLevel() != null && !request.reasoningLevel().isBlank()) {
            rl = request.reasoningLevel().strip().toUpperCase(Locale.ROOT);
            if (!ALLOWED_REASONING_LEVELS.contains(rl)) {
                RouterUtil.sendValidationError(
                        exchange, "reasoningLevel must be one of: " + ALLOWED_REASONING_LEVELS_LIST);
                return null;
            }
        }
        String rlc = null;
        if (request.reasoningLevelCode() != null
                && !request.reasoningLevelCode().isBlank()) {
            rlc = request.reasoningLevelCode().strip().toUpperCase(Locale.ROOT);
            if (!ALLOWED_REASONING_LEVELS.contains(rlc)) {
                RouterUtil.sendValidationError(
                        exchange, "reasoningLevelCode must be one of: " + ALLOWED_REASONING_LEVELS_LIST);
                return null;
            }
        }
        Double temp = null;
        if (request.temperature() != null) {
            if (request.temperature().isNaN() || request.temperature() < 0.0 || request.temperature() > 2.0) {
                RouterUtil.sendValidationError(exchange, "temperature must be between 0.0 and 2.0");
                return null;
            }
            temp = request.temperature();
        }
        Double tempCode = null;
        if (request.temperatureCode() != null) {
            if (request.temperatureCode().isNaN()
                    || request.temperatureCode() < 0.0
                    || request.temperatureCode() > 2.0) {
                RouterUtil.sendValidationError(exchange, "temperatureCode must be between 0.0 and 2.0");
                return null;
            }
            tempCode = request.temperatureCode();
        }
        return new JobSpec.ModelOverrides(rl, rlc, temp, tempCode);
    }

    private @Nullable List<String> validateContextTexts(HttpExchange exchange, JobSpecRequest request)
            throws IOException {
        var raw = new ArrayList<String>();
        if (request.contextText() != null) raw.addAll(request.contextText());
        if (request.context() != null && request.context().text() != null)
            raw.addAll(request.context().text());
        if (raw.isEmpty()) return List.of();

        var valid = new ArrayList<String>();
        for (int i = 0; i < raw.size(); i++) {
            var t = raw.get(i);
            if (t == null || t.isBlank() || t.getBytes(UTF_8).length > 1024 * 1024) continue;
            valid.add(t);
        }
        if (valid.isEmpty()) {
            RouterUtil.sendValidationError(exchange, "No valid context text provided");
            return null;
        }
        return valid;
    }

    private record PrReviewJobRequest(
            @Nullable String owner,
            @Nullable String repo,
            int prNumber,
            @Nullable String githubToken,
            @Nullable String plannerModel,
            @Nullable String severityThreshold) {}

    private record JobSpecRequest(
            String sessionId,
            String taskInput,
            boolean autoCommit,
            boolean autoCompress,
            @Nullable String plannerModel,
            @Nullable String scanModel,
            @Nullable String codeModel,
            @Nullable Boolean preScan,
            @Nullable Map<String, String> tags,
            @Nullable List<String> contextText,
            @Nullable ContextPayload context,
            @Nullable String reasoningLevel,
            @Nullable String reasoningLevelCode,
            @Nullable Double temperature,
            @Nullable Double temperatureCode,
            @Nullable Boolean skipVerification) {}

    private record ContextPayload(@Nullable List<String> text) {}

    private record IssueJobRequest(
            @Nullable String owner,
            @Nullable String repo,
            int issueNumber,
            @Nullable String githubToken,
            @Nullable String plannerModel,
            @Nullable String codeModel,
            @Nullable Map<String, Object> buildSettings,
            @Nullable Integer maxIssueFixAttempts,
            @Nullable Boolean skipVerification) {}
}
