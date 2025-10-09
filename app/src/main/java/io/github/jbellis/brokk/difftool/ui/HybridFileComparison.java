package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import io.github.jbellis.brokk.difftool.ui.unified.UnifiedDiffDocument;
import io.github.jbellis.brokk.difftool.ui.unified.UnifiedDiffPanel;
import io.github.jbellis.brokk.gui.GuiTheme;
import java.nio.charset.StandardCharsets;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * hybrid file comparison that uses synchronous processing for small files and asynchronous processing only for large
 * files that benefit from background computation.
 */
public class HybridFileComparison {
    private static final Logger logger = LogManager.getLogger(HybridFileComparison.class);

    /** Creates and displays a diff panel using the optimal sync/async strategy. */
    public static void createDiffPanel(
            BufferSource leftSource,
            BufferSource rightSource,
            BrokkDiffPanel mainPanel,
            GuiTheme theme,
            ContextManager contextManager,
            boolean isMultipleCommitsContext,
            int fileIndex) {

        long startTime = System.currentTimeMillis();

        SizeEstimation leftEstimation = estimateSizeIntelligent(leftSource);
        SizeEstimation rightEstimation = estimateSizeIntelligent(rightSource);

        long maxSize = Math.max(leftEstimation.estimatedBytes(), rightEstimation.estimatedBytes());
        boolean isLowConfidence =
                leftEstimation.confidence() == SizeConfidence.LOW || rightEstimation.confidence() == SizeConfidence.LOW;

        // Use consistent file size validation from FileComparisonHelper
        var sizeValidationError = FileComparisonHelper.validateFileSizes(leftSource, rightSource);
        if (sizeValidationError != null) {
            logger.error("File size validation failed: {}", sizeValidationError);

            // Show error on EDT and clear loading state
            SwingUtilities.invokeLater(() -> {
                // Clear the loading state and re-enable buttons
                mainPanel.displayErrorForFile(fileIndex, sizeValidationError);
            });
            return; // Don't process the file
        }

        // Warn about potentially problematic files
        if (maxSize > PerformanceConstants.HUGE_FILE_THRESHOLD_BYTES) {
            logger.warn("Processing huge file ({} bytes): may cause memory issues", maxSize);
        } else if (maxSize > PerformanceConstants.MEDIUM_FILE_THRESHOLD_BYTES) {
        }

        // Use async for large files OR when size estimation is uncertain and file is medium+
        boolean useAsync = maxSize > PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES
                || (isLowConfidence && maxSize > PerformanceConstants.MEDIUM_FILE_THRESHOLD_BYTES);

        if (useAsync) {
            createAsyncDiffPanel(
                    leftSource,
                    rightSource,
                    mainPanel,
                    theme,
                    contextManager,
                    isMultipleCommitsContext,
                    fileIndex,
                    startTime);
        } else {
            createSyncDiffPanel(
                    leftSource,
                    rightSource,
                    mainPanel,
                    theme,
                    contextManager,
                    isMultipleCommitsContext,
                    fileIndex,
                    startTime);
        }
    }

    /** Synchronous diff creation for small files - faster and simpler. */
    private static void createSyncDiffPanel(
            BufferSource leftSource,
            BufferSource rightSource,
            BrokkDiffPanel mainPanel,
            GuiTheme theme,
            ContextManager contextManager,
            boolean isMultipleCommitsContext,
            int fileIndex,
            long startTime) {

        SwingUtilities.invokeLater(() -> {
            try {
                long diffStartTime = System.currentTimeMillis();

                // Create diff node and compute diff synchronously
                var diffNode = FileComparisonHelper.createDiffNode(
                        leftSource, rightSource, contextManager, isMultipleCommitsContext);

                long diffCreationTime = System.currentTimeMillis() - diffStartTime;
                if (diffCreationTime > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS / 2) {
                    logger.warn("Slow diff node creation: {}ms", diffCreationTime);
                }

                diffStartTime = System.currentTimeMillis();
                diffNode.diff(); // Fast for small files

                long diffComputeTime = System.currentTimeMillis() - diffStartTime;
                if (diffComputeTime > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS) {
                    logger.warn(
                            "Slow diff computation (sync): {}ms - consider lowering size threshold", diffComputeTime);
                }

                // Create appropriate panel type based on view mode
                IDiffPanel panel;
                if (mainPanel.isUnifiedView()) {
                    panel = createUnifiedDiffPanel(diffNode, mainPanel, theme);
                } else {
                    var bufferPanel = new BufferDiffPanel(mainPanel, theme);
                    bufferPanel.setDiffNode(diffNode);
                    panel = bufferPanel;
                }

                // Cache the panel
                mainPanel.cachePanel(fileIndex, panel);

                // Display using the proper method that updates navigation buttons
                mainPanel.displayAndRefreshPanel(fileIndex, panel);

                long totalElapsedTime = System.currentTimeMillis() - startTime;

                if (totalElapsedTime > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS) {
                    logger.warn(
                            "Slow sync diff creation: {}ms (diff: {}ms, total: {}ms)",
                            diffComputeTime,
                            totalElapsedTime,
                            totalElapsedTime);
                } else {
                }

            } catch (RuntimeException ex) {
                logger.error("Error creating sync diff panel", ex);
                mainPanel.getConsoleIO().toolError("Error creating diff: " + ex.getMessage(), "Error");
            }
        });
    }

