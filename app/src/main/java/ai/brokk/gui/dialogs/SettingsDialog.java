package ai.brokk.gui.dialogs;

import ai.brokk.Service;
import ai.brokk.agents.BuildAgent;
import ai.brokk.github.BackgroundGitHubAuth;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.project.MainProject.DataRetentionPolicy;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.List;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public class SettingsDialog extends BaseThemedDialog implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(SettingsDialog.class);

    public static final String GITHUB_SETTINGS_TAB_NAME = "GitHub";

    private final Chrome chrome;
    private final JTabbedPane tabbedPane;
    private SettingsGlobalPanel globalSettingsPanel;
    private SettingsAdvancedPanel advancedSettingsPanel;
    private SettingsProjectPanel projectSettingsPanel;

    private final MaterialButton okButton;
    private final MaterialButton cancelButton;
    private final MaterialButton applyButton;

    private boolean proxySettingsChanged = false; // Track if proxy needs restart
    private boolean uiScaleSettingsChanged = false; // Track if UI scale needs restart

    public SettingsDialog(Frame owner, Chrome chrome) {
        super(owner, "Settings");
        this.chrome = chrome;
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setSize(1100, 600);
        setLocationRelativeTo(owner);

        tabbedPane = new JTabbedPane(JTabbedPane.LEFT);

        // Create buttons first, as they might be passed to panels
        okButton = new MaterialButton("OK");
        cancelButton = new MaterialButton("Cancel");
        applyButton = new MaterialButton("Apply");

        SwingUtil.applyPrimaryButtonStyle(okButton);

        // Create panels immediately (without data) to get proper layout/size
        // Panels will be disabled until data loads
        globalSettingsPanel = new SettingsGlobalPanel(chrome, this);
        tabbedPane.addTab("Global", null, globalSettingsPanel, "Global application settings");

        projectSettingsPanel = new SettingsProjectPanel(chrome, this, okButton, cancelButton, applyButton);
        tabbedPane.addTab("Project", null, projectSettingsPanel, "Settings specific to the current project");

        advancedSettingsPanel = new SettingsAdvancedPanel(chrome, this);
        tabbedPane.addTab("Advanced", null, advancedSettingsPanel, "Advanced global settings");

        // Buttons Panel
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(applyButton);

        // Disable buttons while loading
        okButton.setEnabled(false);
        cancelButton.setEnabled(false);
        applyButton.setEnabled(false);

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
            applySettings();
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

        JPanel root = getContentRoot();
        root.setLayout(new BorderLayout());
        root.add(tabbedPane, BorderLayout.CENTER);
        root.add(buttonPanel, BorderLayout.SOUTH);

        // Load settings in background and populate UI when done
        loadSettingsInBackground();
    }

    /**
     * Loads all settings in background thread and populates UI on EDT when complete.
     * This prevents EDT blocking from file I/O and network operations.
     * Panels are already created and disabled; this method just populates them with data.
     * Package-private to allow access from settings panels for refreshing after changes.
     */
    void loadSettingsInBackground() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        var worker = new SwingWorker<SettingsData, Void>() {
            @Override
            protected SettingsData doInBackground() {
                // All I/O happens here in background thread
                return SettingsData.load(chrome.getProject());
            }

            @Override
            protected void done() {
                // Guard: if the dialog has been disposed or is no longer showing, skip all UI updates
                if (!isDisplayable() || !isShowing()) {
                    return;
                }

                try {
                    var data = get();
                    populateUIFromData(data);
                } catch (Exception e) {
                    logger.error("Failed to load settings", e);
                    chrome.toolError("Failed to load settings: " + e.getMessage(), "Settings Error");
                    dispose();
                }
            }
        };
        worker.execute();
    }

    /**
     * Populates UI panels with loaded settings data. Must be called on EDT.
     * Panels are already created; this method populates them and enables buttons.
     */
    private void populateUIFromData(SettingsData data) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        // Populate panels with data (this also enables them)
        globalSettingsPanel.populateFromData(data);
        advancedSettingsPanel.populateFromData(data);
        projectSettingsPanel.populateFromData(data);

        // Enable buttons now that data is loaded
        okButton.setEnabled(true);
        cancelButton.setEnabled(true);
        applyButton.setEnabled(true);

        revalidate();
        repaint();
    }

    public Chrome getChrome() {
        return chrome;
    }

    private boolean applySettings() {
            MainProject.LlmProxySetting oldProxySetting = MainProject.getProxySetting();

            if (!globalSettingsPanel.applySettings()) {
                    return false; // Global settings failed validation
            }

            if (!advancedSettingsPanel.applySettings()) {
                    return false; // Advanced settings failed validation
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
        loadSettingsInBackground(); // Reload to reflect new policy and available models
    }

    // Called by SettingsProjectPanel's DataRetentionPanel when policy is applied
    // to refresh the Models tab in the Global panel.
    public void refreshGlobalModelsPanelPostPolicyChange() {
        loadSettingsInBackground();
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

    public static SettingsDialog showSettingsDialog(Chrome chrome, String targetTabName) {
        var dialog = new SettingsDialog(chrome.getFrame(), chrome);

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

        BaseThemedDialog dialog =
                new BaseThemedDialog(owner, "Data Retention Policy Required", Dialog.ModalityType.APPLICATION_MODAL);
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

        // Place contentPanel in the content root (which BaseThemedDialog manages)
        JPanel root = dialog.getContentRoot();
        root.setLayout(new BorderLayout());
        root.add(contentPanel, BorderLayout.CENTER);

        okButtonDialog.requestFocusInWindow();
        dialog.setVisible(true);

        return dialogResult[0];
    }

    public SettingsProjectPanel getProjectPanel() {
        return projectSettingsPanel;
    }

    /**
     * Consolidated settings data loaded in background to avoid EDT blocking.
     * All I/O operations happen in the static load() method which runs off EDT.
     */
    public static record SettingsData(
            // Global settings
            MainProject.JvmMemorySettings jvmMemorySettings,
            String brokkApiKey,
            String accountBalance,
            java.util.List<Service.FavoriteModel> favoriteModels,

            // Project-specific settings (nullable if no project)
            @Nullable BuildAgent.BuildDetails buildDetails,
            @Nullable String styleGuide,
            @Nullable String commitMessageFormat,
            @Nullable String reviewGuide) {
        private static final Logger logger = LogManager.getLogger(SettingsData.class);

        /**
         * Loads all settings in background thread. All I/O happens here.
         * This method should never be called on the EDT.
         *
         * @param project Current project, or null if no project is open
         * @return SettingsData with all loaded settings
         */
        @Blocking
        public static SettingsData load(@Nullable IProject project) {
            // Load global settings (file I/O)
            var jvmSettings = MainProject.getJvmMemorySettings();
            var apiKey = MainProject.getBrokkKey();
            var balance = loadAccountBalance(apiKey); // network I/O
            var models = MainProject.loadFavoriteModels(); // file I/O

            // If empty, create default and save
            if (project != null && models.isEmpty()) {
                var currentCodeConfig = project.getMainProject().getCodeModelConfig();
                var defaultAlias = "default";
                var defaultFavorite = new Service.FavoriteModel(defaultAlias, currentCodeConfig);
                models = List.of(defaultFavorite);
                try {
                    MainProject.saveFavoriteModels(models); // file I/O write
                } catch (Exception e) {
                    logger.warn("Failed to save default favorite models", e);
                }
            }

            // Load project-specific settings (file I/O) if project exists
            BuildAgent.BuildDetails buildDetails = null;
            String styleGuide = null;
            String commitFormat = null;
            String reviewGuide = null;

            if (project != null) {
                try {
                    buildDetails = project.loadBuildDetails();
                    styleGuide = project.getStyleGuide();
                    commitFormat = project.getCommitMessageFormat();
                    reviewGuide = project.getReviewGuide();
                } catch (Exception e) {
                    logger.warn("Failed to load project settings", e);
                }
            }

            return new SettingsData(
                    jvmSettings, apiKey, balance, models, buildDetails, styleGuide, commitFormat, reviewGuide);
        }

        /**
         * Loads account balance via network call. Safe to call off EDT.
         */
        @Blocking
        private static String loadAccountBalance(String apiKey) {
            if (apiKey.isBlank()) {
                return "No API key configured";
            }

            try {
                Service.validateKey(apiKey); // throws if invalid
                float balance = Service.getUserBalance(apiKey);
                return String.format("$%.2f", balance);
            } catch (IllegalArgumentException e) {
                return "Invalid API key format";
            } catch (IOException e) {
                logger.warn("Failed to load account balance", e);
                return "Error loading balance: " + e.getMessage();
            }
        }
    }
}
