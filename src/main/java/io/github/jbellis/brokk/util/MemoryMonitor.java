package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.gui.components.NotificationPopup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.text.NumberFormat;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A memory monitoring daemon that attempts to preempt an {@link OutOfMemoryError}. If the memory usage of the JVM
 * exceeds 80%, the {@link NotificationPopup} is shown to the user.
 */
public class MemoryMonitor implements Runnable {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private static final double MEMORY_THRESHOLD = 0.80;
    private static final int GC_INTERVAL = 10;
    private static final double GC_THRESHOLD = Math.max(MEMORY_THRESHOLD - 0.10, 0.10);

    private final AtomicInteger gcDelay = new AtomicInteger(GC_INTERVAL);
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private boolean dialogShownAlready = false;
    private final IConsoleIO consoleIO;

    public MemoryMonitor(IConsoleIO consoleIO) {
        this.consoleIO = consoleIO;
    }

    @Override
    public void run() {
        logger.debug("MemoryMonitor ({}) started", Thread.currentThread().getName());
        // Once we've shown the dialog, we can likely disappear. We assume the user will either:
        //  * close the application and reset it with new memory configurations; or
        //  * continue working with low memory, but doesn't want this dialog to reappear.
        while (!Thread.currentThread().isInterrupted() && !dialogShownAlready) {
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            long usedMemory = heapUsage.getUsed();
            long maxMemory = heapUsage.getMax();

            // If maxMemory is not defined (-1), use the committed size
            if (maxMemory == -1) {
                maxMemory = heapUsage.getCommitted();
            }

            double usagePercentage = (double) usedMemory / maxMemory;

            if (usagePercentage > GC_THRESHOLD && gcDelay.getAndDecrement() < 0) {
                logger.warn("Noticed high memory usage {} times, running garbage collection. Current usage: {}%",
                        GC_INTERVAL,
                        usagePercentage * 100);
                System.gc();
                gcDelay.set(GC_INTERVAL);
                if (usagePercentage > MEMORY_THRESHOLD) {
                    logger.warn("Low memory detected! Usage: {}%", usagePercentage * 100);
                    if (!dialogShownAlready) {
                        dialogShownAlready = true;
                        consoleIO.systemNotify(
                                String.format(
                                        "The IDE may become unresponsive. Current limit (-Xmx) is %s.",
                                        getMaxHeapSize()
                                ),
                                "Low Memory Detected",
                                JOptionPane.WARNING_MESSAGE
                        );
                    }
                }
            }

            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                break;
            }
        }
    }


    private String getMaxHeapSize() {
        try {
            long maxMemoryBytes = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
            if (maxMemoryBytes == Long.MAX_VALUE) return "No Limit";
            long maxMemoryMB = maxMemoryBytes / (1024 * 1024);
            return NumberFormat.getInstance().format(maxMemoryMB) + " MB";
        } catch (Exception e) {
            return "N/A";
        }
    }

    public static Thread startMonitoring(IConsoleIO consoleIO) {
        Thread monitorThread = new Thread(new MemoryMonitor(consoleIO), "BrokkMemoryMonitorThread");
        monitorThread.setDaemon(true); // Ensure thread doesn't prevent JVM shutdown
        monitorThread.start();
        return monitorThread;
    }
}