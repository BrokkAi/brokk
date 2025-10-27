package io.github.jbellis.brokk.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Discovers, registers, provides specifications for, and executes tools.
 * Tools are methods annotated with @Tool on registered object instances.
 *
 * Builder pattern:
 * - Create per-turn registries via ToolRegistry.builder(base).register(...).build()
 * - Built registries are sealed (immutable) and cannot be mutated.
 */
public class ToolRegistry {
    private static final Logger logger = LogManager.getLogger(ToolRegistry.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Backing map for tools. Use a synchronized LinkedHashMap for deterministic ordering while remaining thread-safe.
    private final Map<String, ToolInvocationTarget> toolMap;

    // Internal record to hold method and the instance it belongs to
    private record ToolInvocationTarget(Method method, Object instance) {}

    public record ValidatedInvocation(Method method, Object instance, List<Object> parameters) {}

    public static class ToolValidationException extends RuntimeException {
        public ToolValidationException(String message) {
            super(message);
        }
    }

    /**
     * Minimal atomic signature unit for duplicate detection
     */
    public record SignatureUnit(String toolName, String paramName, Object item) {}

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
            Map.entry("appendNote", "Appending note"),
            Map.entry("addSymbolUsagesToWorkspace", "Adding symbol usages to workspace"),
            Map.entry("addClassSummariesToWorkspace", "Adding class summaries to workspace"),
            Map.entry("addFileSummariesToWorkspace", "Adding file summaries to workspace"),
            Map.entry("addMethodsToWorkspace", "Adding method sources to workspace"),
            Map.entry("dropWorkspaceFragments", "Removing from workspace"),
            Map.entry("recommendContext", "Recommending context"),
            Map.entry("createTaskList", "Creating task list"));

    /** Returns a human-readable headline for the given tool. Falls back to the tool name if there is no mapping. */
    private static String headlineFor(String toolName) {
        return HEADLINES.getOrDefault(toolName, toolName);
    }

    /** Helper to render a simple YAML block from a validated invocation. */
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

    /** Creates a new root ToolRegistry and self-registers internal tools. */
    public ToolRegistry() {
        this(new LinkedHashMap<>());
        // Root-only registration of builtin tools (like 'think') happens only for the root registry.
        register(this);
    }

    /**
     * Private constructor used for initializing the registry storage.
     *
     * @param initialMap initial content to seed the registry with (will be copied)
     */
    private ToolRegistry(Map<String, ToolInvocationTarget> initialMap) {
        this.toolMap = Collections.synchronizedMap(new LinkedHashMap<>(initialMap));
    }

    /** Returns an empty, sealed root registry (primarily for tests). */
    public static ToolRegistry empty() {
        return new ToolRegistry(Map.of());
    }

    /** Builder for creating a sealed local registry based on a base registry. */
    public static Builder builder(ToolRegistry base) {
        return new Builder(base);
    }

    /** Instance builder to avoid FQN/import issues at call sites. */
    public Builder builder() {
        return new Builder(this);
    }

    public static final class Builder {
        private final Map<String, ToolInvocationTarget> entries;

        private Builder(ToolRegistry base) {
            synchronized (base.toolMap) {
                this.entries = new LinkedHashMap<>(base.toolMap);
            }
        }

        /** Register @Tool methods from the given instance; last registration wins on name conflicts. */
        public Builder register(Object toolProviderInstance) {
            Class<?> clazz = toolProviderInstance.getClass();
            for (Method method : clazz.getMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) continue;
                String toolName = method.getName();
                var existing = entries.get(toolName);
                if (existing != null) {
                    logger.debug(
                            "Overriding tool {} provided by {} with {}",
                            toolName,
                            existing.instance().getClass().getName(),
                            toolProviderInstance.getClass().getName());
                } else {
                    logger.debug("Registering tool: '{}' from class {}", toolName, clazz.getName());
                }
                entries.put(toolName, new ToolInvocationTarget(method, toolProviderInstance));
            }
            return this;
        }

