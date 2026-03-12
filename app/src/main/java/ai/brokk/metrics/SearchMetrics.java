package ai.brokk.metrics;

import ai.brokk.Llm;
import ai.brokk.TaskResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Interface for collecting SearchAgent metrics.
 *
 * Implementations must be thread-safe if concurrently accessed.
 *
 * This interface lives in a neutral package so agent code can depend on it
 * without importing CLI-specific classes or implementations.
 */
public interface SearchMetrics {

    /**
     * Record context scan metrics.
     *
     * @param filesAdded count of files added
     * @param skipped whether the scan was skipped
     * @param filesAddedPaths project-relative paths of files added during context scan (empty if skipped)
     * @param metadata response metadata including token usage (may be null)
     */
    void recordContextScan(
            int filesAdded, boolean skipped, Set<String> filesAddedPaths, @Nullable Llm.ResponseMetadata metadata);

    default void startTurn() {
        startTurn(System.currentTimeMillis());
    }

    void startTurn(long requestStartedAtMs);

    void recordToolCall(String toolName);

    void recordFilesAdded(int count);

    /**
     * Record the concrete project-relative file paths that were added to the Workspace during the current turn.
     * The set should include only repo-backed files (project-relative paths). Implementations may ignore
     * virtual/summary fragments or include them as empty/placeholder entries.
     *
     * Example: Set.of("src/main/java/com/acme/Foo.java", "src/test/java/com/acme/FooTest.java")
     */
    void recordFilesAddedPaths(Set<String> paths);

    /**
     * End the current turn and compute files removed using the current wall-clock time as the completion time.
     *
     * @param filesBeforeTurn workspace file set at turn start
     * @param filesAfterTurn workspace file set at turn end
     */
    default void endTurn(Set<String> filesBeforeTurn, Set<String> filesAfterTurn) {
        endTurn(System.currentTimeMillis(), filesBeforeTurn, filesAfterTurn);
    }

    /**
     * End the current turn and compute files removed.
     *
     * @param turnCompletedAtMs turn completion timestamp in epoch milliseconds
     * @param filesBeforeTurn workspace file set at turn start
     * @param filesAfterTurn workspace file set at turn end
     */
    void endTurn(long turnCompletedAtMs, Set<String> filesBeforeTurn, Set<String> filesAfterTurn);

    void recordOutcome(TaskResult.StopReason reason, int workspaceSize);

    /**
     * Record the final snapshot of repo-backed files present in the Workspace at task completion.
     * This allows external harnesses to cross-check and select a primary file from the actual final Workspace.
     */
    void recordFinalWorkspaceFiles(Set<String> finalFiles);

    /**
     * Record information about all fragments in the final workspace.
     *
     * @param fragmentDescriptions list of fragment descriptions (type, id, description, files)
     */
    void recordFinalWorkspaceFragments(List<FragmentInfo> fragmentDescriptions);

    /**
     * Record metrics for the current turn's LLM call using the current wall-clock time as the completion time.
     * Called immediately after the LLM request completes.
     *
     * @param timeMs time spent in the LLM call in milliseconds
     * @param inputTokens number of input tokens
     * @param cachedInputTokens number of cached input tokens
     * @param thinkingTokens number of thinking/reasoning tokens
     * @param outputTokens number of output tokens
     */
    default void recordLlmCall(
            long timeMs, int inputTokens, int cachedInputTokens, int thinkingTokens, int outputTokens) {
        recordLlmCall(System.currentTimeMillis(), timeMs, inputTokens, cachedInputTokens, thinkingTokens, outputTokens);
    }

    /**
     * Record metrics for the current turn's LLM call.
     * Called immediately after the LLM request completes.
     *
     * @param llmCompletedAtMs timestamp when the LLM response finished, in epoch milliseconds
     * @param timeMs time spent in the LLM call in milliseconds
     * @param inputTokens number of input tokens
     * @param cachedInputTokens number of cached input tokens
     * @param thinkingTokens number of thinking/reasoning tokens
     * @param outputTokens number of output tokens
     */
    void recordLlmCall(
            long llmCompletedAtMs,
            long timeMs,
            int inputTokens,
            int cachedInputTokens,
            int thinkingTokens,
            int outputTokens);

