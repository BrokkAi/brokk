package ai.brokk.gui.dialogs;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.components.PreviewTabbedPane;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.util.Icons;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.util.GlobalUiSettings;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

public class PreviewFrame extends DetachableTabFrame {
    private PreviewTabbedPane tabbedPane;

    public PreviewFrame(Chrome chrome, PreviewTabbedPane initialPane) {
        super("Preview", initialPane, () -> {
            chrome.getRightPanel().redockPreview();
            chrome.clearPreviewFrame();
        });
        this.tabbedPane = initialPane;

        // Register close tab shortcut for PreviewFrame (defaults to platform accelerator + W)
        // This overrides the DetachableTabFrame default behavior which redocks the whole frame
        KeyStroke closeTabKeyStroke = GlobalUiSettings.getKeybinding(
                "global.closeTab", KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_W));
        var root = this.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(closeTabKeyStroke, "closePreviewTab");
        root.getActionMap().put("closePreviewTab", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                closeSelectedTab();
            }
        });
    }

    public void setTabbedPane(PreviewTabbedPane newPane) {
        setContentComponent(newPane);
        this.tabbedPane = newPane;
    }

    public void addOrSelectTab(
            String title, JComponent panel, @Nullable ProjectFile fileKey, @Nullable ContextFragment fragmentKey) {
        SwingUtilities.invokeLater(() -> tabbedPane.addOrSelectTab(title, panel, fileKey, fragmentKey));
    }

    /**
     * Closes the currently selected tab.
     */
    private void closeSelectedTab() {
        tabbedPane.closeSelectedTab();
    }

    /**
     * Finds and refreshes all tabs for the given file.
     */
    public void refreshTabsForFile(ProjectFile file) {
        SwingUtilities.invokeLater(() -> tabbedPane.refreshTabsForFile(file));
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        tabbedPane.applyTheme(guiTheme);
        super.applyTheme(guiTheme);
    }
}
