package ai.brokk.git;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.IGitRepo.ModifiedFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.jetbrains.annotations.Nullable;

/**
 * Helper class extracted from GitRepo to encapsulate data- and diff-related operations.
 *
 * <p>This class intentionally keeps a reference to the owning GitRepo so it can use helper methods such as
 * toRepoRelativePath/toProjectFile which were changed to package-private.
 */
public class GitRepoData {
    private static final Logger logger = LogManager.getLogger(GitRepoData.class);

    private final GitRepo repo;
    private final Repository repository;
    private final Git git;

    GitRepoData(GitRepo repo) {
        this.repo = repo;
        this.repository = repo.getRepository();
        this.git = repo.getGit();
    }

    /** Performs git diff operation with the given filter group, handling NoHeadException for empty repositories. */
    public String performDiffWithFilter(TreeFilter filterGroup) throws GitAPIException {
        try (var out = new ByteArrayOutputStream()) {
            try {
                // 1) staged changes
                git.diff()
                        .setCached(true)
                        .setShowNameAndStatusOnly(false)
                        .setPathFilter(filterGroup)
                        .setOutputStream(out)
                        .call();
                var staged = out.toString(StandardCharsets.UTF_8);
                out.reset();

                // 2) unstaged changes
                git.diff()
                        .setCached(false)
                        .setShowNameAndStatusOnly(false)
                        .setPathFilter(filterGroup)
                        .setOutputStream(out)
                        .call();
                var unstaged = out.toString(StandardCharsets.UTF_8);

                return Stream.of(staged, unstaged).filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
            } catch (NoHeadException e) {
                // Handle empty repository case - return empty diff for repositories with no commits
                logger.debug("NoHeadException caught - empty repository, returning empty diff");
                return "";
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Produces a combined diff of staged + unstaged changes, restricted to the given files. */
    public String diffFiles(Collection<ProjectFile> files) throws GitAPIException {
        var filters = files.stream()
                .map(file -> PathFilter.create(repo.toRepoRelativePath(file)))
                .collect(Collectors.toCollection(ArrayList::new));
        var filterGroup = PathFilterGroup.create(filters);

        return performDiffWithFilter(filterGroup);
    }

    public String diff() throws GitAPIException {
        var status = git.status().call();

        var trackedPaths = new HashSet<String>();
        trackedPaths.addAll(status.getModified());
        trackedPaths.addAll(status.getChanged());
        trackedPaths.addAll(status.getAdded());
        trackedPaths.addAll(status.getRemoved());
        trackedPaths.addAll(status.getMissing());

        if (trackedPaths.isEmpty()) {
            logger.trace("No tracked changes found, returning empty diff");
            return "";
        }

        var filters = trackedPaths.stream().map(PathFilter::create).collect(Collectors.toCollection(ArrayList::new));
        var filterGroup = PathFilterGroup.create(filters);

        return performDiffWithFilter(filterGroup);
    }

    /** Show diff between two commits (or a commit and the working directory if newCommitId == HEAD). */
    public String getDiff(String oldRev, String newRev) throws GitAPIException {
        try (var out = new ByteArrayOutputStream()) {
            logger.debug("Generating diff from {} to {}", oldRev, newRev);

            var oldTreeIter = prepareTreeParser(oldRev);
            if (oldTreeIter == null) {
                logger.warn("Old commit/tree {} not found. Returning empty diff.", oldRev);
                return "";
            }

            if ("WORKING".equals(newRev)) {
                git.diff()
                        .setOldTree(oldTreeIter)
                        .setNewTree(null) // Working tree
                        .setOutputStream(out)
                        .call();
            } else {
                var newTreeIter = prepareTreeParser(newRev);
                if (newTreeIter == null) {
                    logger.warn("New commit/tree {} not found. Returning empty diff.", newRev);
                    return "";
                }

                git.diff()
                        .setOldTree(oldTreeIter)
                        .setNewTree(newTreeIter)
                        .setOutputStream(out)
                        .call();
            }

            var result = out.toString(StandardCharsets.UTF_8);
            logger.debug("Generated diff of {} bytes", result.length());
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Show diff for a specific file between two commits. */
    public String getDiff(ProjectFile file, String oldRev, String newRev) throws GitAPIException {
        try (var out = new ByteArrayOutputStream()) {
            var pathFilter = PathFilter.create(repo.toRepoRelativePath(file));
            if ("WORKING".equals(newRev)) {
                git.diff()
                        .setOldTree(prepareTreeParser(oldRev))
                        .setNewTree(null) // Working tree
                        .setPathFilter(pathFilter)
                        .setOutputStream(out)
                        .call();
            } else {
                git.diff()
                        .setOldTree(prepareTreeParser(oldRev))
                        .setNewTree(prepareTreeParser(newRev))
                        .setPathFilter(pathFilter)
                        .setOutputStream(out)
                        .call();
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Placeholder text returned when a blob exceeds our safe-load threshold. */
    public static final String LARGE_OBJECT_PLACEHOLDER = "[File content too large to display]";

    /** Maximum blob size we'll load into memory (10 MB). */
    private static final long MAX_BLOB_SIZE = 10 * 1024 * 1024;

    /** Retrieves the contents of {@code file} at a given commit ID, or returns an empty string if not found. */
    public String getFileContent(String commitId, ProjectFile file) throws GitAPIException {
        if (commitId.isBlank()) {
            throw new IllegalArgumentException("commitId must not be blank");
        }

        var objId = repo.resolveToCommit(commitId);
        var targetPath = repo.toRepoRelativePath(file);

        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(objId);
            var tree = commit.getTree();
            try (var treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                treeWalk.setFilter(PathFilter.create(targetPath));
                if (treeWalk.next()) {
                    var blobId = treeWalk.getObjectId(0);
                    var loader = repository.open(blobId);
                    return loadBlobContent(loader, file, commitId);
                }
            }
        } catch (IOException e) {
            throw new GitRepo.GitWrappedIOException(e);
        }

        throw new GitRepo.GitRepoException("File '%s' not found at commit '%s'".formatted(file, commitId));
    }

    /**
     * Safely loads blob content, handling large objects gracefully.
     * Returns a placeholder for objects exceeding MAX_BLOB_SIZE.
     */
    private String loadBlobContent(org.eclipse.jgit.lib.ObjectLoader loader, ProjectFile file, String commitId)
            throws IOException {
        long size = loader.getSize();

        if (size > MAX_BLOB_SIZE) {
            logger.debug(
                    "File {} at commit {} is {} bytes, exceeds {} byte limit, returning placeholder",
                    file,
                    commitId,
                    size,
                    MAX_BLOB_SIZE);
            return "[File too large to load from git: %d bytes]".formatted(size);
        }

        // For large objects (as determined by JGit's threshold), use streaming to avoid LargeObjectException
        if (loader.isLarge()) {
            logger.debug("File {} at commit {} is large ({} bytes), using stream", file, commitId, size);
            try (var stream = loader.openStream()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        // For small objects, getBytes() is safe and efficient
        return new String(loader.getBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Apply a diff to the working directory.
     *
     * @param diff The diff to apply.
     * @throws GitAPIException if applying the diff fails.
     */
    public void applyDiff(String diff) throws GitAPIException {
        try (var in = new ByteArrayInputStream(diff.getBytes(StandardCharsets.UTF_8))) {
            git.apply().setPatch(in).call();
            repo.invalidateCaches();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<ModifiedFile> extractFilesFromDiffEntries(List<DiffEntry> diffs) {
        var result = new ArrayList<ModifiedFile>();
        for (var diff : diffs) {
            String pathToUse = null;
            IGitRepo.ModificationType type;

            switch (diff.getChangeType()) {
                case ADD, COPY -> {
                    pathToUse = diff.getNewPath();
                    type = IGitRepo.ModificationType.NEW;
                }
                case MODIFY -> {
                    pathToUse = diff.getNewPath();
                    type = IGitRepo.ModificationType.MODIFIED;
                }
                case DELETE -> {
                    pathToUse = diff.getOldPath();
                    type = IGitRepo.ModificationType.DELETED;
                }
                case RENAME -> {
                    // For renames, use only the new path to avoid leaking old names into analytics
                    pathToUse = diff.getNewPath();
                    type = IGitRepo.ModificationType.MODIFIED;
                }
                default -> throw new IllegalStateException("Unexpected value: " + diff.getChangeType());
            }

            if (pathToUse != null && !"/dev/null".equals(pathToUse)) {
                var projectFileOpt = repo.toProjectFile(pathToUse);
                projectFileOpt.ifPresent(projectFile -> result.add(new ModifiedFile(projectFile, type)));
            }
        }

        // Sort by file path for consistent ordering
        result.sort((a, b) -> a.file().toString().compareTo(b.file().toString()));
        return result;
    }

    /**
     * Lists files changed in a specific commit compared to its primary parent. For an initial commit, lists all files
     * in that commit. This is implemented in terms of listFilesChangedBetweenCommits to ensure consistent handling
     * of renames and file status tracking.
     */
    public List<ModifiedFile> listFilesChangedInCommit(String commitId) throws GitAPIException {
        if ("WORKING".equals(commitId)) {
            return listFilesChangedBetweenCommits("HEAD", "WORKING");
        }

        var commitObjectId = repo.resolveToCommit(commitId);

        try (var revWalk = new RevWalk(repository);
                var treeWalk = new TreeWalk(repository)) {
            var commit = revWalk.parseCommit(commitObjectId);

            if (commit.getParentCount() == 0) {
                // Initial commit: list all files in the commit as NEW
                var result = new ArrayList<ModifiedFile>();
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    var path = treeWalk.getPathString();
                    var projectFileOpt = repo.toProjectFile(path);
                    projectFileOpt.ifPresent(
                            projectFile -> result.add(new ModifiedFile(projectFile, IGitRepo.ModificationType.NEW)));
                }
                return result;
            } else {
                // Regular commit: diff against primary parent
                var parentId = commit.getParent(0).getId().getName();
                return listFilesChangedBetweenCommits(parentId, commitId);
            }
        } catch (IOException e) {
            throw new GitRepo.GitWrappedIOException(e);
        }
    }

    public record FileDiff(
            @Nullable ProjectFile oldFile, @Nullable ProjectFile newFile, String oldText, String newText) {}

    private List<DiffEntry> scanDiffs(String oldRef, String newRef) throws GitAPIException {
        var oldTreeIter = prepareTreeParser(oldRef);
        if (oldTreeIter == null) return List.of();

        if (!"WORKING".equals(newRef)) {
            try (var diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                diffFormatter.setRepository(repository);
                diffFormatter.setDetectRenames(true);

                var newTreeIter = prepareTreeParser(newRef);
                if (newTreeIter == null) return List.of();
                return diffFormatter.scan(oldTreeIter, newTreeIter);
            } catch (IOException e) {
                throw new GitRepo.GitWrappedIOException(e);
            }
        }

        // Optimization: avoid full working tree hash computation by pre-filtering paths.
        // 1. Get paths changed between oldRef and HEAD (fast commit-to-commit comparison)
        // 2. Get paths changed in working tree from status (optimized, uses index cache)
        // 3. Union and filter the diff to only those paths
        var changedPaths = new HashSet<String>();

        // Part 1: Paths changed between oldRef and HEAD (if they differ)
        var headTreeIter = prepareTreeParser("HEAD");
        if (headTreeIter != null && !oldRef.equals("HEAD")) {
            try (var diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                diffFormatter.setRepository(repository);
                diffFormatter.setDetectRenames(true);
                for (var entry : diffFormatter.scan(oldTreeIter, headTreeIter)) {
                    if (!"/dev/null".equals(entry.getOldPath())) {
                        changedPaths.add(entry.getOldPath());
                    }
                    if (!"/dev/null".equals(entry.getNewPath())) {
                        changedPaths.add(entry.getNewPath());
                    }
                }
            } catch (IOException e) {
                throw new GitRepo.GitWrappedIOException(e);
            }
            // Re-prepare oldTreeIter since DiffFormatter consumed it
            oldTreeIter = prepareTreeParser(oldRef);
            if (oldTreeIter == null) return List.of();
        }

        // Part 2: Paths changed in working tree (from status)
        var status = git.status().call();
        changedPaths.addAll(status.getModified());
        changedPaths.addAll(status.getChanged());
        changedPaths.addAll(status.getAdded());
        changedPaths.addAll(status.getRemoved());
        changedPaths.addAll(status.getMissing());
        // Notably: NOT including getUntracked() - we don't want untracked files

        if (changedPaths.isEmpty()) {
            return List.of();
        }

        // Part 3: Run filtered diff from oldRef to WORKING using DiffFormatter for rename detection
        var filters = changedPaths.stream().map(PathFilter::create).collect(Collectors.toCollection(ArrayList::new));
        var filterGroup = PathFilterGroup.create(filters);

        try (var diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repository);
            diffFormatter.setDetectRenames(true);
            diffFormatter.setPathFilter(filterGroup);
            var workingTreeIter = new FileTreeIterator(repository);
            return diffFormatter.scan(oldTreeIter, workingTreeIter);
        } catch (IOException e) {
            throw new GitRepo.GitWrappedIOException(e);
        }
    }

    /** Lists files changed between two commit SHAs (from oldCommitId to newCommitId). */
    public List<ModifiedFile> listFilesChangedBetweenCommits(String oldCommitId, String newCommitId)
            throws GitAPIException {
        if (oldCommitId.isBlank()) {
            throw new IllegalArgumentException("oldCommitId must not be blank");
        }
        if (newCommitId.isBlank()) {
            throw new IllegalArgumentException("newCommitId must not be blank");
        }

        var diffs = scanDiffs(oldCommitId, newCommitId);
        return extractFilesFromDiffEntries(diffs);
    }

    /** Returns structured diffs between two references, including content and rename detection. */
    public List<FileDiff> getFileDiffs(String oldRef, String newRef) throws GitAPIException {
        var diffs = scanDiffs(oldRef, newRef);
        var result = new ArrayList<FileDiff>();

        for (var entry : diffs) {
            var oldPath = entry.getOldPath();
            var newPath = entry.getNewPath();

            var oldFile = entry.getChangeType() == DiffEntry.ChangeType.ADD
                    ? null
                    : repo.toProjectFile(oldPath).orElse(null);
            var newFile = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                    ? null
                    : repo.toProjectFile(newPath).orElse(null);

            String oldText = (oldFile != null) ? getRefContent(oldRef, oldFile) : "";
            String newText = (newFile != null) ? getRefContent(newRef, newFile) : "";

            result.add(new FileDiff(oldFile, newFile, oldText, newText));
        }
        return result;
    }

    public String getRefContent(String ref, ProjectFile file) throws GitAPIException {
        if ("WORKING".equals(ref)) {
            return file.read().orElse("");
        }
        try {
            return getFileContent(ref, file);
        } catch (GitAPIException e) {
            logger.debug("File {} not found at ref {}, treating as empty", file, ref);
            return "";
        }
    }

    /** Prepares an AbstractTreeIterator for the given commit-ish string. */
    public @Nullable CanonicalTreeParser prepareTreeParser(String objectId) throws GitAPIException {
        if (objectId.isBlank()) {
            logger.warn("prepareTreeParser called with blank ref. Returning null iterator.");
            return null;
        }

        var objId = repo.resolveToCommit(objectId);

        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(objId);
            var treeId = commit.getTree().getId();
            try (var reader = repository.newObjectReader()) {
                return new CanonicalTreeParser(null, reader, treeId);
            }
        } catch (IOException e) {
            throw new GitRepo.GitWrappedIOException(e);
        }
    }
}
