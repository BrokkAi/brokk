package io.github.jbellis.brokk.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.AiMessage;
import io.github.jbellis.brokk.ContextManager;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Discovers, registers, provides specifications for, and executes tools. Tools are methods annotated with @Tool on
 * registered object instances.
 */
public class ToolRegistry {
    private static final Logger logger = LogManager.getLogger(ToolRegistry.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Maps tool name to its invocation target (method + instance)
    private final Map<String, ToolInvocationTarget> toolMap = new ConcurrentHashMap<>();

    /** Gets human-readable parameter information from a tool call */
    public static String formatListParameter(Map<String, Object> arguments, String paramName) {
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) arguments.get(paramName);
        if (items != null && !items.isEmpty()) {
            // turn it back into a JSON list or the LLM will be lazy too
            var mapper = new ObjectMapper();
            try {
                return "%s=%s".formatted(paramName, mapper.writeValueAsString(items));
            } catch (IOException e) {
                logger.error("Error formatting list parameter", e);
            }
        }
        return "";
    }

    /** Generates a user-friendly explanation for a tool request as a Markdown code fence with YAML formatting. */
    public static String getExplanationForToolRequest(ToolExecutionRequest request) {
        try {
            // Get tool display metadata
            var displayMeta = ToolDisplayMeta.fromToolName(request.name());

            // Skip empty explanations for answer/abort
            if (request.name().equals("answerSearch") || request.name().equals("abortSearch")) {
                return "";
            }

            // Parse the arguments
            var mapper = new ObjectMapper();
            Map<String, Object> args = mapper.readValue(request.arguments(), new TypeReference<>() {});

            // Convert to YAML format
            StringBuilder yamlBuilder = new StringBuilder();

            // Process each argument entry
            for (var entry : args.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // Handle different value types
                if (value instanceof List<?> list) {
                    yamlBuilder.append(key).append(":\n");
                    for (Object item : list) {
                        yamlBuilder.append("  - ").append(item).append("\n");
                    }
                } else if (value instanceof String str && str.contains("\n")) {
                    // Use YAML block scalar for multi-line strings
                    yamlBuilder.append(key).append(": |\n");
                    for (String line :
                            com.google.common.base.Splitter.on('\n').splitToList(str)) { // Use Splitter fully qualified
                        yamlBuilder.append("  ").append(line).append("\n");
                    }
                } else {
                    yamlBuilder.append(key).append(": ").append(value).append("\n");
                }
            }

            // Create the Markdown code fence with icon and headline
            return """
                   ```%s %s
                   %s```
                   """
                    .formatted(displayMeta.getIcon(), displayMeta.getHeadline(), yamlBuilder);
        } catch (Exception e) {
            logger.error("Error formatting tool request explanation", e);
            String paramInfo = getToolParameterInfoFromRequest(request);
            var displayMeta = ToolDisplayMeta.fromToolName(request.name());
            return paramInfo.isBlank() ? displayMeta.getHeadline() : displayMeta.getHeadline() + " (" + paramInfo + ")";
        }
    }

    /** Gets parameter info directly from a request for explanation purposes. */
    private static String getToolParameterInfoFromRequest(ToolExecutionRequest request) {
        try {
            var mapper = new ObjectMapper();
            var arguments = mapper.readValue(request.arguments(), new TypeReference<Map<String, Object>>() {});

            return switch (request.name()) {
                case "searchSymbols", "searchSubstrings", "searchFilenames" ->
                    formatListParameter(arguments, "patterns");
                case "getFileContents" -> formatListParameter(arguments, "filenames");
                case "getFileSummaries" -> formatListParameter(arguments, "filePaths");
                case "getUsages" -> formatListParameter(arguments, "symbols");
                case "getRelatedClasses", "getClassSkeletons", "getClassSources" ->
                    formatListParameter(arguments, "classNames");
                case "getMethodSources" -> formatListParameter(arguments, "methodNames");
                case "getCallGraphTo", "getCallGraphFrom" ->
                    arguments.getOrDefault("methodName", "").toString();
                case "answerSearch", "abortSearch" -> "";
                default -> "";
            };
        } catch (Exception e) {
            logger.error("Error getting parameter info for request {}: {}", request.name(), e);
            return "";
        }
    }

    /** Enum that defines display metadata for each tool */
    private enum ToolDisplayMeta {
        SEARCH_SYMBOLS("🔍", "Searching for symbols"),
        SEARCH_SUBSTRINGS("🔍", "Searching for substrings"),
        SEARCH_FILENAMES("🔍", "Searching for filenames"),
        GET_FILE_CONTENTS("🔍", "Getting file contents"),
        GET_FILE_SUMMARIES("🔍", "Getting file summaries"),
        GET_USAGES("🔍", "Finding usages"),
        GET_CLASS_SKELETONS("🔍", "Getting class overview"),
        GET_CLASS_SOURCES("🔍", "Fetching class source"),
        GET_METHOD_SOURCES("🔍", "Fetching method source"),
        GET_RELATED_CLASSES("🔍", "Finding related code"),
        CALL_GRAPH_TO("🔍", "Getting call graph TO"),
        CALL_GRAPH_FROM("🔍", "Getting call graph FROM"),
        ANSWER_SEARCH("", ""),
        ABORT_SEARCH("", ""),
        UNKNOWN("❓", "");

