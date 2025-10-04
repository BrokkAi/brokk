package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.util.AdaptiveExecutor;
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
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jetbrains.annotations.Nullable;
import javax.swing.JOptionPane;

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
        SQUASH,
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
    private final ContextManager.TaskScope scope;

    // Lightweight accumulators used during a run
    private final List<String> codeAgentFailures = new ArrayList<>();
    private final Map<ProjectFile, String> mergedTestSources = new ConcurrentHashMap<>();

    public MergeAgent(
            IContextManager cm,
            StreamingChatModel planningModel,
            StreamingChatModel codeModel,
            MergeConflict conflict,
            ContextManager.TaskScope scope) {
        this.cm = cm;
        this.planningModel = planningModel;
        this.codeModel = codeModel;
        this.conflict = conflict;

        this.mode = conflict.state();
        this.baseCommitId = conflict.baseCommitId();
        this.otherCommitId = conflict.otherCommitId();
        this.conflicts = conflict.files();
        this.scope = scope;
    }

    /**
     * High-level merge entry point. First annotates all conflicts, then resolves them file-by-file. Also publishes
     * commit explanations for the relevant ours/theirs commits discovered by blame.
     */
    public TaskResult execute() throws IOException, GitAPIException, InterruptedException {
        // FIXME handled InterruptedException, return TaskResult
        codeAgentFailures.clear();

        var repo = (GitRepo) cm.getProject().getRepo();
        validateOtherIsNotMergeCommitForNonMergeMode(repo, mode, otherCommitId);

        // Notify start of annotation
        cm.getIo()
                .systemNotify(
                        "Preparing %d conflicted files for AI merge..."
                                .formatted(conflicts.size()),
                        "Auto Merge",
                        JOptionPane.INFORMATION_MESSAGE);

        // First pass: annotate ALL files up front (parallel)
        var annotatedConflicts = ConcurrentHashMap.<ConflictAnnotator.ConflictFileCommits>newKeySet();
        var unionOurCommits = ConcurrentHashMap.<String>newKeySet();
        var unionTheirCommits = ConcurrentHashMap.<String>newKeySet();

        conflicts.parallelStream().forEach(cf -> {
            if (!cf.isContentConflict()) {
                // FIXME: handle non-content conflicts (adds, deletes, renames) in a future enhancement
                return;
            }

            var conflictAnnotator = new ConflictAnnotator(repo, conflict);
            var pf = requireNonNull(cf.ourFile());

            var annotated = conflictAnnotator.annotate(cf);

            // Write annotated contents to our working path
            try {
                pf.write(annotated.contents());
            } catch (IOException e) {
                logger.error("Failed to write annotated contents for {}: {}", pf, e.toString(), e)
                return;
            }

            annotatedConflicts.add(annotated);
            unionOurCommits.addAll(annotated.ourCommits());
            unionTheirCommits.addAll(annotated.theirCommits());
        });

        // Compute changed files set for reporting
        var changedFiles = annotatedConflicts.stream()
                .map(ConflictAnnotator.ConflictFileCommits::file)
                .collect(Collectors.toSet());

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

        // Merge all files (tests and non-tests) in a single BlitzForge run.
        // Build a lookup from ProjectFile -> annotated conflict details
        var acByFile = annotatedConflicts.stream()
                .collect(Collectors.toMap(ConflictAnnotator.ConflictFileCommits::file, ac -> ac));

        // Prepare BlitzForge configuration and listener
        var instructionsText =
                "AI-assisted merge of conflicted files from %s (mode: %s)".formatted(otherCommitId, mode);
        var bfConfig = new BlitzForge.RunConfig(
                instructionsText,
                codeModel,              // model used only for token-aware scheduling
                true,                   // includeWorkspace
                null,                   // relatedK
                null,                   // perFileCommandTemplate
                "",                     // contextFilter
                BlitzForge.ParallelOutputMode.CHANGED,
                false,                  // buildFirst
                "",                     // postProcessingInstructions
                BlitzForge.Action.MERGE);

        var bfListener = cm.getIo().getBlitzForgeListener(bfConfig, () -> {});

        var blitz = new BlitzForge(cm, cm.getService(), bfConfig, bfListener);

        var allAnnotatedFiles = annotatedConflicts.stream()
                .map(ConflictAnnotator.ConflictFileCommits::file)
                .sorted(Comparator.comparing(ProjectFile::toString))
                .toList();

        blitz.executeParallel(allAnnotatedFiles, file -> {
            var ac = acByFile.get(file);
            if (ac == null) {
                var err = "Internal error: missing annotated conflict for " + file;
                synchronized (codeAgentFailures) {
                    codeAgentFailures.add(err);
                }
                return new BlitzForge.FileResult(file, false, err, "");
            }

            boolean isTest = ContextManager.isTestFile(file);
            var sourcesForPlanner = isTest ? Map.<ProjectFile, String>of() : mergedTestSources;

            try {
                var planner = new MergeOneFile(
                        cm,
                        planningModel,
                        codeModel,
                        mode,
                        baseCommitId,
                        otherCommitId,
                        sourcesForPlanner,
                        allTestFiles,
                        ac);

                var outcome = planner.merge();

                // Read back to determine edited status and populate mergedTestSources for successfully merged tests
                boolean edited = false;
                try {
                    var textOpt = file.read();
                    if (textOpt.isPresent()) {
                        var text = textOpt.get();
                        edited = !containsConflictMarkers(text);
                        if (isTest && edited) {
                            mergedTestSources.put(file, text);
                        } else if (isTest && !edited) {
                            logger.warn("Test file {} still contains conflict markers after merge attempt", file);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Post-merge read failed for {}: {}", file, e.toString());
                }

                if (outcome.status() == MergeOneFile.Status.UNRESOLVED) {
                    var detail = (outcome.details() != null)
                            ? outcome.details()
                            : "<unknown code-agent failure for " + file + ">";
                    synchronized (codeAgentFailures) {
                        codeAgentFailures.add(detail);
                    }
                    return new BlitzForge.FileResult(file, edited, detail, "");
                }

                return new BlitzForge.FileResult(file, edited, null, "");
            } catch (Exception e) {
                var err = "Execution error: " + e.getMessage();
                synchronized (codeAgentFailures) {
                    codeAgentFailures.add(err);
                }
                logger.error("Error merging file {}: {}", file, e.toString(), e);
                return new BlitzForge.FileResult(file, false, err, "");
            }
        });

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
            var msg =
                    "Merge completed successfully. Annotated %d conflicted files (%d tests, %d sources). Verification passed."
                            .formatted(annotatedConflicts.size(), testAnnotated.size(), nonTestAnnotated.size());
            return new TaskResult(
                    cm,
                    "Merge",
                    List.of(new AiMessage(msg)),
                    changedFiles,
                    new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS));
        }

        // We tried auto-editing files that are mentioned in the build failure, the trouble is that you
        // can cause errors in lots of files by screwing up the API in one, and adding all of them
        // obscures rather than clarifies the actual problem. So don't do that.

        // Kick off Architect in the background to attempt to fix build failures and code-agent errors.
        var contextManager = (ContextManager) cm;
        var codeAgentText = codeAgentFailures.isEmpty() ? "" : String.join("\n\n", codeAgentFailures);

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

        var agent = new ArchitectAgent(contextManager, planningModel, codeModel, agentInstructions, scope);
        return agent.execute();
    }

    private static boolean containsConflictMarkers(String text) {
        return text.contains("<<<<<<<") || text.contains("=======") || text.contains(">>>>>>>");
    }

    private static void validateOtherIsNotMergeCommitForNonMergeMode(
            GitRepo repo, MergeMode mode, String otherCommitId) {
        if (mode == MergeMode.MERGE || mode == MergeMode.SQUASH) return;
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
            return BuildAgent.runVerification((ContextManager) cm);
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
