package ai.brokk.gui.dialogs;

import java.awt.Dimension;
import java.awt.Point;
import javax.swing.JViewport;
import javax.swing.text.BadLocationException;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.jetbrains.annotations.Nullable;

/**
 * Helper class for preserving and restoring scroll positions in text editors.
 * Uses line-based positioning for better stability when content changes.
 */
public class ScrollPositionPreserver {

    /**
     * Represents a saved scroll position with line/column caret position and viewport scroll.
     * The viewportPos parameter accepts null and normalizes it to Point(0, 0).
     */
    public record Position(int line, int column, Point viewportPos) {
        public Position(int line, int column, @Nullable Point viewportPos) {
            this.line = line < 0 ? 0 : line;
            this.column = column < 0 ? 0 : column;
            // Normalize null to default point for safety
            this.viewportPos = viewportPos != null ? viewportPos : new Point(0, 0);
        }
    }

    /**
     * Captures the current scroll position from a text area and viewport.
     * Returns a Position with line 0, column 0 if capture fails.
     *
     * @param textArea The text area to capture position from
     * @param viewport The viewport containing the text area
     * @return The captured position, or default position (0, 0, 0,0) if capture fails
     */
    public static Position capture(RSyntaxTextArea textArea, @Nullable JViewport viewport) {
        int line = 0;
        int column = 0;
        Point viewportPos = (viewport != null) ? viewport.getViewPosition() : new Point(0, 0);

        try {
            int caretPos = textArea.getCaretPosition();
            line = textArea.getLineOfOffset(caretPos);
            int lineStart = textArea.getLineStartOffset(line);
            column = caretPos - lineStart;
        } catch (BadLocationException e) {
            // Return default position
        }

        return new Position(line, column, new Point(viewportPos));
    }

    /**
     * Calculates the viewport position to restore, validating it's within bounds.
     *
     * @param savedViewportPos The saved viewport position
     * @param viewportSize The current viewport size
     * @param viewSize The current view (content) size
     * @return A valid viewport position clamped to bounds
     */
    public static Point calculateValidViewportPosition(
            Point savedViewportPos, Dimension viewportSize, Dimension viewSize) {
        int maxX = Math.max(0, viewSize.width - viewportSize.width);
        int maxY = Math.max(0, viewSize.height - viewportSize.height);
        int x = Math.min(Math.max(0, savedViewportPos.x), maxX);
        int y = Math.min(Math.max(0, savedViewportPos.y), maxY);
        return new Point(x, y);
    }

    /**
     * Restores a saved position to a text area, clamping to valid ranges.
     *
     * @param textArea The text area to restore position in
     * @param viewport The viewport to restore scroll position in
     * @param saved The saved position to restore
     * @return true if restoration succeeded, false otherwise
     */
    public static boolean restore(RSyntaxTextArea textArea, @Nullable JViewport viewport, Position saved) {
        try {
            // Restore caret position based on line number
            int lineCount = textArea.getLineCount();
            if (saved.line < lineCount) {
                int lineStart = textArea.getLineStartOffset(saved.line);
                int lineEnd = textArea.getLineEndOffset(saved.line);
                int targetPos = Math.min(lineStart + saved.column, Math.max(0, lineEnd - 1));
                textArea.setCaretPosition(Math.max(0, targetPos));
            }

            // Restore viewport position - validate bounds
            if (viewport != null && viewport.getView() != null) {
                Dimension viewportSize = viewport.getExtentSize();
                Dimension viewSize = viewport.getView().getSize();
                Point validPos = calculateValidViewportPosition(saved.viewportPos, viewportSize, viewSize);
                viewport.setViewPosition(validPos);
            }

            return true;
        } catch (BadLocationException e) {
            return false;
        }
    }
}