    /**
     * Serialize metrics along with the basic result fields into JSON.
     *
     * @param query the original query
     * @param success whether the search succeeded
     */
    String toJson(String query, boolean success);

    /**
     * Information about a fragment in the workspace.
     */
    record FragmentInfo(String type, String id, String description, List<String> files) {}

    /**
     * Convenience factory for a no-op metrics instance.
     */
    static SearchMetrics noOp() {
        return NoOp.INSTANCE;
    }

    /**
     * Convenience factory for a tracking metrics instance.
     */
    static SearchMetrics tracking() {
        return new Tracking();
    }

    /**
     * Lightweight no-op SearchMetrics singleton.
     */
    enum NoOp implements SearchMetrics {
        INSTANCE;

        @Override
        public void recordContextScan(
                int filesAdded,
                boolean skipped,
                Set<String> filesAddedPaths,
                @Nullable Llm.ResponseMetadata metadata) {}

        @Override
        public void startTurn(long requestStartedAtMs) {}

        @Override
        public void recordToolCall(String toolName) {}

        @Override
        public void recordFilesAdded(int count) {}

        @Override
        public void recordFilesAddedPaths(Set<String> paths) {}

        @Override
        public void endTurn(long turnCompletedAtMs, Set<String> filesBeforeTurn, Set<String> filesAfterTurn) {}

        @Override
        public void recordOutcome(TaskResult.StopReason reason, int workspaceSize) {}

        @Override
        public void recordFinalWorkspaceFiles(Set<String> finalFiles) {}

        @Override
        public void recordFinalWorkspaceFragments(List<FragmentInfo> fragmentDescriptions) {}

        @Override
        public void recordLlmCall(
                long llmCompletedAtMs,
                long timeMs,
                int inputTokens,
                int cachedInputTokens,
                int thinkingTokens,
                int outputTokens) {}

        @Override
        public String toJson(String query, boolean success) {
            return "{}";
        }
    }

    /**
     * Full featured metrics implementation for SearchAgent.
     * Methods are synchronized to be safe if accessed from multiple threads.
     */
    class Tracking implements SearchMetrics {
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
        private static final Logger logger = LogManager.getLogger(Tracking.class);

        private final long taskStartedAtMs;
        private long completedAtMsHint;

        private int contextScanFilesAdded = 0;
        private boolean contextScanSkipped = false;
        private Set<String> contextScanFilesAddedPaths = new HashSet<>();
        private @Nullable Llm.ResponseMetadata contextScanMetadata = null;

        private int turnCounter = 0;
        private final List<TurnMetrics> turns = new ArrayList<>();
        private @Nullable TurnMetrics currentTurn = null;

        private @Nullable String failureType = null;
        private @Nullable String stopReason = null;
        private int finalWorkspaceSize = 0;
        private @Nullable Set<String> finalWorkspaceFiles = null;
        private @Nullable List<FragmentInfo> finalWorkspaceFragments = null;

        Tracking() {
            this(System.currentTimeMillis());
        }

        Tracking(long taskStartedAtMs) {
            this.taskStartedAtMs = taskStartedAtMs;
            this.completedAtMsHint = taskStartedAtMs;
        }

