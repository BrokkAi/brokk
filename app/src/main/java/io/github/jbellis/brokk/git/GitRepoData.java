package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo.ModifiedFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
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
    public String diffFiles(List<ProjectFile> files) throws GitAPIException {
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
            logger.debug("No tracked changes found, returning empty diff");
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

            if ("HEAD".equals(newRev)) {
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
            if ("HEAD".equals(newRev)) {
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

    /** Retrieves the contents of {@code file} at a given commit ID, or returns an empty string if not found. */
    public String getFileContent(String commitId, ProjectFile file) throws GitAPIException {
        if (commitId.isBlank()) {
            logger.debug("getFileContent called with blank commitId; returning empty string");
            return "";
        }

        var objId = repo.resolveToCommit(commitId);

        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(objId);
            var tree = commit.getTree();
            try (var treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                String targetPath = repo.toRepoRelativePath(file);
                while (treeWalk.next()) {
                    if (treeWalk.getPathString().equals(targetPath)) {
                        var blobId = treeWalk.getObjectId(0);
                        var loader = repository.open(blobId);
                        return new String(loader.getBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (IOException e) {
            throw new GitRepo.GitWrappedIOException(e);
        }
        logger.debug("File '{}' not found at commit '{}'", file, commitId);
        return "";
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
