package ai.brokk.gui;

import ai.brokk.gui.components.MaterialButton;
import java.awt.Component;
import javax.swing.Icon;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class MaterialOptionPane {

    /**
     * Shows a modal confirmation dialog with MaterialButtons, similar to {@link JOptionPane#showConfirmDialog}.
     * Currently supports {@link JOptionPane#YES_NO_OPTION}.
     */
    public static int showConfirmDialog(
            @Nullable Component parent, String message, String title, int optionType, int messageType) {
        if (optionType != JOptionPane.YES_NO_OPTION) {
            throw new IllegalArgumentException("Only YES_NO_OPTION is currently supported");
        }

        String[] options = {"Yes", "No"};
        return showOptionDialog(parent, message, title, optionType, messageType, null, options, "Yes");
    }

    /**
     * Shows a modal dialog with MaterialButtons for options, similar to {@link JOptionPane#showOptionDialog}.
     *
     * @return the index of the chosen option, or {@link JOptionPane#CLOSED_OPTION} if the user closed the dialog.
     */
    public static int showOptionDialog(
            @Nullable Component parentComponent,
            Object message,
            String title,
            int optionType,
            int messageType,
            @Nullable Icon icon,
            String[] options,
            String initialValue) {

        MaterialButton[] buttons = new MaterialButton[options.length];
        MaterialButton initialButton = null;

        for (int i = 0; i < options.length; i++) {
            String optionText = options[i];
            MaterialButton button = new MaterialButton(optionText);
            buttons[i] = button;

            if (optionText.equals(initialValue)) {
                initialButton = button;
                SwingUtil.applyPrimaryButtonStyle(button);
            }

            button.addActionListener(e -> {
                JOptionPane pane = (JOptionPane) SwingUtilities.getAncestorOfClass(JOptionPane.class, button);
                if (pane != null) {
                    pane.setValue(button);
                }
            });
        }

        return JOptionPane.showOptionDialog(
                parentComponent, message, title, optionType, messageType, icon, buttons, initialButton);
    }
}
