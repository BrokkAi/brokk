package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.components.NotificationPopup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.NumberFormat;

/**
 * A memory monitoring daemon that attempts to preempt an {@link OutOfMemoryError}. If the memory usage of the JVM
 * exceeds 80%, the {@link NotificationPopup} is shown to the user.
 */
public class MemoryMonitor implements Runnable {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private static final double MEMORY_THRESHOLD = 0.80;
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final Frame frame;
    private boolean dialogShownAlready = false;

    public MemoryMonitor(Frame ownerFrame) {
        this.frame = ownerFrame;
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

            if (usagePercentage > MEMORY_THRESHOLD) {
                logger.warn("Low memory detected! Usage: {}%", usagePercentage * 100);
                SwingUtilities.invokeLater(() -> {
                    if (!dialogShownAlready) {
                        dialogShownAlready = true;
                        SwingUtil.runOnEdt(() -> buildPopup(frame).setVisible(true));
                    }
                });
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                break;
            }
        }
    }

    private NotificationPopup buildPopup(Frame owner) {
        final var title = "Low Memory Detected";
        final var body = String.format("The IDE may become unresponsive. Current limit (-Xmx) is %s.", getMaxHeapSize());
        final var urlString = "https://www.brokk.ai/documentation";
        final var builder = new NotificationPopup.NotificationPopupBuilder(owner, title, body);
        try {
            return builder
                    // TODO: Link to a page on how to manage memory
                    .optionalUrl("Increase memory limit...", new URI(urlString).toURL())
                    .build();
        } catch (URISyntaxException | MalformedURLException e) {
            logger.warn("Unable to build notification popup link for URL: {}", urlString, e);
            return builder.build();
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

    public static Thread startMonitoring(Frame frame) {
        Thread monitorThread = new Thread(new MemoryMonitor(frame), "BrokkMemoryMonitorThread_" + frame.getName());
        monitorThread.setDaemon(true); // Ensure thread doesn't prevent JVM shutdown
        monitorThread.start();
        return monitorThread;
    }
}