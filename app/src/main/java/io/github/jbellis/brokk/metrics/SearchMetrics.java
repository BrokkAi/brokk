package io.github.jbellis.brokk.metrics;

import io.github.jbellis.brokk.TaskResult;
import java.util.Set;

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
     * @param timeMs time taken in milliseconds
     * @param skipped whether the scan was skipped
     * @param filesAddedPaths project-relative paths of files added during context scan (empty if skipped)
     */
    void recordContextScan(int filesAdded, long timeMs, boolean skipped, Set<String> filesAddedPaths);

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

    void recordFailure(TaskResult.StopReason reason, int workspaceSize);

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
    void recordFinalWorkspaceFragments(java.util.List<FragmentInfo> fragmentDescriptions);

    /**
     * Serialize metrics along with the basic result fields into JSON.
     *
     * @param query the original query
     * @param turns number of turns (AI messages)
     * @param elapsedMs elapsed time in ms
     * @param success whether the search succeeded
     */
    String toJson(String query, int turns, long elapsedMs, boolean success);

    /**
     * Information about a fragment in the workspace.
     */
    record FragmentInfo(String type, String id, String description, java.util.List<String> files) {}

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
        public void recordContextScan(int filesAdded, long timeMs, boolean skipped, Set<String> filesAddedPaths) {}

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
        public void recordFailure(io.github.jbellis.brokk.TaskResult.StopReason reason, int workspaceSize) {}

        @Override
        public void recordFinalWorkspaceFiles(Set<String> finalFiles) {}

        @Override
        public void recordFinalWorkspaceFragments(java.util.List<FragmentInfo> fragmentDescriptions) {}

        @Override
        public String toJson(String query, int turns, long elapsedMs, boolean success) {
            // Return empty object as requested in PR review
            return "{}";
        }
    }

    /**
     * Full featured metrics implementation for SearchAgent.
     * Methods are synchronized to be safe if accessed from multiple threads.
     */
    class Tracking implements SearchMetrics {
        private static final org.apache.logging.log4j.Logger logger =
                org.apache.logging.log4j.LogManager.getLogger(Tracking.class);

        // Context scan metrics
        private int contextScanFilesAdded = 0;
        private long contextScanTimeMs = 0;
        private boolean contextScanSkipped = false;
        private Set<String> contextScanFilesAddedPaths = new java.util.HashSet<>();

        // Per-turn metrics
        private int turnCounter = 0;
        private final java.util.List<TurnMetrics> turns = new java.util.ArrayList<>();
        private @org.jetbrains.annotations.Nullable TurnMetrics currentTurn = null;
        private long turnStartTimeMs = 0;

        // Failure classification
        private @org.jetbrains.annotations.Nullable String failureType = null;
        private @org.jetbrains.annotations.Nullable String stopReason = null;
        private int finalWorkspaceSize = 0;

        // Final workspace files snapshot (project-relative paths)
        private @org.jetbrains.annotations.Nullable Set<String> finalWorkspaceFiles = null;

        // Final workspace fragments information
        private @org.jetbrains.annotations.Nullable java.util.List<FragmentInfo> finalWorkspaceFragments = null;

        // LLM history directory path
        private @org.jetbrains.annotations.Nullable String llmHistoryPath = null;

        @Override
        public synchronized void recordContextScan(
                int filesAdded, long timeMs, boolean skipped, Set<String> filesAddedPaths) {
            this.contextScanFilesAdded = filesAdded;
            this.contextScanTimeMs = timeMs;
            this.contextScanSkipped = skipped;
            this.contextScanFilesAddedPaths = new java.util.HashSet<>(filesAddedPaths);
        }

        @Override
        public synchronized void startTurn() {
            if (currentTurn != null) {
                turns.add(currentTurn);
            }
            currentTurn = new TurnMetrics(++turnCounter);
            turnStartTimeMs = System.currentTimeMillis();
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
        public synchronized void endTurn(Set<String> filesBeforeTurn, Set<String> filesAfterTurn) {
            if (currentTurn != null) {
                // Compute files removed during this turn
                Set<String> removed = new java.util.HashSet<>(filesBeforeTurn);
                removed.removeAll(filesAfterTurn);
                currentTurn.addRemovedFilePaths(removed);

                long turnTimeMs = System.currentTimeMillis() - turnStartTimeMs;
                currentTurn.setTimeMs(turnTimeMs);

                turns.add(currentTurn);
                currentTurn = null;
            }
        }

        @Override
        public synchronized void recordFailure(
                io.github.jbellis.brokk.TaskResult.StopReason reason, int workspaceSize) {
            this.stopReason = reason.toString();
            this.finalWorkspaceSize = workspaceSize;

            // Classify failure type
            this.failureType = switch (reason) {
                case SUCCESS -> null;
                case INTERRUPTED -> "interrupted";
                case LLM_ERROR -> "llm_error";
                case PARSE_ERROR -> "parse_error";
                case APPLY_ERROR -> "apply_error";
                case BUILD_ERROR -> "build_error";
                case LINT_ERROR -> "lint_error";
                case READ_ONLY_EDIT -> "read_only_edit";
                case IO_ERROR -> "io_error";
                case SEARCH_INVALID_ANSWER -> "search_invalid_answer";
                case LLM_ABORTED -> "llm_aborted";
                case TOOL_ERROR -> "tool_error";
            };
        }

        @Override
        public synchronized void recordFinalWorkspaceFiles(Set<String> finalFiles) {
            this.finalWorkspaceFiles = new java.util.HashSet<>(finalFiles);
        }

        @Override
        public synchronized void recordFinalWorkspaceFragments(java.util.List<FragmentInfo> fragmentDescriptions) {
            this.finalWorkspaceFragments = new java.util.ArrayList<>(fragmentDescriptions);
        }

        /** Get the final workspace files that were recorded. Used by BrokkCli to infer the found file. */
        public synchronized @org.jetbrains.annotations.Nullable Set<String> getFinalWorkspaceFiles() {
            return finalWorkspaceFiles;
        }

        /** Get the turn history with files added per turn. Used by BrokkCli to determine the last file added. */
        public synchronized java.util.List<TurnMetrics> getTurns() {
            return new java.util.ArrayList<>(turns);
        }

        /** Generate enhanced JSON output with metrics. Maintains backward compatibility. */
        @Override
        public synchronized String toJson(String query, int turns, long elapsedMs, boolean success) {
            // Build found_files from context scan + all turn additions
            logger.info(
                    "Building found_files: contextScanFilesAddedPaths size={}, paths={}",
                    contextScanFilesAddedPaths.size(),
                    contextScanFilesAddedPaths);
            Set<String> allFoundFiles = new java.util.HashSet<>(contextScanFilesAddedPaths);
            for (TurnMetrics turn : this.turns) {
                logger.info(
                        "Turn {}: adding {} files: {}",
                        turn.getTurn(),
                        turn.getFiles_added_paths().size(),
                        turn.getFiles_added_paths());
                allFoundFiles.addAll(turn.getFiles_added_paths());
            }
            java.util.List<String> foundFiles = allFoundFiles.stream().sorted().toList();
            logger.info("Final found_files size={}, files={}", foundFiles.size(), foundFiles);

            var contextScan = new ContextScanInfo(
                    contextScanFilesAdded,
                    contextScanTimeMs,
                    contextScanSkipped,
                    new java.util.ArrayList<>(
                            contextScanFilesAddedPaths.stream().sorted().toList()));
            var result = new SearchResult(
                    query,
                    foundFiles,
                    turns,
                    elapsedMs,
                    success,
                    contextScan,
                    new java.util.ArrayList<>(this.turns),
                    failureType,
                    stopReason,
                    finalWorkspaceSize,
                    finalWorkspaceFragments != null ? new java.util.ArrayList<>(finalWorkspaceFragments) : null,
                    llmHistoryPath);

            try {
                return io.github.jbellis.brokk.AbstractProject.objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize search result", e);
            }
        }

        /** Complete search result with all metrics. */
        public record SearchResult(
                String query,
                java.util.List<String> found_files,
                int turns,
                long elapsed_ms,
                boolean success,
                ContextScanInfo context_scan,
                java.util.List<TurnMetrics> turns_detail,
                @org.jetbrains.annotations.Nullable String failure_type,
                @org.jetbrains.annotations.Nullable String stop_reason,
                int final_workspace_size,
                @org.jetbrains.annotations.Nullable java.util.List<FragmentInfo> final_workspace_fragments,
                @org.jetbrains.annotations.Nullable String llm_history_path) {}

        /** Context scan metrics. */
        public record ContextScanInfo(
                int files_added, long scan_time_ms, boolean skipped, java.util.List<String> files_added_paths) {}

        /** Metrics for a single search turn. */
        public static class TurnMetrics {
            private final int turn;
            private final java.util.List<String> tool_calls = new java.util.ArrayList<>();
            private int files_added = 0;
            private final Set<String> files_added_paths = new java.util.HashSet<>();
            private final Set<String> files_removed_paths = new java.util.HashSet<>();
            private long time_ms = 0;

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

            // Jackson getters (required for serialization)
            public int getTurn() {
                return turn;
            }

            public java.util.List<String> getTool_calls() {
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
        }
    }
}
