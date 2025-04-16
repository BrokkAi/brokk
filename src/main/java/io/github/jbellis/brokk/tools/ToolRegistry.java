package io.github.jbellis.brokk.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays; // Added import
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Discovers, registers, provides specifications for, and executes tools.
 * Tools are methods annotated with @Tool on registered object instances.
 */
public class ToolRegistry {
    private static final Logger logger = LogManager.getLogger(ToolRegistry.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Maps tool name to its invocation target (method + instance)
    private final Map<String, ToolInvocationTarget> toolMap = new ConcurrentHashMap<>();

    // Internal record to hold method and the instance it belongs to
    private record ToolInvocationTarget(Method method, Object instance) {}

    /**
     * Creates a new ToolRegistry and self-registers internal tools.
     */
    public ToolRegistry() {
        // Self-register internal tools
        register(this);
    }

    /**
     * A tool for thinking through complex problems step by step.
     * This allows the model to break down its reasoning process explicitly.
     */
    @Tool(value = """
    Think carefully step by step about a complex problem. Use this tool to reason through difficult questions 
    or break problems into smaller pieces. Call it concurrently with other tools.
    """)
    public String think(@P("The step-by-step reasoning to work through") String reasoning) {
        return "I've thought through this problem: " + reasoning;
    }

    /**
     * Registers all methods annotated with @Tool from the given object instance.
     * @param toolProviderInstance An instance of a class containing methods annotated with @Tool.
     */
    public void register(Object toolProviderInstance) {
        Objects.requireNonNull(toolProviderInstance, "toolProviderInstance cannot be null");
        Class<?> clazz = toolProviderInstance.getClass();

        for (Method method : clazz.getMethods()) {
            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                dev.langchain4j.agent.tool.Tool toolAnnotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();

                if (toolMap.containsKey(toolName)) {
                    throw new IllegalArgumentException("Duplicate tool name registration attempted: '%s'".formatted(toolName));
                } else {
                    logger.debug("Registering tool: '{}' from class {}", toolName, clazz.getName());
                    toolMap.put(toolName, new ToolInvocationTarget(method, toolProviderInstance));
                }
            }
        }
    }

    /**
     * Generates ToolSpecifications for the given list of tool names.
     * @param toolNames A list of tool names to get specifications for.
     * @return A list of ToolSpecification objects. Returns an empty list if a name is not found.
     */
    public List<ToolSpecification> getRegisteredTools(List<String> toolNames) {
        return toolNames.stream()
                .map(toolMap::get)
                .filter(Objects::nonNull)
                .map(target -> ToolSpecifications.toolSpecificationFrom(target.method()))
                .collect(Collectors.toList());
    }

    /**
     * Generates ToolSpecifications for tool methods defined as instance methods within a given object.
     * This is useful for agent-specific tools (like answer/abort) defined within an agent instance.
     * @param instance The object containing the @Tool annotated instance methods.
     * @param toolNames The names of the tools to get specifications for.
     * @return A list of ToolSpecification objects. Returns an empty list if a name is not found or the method doesn't match.
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
                        .orElseThrow(() -> new IllegalArgumentException("No tool method found for %s in %s".formatted(toolName, instance)))
                )
                .map(ToolSpecifications::toolSpecificationFrom)
                .collect(Collectors.toList());
    }

    /**
     * Executes a tool based on the provided ToolExecutionRequest.
     * Handles argument parsing and method invocation via reflection.
     * @param request The ToolExecutionRequest from the LLM.
     * @return A ToolExecutionResult indicating success or failure.
     */
    public ToolExecutionResult executeTool(ToolExecutionRequest request) {
        ToolInvocationTarget target = toolMap.get(request.name());
        if (target == null) {
            logger.error("Tool not found: {}", request.name());
            // Return failure
            return ToolExecutionResult.failure(request, "Tool not found: " + request.name());
        }

        return executeTool(request, target);
    }

    /**
     * Executes a tool defined as an instance method on the provided object.
     * @param instance The object instance containing the @Tool annotated method.
     * @param request The ToolExecutionRequest from the LLM.
     * @return A ToolExecutionResult indicating success or failure.
     */
    public ToolExecutionResult executeTool(Object instance, ToolExecutionRequest request) {
        assert instance != null;
        Class<?> cls = instance.getClass();
        String toolName = request.name();

        // Find the method matching the tool name within the instance's class
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

        if (targetMethod == null) {
            logger.error("Tool '{}' not found as an instance method on class {}", toolName, cls.getName());
            return ToolExecutionResult.failure(request, "Tool not found: " + toolName);
        }

        // Create the target and execute
        ToolInvocationTarget target = new ToolInvocationTarget(targetMethod, instance);
        return executeTool(request, target);
    }

    /**
     * Private helper to execute a tool given a request and a resolved target.
     * Handles argument parsing and method invocation.
     * @param request The execution request.
     * @param target The resolved method and instance.
     * @return The execution result.
     */
    private static @NotNull ToolExecutionResult executeTool(ToolExecutionRequest request, ToolInvocationTarget target) {
        Method method = target.method();
        Object instance = target.instance();

        try {
            // 1. Parse JSON arguments from the request
            Map<String, Object> argumentsMap = OBJECT_MAPPER.readValue(request.arguments(), new TypeReference<HashMap<String, Object>>() {});

            // 2. Prepare the arguments array for Method.invoke
            Parameter[] parameters = method.getParameters();
            Object[] methodArgs = new Object[parameters.length];

            for (int i = 0; i < parameters.length; i++) {
                Parameter param = parameters[i];
                if (!argumentsMap.containsKey(param.getName())) {
                    return ToolExecutionResult.failure(request, "Missing required parameter: '%s' in arguments: %s".formatted(param.getName(), request.arguments()));
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
        } catch (Exception e) {
            logger.error("Error executing tool {}", request.name(), e);
            return ToolExecutionResult.failure(request, e.getMessage());
        }
    }
}
