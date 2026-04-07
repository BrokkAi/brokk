package ai.brokk.executor.routers;

import ai.brokk.ContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.gui.history.HistoryGrouping;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Router for /v1/activity endpoint — exposes grouped context history as an activity feed,
 * plus context actions (undo, copy, diff, new session).
 */
@NullMarked
public final class ActivityRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(ActivityRouter.class);
    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(3);
    private static final long ACTION_TIMEOUT_SECONDS = 10;

    private final ContextManager contextManager;

    public ActivityRouter(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        var method = exchange.getRequestMethod();
        var path = exchange.getRequestURI().getPath();
        var normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;

        if (method.equals("GET") && normalizedPath.equals("/v1/activity")) {
            handleGetActivity(exchange);
            return;
        }

        if (method.equals("GET") && normalizedPath.equals("/v1/activity/diff")) {
            handleGetDiff(exchange);
            return;
        }

        if (method.equals("POST")) {
            switch (normalizedPath) {
                case "/v1/activity/undo" -> handlePostUndo(exchange);
                case "/v1/activity/undo-step" -> handlePostUndoStep(exchange);
                case "/v1/activity/redo" -> handlePostRedo(exchange);
                case "/v1/activity/copy-context" -> handlePostCopyContext(exchange);
                case "/v1/activity/copy-context-history" -> handlePostCopyContextHistory(exchange);
                case "/v1/activity/new-session" -> handlePostNewSession(exchange);
                default -> RouterUtil.sendMethodNotAllowed(exchange);
            }
            return;
        }

        RouterUtil.sendMethodNotAllowed(exchange);
    }

    // ── Context lookup ───────────────────────────────────

    private @Nullable Context findContextById(UUID contextId) {
        return contextManager.getContextHistory().getHistory().stream()
                .filter(c -> c.id().equals(contextId))
                .findFirst()
                .orElse(null);
    }

    // ── GET /v1/activity ─────────────────────────────────

    private void handleGetActivity(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) return;

        try {
            var history = contextManager.getContextHistory();
            var contexts = history.getHistory();
            var groups = HistoryGrouping.GroupingBuilder.discoverGroups(contexts, history);

            var groupList = new ArrayList<Map<String, Object>>();

            for (var group : groups) {
                var groupMap = new HashMap<String, Object>();
                groupMap.put("key", group.key());
                groupMap.put("showHeader", group.shouldShowHeader());
                groupMap.put("isLastGroup", group.isLastGroup());

                // Resolve group label
                var labelCv = group.label();
                var label = labelCv.renderNowOrNull();
                if (label == null) {
                    label = labelCv.await(RESOLVE_TIMEOUT).orElse("...");
                }
                groupMap.put("label", label);

                // Build entries — use history.previousOf() to match Java GUI exactly
                var entries = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < group.children().size(); i++) {
                    var ctx = group.children().get(i);
                    var entryMap = new HashMap<String, Object>();
                    entryMap.put("contextId", ctx.id().toString());

                    // Resolve action description (use history.previousOf like HistoryTable does)
                    @Nullable Context actionPrev = history.previousOf(ctx);
                    var actionCv = ctx.getAction(actionPrev);
                    var action = actionCv.renderNowOrNull();
                    if (action == null) {
                        action = actionCv.await(RESOLVE_TIMEOUT).orElse("...");
                    }
                    entryMap.put("action", action);

                    // Determine task type from the latest new TaskEntry
                    var taskType = resolveTaskType(ctx, actionPrev);
                    if (taskType != null) {
                        entryMap.put("taskType", taskType);
                    }

                    // Check if this is an AI result
                    entryMap.put("isAiResult", history.isAiResult(ctx));

                    entries.add(entryMap);
                }
                groupMap.put("entries", entries);
                groupList.add(groupMap);
            }

            var response = new HashMap<String, Object>();
            response.put("groups", groupList);
            response.put("hasUndo", history.hasUndoStates());
            response.put("hasRedo", history.hasRedoStates());
            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("Error handling GET /v1/activity", e);
            var error = ErrorPayload.internalError("Failed to retrieve activity", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    // ── POST /v1/activity/undo ───────────────────────────

    private void handlePostUndo(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;

        var request = RouterUtil.parseJsonOr400(exchange, ContextIdRequest.class, "/v1/activity/undo");
        if (request == null) return;

        var context = findContextById(request.contextId());
        if (context == null) {
            RouterUtil.sendValidationError(exchange, "Context not found: " + request.contextId());
            return;
        }

        try {
            contextManager.undoContextUntilAsync(context).get(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "ok"));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/activity/undo", e);
            var error = ErrorPayload.internalError("Failed to undo to context", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    // ── POST /v1/activity/undo-step ─────────────────────

    private void handlePostUndoStep(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;

        try {
            contextManager.undoContextAsync().get(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "ok"));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/activity/undo-step", e);
            var error = ErrorPayload.internalError("Failed to undo step", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    // ── POST /v1/activity/redo ────────────────────────────

    private void handlePostRedo(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;

        try {
            contextManager.redoContextAsync().get(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "ok"));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/activity/redo", e);
            var error = ErrorPayload.internalError("Failed to redo", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    // ── POST /v1/activity/copy-context ───────────────────

    private void handlePostCopyContext(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;

        var request = RouterUtil.parseJsonOr400(exchange, ContextIdRequest.class, "/v1/activity/copy-context");
        if (request == null) return;

        var context = findContextById(request.contextId());
        if (context == null) {
            RouterUtil.sendValidationError(exchange, "Context not found: " + request.contextId());
            return;
        }

        try {
            contextManager.resetContextToAsync(context).get(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "ok"));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/activity/copy-context", e);
            var error = ErrorPayload.internalError("Failed to copy context", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    // ── POST /v1/activity/copy-context-history ───────────

    private void handlePostCopyContextHistory(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;

        var request = RouterUtil.parseJsonOr400(exchange, ContextIdRequest.class, "/v1/activity/copy-context-history");
        if (request == null) return;

        var context = findContextById(request.contextId());
        if (context == null) {
            RouterUtil.sendValidationError(exchange, "Context not found: " + request.contextId());
            return;
        }

        try {
            contextManager.resetContextToIncludingHistoryAsync(context).get(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "ok"));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/activity/copy-context-history", e);
            var error = ErrorPayload.internalError("Failed to copy context with history", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    // ── POST /v1/activity/new-session ────────────────────

    private void handlePostNewSession(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;

        var request = RouterUtil.parseJsonOr400(exchange, NewSessionRequest.class, "/v1/activity/new-session");
        if (request == null) return;

        var context = findContextById(request.contextId());
        if (context == null) {
            RouterUtil.sendValidationError(exchange, "Context not found: " + request.contextId());
            return;
        }

        var name = request.name() != null && !request.name().isBlank()
                ? request.name()
                : ContextManager.DEFAULT_SESSION_NAME;

        try {
            contextManager.createSessionFromContextAsync(context, name).get(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "ok"));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/activity/new-session", e);
            var error = ErrorPayload.internalError("Failed to create new session", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    // ── GET /v1/activity/diff ────────────────────────────

    private void handleGetDiff(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) return;

        var params = RouterUtil.parseQueryParams(exchange.getRequestURI().getQuery());
        var contextIdStr = params.get("contextId");
        if (contextIdStr == null || contextIdStr.isBlank()) {
            RouterUtil.sendValidationError(exchange, "contextId query parameter is required");
            return;
        }

        UUID contextId;
        try {
            contextId = UUID.fromString(contextIdStr);
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(exchange, "Invalid contextId format");
            return;
        }

        var context = findContextById(contextId);
        if (context == null) {
            RouterUtil.sendValidationError(exchange, "Context not found: " + contextId);
            return;
        }

        try {
            var diffService = contextManager.getContextHistory().getDiffService();
            var fragmentDiffs = diffService.diff(context).get(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            var diffs = new ArrayList<Map<String, Object>>();
            for (var fd : fragmentDiffs) {
                var diffMap = new HashMap<String, Object>();
                diffMap.put("title", fd.title());
                diffMap.put("diff", fd.diff());
                diffMap.put("linesAdded", fd.linesAdded());
                diffMap.put("linesDeleted", fd.linesDeleted());
                diffs.add(diffMap);
            }

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("diffs", diffs));
        } catch (Exception e) {
            logger.error("Error handling GET /v1/activity/diff", e);
            var error = ErrorPayload.internalError("Failed to compute diff", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    // ── Helpers ──────────────────────────────────────────

    /**
     * Determine the task type by checking if the current context added a new task entry
     * compared to the previous context.
     */
    private static @Nullable String resolveTaskType(Context ctx, @Nullable Context prev) {
        var currentTasks = ctx.getTaskHistory();
        var prevTasks = prev != null ? prev.getTaskHistory() : List.<TaskEntry>of();

        if (currentTasks.size() > prevTasks.size()) {
            // A new task was added — get the latest one
            var latestTask = currentTasks.getLast();
            if (latestTask.meta() != null && latestTask.meta().type() != TaskResult.Type.NONE) {
                return latestTask.meta().type().displayName();
            }
        }
        return null;
    }

    // ── Request records ──────────────────────────────────

    private record ContextIdRequest(UUID contextId) {}

    private record NewSessionRequest(UUID contextId, @Nullable String name) {}
}
