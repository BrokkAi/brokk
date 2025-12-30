package ai.brokk.gui.util;

import java.awt.*;
import java.io.File;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/**
 * Utility for showing file choosers with enhanced functionality.
 */
public final class FileChooserUtil {

    private FileChooserUtil() {}

    /**
     * Shows a directory chooser dialog with a "New Folder" button.
     *
     * @param parent the parent frame (may be null)
     * @param title the dialog title
     * @param initialDir the initial directory (may be null for default)
     * @return the selected directory, or null if cancelled
     */
    public static @Nullable File showDirectoryChooserWithNewFolder(
            @Nullable Frame parent, String title, @Nullable File initialDir) {
        var chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setDialogTitle(title);
        if (initialDir != null) {
            chooser.setCurrentDirectory(initialDir);
        }

        var newFolderBtn = createNewFolderButton(chooser);
        addButtonToChooserButtonPanel(chooser, newFolderBtn);

        final int[] result = {JFileChooser.CANCEL_OPTION};
        chooser.addActionListener(e -> {
            if (JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand())) {
                result[0] = JFileChooser.APPROVE_OPTION;
            }
        });

        var dialog = new JDialog(parent, title, true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setContentPane(chooser);
        chooser.addActionListener(e -> dialog.dispose());

        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);

        return result[0] == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }

    private static JButton createNewFolderButton(JFileChooser chooser) {
        var btn = new JButton(Icons.NEW_FOLDER);
        btn.setToolTipText("New Folder");
        // Size will be set in findButtonPanelAndAdd to match existing buttons
        btn.addActionListener(e -> {
            var currentDir = chooser.getCurrentDirectory();
            if (currentDir != null) {
                var name = JOptionPane.showInputDialog(
                        chooser, "Enter folder name:", "New Folder", JOptionPane.PLAIN_MESSAGE);
                if (name != null && !name.isBlank()) {
                    var newFolder = new File(currentDir, name.trim());
                    if (newFolder.mkdir()) {
                        chooser.setCurrentDirectory(newFolder);
                    } else {
                        JOptionPane.showMessageDialog(
                                chooser, "Could not create folder", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        });
        return btn;
    }

    private static void addButtonToChooserButtonPanel(JFileChooser chooser, JButton button) {
        var cancelText = UIManager.getString("FileChooser.cancelButtonText");
        findButtonPanelAndAdd(chooser, button, cancelText);
    }

    private static boolean findButtonPanelAndAdd(Container container, JButton button, @Nullable String cancelText) {
        for (var comp : container.getComponents()) {
            if (comp instanceof JPanel panel) {
                for (var child : panel.getComponents()) {
                    if (child instanceof JButton btn) {
                        if (cancelText != null && cancelText.equals(btn.getText())) {
                            // Make button square with height matching existing buttons
                            int height = btn.getPreferredSize().height;
                            var dim = new Dimension(height, height);
                            button.setPreferredSize(dim);
                            button.setMinimumSize(dim);
                            button.setMaximumSize(dim);
                            panel.add(button, 0);
                            return true;
                        }
                    }
                }
                if (findButtonPanelAndAdd(panel, button, cancelText)) {
                    return true;
                }
            } else if (comp instanceof Container child) {
                if (findButtonPanelAndAdd(child, button, cancelText)) {
                    return true;
                }
            }
        }
        return false;
    }
}
