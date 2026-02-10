package ai.brokk.init.onboarding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Represents an ordered plan of onboarding steps to execute.
 * <p>
 * Steps are ordered based on their dependencies - a step will only execute
 * after all its dependencies have completed.
 * <p>
 * The plan is immutable once created and contains only the steps that are
 * applicable to the current project state.
 */
public class OnboardingPlan {
    private static final Logger logger = LogManager.getLogger(OnboardingPlan.class);

    private final List<OnboardingStep> steps;
    private final Map<String, OnboardingStep> stepById;

    /**
     * Creates an onboarding plan with the given steps.
     * Steps are assumed to already be in dependency order.
     */
    public OnboardingPlan(List<OnboardingStep> steps) {
        this.steps = List.copyOf(steps); // immutable copy
        this.stepById = new HashMap<>();
        for (var step : steps) {
            stepById.put(step.id(), step);
        }
        logger.debug(
                "Created onboarding plan with {} steps: {}",
                steps.size(),
                steps.stream().map(OnboardingStep::id).toList());
    }

    /** Gets all steps in execution order. */
    public List<OnboardingStep> getSteps() {
        return steps;
    }

    /** Gets a step by its ID, or null if not found. */
    public @Nullable OnboardingStep getStep(String stepId) {
        return stepById.get(stepId);
    }

    /** Checks if the plan contains a step with the given ID. */
    public boolean hasStep(String stepId) {
        return stepById.containsKey(stepId);
    }

    /** Gets the number of steps in the plan. */
    public int size() {
        return steps.size();
    }

    /** Checks if the plan is empty. */
    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /**
     * Orders steps based on their dependencies using topological sort.
     * If there are circular dependencies, logs an error and returns
     * the steps in the order they were provided.
     */
    static List<OnboardingStep> orderByDependencies(List<OnboardingStep> steps) {
        if (steps.isEmpty()) {
            return List.of();
        }

        // Build dependency graph
        Map<String, OnboardingStep> stepMap = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (var step : steps) {
            stepMap.put(step.id(), step);
            inDegree.put(step.id(), 0);
        }

        // Calculate in-degrees
        for (var step : steps) {
            for (var dep : step.dependsOn()) {
                if (inDegree.containsKey(dep)) {
                    inDegree.merge(step.id(), 1, Integer::sum);
                } else {
                    logger.warn("Step {} depends on non-existent step: {}", step.id(), dep);
                }
            }
        }

        // Topological sort using Kahn's algorithm
        List<OnboardingStep> ordered = new ArrayList<>();
        Set<String> ready = new HashSet<>();

        // Find all steps with no dependencies
        for (var step : steps) {
            if (inDegree.getOrDefault(step.id(), 0) == 0) {
                ready.add(step.id());
            }
        }

        while (!ready.isEmpty()) {
            // Pick any ready step
            var stepId = ready.iterator().next();
            ready.remove(stepId);

            var step = stepMap.get(stepId);
            ordered.add(step);

            // Reduce in-degree for steps that depended on this one
            for (var otherStep : steps) {
                if (otherStep.dependsOn().contains(stepId)) {
                    var newDegree = inDegree.merge(otherStep.id(), -1, Integer::sum);
                    if (newDegree == 0) {
                        ready.add(otherStep.id());
                    }
                }
            }
        }

        // Check for cycles
        if (ordered.size() != steps.size()) {
            logger.error("Circular dependency detected in onboarding steps. Using original order.");
            return new ArrayList<>(steps);
        }

        logger.debug(
                "Ordered {} steps by dependencies: {}",
                ordered.size(),
                ordered.stream().map(OnboardingStep::id).toList());

        return ordered;
    }
}
