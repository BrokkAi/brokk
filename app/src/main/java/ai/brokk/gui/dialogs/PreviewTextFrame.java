package ai.brokk.gui.dialogs;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.Icons;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * A frame that hosts multiple PreviewTextPanel instances as tabs.
 * Manages tab lifecycle, close confirmation, and theme support.
 */
public class PreviewTextFrame extends JFrame implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(PreviewTextFrame.class);
    
    private final JTabbedPane tabbedPane;
    private final Chrome chrome;
    private final ContextManager contextManager;
    private GuiTheme guiTheme;
    
    // Track tabs by ProjectFile for deduplication
    private final Map<ProjectFile, Component> fileToTabMap = new HashMap<>();
    // Track tabs by unique ID for non-file panels
    private final Map<String, Component> idToTabMap = new HashMap<>();
    
    public PreviewTextFrame(Chrome chrome, ContextManager contextManager, GuiTheme guiTheme) {
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
        
        // Add window listener to handle close events
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleWindowClose();
            }
        });
        
        // Register ESC key to close current tab
        registerEscapeKey();
    }
    
    /**
     * Adds a new tab or selects an existing one for the given panel.
     * 
     * @param title The title for the tab (without "Preview:" prefix)
     * @param panel The PreviewTextPanel to add
     * @param fileKey Optional ProjectFile for deduplication
     */
    public void addOrSelectTab(String title, PreviewTextPanel panel, @Nullable ProjectFile fileKey) {
        SwingUtilities.invokeLater(() -> {
            // Strip "Preview: " prefix if present
            String tabTitle = title.startsWith("Preview: ") ? title.substring(9) : title;
            
            // Check for existing tab
            if (fileKey != null) {
                Component existingTab = fileToTabMap.get(fileKey);
                if (existingTab != null) {
                    // Select existing tab instead of creating duplicate
                    int index = tabbedPane.indexOfComponent(existingTab);
                    if (index >= 0) {
                        tabbedPane.setSelectedIndex(index);
                        return;
                    }
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
    public void updateTabTitle(PreviewTextPanel panel, String newTitle) {
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
    
    private JPanel createTabComponent(String title, PreviewTextPanel panel, @Nullable ProjectFile fileKey) {
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
    
    private void closeTab(PreviewTextPanel panel, @Nullable ProjectFile fileKey) {
        // Check if panel can close (handles unsaved changes)
        if (!panel.confirmClose()) {
            return;
        }
        
        // Remove from tracking
        if (fileKey != null) {
            fileToTabMap.remove(fileKey);
            chrome.projectFileToPreviewWindow.remove(fileKey);
        } else {
            String uniqueId = generateUniqueId(panel);
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
    
    private void handleWindowClose() {
        // Try to close the current tab
        Component selected = tabbedPane.getSelectedComponent();
        if (selected instanceof PreviewTextPanel panel) {
            // Find the file key if any
            ProjectFile fileKey = null;
            for (Map.Entry<ProjectFile, Component> entry : fileToTabMap.entrySet()) {
                if (entry.getValue() == panel) {
                    fileKey = entry.getKey();
                    break;
                }
            }
            closeTab(panel, fileKey);
        } else if (tabbedPane.getTabCount() == 0) {
            disposeFrame();
        }
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
    
    private String generateUniqueId(PreviewTextPanel panel) {
        // Generate a unique ID based on panel's content or identity
        return Integer.toHexString(System.identityHashCode(panel));
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
