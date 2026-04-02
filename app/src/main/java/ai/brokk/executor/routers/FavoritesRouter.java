package ai.brokk.executor.routers;

import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.project.MainProject;
import com.sun.net.httpserver.HttpExchange;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

/**
 * Router for /v1/favorites endpoint.
 * Returns the user's favorite models with their full configuration.
 */
@NullMarked
public final class FavoritesRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(FavoritesRouter.class);

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }
        try {
            var favorites = MainProject.loadFavoriteModels();
            var result = favorites.stream()
                    .map(fm -> {
                        var map = new HashMap<String, Object>();
                        map.put("alias", fm.alias());
                        map.put("modelName", fm.config().name());
                        map.put("reasoning", fm.config().reasoning().name());
                        map.put("tier", fm.config().tier().name());
                        return map;
                    })
                    .toList();

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("favorites", result));
        } catch (Exception e) {
            logger.error("Error handling GET /v1/favorites", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to retrieve favorites", e));
        }
    }
}
