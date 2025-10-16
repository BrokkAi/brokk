package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.FileMode;
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

    /** Result holder pairing the text conflict and derived non-text metadata. */
    private static record BuiltConflict(MergeAgent.FileConflict fileConflict, MergeAgent.NonTextMetadata metadata) {}

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

        private @Nullable FileMode stage2Mode;
        private @Nullable FileMode stage3Mode;

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

        void setStage2(@Nullable ObjectId blob, String content, @Nullable FileMode mode) {
            ourBlob = blob;
            ourContent = content;
            stage2Mode = mode;
            logger.debug(
                    "setStage2: indexPath={}, blob={}, mode={}, contentLen={}",
                    indexPath,
                    blob == null ? "null" : blob.name(),
                    mode == null ? "null" : mode.getBits(),
                    content.length());
        }

        void setStage3(@Nullable ObjectId blob, String content, @Nullable FileMode mode) {
            theirBlob = blob;
            theirContent = content;
            stage3Mode = mode;
            logger.debug(
                    "setStage3: indexPath={}, blob={}, mode={}, contentLen={}",
                    indexPath,
                    blob == null ? "null" : blob.name(),
                    mode == null ? "null" : mode.getBits(),
                    content.length());
        }

        /**
         * Build a ConflictingFile by mapping the staged blobs to their historical paths in the provided commits. If a
         * mapping cannot be found, fall back to the index path.
         */
        BuiltConflict build(
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

            // Directory vs file presence at indexPath in each side
            boolean oursIsDir = hasPathPrefixInCommit(repository, ourCommitId, indexPath);
            boolean theirsIsDir = hasPathPrefixInCommit(repository, otherCommitId, indexPath);
            boolean oursHasFile = pathExistsInCommit(repository, ourCommitId, indexPath);
            boolean theirsHasFile = pathExistsInCommit(repository, otherCommitId, indexPath);

            // Exec bit via index file modes
            boolean oursExec = stage2Mode != null && FileMode.EXECUTABLE_FILE.equals(stage2Mode);
            boolean theirsExec = stage3Mode != null && FileMode.EXECUTABLE_FILE.equals(stage3Mode);

            // Submodule via gitlink filemode
            boolean oursGitlink = stage2Mode != null && FileMode.GITLINK.equals(stage2Mode);
            boolean theirsGitlink = stage3Mode != null && FileMode.GITLINK.equals(stage3Mode);

            // Binary detection
            boolean oursBinary = ourContent != null && isBinary(ourContent);
            boolean theirsBinary = theirContent != null && isBinary(theirContent);

            // Heuristic classification
            NonTextType type = NonTextType.NONE;

            if (oursGitlink || theirsGitlink) {
                type = NonTextType.SUBMODULE_CONFLICT;
            } else if ((oursIsDir && theirsHasFile) || (theirsIsDir && oursHasFile)) {
                type = NonTextType.FILE_DIRECTORY;
            } else {
                boolean ourRenamed = ourBlob != null && ourPath != null && !ourPath.equals(indexPath);
                boolean theirRenamed = theirBlob != null && theirPath != null && !theirPath.equals(indexPath);

                if (ourRenamed && theirRenamed && !ourPath.equals(theirPath)) {
                    type = NonTextType.RENAME_RENAME;
                } else if (ourRenamed || theirRenamed) {
                    type = NonTextType.RENAME_MODIFY;
                } else if ((ourBlob == null && !oursHasFile) || (theirBlob == null && !theirsHasFile)) {
                    type = NonTextType.DELETE_MODIFY;
                } else if (baseBlob == null
                        && ourContent != null
                        && theirContent != null
                        && (oursBinary || theirsBinary)) {
                    type = NonTextType.ADD_ADD_BINARY;
                } else {
                    boolean sameContent = (ourBlob != null && ourBlob.equals(theirBlob))
                            || (ourContent != null && ourContent.equals(theirContent));
                    if (sameContent && (oursExec != theirsExec)) {
                        type = NonTextType.MODE_BIT;
                    }
                }
            }

            // Ensure ProjectFile objects exist for each side using the resolved repo path.
            var ourFile = toProjectFile(ourPath);
            var theirFile = toProjectFile(theirPath);
            var baseFile = basePath == null ? null : toProjectFile(basePath);

            var cf = new MergeAgent.FileConflict(ourFile, ourContent, theirFile, theirContent, baseFile, baseContent);
            var meta = new MergeAgent.NonTextMetadata(
                    type,
                    indexPath,
                    ourPath,
                    theirPath,
                    oursIsDir,
                    theirsIsDir,
                    oursBinary,
                    theirsBinary,
                    oursExec,
                    theirsExec);

            return new BuiltConflict(cf, meta);
        }

        private ProjectFile toProjectFile(String repoPath) {
            // repoPath is relative to the repository root; create a ProjectFile using the project's root
            return new ProjectFile(project.getRoot(), repoPath);
        }
    }

    public static Optional<MergeAgent.MergeConflict> inspectFromProject(IProject project) {
        try {
            return Optional.ofNullable(inspectFromProjectInternal(project));
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
    public static @Nullable MergeAgent.MergeConflict inspectFromProjectInternal(IProject project)
            throws GitAPIException {
        var repo = (GitRepo) project.getRepo();
        var repository = repo.getGit().getRepository();
        var gitDir = repository.getDirectory().toPath();

        var ourCommitId = repo.resolveToCommit("HEAD").getName();

        MergeAgent.MergeMode state = null;
        Path headFile = null;

        // Detect squash merge first: presence of SQUASH_MSG indicates a squash merge in progress.
        if (Files.exists(gitDir.resolve("SQUASH_MSG"))) {
            state = MergeAgent.MergeMode.SQUASH;
            // MERGE_HEAD may or may not be present during a squash merge; don't require it here.
            headFile = gitDir.resolve("MERGE_HEAD");
        }

        if (state == null) {
            var candidates = List.of(
                    Map.entry("MERGE_HEAD", MergeAgent.MergeMode.MERGE),
                    Map.entry("REBASE_HEAD", MergeAgent.MergeMode.REBASE),
                    Map.entry("CHERRY_PICK_HEAD", MergeAgent.MergeMode.CHERRY_PICK),
                    Map.entry("REVERT_HEAD", MergeAgent.MergeMode.REVERT));
            for (var entry : candidates) {
                var candidatePath = gitDir.resolve(entry.getKey());
                if (Files.exists(candidatePath)) {
                    state = entry.getValue();
                    headFile = candidatePath;
                    break;
                }
            }
        }
        if (state == null) {
            return null;
        }

        String originalOtherCommitId;
        if (state == MergeAgent.MergeMode.SQUASH) {
            // Prefer MERGE_HEAD when available; otherwise derive from staged 'theirs' blob by scanning local branches.
            if (Files.exists(requireNonNull(headFile))) {
                originalOtherCommitId = readSingleHead(headFile, state);
            } else {
                originalOtherCommitId = deriveOtherForSquash(repo, repository);
            }
        } else {
            originalOtherCommitId = readSingleHead(requireNonNull(headFile), state);
        }

        String effectiveOtherCommitId = originalOtherCommitId;
        @Nullable String baseCommitId;

        switch (state) {
            case MERGE, SQUASH -> {
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
                case 2 -> builder.setStage2(entry.getObjectId(), content, entry.getFileMode());
                case 3 -> builder.setStage3(entry.getObjectId(), content, entry.getFileMode());
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
        var nonText = new LinkedHashMap<MergeAgent.FileConflict, MergeAgent.NonTextMetadata>();
        for (var b : byIndexPath.values()) {
            var built = b.build(baseCommitId, ourCommitId, effectiveOtherCommitId, stage0Paths, byIndexPath.keySet());
            files.add(built.fileConflict());
            nonText.put(built.fileConflict(), built.metadata());
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
                state, ourCommitId, effectiveOtherCommitId, baseCommitId, Set.copyOf(files), Map.copyOf(nonText));
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

    /**
     * Derive the OTHER commit id for a SQUASH merge when MERGE_HEAD is absent by: - finding a stage-3 (theirs) blob in
     * the index, then - scanning local branches for a tip commit whose tree contains that blob. If no stage-3 entries
     * exist, fall back to ORIG_HEAD, then FETCH_HEAD, then HEAD.
     */
    private static String deriveOtherForSquash(GitRepo repo, Repository repository) throws GitAPIException {
        DirCache dirCache;
        try {
            dirCache = repository.readDirCache();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read DirCache while deriving OTHER for SQUASH", e);
        }

        ObjectId theirBlob = null;
        String anyIndexPath = null;
        for (int i = 0; i < dirCache.getEntryCount(); i++) {
            var entry = dirCache.getEntry(i);
            if (entry.getStage() == 3) {
                theirBlob = entry.getObjectId();
                anyIndexPath = entry.getPathString();
                break;
            }
        }

        // If we have a stage-3 blob, try to resolve OTHER by scanning local branches
        if (theirBlob != null) {
            for (var branch : repo.listLocalBranches()) {
                try {
                    var tip = repository.resolve(branch);
                    if (tip == null) continue;
                    try {
                        // If this succeeds, the blob is present in this branch tip's tree; treat as OTHER.
                        findPathForBlobInCommit(repository, tip.getName(), theirBlob, requireNonNull(anyIndexPath));
                        logger.debug("deriveOtherForSquash: resolved OTHER={} via branch {}", tip.getName(), branch);
                        return tip.getName();
                    } catch (GitRepo.GitRepoException ignore) {
                        // not found in this branch tip; continue scanning
                    }
                } catch (Exception ex) {
                    // resolve failure; ignore and continue
                }
            }
            logger.warn("deriveOtherForSquash: stage-3 blob present but not found in any local branch; falling back");
        } else {
            logger.debug("deriveOtherForSquash: no stage-3 entries in index; attempting fallbacks");
        }

        // Fallbacks when MERGE_HEAD is absent and no stage-3 available or not resolvable:
        var gitDir = repository.getDirectory().toPath();

        // 1) ORIG_HEAD (single-line SHA) if present
        var origHead = gitDir.resolve("ORIG_HEAD");
        if (Files.exists(origHead)) {
            try {
                var other = readSingleHead(origHead, MergeAgent.MergeMode.SQUASH);
                logger.debug("deriveOtherForSquash: using ORIG_HEAD={}", other);
                return other;
            } catch (RuntimeException e) {
                logger.warn("deriveOtherForSquash: failed reading ORIG_HEAD: {}", e.getMessage());
            }
        }

        // 2) FETCH_HEAD: first token of first non-empty line is a commit id
        var fetchHead = gitDir.resolve("FETCH_HEAD");
        if (Files.exists(fetchHead)) {
            try {
                var maybe = Files.readAllLines(fetchHead, StandardCharsets.UTF_8).stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> Iterables.get(
                                Splitter.on(Pattern.compile("\\s+")).split(s), 0))
                        .filter(s -> s.matches("^[0-9a-fA-F]{7,40}$"))
                        .findFirst();
                if (maybe.isPresent()) {
                    var other = maybe.get();
                    logger.debug("deriveOtherForSquash: using FETCH_HEAD={}", other);
                    return other;
                }
            } catch (IOException e) {
                logger.warn("deriveOtherForSquash: failed reading FETCH_HEAD: {}", e.getMessage());
            }
        }

        // 3) Final fallback: HEAD (best-effort to avoid crashing the UI)
        var head = repo.resolveToCommit("HEAD").getName();
        logger.warn("deriveOtherForSquash: could not determine OTHER; falling back to HEAD={}", head);
        return head;
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

    /** Return true if the commit contains any path under the given prefix (path + "/"). */
    private static boolean hasPathPrefixInCommit(Repository repository, String commitId, String pathPrefix) {
        var prefix = pathPrefix.endsWith("/") ? pathPrefix : pathPrefix + "/";
        try (var walk = new RevWalk(repository)) {
            var oid = ObjectId.fromString(commitId);
            RevCommit commit = walk.parseCommit(oid);
            try (var tw = new TreeWalk(repository)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                while (tw.next()) {
                    if (tw.getPathString().startsWith(prefix)) {
                        return true;
                    }
                }
                return false;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to check path prefix in commit: " + pathPrefix + " @ " + commitId, e);
        }
    }

    /** Heuristic binary detection: presence of NUL within the first few KB. */
    private static boolean isBinary(String content) {
        int limit = Math.min(content.length(), 8192);
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\0') return true;
        }
        return false;
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
