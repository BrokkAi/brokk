package ai.brokk.metrics;

import ai.brokk.Llm;
import ai.brokk.TaskResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Unified metrics collector for multi-agent workflows.
 * Tracks timing and resource usage across Search → Architect → Code execution phases.
 *
 * <p>Enable via BRK_COLLECT_METRICS=true environment variable.
 *
 * <p>Usage pattern:
 * <pre>{@code
 * var metrics = WorkflowMetrics.create();
 *
 * // Search phase
 * metrics.startPhase("search");
 * metrics.startSubphase("context_scan");
 * // ... do work ...
 * metrics.endSubphase("context_scan");
 * metrics.recordTokenUsage("context_scan", metadata);
 * metrics.endPhase("search");
 *
 * // Architect phase
 * metrics.startPhase("architect");
 * // ... do work ...
 * metrics.endPhase("architect");
 *
 * // Output
 * String json = metrics.toJson();
 * }</pre>
 */
public class WorkflowMetrics {
    private static final Logger logger = LogManager.getLogger(WorkflowMetrics.class);
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final long workflowStartNanos;
    private final Map<String, PhaseMetrics> phases = new LinkedHashMap<>();
    private final Map<String, Long> activePhaseStarts = new ConcurrentHashMap<>();
    private final Map<String, Long> activeSubphaseStarts = new ConcurrentHashMap<>();

    // Overall workflow info
    private @Nullable String workflowType; // "lutz", "architect", "search", etc.
    private @Nullable TaskResult.StopReason finalStopReason;
    private @Nullable String finalStopExplanation;

    private WorkflowMetrics() {
        this.workflowStartNanos = System.nanoTime();
    }

    /**
     * Create a new workflow metrics collector if BRK_COLLECT_METRICS is enabled,
     * otherwise returns a no-op instance.
     */
    public static WorkflowMetrics create() {
        boolean enabled = "true".equalsIgnoreCase(System.getenv("BRK_COLLECT_METRICS"));
        return enabled ? new WorkflowMetrics() : new NoOpWorkflowMetrics();
    }

    /**
     * Set the workflow type for reporting (e.g., "lutz", "architect", "search").
     */
    public void setWorkflowType(String type) {
        this.workflowType = type;
    }

    /**
     * Start timing a major phase (e.g., "search", "architect", "task_execution").
     */
    public void startPhase(String phaseName) {
        activePhaseStarts.put(phaseName, System.nanoTime());
        phases.computeIfAbsent(phaseName, PhaseMetrics::new);
        logger.debug("Started phase: {}", phaseName);
    }

    /**
     * End timing a major phase.
     */
    public void endPhase(String phaseName) {
        Long startNanos = activePhaseStarts.remove(phaseName);
        if (startNanos == null) {
            logger.warn("endPhase called for phase '{}' that was not started", phaseName);
            return;
        }
        long durationNanos = System.nanoTime() - startNanos;
        PhaseMetrics phase = phases.get(phaseName);
        if (phase != null) {
            phase.totalDurationMs = Duration.ofNanos(durationNanos).toMillis();
        }
        logger.debug("Ended phase: {} ({}ms)", phaseName, phase != null ? phase.totalDurationMs : 0);
    }

    /**
     * Start timing a subphase within the current phase (e.g., "context_scan", "llm_call", "workspace_modification").
     */
    public void startSubphase(String subphaseName) {
        String key = getCurrentPhaseKey() + "." + subphaseName;
        activeSubphaseStarts.put(key, System.nanoTime());
        logger.debug("Started subphase: {}", subphaseName);
    }

    /**
     * End timing a subphase and record it.
     */
    public void endSubphase(String subphaseName) {
        String key = getCurrentPhaseKey() + "." + subphaseName;
        Long startNanos = activeSubphaseStarts.remove(key);
        if (startNanos == null) {
            logger.warn("endSubphase called for '{}' that was not started", subphaseName);
            return;
        }
        long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        PhaseMetrics phase = getCurrentPhase();
        if (phase != null) {
            phase.subphases.put(subphaseName, durationMs);
        }
        logger.debug("Ended subphase: {} ({}ms)", subphaseName, durationMs);
    }

    /**
     * Record token usage for a specific operation (e.g., "context_scan", "llm_turn_1").
     */
    public void recordTokenUsage(String operation, @Nullable Llm.ResponseMetadata metadata) {
        if (metadata == null) {
            return;
        }
        PhaseMetrics phase = getCurrentPhase();
        if (phase != null) {
            TokenUsageInfo usage = new TokenUsageInfo(
                    metadata.inputTokens(),
                    metadata.cachedInputTokens(),
                    metadata.thinkingTokens(),
                    metadata.outputTokens());
            phase.tokenUsage.put(operation, usage);
        }
    }

