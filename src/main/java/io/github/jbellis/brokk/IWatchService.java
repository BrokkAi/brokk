package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface IWatchService extends AutoCloseable {
    default void start(CompletableFuture<?> delayNotificationsUntilCompleted) {}

    default void pause() {}

    default void resume() {}

    @Override
    default void close() {}

    interface Listener {
        void onFilesChanged(EventBatch batch);

        void onNoFilesChangedDuringPollInterval();
    }

    /** mutable since we will collect events until they stop arriving */
    class EventBatch {
        boolean isOverflowed;
        Set<ProjectFile> files = new HashSet<>();

        @Override
        public String toString() {
            return "EventBatch{" + "isOverflowed=" + isOverflowed + ", files=" + files + '}';
        }
    }
}
