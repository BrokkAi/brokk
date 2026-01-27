package ai.brokk.gui;

import ai.brokk.gui.components.MaterialButton;
import java.awt.Component;
import java.util.List;
import javax.swing.Icon;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class MaterialOptionPane {

    /**
     * Shows a modal confirmation dialog with MaterialButtons, similar to {@link JOptionPane#showConfirmDialog}.
     */
    public static int showConfirmDialog(
            @Nullable Component parent, String message, String title, int optionType, int messageType) {
        ConfirmDialogSpec spec = confirmDialogSpec(optionType);
        int index = showOptionDialog(
                parent,
                message,
                title,
                optionType,
                messageType,
                null,
                spec.options().toArray(new String[0]),
                spec.initial());

        if (index == JOptionPane.CLOSED_OPTION) {
            return JOptionPane.CLOSED_OPTION;
        }

        return spec.returnValueForIndex(index);
    }

    private static ConfirmDialogSpec confirmDialogSpec(int optionType) {
        return switch (optionType) {
            case JOptionPane.YES_NO_OPTION ->
                new ConfirmDialogSpec(
                        List.of("Yes", "No"), "Yes", List.of(JOptionPane.YES_OPTION, JOptionPane.NO_OPTION));
            case JOptionPane.YES_NO_CANCEL_OPTION ->
                new ConfirmDialogSpec(
                        List.of("Yes", "No", "Cancel"),
                        "Yes",
                        List.of(JOptionPane.YES_OPTION, JOptionPane.NO_OPTION, JOptionPane.CANCEL_OPTION));
            case JOptionPane.OK_CANCEL_OPTION ->
                new ConfirmDialogSpec(
                        List.of("OK", "Cancel"), "OK", List.of(JOptionPane.OK_OPTION, JOptionPane.CANCEL_OPTION));
            case JOptionPane.DEFAULT_OPTION ->
                new ConfirmDialogSpec(List.of("OK"), "OK", List.of(JOptionPane.OK_OPTION));
            default -> throw new IllegalArgumentException("Unsupported optionType: " + optionType);
        };
    }

    private record ConfirmDialogSpec(List<String> options, String initial, List<Integer> returnValues) {
        int returnValueForIndex(int index) {
            if (index < 0 || index >= returnValues.size()) {
                return JOptionPane.CLOSED_OPTION;
            }
            return returnValues.get(index);
        }
    }

    /**
     * Shows a modal dialog with MaterialButtons for options, similar to {@link JOptionPane#showOptionDialog}.
     *
     * @return the index of the chosen option, or {@link JOptionPane#CLOSED_OPTION} if the user closed the dialog.
     */
    /**
     * Shows a modal dialog with a message and a Cancel button.
     * The dialog stays open until the provided future completes or the user clicks Cancel.
     * <p>
     * NOTE: This method blocks the calling thread until the dialog is closed. If called on the EDT,
     * it triggers a secondary event pump (standard Swing modal behavior).
     *
     * @return true if the future completed successfully, false if the user cancelled.
     */
    public static boolean showBlockingProgressDialog(
            @Nullable Component parent,
            String message,
            String title,
            java.util.concurrent.CompletableFuture<?> future) {
        MaterialButton cancelButton = new MaterialButton("Cancel");
        MaterialButton[] buttons = {cancelButton};

        JOptionPane pane = new JOptionPane(
                message, JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, buttons, cancelButton);
        javax.swing.JDialog dialog = pane.createDialog(parent, title);

        cancelButton.addActionListener(e -> {
            future.cancel(true);
            dialog.dispose();
        });

        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                future.cancel(true);
            }
        });

        // Close dialog when future completes
        future.whenComplete((res, err) -> SwingUtil.runOnEdt(dialog::dispose));

        dialog.setVisible(true);

        return future.isDone() && !future.isCancelled();
    }

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