        private final String icon;
        private final String headline;

        ToolDisplayMeta(String icon, String headline) {
            this.icon = icon;
            this.headline = headline;
        }

        public String getIcon() {
            return icon;
        }

        public String getHeadline() {
            return headline;
        }

        public static ToolDisplayMeta fromToolName(String toolName) {
            return switch (toolName) {
                case "searchSymbols" -> SEARCH_SYMBOLS;
                case "searchSubstrings" -> SEARCH_SUBSTRINGS;
                case "searchFilenames" -> SEARCH_FILENAMES;
                case "getFileContents" -> GET_FILE_CONTENTS;
                case "getFileSummaries" -> GET_FILE_SUMMARIES;
                case "getUsages" -> GET_USAGES;
                case "getRelatedClasses" -> GET_RELATED_CLASSES;
                case "getClassSkeletons" -> GET_CLASS_SKELETONS;
                case "getClassSources" -> GET_CLASS_SOURCES;
                case "getMethodSources" -> GET_METHOD_SOURCES;
                case "getCallGraphTo" -> CALL_GRAPH_TO;
                case "getCallGraphFrom" -> CALL_GRAPH_FROM;
                case "answerSearch" -> ANSWER_SEARCH;
                case "abortSearch" -> ABORT_SEARCH;
                default -> {
                    logger.warn("Unknown tool name for display metadata: {}", toolName);
                    yield UNKNOWN;
                }
            };
        }
    }
    // private final ContextManager contextManager; // Unused field removed

    // Internal record to hold method and the instance it belongs to
    private record ToolInvocationTarget(Method method, Object instance) {}

    /** Creates a new ToolRegistry and self-registers internal tools. */
    public ToolRegistry(ContextManager contextManagerIgnored) {
        // this.contextManager = contextManager; // contextManager field removed
        register(this);
    }

    /**
     * A tool for thinking through complex problems step by step. This allows the model to break down its reasoning
     * process explicitly.
     */
    @Tool(
            value =
                    """
    Think carefully step by step about a complex problem. Use this tool to reason through difficult questions
    or break problems into smaller pieces. Call it concurrently with other tools.
    """)
    public String think(@P("The step-by-step reasoning to work through") String reasoning) {
        // Llm special-cases this tool, but we need to return a value so the execution request is happy
        return "Good thinking.";
    }

