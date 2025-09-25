package io.github.jbellis.brokk.difftool.ui.unified;

import java.awt.Rectangle;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * Handles navigation between hunks in a unified diff view. This class manages hunk-based navigation similar to how
 * side-by-side panels navigate between changes.
 */
public class UnifiedDiffNavigator {
    private static final Logger logger = LogManager.getLogger(UnifiedDiffNavigator.class);

    private final RSyntaxTextArea textArea;
    private List<Integer> hunkStartLines = List.of(); // Cached hunk positions
    private int currentHunkIndex = 0;

    public UnifiedDiffNavigator(String plainTextContent, RSyntaxTextArea textArea) {
        this.textArea = textArea;
        refreshHunkPositions();
    }

    /**
     * Refresh the cached hunk positions from the text area content. Should be called when the content changes (e.g.,
     * context mode switch).
     */
    public void refreshHunkPositions() {
        var hunkLines = new java.util.ArrayList<Integer>();

        try {
            var document = textArea.getDocument();
            int lineCount = textArea.getLineCount();

            for (int i = 0; i < lineCount; i++) {
                int lineStart = textArea.getLineStartOffset(i);
                int lineEnd = textArea.getLineEndOffset(i);
                String lineText = document.getText(lineStart, lineEnd - lineStart);

                if (lineText.startsWith("@@")) {
                    hunkLines.add(i);
                }
            }
        } catch (BadLocationException e) {
            logger.warn("Failed to refresh hunk positions", e);
        }

        this.hunkStartLines = hunkLines;

        // Ensure current hunk index is still valid
        if (currentHunkIndex >= hunkStartLines.size()) {
            currentHunkIndex = Math.max(0, hunkStartLines.size() - 1);
        }

        logger.debug("Refreshed hunk positions: {} hunks found", hunkStartLines.size());
    }

    /** Navigate to the next hunk. */
    public void navigateToNextHunk() {
        if (canNavigateToNextHunk()) {
            currentHunkIndex++;
            navigateToCurrentHunk();
            logger.debug("Navigated to next hunk: {}/{}", currentHunkIndex + 1, hunkStartLines.size());
        } else {
            logger.debug("Cannot navigate to next hunk: at last hunk");
        }
    }

    /** Navigate to the previous hunk. */
    public void navigateToPreviousHunk() {
        if (canNavigateToPreviousHunk()) {
            currentHunkIndex--;
            navigateToCurrentHunk();
            logger.debug("Navigated to previous hunk: {}/{}", currentHunkIndex + 1, hunkStartLines.size());
        } else {
            logger.debug("Cannot navigate to previous hunk: at first hunk");
        }
    }

    /** Navigate to the first hunk. */
    public void goToFirstHunk() {
        if (!hunkStartLines.isEmpty()) {
            currentHunkIndex = 0;
            navigateToCurrentHunk();
            logger.debug("Navigated to first hunk");
        }
    }

    /** Navigate to the last hunk. */
    public void goToLastHunk() {
        if (!hunkStartLines.isEmpty()) {
            currentHunkIndex = hunkStartLines.size() - 1;
            navigateToCurrentHunk();
            logger.debug("Navigated to last hunk");
        }
    }

    /**
     * Navigate to a specific hunk by index.
     *
     * @param hunkIndex 0-based hunk index
     */
    public void navigateToHunk(int hunkIndex) {
        if (hunkIndex >= 0 && hunkIndex < hunkStartLines.size()) {
            currentHunkIndex = hunkIndex;
            navigateToCurrentHunk();
            logger.debug("Navigated to hunk {}/{}", hunkIndex + 1, hunkStartLines.size());
        } else {
            logger.warn("Invalid hunk index: {} (valid range: 0-{})", hunkIndex, hunkStartLines.size() - 1);
        }
    }

    /** Check if navigation to next hunk is possible. */
    public boolean canNavigateToNextHunk() {
        return !hunkStartLines.isEmpty() && currentHunkIndex < hunkStartLines.size() - 1;
    }

    /** Check if navigation to previous hunk is possible. */
    public boolean canNavigateToPreviousHunk() {
        return !hunkStartLines.isEmpty() && currentHunkIndex > 0;
    }

    /** Check if currently at the first hunk. */
    public boolean isAtFirstHunk() {
        return hunkStartLines.isEmpty() || currentHunkIndex == 0;
    }

    /** Check if currently at the last hunk. */
    public boolean isAtLastHunk() {
        return hunkStartLines.isEmpty() || currentHunkIndex == hunkStartLines.size() - 1;
    }

    /** Get the current hunk index. */
    public int getCurrentHunkIndex() {
        return currentHunkIndex;
    }

    /** Get the total number of hunks. */
    public int getHunkCount() {
        return hunkStartLines.size();
    }

    /** Get the line number of the current hunk. */
    public int getCurrentHunkLine() {
        if (currentHunkIndex >= 0 && currentHunkIndex < hunkStartLines.size()) {
            return hunkStartLines.get(currentHunkIndex);
        }
        return -1;
    }

    /**
     * Find the hunk that contains the given line number.
     *
     * @param lineNumber 0-based line number
     * @return Hunk index, or -1 if not found
     */
    public int findHunkForLine(int lineNumber) {
        for (int i = 0; i < hunkStartLines.size(); i++) {
            var hunkStart = hunkStartLines.get(i);
            var hunkEnd = (i + 1 < hunkStartLines.size()) ? hunkStartLines.get(i + 1) : textArea.getLineCount();

            if (lineNumber >= hunkStart && lineNumber < hunkEnd) {
                return i;
            }
        }
        return -1;
    }

    /** Update current hunk based on caret position. */
    public void updateCurrentHunkFromCaret() {
        try {
            int caretPosition = textArea.getCaretPosition();
            int lineNumber = textArea.getLineOfOffset(caretPosition);

            int hunkIndex = findHunkForLine(lineNumber);
            if (hunkIndex >= 0) {
                currentHunkIndex = hunkIndex;
                logger.debug("Updated current hunk to {} based on caret position", currentHunkIndex);
            }
        } catch (BadLocationException e) {
            logger.warn("Failed to update current hunk from caret position", e);
        }
    }

    /** Navigate to the current hunk by positioning the caret and scrolling. */
    private void navigateToCurrentHunk() {
        if (currentHunkIndex < 0 || currentHunkIndex >= hunkStartLines.size()) {
            logger.warn("Invalid current hunk index: {}", currentHunkIndex);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                var lineNumber = hunkStartLines.get(currentHunkIndex);
                var offset = textArea.getLineStartOffset(lineNumber);

                // Set caret position
                textArea.setCaretPosition(offset);
                textArea.requestFocusInWindow();

                // Scroll to make the line visible
                var rect = textArea.modelToView2D(offset).getBounds();
                if (rect != null) {
                    // Expand rectangle to ensure some context is visible
                    var expandedRect = new Rectangle(rect.x, Math.max(0, rect.y - 50), rect.width, rect.height + 100);
                    textArea.scrollRectToVisible(expandedRect);
                }

                logger.trace("Positioned caret at hunk {} (line {}, offset {})", currentHunkIndex, lineNumber, offset);

            } catch (BadLocationException e) {
                logger.error(
                        "Failed to navigate to hunk {} at line {}",
                        currentHunkIndex,
                        hunkStartLines.get(currentHunkIndex),
                        e);
            }
        });
    }

    /** Get a summary of navigation state for debugging. */
    public String getNavigationInfo() {
        return String.format("Hunk %d/%d (line %d)", currentHunkIndex + 1, hunkStartLines.size(), getCurrentHunkLine());
    }
}
