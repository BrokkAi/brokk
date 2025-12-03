package ai.brokk;

import ai.brokk.analyzer.ProjectFile;
import java.util.Set;

/**
 * Listener intended to monitor changes to any file in the project.  As opposed to TrackedFileChangeListener,
 * which only monitors files tracked by git.
 */
public interface FileChangeListener {
    void onFilesChanged(Set<ProjectFile> changedFiles);
}
