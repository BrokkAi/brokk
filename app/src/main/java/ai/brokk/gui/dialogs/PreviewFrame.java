package ai.brokk.gui.dialogs;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.components.PreviewTabbedPane;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.theme.ThemeTitleBarManager;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.util.GlobalUiSettings;
import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class PreviewFrame extends JFrame implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(PreviewFrame.class);

    private final PreviewTabbedPane tabbedPane;
    private final Chrome chrome;
    private GuiTheme guiTheme;

    public PreviewTabbedPane getTabbedPane() {
        return tabbedPane;
    }

    public PreviewFrame(Chrome chrome, GuiTheme guiTheme) {
        super("Preview");
        this.chrome = chrome;
        this.guiTheme = guiTheme;

        // Apply icon, macOS full-window-content, and title bar
        Chrome.applyIcon(this);
        Chrome.maybeApplyMacFullWindowContent(this);
        ThemeTitleBarManager.maybeApplyMacTitleBar(this, "Preview");

        // Create tabbed pane
        tabbedPane = new PreviewTabbedPane(chrome, guiTheme,
                title -> updateWindowTitle(title),
                this::disposeFrame
        );
        add(tabbedPane, BorderLayout.CENTER);

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

    public void updateTabTitle(JComponent panel, String newTitle) {
        SwingUtilities.invokeLater(() -> tabbedPane.updateTabTitle(panel, newTitle));
    }

    /**
     * Closes the currently selected tab if any. Attempts to find an associated ProjectFile key for proper tracking.
     */
    private void closeSelectedTab() {
        int selectedIndex = tabbedPane.getSelectedIndex();
        if (selectedIndex >= 0) {
            Component comp = tabbedPane.getComponentAt(selectedIndex);
            ProjectFile fileKey = null;
            for (Map.Entry<ProjectFile, Component> entry : tabbedPane.getFileToTabMap().entrySet()) {
                if (entry.getValue() == comp) {
                    fileKey = entry.getKey();
                    break;
                }
            }
            tabbedPane.closeTab(comp, fileKey);
        }
    }

    /**
     * Handles window close button (X) - closes entire frame after confirming all tabs.
     */
    private void handleFrameClose() {
        // Check all tabs for unsaved changes before closing
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component comp = tabbedPane.getComponentAt(i);
            if (comp instanceof PreviewTextPanel textPanel) {
                if (!textPanel.confirmClose()) {
                    // User cancelled - don't close
                    return;
                }
            }
        }
        disposeFrame();
    }

    private void disposeFrame() {
        tabbedPane.clearTracking();
        chrome.clearPreviewTextFrame();
        dispose();
    }

    private void updateWindowTitle(String label) {
        SwingUtilities.invokeLater(() -> {
            if (label.isEmpty()) {
                setTitle("Preview");
            } else {
                setTitle("Preview: " + label);
            }
            ThemeTitleBarManager.maybeApplyMacTitleBar(this, getTitle());
        });
    }

    /**
     * Replaces an existing tab's component with a new one.
     * Used when placeholder content needs to be replaced with a different component type.
     */
    public void replaceTabComponent(JComponent oldComponent, JComponent newComponent, String title) {
        SwingUtilities.invokeLater(() -> tabbedPane.replaceTabComponent(oldComponent, newComponent, title));
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
