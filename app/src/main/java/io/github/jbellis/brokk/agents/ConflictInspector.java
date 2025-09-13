package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jetbrains.annotations.Nullable;

/**
 * Helper responsible for inspecting on-disk git state to determine whether the repository is in a
 * merge/rebase/cherry-pick/revert conflict state and for producing the effective params.
 */
public final class ConflictInspector {

    private static final Logger logger = LogManager.getLogger(ConflictInspector.class);

    /**
     * Collects staged blobs and contents for a single conflicting index path and builds a ConflictingFile with per-side
     * historical paths resolved from the actual commit trees. This is resilient to renames: we locate the path of each
     * side by searching the side's tree for the staged blob id.
     */
    private static class ConflictingFileBuilder {
        private final String indexPath;
        private final IProject project;
        private @Nullable String ourContent;
        private @Nullable String theirContent;
        private @Nullable String baseContent;

        private @Nullable ObjectId baseBlob;
        private @Nullable ObjectId ourBlob;
        private @Nullable ObjectId theirBlob;

        ConflictingFileBuilder(IProject project, String indexPath) {
            this.project = project;
            this.indexPath = indexPath;
        }

        void setStage1(@Nullable ObjectId blob, @Nullable String content) {
            baseBlob = blob;
            baseContent = content;
            logger.debug(
                    "setStage1: indexPath={}, blob={}, contentLen={}",
                    indexPath,
                    blob == null ? "null" : blob.name(),
                    content == null ? 0 : content.length());
        }

        void setStage2(@Nullable ObjectId blob, String content) {
            ourBlob = blob;
            ourContent = content;
            logger.debug(
                    "setStage2: indexPath={}, blob={}, contentLen={}",
                    indexPath,
                    blob == null ? "null" : blob.name(),
                    content.length());
        }

        void setStage3(@Nullable ObjectId blob, String content) {
            theirBlob = blob;
            theirContent = content;
            logger.debug(
                    "setStage3: indexPath={}, blob={}, contentLen={}",
                    indexPath,
                    blob == null ? "null" : blob.name(),
                    content.length());
        }

        /**
         * Build a ConflictingFile by mapping the staged blobs to their historical paths in the provided commits. If a
         * mapping cannot be found, fall back to the index path.
         */
        MergeAgent.FileConflict build(
                @Nullable String baseCommitId,
                String ourCommitId,
                String otherCommitId,
                Set<String> stage0Paths,
                Set<String> unmergedPaths)
                throws GitRepo.GitRepoException {
            var repository = ((GitRepo) project.getRepo()).getGit().getRepository();

            logger.debug(
                    "build: indexPath={}, baseCommitId={}, ourCommitId={}, otherCommitId={}, hasBaseBlob={}, hasOurBlob={}, hasTheirBlob={}",
                    indexPath,
                    baseCommitId,
                    ourCommitId,
                    otherCommitId,
                    baseBlob != null,
                    ourBlob != null,
                    theirBlob != null);

            var ourPath =
                    ourBlob == null ? indexPath : findPathForBlobInCommit(repository, ourCommitId, ourBlob, indexPath);
            var theirPath = theirBlob == null
                    ? indexPath
                    : findPathForBlobInCommit(repository, otherCommitId, theirBlob, indexPath);

            @Nullable
            var basePath = (baseCommitId == null || baseBlob == null)
                    ? null
                    : findPathForBlobInCommit(repository, baseCommitId, baseBlob, indexPath);

            // Heuristic: in rename/modify vs modify, stage-2 may be missing for indexPath.
            // If our blob is missing and indexPath doesn't exist in our commit, try to use a single stage-0 candidate.
            if (ourBlob == null && !pathExistsInCommit(repository, ourCommitId, indexPath)) {
                var candidates = stage0Paths.stream()
                        .filter(p -> !unmergedPaths.contains(p))
                        .toList();
                if (candidates.size() == 1) {
                    var candidate = candidates.getFirst();
                    logger.debug(
                            "build: ourBlob missing; using stage-0 candidate '{}' as ourPath for indexPath '{}'",
                            candidate,
                            indexPath);
                    ourPath = candidate;
                } else {
                    logger.debug(
                            "build: ourBlob missing; no unique stage-0 candidate for indexPath '{}' (candidates={})",
                            indexPath,
                            candidates);
                }
            }

            // If we still don't have ourContent but the path exists in our commit, read it from the tree.
            if (ourContent == null && pathExistsInCommit(repository, ourCommitId, ourPath)) {
                try {
                    ourContent = readFileContentFromCommit(repository, ourCommitId, ourPath);
                    logger.debug(
                            "build: filled ourContent from {}: path={}, len={}",
                            ourCommitId,
                            ourPath,
                            ourContent.length());
                } catch (RuntimeException e) {
                    logger.debug(
                            "build: unable to read ourContent from {} at {}: {}", ourCommitId, ourPath, e.getMessage());
                }
            }

            logger.debug("build: resolved paths -> ours={}, base={}, theirs={}", ourPath, basePath, theirPath);

            // Ensure ProjectFile objects exist for each side using the resolved repo path.
            // Note: content may be null to represent deletes/adds, but tests expect a non-null ProjectFile
            // reflecting the index path (or resolved historical path).
            var ourFile = toProjectFile(ourPath);
            var theirFile = toProjectFile(theirPath);
            var baseFile = basePath == null ? null : toProjectFile(basePath);

            return new MergeAgent.FileConflict(ourFile, ourContent, theirFile, theirContent, baseFile, baseContent);
        }

