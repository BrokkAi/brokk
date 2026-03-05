package ai.brokk.executor.routers;

import ai.brokk.ContextManager;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.git.GitWorkflow;
import ai.brokk.git.IGitRepo;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
            default ->
                SimpleHttpServer.sendJsonResponse(
                        exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
        }
    }

    private record CommitRequest(@Nullable String message) {}

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
}
