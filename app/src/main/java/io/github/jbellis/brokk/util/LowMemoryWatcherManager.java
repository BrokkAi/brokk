// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the
// Apache 2.0 license.
/*
 * Modifications copyright 2025 Brokk, Inc. and made available under the GPLv3.
 *
 * The original file can be found at https://github.com/JetBrains/intellij-community/blob/8716ac75ffffbf446285cc33c325c5a98ddeb6c5/platform/util/src/com/intellij/openapi/util/LowMemoryWatcherManager.java
 */
package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.IConsoleIO;
import java.lang.management.*;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LowMemoryWatcherManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LowMemoryWatcherManager.class);

    private static final long MEM_THRESHOLD = 5 /*MB*/ * 1024 * 1024;
    private static final long GC_TIME_THRESHOLD = 10_000; // 10 seconds
    private static final float OCCUPIED_MEMORY_THRESHOLD = 0.90f;

    private final AtomicLong lastGcTime = new AtomicLong();

    private final ExecutorService myExecutorService;

    private @Nullable Future<?> mySubmitted; // guarded by myJanitor
    private final Future<?> myMemoryPoolMXBeansFuture;
    private final Consumer<Boolean> myJanitor = new Consumer<>() {
        @Override
        public void accept(Boolean afterGc) {
            // Clearing `mySubmitted` before all listeners are called, to avoid data races when a
            // listener is added in the middle of execution
            // and is lost. This may, however, cause listeners to execute more than once (potentially
            // even in parallel).
            synchronized (myJanitor) {
                mySubmitted = null;
            }
            LowMemoryWatcher.onLowMemorySignalReceived(afterGc);
        }
    };

    // A list of strong references to low memory watchers.
    private final List<LowMemoryWatcher> lowMemoryWatcherRefs = new ArrayList<>();

    public LowMemoryWatcherManager(ExecutorService backendExecutorService) {
        myExecutorService = backendExecutorService;
        myMemoryPoolMXBeansFuture = initializeMXBeanListenersLater(backendExecutorService);
        lastGcTime.set(getMajorGcTime());
    }

    /**
     * Registers an action with {@link io.github.jbellis.brokk.util.LowMemoryWatcher} and becomes the parent object
     * holding the strong reference to it in order for it not to be garbage collected.
     *
     * @param runnable the action to run on a low-memory event.
     */
    public void registerWithStrongReference(Runnable runnable, LowMemoryWatcher.LowMemoryWatcherType notificationType) {
        final var reference = LowMemoryWatcher.register(runnable, notificationType);
        lowMemoryWatcherRefs.add(reference);
    }

    /**
     * @return a suggested heap size based on the currently allocated heap size. The result is current heap size + 1G.
     */
    public static int suggestedHeapSizeMb() {
        final long maxHeapSize = Runtime.getRuntime().maxMemory();
        return maxHeapSize == Long.MAX_VALUE ? 4096 : (int) (maxHeapSize / (1024 * 1024) + 1024);
    }

    /**
     * Helper method to format bytes into a human-readable MB string.
     *
     * @return the current maximum heap size in a human-readable MB string.
     */
    public static String formatBytes(long memoryInBytes) {
        return NumberFormat.getInstance().format(memoryInBytes / (1024 * 1024)) + " MB";
    }

    private static long getMajorGcTime() {
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getName().toLowerCase(Locale.ROOT).contains("g1 old generation")) {
                return gc.getCollectionTime();
            }
        }
        return 0;
    }

    private Future<?> initializeMXBeanListenersLater(ExecutorService backendExecutorService) {
        // do it in the other thread to get it out of the way during startup
        return backendExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    for (MemoryPoolMXBean bean : ManagementFactory.getMemoryPoolMXBeans()) {
                        if (bean.getType() == MemoryType.HEAP
                                && bean.isCollectionUsageThresholdSupported()
                                && bean.isUsageThresholdSupported()) {
                            long max = bean.getUsage().getMax();
                            long threshold = Math.min((long) (max * OCCUPIED_MEMORY_THRESHOLD), max - MEM_THRESHOLD);
                            if (threshold > 0) {
                                bean.setUsageThreshold(threshold);
                                bean.setCollectionUsageThreshold(threshold);
                            }
                        }
                    }
                    ((NotificationEmitter) ManagementFactory.getMemoryMXBean())
                            .addNotificationListener(myLowMemoryListener, null, null);
                } catch (Throwable e) {
                    // should not happen normally
                    logger.warn("Errors initializing LowMemoryWatcher: ", e);
                }
            }

            @Override
            public String toString() {
                return "initializeMXBeanListeners runnable";
            }
        });
    }

    private static class GcTracker {
        private static final long WINDOW_SIZE_MS = 60_000; // 1 minute
        private final Queue<GcPeriod> gcPeriods = new ArrayDeque<>();

        private record GcPeriod(long timestamp, long gcTime) {}

        public synchronized long trackGcAndGetRecentTime(long currentGcTime, long previousGcTimeValue) {
            long currentTime = System.currentTimeMillis();

            if (currentGcTime > previousGcTimeValue) {
                gcPeriods.offer(new GcPeriod(currentTime, currentGcTime - previousGcTimeValue));
            }

            while (!gcPeriods.isEmpty() && gcPeriods.peek().timestamp < currentTime - WINDOW_SIZE_MS) {
                gcPeriods.poll();
            }

            return gcPeriods.stream().mapToLong(period -> period.gcTime).sum();
        }
    }

    private final GcTracker gcTracker = new GcTracker();

    private final NotificationListener myLowMemoryListener = new NotificationListener() {
        @Override
        public void handleNotification(Notification notification, Object __) {
            if (LowMemoryWatcher.notificationsSuppressed()) return;
            boolean memoryThreshold = MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED.equals(notification.getType());
            boolean memoryCollectionThreshold =
                    MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED.equals(notification.getType());

            if (memoryThreshold || memoryCollectionThreshold) {
                long currentGcTime = getMajorGcTime();
                long previousGcTimeValue = lastGcTime.getAndSet(currentGcTime);
                long recentGcTime = gcTracker.trackGcAndGetRecentTime(currentGcTime, previousGcTimeValue);

                synchronized (myJanitor) {
                    if (mySubmitted == null) {
                        mySubmitted =
                                myExecutorService.submit(() -> myJanitor.accept(recentGcTime > GC_TIME_THRESHOLD));
                        // maybe it's executed too fast or even synchronously
                        if (mySubmitted.isDone()) {
                            mySubmitted = null;
                        }
                    }
                }
            }
        }
    };

    @Override
    public void close() {
        lowMemoryWatcherRefs.clear();
        try {
            myMemoryPoolMXBeansFuture.get();
            ((NotificationEmitter) ManagementFactory.getMemoryMXBean()).removeNotificationListener(myLowMemoryListener);
        } catch (Exception e) {
            logger.error("Exception encountered while shutting down low memory watchers.", e);
        }
        synchronized (myJanitor) {
            if (mySubmitted != null) {
                mySubmitted.cancel(false);
                mySubmitted = null;
            }
        }

        LowMemoryWatcher.stopAll();
    }

    /** Manages the frequency of low memory warnings sent to a user. */
    public static class LowMemoryWarningManager {

        private static final long WARNING_COOLDOWN = TimeUnit.MINUTES.toNanos(5);
        private static final AtomicLong lastWarningTime = new AtomicLong(System.nanoTime() - WARNING_COOLDOWN);

        /**
         * Sends an alert request to let the user know that the current available memory is too low. To avoid
         * overwhelming the user, there is a cooldown period where new requests are ignored until the cooldown is over.
         */
        public static void alertUser(IConsoleIO io) {
            final long last = lastWarningTime.get();
            final long now = System.nanoTime();

            // Check if the cooldown has passed
            if (now - last > WARNING_COOLDOWN) {
                // Atomically check if the last time is still the same and, if so, update it.
                // If this returns true, it means no other thread updated it since we read it.
                if (lastWarningTime.compareAndSet(last, now)) {
                    logger.warn("Alerting user of low available memory.");

                    final long maxHeap = Runtime.getRuntime().maxMemory();
                    final String currentMaxHeap = maxHeap == Long.MAX_VALUE ? "No Limit" : formatBytes(maxHeap);

                    final String msg = String.format(
                            """
                            The IDE may become unresponsive. Current limit (-Xmx) is %s. Try reopening with:
                                jbang run --java-options -Xmx%dM brokk@brokkai/brokk
                            """,
                            currentMaxHeap, suggestedHeapSizeMb());
                    // Ideally, we would set the next warning time on a callback triggered when the user
                    // acknowledges
                    // the dialog (presses OK), but for now we will simply use a cooldown
                    io.systemNotify(msg, "Low Available Memory", JOptionPane.WARNING_MESSAGE);
                }
            }
        }
    }
}