        private ProjectFile toProjectFile(String repoPath) {
            // repoPath is relative to the repository root; create a ProjectFile using the project's root
            return new ProjectFile(project.getRoot(), repoPath);
        }
    }

    public static MergeAgent.MergeConflict inspectFromProject(IProject project) {
        try {
            return inspectFromProjectInternal(project);
        } catch (GitAPIException e) {
            // fatal
            throw new RuntimeException(e);
        }
    }

    /**
     * Inspect the repository state and build a Conflict snapshot consisting of the effective merge mode, the commit ids
     * for our/other/base, and the set of ConflictingFile entries. Unmerged index stages are interpreted as: stage 1 =
     * base, stage 2 = ours, stage 3 = theirs.
     */
    public static MergeAgent.MergeConflict inspectFromProjectInternal(IProject project) throws GitAPIException {
        var repo = (GitRepo) project.getRepo();
        var repository = repo.getGit().getRepository();
        var gitDir = repository.getDirectory().toPath();

        var ourCommitId = repo.resolve("HEAD").getName();

        var candidates = List.of(
                Map.entry("MERGE_HEAD", MergeAgent.MergeMode.MERGE),
                Map.entry("REBASE_HEAD", MergeAgent.MergeMode.REBASE),
                Map.entry("CHERRY_PICK_HEAD", MergeAgent.MergeMode.CHERRY_PICK),
                Map.entry("REVERT_HEAD", MergeAgent.MergeMode.REVERT));
        MergeAgent.MergeMode state = null;
        Path headFile = null;
        for (var entry : candidates) {
            var candidatePath = gitDir.resolve(entry.getKey());
            if (Files.exists(candidatePath)) {
                state = entry.getValue();
                headFile = candidatePath;
                break;
            }
        }
        if (state == null) {
            throw new IllegalStateException(
                    "Repository is not in a merge/rebase/cherry-pick/revert conflict state (no *_HEAD found)");
        }

        String originalOtherCommitId = readSingleHead(requireNonNull(headFile), state);

        String effectiveOtherCommitId = originalOtherCommitId;
        @Nullable String baseCommitId;

        switch (state) {
            case MERGE -> {
                try {
                    baseCommitId = repo.getMergeBase("HEAD", originalOtherCommitId);
                } catch (GitAPIException e) {
                    throw new RuntimeException("Failed to compute merge base", e);
                }
            }
            case REBASE, CHERRY_PICK -> baseCommitId = firstParentOf(repository, originalOtherCommitId);
            case REVERT -> {
                baseCommitId = originalOtherCommitId;
                @Nullable var parent = firstParentOf(repository, originalOtherCommitId);
                effectiveOtherCommitId = parent == null ? originalOtherCommitId : parent;
            }
            default -> throw new IllegalStateException("Unhandled merge state: " + state);
        }

        logger.debug(
                "inspectFromProject: state={}, ourCommitId={}, originalOtherCommitId={}, effectiveOtherCommitId={}, baseCommitId={}",
                state,
                ourCommitId,
                originalOtherCommitId,
                effectiveOtherCommitId,
                baseCommitId);

        var byIndexPath = new LinkedHashMap<String, ConflictingFileBuilder>();
        DirCache dirCache;
        try {
            dirCache = repository.readDirCache();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read DirCache", e);
        }

        var stage0Paths = new LinkedHashSet<String>();
        for (int i = 0; i < dirCache.getEntryCount(); i++) {
            var entry = dirCache.getEntry(i);
            var indexPath = entry.getPathString();
            if (entry.getStage() == 0) {
                stage0Paths.add(indexPath);
                continue;
            }
            logger.debug(
                    "DirCache unmerged entry: path={}, stage={}, objectId={}, fileMode={}",
                    indexPath,
                    entry.getStage(),
                    entry.getObjectId().name(),
                    entry.getFileMode());
            var builder = byIndexPath.computeIfAbsent(indexPath, p -> new ConflictingFileBuilder(project, p));

            String content;
            try {
                content = new String(repository.open(entry.getObjectId()).getBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException("Failed to read blob for " + indexPath + " at stage " + entry.getStage(), e);
            }

            switch (entry.getStage()) {
                case 1 -> builder.setStage1(entry.getObjectId(), content);
                case 2 -> builder.setStage2(entry.getObjectId(), content);
                case 3 -> builder.setStage3(entry.getObjectId(), content);
                default -> {
                    /* ignore unknown stage */
                }
            }
        }

        logger.debug("Conflicting index paths: {}", byIndexPath.keySet());
        for (var e : byIndexPath.entrySet()) {
            var b = e.getValue();
            logger.debug(
                    "IndexPath {} stages: base(stage1)={}, ours(stage2)={}, theirs(stage3)={}",
                    e.getKey(),
                    b.baseBlob != null,
                    b.ourBlob != null,
                    b.theirBlob != null);
        }

        var files = new LinkedHashSet<MergeAgent.FileConflict>();
        for (var b : byIndexPath.values()) {
            files.add(b.build(baseCommitId, ourCommitId, effectiveOtherCommitId, stage0Paths, byIndexPath.keySet()));
        }

        for (var f : files) {
            logger.debug(
                    "FileConflict: our={}, their={}, base={}, ourContentLen={}, theirContentLen={}, baseContentLen={}",
                    f.ourFile() == null ? "null" : f.ourFile().getRelPath(),
                    f.theirFile() == null ? "null" : f.theirFile().getRelPath(),
                    f.baseFile() == null ? "null" : f.baseFile().getRelPath(),
                    f.ourContent() == null ? 0 : f.ourContent().length(),
                    f.theirContent() == null ? 0 : f.theirContent().length(),
                    f.baseContent() == null ? 0 : f.baseContent().length());
        }

        return new MergeAgent.MergeConflict(
                state, ourCommitId, effectiveOtherCommitId, baseCommitId, Set.copyOf(files));
    }

    private static String readSingleHead(Path headPath, MergeAgent.MergeMode state) {
        try {
            List<String> heads = Files.readAllLines(headPath, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (heads.size() != 1) {
                throw new IllegalStateException(
                        "Expected exactly one " + headPath.getFileName() + "; found " + heads.size());
            }
            var head = heads.getFirst();
            logger.debug("readSingleHead: {} -> {}", headPath.getFileName(), head);
            return head;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + headPath.getFileName() + " for state " + state, e);
        }
    }

    /** Return the first parent commit id (hex) of the given commit, or null if none. */
    public static @Nullable String firstParentOf(Repository repository, String commitId) {
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectId oid = ObjectId.fromString(commitId);
            RevCommit commit = walk.parseCommit(oid);
            if (commit.getParentCount() > 0) {
                var parent = commit.getParent(0).getName();
                logger.debug("firstParentOf: commit={}, parent={}", commitId, parent);
                return parent;
            }
            logger.debug("firstParentOf: commit={}, parent=null", commitId);
            return null;
        } catch (IOException e) {
            throw new RuntimeException("Unable to resolve parent of " + commitId, e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid commit id: " + commitId, e);
        }
    }

    private static boolean pathExistsInCommit(Repository repository, String commitId, String path) {
        try (var walk = new RevWalk(repository)) {
            var oid = ObjectId.fromString(commitId);
            RevCommit commit = walk.parseCommit(oid);
            try (var tw = new TreeWalk(repository)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                while (tw.next()) {
                    if (tw.getPathString().equals(path)) {
                        return true;
                    }
                }
                return false;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to check path in commit: " + path + " @ " + commitId, e);
        }
    }

    /**
     * Locate the path of a blob within a commit's tree. Returns the path string or null if the blob does not appear in
     * that tree. FallbackIndexPath is only used for logging context.
     */
    private static String readFileContentFromCommit(Repository repository, String commitId, String path) {
        try (var walk = new RevWalk(repository)) {
            var oid = ObjectId.fromString(commitId);
            RevCommit commit = walk.parseCommit(oid);
            try (var tw = new TreeWalk(repository)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                while (tw.next()) {
                    if (tw.getPathString().equals(path)) {
                        var blobId = tw.getObjectId(0);
                        var bytes = repository.open(blobId).getBytes();
                        return new String(bytes, StandardCharsets.UTF_8);
                    }
                }
                throw new RuntimeException("Path not found in commit: " + path + " @ " + commitId);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + path + " @ " + commitId, e);
        }
    }

    private static String findPathForBlobInCommit(
            Repository repository, String commitId, ObjectId blobId, String fallbackIndexPath)
            throws GitRepo.GitRepoException {
        try (var walk = new RevWalk(repository)) {
            var oid = ObjectId.fromString(commitId);
            RevCommit commit = walk.parseCommit(oid);
            try (var tw = new TreeWalk(repository)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                while (tw.next()) {
                    if (tw.getObjectId(0).equals(blobId)) {
                        var resolved = tw.getPathString();
                        logger.debug("Resolved blob {} to path {} at {}", blobId.name(), resolved, commitId);
                        return resolved;
                    }
                }
            }
            logger.debug(
                    "Blob {} not found in commit {} (index path was {})", blobId.name(), commitId, fallbackIndexPath);
            throw new GitRepo.GitRepoException("Blob not found", new AssertionError());
        } catch (IOException e) {
            logger.warn("Failed to map blob {} in commit {}: {}", blobId.name(), commitId, e.getMessage());
            throw new GitRepo.GitRepoException(requireNonNull(e.getMessage()), e);
        }
    }
}
