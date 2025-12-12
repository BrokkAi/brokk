package ai.brokk.difftool.ui;

import ai.brokk.ContextManager;
import ai.brokk.difftool.node.JMDiffNode;
import ai.brokk.difftool.performance.PerformanceConstants;
import ai.brokk.difftool.ui.unified.UnifiedDiffDocument;
import ai.brokk.difftool.ui.unified.UnifiedDiffPanel;
import ai.brokk.gui.theme.GuiTheme;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

        long maxSize = Math.max(leftSource.estimatedSizeBytes(), rightSource.estimatedSizeBytes());

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
        }

        // Use async for large files
        boolean useAsync = maxSize > PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES;

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

                // Create diff node (panels will compute diff in their initialization)
                var diffNode = FileComparisonHelper.createDiffNode(
                        leftSource, rightSource, contextManager, isMultipleCommitsContext);

                long diffCreationTime = System.currentTimeMillis() - diffStartTime;
                if (diffCreationTime > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS / 2) {
                    logger.warn("Slow diff node creation: {}ms", diffCreationTime);
                }

                // Create appropriate panel type based on view mode
                AbstractDiffPanel panel;
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
                    logger.warn("Slow sync diff creation: {}ms", totalElapsedTime);
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
                        AbstractDiffPanel panel;
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

    /** Creates a UnifiedDiffPanel using the provided JMDiffNode. */
    private static AbstractDiffPanel createUnifiedDiffPanel(
            JMDiffNode diffNode, BrokkDiffPanel mainPanel, GuiTheme theme) {
        try {
            var unifiedPanel = new UnifiedDiffPanel(mainPanel, theme, diffNode);

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
