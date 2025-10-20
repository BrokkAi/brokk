package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeAlgorithm;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jetbrains.annotations.Nullable;

/**
 * Builds a diff3-style annotated conflict for a single file. Responsibility: given base/ours/theirs content and commit
 * ids, produce the annotated conflict text, and collect the set of commit ids observed in the Ours/Theirs blame for the
 * lines we emit.
 *
 * <p>This class is stateful: each instance maintains an internal counter so conflict markers are numbered sequentially
 * across multiple files/annotations.
 */
public final class ConflictAnnotator {

    private static final Logger logger = LogManager.getLogger(ConflictAnnotator.class);

    static boolean containsConflictMarkers(String text) {
        return text.contains("BRK_CONFLICT_BEGIN_") || text.contains("BRK_CONFLICT_END_");
    }

    /**
     * Structured result for an annotated conflict. - file: the file being annotated - contents: the annotated text with
     * BRK_* markers - theirCommits / ourCommits: ids seen in blame during construction (base commits omitted) -
     * conflictBlockCount: number of BRK_CONFLICT_BEGIN_* blocks actually emitted (excludes auto-resolved import
     * conflicts)
     */
    public record ConflictFileCommits(
            ProjectFile file,
            String contents,
            Set<String> theirCommits,
            Set<String> ourCommits,
            int conflictLineCount) {}

    private final AtomicInteger conflictCounter = new AtomicInteger();
    private final GitRepo repo;
    private final MergeAgent.MergeConflict mergeConflict;

    public ConflictAnnotator(GitRepo repo, MergeAgent.MergeConflict mergeConflict) {
        this.repo = repo;
        this.mergeConflict = mergeConflict;
        logger.debug(
                "ConflictAnnotator initialized for merge mode: {}, baseCommit: {}, otherCommit: {}",
                mergeConflict.state(),
                mergeConflict.baseCommitId(),
                mergeConflict.otherCommitId());
    }

