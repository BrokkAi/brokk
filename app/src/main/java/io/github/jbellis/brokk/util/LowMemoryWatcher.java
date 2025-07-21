// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/*
 * Modifications copyright 2025 Brokk, Inc. and made available under the GPLv3.
 *
 * The original file can be found at https://github.com/JetBrains/intellij-community/blob/8716ac75ffffbf446285cc33c325c5a98ddeb6c5/platform/util/src/com/intellij/openapi/util/LowMemoryWatcher.java
 */
package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.util.containers.WeakList;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Eugene Zhuravlev
 */
public final class LowMemoryWatcher {
    private static final Logger logger = LoggerFactory.getLogger(LowMemoryWatcher.class);

    public enum LowMemoryWatcherType {
        ALWAYS,
        ONLY_AFTER_GC
    }

    private static final WeakList<LowMemoryWatcher> ourListeners = new WeakList<>();
    private final Runnable myRunnable;
    private final LowMemoryWatcherType myType;
    private static final AtomicBoolean ourNotificationsSuppressed = new AtomicBoolean();

    public static void onLowMemorySignalReceived(boolean afterGc) {
        logger.info("Low memory signal received: afterGc={}", afterGc);
        for (LowMemoryWatcher watcher : ourListeners.toStrongList()) {
            try {
                if (watcher.myType == LowMemoryWatcherType.ALWAYS
                        || (watcher.myType == LowMemoryWatcherType.ONLY_AFTER_GC && afterGc)) {
                    watcher.myRunnable.run();
                }
            } catch (Throwable e) {
                logger.error("Error while running low memory watcher", e);
            }
        }
    }

    static boolean notificationsSuppressed() {
        return ourNotificationsSuppressed.get();
    }

    /**
     * Registers a runnable to run on low memory events
     *
     * @param runnable         the action which executes on low-memory condition. Can be executed:
     *                         - in arbitrary thread
     *                         - in unpredictable time
     *                         - multiple copies in parallel, so please make it reentrant.
     * @param notificationType When ONLY_AFTER_GC, then the runnable will be invoked only if the low-memory condition still exists after GC.
     *                         When ALWAYS, then the runnable also will be invoked when the low-memory condition is detected before GC.
     * @return a LowMemoryWatcher instance holding the runnable. This instance should be kept in memory while the
     * low memory notification functionality is needed. As soon as it's garbage-collected, the runnable won't receive any further notifications.
     */
    @Contract(pure = true) // to avoid ignoring the result
    public static LowMemoryWatcher register(@NotNull Runnable runnable, @NotNull LowMemoryWatcherType notificationType) {
        return new LowMemoryWatcher(runnable, notificationType);
    }

    /**
     * Registers a runnable to run on low memory events
     *
     * @param runnable the action which executes on low-memory condition. Can be executed:
     *                 - in arbitrary thread
     *                 - in unpredictable time
     *                 - multiple copies in parallel, so please make it reentrant.
     * @return a LowMemoryWatcher instance holding the runnable. This instance should be kept in memory while the
     * low memory notification functionality is needed. As soon as it's garbage-collected, the runnable won't receive any further notifications.
     */
    @Contract(pure = true) // to avoid ignoring the result
    public static LowMemoryWatcher register(@NotNull Runnable runnable) {
        return new LowMemoryWatcher(runnable, LowMemoryWatcherType.ALWAYS);
    }

    private LowMemoryWatcher(@NotNull Runnable runnable, @NotNull LowMemoryWatcherType type) {
        myRunnable = runnable;
        myType = type;
        ourListeners.add(this);
    }

    public void stop() {
        ourListeners.remove(this);
    }

    /**
     * LowMemoryWatcher maintains a background thread where all the handlers are invoked.
     * In server environments, this thread may run indefinitely and prevent the class loader from
     * being gc-ed. Thus, it's necessary to invoke this method to stop that thread and let the classes be garbage-collected.
     */
    static void stopAll() {
        ourListeners.clear();
    }
}
