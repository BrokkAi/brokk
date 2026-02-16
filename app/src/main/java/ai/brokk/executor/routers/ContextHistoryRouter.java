package ai.brokk.executor.routers;

import ai.brokk.ContextManager;
import ai.brokk.context.ContextHistory;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

/**
 * Provides HTTP endpoints for context history operations (undo / redo).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /v1/context/undo - attempts a single undo. Response: { "wasUndone": bool, "hasMoreUndo": bool }</li>
 *   <li>POST /v1/context/redo - attempts a single redo. Response: { "wasRedone": bool, "hasMoreRedo": bool }</li>
 * </ul>
 *
 * <p>Notes:
 * <ul>
 *   <li>Methods validate HTTP method using RouterUtil.ensureMethod and return structured ErrorPayload on validation errors.</li>
 *   <li>The router expects a ContextManager instance (the executor wiring layer should register this handler).</li>
 *   <li>Operations are executed via ContextManager's async methods to respect threading conventions.</li>
 * </ul>
 */
@NullMarked
public final class ContextHistoryRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(ContextHistoryRouter.class);

    private final ContextManager contextManager;

    public ContextHistoryRouter(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        try {
            if (path.equals("/v1/context/undo")) {
                handlePostUndo(exchange);
            } else if (path.equals("/v1/context/redo")) {
                handlePostRedo(exchange);
            } else {
                SimpleHttpServer.sendJsonResponse(
                        exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
            }
        } catch (Exception e) {
            logger.error("Unhandled error in ContextHistoryRouter for path {}: {}", path, e.getMessage(), e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Internal server error", e));
        }
    }

    private void handlePostUndo(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            // Use undoContextAsync which respects threading conventions
            Future<?> fut = contextManager.undoContextAsync();
            // Wait briefly for completion; undoContextAsync performs notifications itself
            try {
                fut.get(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                logger.debug("Undo future did not complete within timeout: {}", ex.toString());
            }

            ContextHistory ch = contextManager.getContextHistory();
            boolean hasMoreUndo = ch.hasUndoStates();
            // Report wasUndone=true when the call succeeded without exception
            var resp = new UndoResponse(true, hasMoreUndo);
            SimpleHttpServer.sendJsonResponse(exchange, 200, resp);
        } catch (Exception e) {
            logger.warn("Failed to perform undo: {}", e.toString());
            RouterUtil.sendValidationError(exchange, "Unable to undo context");
        }
    }

    private void handlePostRedo(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        // Check if redo states exist before attempting redo
        ContextHistory ch = contextManager.getContextHistory();
        boolean hasRedo = ch.hasRedoStates();
        if (!hasRedo) {
            var resp = new RedoResponse(false, false);
            SimpleHttpServer.sendJsonResponse(exchange, 200, resp);
            return;
        }

        try {
            // Trigger redo asynchronously via ContextManager
            Future<?> fut = contextManager.redoContextAsync();
            // Wait briefly so quick redos can be observed synchronously
            try {
                fut.get(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                logger.debug("Redo future did not complete within timeout: {}", ex.toString());
            }

            // Re-check state after redo attempt
            boolean hasMoreRedo = ch.hasRedoStates();
            var resp = new RedoResponse(true, hasMoreRedo);
            SimpleHttpServer.sendJsonResponse(exchange, 200, resp);
        } catch (Exception e) {
            logger.warn("Failed to perform redo: {}", e.toString());
            RouterUtil.sendValidationError(exchange, "Unable to redo context");
        }
    }

    // Response records for JSON serialization
    private record UndoResponse(boolean wasUndone, boolean hasMoreUndo) {}

    private record RedoResponse(boolean wasRedone, boolean hasMoreRedo) {}
}
