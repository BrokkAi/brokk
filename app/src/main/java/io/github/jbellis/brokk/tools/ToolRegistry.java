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
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

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
        // Skip empty explanations for answer/abort
        if (request.name().equals("answerSearch") || request.name().equals("abortSearch")) {
            return "";
        }

        try {
            // Resolve target and perform typed conversion via validateTool
            var vi = validateTool(toolOwner, request);
            var argsYaml = toYaml(vi);
            var headline = headlineFor(request.name());

            return """
                   ### %s
                   ```yaml
                   %s```
                   """
                    .formatted(headline, argsYaml);
        } catch (ToolValidationException e) {
            // Log validation error but don't crash - this is just for display
            logger.warn("Invalid tool request for display: {} - {}", request.name(), e.getMessage());
            logger.debug("Full tool request: {}", request.arguments());

            // Return empty string - validation details are logged, not shown to user
            return "";
        }
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

    /** Mapping of tool names to display headlines (icons removed). */
    private static final Map<String, String> HEADLINES = Map.ofEntries(
            Map.entry("searchSymbols", "Searching for symbols"),
            Map.entry("searchSubstrings", "Searching for substrings"),
            Map.entry("searchFilenames", "Searching for filenames"),
            Map.entry("getFileContents", "Getting file contents"),
            Map.entry("getFileSummaries", "Getting file summaries"),
            Map.entry("getUsages", "Finding usages"),
            Map.entry("getRelatedClasses", "Finding related code"),
            Map.entry("getClassSkeletons", "Getting class overview"),
            Map.entry("getClassSources", "Fetching class source"),
            Map.entry("getMethodSources", "Fetching method source"),
            Map.entry("getCallGraphTo", "Getting call graph TO"),
            Map.entry("getCallGraphFrom", "Getting call graph FROM"),
            Map.entry("searchGitCommitMessages", "Searching git commits"),
            Map.entry("listFiles", "Listing files"),
            Map.entry("getFiles", "Finding files for classes"),
            Map.entry("addFilesToWorkspace", "Adding files to workspace"),
            Map.entry("addClassesToWorkspace", "Adding classes to workspace"),
            Map.entry("addUrlContentsToWorkspace", "Adding URL contents to workspace"),
            Map.entry("addTextToWorkspace", "Adding text to workspace"),
            Map.entry("addSymbolUsagesToWorkspace", "Adding symbol usages to workspace"),
            Map.entry("addClassSummariesToWorkspace", "Adding class summaries to workspace"),
            Map.entry("addFileSummariesToWorkspace", "Adding file summaries to workspace"),
            Map.entry("addMethodsToWorkspace", "Adding method sources to workspace"),
            Map.entry("addCallGraphInToWorkspace", "Adding callers to workspace"),
            Map.entry("addCallGraphOutToWorkspace", "Adding callees to workspace"),
            Map.entry("dropWorkspaceFragments", "Removing from workspace"),
            Map.entry("recommendContext", "Recommending context"),
            Map.entry("createTaskList", "Creating task list"));

    /** Returns a human-readable headline for the given tool. Falls back to the tool name if there is no mapping. */
    private static String headlineFor(String toolName) {
        return HEADLINES.getOrDefault(toolName, toolName);
    }

    // Internal record to hold method and the instance it belongs to
    private record ToolInvocationTarget(Method method, Object instance) {}

    public record ValidatedInvocation(Method method, Object instance, List<Object> parameters) {}

    public static class ToolValidationException extends RuntimeException {
        public ToolValidationException(String message) {
            super(message);
        }
    }

    /**
     * Minimal atomic signature unit for duplicate detection: - toolName: the tool being invoked - paramName: the list
     * parameter that was sliced - item: the single item from that list parameter
     */
    public record SignatureUnit(String toolName, String paramName, Object item) {}

    /**
     * Returns true if the given tool is a workspace-mutation tool that should never be deduplicated. This is an
     * explicit whitelist so new tools are safe-by-default.
     */
    public boolean isWorkspaceMutationTool(String toolName) {
        return Set.of(
                        "addFilesToWorkspace",
                        "addClassesToWorkspace",
                        "addUrlContentsToWorkspace",
                        "addTextToWorkspace",
                        "addSymbolUsagesToWorkspace",
                        "addClassSummariesToWorkspace",
                        "addFileSummariesToWorkspace",
                        "addMethodsToWorkspace",
                        "addCallGraphInToWorkspace",
                        "addCallGraphOutToWorkspace",
                        "dropWorkspaceFragments")
                .contains(toolName);
    }

    /**
     * Produces a list of SignatureUnit objects for the provided request by validating the request against the target
     * method and inspecting the typed parameters.
     *
     * <p>Constraints for dedupe: - The target method must have exactly one parameter whose runtime value is a
     * Collection. - Each element in that collection must be a primitive wrapper, String, or other simple scalar.
     * Otherwise, throws IllegalArgumentException to signal unsupported pattern for dedupe.
     */
    public List<SignatureUnit> signatureUnits(Object instance, ToolExecutionRequest request) {
        String toolName = request.name();

        ValidatedInvocation vi = validateTool(instance, request);
        Method method = vi.method();
        Parameter[] params = method.getParameters();
        List<Object> values = vi.parameters();

        // Identify collection-like parameters (by runtime value)
        List<Integer> collectionIndices = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) instanceof Collection<?>) {
                collectionIndices.add(i);
            }
        }

        if (collectionIndices.size() != 1) {
            throw new IllegalArgumentException("Tool '" + toolName
                    + "' must have exactly one list parameter for dedupe; found " + collectionIndices.size());
        }

        int sliceIdx = collectionIndices.get(0);
        String sliceName = params[sliceIdx].getName();
        Collection<?> coll = (Collection<?>) values.get(sliceIdx);

        // Validate element shape (primitives/simple scalars)
        for (Object elem : coll) {
            if (!isSimpleScalar(elem)) {
                throw new IllegalArgumentException(
                        "Tool '" + toolName + "' list parameter '" + sliceName + "' contains non-scalar element: "
                                + (elem == null ? "null" : elem.getClass().getName()));
            }
        }

        // Create a unit per element
        return coll.stream()
                .map(item -> new SignatureUnit(toolName, sliceName, item))
                .collect(Collectors.toList());
    }

    /**
     * Build a new ToolExecutionRequest from a set of SignatureUnit items that came from the same original request. This
     * rewrites the single list parameter to the provided items while preserving other arguments.
     */
    public ToolExecutionRequest buildRequestFromUnits(ToolExecutionRequest original, List<SignatureUnit> units) {
        if (units.isEmpty()) return original;

        String toolName = original.name();
        String paramName = units.get(0).paramName();

        // Ensure all units belong to the same tool/param
        boolean consistent = units.stream()
                .allMatch(u -> u.toolName().equals(toolName) && u.paramName().equals(paramName));
        if (!consistent) {
            logger.error("Inconsistent SignatureUnits when rebuilding request for {}: {}", toolName, units);
            return original;
        }

        // Parse original arguments, replace the list parameter with our new items, preserve others
        try {
            Map<String, Object> args = OBJECT_MAPPER.readValue(
                    original.arguments(), new TypeReference<LinkedHashMap<String, Object>>() {});
            var items = units.stream().map(SignatureUnit::item).collect(Collectors.toList());
            if (!args.containsKey(paramName)) {
                logger.error("Parameter '{}' not found in original arguments for tool {}", paramName, toolName);
                return original;
            }
            args.put(paramName, items);
            String json = OBJECT_MAPPER.writeValueAsString(args);
            return ToolExecutionRequest.builder().name(toolName).arguments(json).build();
        } catch (JsonProcessingException e) {
            logger.error("Error rebuilding request from units for {}: {}", toolName, e.getMessage(), e);
            return original;
        }
    }

    private static boolean isSimpleScalar(@Nullable Object v) {
        if (v == null) return true;
        return v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character;
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
                if (paramType instanceof ParameterizedType) {
                    // Preserve generic information (e.g., List<String>) when converting
                    JavaType javaType = typeFactory.constructType(paramType);
                    converted = OBJECT_MAPPER.convertValue(argValue, javaType);

                    // If this is a collection-like type with a specific element type, validate element types
                    if (javaType.isCollectionLikeType()) {
                        JavaType contentType = javaType.getContentType();
                        Class<?> contentClass = contentType.getRawClass();
                        if (contentClass != Object.class) {
                            if (converted instanceof Collection<?> coll) {
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
        Class<?> clazz = toolProviderInstance.getClass();

        for (Method method : clazz.getMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
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
        Class<?> cls = instance.getClass();

        // Gather all instance methods declared in the class that are annotated with @Tool.
        List<Method> annotatedMethods = Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
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
