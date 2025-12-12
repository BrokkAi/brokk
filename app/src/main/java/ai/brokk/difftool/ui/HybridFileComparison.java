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
            long diffStartTime = System.currentTimeMillis();

            var diffNode = FileComparisonHelper.createDiffNode(
                    leftSource, rightSource, contextManager, isMultipleCommitsContext);

            long diffCreationTime = System.currentTimeMillis() - diffStartTime;
            if (diffCreationTime > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS / 2) {
                logger.warn("Slow diff node creation: {}ms", diffCreationTime);
            }

            buildAndDisplayPanelOnEdt(diffNode, mainPanel, theme, fileIndex);

            long elapsedTime = System.currentTimeMillis() - startTime;
            if (elapsedTime > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS) {
                logger.warn("Slow sync diff creation: {}ms", elapsedTime);
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
            var diffNode = FileComparisonHelper.createDiffNode(
                    leftSource, rightSource, contextManager, isMultipleCommitsContext);

            diffNode.diff();

            SwingUtilities.invokeLater(() -> {
                buildAndDisplayPanelOnEdt(diffNode, mainPanel, theme, fileIndex);

                long elapsedTime = System.currentTimeMillis() - startTime;
                if (elapsedTime > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS * 5) {
                    logger.warn("Slow async diff creation: {}ms", elapsedTime);
                }
            });
        });
    }

    /** Creates, caches, and displays the appropriate diff panel on the EDT. */
    private static void buildAndDisplayPanelOnEdt(
            JMDiffNode diffNode, BrokkDiffPanel mainPanel, GuiTheme theme, int fileIndex) {
        AbstractDiffPanel panel;
        if (mainPanel.isUnifiedView()) {
            var unifiedPanel = new UnifiedDiffPanel(mainPanel, theme, diffNode);

            // Apply global context mode preference from main panel
            var targetMode = mainPanel.getGlobalShowAllLinesInUnified()
                    ? UnifiedDiffDocument.ContextMode.FULL_CONTEXT
                    : UnifiedDiffDocument.ContextMode.STANDARD_3_LINES;
            unifiedPanel.setContextMode(targetMode);

            panel = unifiedPanel;
        } else {
            var bufferPanel = new BufferDiffPanel(mainPanel, theme);
            bufferPanel.setDiffNode(diffNode);
            panel = bufferPanel;
        }

        mainPanel.cachePanel(fileIndex, panel);
        mainPanel.displayAndRefreshPanel(fileIndex, panel);
    }

}
