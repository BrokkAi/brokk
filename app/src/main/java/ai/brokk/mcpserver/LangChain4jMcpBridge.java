package ai.brokk.mcpserver;

import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class LangChain4jMcpBridge {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcp-progress-scheduler");
        t.setDaemon(true);
        return t;
    });

    public static List<McpServerFeatures.SyncToolSpecification> toolSpecificationsFrom(
            ToolRegistry registry, Collection<String> toolNames) {
        return toolSpecificationsFrom(registry, toolNames, null);
    }

    public static List<McpServerFeatures.SyncToolSpecification> toolSpecificationsFrom(
            ToolRegistry registry,
            Collection<String> toolNames,
            @org.jetbrains.annotations.Nullable McpToolCallHistoryWriter historyWriter) {
        return registry.getTools(toolNames).stream()
                .map(spec -> {
                    McpSchema.JsonSchema inputSchema = spec.parameters() == null
                            ? new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null)
                            : toMcpSchema(spec.parameters());

                    McpSchema.Tool mcpTool = McpSchema.Tool.builder()
                            .name(spec.name())
                            .description(spec.description())
                            .inputSchema(inputSchema)
                            .build();

                    return McpServerFeatures.SyncToolSpecification.builder()
                            .tool(mcpTool)
                            .callHandler((exchange, request) -> {
                                var args = request.arguments() != null ? request.arguments() : Map.of();
                                String jsonArgs;
                                try {
                                    jsonArgs = OBJECT_MAPPER.writeValueAsString(args);
                                } catch (JsonProcessingException e) {
                                    throw new RuntimeException(e);
                                }

                                // Write full raw MCP request to the log
                                var logFile = historyWriter != null
                                        ? historyWriter.writeRequest(spec.name(), serializeRequest(request))
                                        : null;

                                Object progressToken = request.progressToken();
                                if (progressToken != null) {
                                    exchange.progressNotification(new McpSchema.ProgressNotification(
                                            progressToken, 0.0, 1.0, "Starting " + spec.name()));
                                    if (historyWriter != null && logFile != null) {
                                        historyWriter.appendProgress(logFile, 0.0, "Starting " + spec.name());
                                    }
                                }

                                CompletableFuture<McpSchema.CallToolResult> future =
                                        CompletableFuture.supplyAsync(() -> {
                                            try {
                                                ToolExecutionRequest lc4jRequest = ToolExecutionRequest.builder()
                                                        .id("1")
                                                        .name(spec.name())
                                                        .arguments(jsonArgs)
                                                        .build();

                                                var result = registry.executeTool(lc4jRequest);
                                                return McpSchema.CallToolResult.builder()
                                                        .addTextContent(result.resultText())
                                                        .isError(result.status() != ToolExecutionResult.Status.SUCCESS)
                                                        .build();
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                throw new RuntimeException(e);
                                            }
                                        });

                                if (progressToken != null) {
                                    AtomicReference<Double> currentProgress = new AtomicReference<>(0.0);
                                    var progressTask = SCHEDULER.scheduleAtFixedRate(
                                            () -> {
                                                if (future.isDone()) return;
                                                double next =
                                                        currentProgress.get() + (1.0 - currentProgress.get()) * 0.5;
                                                currentProgress.set(next);
                                                String progressMsg = "Executing " + spec.name() + "...";
                                                exchange.progressNotification(new McpSchema.ProgressNotification(
                                                        progressToken, next, 1.0, progressMsg));
                                                if (historyWriter != null && logFile != null) {
                                                    historyWriter.appendProgress(logFile, next, progressMsg);
                                                }
                                            },
                                            1,
                                            1,
                                            TimeUnit.SECONDS);
                                    future.whenComplete((r, t) -> progressTask.cancel(false));
                                }

                                McpSchema.CallToolResult callResult;
                                try {
                                    callResult = future.get();
                                } catch (java.util.concurrent.ExecutionException e) {
                                    Throwable cause = e.getCause();
                                    if (cause instanceof RuntimeException re) throw re;
                                    throw new RuntimeException(cause);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new RuntimeException(e);
                                }

                                // Append result to the log
                                if (historyWriter != null && logFile != null) {
                                    String statusStr =
                                            callResult.isError() != null && callResult.isError() ? "ERROR" : "SUCCESS";
                                    String body = callResult.content().stream()
                                            .filter(c -> c instanceof McpSchema.TextContent)
                                            .map(c -> ((McpSchema.TextContent) c).text())
                                            .collect(Collectors.joining("\n"));
                                    historyWriter.appendResult(logFile, statusStr, body);
                                }

                                return callResult;
                            })
                            .build();
                })
                .collect(Collectors.toList());
    }

    private static String serializeRequest(McpSchema.CallToolRequest request) {
        try {
            return OBJECT_MAPPER.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public static McpSchema.JsonSchema toMcpSchema(JsonObjectSchema lc4j) {
        Map<String, Object> properties = new HashMap<>();
        lc4j.properties().forEach((name, element) -> properties.put(name, convertElement(element)));

        return new McpSchema.JsonSchema(
                "object", properties, lc4j.required() != null ? lc4j.required() : List.of(), false, null, null);
    }

    private static Map<String, Object> convertElement(JsonSchemaElement element) {
        Map<String, Object> map = new HashMap<>();
        if (element instanceof JsonStringSchema) {
            map.put("type", "string");
        } else if (element instanceof JsonIntegerSchema) {
            map.put("type", "integer");
        } else if (element instanceof JsonNumberSchema) {
            map.put("type", "number");
        } else if (element instanceof JsonBooleanSchema) {
            map.put("type", "boolean");
        } else if (element instanceof JsonArraySchema arraySchema) {
            map.put("type", "array");
            map.put("items", convertElement(arraySchema.items()));
        } else if (element instanceof JsonObjectSchema objectSchema) {
            map.put("type", "object");
            Map<String, Object> nestedProps = new HashMap<>();
            objectSchema.properties().forEach((k, v) -> nestedProps.put(k, convertElement(v)));
            map.put("properties", nestedProps);
            if (objectSchema.required() != null) {
                map.put("required", objectSchema.required());
            }
        } else {
            map.put("type", "string");
        }

        String desc = element.description();
        if (desc != null && !desc.isBlank()) {
            map.put("description", desc);
        }
        return map;
    }
}