        @Override
        public synchronized void recordContextScan(
                int filesAdded, boolean skipped, Set<String> filesAddedPaths, @Nullable Llm.ResponseMetadata metadata) {
            this.contextScanFilesAdded = filesAdded;
            this.contextScanSkipped = skipped;
            this.contextScanFilesAddedPaths = new HashSet<>(filesAddedPaths);
            this.contextScanMetadata = metadata;
            if (metadata != null) {
                completedAtMsHint = Math.max(completedAtMsHint, taskStartedAtMs + Math.max(0L, metadata.elapsedMs()));
            }
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
        public synchronized void recordToolCall(String toolName) {
            if (currentTurn != null) {
                currentTurn.addToolCall(toolName);
            }
        }

        @Override
        public synchronized void recordFilesAdded(int count) {
            if (currentTurn != null) {
                currentTurn.addFiles(count);
            }
        }

        @Override
        public synchronized void recordFilesAddedPaths(Set<String> paths) {
            if (currentTurn != null && !paths.isEmpty()) {
                currentTurn.addFilePaths(paths);
            }
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
                completedAtMsHint = Math.max(completedAtMsHint, llmCompletedAtMs);
            }
        }

        @Override
        public synchronized void endTurn(
                long turnCompletedAtMs, Set<String> filesBeforeTurn, Set<String> filesAfterTurn) {
            if (currentTurn != null) {
                Set<String> removed = new HashSet<>(filesBeforeTurn);
                removed.removeAll(filesAfterTurn);
                currentTurn.addRemovedFilePaths(removed);
                currentTurn.completeTurn(turnCompletedAtMs);
                turns.add(currentTurn);
                currentTurn = null;
                completedAtMsHint = Math.max(completedAtMsHint, turnCompletedAtMs);
            }
        }

        @Override
        public synchronized void recordOutcome(TaskResult.StopReason reason, int workspaceSize) {
            this.stopReason = reason.toString();
            this.finalWorkspaceSize = workspaceSize;
            this.failureType = switch (reason) {
                case SUCCESS -> null;
                default -> reason.toString().toLowerCase(Locale.ROOT);
            };
        }

        @Override
        public synchronized void recordFinalWorkspaceFiles(Set<String> finalFiles) {
            this.finalWorkspaceFiles = new HashSet<>(finalFiles);
        }

        @Override
        public synchronized void recordFinalWorkspaceFragments(List<FragmentInfo> fragmentDescriptions) {
            this.finalWorkspaceFragments = new ArrayList<>(fragmentDescriptions);
        }

        public synchronized List<TurnMetrics> getTurns() {
            return new ArrayList<>(turns);
        }

