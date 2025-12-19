package ai.brokk.git;

import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/**
 * A simple in-memory IGitRepo implementation for testing purposes. It tracks added and removed files without actual Git
 * operations.
 */
public class TestRepo implements IGitRepo {

    private final Set<ProjectFile> trackedFiles = new HashSet<>();
    private final Set<ProjectFile> addedFiles = new HashSet<>();
    private final Set<ProjectFile> removedFiles = new HashSet<>();
    private final Path root;

    public TestRepo(Path root) {
        this.root = root;
    }

    @Override
    public synchronized Set<ProjectFile> getTrackedFiles() {
        var currentTracked = new HashSet<>(trackedFiles);
        currentTracked.addAll(addedFiles);
        currentTracked.removeAll(removedFiles);
        return Collections.unmodifiableSet(currentTracked);
    }

    @Override
    public synchronized void add(Collection<ProjectFile> files) throws GitAPIException {
        for (var file : files) {
            addedFiles.add(file);
            removedFiles.remove(file); // If it was removed, adding it back makes it not removed
            trackedFiles.add(file); // Ensure it's in the base tracked set
        }
    }

    @Override
    public synchronized void add(ProjectFile file) throws GitAPIException {
        addedFiles.add(file);
        removedFiles.remove(file);
        trackedFiles.add(file);
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
            sb.append(String.format("A %s\n", file));
        }
        for (ProjectFile file : removedFiles) {
            sb.append(String.format("D %s\n", file));
        }
        return sb.toString();
    }

    @Override
    public void invalidateCaches() {
        // No-op for this simple implementation
    }

    @Override
    public String diffFiles(List<ProjectFile> selectedFiles) {
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
    public String getDiff(@Nullable String oldRev, @Nullable String newRev) throws GitAPIException {
        if (newRev == null || oldRev == null) {
            throw new GitAPIException("Commit IDs cannot be null for diffing in InMemoryRepo") {};
        }
        return String.format(
                """
                             diff --git a/%s b/%s
                             index 0000000..0000000
                             --- a/%s
                             +++ b/%s
                             @@ -0,0 +0,0 @@
                             [Simulated diff content for %s vs %s in InMemoryRepo]
                             """,
                oldRev, newRev, oldRev, newRev, newRev, oldRev);
    }

    /** Clears all tracked, added, and removed files. For test cleanup. */
    public synchronized void reset() {
        trackedFiles.clear();
        addedFiles.clear();
        removedFiles.clear();
    }

    /** Gets the set of files explicitly marked as 'added'. For test verification. */
    public Set<ProjectFile> getAddedFilesSnapshot() {
        return Collections.unmodifiableSet(new HashSet<>(addedFiles));
    }

    /** Gets the set of files explicitly marked as 'removed'. For test verification. */
    public Set<ProjectFile> getRemovedFilesSnapshot() {
        return Collections.unmodifiableSet(new HashSet<>(removedFiles));
    }

    @Override
    public String getCurrentCommitId() {
        return "in-memory-commit-id";
    }

    @Override
    public Set<ModifiedFile> getModifiedFiles() {
        return Collections.emptySet();
    }

    @Override
    public void checkout(String branchOrCommit) {
        // no-op
    }

    @Override
    public void applyDiff(String diff) {
        // no-op
    }
}
