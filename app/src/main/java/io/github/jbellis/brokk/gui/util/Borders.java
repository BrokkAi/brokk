package io.github.jbellis.brokk.gui.util;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * Utility for creating modern, flat titled borders.
 *
 * This returns a titled border that draws only a single 1px line at the top
 * (using the Look & Feel separator color) and positions the title above the
 * line. This produces the flat divider-style UI the app is moving toward.
 */
public class Borders {
    public static TitledBorder createTitledBorder(String title) {
        // Use an empty border to avoid drawing any lines, while TitledBorder handles positioning the title.
        var empty = BorderFactory.createEmptyBorder();
        return BorderFactory.createTitledBorder(
                empty,
                title,
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.ABOVE_TOP,
                new Font(Font.DIALOG, Font.BOLD, 12));
    }
}