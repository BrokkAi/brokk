package ai.brokk.metrics;

import ai.brokk.TaskResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public interface ArchitectMetrics {

    default void startTurn() {
        startTurn(System.currentTimeMillis());
    }

    void startTurn(long requestStartedAtMs);

    void recordToolCall(String toolName, long startedAtMs, long completedAtMs, String status);

    default void endTurn() {
        endTurn(System.currentTimeMillis());
    }

    void endTurn(long turnCompletedAtMs);

    default void recordLlmCall(
            long timeMs, int inputTokens, int cachedInputTokens, int thinkingTokens, int outputTokens) {
        recordLlmCall(System.currentTimeMillis(), timeMs, inputTokens, cachedInputTokens, thinkingTokens, outputTokens);
    }

    void recordLlmCall(
            long llmCompletedAtMs,
            long timeMs,
            int inputTokens,
            int cachedInputTokens,
            int thinkingTokens,
            int outputTokens);

    default void recordPlanningEvent(String type, String detail) {
        recordPlanningEvent(type, System.currentTimeMillis(), detail);
    }

    void recordPlanningEvent(String type, long occurredAtMs, String detail);

    void recordSubAgentCall(
            String agentName,
            String trigger,
            long startedAtMs,
            long completedAtMs,
            TaskResult.StopDetails stopDetails,
            int requestCount,
            boolean workspaceChanged);

    void recordOutcome(TaskResult.StopDetails stopDetails, int workspaceSize);

    void recordFinalWorkspaceFiles(Set<String> finalFiles);

    void recordFinalWorkspaceFragments(List<FragmentInfo> fragmentDescriptions);

    String toJson(String goal, boolean success);

    record FragmentInfo(String type, String id, String description, List<String> files) {}

    record ToolCallDetail(String name, long started_at_ms, long completed_at_ms, long duration_ms, String status) {}

    record PlanningEvent(String type, long occurred_at_ms, String detail) {}

    record SubAgentCall(
            String agent,
            @Nullable Integer planning_turn,
            String trigger,
            long started_at_ms,
            long completed_at_ms,
            long duration_ms,
            String stop_reason,
            String stop_explanation,
            int request_count,
            boolean workspace_changed) {}

    static ArchitectMetrics noOp() {
        return NoOp.INSTANCE;
    }

    static ArchitectMetrics tracking() {
        return new Tracking();
    }

    enum NoOp implements ArchitectMetrics {
        INSTANCE;

        @Override
        public void startTurn(long requestStartedAtMs) {}

        @Override
        public void recordToolCall(String toolName, long startedAtMs, long completedAtMs, String status) {}

        @Override
        public void endTurn(long turnCompletedAtMs) {}

        @Override
        public void recordLlmCall(
                long llmCompletedAtMs,
                long timeMs,
                int inputTokens,
                int cachedInputTokens,
                int thinkingTokens,
                int outputTokens) {}

        @Override
        public void recordPlanningEvent(String type, long occurredAtMs, String detail) {}

        @Override
        public void recordSubAgentCall(
                String agentName,
                String trigger,
                long startedAtMs,
                long completedAtMs,
                TaskResult.StopDetails stopDetails,
                int requestCount,
                boolean workspaceChanged) {}

        @Override
        public void recordOutcome(TaskResult.StopDetails stopDetails, int workspaceSize) {}

        @Override
        public void recordFinalWorkspaceFiles(Set<String> finalFiles) {}

        @Override
        public void recordFinalWorkspaceFragments(List<FragmentInfo> fragmentDescriptions) {}

        @Override
        public String toJson(String goal, boolean success) {
            return "{}";
        }
    }

    class Tracking implements ArchitectMetrics {
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        private final long taskStartedAtMs;
        private long completedAtMsHint;

        private int turnCounter = 0;
        private final List<TurnMetrics> turns = new ArrayList<>();
        private final List<SubAgentCall> subAgentCalls = new ArrayList<>();
        private @Nullable TurnMetrics currentTurn = null;

        private @Nullable String failureType = null;
        private String stopReason = "";
        private String stopExplanation = "";
        private int finalWorkspaceSize = 0;
        private Set<String> finalWorkspaceFiles = Set.of();
        private List<FragmentInfo> finalWorkspaceFragments = List.of();

        Tracking() {
            this(System.currentTimeMillis());
        }

        Tracking(long taskStartedAtMs) {
            this.taskStartedAtMs = taskStartedAtMs;
            this.completedAtMsHint = taskStartedAtMs;
        }

        @Override
        public synchronized void startTurn(long requestStartedAtMs) {
            if (currentTurn != null) {
                currentTurn.completeTurn(requestStartedAtMs);
                turns.add(currentTurn);
            }
            completedAtMsHint = Math.max(completedAtMsHint, requestStartedAtMs);
            currentTurn = new TurnMetrics(++turnCounter, requestStartedAtMs);
        }

        @Override
        public synchronized void recordToolCall(String toolName, long startedAtMs, long completedAtMs, String status) {
            if (currentTurn != null) {
                currentTurn.addToolCall(toolName, startedAtMs, completedAtMs, status);
            }
            completedAtMsHint = Math.max(completedAtMsHint, completedAtMs);
        }

        @Override
        public synchronized void endTurn(long turnCompletedAtMs) {
            if (currentTurn == null) {
                completedAtMsHint = Math.max(completedAtMsHint, turnCompletedAtMs);
                return;
            }
            currentTurn.completeTurn(turnCompletedAtMs);
            turns.add(currentTurn);
            currentTurn = null;
            completedAtMsHint = Math.max(completedAtMsHint, turnCompletedAtMs);
        }

        @Override
        public synchronized void recordLlmCall(
                long llmCompletedAtMs,
                long timeMs,
                int inputTokens,
                int cachedInputTokens,
                int thinkingTokens,
                int outputTokens) {
            if (currentTurn != null) {
                currentTurn.recordLlmCall(
                        llmCompletedAtMs, timeMs, inputTokens, cachedInputTokens, thinkingTokens, outputTokens);
            }
            completedAtMsHint = Math.max(completedAtMsHint, llmCompletedAtMs);
        }

        @Override
        public synchronized void recordPlanningEvent(String type, long occurredAtMs, String detail) {
            if (currentTurn != null) {
                currentTurn.addPlanningEvent(type, occurredAtMs, detail);
            }
            completedAtMsHint = Math.max(completedAtMsHint, occurredAtMs);
        }

        @Override
        public synchronized void recordSubAgentCall(
                String agentName,
                String trigger,
                long startedAtMs,
                long completedAtMs,
                TaskResult.StopDetails stopDetails,
                int requestCount,
                boolean workspaceChanged) {
            Integer planningTurn = currentTurn != null ? currentTurn.getTurn() : null;
            var call = new SubAgentCall(
                    agentName,
                    planningTurn,
                    trigger,
                    startedAtMs,
                    completedAtMs,
                    duration(startedAtMs, completedAtMs),
                    stopDetails.reason().name(),
                    stopDetails.explanation(),
                    requestCount,
                    workspaceChanged);
            subAgentCalls.add(call);
            if (currentTurn != null) {
                currentTurn.addSubAgentCall(call);
            }
            completedAtMsHint = Math.max(completedAtMsHint, completedAtMs);
        }

        @Override
        public synchronized void recordOutcome(TaskResult.StopDetails stopDetails, int workspaceSize) {
            stopReason = stopDetails.reason().name();
            stopExplanation = stopDetails.explanation();
            finalWorkspaceSize = workspaceSize;
            failureType = switch (stopDetails.reason()) {
                case SUCCESS -> null;
                default -> stopDetails.reason().name().toLowerCase(Locale.ROOT);
            };
        }

        @Override
        public synchronized void recordFinalWorkspaceFiles(Set<String> finalFiles) {
            finalWorkspaceFiles = Set.copyOf(finalFiles);
        }

        @Override
        public synchronized void recordFinalWorkspaceFragments(List<FragmentInfo> fragmentDescriptions) {
            finalWorkspaceFragments = fragmentDescriptions.stream()
                    .map(fragment -> new FragmentInfo(
                            fragment.type(),
                            fragment.id(),
                            fragment.description(),
                            fragment.files().stream().sorted().toList()))
                    .sorted(Comparator.comparing(FragmentInfo::type)
                            .thenComparing(FragmentInfo::id)
                            .thenComparing(FragmentInfo::description))
                    .toList();
        }

        public synchronized List<TurnMetrics> getTurns() {
            return new ArrayList<>(turns);
        }

        @Override
        public synchronized String toJson(String goal, boolean success) {
            finalizeCurrentTurnIfNeeded();

            long llmElapsedMs = turns.stream()
                    .map(TurnMetrics::getLlm_duration_ms)
                    .filter(value -> value != null)
                    .mapToLong(Long::longValue)
                    .sum();
            long toolExecutionElapsedMs = turns.stream()
                    .map(TurnMetrics::getTool_execution_duration_ms)
                    .filter(value -> value != null)
                    .mapToLong(Long::longValue)
                    .sum();
            long planningElapsedMs = turns.stream()
                    .map(TurnMetrics::getTurn_duration_ms)
                    .filter(value -> value != null)
                    .mapToLong(Long::longValue)
                    .sum();
            int toolCallsTotal =
                    turns.stream().mapToInt(turn -> turn.getTool_calls().size()).sum();

            long completedAtMs = completedAtMsHint;
            long wallElapsedMs = Math.max(0L, completedAtMs - taskStartedAtMs);

            var result = new ArchitectResult(
                    goal,
                    success,
                    turns.size(),
                    new ArrayList<>(turns),
                    toolCallsTotal,
                    subAgentCalls.size(),
                    new ArrayList<>(subAgentCalls),
                    llmElapsedMs,
                    toolExecutionElapsedMs,
                    planningElapsedMs,
                    failureType,
                    stopReason,
                    stopExplanation,
                    finalWorkspaceSize,
                    finalWorkspaceFiles.stream().sorted().toList(),
                    new ArrayList<>(finalWorkspaceFragments),
                    taskStartedAtMs,
                    completedAtMs,
                    wallElapsedMs);

            try {
                return OBJECT_MAPPER.writeValueAsString(result);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize architect metrics", e);
            }
        }

        private void finalizeCurrentTurnIfNeeded() {
            if (currentTurn == null) {
                return;
            }
            long turnCompletedAtMs = Math.max(completedAtMsHint, currentTurn.getRequest_started_at_ms());
            currentTurn.completeTurn(turnCompletedAtMs);
            turns.add(currentTurn);
            currentTurn = null;
            completedAtMsHint = Math.max(completedAtMsHint, turnCompletedAtMs);
        }

        private static long duration(long startedAtMs, long completedAtMs) {
            return Math.max(0L, completedAtMs - startedAtMs);
        }

        public record ArchitectResult(
                String goal,
                boolean success,
                int planning_turns,
                List<TurnMetrics> planning_turns_detail,
                int tool_calls_total,
                int sub_agent_calls_total,
                List<SubAgentCall> sub_agent_calls,
                long llm_elapsed_ms,
                long tool_execution_elapsed_ms,
                long planning_elapsed_ms,
                @Nullable String failure_type,
                String stop_reason,
                String stop_explanation,
                int final_workspace_size,
                List<String> final_workspace_files,
                List<FragmentInfo> final_workspace_fragments,
                long started_at_ms,
                long completed_at_ms,
                long wall_elapsed_ms) {}

        public static class TurnMetrics {
            private final int turn;
            private final long request_started_at_ms;
            private final List<ToolCallDetail> tool_calls = new ArrayList<>();
            private final List<PlanningEvent> planning_events = new ArrayList<>();
            private final List<SubAgentCall> sub_agent_calls = new ArrayList<>();
            private @Nullable Long llm_completed_at_ms = null;
            private @Nullable Long turn_completed_at_ms = null;
            private @Nullable Long llm_duration_ms = null;
            private @Nullable Long turn_duration_ms = null;
            private @Nullable Long tool_execution_duration_ms = null;
            private int input_tokens = 0;
            private int cached_input_tokens = 0;
            private int thinking_tokens = 0;
            private int output_tokens = 0;

            public TurnMetrics(int turnNumber, long requestStartedAtMs) {
                this.turn = turnNumber;
                this.request_started_at_ms = requestStartedAtMs;
            }

            public void addToolCall(String toolName, long startedAtMs, long completedAtMs, String status) {
                tool_calls.add(new ToolCallDetail(
                        toolName, startedAtMs, completedAtMs, duration(startedAtMs, completedAtMs), status));
            }

            public void addPlanningEvent(String type, long occurredAtMs, String detail) {
                planning_events.add(new PlanningEvent(type, occurredAtMs, detail));
            }

            public void addSubAgentCall(SubAgentCall call) {
                sub_agent_calls.add(call);
            }

            public void recordLlmCall(
                    long llmCompletedAtMs,
                    long timeMs,
                    int inputTokens,
                    int cachedInputTokens,
                    int thinkingTokens,
                    int outputTokens) {
                llm_completed_at_ms = llmCompletedAtMs;
                llm_duration_ms = timeMs;
                input_tokens = inputTokens;
                cached_input_tokens = cachedInputTokens;
                this.thinking_tokens = thinkingTokens;
                this.output_tokens = outputTokens;
            }

            public void completeTurn(long turnCompletedAtMs) {
                turn_completed_at_ms = turnCompletedAtMs;
                turn_duration_ms = duration(request_started_at_ms, turnCompletedAtMs);
                tool_execution_duration_ms =
                        llm_completed_at_ms == null ? null : duration(llm_completed_at_ms, turnCompletedAtMs);
            }

            public int getTurn() {
                return turn;
            }

            public long getRequest_started_at_ms() {
                return request_started_at_ms;
            }

            public @Nullable Long getLlm_completed_at_ms() {
                return llm_completed_at_ms;
            }

            public @Nullable Long getTurn_completed_at_ms() {
                return turn_completed_at_ms;
            }

            public @Nullable Long getLlm_duration_ms() {
                return llm_duration_ms;
            }

            public @Nullable Long getTurn_duration_ms() {
                return turn_duration_ms;
            }

            public @Nullable Long getTool_execution_duration_ms() {
                return tool_execution_duration_ms;
            }

            public int getInput_tokens() {
                return input_tokens;
            }

            public int getCached_input_tokens() {
                return cached_input_tokens;
            }

            public int getThinking_tokens() {
                return thinking_tokens;
            }

            public int getOutput_tokens() {
                return output_tokens;
            }

            public List<ToolCallDetail> getTool_calls() {
                return new ArrayList<>(tool_calls);
            }

            public List<PlanningEvent> getPlanning_events() {
                return new ArrayList<>(planning_events);
            }

            public List<SubAgentCall> getSub_agent_calls() {
                return new ArrayList<>(sub_agent_calls);
            }
        }
    }
}
