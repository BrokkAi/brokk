package ai.brokk.executor.routers;

import ai.brokk.ContextManager;
import ai.brokk.SessionManager;
import ai.brokk.agents.ReviewScope;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.git.CommitInfo;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.git.IGitRepo;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Router for /v1/repo endpoints.
 */
@NullMarked
public final class RepoRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(RepoRouter.class);

    private final ContextManager contextManager;

    public RepoRouter(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        var method = exchange.getRequestMethod();
        var path = exchange.getRequestURI().getPath();
        var normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;

        if (!method.equals("POST")) {
            RouterUtil.sendMethodNotAllowed(exchange);
            return;
        }

        switch (normalizedPath) {
            case "/v1/repo/commit" -> handlePostCommit(exchange);
            case "/v1/repo/pr/suggest" -> handlePostPrSuggest(exchange);
            case "/v1/repo/pr/create" -> handlePostPrCreate(exchange);
            case "/v1/repo/pr/sessions" -> handlePostPrSessions(exchange);
            default ->
                SimpleHttpServer.sendJsonResponse(
                        exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
        }
    }

    private record CommitRequest(@Nullable String message) {}

    private record PrSuggestRequest(
            @Nullable String sourceBranch, @Nullable String targetBranch, @Nullable List<String> sessionIds) {}

    private record PrSessionsRequest(@Nullable String sourceBranch, @Nullable String targetBranch) {}

    private record PrCreateRequest(
            @Nullable String sourceBranch,
            @Nullable String targetBranch,
            @Nullable String title,
            @Nullable String body,
            @Nullable List<String> sessionIds) {}

    private void handlePostCommit(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;

        var request = RouterUtil.parseJsonOr400(exchange, CommitRequest.class, "/v1/repo/commit");
        if (request == null) return;

        var project = contextManager.getProject();
        if (!project.hasGit()) {
            RouterUtil.sendValidationError(exchange, "Project does not have a git repository");
            return;
        }

        try {
            var repo = project.getRepo();
            var modified = repo.getModifiedFiles();

            if (modified.isEmpty()) {
                SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "no_changes"));
                return;
            }

            var filesToCommit =
                    modified.stream().map(IGitRepo.ModifiedFile::file).toList();

            var gitWorkflow = new GitWorkflow(contextManager);
            @Nullable String message = request.message();

            if (message == null || message.isBlank()) {
                message = gitWorkflow.suggestCommitMessage(filesToCommit, true).orElse("Manual commit");
            }

            var commitResult = gitWorkflow.commit(filesToCommit, message);

            contextManager.getIo().updateGitRepo();

            SimpleHttpServer.sendJsonResponse(
                    exchange,
                    Map.of(
                            "commitId", commitResult.commitId(),
                            "firstLine", commitResult.firstLine()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Commit operation was interrupted", e);
            SimpleHttpServer.sendJsonResponse(exchange, 503, ErrorPayload.internalError("Operation interrupted", e));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/repo/commit", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to commit changes", e));
        }
    }

    private void handlePostPrSuggest(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;

        var project = contextManager.getProject();
        if (!project.hasGit()) {
            RouterUtil.sendValidationError(exchange, "Project does not have a git repository");
            return;
        }

        var request = RouterUtil.parseJsonOr400(exchange, PrSuggestRequest.class, "/v1/repo/pr/suggest");
        if (request == null) return;

        // Parse and validate session IDs if provided
        List<UUID> sessionUuids = parseSessionIds(request.sessionIds());
        if (sessionUuids == null) {
            RouterUtil.sendValidationError(exchange, "Invalid session ID format; expected valid UUIDs");
            return;
        }

        try {
            var repo = project.getRepo();
            String source = resolveSourceBranch(request.sourceBranch(), repo);
            String target = resolveTargetBranch(request.targetBranch(), repo);

            var gitWorkflow = new GitWorkflow(contextManager);
            GitWorkflow.PrSuggestion suggestion;
            if (sessionUuids.isEmpty()) {
                suggestion = gitWorkflow.suggestPullRequestDetails(source, target, contextManager.getIo());
            } else {
                suggestion =
                        gitWorkflow.suggestPullRequestDetails(source, target, contextManager.getIo(), sessionUuids);
            }

            SimpleHttpServer.sendJsonResponse(
                    exchange,
                    Map.of(
                            "title", suggestion.title(),
                            "description", suggestion.description(),
                            "usedCommitMessages", suggestion.usedCommitMessages(),
                            "sourceBranch", source,
                            "targetBranch", target));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("PR suggest operation was interrupted", e);
            SimpleHttpServer.sendJsonResponse(exchange, 503, ErrorPayload.internalError("Operation interrupted", e));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/repo/pr/suggest", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to suggest PR details", e));
        }
    }

    private void handlePostPrCreate(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;

        var project = contextManager.getProject();
        if (!project.hasGit()) {
            RouterUtil.sendValidationError(exchange, "Project does not have a git repository");
            return;
        }

        var request = RouterUtil.parseJsonOr400(exchange, PrCreateRequest.class, "/v1/repo/pr/create");
        if (request == null) return;

        if (request.title() == null || request.title().isBlank()) {
            RouterUtil.sendValidationError(exchange, "title is required");
            return;
        }
        if (request.body() == null) {
            RouterUtil.sendValidationError(exchange, "body is required");
            return;
        }

        // Parse and validate session IDs if provided
        List<UUID> sessionUuids = parseSessionIds(request.sessionIds());
        if (sessionUuids == null) {
            RouterUtil.sendValidationError(exchange, "Invalid session ID format; expected valid UUIDs");
            return;
        }

        var githubToken = exchange.getRequestHeaders().getFirst("X-Github-Token");

        try {
            var repo = project.getRepo();
            String source = resolveSourceBranch(request.sourceBranch(), repo);
            String target = resolveTargetBranch(request.targetBranch(), repo);

            // Append session IDs metadata line to body if any sessions are selected
            String finalBody = request.body();
            if (!sessionUuids.isEmpty()) {
                String ids =
                        sessionUuids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
                finalBody = finalBody + "\n\nbrokk-session-ids:" + ids;
            }

            var gitWorkflow = new GitWorkflow(contextManager);
            var prUri = gitWorkflow.createPullRequest(source, target, request.title(), finalBody, githubToken);

            SimpleHttpServer.sendJsonResponse(
                    exchange,
                    Map.of(
                            "url", prUri.toString(),
                            "sourceBranch", source,
                            "targetBranch", target));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("PR create operation was interrupted", e);
            SimpleHttpServer.sendJsonResponse(exchange, 503, ErrorPayload.internalError("Operation interrupted", e));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/repo/pr/create", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to create pull request", e));
        }
    }

    private void handlePostPrSessions(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) return;

        var project = contextManager.getProject();
        if (!project.hasGit()) {
            RouterUtil.sendValidationError(exchange, "Project does not have a git repository");
            return;
        }

        var request = RouterUtil.parseJsonOr400(exchange, PrSessionsRequest.class, "/v1/repo/pr/sessions");
        if (request == null) return;

        try {
            var repo = project.getRepo();
            String source = resolveSourceBranch(request.sourceBranch(), repo);
            String target = resolveTargetBranch(request.targetBranch(), repo);

            // Match Swing semantics: listCommitsBetweenBranches(target, source, true)
            // This requires GitRepo, not IGitRepo
            List<CommitInfo> commits;
            if (repo instanceof GitRepo gitRepo) {
                commits = gitRepo.listCommitsBetweenBranches(target, source, true);
            } else {
                commits = List.of();
            }

            // Find overlapping sessions matching Swing's updateSessionsTab behavior
            List<UUID> overlappingIds = ReviewScope.findOverlappingSessions(contextManager, commits);
            Set<UUID> overlappingIdSet = Set.copyOf(overlappingIds);

            // Build session metadata matching Swing: filter listSessions() to overlapping IDs
            SessionManager sm = project.getSessionManager();
            var allSessions = sm.listSessions();

            List<Map<String, Object>> sessionList = new ArrayList<>();
            for (var info : allSessions) {
                if (overlappingIdSet.contains(info.id())) {
                    int taskCount = sm.countAiResponses(info.id());
                    Map<String, Object> sessionData = new HashMap<>();
                    sessionData.put("id", info.id().toString());
                    sessionData.put("name", info.name());
                    sessionData.put("taskCount", taskCount);
                    sessionList.add(sessionData);
                }
            }

            SimpleHttpServer.sendJsonResponse(
                    exchange,
                    Map.of(
                            "sessions", sessionList,
                            "sourceBranch", source,
                            "targetBranch", target));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/repo/pr/sessions", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to get PR sessions", e));
        }
    }

    private String resolveSourceBranch(@Nullable String requested, IGitRepo repo) throws GitAPIException {
        if (requested != null && !requested.isBlank()) {
            return requested.strip();
        }
        return repo.getCurrentBranch();
    }

    private String resolveTargetBranch(@Nullable String requested, IGitRepo repo) throws GitAPIException {
        if (requested != null && !requested.isBlank()) {
            return requested.strip();
        }
        return repo.getDefaultBranch();
    }

    /**
     * Parses a list of session ID strings into UUIDs.
     * Returns an empty list if input is null or empty.
     * Returns null if any ID fails to parse (indicating a validation error).
     */
    @Nullable
    private List<UUID> parseSessionIds(@Nullable List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return new ArrayList<>();
        }
        var result = new ArrayList<UUID>(sessionIds.size());
        for (String idStr : sessionIds) {
            if (idStr == null || idStr.isBlank()) {
                continue;
            }
            try {
                result.add(UUID.fromString(idStr.strip()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid session ID format: {}", idStr);
                return null;
            }
        }
        return result;
    }
}
