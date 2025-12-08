package ai.brokk.gui.dialogs;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.*;
import java.awt.event.*;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class PreviewFrame extends JFrame implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(PreviewFrame.class);

    private final JTabbedPane tabbedPane;
    private final Chrome chrome;
    private GuiTheme guiTheme;

    // Track tabs by ProjectFile for deduplication (fast path for file-based previews)
    private final Map<ProjectFile, Component> fileToTabMap = new HashMap<>();
    // Track tabs by their associated fragment for fragment-based deduplication
    private final Map<Component, ContextFragment> tabToFragmentMap = new HashMap<>();

    // Navigation history for back/forward support
    private static final int MAX_HISTORY_SIZE = 1000;

    /**
     * Simple navigation entry representing a file + character offset (caret position).
     */
    private static record NavigationEntry(ProjectFile file, int offset) {}

    // Bounded list of navigation entries and the current index into the list.
    // navigationIndex == -1 means the history is empty.
    private final List<NavigationEntry> navigationHistory = new ArrayList<>();
    private int navigationIndex = -1;

    public PreviewFrame(Chrome chrome, GuiTheme guiTheme) {
        super("Preview");
        this.chrome = chrome;
        this.guiTheme = guiTheme;

        // Apply icon, macOS full-window-content, and title bar
        Chrome.applyIcon(this);
        Chrome.applyMacOSFullWindowContent(this);
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

        // Register navigation keyboard shortcuts (Ctrl+Alt+Left/Right for back/forward)
        registerNavigationShortcuts();
    }

    /**
     * Adds a new tab or selects an existing one for the given panel.
     *
     * @param title The title for the tab (without "Preview:" prefix)
     * @param panel The preview component to add
     * @param fileKey Optional ProjectFile for deduplication
     * @param fragmentKey Optional ContextFragment for deduplication via hasSameSource
     */
    public void addOrSelectTab(
            String title, JComponent panel, @Nullable ProjectFile fileKey, @Nullable ContextFragment fragmentKey) {
        SwingUtilities.invokeLater(() -> {
            // Strip "Preview: " prefix if present
            String tabTitle = title.startsWith("Preview: ") ? title.substring(9) : title;

            // Try file-based deduplication first (fast path)
            if (fileKey != null) {
                Component existingTab = fileToTabMap.get(fileKey);
                if (existingTab != null) {
                    int index = tabbedPane.indexOfComponent(existingTab);
                    if (index >= 0) {
                        if (tryReplaceOrSelectTab(index, existingTab, panel, tabTitle, fileKey, fragmentKey)) {
                            return;
                        }
                    }
                }
            }

            // Try fragment-based deduplication using hasSameSource / content equality
            if (fragmentKey != null) {
                for (var entry : tabToFragmentMap.entrySet()) {
                    if (fragmentsMatch(entry.getValue(), fragmentKey)) {
                        Component existingTab = entry.getKey();
                        int index = tabbedPane.indexOfComponent(existingTab);
                        if (index >= 0) {
                            if (tryReplaceOrSelectTab(index, existingTab, panel, tabTitle, fileKey, fragmentKey)) {
                                return;
                            }
                        }
                    }
                }
            }

            // No existing tab found - add new tab
            tabbedPane.addTab(tabTitle, panel);
            int tabIndex = tabbedPane.getTabCount() - 1;

            // Create custom tab component with close button
            JPanel tabComponent = createTabComponent(tabTitle, panel, fileKey);
            tabbedPane.setTabComponentAt(tabIndex, tabComponent);

            // Track the tab
            if (fileKey != null) {
                fileToTabMap.put(fileKey, panel);
            }
            if (fragmentKey != null) {
                tabToFragmentMap.put(panel, fragmentKey);
            }

            // Select the new tab
            tabbedPane.setSelectedIndex(tabIndex);

            // Update window title
            updateWindowTitle();
        });
    }

    /**
     * Checks if two fragments match for deduplication purposes.
     * Uses content equality for StringFragments, hasSameSource for others.
     */
    private boolean fragmentsMatch(ContextFragment existing, ContextFragment candidate) {
        // Content equality for string fragments
        if (existing instanceof ContextFragment.StringFragment sfExisting
                && candidate instanceof ContextFragment.StringFragment sfCandidate) {
            String textA = sfExisting.text().renderNowOrNull();
            String textB = sfCandidate.text().renderNowOrNull();
            return Objects.equals(textA, textB);
        }
        // hasSameSource for all other fragment types
        return existing.hasSameSource(candidate);
    }

    /**
     * Attempts to replace or select an existing tab. Returns true if handled, false to continue searching.
     */
    private boolean tryReplaceOrSelectTab(
            int index,
            Component existingTab,
            JComponent panel,
            String tabTitle,
            @Nullable ProjectFile fileKey,
            @Nullable ContextFragment fragmentKey) {
        // If existing is a PreviewTextPanel, confirm close (unsaved changes)
        if (existingTab instanceof PreviewTextPanel existingPanel) {
            if (!existingPanel.confirmClose()) {
                // User cancelled replacement; just select existing tab
                tabbedPane.setSelectedIndex(index);
                return true;
            }
        }

        // Remove old fragment tracking
        tabToFragmentMap.remove(existingTab);

        // Replace the component with the new panel
        tabbedPane.setComponentAt(index, panel);

        // Rebuild the tab header so close button targets the new panel
        JPanel tabComponent = createTabComponent(tabTitle, panel, fileKey);
        tabbedPane.setTabComponentAt(index, tabComponent);

        // Track the new component
        if (fileKey != null) {
            fileToTabMap.put(fileKey, panel);
        }
        if (fragmentKey != null) {
            tabToFragmentMap.put(panel, fragmentKey);
        }

        // Apply theme to the new panel if applicable (keeps UI consistent)
        if (panel instanceof ThemeAware themeAware) {
            try {
                themeAware.applyTheme(guiTheme);
            } catch (Exception ex) {
                logger.debug("Failed to apply theme to replaced preview component", ex);
            }
        }

        // Select and update window title
        tabbedPane.setSelectedIndex(index);
        updateWindowTitle();
        return true;
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
                if (tabComponent instanceof JPanel tabPanel) {
                    // Find the label in the tab component
                    for (Component c : tabPanel.getComponents()) {
                        if (c instanceof JLabel label) {
                            label.setText(tabTitle);
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
        }
        tabToFragmentMap.remove(panel);

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
        tabToFragmentMap.clear();

        // Notify Chrome to clear reference
        chrome.clearPreviewTextFrame();

        // Dispose the frame
        dispose();
    }

    /**
     * Pushes a location (file + caret character offset) onto the navigation history.
     * Avoids adding consecutive duplicates and truncates forward history if present.
     */
    public void pushLocation(ProjectFile file, int offset) {
        if (file == null) return;

        // Avoid consecutive duplicates
        if (navigationIndex >= 0 && navigationIndex < navigationHistory.size()) {
            NavigationEntry current = navigationHistory.get(navigationIndex);
            if (current.file().equals(file) && current.offset() == offset) {
                return;
            }
        }

        // Truncate forward history if necessary
        if (navigationIndex >= 0 && navigationIndex < navigationHistory.size() - 1) {
            navigationHistory.subList(navigationIndex + 1, navigationHistory.size()).clear();
        }

        // Add entry and clamp size
        navigationHistory.add(new NavigationEntry(file, offset));
        navigationIndex = navigationHistory.size() - 1;

        while (navigationHistory.size() > MAX_HISTORY_SIZE) {
            navigationHistory.remove(0);
            navigationIndex--;
        }
    }

    /**
     * Navigate back in history (Ctrl+Alt+Left).
     */
    public void navigateBack() {
        if (navigationIndex <= 0) return;
        navigationIndex--;
        navigateToEntry(navigationHistory.get(navigationIndex));
    }

    /**
     * Navigate forward in history (Ctrl+Alt+Right).
     */
    public void navigateForward() {
        if (navigationIndex >= navigationHistory.size() - 1) return;
        navigationIndex++;
        navigateToEntry(navigationHistory.get(navigationIndex));
    }

    /**
     * Navigate to a specific history entry. If the file is already open in a tab, select it and
     * move the caret. If not open, attempt to request Chrome to preview the file (best-effort).
     */
    private void navigateToEntry(NavigationEntry entry) {
        SwingUtilities.invokeLater(() -> {
            // If file is already open, select it
            Component existingTab = fileToTabMap.get(entry.file());
            if (existingTab != null) {
                int index = tabbedPane.indexOfComponent(existingTab);
                if (index >= 0) {
                    tabbedPane.setSelectedIndex(index);
                    if (existingTab instanceof PreviewTextPanel textPanel) {
                        textPanel.setCaretPositionAndCenter(entry.offset());
                    }
                    return;
                }
            }

            // Best-effort: ask Chrome to preview the file at the recorded offset.
            // If Chrome doesn't implement previewFile, this call is wrapped so it won't crash the app.
            try {
                chrome.previewFile(entry.file(), entry.offset());
            } catch (Throwable t) {
                // Ignore: not all Chrome implementations may expose previewFile
            }

            // Try again to set the caret after preview opens (may be a no-op if preview didn't open)
            SwingUtilities.invokeLater(() -> {
                Component newTab = fileToTabMap.get(entry.file());
                if (newTab instanceof PreviewTextPanel textPanel) {
                    textPanel.setCaretPositionAndCenter(entry.offset());
                }
            });
        });
    }

    /**
     * Registers Ctrl+Alt+Left and Ctrl+Alt+Right to navigateBack/navigateForward.
     */
    private void registerNavigationShortcuts() {
        JRootPane rootPane = getRootPane();
        if (rootPane == null) return;

        KeyStroke backKey = KeyStroke.getKeyStroke(KeyEvent.VK_LEFT,
                InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(backKey, "navigateBack");
        rootPane.getActionMap().put("navigateBack", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                navigateBack();
            }
        });

        KeyStroke forwardKey = KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT,
                InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(forwardKey, "navigateForward");
        rootPane.getActionMap().put("navigateForward", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                navigateForward();
            }
        });
    }

    private void updateWindowTitle() {
        SwingUtilities.invokeLater(() -> {
            if (tabbedPane.getTabCount() == 0) {
                setTitle("Preview");
            } else {
                Component selected = tabbedPane.getSelectedComponent();
                if (selected != null) {
                    Component tabComponent = tabbedPane.getTabComponentAt(tabbedPane.getSelectedIndex());
                    if (tabComponent instanceof JPanel tabPanel) {
                        for (Component c : tabPanel.getComponents()) {
                            if (c instanceof JLabel label) {
                                setTitle("Preview: " + label.getText());
                                break;
                            }
                        }
                    }
                }
            }
            Chrome.applyTitleBar(this, getTitle());
        });
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

                // Get fragment key from old component
                ContextFragment fragmentKey = tabToFragmentMap.remove(oldComponent);

                // Remove old file tracking
                if (fileKey != null) {
                    fileToTabMap.remove(fileKey);
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
                }
                if (fragmentKey != null) {
                    tabToFragmentMap.put(newComponent, fragmentKey);
                }

                // Apply theme if applicable
                if (newComponent instanceof ThemeAware themeAware) {
                    try {
                        themeAware.applyTheme(guiTheme);
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
                    ProjectFile panelFile = castNonNull(panel.getFile());
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
            if (comp instanceof ThemeAware themeAware) {
                themeAware.applyTheme(guiTheme);
            }
        }
        // Update UI components
        SwingUtilities.updateComponentTreeUI(this);
    }
}
