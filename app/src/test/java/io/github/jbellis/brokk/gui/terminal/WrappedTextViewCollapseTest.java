package io.github.jbellis.brokk.gui.terminal;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WrappedTextViewCollapseTest {

    @Test
    public void testCollapsedTextViewRendersTwoLines() {
        var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = image.getGraphics();
        var font = new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12);
        g.setFont(font);
        var fm = g.getFontMetrics();
        int lineHeight = fm.getHeight();

        var view = new WrappedTextView();
        view.setFont(font);
        view.setAvailableWidth(200); // A reasonable width
        view.setExpanded(false);
        view.setMaxVisibleLines(2);

        String longText =
                "This is a very long line of text that is designed to wrap into multiple lines to properly test the "
                        + "collapsing and ellipsis functionality of the WrappedTextView component. It should be long "
                        + "enough to exceed two lines at the given width.";

        view.setText(longText);

        // Verify that the content height is exactly 2 lines high when collapsed.
        Assertions.assertEquals(2 * lineHeight, view.getContentHeight());
    }
}
