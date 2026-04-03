package ai.brokk.executor.routers;

import ai.brokk.executor.agents.AgentDefinition;
import ai.brokk.executor.agents.AgentStore;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Router for /v1/agents CRUD endpoints.
 * Manages custom agent definitions stored as markdown+YAML files.
 */
@NullMarked
public final class AgentsRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(AgentsRouter.class);

    private final AgentStore agentStore;

    public AgentsRouter(AgentStore agentStore) {
        this.agentStore = agentStore;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        var method = exchange.getRequestMethod();
        var path = exchange.getRequestURI().getPath();
        var normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;

        if (normalizedPath.equals("/v1/agents")) {
            switch (method) {
                case "GET" -> handleListAgents(exchange);
                case "POST" -> handleCreateAgent(exchange);
                default -> RouterUtil.sendMethodNotAllowed(exchange);
            }
            return;
        }

        // Extract agent name from /v1/agents/{name}
        var agentName = extractAgentName(normalizedPath);
        if (agentName == null) {
            SimpleHttpServer.sendJsonResponse(exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
            return;
        }

        switch (method) {
            case "GET" -> handleGetAgent(exchange, agentName);
            case "PUT" -> handleUpdateAgent(exchange, agentName);
            case "DELETE" -> handleDeleteAgent(exchange, agentName);
            default -> RouterUtil.sendMethodNotAllowed(exchange);
        }
    }

    private void handleListAgents(HttpExchange exchange) throws IOException {
        var agents = agentStore.list();
        var response = agents.stream().map(AgentsRouter::toResponseMap).toList();
        SimpleHttpServer.sendJsonResponse(exchange, response);
    }

    private void handleGetAgent(HttpExchange exchange, String name) throws IOException {
        var agent = agentStore.get(name);
        if (agent.isEmpty()) {
            SimpleHttpServer.sendJsonResponse(
                    exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Agent not found: " + name));
            return;
        }
        SimpleHttpServer.sendJsonResponse(exchange, toResponseMap(agent.get()));
    }

    private void handleCreateAgent(HttpExchange exchange) throws IOException {
        var request = RouterUtil.parseJsonOr400(exchange, AgentRequest.class, "/v1/agents");
        if (request == null) return;

        if (request.name() == null || request.name().isBlank()) {
            RouterUtil.sendValidationError(exchange, "name is required");
            return;
        }
        if (request.description() == null || request.description().isBlank()) {
            RouterUtil.sendValidationError(exchange, "description is required");
            return;
        }
        if (request.systemPrompt() == null || request.systemPrompt().isBlank()) {
            RouterUtil.sendValidationError(exchange, "systemPrompt is required");
            return;
        }

        var def = new AgentDefinition(
                request.name(),
                request.description(),
                request.tools(),
                request.model(),
                request.maxTurns(),
                request.systemPrompt(),
                "project");
        var errors = def.validate();
        if (!errors.isEmpty()) {
            RouterUtil.sendValidationError(exchange, String.join("; ", errors));
            return;
        }

        // Check if agent already exists
        if (agentStore.get(def.name()).isPresent()) {
            RouterUtil.sendValidationError(exchange, "Agent already exists: " + def.name() + ". Use PUT to update.");
            return;
        }

        agentStore.save(def);
        logger.info("Created agent '{}'", def.name());
        SimpleHttpServer.sendJsonResponse(exchange, 201, toResponseMap(def));
    }

    private void handleUpdateAgent(HttpExchange exchange, String name) throws IOException {
        var existing = agentStore.get(name);
        if (existing.isEmpty()) {
            SimpleHttpServer.sendJsonResponse(
                    exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Agent not found: " + name));
            return;
        }

        var request = RouterUtil.parseJsonOr400(exchange, AgentRequest.class, "/v1/agents/" + name);
        if (request == null) return;

        // Use the path name, not the body name (path takes precedence).
        // Fall back to existing values for any field the request omits.
        var prev = existing.get();
        var def = new AgentDefinition(
                name,
                request.description() != null ? request.description() : prev.description(),
                request.tools() != null ? request.tools() : prev.tools(),
                request.model() != null ? request.model() : prev.model(),
                request.maxTurns() != null ? request.maxTurns() : prev.maxTurns(),
                request.systemPrompt() != null ? request.systemPrompt() : prev.systemPrompt(),
                prev.scope());

        var errors = def.validate();
        if (!errors.isEmpty()) {
            RouterUtil.sendValidationError(exchange, String.join("; ", errors));
            return;
        }

        agentStore.save(def, existing.get().scope());
        logger.info("Updated agent '{}'", name);
        SimpleHttpServer.sendJsonResponse(exchange, toResponseMap(def));
    }

    private void handleDeleteAgent(HttpExchange exchange, String name) throws IOException {
        // Try project-level first, then user-level
        boolean deleted = agentStore.delete(name, "project") || agentStore.delete(name, "user");
        if (!deleted) {
            SimpleHttpServer.sendJsonResponse(
                    exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Agent not found: " + name));
            return;
        }
        logger.info("Deleted agent '{}'", name);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    @Nullable
    private static String extractAgentName(String path) {
        var prefix = "/v1/agents/";
        if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
            return null;
        }
        var name = path.substring(prefix.length());
        // Reject paths with additional segments
        if (name.contains("/")) {
            return null;
        }
        return name;
    }

    private static Map<String, Object> toResponseMap(AgentDefinition def) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", def.name());
        map.put("description", def.description());
        if (def.tools() != null) {
            map.put("tools", def.tools());
        }
        if (def.model() != null) {
            map.put("model", def.model());
        }
        if (def.maxTurns() != null) {
            map.put("maxTurns", def.maxTurns());
        }
        map.put("systemPrompt", def.systemPrompt());
        map.put("scope", def.scope());
        return map;
    }

    private record AgentRequest(
            @Nullable String name,
            @Nullable String description,
            @Nullable List<String> tools,
            @Nullable String model,
            @Nullable Integer maxTurns,
            @Nullable String systemPrompt) {}
}
