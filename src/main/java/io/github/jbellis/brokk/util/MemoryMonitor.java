package io.github.jbellis.brokk.util;

import com.sun.management.GarbageCollectionNotificationInfo;
import io.github.jbellis.brokk.IConsoleIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import javax.swing.*;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.text.NumberFormat;
import java.util.List;

/**
 * A memory monitoring daemon that attempts to preempt an {@link OutOfMemoryError}. If the memory usage of the JVM
 * exceeds 85%, the {@link IConsoleIO} dialog is shown to the user.
 */
public class MemoryMonitor implements Runnable {

    private final static Logger logger = LoggerFactory.getLogger(MemoryMonitor.class);

    private static final double MEMORY_THRESHOLD = 0.85;

    private boolean dialogShownAlready = false;
    private final IConsoleIO consoleIO;

    private MemoryMonitor(IConsoleIO consoleIO) {
        this.consoleIO = consoleIO;
    }

    private void issueLowMemoryWarning(double usageRatio) {
        // Once we've shown the dialog, we can likely disappear. We assume the user will either:
        //  * close the application and reset it with new memory configurations; or
        //  * continue working with low memory, but doesn't want this dialog to reappear.
        if (!dialogShownAlready) {
            dialogShownAlready = true;
            final var msg = String.format(
                    "Memory usage is at %.2f%% after the most recent garbage collection, " +
                            "thus the IDE may become unresponsive. Current limit (-Xmx) is %s.",
                    usageRatio * 100,
                    getMaxHeapSize()
            );
            consoleIO.systemNotify(msg, "Low Available Memory Detected", JOptionPane.WARNING_MESSAGE);
        }
    }

    @Override
    public void run() {
        logger.debug("MemoryMonitor ({}) started", Thread.currentThread().getName());
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        // Hook a listener to every garbage collector
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            NotificationEmitter emitter = (NotificationEmitter) gcBean;

            final NotificationListener listener = (notification, handback) -> {
                // We only care about GC completion notifications
                if (notification.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
                    final var info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());

                    long totalUsedAfterGc = 0;
                    long totalMax = 0;

                    // Sum memory usage across all heap pools after the GC
                    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                        if (pool.getType() == java.lang.management.MemoryType.HEAP) {
                            final var usageAfterGc = info.getGcInfo().getMemoryUsageAfterGc().get(pool.getName());
                            totalUsedAfterGc += usageAfterGc.getUsed();
                            totalMax += usageAfterGc.getMax();
                        }
                    }

                    if (totalMax == 0) return;
                    double usageRatio = (double) totalUsedAfterGc / totalMax;

                    logger.debug("[{}] GC finished. Heap usage after GC: {}%", info.getGcName(), usageRatio * 100);

                    // If usage is STILL high, issue the warning
                    if (usageRatio > MEMORY_THRESHOLD) {
                        issueLowMemoryWarning(usageRatio);
                    }
                }
            };

            emitter.addNotificationListener(listener, null, null);
            logger.debug("Registered garbage collector notification listener {}", listener);
        }
    }

    private String getMaxHeapSize() {
        long maxMemoryBytes = Runtime.getRuntime().maxMemory();
        return formatBytesToMBString(maxMemoryBytes);
    }

    /**
     * Helper method to format a byte value into a human-readable MB string.
     *
     * @param maxMemoryBytes The number of bytes to format.
     * @return A formatted string (e.g., "2048 MB" or "No Limit").
     */
    private String formatBytesToMBString(long maxMemoryBytes) {
        if (maxMemoryBytes == Long.MAX_VALUE) return "No Limit";
        long maxMemoryMB = maxMemoryBytes / (1024 * 1024);
        return NumberFormat.getInstance().format(maxMemoryMB) + " MB";
    }

    public static void startMonitoring(IConsoleIO consoleIO) {
        if (!isJmxEnabled()) {
            logger.warn("JMX is disabled, cannot start monitoring");
        } else {
            Thread monitorThread = new Thread(new MemoryMonitor(consoleIO), "BrokkMemoryMonitorThread");
            monitorThread.setDaemon(true); // Ensure thread doesn't prevent JVM shutdown
            monitorThread.start();
        }
    }

    /**
     * Checks if the local JMX Management Extensions are available.
     *
     * @return true if JMX is enabled, false otherwise.
     */
    public static boolean isJmxEnabled() {
        try {
            // Attempt to get a fundamental MXBean.
            // If this fails (returns null or throws), JMX is not available.
            return ManagementFactory.getMemoryMXBean() != null;
        } catch (Exception e) {
            // Catching a broad exception handles any unexpected initialization errors.
            logger.warn("JMX check failed with an exception", e);
            return false;
        }
    }
}