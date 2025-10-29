package ai.brokk;

import ai.brokk.IWatchService.EventBatch;
import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Helper utilities for implementing IWatchService.Listener.
 * Provides common patterns for filtering and classifying file system events.
 *
 * <p>This class is designed to make it easier for components like ContextManager
 * to implement file watching without duplicating logic from AnalyzerWrapper.
 */
public class FileWatcherHelper {

    private final Path projectRoot;
    private final Path gitRepoRoot;

    public FileWatcherHelper(Path projectRoot, Path gitRepoRoot) {
        this.projectRoot = projectRoot;
        this.gitRepoRoot = gitRepoRoot;
    }

    /**
     * Checks if the event batch contains changes to git metadata (.git directory).
     *
     * @param batch The event batch to check
     * @return true if git metadata was modified
     */
    public boolean isGitMetadataChanged(EventBatch batch) {
        if (gitRepoRoot == null) {
            return false;
        }

        Path relativeGitMetaDir = projectRoot.relativize(gitRepoRoot.resolve(".git"));
        return batch.files.stream().anyMatch(pf -> pf.getRelPath().startsWith(relativeGitMetaDir));
    }

    /**
     * Filters the event batch to return only files that match the given tracked files.
     *
     * @param batch The event batch
     * @param trackedFiles Set of tracked files to filter against
     * @return Set of changed files that are tracked
     */
    public Set<ProjectFile> getChangedTrackedFiles(EventBatch batch, Set<ProjectFile> trackedFiles) {
        return batch.files.stream().filter(trackedFiles::contains).collect(Collectors.toSet());
    }

    /**
     * Filters the event batch to return only files with the given extensions.
     *
     * @param batch The event batch
     * @param extensions Set of file extensions (e.g., "java", "ts")
     * @return Set of changed files matching the extensions
     */
    public Set<ProjectFile> getFilesWithExtensions(EventBatch batch, Set<String> extensions) {
        return batch.files.stream()
                .filter(pf -> extensions.contains(pf.extension()))
                .collect(Collectors.toSet());
    }

    /**
     * Gets files from the batch that are in a specific directory or its subdirectories.
     *
     * @param batch The event batch
     * @param directory The directory path (relative to project root)
     * @return Set of changed files under the directory
     */
    public Set<ProjectFile> getFilesInDirectory(EventBatch batch, Path directory) {
        return batch.files.stream()
                .filter(pf -> pf.getRelPath().startsWith(directory))
                .collect(Collectors.toSet());
    }

    /**
     * Checks if the batch contains any of the specified files.
     *
     * @param batch The event batch
     * @param files Set of files to check for
     * @return true if any of the specified files are in the batch
     */
    public boolean containsAnyFile(EventBatch batch, Set<ProjectFile> files) {
        return batch.files.stream().anyMatch(files::contains);
    }

    /**
     * Checks if the event represents a significant change that should trigger updates.
     * This includes overflow events or actual file changes.
     *
     * @param batch The event batch
     * @return true if this is a significant change
     */
    public boolean isSignificantChange(EventBatch batch) {
        return batch.isOverflowed || !batch.files.isEmpty();
    }

    /**
     * Result object for classifying file changes by type.
     */
    public static class ChangeClassification {
        public final boolean gitMetadataChanged;
        public final boolean trackedFilesChanged;
        public final Set<ProjectFile> changedTrackedFiles;

        public ChangeClassification(
                boolean gitMetadataChanged, boolean trackedFilesChanged, Set<ProjectFile> changedTrackedFiles) {
            this.gitMetadataChanged = gitMetadataChanged;
            this.trackedFilesChanged = trackedFilesChanged;
            this.changedTrackedFiles = changedTrackedFiles;
        }
    }

    /**
     * Classifies the changes in an event batch for common use cases.
     *
     * @param batch The event batch
     * @param trackedFiles Set of currently tracked files
     * @return Classification of the changes
     */
    public ChangeClassification classifyChanges(EventBatch batch, Set<ProjectFile> trackedFiles) {
        boolean gitChanged = isGitMetadataChanged(batch);
        Set<ProjectFile> changedTracked = getChangedTrackedFiles(batch, trackedFiles);
        boolean trackedChanged = !changedTracked.isEmpty() || batch.isOverflowed;

        return new ChangeClassification(gitChanged, trackedChanged, changedTracked);
    }
}