    /**
     * Asynchronous diff creation for large files - prevents UI blocking. Uses simple background thread instead of
     * over-engineered SwingWorker.
     */
    private static void createAsyncDiffPanel(
            BufferSource leftSource,
            BufferSource rightSource,
            BrokkDiffPanel mainPanel,
            GuiTheme theme,
            ContextManager contextManager,
            boolean isMultipleCommitsContext,
            int fileIndex,
            long startTime) {

        var taskDescription = "Computing diff: %s"
                .formatted(mainPanel.fileComparisons.get(fileIndex).getDisplayName());

        contextManager.submitBackgroundTask(taskDescription, () -> {
            try {
                // Create diff node and compute diff in background
                var diffNode = FileComparisonHelper.createDiffNode(
                        leftSource, rightSource, contextManager, isMultipleCommitsContext);

                diffNode.diff(); // This is the potentially slow operation for large files

                // Create panel on EDT after diff computation is complete
                SwingUtilities.invokeLater(() -> {
                    try {
                        // Create appropriate panel type based on view mode
                        IDiffPanel panel;
                        if (mainPanel.isUnifiedView()) {
                            panel = createUnifiedDiffPanel(diffNode, mainPanel, theme);
                        } else {
                            var bufferPanel = new BufferDiffPanel(mainPanel, theme);
                            bufferPanel.setDiffNode(diffNode);
                            panel = bufferPanel;
                        }

                        // Cache the panel
                        mainPanel.cachePanel(fileIndex, panel);

                        // Display using the proper method that updates navigation buttons
                        mainPanel.displayAndRefreshPanel(fileIndex, panel);

                        // Performance monitoring
                        long elapsedTime = System.currentTimeMillis() - startTime;

                        if (elapsedTime > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS * 5) {
                            logger.warn("Slow async diff creation: {}ms", elapsedTime);
                        } else {
                        }

                    } catch (RuntimeException ex) {
                        logger.error("Error creating async diff panel on EDT", ex);
                        mainPanel.getConsoleIO().toolError("Error creating diff: " + ex.getMessage(), "Error");
                    }
                });

            } catch (RuntimeException ex) {
                logger.error("Error computing diff in background thread", ex);
                SwingUtilities.invokeLater(() -> {
                    mainPanel.getConsoleIO().toolError("Error computing diff: " + ex.getMessage(), "Error");
                });
            }
        });
    }

    /**
     * Intelligently estimates the content size for a BufferSource with confidence assessment. Uses different strategies
     * based on source type and available information.
     */
    private static SizeEstimation estimateSizeIntelligent(BufferSource source) {
        try {
            if (source instanceof BufferSource.FileSource fileSource) {
                var file = fileSource.file();
                if (file.exists() && file.isFile()) {
                    long fileSize = file.length();
                    // High confidence for existing files
                    return new SizeEstimation(fileSize, SizeConfidence.HIGH, "file.length()");
                } else {
                    // File doesn't exist or isn't a regular file
                    logger.warn("File does not exist or is not regular file: {}", file);
                    return new SizeEstimation(0L, SizeConfidence.LOW, "file not found");
                }

            } else if (source instanceof BufferSource.StringSource stringSource) {
                var content = stringSource.content();
                // Accurate size calculation for string content
                long byteSize = content.getBytes(StandardCharsets.UTF_8).length;
                return new SizeEstimation(byteSize, SizeConfidence.HIGH, "UTF-8 byte count");

            } else {
                // Unknown BufferSource type - use conservative estimate
                logger.warn("Unknown BufferSource type: {}", source.getClass().getName());
                return new SizeEstimation(
                        PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES / 8, SizeConfidence.LOW, "unknown source type");
            }

        } catch (RuntimeException ex) {
            logger.error("Error estimating size for source: {}", source, ex);
            return new SizeEstimation(
                    PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES / 4,
                    SizeConfidence.LOW,
                    "estimation error: " + ex.getMessage());
        }
    }

    /** Represents a size estimation with confidence level and method used. */
    private record SizeEstimation(long estimatedBytes, SizeConfidence confidence, @Nullable String method) {}

    /** Confidence level in size estimation accuracy. */
    private enum SizeConfidence {
        HIGH, // Exact or very accurate (e.g., file.length(), string.getBytes().length)
        MEDIUM, // Good approximation (e.g., string.length() * avg_char_size)
        LOW // Rough estimate or fallback (e.g., unknown types, errors)
    }

    /** Creates a UnifiedDiffPanel using the provided JMDiffNode. */
    private static IDiffPanel createUnifiedDiffPanel(JMDiffNode diffNode, BrokkDiffPanel mainPanel, GuiTheme theme) {
        try {
            var unifiedPanel = new UnifiedDiffPanel(mainPanel, theme, diffNode);

            if (unifiedPanel == null) {
                throw new RuntimeException("UnifiedDiffPanel constructor returned null");
            }

            // Apply global context mode preference from main panel
            var targetMode = mainPanel.getGlobalShowAllLinesInUnified()
                    ? UnifiedDiffDocument.ContextMode.FULL_CONTEXT
                    : UnifiedDiffDocument.ContextMode.STANDARD_3_LINES;
            unifiedPanel.setContextMode(targetMode);

            return unifiedPanel;
        } catch (Exception e) {
            logger.error(
                    "Exception in createUnifiedDiffPanel for JMDiffNode {} - {}: {}",
                    diffNode.getName(),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e);
            // Fallback to empty panel that shows the error
            throw new RuntimeException("Failed to create unified diff panel: " + e.getMessage(), e);
        }
    }
}