        /** Build a sealed, non-root ToolRegistry with the accumulated entries. */
        public ToolRegistry build() {
            return new ToolRegistry(entries);
        }
        // fluent register chaining supported by returning Builder
    }

    /** Generates ToolSpecifications for the given list of tool names. */
    public List<ToolSpecification> getTools(Collection<String> toolNames) {
        var present = toolNames.stream().filter(toolMap::containsKey).toList();
        var missing = toolNames.stream().filter(t -> !toolMap.containsKey(t)).toList();
        if (!missing.isEmpty()) {
            logger.warn("Some requested global tools are not registered and will be skipped: {}", missing);
        }
        return present.stream()
                .map(toolMap::get)
                .map(target -> ToolSpecifications.toolSpecificationFrom(target.method()))
                .collect(Collectors.toList());
    }

    /** Returns a single global tool specification if registered; empty if missing (no error logging). */
    public Optional<ToolSpecification> getRegisteredTool(String toolName) {
        var target = toolMap.get(toolName);
        if (target == null) return Optional.empty();
        return Optional.of(ToolSpecifications.toolSpecificationFrom(target.method()));
    }

    /** Returns true if a global tool with the given name is registered. */
    public boolean isRegistered(String toolName) {
        return toolMap.containsKey(toolName);
    }

    /** Executes a tool exclusively from the registry (no instance tools). */
    public ToolExecutionResult executeTool(ToolExecutionRequest request) throws InterruptedException {
        ValidatedInvocation validated;
        try {
            validated = validateTool(request);
        } catch (ToolValidationException e) {
            return ToolExecutionResult.failure(request, e.getMessage());
        }

        try {
            logger.debug("Invoking global tool '{}' with args: {}", request.name(), validated.parameters());
            Object resultObject = validated
                    .method()
                    .invoke(validated.instance(), validated.parameters().toArray());
            String resultString = resultObject != null ? resultObject.toString() : "";
            return ToolExecutionResult.success(request, resultString);
        } catch (InvocationTargetException e) {
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

    /** Remove duplicate ToolExecutionRequests from an AiMessage while preserving order. */
    public static AiMessage removeDuplicateToolRequests(AiMessage message) {
        if (!message.hasToolExecutionRequests()) {
            return message;
        }
        var deduplicated = List.copyOf(new LinkedHashSet<>(message.toolExecutionRequests()));
        if (deduplicated.size() == message.toolExecutionRequests().size()) {
            return message;
        }
        return AiMessage.from(message.text(), deduplicated);
    }

    @Tool(
            """
    Think carefully step by step about a complex problem. Use this tool to reason through difficult questions
    or break problems into smaller pieces. Call it concurrently with other tools.
    """)
    public String think(@P("The step-by-step reasoning to work through") String reasoning) {
        return "Good thinking.";
    }

    /** Register @Tool methods from the given instance (allowed only when not sealed). */
    public void register(Object toolProviderInstance) {
        Class<?> clazz = toolProviderInstance.getClass();

        for (Method method : clazz.getMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            String toolName = method.getName();

            synchronized (toolMap) {
                if (toolMap.containsKey(toolName)) {
                    var existing = toolMap.get(toolName);
                    var existingClass = existing.instance().getClass().getName();
                    var providerClass = toolProviderInstance.getClass().getName();
                    logger.debug("Overriding tool {} provided by {} with {}", toolName, existingClass, providerClass);
                } else {
                    logger.debug("Registering tool: '{}' from class {}", toolName, clazz.getName());
                }
                toolMap.put(toolName, new ToolInvocationTarget(method, toolProviderInstance));
            }
        }
    }

    /** Validate against this registry. */
    public ValidatedInvocation validateTool(ToolExecutionRequest request) {
        String toolName = request.name();
        if (toolName.isBlank()) {
            throw new ToolValidationException("Tool name cannot be empty");
        }

        ToolInvocationTarget target = toolMap.get(request.name());
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
                    JavaType javaType = typeFactory.constructType(paramType);
                    converted = OBJECT_MAPPER.convertValue(argValue, javaType);

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
                                throw new ToolValidationException("Parameter '%s' expected value of type %s but got %s"
                                        .formatted(
                                                param.getName(),
                                                contentClass.getName(),
                                                converted.getClass().getName()));
                            }
                        }
                    }
                } else {
                    converted = OBJECT_MAPPER.convertValue(argValue, param.getType());
                }

                parameters.add(converted);
            }
            return parameters;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ToolValidationException("Error parsing arguments json: " + e.getMessage());
        }
    }

    /** Generates a user-friendly explanation for a tool request validated against THIS registry. */
    public String getExplanationForToolRequest(ToolExecutionRequest request) {
        if (request.name().equals("answerSearch") || request.name().equals("abortSearch")) {
            return "";
        }
        try {
            var vi = validateTool(request);
            var argsYaml = toYaml(vi);
            var headline = headlineFor(request.name());
            return """
                   ### %s
                   ```yaml
                   %s```
                   """
                    .formatted(headline, argsYaml);
        } catch (ToolValidationException e) {
            logger.debug("Could not generate explanation for tool request '{}': {}", request.name(), e.getMessage());
            return "";
        }
    }

    /** Deduplication helper producing one signature unit per element of the single list param. */
    public List<SignatureUnit> signatureUnits(Object instance, ToolExecutionRequest request) {
        String toolName = request.name();

        ValidatedInvocation vi = validateTool(request);
        Method method = vi.method();
        Parameter[] params = method.getParameters();
        List<Object> values = vi.parameters();

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

        int sliceIdx = collectionIndices.getFirst();
        String sliceName = params[sliceIdx].getName();
        Collection<?> coll = (Collection<?>) values.get(sliceIdx);

        for (Object elem : coll) {
            if (!isSimpleScalar(elem)) {
                throw new IllegalArgumentException("Tool '" + toolName + "' list parameter '" + sliceName
                        + "' contains non-scalar element: " + elem.getClass().getName());
            }
        }

        return coll.stream()
                .map(item -> new SignatureUnit(toolName, sliceName, item))
                .collect(Collectors.toList());
    }

    /** Rebuild a ToolExecutionRequest from a slice of signature units belonging to the same list parameter. */
    public ToolExecutionRequest buildRequestFromUnits(ToolExecutionRequest original, List<SignatureUnit> units) {
        if (units.isEmpty()) return original;

        String toolName = original.name();
        String paramName = units.getFirst().paramName();

        boolean consistent = units.stream()
                .allMatch(u -> u.toolName().equals(toolName) && u.paramName().equals(paramName));
        if (!consistent) {
            logger.error("Inconsistent SignatureUnits when rebuilding request for {}: {}", toolName, units);
            return original;
        }

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
}
