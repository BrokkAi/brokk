package io.github.jbellis.brokk.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.AiMessage;
import io.github.jbellis.brokk.ContextManager;
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

    /** Generates a user-friendly explanation for a tool request as a Markdown code fence with YAML formatting. */
    public String getExplanationForToolRequest(Object toolOwner, ToolExecutionRequest request) {
        var displayMeta = ToolDisplayMeta.fromToolName(request.name());

        // Skip empty explanations for answer/abort
        if (request.name().equals("answerSearch") || request.name().equals("abortSearch")) {
            return "";
        }

        // Resolve target and perform typed conversion via validateTool; let ToolValidationException propagate.
        var vi = validateTool(toolOwner, request);
        var argsYaml = toYaml(vi);

        return """
               ### %s %s
               ```yaml
               %s```
               """
                .formatted(displayMeta.getIcon(), displayMeta.getHeadline(), argsYaml);
    }

    // Helper to render a simple YAML block from a map of arguments
    private static String toYaml(ValidatedInvocation vi) {
        var named = new LinkedHashMap<String, Object>();
        var params = vi.method().getParameters();
        var values = vi.parameters();
        assert params.length == values.size();
        for (int i = 0; i < params.length; i++) {
            named.put(params[i].getName(), values.get(i));
        }
        var args = (Map<String, Object>) named;

        var sb = new StringBuilder();
        for (var entry : args.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (value instanceof Collection<?> list) {
                sb.append(key).append(":\n");
                for (var item : list) {
                    sb.append("  - ").append(item).append("\n");
                }
            } else if (value instanceof String s && s.contains("\n")) {
                sb.append(key).append(": |\n");
                s.lines().forEach(line -> sb.append("  ").append(line).append("\n"));
            } else {
                sb.append(key).append(": ").append(value).append("\n");
            }
        }
        return sb.toString();
    }

    // Helper to find a @Tool instance method by effective tool name on the provided instance

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
        SEARCH_GIT_COMMIT_MESSAGES("🔍", "Searching git commits"),
        LIST_FILES("🔍", "Listing files"),
        GET_FILES("🔍", "Finding files for classes"),
        ADD_FILES_TO_WORKSPACE("📝", "Adding files to workspace"),
        ADD_CLASSES_TO_WORKSPACE("📝", "Adding classes to workspace"),
        ADD_URL_CONTENTS_TO_WORKSPACE("📝", "Adding URL contents to workspace"),
        ADD_TEXT_TO_WORKSPACE("📝", "Adding text to workspace"),
        ADD_SYMBOL_USAGES_TO_WORKSPACE("📝", "Adding symbol usages to workspace"),
        ADD_CLASS_SUMMARIES_TO_WORKSPACE("📝", "Adding class summaries to workspace"),
        ADD_FILE_SUMMARIES_TO_WORKSPACE("📝", "Adding file summaries to workspace"),
        ADD_METHODS_TO_WORKSPACE("📝", "Adding method sources to workspace"),
        ADD_CALL_GRAPH_IN_TO_WORKSPACE("📝", "Adding callers to workspace"),
        ADD_CALL_GRAPH_OUT_TO_WORKSPACE("📝", "Adding callees to workspace"),
        DROP_WORKSPACE_FRAGMENTS("🗑️", "Removing from workspace"),
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
                case "searchGitCommitMessages" -> SEARCH_GIT_COMMIT_MESSAGES;
                case "listFiles" -> LIST_FILES;
                case "getFiles" -> GET_FILES;
                case "addFilesToWorkspace" -> ADD_FILES_TO_WORKSPACE;
                case "addClassesToWorkspace" -> ADD_CLASSES_TO_WORKSPACE;
                case "addUrlContentsToWorkspace" -> ADD_URL_CONTENTS_TO_WORKSPACE;
                case "addTextToWorkspace" -> ADD_TEXT_TO_WORKSPACE;
                case "addSymbolUsagesToWorkspace" -> ADD_SYMBOL_USAGES_TO_WORKSPACE;
                case "addClassSummariesToWorkspace" -> ADD_CLASS_SUMMARIES_TO_WORKSPACE;
                case "addFileSummariesToWorkspace" -> ADD_FILE_SUMMARIES_TO_WORKSPACE;
                case "addMethodsToWorkspace" -> ADD_METHODS_TO_WORKSPACE;
                case "addCallGraphInToWorkspace" -> ADD_CALL_GRAPH_IN_TO_WORKSPACE;
                case "addCallGraphOutToWorkspace" -> ADD_CALL_GRAPH_OUT_TO_WORKSPACE;
                case "dropWorkspaceFragments" -> DROP_WORKSPACE_FRAGMENTS;
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

    public record ValidatedInvocation(Method method, Object instance, List<Object> parameters) {}

    public static class ToolValidationException extends RuntimeException {
        public ToolValidationException(String message) {
            super(message);
        }
    }

    /** Validates a tool request against the provided instance's @Tool methods (falling back to globals). */
    public ValidatedInvocation validateTool(Object instance, ToolExecutionRequest request) {
        String toolName = request.name();
        if (toolName.isBlank()) {
            throw new ToolValidationException("Tool name cannot be empty");
        }

        // first check the instance
        Class<?> cls = instance.getClass();
        Method targetMethod = Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(m -> {
                    String name = m.getName();
                    return name.equals(toolName);
                })
                .findFirst()
                .orElse(null);

        // then check the global tool map
        ToolInvocationTarget target =
                (targetMethod != null) ? new ToolInvocationTarget(targetMethod, instance) : toolMap.get(request.name());
        if (target == null) {
            throw new ToolValidationException("Tool not found: " + request.name());
        }

        var args = parseArguments(request, target.method());
        return new ValidatedInvocation(target.method(), target.instance(), args);
    }

    private static List<Object> parseArguments(ToolExecutionRequest request, Method method) {
        try {
            Map<String, Object> argumentsMap =
                    OBJECT_MAPPER.readValue(request.arguments(), new TypeReference<HashMap<String, Object>>() {});
            Parameter[] jsonParams = method.getParameters();
            var parameters = new ArrayList<Object>(jsonParams.length);
            var typeFactory = OBJECT_MAPPER.getTypeFactory();

            for (Parameter param : jsonParams) {
                if (!argumentsMap.containsKey(param.getName())) {
                    throw new ToolValidationException("Missing required parameter: '%s' in arguments: %s"
                            .formatted(param.getName(), request.arguments()));
                }

                Object argValue = argumentsMap.get(param.getName());
                Object converted;

                var paramType = param.getParameterizedType();
                if (paramType instanceof java.lang.reflect.ParameterizedType) {
                    // Preserve generic information (e.g., List<String>) when converting
                    JavaType javaType = typeFactory.constructType(paramType);
                    converted = OBJECT_MAPPER.convertValue(argValue, javaType);

                    // If this is a collection-like type with a specific element type, validate element types
                    if (javaType.isCollectionLikeType()) {
                        JavaType contentType = javaType.getContentType();
                        Class<?> contentClass = contentType.getRawClass();
                        if (contentClass != Object.class) {
                            if (converted instanceof java.util.Collection<?> coll) {
                                for (Object elem : coll) {
                                    if (!contentClass.isInstance(elem)) {
                                        throw new ToolValidationException(
                                                "Parameter '%s' expected elements of type %s but got %s"
                                                        .formatted(
                                                                param.getName(),
                                                                contentClass.getName(),
                                                                elem.getClass().getName()));
                                    }
                                }
                            } else if (converted != null && !contentClass.isInstance(converted)) {
                                // Handle non-collection cases conservatively
                                throw new ToolValidationException("Parameter '%s' expected value of type %s but got %s"
                                        .formatted(
                                                param.getName(),
                                                contentClass.getName(),
                                                converted.getClass().getName()));
                            }
                        }
                    }
                } else {
                    // Non-parameterized types (or primitives) - fall back to raw type conversion
                    converted = OBJECT_MAPPER.convertValue(argValue, param.getType());
                }

                parameters.add(converted);
            }
            return parameters;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ToolValidationException("Error parsing arguments json: " + e.getMessage());
        }
    }

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
                String toolName = method.getName();
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
                            String name = m.getName();
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
        ValidatedInvocation validated;
        try {
            validated = validateTool(instance, request);
        } catch (ToolValidationException e) {
            return ToolExecutionResult.failure(request, e.getMessage());
        }

        try {
            logger.debug("Invoking tool '{}' with args: {}", request.name(), validated.parameters());
            Object resultObject = validated
                    .method()
                    .invoke(validated.instance(), validated.parameters().toArray());
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
