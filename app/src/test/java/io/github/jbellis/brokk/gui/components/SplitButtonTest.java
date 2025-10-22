package io.github.jbellis.brokk.gui.components;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.*;

public class SplitButtonTest {

    @Test
    public void testPreferredWidthAdjustsWithTextChanges() throws Exception {
        final SplitButton split = new SplitButton("initial");

        final int[] widths = new int[3];

        SwingUtilities.invokeAndWait(() -> {
            // Long label -> measure
            split.setText("A very long label that should increase width considerably");
            Dimension d1 = split.getPreferredSize();
            widths[0] = d1.width;

            // Shorter label -> measure
            split.setText("Short");
            Dimension d2 = split.getPreferredSize();
            widths[1] = d2.width;

            // Back to long label -> measure again
            split.setText("A very long label that should increase width considerably");
            Dimension d3 = split.getPreferredSize();
            widths[2] = d3.width;
        });

        assertTrue(widths[0] > widths[1], "Preferred width should decrease when the label is shortened");
        assertTrue(widths[2] > widths[1], "Preferred width should increase when the label is lengthened again");
    }
}
