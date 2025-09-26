package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.util.AdaptiveExecutor;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.util.TokenAware;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jetbrains.annotations.Nullable;

/**
 * Simplified MergeAgent that delegates conflict detection, annotation and per-file planning to ConflictInspector,
 * ConflictAnnotator and MergePlanner.
 *
 * <p>The heavy lifting has been extracted; this class keeps the public construction and orchestration responsibilities
 * (gather conflicts, annotate, run per-file planners, run verification and publish commit summaries).
 */
public class MergeAgent {
    private static final Logger logger = LogManager.getLogger(MergeAgent.class);

    public enum MergeMode {
        MERGE,
        REBASE,
        REVERT,
        CHERRY_PICK
    }

    protected final IContextManager cm;
    protected final MergeConflict conflict;

    // Convenience fields derived from conflict
    protected final MergeMode mode;
    protected final String otherCommitId;
    protected final @Nullable String baseCommitId;
    protected final Set<FileConflict> conflicts;

    private final StreamingChatModel planningModel;
    private final StreamingChatModel codeModel;

    // Lightweight accumulators used during a run
    private final List<String> codeAgentFailures = new ArrayList<>();
    private final Map<ProjectFile, String> mergedTestSources = new ConcurrentHashMap<>();

    public MergeAgent(
            IContextManager cm,
            StreamingChatModel planningModel,
            StreamingChatModel codeModel,
            MergeConflict conflict) {
        this.cm = cm;
        this.planningModel = planningModel;
        this.codeModel = codeModel;
        this.conflict = conflict;

        this.mode = conflict.state();
        this.baseCommitId = conflict.baseCommitId();
        this.otherCommitId = conflict.otherCommitId();
        this.conflicts = conflict.files();
    }

    /** Create a MergeAgent by inspecting the on-disk repository state. */
    public static MergeAgent inferFromExternal(
            ContextManager cm, StreamingChatModel planningModel, StreamingChatModel codeModel) {
        var conflict = ConflictInspector.inspectFromProject(cm.getProject());
        logger.debug(conflict);
        return new MergeAgent(cm, planningModel, codeModel, conflict);
    }

