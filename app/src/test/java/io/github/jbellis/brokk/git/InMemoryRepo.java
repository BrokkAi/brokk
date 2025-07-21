package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A simple in-memory IGitRepo implementation for testing purposes.
 * It tracks added and removed files without actual Git operations.
 */
public class InMemoryRepo implements IGitRepo {

    private final Set<ProjectFile> trackedFiles = new HashSet<>();
    private final Set<ProjectFile> addedFiles = new HashSet<>();
    private final Set<ProjectFile> removedFiles = new HashSet<>();

    public InMemoryRepo() {
    }

    public InMemoryRepo(Set<ProjectFile> initialTrackedFiles) {
        this.trackedFiles.addAll(initialTrackedFiles);
    }

    @Override
    public synchronized Set<ProjectFile> getTrackedFiles() {
        var currentTracked = new HashSet<>(trackedFiles);
        currentTracked.addAll(addedFiles);
        currentTracked.removeAll(removedFiles);
        return Collections.unmodifiableSet(currentTracked);
    }

    @Override
    public synchronized void add(List<ProjectFile> files) throws GitAPIException {
        for (var file : files) {
            addedFiles.add(file);
            removedFiles.remove(file); // If it was removed, adding it back makes it not removed
            trackedFiles.add(file); // Ensure it's in the base tracked set
        }
    }

    @Override
    public synchronized void remove(ProjectFile file) throws GitAPIException {
        removedFiles.add(file);
        addedFiles.remove(file); // If it was added, removing it means it's no longer added
        // We don't remove from `trackedFiles` here because `git rm --cached` conceptually
        // means it *was* tracked and is now staged for deletion.
        // `getTrackedFiles` will reflect its removal from the live set.
    }

    @Override
    public String diff() throws GitAPIException {
        // Basic diff representation for testing if needed
        var sb = new StringBuilder();
        for (ProjectFile file : addedFiles) {
            sb.append(String.format("A %s\n", file.toString()));
        }
        for (ProjectFile file : removedFiles) {
            sb.append(String.format("D %s\n", file.toString()));
        }
        return sb.toString();
    }

    @Override
    public void invalidateCaches() {
        // No-op for this simple implementation
    }

    @Override
    public ObjectId resolve(String s) throws GitAPIException {
        throw new UnsupportedOperationException("SimpleFileTrackerRepo.resolve not implemented");
    }

    @Override
    public String diffFiles(List<ProjectFile> selectedFiles) throws GitAPIException {
        var selectedPaths = selectedFiles.stream().map(ProjectFile::toString).collect(Collectors.toSet());
        var sb = new StringBuilder();
        for (ProjectFile file : addedFiles) {
            if (selectedPaths.contains(file.toString())) {
                sb.append(String.format("A %s\n", file));
            }
        }
        for (ProjectFile file : removedFiles) {
            if (selectedPaths.contains(file.toString())) {
                sb.append(String.format("D %s\n", file));
            }
        }
        return sb.toString();
    }

    // Other methods from IGitRepo can retain default UnsupportedOperationException
    // or be implemented with simple stubs if needed by tests.

    @Override
    public String showDiff(@Nullable String newCommitId, @Nullable String oldCommitId) throws GitAPIException {
        if (newCommitId == null || oldCommitId == null) {
            throw new GitAPIException("Commit IDs cannot be null for diffing in InMemoryRepo") {};
        }
        return String.format("""
                             diff --git a/%s b/%s
                             index 0000000..0000000
                             --- a/%s
                             +++ b/%s
                             @@ -0,0 +0,0 @@
                             [Simulated diff content for %s vs %s in InMemoryRepo]
                             """, oldCommitId, newCommitId, oldCommitId, newCommitId, newCommitId, oldCommitId);
    }

    /**
     * Clears all tracked, added, and removed files. For test cleanup.
     */
    public synchronized void reset() {
        trackedFiles.clear();
        addedFiles.clear();
        removedFiles.clear();
    }

    /**
     * Gets the set of files explicitly marked as 'added'. For test verification.
     */
    public Set<ProjectFile> getAddedFilesSnapshot() {
        return Collections.unmodifiableSet(new HashSet<>(addedFiles));
    }

    /**
     * Gets the set of files explicitly marked as 'removed'. For test verification.
     */
    public Set<ProjectFile> getRemovedFilesSnapshot() {
        return Collections.unmodifiableSet(new HashSet<>(removedFiles));
    }
}
