package ai.brokk.gui.dialogs;

import ai.brokk.IProject;
import ai.brokk.MainProject;
import ai.brokk.MainProject.DataRetentionPolicy;
import ai.brokk.github.BackgroundGitHubAuth;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Optional;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SettingsDialog extends JDialog implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(SettingsDialog.class);

    public static final String GITHUB_SETTINGS_TAB_NAME = "GitHub";

    private final Chrome chrome;
    private final JTabbedPane tabbedPane;
    private SettingsGlobalPanel globalSettingsPanel;
    private SettingsProjectPanel projectSettingsPanel;

    private final MaterialButton okButton;
    private final MaterialButton cancelButton;
    private final MaterialButton applyButton;

    private boolean proxySettingsChanged = false; // Track if proxy needs restart
    private boolean uiScaleSettingsChanged = false; // Track if UI scale needs restart

    public SettingsDialog(Frame owner, Chrome chrome) {
        this(owner, chrome, Optional.empty());
    }

    private SettingsDialog(Frame owner, Chrome chrome, Optional<String> generatedStyleGuide) {
        super(owner, "Settings", true);
        this.chrome = chrome;
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setSize(620, 600);
        setLocationRelativeTo(owner);

        tabbedPane = new JTabbedPane(JTabbedPane.LEFT);

        // Create buttons first, as they might be passed to panels
        okButton = new MaterialButton("OK");
        cancelButton = new MaterialButton("Cancel");
        applyButton = new MaterialButton("Apply");

        SwingUtil.applyPrimaryButtonStyle(okButton);

        // Global Settings Panel
        globalSettingsPanel = new SettingsGlobalPanel(chrome, this);
        tabbedPane.addTab("Global", null, globalSettingsPanel, "Global application settings");

        // Project Settings Panel
        // Pass dialog buttons to project panel for enabling/disabling during build agent run
        projectSettingsPanel =
                new SettingsProjectPanel(chrome, this, okButton, cancelButton, applyButton, generatedStyleGuide);
        tabbedPane.addTab("Project", null, projectSettingsPanel, "Settings specific to the current project");

        updateProjectPanelEnablement(); // Initial enablement

        // Buttons Panel
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(applyButton);

        okButton.addActionListener(e -> {
            if (applySettings()) {
                dispose();
                handleProxyRestartIfNeeded();
            }
        });
        cancelButton.addActionListener(e -> {
            // Cancel any ongoing background GitHub authentication
            BackgroundGitHubAuth.cancelCurrentAuth();
            dispose();
            // No restart needed if cancelled
        });
        applyButton.addActionListener(e -> {
            if (applySettings()) {
                // Reload settings in panels to reflect saved state
                globalSettingsPanel.loadSettings();
                chrome.getProject();
                projectSettingsPanel.loadSettings();
                handleProxyRestartIfNeeded(); // Handle restart if proxy changed on Apply
            }
        });

        getRootPane()
                .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancelButton.doClick();
            }
        });

        // Add window listener to handle window close events
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Cancel any ongoing background GitHub authentication when window closes
                BackgroundGitHubAuth.cancelCurrentAuth();
            }
        });

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tabbedPane, BorderLayout.CENTER);
        getContentPane().add(buttonPanel, BorderLayout.SOUTH);
    }

    public Chrome getChrome() {
        return chrome;
    }

    private void updateProjectPanelEnablement() {
        projectSettingsPanel.setEnabled(true);
        int projectTabIndex = tabbedPane.indexOfComponent(projectSettingsPanel);
        if (projectTabIndex != -1) {
            tabbedPane.setEnabledAt(projectTabIndex, true);
        }
    }

    private boolean applySettings() {
        MainProject.LlmProxySetting oldProxySetting = MainProject.getProxySetting();

        if (!globalSettingsPanel.applySettings()) {
            return false; // Global settings failed validation
        }

        if (!projectSettingsPanel.applySettings()) {
            return false; // Project settings failed validation
        }

        MainProject.LlmProxySetting newProxySetting = MainProject.getProxySetting();
        if (oldProxySetting != newProxySetting
                && newProxySetting != MainProject.LlmProxySetting.STAGING) { // STAGING is non-interactive
            proxySettingsChanged = true;
        }

        return true;
    }

    private void handleProxyRestartIfNeeded() {
        if (proxySettingsChanged || uiScaleSettingsChanged) {
            JOptionPane.showMessageDialog(
                    this,
                    "Some settings have changed (Proxy and/or UI Scale).\nPlease restart Brokk to apply them.",
                    "Restart Required",
                    JOptionPane.INFORMATION_MESSAGE);
            proxySettingsChanged = false;
            uiScaleSettingsChanged = false;
        }
    }

    // Called by SettingsGlobalPanel when Brokk key changes, as it might affect org policy
    public void triggerDataRetentionPolicyRefresh() {
        projectSettingsPanel.refreshDataRetentionPanel();
        // After data retention policy is refreshed, the model list (which depends on policy)
        // in the global panel might need to be updated as well.
        globalSettingsPanel.loadSettings(); // Reload to reflect new policy and available models
    }

    // Called by SettingsProjectPanel's DataRetentionPanel when policy is applied
    // to refresh the Models tab in the Global panel.
    public void refreshGlobalModelsPanelPostPolicyChange() {
        globalSettingsPanel.loadSettings();
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        var previousSize = getSize();
        SwingUtilities.updateComponentTreeUI(this);
        globalSettingsPanel.applyTheme(guiTheme);
        projectSettingsPanel.applyTheme(guiTheme);
        setSize(previousSize);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
        // Word wrap not applicable to settings dialog
        var previousSize = getSize();
        SwingUtilities.updateComponentTreeUI(this);
        globalSettingsPanel.applyTheme(guiTheme, wordWrap);
        projectSettingsPanel.applyTheme(guiTheme, wordWrap);
        setSize(previousSize);
    }

    // Called by SettingsGlobalPanel when UI scale preference changes
    public void markRestartNeededForUiScale() {
        this.uiScaleSettingsChanged = true;
    }

    /**
     * Shows settings dialog, reading style guide from disk.
     * Used when opening settings manually after initialization.
     */
    public static SettingsDialog showSettingsDialog(Chrome chrome, String targetTabName) {
        return showSettingsDialog(chrome, targetTabName, Optional.empty());
    }

    /**
     * Shows settings dialog with pre-generated style guide content.
     * Used during initialization to avoid race condition with file writes.
     */
    public static SettingsDialog showSettingsDialog(
            Chrome chrome, String targetTabName, Optional<String> generatedStyleGuide) {
        var dialog = new SettingsDialog(chrome.getFrame(), chrome, generatedStyleGuide);

        // Load settings after dialog construction but before showing
        // This ensures any background file writes (e.g., style guide generation) have completed
        dialog.globalSettingsPanel.loadSettings();
        dialog.projectSettingsPanel.loadSettings();

        boolean tabSelected = false;
        // Top-level tabs: "Global", "Project"
        // Global sub-tabs: "Service", "Appearance", SettingsGlobalPanel.MODELS_TAB_TITLE, "Alternative Models",
        // "GitHub"
        // Project sub-tabs: "General", "Build", "Data Retention"

        // Try to select top-level tab first
        int globalTabIndex = -1, projectTabIndex = -1;
        for (int i = 0; i < dialog.tabbedPane.getTabCount(); i++) {
            if ("Global".equals(dialog.tabbedPane.getTitleAt(i))) globalTabIndex = i;
            if ("Project".equals(dialog.tabbedPane.getTitleAt(i))) projectTabIndex = i;
        }

        if (targetTabName.equals("Global") && globalTabIndex != -1 && dialog.tabbedPane.isEnabledAt(globalTabIndex)) {
            dialog.tabbedPane.setSelectedIndex(globalTabIndex);
            tabSelected = true;
        } else if (targetTabName.equals("Project")
                && projectTabIndex != -1
                && dialog.tabbedPane.isEnabledAt(projectTabIndex)) {
            dialog.tabbedPane.setSelectedIndex(projectTabIndex);
            tabSelected = true;
        } else {
            // Check Global sub-tabs
            JTabbedPane globalSubTabs = dialog.globalSettingsPanel.getGlobalSubTabbedPane();
            for (int i = 0; i < globalSubTabs.getTabCount(); i++) {
                if (targetTabName.equals(globalSubTabs.getTitleAt(i))) {
                    if (globalTabIndex != -1 && dialog.tabbedPane.isEnabledAt(globalTabIndex)) {
                        dialog.tabbedPane.setSelectedIndex(globalTabIndex); // Select "Global" parent
                        if (globalSubTabs.isEnabledAt(i)
                                || targetTabName.equals(
                                        SettingsGlobalPanel
                                                .MODELS_TAB_TITLE)) { // Models tab content itself handles enablement
                            globalSubTabs.setSelectedIndex(i);
                            tabSelected = true;
                        }
                    }
                    break;
                }
            }

            // If not found in Global, check Project sub-tabs (only if project is open)
            if (!tabSelected && projectTabIndex != -1 && dialog.tabbedPane.isEnabledAt(projectTabIndex)) {
                JTabbedPane projectSubTabs = dialog.projectSettingsPanel.getProjectSubTabbedPane();
                for (int i = 0; i < projectSubTabs.getTabCount(); i++) {
                    if (targetTabName.equals(projectSubTabs.getTitleAt(i))) {
                        dialog.tabbedPane.setSelectedIndex(projectTabIndex); // Select "Project" parent
                        if (projectSubTabs.isEnabledAt(i)) {
                            projectSubTabs.setSelectedIndex(i);
                            tabSelected = true;
                        }
                        break;
                    }
                }
            }
        }
        if (!tabSelected) {
            logger.warn("Could not find or select target settings tab: {}", targetTabName);
        }
        dialog.setVisible(true);
        return dialog;
    }

    public static boolean showStandaloneDataRetentionDialog(IProject project, Frame owner) {
        assert project.isDataShareAllowed()
                : "Standalone data retention dialog should not be shown if data sharing is disabled by organization";

        var dialog = new JDialog(owner, "Data Retention Policy Required", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setSize(600, 350); // Adjusted size
        dialog.setLocationRelativeTo(owner);

        // Create a temporary SettingsProjectPanel just for its DataRetentionPanel inner class logic
        // This is a bit of a workaround to reuse the panel logic.
        var tempProjectPanelForRetention =
                new SettingsProjectPanel.DataRetentionPanel(project, null); // Pass null for parentProjectPanel

        var contentPanel = new JPanel(new BorderLayout(10, 10));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var noteLabel = new JLabel(
                "<html>(You can change this setting later under Project -> Data Retention in the main Settings dialog.)</html>");
        noteLabel.setFont(
                noteLabel.getFont().deriveFont(Font.PLAIN, noteLabel.getFont().getSize() * 0.9f));
        contentPanel.add(noteLabel, BorderLayout.NORTH);

        contentPanel.add(tempProjectPanelForRetention, BorderLayout.CENTER);

        var okButtonDialog = new MaterialButton("OK");
        SwingUtil.applyPrimaryButtonStyle(okButtonDialog);
        var cancelButtonDialog = new MaterialButton("Cancel");
        var buttonPanelDialog = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanelDialog.add(okButtonDialog);
        buttonPanelDialog.add(cancelButtonDialog);
        contentPanel.add(buttonPanelDialog, BorderLayout.SOUTH);

        final boolean[] dialogResult = {false};

        okButtonDialog.addActionListener(e -> {
            var selectedPolicy = tempProjectPanelForRetention.getSelectedPolicy();
            if (selectedPolicy == DataRetentionPolicy.UNSET) {
                JOptionPane.showMessageDialog(
                        dialog,
                        "Please select a data retention policy to continue.",
                        "Selection Required",
                        JOptionPane.WARNING_MESSAGE);
            } else {
                tempProjectPanelForRetention.applyPolicy(); // This saves the policy
                // No need to call parentProjectPanel.parentDialog.getChrome().getContextManager().reloadService();
                // or parentProjectPanel.parentDialog.refreshGlobalModelsPanelPostPolicyChange();
                // because this is a standalone dialog, Chrome isn't fully set up perhaps, and there's no SettingsDialog
                // instance.
                // The main app should handle model refresh after this dialog returns true.
                dialogResult[0] = true;
                dialog.dispose();
            }
        });

        cancelButtonDialog.addActionListener(e -> {
            dialogResult[0] = false;
            dialog.dispose();
        });

        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dialogResult[0] = false;
                dialog.dispose();
            }
        });

        dialog.setContentPane(contentPanel);
        okButtonDialog.requestFocusInWindow();
        dialog.setVisible(true);

        return dialogResult[0];
    }

    public SettingsProjectPanel getProjectPanel() {
        return projectSettingsPanel;
    }
}
