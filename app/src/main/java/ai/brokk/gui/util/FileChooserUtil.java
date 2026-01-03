package ai.brokk.gui.util;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
     * @param parent the parent component (may be null)
     * @param title the dialog title
     * @param initialDir the initial directory (may be null for default)
     * @return the selected directory, or null if cancelled
     */
    public static @Nullable File showDirectoryChooserWithNewFolder(
            @Nullable Component parent, String title, @Nullable File initialDir) {
        var chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setDialogTitle(title);
        chooser.setApproveButtonText("Open");
        if (initialDir != null) {
            chooser.setCurrentDirectory(initialDir);
        }

        // Use accessory panel for New Folder button (public API, no internal hierarchy dependency)
        chooser.setAccessory(createNewFolderAccessory(chooser));

        int result = chooser.showOpenDialog(castNonNull(parent));
        return result == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }

    private static JPanel createNewFolderAccessory(JFileChooser chooser) {
        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        var btn = new JButton(Icons.NEW_FOLDER);
        btn.setToolTipText("New Folder");
        btn.addActionListener(e -> {
            var currentDir = chooser.getCurrentDirectory();
            if (currentDir != null) {
                var name = JOptionPane.showInputDialog(
                        chooser, "Enter folder name:", "New Folder", JOptionPane.PLAIN_MESSAGE);
                if (name != null && !name.isBlank()) {
                    var newFolder = new File(currentDir, name.trim());
                    try {
                        Files.createDirectories(newFolder.toPath());
                        chooser.setCurrentDirectory(newFolder);
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(
                                chooser,
                                "Could not create folder: " + ex.getMessage(),
                                "Error",
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        });

        panel.add(btn, BorderLayout.NORTH);
        return panel;
    }
}