    /**
     * High-level merge entry point. First annotates all conflicts, then resolves them file-by-file. Also publishes
     * commit explanations for the relevant ours/theirs commits discovered by blame.
     */
    public void execute() throws IOException, GitAPIException, InterruptedException {
        codeAgentFailures.clear();

        var repo = (GitRepo) cm.getProject().getRepo();
        validateOtherIsNotMergeCommitForNonMergeMode(repo, mode, otherCommitId);

        var conflictAnnotator = new ConflictAnnotator(repo, conflict);

        // First pass: annotate ALL files up front (single loop).
        var annotatedConflicts = new ArrayList<ConflictAnnotator.ConflictFileCommits>(conflicts.size());
        var unionOurCommits = new LinkedHashSet<String>();
        var unionTheirCommits = new LinkedHashSet<String>();

        for (var cf : conflicts) {
            if (Thread.interrupted()) throw new InterruptedException();

            if (!cf.isContentConflict()) {
                // FIXME: handle non-content conflicts (adds, deletes, renames) in a future enhancement
                continue;
            }

            var pf = requireNonNull(cf.ourFile());

            var annotated = conflictAnnotator.annotate(cf);

            // Write annotated contents to our working path
            pf.write(annotated.contents());

            annotatedConflicts.add(annotated);
            unionOurCommits.addAll(annotated.ourCommits());
            unionTheirCommits.addAll(annotated.theirCommits());
        }

        // Kick off background explanations for our/their relevant commits discovered via blame.
        Future<String> oursFuture = cm.submitBackgroundTask("Explain relevant OUR commits", () -> {
            try {
                return buildCommitExplanations("Our relevant commits", unionOurCommits);
            } catch (Exception e) {
                logger.warn("Asynchronous OUR commit explanations failed: {}", e.toString(), e);
                return "";
            }
        });
        Future<String> theirsFuture = cm.submitBackgroundTask("Explain relevant THEIR commits", () -> {
            try {
                return buildCommitExplanations("Their relevant commits", unionTheirCommits);
            } catch (Exception e) {
                logger.warn("Asynchronous THEIR commit explanations failed: {}", e.toString(), e);
                return "";
            }
        });

        // Partition test vs non-test for the MERGE phase only (annotation already done above).
        var testAnnotated = annotatedConflicts.stream()
                .filter(ac -> ContextManager.isTestFile(ac.file()))
                .sorted(Comparator.comparing(ac -> ac.file().toString()))
                .toList();
        var nonTestAnnotated = annotatedConflicts.stream()
                .filter(ac -> !ContextManager.isTestFile(ac.file()))
                .sorted(Comparator.comparing(ac -> ac.file().toString()))
                .toList();

        List<ProjectFile> allTestFiles = testAnnotated.stream()
                .map(ConflictAnnotator.ConflictFileCommits::file)
                .toList();

        // Merge test files first (in parallel) to seed relevance
        if (!testAnnotated.isEmpty()) {
            var service = cm.getService();
            ExecutorService testExecutor = AdaptiveExecutor.create(service, codeModel, testAnnotated.size());
            try {
                CompletionService<MergeOneFile.Outcome> completionService =
                        new ExecutorCompletionService<>(testExecutor);

                // Token-aware callable to merge a single test file
                for (var ac : testAnnotated) {
                    if (Thread.interrupted()) throw new InterruptedException();

                    interface TokenAwareCallable extends Callable<MergeOneFile.Outcome>, TokenAware {}

                    completionService.submit(new TokenAwareCallable() {
                        @Override
                        public int tokens() {
                            try {
                                return Messages.getApproximateTokens(ac.contents());
                            } catch (Exception e) {
                                logger.debug("Token estimation failed for {} – {}", ac.file(), e.toString());
                                return 0;
                            }
                        }

                        @Override
                        public MergeOneFile.Outcome call() throws Exception {
                            var planner = new MergeOneFile(
                                    cm,
                                    planningModel,
                                    codeModel,
                                    mode,
                                    baseCommitId,
                                    otherCommitId,
                                    Map.of(),
                                    allTestFiles,
                                    ac);
                            return planner.merge();
                        }
                    });
                }

                for (var cfc : testAnnotated) {
                    if (Thread.interrupted()) throw new InterruptedException();

                    var fut = completionService.take();
                    // logging context only; order does not strictly map
                    try {
                        var outcome = fut.get();
                        logger.info("Merged test file (parallel) possibly {} => {}", cfc.file(), outcome);
                    } catch (ExecutionException e) {
                        var cause = e.getCause() == null ? e : e.getCause();
                        logger.error("Error merging test file {}: {}", cfc.file(), cause.toString(), cause);
                    }

                    // Update merged test sources for files that no longer have conflict markers
                    var textOpt = cfc.file().read();
                    if (textOpt.isPresent() && !containsConflictMarkers(textOpt.get())) {
                        mergedTestSources.put(cfc.file(), textOpt.get());
                    } else {
                        logger.warn("Test file {} still contains conflict markers after merge attempt", cfc.file());
                    }
                }
            } finally {
                testExecutor.shutdownNow();
            }
        }

        // Then merge non-test files (in parallel), leveraging merged test sources
        if (!nonTestAnnotated.isEmpty()) {
            var service = cm.getService();
            ExecutorService nonTestExecutor = AdaptiveExecutor.create(service, codeModel, nonTestAnnotated.size());
            try {
                CompletionService<Map.Entry<ProjectFile, MergeOneFile.Outcome>> completionService =
                        new ExecutorCompletionService<>(nonTestExecutor);

                // Token-aware callable to merge a single non-test file
                for (var ac : nonTestAnnotated) {
                    if (Thread.interrupted()) throw new InterruptedException();

                    interface TokenAwareCallable
                            extends Callable<Map.Entry<ProjectFile, MergeOneFile.Outcome>>, TokenAware {}

                    completionService.submit(new TokenAwareCallable() {
                        @Override
                        public int tokens() {
                            try {
                                return Messages.getApproximateTokens(ac.contents());
                            } catch (Exception e) {
                                logger.debug("Token estimation failed for {} – {}", ac.file(), e.toString());
                                return 0;
                            }
                        }

                        @Override
                        public Map.Entry<ProjectFile, MergeOneFile.Outcome> call() throws Exception {
                            var planner = new MergeOneFile(
                                    cm,
                                    planningModel,
                                    codeModel,
                                    mode,
                                    baseCommitId,
                                    otherCommitId,
                                    mergedTestSources,
                                    allTestFiles,
                                    ac);
                            var outcome = planner.merge();
                            return Map.entry(ac.file(), outcome);
                        }
                    });
                }

                for (int i = 0; i < nonTestAnnotated.size(); i++) {
                    if (Thread.interrupted()) throw new InterruptedException();

                    var fut = completionService.take();
                    try {
                        var entry = fut.get();
                        var file = entry.getKey();
                        var outcome = entry.getValue();
                        logger.info("Merged source file (parallel) {} => {}", file, outcome);

                        if (outcome.status() == MergeOneFile.Status.UNRESOLVED) {
                            if (outcome.details() != null) {
                                codeAgentFailures.add(outcome.details());
                            } else {
                                codeAgentFailures.add("<unknown code-agent failure for " + file + ">");
                            }
                        }
                    } catch (ExecutionException e) {
                        var cause = e.getCause() == null ? e : e.getCause();
                        logger.error("Error merging source file in parallel: {}", cause.toString(), cause);
                    }
                }
            } finally {
                nonTestExecutor.shutdownNow();
            }
        }

        // Publish commit explanations (if available)
        try {
            var oursExpl = oursFuture.get();
            if (!oursExpl.isBlank()) {
                addTextToWorkspace("Relevant 'ours' change summaries", oursExpl);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            logger.warn("Failed to compute OUR commit explanations: {}", e.getMessage(), e);
        }

        try {
            var theirsExpl = theirsFuture.get();
            if (!theirsExpl.isBlank()) {
                addTextToWorkspace("Relevant 'theirs' change summaries", theirsExpl);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            logger.warn("Failed to compute THEIR commit explanations: {}", e.getMessage(), e);
        }

        // Run verification step if configured
        var buildFailureText = runVerificationIfConfigured();
        if (buildFailureText.isBlank() && codeAgentFailures.isEmpty()) {
            logger.info("Verification passed and no CodeAgent failures; merge completed successfully.");
            return;
        }

        // We tried auto-editing files that are mentioned i nthe build failure, the trouble is that you
        // can cause errors in lots of files by screwing up the API in one, and adding all of them
        // obscures rather than clarifies the actual problem. So don't do that.

        // Kick off Architect in the background to attempt to fix build failures and code-agent errors.
        var contextManager = (ContextManager) cm;
        var codeAgentText = codeAgentFailures.isEmpty() ? "" : String.join("\n\n", codeAgentFailures);

        // Publish build output to the workspace BuildFragment so Architect can reference it.
        // Architect instructions should not inline the full build output; it will be available
        // as a concise Build fragment in the workspace (keeps prompts compact).
        contextManager.updateBuildFragment(buildFailureText);

        var agentInstructions =
                """
                I attempted to merge changes from %s into our branch (mode: %s). I have added summaries
                of the changes involved to the Workspace.

                The per-file code agent reported these failures:
                ```
                %s
                ```

                The verification/build output has been added to the Workspace as a Build fragment. Please fix the build and tests, update code as necessary, and produce a clean build. Commit any changes.
                """
                        .formatted(otherCommitId, mode, codeAgentText);

        var agent = new ArchitectAgent(contextManager, planningModel, codeModel, agentInstructions);
        var result = agent.execute();
        if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
            contextManager.addToHistory(result, true);
        }
    }

    private static boolean containsConflictMarkers(String text) {
        return text.contains("<<<<<<<") || text.contains("=======") || text.contains(">>>>>>>");
    }

    private static void validateOtherIsNotMergeCommitForNonMergeMode(
            GitRepo repo, MergeMode mode, String otherCommitId) {
        if (mode == MergeMode.MERGE) return;
        try (var rw = new RevWalk(repo.getGit().getRepository())) {
            var oid = repo.getGit().getRepository().resolve(otherCommitId);
            if (oid == null) {
                logger.warn("Unable to resolve otherCommitId {}", otherCommitId);
                return;
            }
            var commit = rw.parseCommit(oid);
            if (commit.getParentCount() > 1) {
                throw new IllegalArgumentException(
                        "Non-merge modes (REBASE/REVERT/CHERRY_PICK) do not support a merge commit as 'other': "
                                + otherCommitId);
            }
        } catch (IOException e) {
            // Be permissive if we cannot verify; just log
            logger.warn("Could not verify whether {} is a merge commit: {}", otherCommitId, e.toString());
        }
    }

    /**
     * Build a markdown document explaining each commit in {@code commitIds} (single-commit explanations). Returns empty
     * string if {@code commitIds} is empty.
     */
    private String buildCommitExplanations(String title, Set<String> commitIds) {
        if (commitIds.isEmpty()) return "";
        var sections = new ArrayList<String>();
        for (var id : commitIds) {
            var shortId = ((GitRepo) cm.getProject().getRepo()).shortHash(id);
            String explanation;
            try {
                explanation = MergeOneFile.explainCommitCached(cm, id);
            } catch (Exception e) {
                logger.warn("explainCommit failed for {}: {}", id, e.toString());
                explanation = "Unable to explain commit " + id + ": " + e.getMessage();
            }
            sections.add("## " + shortId + "\n\n" + explanation);
        }
        return "# " + title + "\n\n" + String.join("\n\n", sections);
    }

    /** Run verification build if configured; returns empty string on success, otherwise failure text. */
    private String runVerificationIfConfigured() {
        try {
            var cmd = BuildAgent.determineVerificationCommandAsync((ContextManager) cm)
                    .join();
            if (cmd == null || cmd.isBlank()) return "";
            cm.getIo()
                    .llmOutput(
                            "\nRunning verification command: " + cmd,
                            dev.langchain4j.data.message.ChatMessageType.CUSTOM);
            cm.getIo().llmOutput("\n```bash\n", dev.langchain4j.data.message.ChatMessageType.CUSTOM);
            Environment.instance.runShellCommand(
                    cmd,
                    ((ContextManager) cm).getProject().getRoot(),
                    line -> cm.getIo().llmOutput(line + "\n", dev.langchain4j.data.message.ChatMessageType.CUSTOM),
                    Environment.UNLIMITED_TIMEOUT);
            return ""; // success
        } catch (Environment.SubprocessException e) {
            return e.getMessage() + "\n\n" + e.getOutput();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Verification command was interrupted.";
        }
    }

    /** Add a summary as a text fragment to the Workspace via the workspace tool. */
    private void addTextToWorkspace(String title, String text) {
        try {
            var mapper = new ObjectMapper();
            var args = mapper.writeValueAsString(Map.of("description", title, "content", text));
            var req = ToolExecutionRequest.builder()
                    .name("addTextToWorkspace")
                    .arguments(args)
                    .build();
            var tr = cm.getToolRegistry().executeTool(this, req);
            logger.debug("addTextToWorkspace: {} {} ", tr.status(), tr.resultText());
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize addTextToWorkspace args: {}", e.toString());
        } catch (Exception e) {
            logger.warn("Failed to add text to workspace: {}", e.toString());
        }
    }

    /**
     * baseCommitId may ay be null if no merge base can be determined (e.g. unrelated histories, shallow clone, root
     * commit with no parent, or cherry-pick/rebase target where first parent is undefined).
     */
    public record MergeConflict(
            MergeMode state,
            String ourCommitId,
            String otherCommitId,
            @Nullable String baseCommitId,
            Set<FileConflict> files) {}

    /**
     * Represents a single path's merge conflict.
     *
     * <p>Semantics: - A ProjectFile path may be present even when the corresponding content is null (e.g., ours deleted
     * vs. theirs modified, or rename/modify where one side has no blob staged). The path reflects either the resolved
     * historical path or the index path. - The base side may be entirely absent (add/add), or have content without a
     * resolvable path.
     */
    public record FileConflict(
            @Nullable ProjectFile ourFile,
            @Nullable String ourContent,
            @Nullable ProjectFile theirFile,
            @Nullable String theirContent,
            @Nullable ProjectFile baseFile,
            @Nullable String baseContent) {
        public FileConflict {
            // One-way invariants: if there is content, there must be a path.
            // The inverse is allowed (path with null content) for delete/modify, etc.
            assert ourContent == null || ourFile != null;
            assert theirContent == null || theirFile != null;
        }

        /**
         * True only when both sides carry text content to be diff3-merged. Delete/modify and similar cases will return
         * false.
         */
        public boolean isContentConflict() {
            return ourContent != null && theirContent != null;
        }
    }
}