    /**
     * Registers all methods annotated with @Tool from the given object instance.
     *
     * @param toolProviderInstance An instance of a class containing methods annotated with @Tool.
     */
    public void register(Object toolProviderInstance) {
        Objects.requireNonNull(toolProviderInstance, "toolProviderInstance cannot be null");
        Class<?> clazz = toolProviderInstance.getClass();

        for (Method method : clazz.getMethods()) {
            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                dev.langchain4j.agent.tool.Tool toolAnnotation =
                        method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();

                if (toolMap.containsKey(toolName)) {
                    throw new IllegalArgumentException(
                            "Duplicate tool name registration attempted: '%s'".formatted(toolName));
                } else {
                    logger.debug("Registering tool: '{}' from class {}", toolName, clazz.getName());
                    toolMap.put(toolName, new ToolInvocationTarget(method, toolProviderInstance));
                }
            }
        }
    }

    /**
     * Generates ToolSpecifications for the given list of tool names.
     *
     * @param toolNames A list of tool names to get specifications for.
     * @return A list of ToolSpecification objects. Returns an empty list if a name is not found.
     */
    public List<ToolSpecification> getRegisteredTools(List<String> toolNames) {
        var missingTools =
                toolNames.stream().filter(tool -> !toolMap.containsKey(tool)).toList();
        if (!missingTools.isEmpty()) {
            logger.error("Missing tools: '{}'", missingTools); // let it throw NPE below
        }
        return toolNames.stream()
                .map(toolMap::get)
                .map(target -> ToolSpecifications.toolSpecificationFrom(target.method()))
                .collect(Collectors.toList());
    }

    /**
     * Generates ToolSpecifications for tool methods defined as instance methods within a given object. This is useful
     * for agent-specific tools (like answer/abort) defined within an agent instance.
     *
     * @param instance The object containing the @Tool annotated instance methods.
     * @param toolNames The names of the tools to get specifications for.
     * @return A list of ToolSpecification objects. Returns an empty list if a name is not found or the method doesn't
     *     match.
     */
    public List<ToolSpecification> getTools(Object instance, Collection<String> toolNames) {
        Objects.requireNonNull(instance, "toolInstance cannot be null");
        Class<?> cls = instance.getClass();

        // Gather all instance methods declared in the class that are annotated with @Tool.
        List<Method> annotatedMethods = Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class))
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .toList();

        // For each toolName, directly find the corresponding method and generate its specification.
        return toolNames.stream()
                .map(toolName -> annotatedMethods.stream()
                        .filter(m -> {
                            var toolAnnotation = m.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                            String name = toolAnnotation.name().isEmpty() ? m.getName() : toolAnnotation.name();
                            return name.equals(toolName);
                        })
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No tool method found for %s in %s".formatted(toolName, instance))))
                .map(ToolSpecifications::toolSpecificationFrom)
                .collect(Collectors.toList());
    }

    /**
     * Executes a tool defined as an instance method on the provided object.
     *
     * @param instance The object instance containing the @Tool annotated method.
     * @param request The ToolExecutionRequest from the LLM.
     * @return A ToolExecutionResult indicating success or failure.
     */
    public ToolExecutionResult executeTool(Object instance, ToolExecutionRequest request) throws InterruptedException {
        Class<?> cls = instance.getClass();
        String toolName = request.name();

        // Look for the method matching the tool name within the instance's class
        Method targetMethod = Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class))
                .filter(m -> !Modifier.isStatic(m.getModifiers())) // Ensure it's an instance method
                .filter(m -> {
                    var toolAnnotation = m.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                    String name = toolAnnotation.name().isEmpty() ? m.getName() : toolAnnotation.name();
                    return name.equals(toolName);
                })
                .findFirst()
                .orElse(null);

        ToolInvocationTarget target;
        if (targetMethod == null) {
            // check globally registered tools
            target = toolMap.get(request.name());
        } else {
            target = new ToolInvocationTarget(targetMethod, instance);
        }

        if (target == null) {
            logger.error("Tool not found: {}", request.name());
            return ToolExecutionResult.failure(request, "Tool not found: " + request.name());
        }
        return executeTool(request, target);
    }

    /**
     * Private helper to execute a tool given a request and a resolved target. Handles argument parsing and method
     * invocation.
     *
     * @param request The execution request.
     * @param target The resolved method and instance.
     * @return The execution result.
     */
    private static ToolExecutionResult executeTool(ToolExecutionRequest request, ToolInvocationTarget target)
            throws InterruptedException {
        Method method = target.method();
        Object instance = target.instance();

        try {
            // 1. Parse JSON arguments from the request
            Map<String, Object> argumentsMap =
                    OBJECT_MAPPER.readValue(request.arguments(), new TypeReference<HashMap<String, Object>>() {});

            // 2. Prepare the arguments array for Method.invoke
            Parameter[] parameters = method.getParameters();
            Object[] methodArgs = new Object[parameters.length];

            for (int i = 0; i < parameters.length; i++) {
                Parameter param = parameters[i];
                if (!argumentsMap.containsKey(param.getName())) {
                    return ToolExecutionResult.failure(
                            request,
                            "Missing required parameter: '%s' in arguments: %s"
                                    .formatted(param.getName(), request.arguments()));
                }

                Object argValue = argumentsMap.get(param.getName());

                // Convert the argument to the correct type expected by the method parameter
                // Jackson might have already done some conversion (e.g., numbers), but lists/etc. might need care.
                // Using ObjectMapper for type conversion.
                methodArgs[i] = OBJECT_MAPPER.convertValue(argValue, param.getType());
            }

            // 3. Invoke the method
            logger.debug("Invoking tool '{}' with args: {}", request.name(), List.of(methodArgs));
            Object resultObject = method.invoke(instance, methodArgs);
            String resultString = resultObject != null ? resultObject.toString() : "";

            return ToolExecutionResult.success(request, resultString);
        } catch (InvocationTargetException e) {
            // some code paths will wrap IE in RuntimeException, so check the entire Cause hierarchy
            for (var e2 = (Throwable) e; e2 != null; e2 = e2.getCause()) {
                if (e2 instanceof InterruptedException ie) {
                    throw ie;
                }
            }
            throw new RuntimeException(e);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return ToolExecutionResult.failure(request, "Error parsing arguments json: " + e.getMessage());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Removes duplicate ToolExecutionRequests from an AiMessage. Duplicates are identified by having the same tool name
     * and arguments. The order of the remaining requests is preserved from the first occurrence.
     *
     * @param message The input AiMessage.
     * @return A new AiMessage with duplicate tool requests removed, or the original message if no requests were present
     *     or no duplicates found.
     */
    public static AiMessage removeDuplicateToolRequests(AiMessage message) {
        if (!message.hasToolExecutionRequests()) {
            return message;
        }

        var deduplicated = List.copyOf(new LinkedHashSet<>(message.toolExecutionRequests()));

        // If no duplicates were found, return the original message
        if (deduplicated.size() == message.toolExecutionRequests().size()) {
            return message;
        }

        // Create a new AiMessage with the unique requests
        return AiMessage.from(message.text(), deduplicated);
    }
}
