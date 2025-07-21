package io.github.jbellis.brokk.gui;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

public class BorderUtils {

    private BorderUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Adds a focus-dependent border to a JComponent.
     * The border changes when another specified component gains or loses focus.
     * Unfocused: 1px gray line with 1px internal padding.
     * Focused: 2px line using the UIManager's focus color.
     *
     * @param componentToBorder The component whose border will be changed.
     * @param componentToListenFocus The component whose focus events will trigger the border change.
     */
    public static void addFocusBorder(JComponent componentToBorder, JComponent componentToListenFocus) {
        Border unfocusedBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                BorderFactory.createEmptyBorder(1, 1, 1, 1)
        );
        Border focusedBorder = BorderFactory.createLineBorder(UIManager.getColor("Component.focusColor"), 2);

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
