package ai.brokk.gui.dialogs;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Dimension;
import java.awt.Point;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

class ScrollPositionPreserverTest {

    @Test
    void testCalculateValidViewportPosition_WithinBounds() {
        Point saved = new Point(100, 200);
        Dimension viewportSize = new Dimension(800, 600);
        Dimension viewSize = new Dimension(2000, 3000);

        Point result = ScrollPositionPreserver.calculateValidViewportPosition(saved, viewportSize, viewSize);

        assertEquals(100, result.x, "X should remain unchanged when within bounds");
        assertEquals(200, result.y, "Y should remain unchanged when within bounds");
    }

    @Test
    void testCalculateValidViewportPosition_ExceedsMaxX() {
        Point saved = new Point(2000, 100);
        Dimension viewportSize = new Dimension(800, 600);
        Dimension viewSize = new Dimension(2000, 3000);

        Point result = ScrollPositionPreserver.calculateValidViewportPosition(saved, viewportSize, viewSize);

        assertEquals(1200, result.x, "X should be clamped to maxX (viewSize.width - viewportSize.width)");
        assertEquals(100, result.y, "Y should remain unchanged");
    }

    @Test
    void testCalculateValidViewportPosition_ExceedsMaxY() {
        Point saved = new Point(100, 5000);
        Dimension viewportSize = new Dimension(800, 600);
        Dimension viewSize = new Dimension(2000, 3000);

        Point result = ScrollPositionPreserver.calculateValidViewportPosition(saved, viewportSize, viewSize);

        assertEquals(100, result.x, "X should remain unchanged");
        assertEquals(2400, result.y, "Y should be clamped to maxY (viewSize.height - viewportSize.height)");
    }

    @Test
    void testCalculateValidViewportPosition_NegativeValues() {
        Point saved = new Point(-50, -100);
        Dimension viewportSize = new Dimension(800, 600);
        Dimension viewSize = new Dimension(2000, 3000);

        Point result = ScrollPositionPreserver.calculateValidViewportPosition(saved, viewportSize, viewSize);

        assertEquals(0, result.x, "Negative X should be clamped to 0");
        assertEquals(0, result.y, "Negative Y should be clamped to 0");
    }

    @Test
    void testCalculateValidViewportPosition_ViewSmallerThanViewport() {
        Point saved = new Point(100, 200);
        Dimension viewportSize = new Dimension(800, 600);
        Dimension viewSize = new Dimension(400, 300); // Smaller than viewport

        Point result = ScrollPositionPreserver.calculateValidViewportPosition(saved, viewportSize, viewSize);

        assertEquals(0, result.x, "X should be 0 when view is smaller than viewport");
        assertEquals(0, result.y, "Y should be 0 when view is smaller than viewport");
    }

    @Test
    void testCalculateValidViewportPosition_ExactFit() {
        Point saved = new Point(100, 200);
        Dimension viewportSize = new Dimension(800, 600);
        Dimension viewSize = new Dimension(800, 600); // Exact same size

        Point result = ScrollPositionPreserver.calculateValidViewportPosition(saved, viewportSize, viewSize);

        assertEquals(0, result.x, "X should be 0 when sizes match exactly");
        assertEquals(0, result.y, "Y should be 0 when sizes match exactly");
    }

    @Test
    void testPositionRecord_NormalizesNegativeValues() {
        var position = new ScrollPositionPreserver.Position(-5, -10, new Point(50, 100));

        assertEquals(0, position.line(), "Negative line should be normalized to 0");
        assertEquals(0, position.column(), "Negative column should be normalized to 0");
        assertEquals(50, position.viewportPos().x);
        assertEquals(100, position.viewportPos().y);
    }

    @Test
    void testPositionRecord_NullViewportPos() {
        var position = new ScrollPositionPreserver.Position(5, 10, null);

        assertEquals(5, position.line());
        assertEquals(10, position.column());
        assertNotNull(position.viewportPos(), "Null viewport position should be replaced with default");
        assertEquals(0, position.viewportPos().x);
        assertEquals(0, position.viewportPos().y);
    }

    @Test
    void testCaptureWithNullViewport() {
        RSyntaxTextArea textArea = new RSyntaxTextArea("Line 1\nLine 2\nLine 3");
        textArea.setCaretPosition(10); // Somewhere in line 2

        var position = ScrollPositionPreserver.capture(textArea, null);

        assertNotNull(position);
        assertTrue(position.line() >= 0, "Line should be non-negative");
        assertTrue(position.column() >= 0, "Column should be non-negative");
        assertEquals(new Point(0, 0), position.viewportPos(), "Null viewport should result in 0,0 position");
    }

