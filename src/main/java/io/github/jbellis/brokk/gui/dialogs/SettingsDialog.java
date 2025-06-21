package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.MainProject.DataRetentionPolicy;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class SettingsDialog extends JDialog implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(SettingsDialog.class);

    public static final String GITHUB_SETTINGS_TAB_NAME = "GitHub";

    private final Chrome chrome;
    private final JTabbedPane tabbedPane;
    private SettingsGlobalPanel globalSettingsPanel;
    private SettingsProjectPanel projectSettingsPanel;

    private final JButton okButton;
    private final JButton cancelButton;
    private final JButton applyButton;

    private boolean proxySettingsChanged = false; // Track if proxy needs restart


    public SettingsDialog(Frame owner, Chrome chrome) {
        super(owner, "Settings", true);
        this.chrome = chrome;
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setSize(620, 480); // Adjusted size slightly for better layout with tabs on left
        setLocationRelativeTo(owner);

        tabbedPane = new JTabbedPane(JTabbedPane.LEFT);

        // Create buttons first, as they might be passed to panels
        okButton = new JButton("OK");
        cancelButton = new JButton("Cancel");
        applyButton = new JButton("Apply");

        // Global Settings Panel
        globalSettingsPanel = new SettingsGlobalPanel(chrome, this);
        tabbedPane.addTab("Global", null, globalSettingsPanel, "Global application settings");

        // Project Settings Panel
        // Pass dialog buttons to project panel for enabling/disabling during build agent run
        projectSettingsPanel = new SettingsProjectPanel(chrome, this, okButton, cancelButton, applyButton);
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
            dispose();
            // No restart needed if cancelled
        });
        applyButton.addActionListener(e -> {
            if (applySettings()) {
                // Reload settings in panels to reflect saved state
                globalSettingsPanel.loadSettings();
                if (chrome.getProject() != null) {
                    projectSettingsPanel.loadSettings();
                }
                handleProxyRestartIfNeeded(); // Handle restart if proxy changed on Apply
            }
        });

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                cancelButton.doClick();
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
        boolean projectIsOpen = (chrome.getProject() != null);
        projectSettingsPanel.setEnabled(projectIsOpen);
        // The SettingsProjectPanel's initComponents will handle enabling/disabling its own sub-components
        // based on whether a project is open.
        int projectTabIndex = tabbedPane.indexOfComponent(projectSettingsPanel);
        if (projectTabIndex != -1) {
            tabbedPane.setEnabledAt(projectTabIndex, projectIsOpen);
        }
        // Also, the "Default Models" sub-tab in Global settings needs to be updated
        globalSettingsPanel.updateModelsPanelEnablement();
    }


    private boolean applySettings() {
        MainProject.LlmProxySetting oldProxySetting = MainProject.getProxySetting();

        if (!globalSettingsPanel.applySettings()) {
            return false; // Global settings failed validation
        }

        if (chrome.getProject() != null) {
            if (!projectSettingsPanel.applySettings()) {
                return false; // Project settings failed validation
            }
        }
        
        MainProject.LlmProxySetting newProxySetting = MainProject.getProxySetting();
        if (oldProxySetting != newProxySetting && newProxySetting != MainProject.LlmProxySetting.STAGING) { // STAGING is non-interactive
            proxySettingsChanged = true;
        }

        return true;
    }
    
    private void handleProxyRestartIfNeeded() {
        if (proxySettingsChanged) {
            JOptionPane.showMessageDialog(this,
                    "LLM Proxy settings have changed. Please restart Brokk to apply them.",
                    "Restart Required",
                    JOptionPane.INFORMATION_MESSAGE);
            proxySettingsChanged = false; // Reset flag
        }
    }

    // Called by SettingsGlobalPanel when Brokk key changes, as it might affect org policy
    public void triggerDataRetentionPolicyRefresh() {
        if (chrome.getProject() != null) {
            projectSettingsPanel.refreshDataRetentionPanel();
            // After data retention policy is refreshed, the model list (which depends on policy)
            // in the global panel might need to be updated as well.
            globalSettingsPanel.loadSettings(); // Reload to reflect new policy and available models
        }
    }
    
    // Called by SettingsProjectPanel's DataRetentionPanel when policy is applied
    // to refresh the Models tab in the Global panel.
    public void refreshGlobalModelsPanelPostPolicyChange() {
        globalSettingsPanel.loadSettings(); // This will re-evaluate model availability.
        globalSettingsPanel.updateModelsPanelEnablement(); // Ensure combos are correctly enabled.
    }


    @Override
    public void applyTheme(GuiTheme guiTheme) {
        var previousSize = getSize();
        SwingUtilities.updateComponentTreeUI(this);
        globalSettingsPanel.applyTheme(guiTheme);
        projectSettingsPanel.applyTheme(guiTheme);
        setSize(previousSize);
    }

    public static SettingsDialog showSettingsDialog(Chrome chrome, String targetTabName) {
        var dialog = new SettingsDialog(chrome.getFrame(), chrome);

        if (targetTabName != null) {
            boolean tabSelected = false;
            // Top-level tabs: "Global", "Project"
            // Global sub-tabs: "Service", "Appearance", SettingsGlobalPanel.MODELS_TAB_TITLE, "Alternative Models", "GitHub"
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
            } else if (targetTabName.equals("Project") && projectTabIndex != -1 && dialog.tabbedPane.isEnabledAt(projectTabIndex)) {
                dialog.tabbedPane.setSelectedIndex(projectTabIndex);
                tabSelected = true;
            } else {
                // Check Global sub-tabs
                JTabbedPane globalSubTabs = dialog.globalSettingsPanel.getGlobalSubTabbedPane();
                for (int i = 0; i < globalSubTabs.getTabCount(); i++) {
                    if (targetTabName.equals(globalSubTabs.getTitleAt(i))) {
                        if (globalTabIndex != -1 && dialog.tabbedPane.isEnabledAt(globalTabIndex)) {
                            dialog.tabbedPane.setSelectedIndex(globalTabIndex); // Select "Global" parent
                            if (globalSubTabs.isEnabledAt(i) || targetTabName.equals(SettingsGlobalPanel.MODELS_TAB_TITLE)) { // Models tab content itself handles enablement
                                globalSubTabs.setSelectedIndex(i);
                                tabSelected = true;
                            }
                        }
                        break;
                    }
                }

                // If not found in Global, check Project sub-tabs (only if project is open)
                if (!tabSelected && chrome.getProject() != null && projectTabIndex != -1 && dialog.tabbedPane.isEnabledAt(projectTabIndex)) {
                    JTabbedPane projectSubTabs = dialog.projectSettingsPanel.getProjectSubTabbedPane();
                     if (projectSubTabs != null) { // projectSubTabs could be null if project panel didn't init fully
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
            }
            if (!tabSelected) {
                logger.warn("Could not find or select target settings tab: {}", targetTabName);
            }
        }
        dialog.setVisible(true);
        return dialog;
    }

    public static boolean showStandaloneDataRetentionDialog(IProject project, Frame owner) {
        assert project.isDataShareAllowed() : "Standalone data retention dialog should not be shown if data sharing is disabled by organization";

        var dialog = new JDialog(owner, "Data Retention Policy Required", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setSize(600, 350); // Adjusted size
        dialog.setLocationRelativeTo(owner);

        // Create a temporary SettingsProjectPanel just for its DataRetentionPanel inner class logic
        // This is a bit of a workaround to reuse the panel logic.
        var tempProjectPanelForRetention = new SettingsProjectPanel.DataRetentionPanel(project, null); // Pass null for parentProjectPanel

        var contentPanel = new JPanel(new BorderLayout(10, 10));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var noteLabel = new JLabel("<html>(You can change this setting later under Project -> Data Retention in the main Settings dialog.)</html>");
        noteLabel.setFont(noteLabel.getFont().deriveFont(Font.PLAIN, noteLabel.getFont().getSize() * 0.9f));
        contentPanel.add(noteLabel, BorderLayout.NORTH);

        contentPanel.add(tempProjectPanelForRetention, BorderLayout.CENTER);

        var okButtonDialog = new JButton("OK");
        var cancelButtonDialog = new JButton("Cancel");
        var buttonPanelDialog = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanelDialog.add(okButtonDialog);
        buttonPanelDialog.add(cancelButtonDialog);
        contentPanel.add(buttonPanelDialog, BorderLayout.SOUTH);

        final boolean[] dialogResult = {false};

        okButtonDialog.addActionListener(e -> {
            var selectedPolicy = tempProjectPanelForRetention.getSelectedPolicy();
            if (selectedPolicy == DataRetentionPolicy.UNSET) {
                JOptionPane.showMessageDialog(dialog, "Please select a data retention policy to continue.", "Selection Required", JOptionPane.WARNING_MESSAGE);
            } else {
                tempProjectPanelForRetention.applyPolicy(); // This saves the policy
                // No need to call parentProjectPanel.parentDialog.getChrome().getContextManager().reloadModelsAsync();
                // or parentProjectPanel.parentDialog.refreshGlobalModelsPanelPostPolicyChange();
                // because this is a standalone dialog, Chrome isn't fully set up perhaps, and there's no SettingsDialog instance.
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
