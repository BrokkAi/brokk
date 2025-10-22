package io.github.jbellis.brokk.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Aggregates tool specifications from three sources with deterministic precedence:
 *   1) Workspace-scoped instance tools (WorkspaceTools)
 *   2) Agent-owned instance tools (the agent using this facade)
 *   3) Globally registered tools in ToolRegistry
 *
 * Also routes execution of a ToolExecutionRequest to the bound target.
 */
public final class CompositeToolRegistry {
    private static final Logger logger = LogManager.getLogger(CompositeToolRegistry.class);

    private final ToolRegistry registry;
    private final Object agentInstance;
    private final WorkspaceTools workspaceTools;

    // Binding of tool name -> execution target (decided at spec build time)
    private final Map<String, Target> bindings = new LinkedHashMap<>();

    private enum TargetType {
        WORKSPACE,
        AGENT,
        GLOBAL
    }

    private record Target(TargetType type, @Nullable Object instance) {}

    public CompositeToolRegistry(ToolRegistry registry, WorkspaceTools workspaceTools, Object agentInstance) {
        this.registry = registry;
        this.agentInstance = agentInstance;
        this.workspaceTools = workspaceTools;
    }

    /** Returns the current workspace context if a WorkspaceTools instance is bound, otherwise null. */
    public io.github.jbellis.brokk.context.Context getWorkspaceContext() {
        return workspaceTools.getContext();
    }

    /**
     * Builds ToolSpecifications from allowed names using precedence:
     * workspace instance > agent instance > global registry.
     * Records a binding for each exposed tool to route execution later.
     */
    public List<ToolSpecification> getToolSpecifications(Collection<String> allowedNames) {
        bindings.clear();

        var names = new LinkedHashSet<>(allowedNames); // preserve caller order while deduping
        var specs = new ArrayList<ToolSpecification>(names.size());

        var wsNames = instanceToolNames(workspaceTools);
        var agentNames = instanceToolNames(agentInstance);

        for (var name : names) {
            // Workspace instance takes precedence
            if (wsNames.contains(name)) {
                specs.addAll(registry.getTools(workspaceTools, List.of(name)));
                bindings.put(name, new Target(TargetType.WORKSPACE, workspaceTools));
                continue;
            }

            // Agent instance next
            if (agentNames.contains(name)) {
                specs.addAll(registry.getTools(agentInstance, List.of(name)));
                bindings.put(name, new Target(TargetType.AGENT, agentInstance));
                continue;
            }

            // Global fallback
            var global = registry.getRegisteredTool(name);
            if (global.isPresent()) {
                specs.add(global.get());
                bindings.put(name, new Target(TargetType.GLOBAL, null));
            } else {
                logger.debug("Tool '{}' not found in any provider (workspace, agent, or global); skipping.", name);
            }
        }

        // Log any overshadowing (debug only)
        var overshadowed = names.stream()
                .filter(n -> {
                    boolean ws = wsNames.contains(n);
                    boolean ag = agentNames.contains(n);
                    boolean gl = registry.isRegistered(n);
                    int count = (ws ? 1 : 0) + (ag ? 1 : 0) + (gl ? 1 : 0);
                    return count > 1;
                })
                .collect(Collectors.toList());
        if (!overshadowed.isEmpty()) {
            logger.debug(
                    "Some tool names are provided by multiple sources; instance precedence applied: {}", overshadowed);
        }

        return specs;
    }

    /** Executes using the previously resolved binding for the tool name. */
    public ToolExecutionResult execute(ToolExecutionRequest request) throws InterruptedException {
        var name = request.name();
        var target = bindings.get(name);

        if (target == null) {
            // Fallback resolution if execute is called for a tool that wasn't exposed via getToolSpecifications
            logger.debug("No binding found for tool '{}'; attempting dynamic resolution.", name);
            var wsNames = instanceToolNames(workspaceTools);
            if (wsNames.contains(name)) {
                return registry.executeTool(workspaceTools, request);
            }
            var agentNames = instanceToolNames(agentInstance);
            if (agentNames.contains(name)) {
                return registry.executeTool(agentInstance, request);
            }
            // global fallback
            return registry.executeTool(this, request); // validateTool will fall back to global registry
        }

        return switch (target.type()) {
            case WORKSPACE -> registry.executeTool(Objects.requireNonNull(target.instance()), request);
            case AGENT -> registry.executeTool(Objects.requireNonNull(target.instance()), request);
            case GLOBAL -> registry.executeTool(this, request); // instance has no @Tool methods; falls back to global
        };
    }

    private static Set<String> instanceToolNames(Object instance) {
        Class<?> cls = instance.getClass();
        Set<String> names = new HashSet<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class) && !Modifier.isStatic(m.getModifiers())) {
                names.add(m.getName());
            }
        }
        return names;
    }
}