    /** Annotate a conflicting file using blame information to add context and collect commit ids. */
    public ConflictFileCommits annotate(MergeAgent.FileConflict cf) {
        logger.debug(
                "Annotating file conflict for ourFile: {}, theirFile: {}",
                cf.ourFile() != null ? cf.ourFile().getRelPath() : "N/A",
                cf.theirFile() != null ? cf.theirFile().getRelPath() : "N/A");

        var repo = this.repo;
        var state = mergeConflict.state();
        @Nullable var baseCommitId = mergeConflict.baseCommitId();
        var otherCommitId = mergeConflict.otherCommitId();

        var git = repo.getGit();
        var repository = git.getRepository();

        // Determine which commit to show in the ours header:
        // - normal: HEAD
        // - rebase: the "onto" commit
        String oursRefForHeader = "HEAD";
        if (state == MergeAgent.MergeMode.REBASE) {
            @Nullable var onto = readRebaseOntoCommit(repository);
            if (onto != null && !onto.isBlank()) {
                oursRefForHeader = onto;
            }
        }

        // Resolve ours commit id (needed when oursRefForHeader is "HEAD")
        String oursCommitId;
        try {
            if ("HEAD".equals(oursRefForHeader)) {
                oursCommitId = repo.resolveToCommit("HEAD").getName();
            } else {
                oursCommitId = oursRefForHeader;
            }
        } catch (GitAPIException e) {
            logger.warn("Failed to resolve oursRefForHeader '{}': {}", oursRefForHeader, e.getMessage());
            oursCommitId = oursRefForHeader; // best-effort fallback
        }
        logger.debug("Resolved oursCommitId: {} (from {})", oursCommitId, oursRefForHeader);

        // Short IDs for annotations
        String oursShort = repo.shortHash(oursCommitId);
        String baseShort = baseCommitId == null ? "" : repo.shortHash(baseCommitId);
        String theirsShort = repo.shortHash(otherCommitId);
        logger.debug("Short SHAs: ours={}, base={}, theirs={}", oursShort, baseShort, theirsShort);

        // Per-line blame lookups (may be null if unavailable). Use the historically-correct file paths.
        var ourBlame = getBlame(git, repository, oursCommitId, cf.ourFile());
        var baseBlame = cf.baseFile() == null ? null : getBlame(git, repository, baseCommitId, cf.baseFile());
        var theirBlame = getBlame(git, repository, otherCommitId, cf.theirFile());
        logger.debug(
                "Blame results: ourBlame={}, baseBlame={}, theirBlame={}",
                ourBlame != null,
                baseBlame != null,
                theirBlame != null);

        // Build RawText sequences and run JGit merge algorithm (diff3-like)
        var baseText = cf.baseContent() == null ? "" : cf.baseContent();
        var baseRaw = new RawText(baseText.getBytes(StandardCharsets.UTF_8));
        var ourRaw = new RawText(requireNonNull(cf.ourContent()).getBytes(StandardCharsets.UTF_8));
        var theirRaw = new RawText(requireNonNull(cf.theirContent()).getBytes(StandardCharsets.UTF_8));
        logger.debug(
                "JGit merge input: base len={}, our len={}, their len={}",
                baseRaw.size(),
                ourRaw.size(),
                theirRaw.size());

        var ma = new MergeAlgorithm();
        MergeResult<RawText> result = ma.merge(RawTextComparator.DEFAULT, baseRaw, ourRaw, theirRaw);
        // The MergeResult is iterable but does not have a size() method directly.
        // Collect chunks into a list for easier indexed traversal and then get the size.
        var chunkList = new ArrayList<MergeChunk>();
        for (MergeChunk c : result) {
            chunkList.add(c);
        }
        logger.debug("JGit merge algorithm completed. Result has {} chunks.", chunkList.size());

        var sequences = result.getSequences(); // 0=base,1=ours,2=theirs

        var outLines = new ArrayList<String>();
        var ourCommitIds = new LinkedHashSet<String>();
        var theirCommitIds = new LinkedHashSet<String>();
        int actualConflictLineCount = 0;

        for (int i = 0; i < chunkList.size(); i++) {
            var chunk = chunkList.get(i);
            if (chunk.getConflictState() == MergeChunk.ConflictState.NO_CONFLICT) {
                // Non-conflicting: take content from the sequence indicated by the chunk
                var seq = sequences.get(chunk.getSequenceIndex());
                for (int ln = chunk.getBegin(); ln < chunk.getEnd(); ln++) {
                    outLines.add(seq.getString(ln));
                }
            } else {
                // Conflict block: gather contiguous conflict chunks and assemble per-sequence content
                record LineInfo(int lineNum, String content) {}
                int j = i;
                var ourLines = new ArrayList<LineInfo>();
                var baseLines = new ArrayList<LineInfo>();
                var theirLines = new ArrayList<LineInfo>();

                while (j < chunkList.size()
                        && chunkList.get(j).getConflictState() != MergeChunk.ConflictState.NO_CONFLICT) {
                    var c = chunkList.get(j);
                    var seq = sequences.get(c.getSequenceIndex());
                    for (int ln = c.getBegin(); ln < c.getEnd(); ln++) {
                        var line = seq.getString(ln);
                        switch (c.getSequenceIndex()) {
                            case 0 -> baseLines.add(new LineInfo(ln, line));
                            case 1 -> ourLines.add(new LineInfo(ln, line));
                            case 2 -> theirLines.add(new LineInfo(ln, line));
                            default -> {} // should not happen
                        }
                    }
                    j++;
                }
                // advance outer loop past the grouped conflict chunks
                i = j - 1;

                // Before emitting a BRK_* block, check for import-only conflict that we can auto-resolve (Java only)
                if ("java".equalsIgnoreCase(requireNonNull(cf.ourFile()).extension())) {
                    var baseContentLines =
                            baseLines.stream().map(LineInfo::content).toList();
                    var ourContentLines =
                            ourLines.stream().map(LineInfo::content).toList();
                    var theirContentLines =
                            theirLines.stream().map(LineInfo::content).toList();
                    if (ImportConflictResolver.isImportConflict(
                            baseContentLines.isEmpty() ? null : baseContentLines, ourContentLines, theirContentLines)) {
                        var merged = ImportConflictResolver.resolveImportConflict(ourContentLines, theirContentLines);
                        outLines.addAll(merged);
                        logger.debug(
                                "Auto-resolved import conflict for file: {}",
                                cf.ourFile().getRelPath());
                        continue;
                    }
                }
                // Emit BRK_* annotated conflict (custom format) with a sequential number
                int conflictNum = conflictCounter.incrementAndGet();
                int linesInConflict = ourLines.size() + baseLines.size() + theirLines.size();
                actualConflictLineCount += linesInConflict;
                logger.debug(
                        "Emitting BRK_CONFLICT_{} for file {}. Our lines: {}, Base lines: {}, Their lines: {}. Total conflict lines: {}",
                        conflictNum,
                        cf.ourFile().getRelPath(),
                        ourLines.size(),
                        baseLines.size(),
                        theirLines.size(),
                        linesInConflict);
                outLines.add("BRK_CONFLICT_BEGIN_" + conflictNum);
                // Header with a commit-ish for our side (best-effort)
                outLines.add("BRK_OUR_VERSION " + oursShort);
                for (var info : ourLines) {
                    var commit = ourBlame == null ? null : ourBlame.getSourceCommit(info.lineNum);
                    var shortSha = commit == null ? oursShort : repo.shortHash(commit.getName());
                    if (commit != null) {
                        ourCommitIds.add(commit.getName());
                    }
                    outLines.add(shortSha + " " + info.content);
                }
                outLines.add("BRK_BASE_VERSION " + baseShort);
                for (var info : baseLines) {
                    var commit = baseBlame == null ? null : baseBlame.getSourceCommit(info.lineNum);
                    var shortSha = commit == null ? baseShort : repo.shortHash(commit.getName());
                    outLines.add(shortSha + " " + info.content);
                }
                outLines.add("BRK_THEIR_VERSION " + theirsShort);
                for (var info : theirLines) {
                    var commit = theirBlame == null ? null : theirBlame.getSourceCommit(info.lineNum);
                    var shortSha = commit == null ? theirsShort : repo.shortHash(commit.getName());
                    if (commit != null) {
                        theirCommitIds.add(commit.getName());
                    }
                    outLines.add(shortSha + " " + info.content);
                }
                outLines.add("BRK_CONFLICT_END_" + conflictNum);
            }
        }
        logger.debug(
                "Finished annotating file {}. Total conflict blocks: {}, Our commits: {}, Their commits: {}",
                requireNonNull(cf.ourFile()).getRelPath(),
                conflictCounter.get(),
                ourCommitIds.size(),
                theirCommitIds.size());

        // Annotate against ourFile as the working path representative
        return new ConflictFileCommits(
                requireNonNull(cf.ourFile()),
                String.join("\n", outLines),
                Set.copyOf(theirCommitIds),
                Set.copyOf(ourCommitIds),
                actualConflictLineCount);
    }

