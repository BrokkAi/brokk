package ai.brokk.gui.dialogs;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.util.GlobalUiSettings;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

public class PreviewFrame extends DetachableTabFrame {
    public PreviewFrame(Chrome chrome, JComponent initialContent) {
        super("Preview", initialContent, () -> {
            chrome.getRightPanel().redockPreview();
            chrome.clearPreviewFrame();
        });
    }

    public void setContent(JComponent newContent) {
        setContentComponent(newContent);
    }

    /**
     * Refreshes the preview for the given file if it matches.
     */
    public void refreshForFile(ProjectFile file) {
        SwingUtilities.invokeLater(() -> {
            Component content = getContentPane();
            if (content instanceof JRootPane rp) {
                content = rp.getContentPane();
            }
            if (content instanceof Container container && container.getComponentCount() > 0) {
                Component comp = container.getComponent(0);
                if (comp instanceof ai.brokk.gui.dialogs.PreviewTextPanel panel) {
                    if (file.equals(panel.getFile())) panel.refreshFromDisk();
                } else if (comp instanceof ai.brokk.gui.dialogs.PreviewImagePanel imagePanel) {
                    if (file.equals(imagePanel.getFile())) imagePanel.refreshFromDisk();
                }
            }
        });
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        super.applyTheme(guiTheme);
    }
}
