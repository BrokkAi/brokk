package ai.brokk.gui.terminal;

import java.awt.Font;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WrappedTextViewEllipsisFitTest {

    @Test
    public void testEllipsisFit() {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        var g = image.getGraphics();
        try {
            var font = new Font("SansSerif", Font.PLAIN, 12);
            g.setFont(font);
            var fm = g.getFontMetrics();

            // Baseline strings and metrics
            String ellipsis = ".....";
            int ellipsisWidth = fm.stringWidth(ellipsis);

            String shortLine = "Hello";
            int shortWidth = fm.stringWidth(shortLine);

            String longLine =
                    "This is a somewhat longer line of text that will require truncation for narrower widths.";

            // 1) A line that fits is returned unchanged.
            String fitsExactly = WrappedTextView.addEllipsisToFit(shortLine, fm, shortWidth);
            Assertions.assertEquals(shortLine, fitsExactly, "Fitting line should be unchanged");

            String fitsLarger = WrappedTextView.addEllipsisToFit(shortLine, fm, shortWidth + 20);
            Assertions.assertEquals(shortLine, fitsLarger, "Line should remain unchanged when width is larger");

            // 2) A line that does not fit is truncated and ends with "..."
            int longWidth = fm.stringWidth(longLine);
            int halfWidth =
                    Math.max(ellipsisWidth + 1, longWidth / 2); // ensure smaller than full width, larger than ellipsis
            String truncated = WrappedTextView.addEllipsisToFit(longLine, fm, halfWidth);
            Assertions.assertNotEquals(longLine, truncated, "Non-fitting line should be truncated");
            Assertions.assertTrue(truncated.endsWith(ellipsis), "Truncated line should end with ellipsis");

            // 3) The truncated line with the ellipsis fits within the specified width
            Assertions.assertTrue(
                    fm.stringWidth(truncated) <= halfWidth,
                    "Truncated-with-ellipsis string should fit the provided width");

            // 4) Edge cases

            // 4a) available width is zero
            String zeroWidth = WrappedTextView.addEllipsisToFit(longLine, fm, 0);
            Assertions.assertEquals("", zeroWidth, "Zero width should return empty string");

            // 4b) available width smaller than ellipsis
            String tooSmall = WrappedTextView.addEllipsisToFit(longLine, fm, ellipsisWidth - 1);
            Assertions.assertEquals("", tooSmall, "Width smaller than ellipsis should return empty string");

            // 4c) available width exactly equal to ellipsis width
            String exactlyEllipsis = WrappedTextView.addEllipsisToFit(longLine, fm, ellipsisWidth);
            Assertions.assertEquals(ellipsis, exactlyEllipsis, "Exact ellipsis width should return only ellipsis");

            // 4d) available width just a bit larger than ellipsis width: still may only fit ellipsis or a tiny prefix
            int justAboveEllipsis = ellipsisWidth + 2;
            String aboveEllipsis = WrappedTextView.addEllipsisToFit(longLine, fm, justAboveEllipsis);
            Assertions.assertTrue(
                    aboveEllipsis.equals(ellipsis) || aboveEllipsis.endsWith(ellipsis),
                    "Width slightly above ellipsis should return ellipsis or very short prefix plus ellipsis");
            Assertions.assertTrue(
                    fm.stringWidth(aboveEllipsis) <= justAboveEllipsis, "Result must fit within the provided width");
        } finally {
            g.dispose();
        }
    }
}
