package io.github.jbellis.brokk.gui.terminal;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WrappedTextViewBehaviorTest {

    @Test
    public void testCollapsedSingleLineHeight() {
        var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = image.getGraphics();
        try {
            var font = new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12);
            g.setFont(font);
            var fm = g.getFontMetrics();
            int lineHeight = fm.getHeight();

            var view = new WrappedTextView();
            view.setFont(font);
            view.setAvailableWidth(120);
            view.setExpanded(false);
            view.setMaxVisibleLines(1);

            // A line long enough to require truncation with ellipsis when collapsed to 1 line
            String longSingleLine =
                    "ThisIsASingleReallyLongWordThatShouldBeTruncatedWhenSpaceIsLimitedForOneLineOnly";
            view.setText(longSingleLine);

            Assertions.assertEquals(lineHeight, view.getContentHeight(), "Collapsed one-line height should equal one line");
        } finally {
            g.dispose();
        }
    }

    @Test
    public void testExpandedTextViewRendersMoreThanTwoLines() {
        var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = image.getGraphics();
        try {
            var font = new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12);
            g.setFont(font);
            var fm = g.getFontMetrics();
            int lineHeight = fm.getHeight();

            var view = new WrappedTextView();
            view.setFont(font);
            view.setAvailableWidth(200); // Reasonable width that will wrap a long paragraph
            view.setExpanded(true);      // Expanded: should render full height (more than 2 lines if needed)
            view.setMaxVisibleLines(2);

            String longText =
                    "This is a very long line of text that is designed to wrap into multiple lines to properly test the "
                            + "expansion behavior of the WrappedTextView component. It should exceed two lines at the given width.";
            view.setText(longText);

            int contentHeight = view.getContentHeight();
            Assertions.assertTrue(
                    contentHeight > 2 * lineHeight,
                    "Expanded view should render more than two lines");
        } finally {
            g.dispose();
        }
    }
}
