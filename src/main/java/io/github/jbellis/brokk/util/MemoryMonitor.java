package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.gui.dialogs.LowMemoryDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * A memory monitoring daemon that attempts to preempt an {@link OutOfMemoryError}. If the memory usage of the JVM 
 * exceeds 85%, the {@link LowMemoryDialog} is shown to the user.
 */
public class MemoryMonitor implements Runnable {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private static final double MEMORY_THRESHOLD = 0.80;
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final Frame frame;

    public MemoryMonitor(Frame ownerFrame) {
        this.frame = ownerFrame;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            long usedMemory = heapUsage.getUsed();
            long maxMemory = heapUsage.getMax();

            // If maxMemory is not defined (-1), use the committed size
            if (maxMemory == -1) {
                maxMemory = heapUsage.getCommitted();
            }

            double usagePercentage = (double) usedMemory / maxMemory;

            if (usagePercentage > MEMORY_THRESHOLD) {
                logger.warn("Low memory detected! Usage: {}%", usagePercentage * 100);
                SwingUtilities.invokeLater(() -> LowMemoryDialog.showLowMemoryDialog(frame));
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                break;
            }
        }
    }

    public static Thread startMonitoring(Frame frame) {
        Thread monitorThread = new Thread(new MemoryMonitor(frame), "BrokkMemoryMonitorThread_" + frame.getName());
        monitorThread.setDaemon(true); // Ensure thread doesn't prevent JVM shutdown
        monitorThread.start();
        return monitorThread;
    }
}