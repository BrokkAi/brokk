package io.github.jbellis.brokk.gui.terminal;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.image.BufferedImage;

public class WrappedTextViewEllipsisFitNewTest {

    private FontMetrics getFontMetrics() {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        var g = image.getGraphics();
        var font = new Font("SansSerif", Font.PLAIN, 12);
        g.setFont(font);
        return g.getFontMetrics();
    }

    @Test
    public void fitsWithoutTruncation() {
        var fm = getFontMetrics();
        String text = "Short text";
        int width = fm.stringWidth(text);
        String result = WrappedTextView.addEllipsisToFit(text, fm, width);
        Assertions.assertEquals(text, result);
        Assertions.assertTrue(fm.stringWidth(result) <= width);
    }

    @Test
    public void truncatesAndEndsWithEllipsis() {
        var fm = getFontMetrics();
        String text = "This is a very long line that will need truncation";
        int width = Math.max(10, fm.stringWidth("This is a very"));
        String result = WrappedTextView.addEllipsisToFit(text, fm, width);
        Assertions.assertTrue(result.endsWith("..."), "Result should end with '...'");
        Assertions.assertTrue(fm.stringWidth(result) <= width, "Truncated width must fit");
    }

    @Test
    public void tooSmallForEllipsisReturnsEmpty() {
        var fm = getFontMetrics();
        String text = "data";
        int width = fm.stringWidth(".."); // ensure smaller than "..."
        String result = WrappedTextView.addEllipsisToFit(text, fm, width);
        Assertions.assertEquals("", result);
    }
}
