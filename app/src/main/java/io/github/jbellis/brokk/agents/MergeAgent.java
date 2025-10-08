package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.util.AdaptiveExecutor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jetbrains.annotations.Nullable;

/* Added imports for referenced helpers/agents. These are typically in the same package;
importing is harmless and makes intent explicit. */

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

    // NonTextType is defined at package scope (io.github.jbellis.brokk.agents.NonTextType);
    // use the top-level enum to avoid type conflicts with other classes in the package.

    protected final IContextManager cm;
    protected MergeConflict conflict;

    // Convenience fields derived from conflict
    protected final MergeMode mode;
    protected String otherCommitId;
    protected @Nullable String baseCommitId;
    protected Set<FileConflict> conflicts;

    private final StreamingChatModel planningModel;
    private final StreamingChatModel codeModel;
    private final ContextManager.TaskScope scope;
    private final String mergeInstructions;

    private static final String DEFAULT_MERGE_INSTRUCTIONS =
            "Resolve ALL conflicts with the minimal change that preserves the\n"
                    + "semantics of the changes made in both \"theirs\" and \"ours.\"";

    // Lightweight accumulators used during a run
    private final ConcurrentHashMap<ProjectFile, String> codeAgentFailures = new ConcurrentHashMap<>();

    public MergeAgent(
            IContextManager cm,
            StreamingChatModel planningModel,
            StreamingChatModel codeModel,
            MergeConflict conflict,
            ContextManager.TaskScope scope,
            @Nullable String mergeInstructions) {
        this.cm = cm;
        this.planningModel = planningModel;
        this.codeModel = codeModel;
        this.conflict = conflict;

        this.mode = conflict.state();
        this.baseCommitId = conflict.baseCommitId();
        this.otherCommitId = conflict.otherCommitId();
        this.conflicts = conflict.files();
        this.scope = scope;
        this.mergeInstructions = (mergeInstructions == null || mergeInstructions.isBlank())
                ? DEFAULT_MERGE_INSTRUCTIONS
                : mergeInstructions;
    }

    // Backwards-compatible constructor for callers that do not supply custom merge instructions (e.g., CLI).
    public MergeAgent(
            IContextManager cm,
            StreamingChatModel planningModel,
            StreamingChatModel codeModel,
            MergeConflict conflict,
            ContextManager.TaskScope scope) {
        this(cm, planningModel, codeModel, conflict, scope, null);
    }

    /**
     * High-level merge entry point. First annotates all conflicts, then resolves them file-by-file. Also publishes
     * commit explanations for the relevant ours/theirs commits discovered by blame.
     */
    public TaskResult execute() throws IOException, GitAPIException, InterruptedException {
        codeAgentFailures.clear();

        var repo = (GitRepo) cm.getProject().getRepo();
        validateOtherIsNotMergeCommitForNonMergeMode(repo, mode, otherCommitId);

        // Notify start of annotation
        cm.getIo()
                .showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Preparing %d conflicted files for AI merge...".formatted(conflicts.size()));

        // NEW: Heuristic non-text resolution phase (rename/delete/mode/dir collisions, etc.)
        if (scope.nonTextMode() != NonTextResolutionMode.OFF) {
            try {
                resolveNonTextConflicts(this.conflict, repo);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return interruptedResult("Merge cancelled by user.");
            }

            // IMPORTANT: re-inspect repo state; non-text ops may change the set of conflicts
            var refreshedOpt = ConflictInspector.inspectFromProject(cm.getProject());
            if (refreshedOpt.isEmpty()) {
                logger.warn(
                        "ConflictInspector.inspectFromProject returned empty Optional after non-text resolution; continuing with previous conflict snapshot.");
            } else {
                var refreshed = refreshedOpt.get();
                if (refreshed.files().isEmpty()) {
                    // nothing left to resolve; still run verification as usual
                    logger.info("All non-text conflicts resolved; no content conflicts remain.");
                    var buildFailureText = runVerificationIfConfigured();
                    if (buildFailureText.isBlank()) {
                        return new TaskResult(
                                cm,
                                "Merge",
                                List.of(new AiMessage("Non-text conflicts resolved; verification passed.")),
                                Set.of(),
                                new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS));
                    }
                    // fall through to ArchitectAgent handoff path already present below
                } else {
                    // Swap our conflict snapshot to the refreshed one
                    this.conflict = refreshed;
                    this.baseCommitId = this.conflict.baseCommitId();
                    this.otherCommitId = this.conflict.otherCommitId();
                    this.conflicts = this.conflict.files();
                }
            }
        }

        // First pass: annotate ALL files up front (parallel)
        var annotatedConflicts = ConcurrentHashMap.<ConflictAnnotator.ConflictFileCommits>newKeySet();
        var unionOurCommits = ConcurrentHashMap.<String>newKeySet();
        var unionTheirCommits = ConcurrentHashMap.<String>newKeySet();

        conflicts.parallelStream().forEach(cf -> {
            if (!cf.isContentConflict()) {
                // Non-content conflicts are handled separately by the non-text resolver above.
                return;
            }

            var conflictAnnotator = new ConflictAnnotator(repo, conflict);
            var pf = requireNonNull(cf.ourFile());

            var annotated = conflictAnnotator.annotate(cf);

            // Write annotated contents to our working path
            try {
                pf.write(annotated.contents());
            } catch (IOException e) {
                logger.error("Failed to write annotated contents for {}: {}", pf, e.toString(), e);
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

        if (Thread.currentThread().isInterrupted()) {
            return interruptedResult("Merge cancelled by user.");
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

        // Build a lookup from ProjectFile -> annotated conflict details
        var acByFile = annotatedConflicts.stream()
                .collect(Collectors.toMap(ConflictAnnotator.ConflictFileCommits::file, ac -> ac));

        // Prepare BlitzForge configuration and listener
        var instructionsText =
                "AI-assisted merge of conflicted files from %s (mode: %s)".formatted(otherCommitId, mode);
        var bfConfig = new BlitzForge.RunConfig(
                instructionsText,
                codeModel, // model used only for token-aware scheduling
                () -> "", // perFileContext
                () -> "", // sharedContext
                "", // contextFilter
                BlitzForge.ParallelOutputMode.CHANGED);

        var bfListener = cm.getIo().getBlitzForgeListener(() -> {});

        var blitz = new BlitzForge(cm, cm.getService(), bfConfig, bfListener);

        var allAnnotatedFiles = annotatedConflicts.stream()
                .map(ConflictAnnotator.ConflictFileCommits::file)
                .sorted(Comparator.comparing(ProjectFile::toString))
                .toList();

        var result = blitz.executeParallel(allAnnotatedFiles, file -> {
            var ac = requireNonNull(acByFile.get(file));

            IConsoleIO console = bfListener.getConsoleIO(file);

            var planner =
                    new MergeOneFile(cm, planningModel, codeModel, mode, baseCommitId, otherCommitId, ac, console);

            var outcome = planner.merge();

            boolean edited = false;
            var textOpt = file.read();
            if (textOpt.isPresent()) {
                var text = textOpt.get();
                edited = !containsConflictMarkers(text);
            }

            if (outcome.status() == MergeOneFile.Status.UNRESOLVED) {
                var detail = (outcome.details() != null)
                        ? outcome.details()
                        : "<unknown code-agent failure for " + file + ">";
                codeAgentFailures.put(file, detail);
                return new BlitzForge.FileResult(file, edited, detail, "");
            }

            return new BlitzForge.FileResult(file, edited, null, "");
        });

        if (result.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED) {
            return interruptedResult("Merge cancelled by user.");
        }

        // Publish commit explanations (if available)
        try {
            var oursExpl = oursFuture.get();
            if (!oursExpl.isBlank()) {
                addTextToWorkspace("Relevant 'ours' change summaries", oursExpl);
            }
        } catch (InterruptedException e) {
            return interruptedResult("Merge cancelled by user.");
        } catch (ExecutionException e) {
            logger.warn("Failed to compute OUR commit explanations: {}", e.getMessage(), e);
        }

        try {
            var theirsExpl = theirsFuture.get();
            if (!theirsExpl.isBlank()) {
                addTextToWorkspace("Relevant 'theirs' change summaries", theirsExpl);
            }
        } catch (InterruptedException e) {
            return interruptedResult("Merge cancelled by user.");
        } catch (ExecutionException e) {
            logger.warn("Failed to compute THEIR commit explanations: {}", e.getMessage(), e);
        }

        // Run verification step if configured
        var buildFailureText = runVerificationIfConfigured();
        if (Thread.currentThread().isInterrupted()) {
            return interruptedResult("Merge cancelled by user.");
        }
        if (buildFailureText.isBlank() && codeAgentFailures.isEmpty()) {
            logger.info("Verification passed and no CodeAgent failures; merge completed successfully.");
            var msg = "Merge completed successfully. Annotated %d conflicted files. Verification passed."
                    .formatted(annotatedConflicts.size());
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
        var codeAgentText = "";
        if (!codeAgentFailures.isEmpty()) {
            codeAgentText = "The Code Agent reported these failures:\n"
                    + codeAgentFailures.entrySet().stream()
                            .map(e -> e.getKey() + "\n```\n" + e.getValue() + "\n```")
                            .collect(Collectors.joining("\n\n"));
        }

        var agentInstructions =
                """
                        I attempted to merge changes from %s into our branch (mode: %s). My goal was:
                        %s

                        I have added summaries of the changes involved to the Workspace.

                        %s

                        The verification/build output has been added to the Workspace as a Build fragment. Please fix the build and tests, update code as necessary, and produce a clean build. Commit any changes.
                        """
                        .formatted(otherCommitId, mode, mergeInstructions, codeAgentText);

        var agent = new ArchitectAgent(contextManager, planningModel, codeModel, agentInstructions, scope);
        return agent.executeWithSearch(scope);
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
            return BuildAgent.runVerification(cm);
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

    /** Metadata describing non-textual aspects of a conflict detected from the git index and trees. */
    public record NonTextMetadata(
            NonTextType type,
            @Nullable String indexPath,
            @Nullable String ourPath,
            @Nullable String theirPath,
            boolean oursIsDirectory,
            boolean theirsIsDirectory,
            boolean oursBinary,
            boolean theirsBinary,
            boolean oursExecBit,
            boolean theirsExecBit) {}

    /**
     * baseCommitId may ay be null if no merge base can be determined (e.g. unrelated histories, shallow clone, root
     * commit with no parent, or cherry-pick/rebase target where first parent is undefined).
     */
    public record MergeConflict(
            MergeMode state,
            String ourCommitId,
            String otherCommitId,
            @Nullable String baseCommitId,
            Set<FileConflict> files,
            Map<FileConflict, NonTextMetadata> nonText) {

        // Backward-compatible constructor for tests and callers that don't supply non-text metadata
        public MergeConflict(
                MergeMode state,
                String ourCommitId,
                String otherCommitId,
                @Nullable String baseCommitId,
                Set<FileConflict> files) {
            this(state, ourCommitId, otherCommitId, baseCommitId, files, Map.of());
        }
    }

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

    /**
     * Resolve non-text conflicts (delete/modify, rename/modify, file<->dir, add/add-binary, mode bits) using the
     * NonTextHeuristicResolver. Groups related paths to avoid conflicting ops, applies ops, and emits a receipt.
     */
    private void resolveNonTextConflicts(MergeConflict mc, GitRepo repo) throws InterruptedException {
        // Collect candidates with metadata
        var metaMap = mc.nonText();
        var candidates = mc.files().stream()
                .filter(fc -> !fc.isContentConflict())
                .filter(metaMap::containsKey)
                .map(fc -> Map.entry(fc, metaMap.get(fc)))
                .toList();
        if (candidates.isEmpty()) return;

        var groups = groupNonText(candidates);

        // Parallelize by group; each group runs serially
        var service = cm.getService();
        var exec = AdaptiveExecutor.create(service, codeModel, groups.size());
        try {
            var cs = new ExecutorCompletionService<String>(exec);
            for (var g : groups) {
                cs.submit(() -> {
                    var plan = NonTextHeuristicResolver.plan(g.items());
                    try {
                        NonTextHeuristicResolver.apply(repo, cm.getProject().getRoot(), plan.ops());
                        // Build a short receipt for Workspace
                        var receipt = new StringBuilder();
                        receipt.append("Group:\n");
                        for (var it : g.items()) {
                            var m = it.getValue();
                            receipt.append("- type=")
                                    .append(m.type())
                                    .append(" index=")
                                    .append(nullToDash(m.indexPath()))
                                    .append(" ours=")
                                    .append(nullToDash(m.ourPath()))
                                    .append(" theirs=")
                                    .append(nullToDash(m.theirPath()))
                                    .append("\n");
                        }
                        receipt.append("\nPlan (").append(plan.confidence()).append("):\n");
                        for (var op : plan.ops()) {
                            receipt.append("  ").append(op).append("\n");
                        }
                        if (!plan.summary().isBlank()) {
                            receipt.append("\nSummary:\n")
                                    .append(plan.summary())
                                    .append("\n");
                        }
                        return receipt.toString();
                    } catch (Exception e) {
                        logger.warn("Non-text heuristic apply failed: {}", e.toString(), e);
                        return "Non-text heuristic apply FAILED for group: " + e.getMessage();
                    }
                });
            }

            var receipts = new ArrayList<String>(groups.size());
            for (int i = 0; i < groups.size(); i++) {
                if (Thread.interrupted()) throw new InterruptedException();
                var fut = cs.take();
                try {
                    receipts.add(fut.get());
                } catch (ExecutionException ee) {
                    var cause = ee.getCause() == null ? ee : ee.getCause();
                    logger.warn("Non-text group task failed: {}", cause.toString(), cause);
                    receipts.add("Non-text group task FAILED: " + cause.getMessage());
                }
            }

            // Publish a single Workspace note aggregating all receipts
            addTextToWorkspace("Non-text conflict decisions", String.join("\n\n---\n\n", receipts));
        } finally {
            exec.shutdownNow();
        }
    }

    private record Group(List<Map.Entry<FileConflict, NonTextMetadata>> items) {}

    private static List<Group> groupNonText(List<Map.Entry<FileConflict, NonTextMetadata>> candidates) {
        var grouped = NonTextGrouper.group(candidates);
        var out = new ArrayList<Group>(grouped.size());
        for (var g : grouped) {
            out.add(new Group(g));
        }
        return out;
    }

    private static String nullToDash(@Nullable String s) {
        return s == null ? "-" : s;
    }

    private Set<ProjectFile> allConflictFilesInWorkspace() {
        // Include all files participating from the conflict set
        return conflicts.stream()
                .flatMap(fc -> Stream.of(fc.ourFile(), fc.theirFile()).filter(f -> f != null))
                .collect(Collectors.toSet());
    }

    private TaskResult interruptedResult(String message) {
        return new TaskResult(
                cm,
                "Merge",
                List.of(new AiMessage(message)),
                allConflictFilesInWorkspace(),
                new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
    }
}