        @Override
        public synchronized String toJson(String query, boolean success) {
            finalizeCurrentTurnIfNeeded();

            long elapsedMs = 0;
            for (var turn : turns) {
                elapsedMs += turn.getTime_ms();
            }

            Set<String> allFoundFiles = new HashSet<>(contextScanFilesAddedPaths);
            for (var turn : turns) {
                allFoundFiles.addAll(turn.getFiles_added_paths());
            }
            List<String> foundFiles = allFoundFiles.stream().sorted().toList();
            logger.debug("Final found_files size={}, files={}", foundFiles.size(), foundFiles);

            var contextScan = new ContextScanInfo(
                    contextScanFilesAdded,
                    contextScanSkipped,
                    new ArrayList<>(contextScanFilesAddedPaths.stream().sorted().toList()),
                    contextScanMetadata);

            List<String> finalWorkspaceFilesList = finalWorkspaceFiles != null
                    ? finalWorkspaceFiles.stream().sorted().toList()
                    : null;

            long completedAtMs = completedAtMsHint;
            long wallElapsedMs = Math.max(0L, completedAtMs - taskStartedAtMs);

            var result = new SearchResult(
                    query,
                    foundFiles,
                    elapsedMs,
                    success,
                    contextScan,
                    new ArrayList<>(turns),
                    turns.size(),
                    failureType,
                    stopReason,
                    finalWorkspaceSize,
                    finalWorkspaceFilesList,
                    finalWorkspaceFragments != null ? new ArrayList<>(finalWorkspaceFragments) : null,
                    taskStartedAtMs,
                    completedAtMs,
                    wallElapsedMs);

            try {
                return OBJECT_MAPPER.writeValueAsString(result);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize search result", e);
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

        public record SearchResult(
                String query,
                List<String> found_files,
                long elapsed_ms,
                boolean success,
                ContextScanInfo context_scan,
                List<TurnMetrics> turns_detail,
                int turns,
                @Nullable String failure_type,
                @Nullable String stop_reason,
                int final_workspace_size,
                @Nullable List<String> final_workspace_files,
                @Nullable List<FragmentInfo> final_workspace_fragments,
                long started_at_ms,
                long completed_at_ms,
                long wall_elapsed_ms) {}

        public record ContextScanInfo(
                int files_added,
                boolean skipped,
                List<String> files_added_paths,
                @Nullable Llm.ResponseMetadata token_usage) {}

        public static class TurnMetrics {
            private final int turn;
            private final long request_started_at_ms;
            private final List<String> tool_calls = new ArrayList<>();
            private int files_added = 0;
            private final Set<String> files_added_paths = new HashSet<>();
            private final Set<String> files_removed_paths = new HashSet<>();
            private long time_ms = 0;
            private @Nullable Long llm_duration_ms = null;
            private @Nullable Long request_completed_at_ms = null;
            private @Nullable Long llm_completed_at_ms = null;
            private @Nullable Long turn_completed_at_ms = null;
            private @Nullable Long turn_duration_ms = null;
            private @Nullable Long tool_execution_duration_ms = null;
            private int input_tokens = 0;
            private int cached_input_tokens = 0;
            private int thinking_tokens = 0;
            private int output_tokens = 0;

            public TurnMetrics(int turnNumber, long requestStartedAtMs) {
                this.turn = turnNumber;
                this.request_started_at_ms = requestStartedAtMs;
                this.request_completed_at_ms = requestStartedAtMs;
            }

            public void addToolCall(String toolName) {
                tool_calls.add(toolName);
            }

            public void addFiles(int count) {
                files_added += count;
            }

            public void addFilePaths(Set<String> paths) {
                files_added_paths.addAll(paths);
            }

            public void addRemovedFilePaths(Set<String> paths) {
                files_removed_paths.addAll(paths);
            }

            public void recordLlmCall(
                    long llmCompletedAtMs,
                    long timeMs,
                    int inputTokens,
                    int cachedInputTokens,
                    int thinkingTokens,
                    int outputTokens) {
                this.time_ms = timeMs;
                this.llm_duration_ms = timeMs;
                this.llm_completed_at_ms = llmCompletedAtMs;
                this.request_completed_at_ms = llmCompletedAtMs;
                this.input_tokens = inputTokens;
                this.cached_input_tokens = cachedInputTokens;
                this.thinking_tokens = thinkingTokens;
                this.output_tokens = outputTokens;
            }

            public void completeTurn(long turnCompletedAtMs) {
                this.turn_completed_at_ms = turnCompletedAtMs;
                this.turn_duration_ms = Math.max(0L, turnCompletedAtMs - request_started_at_ms);
                this.tool_execution_duration_ms =
                        llm_completed_at_ms == null ? null : Math.max(0L, turnCompletedAtMs - llm_completed_at_ms);
            }

            public int getTurn() {
                return turn;
            }

            public long getRequest_started_at_ms() {
                return request_started_at_ms;
            }

            public @Nullable Long getRequest_completed_at_ms() {
                return request_completed_at_ms;
            }

            public @Nullable Long getLlm_completed_at_ms() {
                return llm_completed_at_ms;
            }

            public @Nullable Long getTurn_completed_at_ms() {
                return turn_completed_at_ms;
            }

            public List<String> getTool_calls() {
                return tool_calls;
            }

            public int getFiles_added() {
                return files_added;
            }

            public Set<String> getFiles_added_paths() {
                return files_added_paths;
            }

            public Set<String> getFiles_removed_paths() {
                return files_removed_paths;
            }

            public long getTime_ms() {
                return time_ms;
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
        }
    }
}
