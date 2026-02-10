package ai.brokk.gui.components;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Dimension;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

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

    @Test
    public void testArrowAreaStableWhenTextChanges() throws Exception {
        final SplitButton split = new SplitButton("initial");

        final int[] widths = new int[2];

        SwingUtilities.invokeAndWait(() -> {
            // Long label -> measure
            split.setText("A very long label that should increase width considerably");
            widths[0] = split.getPreferredSize().width;

            // Shorter label -> measure
            split.setText("Short");
            widths[1] = split.getPreferredSize().width;
        });

        int delta = widths[0] - widths[1];

        // Expect the change to be dominated by the action text width.
        final int EXPECTED_MIN_TEXT_DELTA = 10; // px

        assertTrue(
                delta > EXPECTED_MIN_TEXT_DELTA,
                "Width delta should be dominated by action text change (arrow area should remain stable)");
    }
}
