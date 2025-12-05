package ai.brokk.gui.dialogs;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class PreviewFrame extends JFrame implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(PreviewFrame.class);

    private final JTabbedPane tabbedPane;
    private final Chrome chrome;
    private final ContextManager contextManager;
    private GuiTheme guiTheme;

    // Track tabs by ProjectFile for deduplication
    private final Map<ProjectFile, Component> fileToTabMap = new HashMap<>();
    // Track tabs by unique ID for non-file panels
    private final Map<String, Component> idToTabMap = new HashMap<>();

    public PreviewFrame(Chrome chrome, ContextManager contextManager, GuiTheme guiTheme) {
        super("Preview");
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.guiTheme = guiTheme;

        // Apply icon and title bar
        Chrome.applyIcon(this);
        Chrome.applyTitleBar(this, "Preview");

        // Create tabbed pane
        tabbedPane = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
        add(tabbedPane, BorderLayout.CENTER);

        // Listen for tab selection changes to update window title
        tabbedPane.addChangeListener(e -> updateWindowTitle());

        // Set default close operation to DO_NOTHING so we can handle it
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        // Add window listener to handle close events (X button closes entire frame)
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleFrameClose();
            }
        });

        // Register ESC key to close current tab
        registerEscapeKey();
    }

    /**
     * Adds a new tab or selects an existing one for the given panel.
     *
     * @param title The title for the tab (without "Preview:" prefix)
     * @param panel The preview component to add
     * @param fileKey Optional ProjectFile for deduplication
     */
    public void addOrSelectTab(String title, JComponent panel, @Nullable ProjectFile fileKey) {
        SwingUtilities.invokeLater(() -> {
            // Strip "Preview: " prefix if present
            String tabTitle = title.startsWith("Preview: ") ? title.substring(9) : title;

            // If we have a file key and an existing tab, replace it (placeholder -> real content) or select it.
            if (fileKey != null) {
                Component existingTab = fileToTabMap.get(fileKey);
                if (existingTab != null) {
                    int index = tabbedPane.indexOfComponent(existingTab);
                    if (index >= 0) {
                        // If existing is a PreviewTextPanel, confirm close (unsaved changes)
                        if (existingTab instanceof PreviewTextPanel existingPanel) {
                            if (!existingPanel.confirmClose()) {
                                // User cancelled replacement; just select existing tab
                                tabbedPane.setSelectedIndex(index);
                                return;
                            }
                        }
                        // Replace the component with the new panel
                        tabbedPane.setComponentAt(index, panel);

                        // Rebuild the tab header so close button targets the new panel
                        JPanel tabComponent = createTabComponent(tabTitle, panel, fileKey);
                        tabbedPane.setTabComponentAt(index, tabComponent);

                        // Track the new component for this file
                        fileToTabMap.put(fileKey, panel);

                        // Apply theme to the new panel if applicable (keeps UI consistent)
                        if (panel instanceof ThemeAware && guiTheme != null) {
                            try {
                                ((ThemeAware) panel).applyTheme(guiTheme);
                            } catch (Exception ex) {
                                logger.debug("Failed to apply theme to replaced preview component", ex);
                            }
                        }

                        // Select and update window title
                        tabbedPane.setSelectedIndex(index);
                        updateWindowTitle();
                        return;
                    }
                    // If the existing component isn't found in the tabbed pane (index < 0), fall through to add new tab
                }
            }

            // Check by unique ID for non-file panels
            String uniqueId = generateUniqueId(panel);
            Component existingIdTab = idToTabMap.get(uniqueId);
            if (existingIdTab != null) {
                int index = tabbedPane.indexOfComponent(existingIdTab);
                if (index >= 0) {
                    tabbedPane.setSelectedIndex(index);
                    return;
                }
            }

            // Add new tab
            tabbedPane.addTab(tabTitle, panel);
            int tabIndex = tabbedPane.getTabCount() - 1;

            // Create custom tab component with close button
            JPanel tabComponent = createTabComponent(tabTitle, panel, fileKey);
            tabbedPane.setTabComponentAt(tabIndex, tabComponent);

            // Track the tab
            if (fileKey != null) {
                fileToTabMap.put(fileKey, panel);
            } else {
                idToTabMap.put(uniqueId, panel);
            }

            // Select the new tab
            tabbedPane.setSelectedIndex(tabIndex);

            // Update window title
            updateWindowTitle();
        });
    }

    /**
     * Updates the tab title for asynchronously resolved descriptions.
     */
    public void updateTabTitle(JComponent panel, String newTitle) {
        SwingUtilities.invokeLater(() -> {
            int index = tabbedPane.indexOfComponent(panel);
            if (index >= 0) {
                String tabTitle = newTitle.startsWith("Preview: ") ? newTitle.substring(9) : newTitle;
                Component tabComponent = tabbedPane.getTabComponentAt(index);
                if (tabComponent instanceof JPanel) {
                    // Find the label in the tab component
                    for (Component c : ((JPanel) tabComponent).getComponents()) {
                        if (c instanceof JLabel) {
                            ((JLabel) c).setText(tabTitle);
                            break;
                        }
                    }
                }
                updateWindowTitle();
            }
        });
    }

    private JPanel createTabComponent(String title, JComponent panel, @Nullable ProjectFile fileKey) {
        JPanel tabPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        tabPanel.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        tabPanel.add(titleLabel);

        // Close button
        JButton closeButton = new JButton("×");
        closeButton.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        closeButton.setMargin(new Insets(0, 4, 0, 4));
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.setFocusPainted(false);
        closeButton.addActionListener(e -> closeTab(panel, fileKey));
        tabPanel.add(closeButton);

        return tabPanel;
    }

    private void closeTab(Component panel, @Nullable ProjectFile fileKey) {
        // Check if panel can close (handles unsaved changes for text previews)
        if (panel instanceof PreviewTextPanel textPanel) {
            if (!textPanel.confirmClose()) {
                return;
            }
        }

        // Remove from tracking
        if (fileKey != null) {
            fileToTabMap.remove(fileKey);
            chrome.projectFileToPreviewWindow.remove(fileKey);
        } else {
            String uniqueId = generateUniqueId((JComponent) panel);
            idToTabMap.remove(uniqueId);
        }

        // Remove the tab
        int index = tabbedPane.indexOfComponent(panel);
        if (index >= 0) {
            tabbedPane.remove(index);
        }

        // If no tabs remain, dispose the frame
        if (tabbedPane.getTabCount() == 0) {
            disposeFrame();
        } else {
            updateWindowTitle();
        }
    }

    /**
     * Handles ESC key - closes the current tab only.
     */
    private void handleWindowClose() {
        // Try to close the current tab
        Component selected = tabbedPane.getSelectedComponent();
        if (selected != null) {
            // Find the file key if any
            ProjectFile fileKey = null;
            for (Map.Entry<ProjectFile, Component> entry : fileToTabMap.entrySet()) {
                if (entry.getValue() == selected) {
                    fileKey = entry.getKey();
                    break;
                }
            }
            closeTab(selected, fileKey);
        } else if (tabbedPane.getTabCount() == 0) {
            disposeFrame();
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
        // Clear all tracking
        for (ProjectFile file : fileToTabMap.keySet()) {
            chrome.projectFileToPreviewWindow.remove(file);
        }
        fileToTabMap.clear();
        idToTabMap.clear();

        // Notify Chrome to clear reference
        chrome.clearPreviewTextFrame();

        // Dispose the frame
        dispose();
    }

    private void registerEscapeKey() {
        KeyboardShortcutUtil.registerCloseEscapeShortcut(getRootPane(), this::handleWindowClose);
    }

    private void updateWindowTitle() {
        SwingUtilities.invokeLater(() -> {
            if (tabbedPane.getTabCount() == 0) {
                setTitle("Preview");
            } else {
                Component selected = tabbedPane.getSelectedComponent();
                if (selected != null) {
                    Component tabComponent = tabbedPane.getTabComponentAt(tabbedPane.getSelectedIndex());
                    if (tabComponent instanceof JPanel) {
                        for (Component c : ((JPanel) tabComponent).getComponents()) {
                            if (c instanceof JLabel) {
                                setTitle("Preview: " + ((JLabel) c).getText());
                                break;
                            }
                        }
                    }
                }
            }
            Chrome.applyTitleBar(this, getTitle());
        });
    }

    private String generateUniqueId(JComponent panel) {
        // Generate a unique ID based on panel's identity
        return Integer.toHexString(System.identityHashCode(panel));
    }

    /**
     * Replaces an existing tab's component with a new one.
     * Used when placeholder content needs to be replaced with a different component type.
     */
    public void replaceTabComponent(JComponent oldComponent, JComponent newComponent, String title) {
        SwingUtilities.invokeLater(() -> {
            int index = tabbedPane.indexOfComponent(oldComponent);
            if (index >= 0) {
                // Find the file key for the old component
                ProjectFile fileKey = null;
                for (Map.Entry<ProjectFile, Component> entry : fileToTabMap.entrySet()) {
                    if (entry.getValue() == oldComponent) {
                        fileKey = entry.getKey();
                        break;
                    }
                }

                // Remove old tracking
                if (fileKey != null) {
                    fileToTabMap.remove(fileKey);
                } else {
                    String oldId = generateUniqueId(oldComponent);
                    idToTabMap.remove(oldId);
                }

                // Replace the component
                tabbedPane.setComponentAt(index, newComponent);

                // Strip "Preview: " prefix for tab title
                String tabTitle = title.startsWith("Preview: ") ? title.substring(9) : title;

                // Rebuild tab header
                JPanel tabComponent = createTabComponent(tabTitle, newComponent, fileKey);
                tabbedPane.setTabComponentAt(index, tabComponent);

                // Track the new component
                if (fileKey != null) {
                    fileToTabMap.put(fileKey, newComponent);
                } else {
                    idToTabMap.put(generateUniqueId(newComponent), newComponent);
                }

                // Apply theme if applicable
                if (newComponent instanceof ThemeAware && guiTheme != null) {
                    try {
                        ((ThemeAware) newComponent).applyTheme(guiTheme);
                    } catch (Exception ex) {
                        logger.debug("Failed to apply theme to replaced preview component", ex);
                    }
                }

                // Select and update window title
                tabbedPane.setSelectedIndex(index);
                updateWindowTitle();
            }
        });
    }

    /**
     * Finds and refreshes all tabs for the given file.
     */
    public void refreshTabsForFile(ProjectFile file) {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                Component comp = tabbedPane.getComponentAt(i);
                if (comp instanceof PreviewTextPanel panel) {
                    ProjectFile panelFile = panel.getFile();
                    if (file.equals(panelFile)) {
                        panel.refreshFromDisk();
                    }
                } else if (comp instanceof PreviewImagePanel imagePanel) {
                    var bf = imagePanel.getFile();
                    if (bf instanceof ProjectFile pf && file.equals(pf)) {
                        imagePanel.refreshFromDisk();
                    }
                }
            }
        });
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        this.guiTheme = guiTheme;
        // Apply theme to all tabs
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component comp = tabbedPane.getComponentAt(i);
            if (comp instanceof ThemeAware) {
                ((ThemeAware) comp).applyTheme(guiTheme);
            }
        }
        // Update UI components
        SwingUtilities.updateComponentTreeUI(this);
    }
}
