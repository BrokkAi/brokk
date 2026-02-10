package ai.brokk.gui;

import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Objects;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.LineBorder;

public class BorderUtils {

    private BorderUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Adds a focus-dependent border to a JComponent. The border changes when another specified component gains or loses
     * focus. Unfocused: 1px line with 1px internal padding. Focused: 2px line using the UIManager's focus color.
     * Both states use rounded corners.
     *
     * @param componentToBorder The component whose border will be changed.
     * @param componentToListenFocus The component whose focus events will trigger the border change.
     */
    public static void addFocusBorder(JComponent componentToBorder, JComponent componentToListenFocus) {
        Color borderColor = Objects.requireNonNullElse(UIManager.getColor("Component.borderColor"), Color.GRAY);
        Color focusColor = Objects.requireNonNullElse(UIManager.getColor("Component.focusColor"), Color.BLUE);

        Border unfocusedBorder = BorderFactory.createCompoundBorder(
                new LineBorder(borderColor, 1, true), BorderFactory.createEmptyBorder(1, 1, 1, 1));
        Border focusedBorder = new LineBorder(focusColor, 2, true);

        componentToBorder.setBorder(unfocusedBorder);

        componentToListenFocus.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                componentToBorder.setBorder(focusedBorder);
            }

            @Override
            public void focusLost(FocusEvent e) {
                componentToBorder.setBorder(unfocusedBorder);
            }
        });
    }
}
