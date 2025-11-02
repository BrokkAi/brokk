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
     * @param filesAdded      count of files added
     * @param skipped         whether the scan was skipped
     * @param filesAddedPaths project-relative paths of files added during context scan (empty if skipped)
     * @param metadata        response metadata including token usage (may be null)
     */
    void recordContextScan(
            int filesAdded, boolean skipped, Set<String> filesAddedPaths, @Nullable Llm.ResponseMetadata metadata);

    void startTurn();

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
     * End the current turn and compute files removed.
     *
     * @param filesBeforeTurn workspace file set at turn start
     * @param filesAfterTurn workspace file set at turn end
     */
    void endTurn(Set<String> filesBeforeTurn, Set<String> filesAfterTurn);

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
     * Record metrics for the current turn's LLM call.
     * Called immediately after the LLM request completes.
     *
     * @param timeMs time spent in the LLM call in milliseconds
     * @param inputTokens number of input tokens
     * @param cachedInputTokens number of cached input tokens
     * @param thinkingTokens number of thinking/reasoning tokens
     * @param outputTokens number of output tokens
     */
    void recordLlmCall(long timeMs, int inputTokens, int cachedInputTokens, int thinkingTokens, int outputTokens);

    /**
     * Serialize metrics along with the basic result fields into JSON.
     *
     * @param query the original query
     * @param turns number of turns (AI messages)
     * @param success whether the search succeeded
     */
    String toJson(String query, int turns, boolean success);

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

    // Nested implementations moved from separate files

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
        public void startTurn() {}

        @Override
        public void recordToolCall(String toolName) {}

        @Override
        public void recordFilesAdded(int count) {}

        @Override
        public void recordFilesAddedPaths(Set<String> paths) {}

        @Override
        public void endTurn(Set<String> filesBeforeTurn, Set<String> filesAfterTurn) {}

        @Override
        public void recordOutcome(TaskResult.StopReason reason, int workspaceSize) {}

        @Override
        public void recordFinalWorkspaceFiles(Set<String> finalFiles) {}

        @Override
        public void recordFinalWorkspaceFragments(List<FragmentInfo> fragmentDescriptions) {}

        @Override
        public void recordLlmCall(
                long timeMs, int inputTokens, int cachedInputTokens, int thinkingTokens, int outputTokens) {}

        @Override
        public String toJson(String query, int turns, boolean success) {
            // Return empty object as requested in PR review
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

        // Context scan metrics
        private int contextScanFilesAdded = 0;
        private boolean contextScanSkipped = false;
        private Set<String> contextScanFilesAddedPaths = new HashSet<>();
        private @Nullable Llm.ResponseMetadata contextScanMetadata = null;

        // Per-turn metrics
        private int turnCounter = 0;
        private final List<TurnMetrics> turns = new ArrayList<>();
        private @Nullable TurnMetrics currentTurn = null;

        // Failure classification
        private @Nullable String failureType = null;
        private @Nullable String stopReason = null;
        private int finalWorkspaceSize = 0;

        // Final workspace files snapshot (project-relative paths)
        private @Nullable Set<String> finalWorkspaceFiles = null;

        // Final workspace fragments information
        private @Nullable List<FragmentInfo> finalWorkspaceFragments = null;

        @Override
        public synchronized void recordContextScan(
                int filesAdded, boolean skipped, Set<String> filesAddedPaths, @Nullable Llm.ResponseMetadata metadata) {
            this.contextScanFilesAdded = filesAdded;
            this.contextScanSkipped = skipped;
            this.contextScanFilesAddedPaths = new HashSet<>(filesAddedPaths);
            this.contextScanMetadata = metadata;
        }

        @Override
        public synchronized void startTurn() {
            if (currentTurn != null) {
                turns.add(currentTurn);
            }
            currentTurn = new TurnMetrics(++turnCounter);
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
                long timeMs, int inputTokens, int cachedInputTokens, int thinkingTokens, int outputTokens) {
            if (currentTurn != null) {
                currentTurn.setTimeMs(timeMs);
                currentTurn.setTokenUsage(inputTokens, cachedInputTokens, thinkingTokens, outputTokens);
            }
        }

        @Override
        public synchronized void endTurn(Set<String> filesBeforeTurn, Set<String> filesAfterTurn) {
            if (currentTurn != null) {
                // Compute files removed during this turn
                Set<String> removed = new HashSet<>(filesBeforeTurn);
                removed.removeAll(filesAfterTurn);
                currentTurn.addRemovedFilePaths(removed);

                turns.add(currentTurn);
                currentTurn = null;
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

        /** Get the turn history with files added per turn. Used by BrokkCli to determine the last file added. */
        public synchronized List<TurnMetrics> getTurns() {
            return new ArrayList<>(turns);
        }

        /** Generate enhanced JSON output with metrics. Maintains backward compatibility. */
        @Override
        public synchronized String toJson(String query, int turns, boolean success) {
            // Compute elapsed_ms as sum of all turn times (LLM call times)
            long elapsedMs =
                    this.turns.stream().mapToLong(TurnMetrics::getTime_ms).sum();

            // Build found_files from context scan + all turn additions
            logger.debug(
                    "Building found_files: contextScanFilesAddedPaths size={}, paths={}",
                    contextScanFilesAddedPaths.size(),
                    contextScanFilesAddedPaths);
            Set<String> allFoundFiles = new HashSet<>(contextScanFilesAddedPaths);
            for (TurnMetrics turn : this.turns) {
                logger.debug(
                        "Turn {}: adding {} files: {}",
                        turn.getTurn(),
                        turn.getFiles_added_paths().size(),
                        turn.getFiles_added_paths());
                allFoundFiles.addAll(turn.getFiles_added_paths());
            }
            List<String> foundFiles = allFoundFiles.stream().sorted().toList();
            logger.debug("Final found_files size={}, files={}", foundFiles.size(), foundFiles);

            var contextScan = new ContextScanInfo(
                    contextScanFilesAdded,
                    contextScanSkipped,
                    new ArrayList<>(contextScanFilesAddedPaths.stream().sorted().toList()),
                    contextScanMetadata);

            // Convert final workspace files to sorted list
            List<String> finalWorkspaceFilesList = finalWorkspaceFiles != null
                    ? finalWorkspaceFiles.stream().sorted().toList()
                    : null;

            var result = new SearchResult(
                    query,
                    foundFiles,
                    turns,
                    elapsedMs,
                    success,
                    contextScan,
                    new ArrayList<>(this.turns),
                    failureType,
                    stopReason,
                    finalWorkspaceSize,
                    finalWorkspaceFilesList,
                    finalWorkspaceFragments != null ? new ArrayList<>(finalWorkspaceFragments) : null);

            try {
                return OBJECT_MAPPER.writeValueAsString(result);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize search result", e);
            }
        }

        /** Complete search result with all metrics. */
        public record SearchResult(
                String query,
                List<String> found_files,
                int turns,
                long elapsed_ms,
                boolean success,
                ContextScanInfo context_scan,
                List<TurnMetrics> turns_detail,
                @Nullable String failure_type,
                @Nullable String stop_reason,
                int final_workspace_size,
                @Nullable List<String> final_workspace_files,
                @Nullable List<FragmentInfo> final_workspace_fragments) {}

        /** Context scan metrics. */
        public record ContextScanInfo(
                int files_added,
                boolean skipped,
                List<String> files_added_paths,
                @Nullable Llm.ResponseMetadata token_usage) {}

        /** Metrics for a single search turn. */
        public static class TurnMetrics {
            private final int turn;
            private final List<String> tool_calls = new ArrayList<>();
            private int files_added = 0;
            private final Set<String> files_added_paths = new HashSet<>();
            private final Set<String> files_removed_paths = new HashSet<>();
            private long time_ms = 0;
            private int input_tokens = 0;
            private int cached_input_tokens = 0;
            private int thinking_tokens = 0;
            private int output_tokens = 0;

            public TurnMetrics(int turnNumber) {
                this.turn = turnNumber;
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

            public void setTimeMs(long timeMs) {
                this.time_ms = timeMs;
            }

            public void setTokenUsage(int inputTokens, int cachedInputTokens, int thinkingTokens, int outputTokens) {
                this.input_tokens = inputTokens;
                this.cached_input_tokens = cachedInputTokens;
                this.thinking_tokens = thinkingTokens;
                this.output_tokens = outputTokens;
            }

            // Jackson getters (required for serialization)
            public int getTurn() {
                return turn;
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