    @Test
    void testCaptureBasicPosition() {
        RSyntaxTextArea textArea = new RSyntaxTextArea("Line 1\nLine 2\nLine 3");
        textArea.setCaretPosition(0); // Start of line 1

        var position = ScrollPositionPreserver.capture(textArea, null);

        assertEquals(0, position.line(), "Caret at position 0 should be line 0");
        assertEquals(0, position.column(), "Caret at position 0 should be column 0");
    }

    @Test
    void testCaptureMiddleOfLine() {
        RSyntaxTextArea textArea = new RSyntaxTextArea("Line 1\nLine 2\nLine 3");
        // "Line 1\n" = 7 chars, so position 10 is in "Line 2" at offset 3 (0-indexed: 7+3=10)
        textArea.setCaretPosition(10);

        var position = ScrollPositionPreserver.capture(textArea, null);

        assertEquals(1, position.line(), "Position 10 should be on line 1 (second line)");
        assertEquals(3, position.column(), "Position should be at column 3 in the line");
    }

    @Test
    void testRestoreWithNullViewport() throws Exception {
        RSyntaxTextArea textArea = new RSyntaxTextArea("Line 1\nLine 2\nLine 3\nLine 4");
        var saved = new ScrollPositionPreserver.Position(2, 3, new Point(0, 0));

        boolean result = ScrollPositionPreserver.restore(textArea, null, saved);

        assertTrue(result, "Restore should succeed even with null viewport");
        // Verify caret was restored
        int expectedPos = textArea.getLineStartOffset(2) + 3;
        assertEquals(expectedPos, textArea.getCaretPosition(), "Caret should be restored to line 2, column 3");
    }

    @Test
    void testRestoreToValidPosition() throws Exception {
        RSyntaxTextArea textArea = new RSyntaxTextArea("Line 1\nLine 2\nLine 3\nLine 4");
        var saved = new ScrollPositionPreserver.Position(1, 2, new Point(0, 0));

        boolean result = ScrollPositionPreserver.restore(textArea, null, saved);

        assertTrue(result);
        int expectedPos = textArea.getLineStartOffset(1) + 2;
        assertEquals(expectedPos, textArea.getCaretPosition());
    }

    @Test
    void testRestoreColumnBeyondLineEnd() throws Exception {
        RSyntaxTextArea textArea = new RSyntaxTextArea("Short\nLine 2");
        // Try to restore to line 0, column 100 (way beyond "Short")
        var saved = new ScrollPositionPreserver.Position(0, 100, new Point(0, 0));

        boolean result = ScrollPositionPreserver.restore(textArea, null, saved);

        assertTrue(result);
        // Should clamp to end of line 0
        int line0End = textArea.getLineEndOffset(0) - 1; // -1 to account for newline
        assertEquals(line0End, textArea.getCaretPosition(), "Should clamp column to line end");
    }

    @Test
    void testRestoreLineBeyondDocumentEnd() {
        RSyntaxTextArea textArea = new RSyntaxTextArea("Line 1\nLine 2");
        // Try to restore to line 10 (document only has 2 lines)
        var saved = new ScrollPositionPreserver.Position(10, 5, new Point(0, 0));

        boolean result = ScrollPositionPreserver.restore(textArea, null, saved);

        assertTrue(result);
        // Caret should not be modified since line 10 doesn't exist
        // The method skips restoration when savedLine >= lineCount
    }

    @Test
    void testCaptureAndRestore_RoundTrip() throws Exception {
        RSyntaxTextArea textArea = new RSyntaxTextArea("Line 1\nLine 2\nLine 3\nLine 4\nLine 5");
        textArea.setCaretPosition(15); // Some position in the text

        // Capture position
        var captured = ScrollPositionPreserver.capture(textArea, null);

        // Change content to something different but with same line count
        textArea.setText("Different 1\nDifferent 2\nDifferent 3\nDifferent 4\nDifferent 5");

        // Restore position
        boolean restored = ScrollPositionPreserver.restore(textArea, null, captured);

        assertTrue(restored);
        // Position should be restored to same line/column
        int newPos = textArea.getCaretPosition();
        int restoredLine = textArea.getLineOfOffset(newPos);
        int restoredCol = newPos - textArea.getLineStartOffset(restoredLine);

        assertEquals(captured.line(), restoredLine, "Line should be preserved");
        // Column might differ if line length changed, but should attempt to restore
    }
}
