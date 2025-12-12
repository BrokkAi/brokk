package ai.brokk;

import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public interface IWatchService extends AutoCloseable {
    Logger logger = LogManager.getLogger(IWatchService.class);

    /**
     * Resolves the actual git metadata directory, handling worktrees where .git is a file.
     * In worktrees, .git is a file containing "gitdir: /path/to/main/.git/worktrees/xxx".
     *
     * @param gitRepoRoot The git repository root (may be null)
     * @return The resolved git metadata directory path, or gitRepoRoot/.git for regular repos
     */
    static @Nullable Path resolveGitMetaDir(@Nullable Path gitRepoRoot) {
        if (gitRepoRoot == null) {
            return null;
        }
        var gitPath = gitRepoRoot.resolve(".git");
        if (Files.isRegularFile(gitPath)) {
            // Worktree case: .git is a file pointing to the actual git directory
            try {
                var content = Files.readString(gitPath, StandardCharsets.UTF_8).trim();
                if (content.startsWith("gitdir: ")) {
                    var gitDirPath = content.substring("gitdir: ".length());
                    // Resolve against .git file's parent to handle both absolute and relative paths
                    var resolved = gitPath.getParent().resolve(gitDirPath).normalize();
                    logger.debug("Resolved worktree git metadata directory: {} -> {}", gitPath, resolved);
                    return resolved;
                }
            } catch (IOException e) {
                logger.warn("Failed to read .git file for worktree resolution: {}", e.getMessage());
            }
        }
        return gitPath;
    }
    default void start(CompletableFuture<?> delayNotificationsUntilCompleted) {}

    default void pause() {}

    default void resume() {}

    default boolean isPaused() {
        return false;
    }

    /**
     * Dynamically add a listener to receive file system events.
     * @param listener The listener to add
     */
    default void addListener(Listener listener) {}

    /**
     * Remove a previously added listener.
     * @param listener The listener to remove
     */
    default void removeListener(Listener listener) {}

    @Override
    default void close() {}

    interface Listener {
        void onFilesChanged(EventBatch batch);

        void onNoFilesChangedDuringPollInterval();
    }

    /** mutable since we will collect events until they stop arriving */
    class EventBatch {
        boolean isOverflowed;
        boolean untrackedGitignoreChanged;
        Set<ProjectFile> files = new HashSet<>();

        @Override
        public String toString() {
            return "EventBatch{" + "isOverflowed=" + isOverflowed + ", untrackedGitignoreChanged="
                    + untrackedGitignoreChanged + ", files=" + files + '}';
        }
    }
}
