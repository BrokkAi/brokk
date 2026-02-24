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
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class LangChain4jMcpBridge {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static List<McpServerFeatures.SyncToolSpecification> toolSpecificationsFrom(
            ToolRegistry registry, Collection<String> toolNames) {
        return registry.getTools(toolNames).stream()
                .map(spec -> {
                    McpSchema.JsonSchema inputSchema = spec.parameters() != null
                            ? toMcpSchema(spec.parameters())
                            : new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);

                    McpSchema.Tool mcpTool = McpSchema.Tool.builder()
                            .name(spec.name())
                            .description(spec.description())
                            .inputSchema(inputSchema)
                            .build();

                    return McpServerFeatures.SyncToolSpecification.builder()
                            .tool(mcpTool)
                            .callHandler((exchange, request) -> {
                                try {
                                    String jsonArgs = OBJECT_MAPPER.writeValueAsString(request.arguments());
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
                                } catch (JsonProcessingException e) {
                                    throw new RuntimeException(e);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new RuntimeException(e);
                                }
                            })
                            .build();
                })
                .collect(Collectors.toList());
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
