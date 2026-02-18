package ai.brokk.executor.routers;

import ai.brokk.Completions;
import ai.brokk.ContextManager;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import com.sun.net.httpserver.HttpExchange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.autocomplete.ShorthandCompletion;
import org.jspecify.annotations.NullMarked;

/**
 * Router for GET /v1/completions endpoint.
 * Provides file and symbol completions for @-mention autocomplete in editors.
 */
@NullMarked
public final class CompletionsRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(CompletionsRouter.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final ContextManager contextManager;

    public CompletionsRouter(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }

        try {
            var params = RouterUtil.parseQueryParams(exchange.getRequestURI().getQuery());
            var query = params.getOrDefault("query", "").trim();
            if (query.isEmpty()) {
                SimpleHttpServer.sendJsonResponse(exchange, Map.of("completions", List.of()));
                return;
            }

            int limit = DEFAULT_LIMIT;
            var limitStr = params.get("limit");
            if (limitStr != null && !limitStr.isEmpty()) {
                try {
                    limit = Math.min(Math.max(1, Integer.parseInt(limitStr)), MAX_LIMIT);
                } catch (NumberFormatException e) {
                    // use default
                }
            }

            // Always search both files and symbols, merge results.
            // Path-like queries (containing / or \) skip symbol search.
            boolean isPathQuery = query.contains("/") || query.contains("\\");
            var completions = new ArrayList<Map<String, String>>();

            // Files first for path queries, symbols first otherwise
            if (isPathQuery) {
                completions.addAll(getFileCompletions(query, limit));
            } else {
                completions.addAll(getSymbolCompletions(query, limit));
                if (completions.size() < limit) {
                    completions.addAll(getFileCompletions(query, limit - completions.size()));
                }
            }

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("completions", completions));
        } catch (Exception e) {
            logger.error("Error handling GET /v1/completions", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to retrieve completions", e));
        }
    }

    private List<Map<String, String>> getFileCompletions(String query, int limit) {
        var project = contextManager.getProject();
        var allFiles = project.getAllFiles();

        var scored = Completions.scoreProjectFiles(
                query,
                project,
                allFiles,
                ProjectFile::getFileName,
                ProjectFile::toString,
                f -> new ShorthandCompletion(null, f.getFileName(), f.toString()),
                1);

        var results = new ArrayList<Map<String, String>>();
        for (var completion : scored) {
            if (results.size() >= limit) break;
            var item = new HashMap<String, String>();
            item.put("type", "file");
            item.put("name", completion.getInputText());
            item.put("detail", completion.getReplacementText());
            results.add(item);
        }
        return results;
    }

    private List<Map<String, String>> getSymbolCompletions(String query, int limit) {
        if (query.length() < 2) {
            return List.of();
        }

        var analyzer = contextManager.getAnalyzerWrapper().getNonBlocking();
        if (analyzer == null || analyzer.isEmpty()) {
            return List.of();
        }

        var symbols = Completions.completeSymbols(query, analyzer);

        var results = new ArrayList<Map<String, String>>();
        for (var symbol : symbols) {
            if (results.size() >= limit) break;
            var item = new HashMap<String, String>();
            item.put("type", mapCodeUnitType(symbol.kind()));
            item.put("name", symbol.shortName());
            item.put("detail", symbol.fqName());
            results.add(item);
        }
        return results;
    }

    private static String mapCodeUnitType(CodeUnitType kind) {
        return switch (kind) {
            case CLASS -> "class";
            case FUNCTION -> "function";
            case FIELD -> "field";
            case MODULE -> "module";
        };
    }
}
