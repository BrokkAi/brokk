package ai.brokk.gui.dialogs;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.PreviewTabbedPane;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.theme.ThemeTitleBarManager;
import ai.brokk.gui.util.Icons;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.util.GlobalUiSettings;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Map;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

public class PreviewFrame extends JFrame implements ThemeAware {
    private PreviewTabbedPane tabbedPane;
    private final Chrome chrome;
    private GuiTheme guiTheme;

    public PreviewTabbedPane getTabbedPane() {
        return tabbedPane;
    }

    public void setTabbedPane(PreviewTabbedPane newPane) {
        mainContent.remove(this.tabbedPane);
        this.tabbedPane = newPane;
        mainContent.add(tabbedPane, BorderLayout.CENTER);
        mainContent.revalidate();
        mainContent.repaint();
    }

    private final JPanel mainContent;

    public PreviewFrame(Chrome chrome, GuiTheme guiTheme, PreviewTabbedPane initialPane) {
        super("Preview");
        this.chrome = chrome;
        this.guiTheme = guiTheme;
        this.tabbedPane = initialPane;

        // Apply icon, macOS full-window-content, and title bar
        Chrome.applyIcon(this);
        Chrome.maybeApplyMacFullWindowContent(this);
        ThemeTitleBarManager.maybeApplyMacTitleBar(this, "Preview");

        // Create toolbar with dock button
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 2));
        MaterialButton dockButton = new MaterialButton("");
        dockButton.setIcon(Icons.VISIBILITY);
        dockButton.setToolTipText("Dock Preview");
        dockButton.addActionListener(e -> handleRedock());
        toolbar.add(dockButton);

        mainContent = new JPanel(new BorderLayout());
        mainContent.add(toolbar, BorderLayout.NORTH);
        mainContent.add(tabbedPane, BorderLayout.CENTER);

        add(mainContent, BorderLayout.CENTER);

        // Set default close operation to DO_NOTHING so we can handle it
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        // Add window listener to handle close events (X button closes entire frame)
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleFrameClose();
            }
        });

        // Register close tab shortcut for PreviewFrame (defaults to platform accelerator + W)
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

    public void addOrSelectTab(
            String title, JComponent panel, @Nullable ProjectFile fileKey, @Nullable ContextFragment fragmentKey) {
        SwingUtilities.invokeLater(() -> tabbedPane.addOrSelectTab(title, panel, fileKey, fragmentKey));
    }

    /**
     * Closes the currently selected tab if any. Attempts to find an associated ProjectFile key for proper tracking.
     */
    private void closeSelectedTab() {
        int selectedIndex = tabbedPane.getSelectedIndex();
        if (selectedIndex >= 0) {
            Component comp = tabbedPane.getComponentAt(selectedIndex);
            ProjectFile fileKey = null;
            for (Map.Entry<ProjectFile, Component> entry :
                    tabbedPane.getFileToTabMap().entrySet()) {
                if (entry.getValue() == comp) {
                    fileKey = entry.getKey();
                    break;
                }
            }
            tabbedPane.closeTab(comp, fileKey);
        }
    }

    /**
     * Handles window close button (X) - redocks the frame to the main window.
     */
    private void handleFrameClose() {
        handleRedock();
    }

    private void handleRedock() {
        chrome.getRightPanel().redockPreview();
        chrome.clearPreviewFrame();
        dispose();
    }

    /**
     * Finds and refreshes all tabs for the given file.
     */
    public void refreshTabsForFile(ProjectFile file) {
        SwingUtilities.invokeLater(() -> tabbedPane.refreshTabsForFile(file));
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        this.guiTheme = guiTheme;
        // Apply theme to all tabs
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component comp = tabbedPane.getComponentAt(i);
            if (comp instanceof ThemeAware themeAware) {
                themeAware.applyTheme(guiTheme);
            }
        }
        // Update UI components
        SwingUtilities.updateComponentTreeUI(this);
    }
}