    private static @Nullable BlameResult getBlame(
            Git git, Repository repository, @Nullable String commitId, @Nullable ProjectFile file) {
        if (commitId == null || file == null) {
            logger.debug(
                    "Skipping blame: commitId or file is null (commitId={}, file={})",
                    commitId,
                    file != null ? file.getRelPath() : "null");
            return null;
        }
        logger.debug("Attempting to get blame for file {} at commit {}", file.getRelPath(), commitId);
        try (var revWalk = new RevWalk(repository)) {
            var oid = repository.resolve(commitId);
            if (oid == null) {
                logger.warn(
                        "Failed to resolve commit {}. Blame will be null for file {}.", commitId, file.getRelPath());
                return null;
            }
            var commit = revWalk.parseCommit(oid);
            var blameResult = git.blame()
                    .setStartCommit(commit)
                    .setFilePath(file.getRelPath().toString())
                    .setFollowFileRenames(true)
                    .call();
            logger.debug(
                    "Blame computed for file {} at commit {}. Result: {}",
                    file.getRelPath(),
                    commitId,
                    blameResult != null);
            return blameResult;
        } catch (GitAPIException | IOException e) {
            logger.warn("Failed to compute blame for {} at {}: {}", file.getRelPath(), commitId, e.getMessage());
            return null;
        }
    }

    /** Reads the commit id of the rebase "onto" from .git/rebase-merge/onto or .git/rebase-apply/onto. */
    private static @Nullable String readRebaseOntoCommit(Repository repository) {
        logger.debug("Attempting to read rebase 'onto' commit from repository.");
        Path gitDir = repository.getDirectory().toPath();
        Path rebaseMergeOnto = gitDir.resolve("rebase-merge").resolve("onto");
        Path rebaseApplyOnto = gitDir.resolve("rebase-apply").resolve("onto");
        for (var p : List.of(rebaseMergeOnto, rebaseApplyOnto)) {
            try {
                if (Files.exists(p)) {
                    var line = Files.readString(p, StandardCharsets.UTF_8).trim();
                    if (!line.isEmpty()) {
                        logger.debug("Found rebase 'onto' commit '{}' in {}.", line, p);
                        return line;
                    }
                }
            } catch (IOException ignored) {
                logger.debug("Could not read rebase 'onto' file {}: {}", p, ignored.getMessage());
                // ignore and try next
            }
        }
        logger.debug("No rebase 'onto' commit found.");
        return null;
    }
}
