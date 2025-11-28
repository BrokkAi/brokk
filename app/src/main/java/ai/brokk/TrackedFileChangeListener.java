package ai.brokk;

/**
 * A listener interface for monitoring changes to tracked files (i.e. in git) in a project.
 */
public interface TrackedFileChangeListener {
    void onTrackedFilesChanged();
}
