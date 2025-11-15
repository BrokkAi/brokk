package ai.brokk.gui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WrappedTextViewEllipsisTest {

    private FontMetrics fm;

    @BeforeEach
    void setUp() {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
            fm = g2.getFontMetrics();
        } finally {
            g2.dispose();
        }
    }

    @Test
    void returnFullStringWhenWidthSufficient() {
        String input = "Hello World";
        int fullWidth = fm.stringWidth(input);

        String result = WrappedTextView.addEllipsisToFit(input, fm, fullWidth);

        assertEquals(input, result);
    }

    @Test
    void returnFullStringWhenWidthExceedsNeeded() {
        String input = "Test";
        int fullWidth = fm.stringWidth(input);

        String result = WrappedTextView.addEllipsisToFit(input, fm, fullWidth + 100);

        assertEquals(input, result);
    }

    @Test
    void returnTruncatedWithEllipsisWhenWidthInsufficient() {
        String input = "This is a very long string that needs truncation";
        String ellipsis = ".....";
        int ellipsisWidth = fm.stringWidth(ellipsis);
        int fullWidth = fm.stringWidth(input);
        int availableWidth = fullWidth / 2;

        String result = WrappedTextView.addEllipsisToFit(input, fm, availableWidth);

        assertTrue(result.endsWith(ellipsis), "Result should end with ellipsis");
        assertTrue(result.length() < input.length(), "Result should be shorter than input");
        int resultWidth = fm.stringWidth(result);
        assertTrue(resultWidth <= availableWidth, "Result width should not exceed available width");
        assertTrue(resultWidth > ellipsisWidth, "Result should contain some prefix before ellipsis");
    }

    @Test
    void returnEmptyWhenWidthSmallerThanEllipsis() {
        String input = "Any string";
        String ellipsis = ".....";
        int ellipsisWidth = fm.stringWidth(ellipsis);

        String result = WrappedTextView.addEllipsisToFit(input, fm, ellipsisWidth - 1);

        assertEquals("", result);
    }

    @Test
    void returnEmptyWhenWidthZero() {
        String input = "Test";

        String result = WrappedTextView.addEllipsisToFit(input, fm, 0);

        assertEquals("", result);
    }

    @Test
    void returnEmptyWhenWidthNegative() {
        String input = "Test";

        String result = WrappedTextView.addEllipsisToFit(input, fm, -10);

        assertEquals("", result);
    }

    @Test
    void handleEmptyInputString() {
        String input = "";
        int width = 100;

        String result = WrappedTextView.addEllipsisToFit(input, fm, width);

        assertEquals("", result);
    }
}