    /**
     * Increment a counter (e.g., "tool_calls", "files_added").
     */
    public void incrementCounter(String counterName) {
        incrementCounter(counterName, 1);
    }

    /**
     * Increment a counter by a specific amount.
     */
    public void incrementCounter(String counterName, int amount) {
        PhaseMetrics phase = getCurrentPhase();
        if (phase != null) {
            phase.counters.merge(counterName, amount, Integer::sum);
        }
    }

    /**
     * Record the final outcome of the workflow.
     */
    public void recordOutcome(TaskResult.StopReason reason, @Nullable String explanation) {
        this.finalStopReason = reason;
        this.finalStopExplanation = explanation;
    }

    /**
     * Attach agent-specific metrics to a phase (e.g., SearchAgent or CodeAgent metrics).
     */
    public void attachAgentMetrics(String phaseName, String agentType, Object agentMetrics) {
        PhaseMetrics phase = phases.get(phaseName);
        if (phase != null) {
            phase.agentSpecificMetrics.put(agentType, agentMetrics);
        }
    }

    /**
     * Serialize the complete workflow metrics to JSON.
     */
    public String toJson() {
        long totalWorkflowMs =
                Duration.ofNanos(System.nanoTime() - workflowStartNanos).toMillis();

        var result = new WorkflowResult(
                workflowType,
                totalWorkflowMs,
                phases,
                finalStopReason != null ? finalStopReason.name() : null,
                finalStopExplanation);

        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize workflow metrics", e);
            return "{}";
        }
    }

    /**
     * Emit metrics to stderr in the standard BRK_WORKFLOW_METRICS format.
     */
    public void emit() {
        String json = toJson();
        System.err.println("\nBRK_WORKFLOW_METRICS=" + json);
    }

    // Helper methods

    private String getCurrentPhaseKey() {
        // Return the most recently started phase that hasn't ended
        return activePhaseStarts.keySet().stream()
                .reduce((first, second) -> second) // Get last
                .orElse("default");
    }

    private @Nullable PhaseMetrics getCurrentPhase() {
        String key = getCurrentPhaseKey();
        return phases.get(key);
    }

    // Data structures for JSON serialization

    public record WorkflowResult(
            @Nullable String workflow_type,
            long total_duration_ms,
            Map<String, PhaseMetrics> phases,
            @Nullable String final_stop_reason,
            @Nullable String final_stop_explanation) {}

    public static class PhaseMetrics {
        private final String name;
        private long totalDurationMs = 0;
        private final Map<String, Long> subphases = new LinkedHashMap<>();
        private final Map<String, TokenUsageInfo> tokenUsage = new LinkedHashMap<>();
        private final Map<String, Integer> counters = new LinkedHashMap<>();
        private final Map<String, Object> agentSpecificMetrics = new LinkedHashMap<>();

        PhaseMetrics(String name) {
            this.name = name;
        }

        // Getters for JSON serialization
        public String getName() {
            return name;
        }

        public long getTotal_duration_ms() {
            return totalDurationMs;
        }

        public Map<String, Long> getSubphases() {
            return subphases;
        }

        public Map<String, TokenUsageInfo> getToken_usage() {
            return tokenUsage;
        }

        public Map<String, Integer> getCounters() {
            return counters;
        }

        public Map<String, Object> getAgent_specific_metrics() {
            return agentSpecificMetrics;
        }
    }

    public record TokenUsageInfo(int input_tokens, int cached_input_tokens, int thinking_tokens, int output_tokens) {

        public int total() {
            return input_tokens + cached_input_tokens + thinking_tokens + output_tokens;
        }
    }

    /**
     * No-op implementation for when metrics collection is disabled.
     */
    private static class NoOpWorkflowMetrics extends WorkflowMetrics {
        @Override
        public void setWorkflowType(String type) {}

        @Override
        public void startPhase(String phaseName) {}

        @Override
        public void endPhase(String phaseName) {}

        @Override
        public void startSubphase(String subphaseName) {}

        @Override
        public void endSubphase(String subphaseName) {}

        @Override
        public void recordTokenUsage(String operation, @Nullable Llm.ResponseMetadata metadata) {}

        @Override
        public void incrementCounter(String counterName) {}

        @Override
        public void incrementCounter(String counterName, int amount) {}

        @Override
        public void recordOutcome(TaskResult.StopReason reason, @Nullable String explanation) {}

        @Override
        public void attachAgentMetrics(String phaseName, String agentType, Object agentMetrics) {}

        @Override
        public String toJson() {
            return "{}";
        }

        @Override
        public void emit() {
            // No-op
        }
    }
}
