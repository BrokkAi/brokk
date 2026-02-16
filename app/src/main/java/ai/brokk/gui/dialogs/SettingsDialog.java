package ai.brokk.gui.dialogs;

import static java.util.Objects.requireNonNull;

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
import ai.brokk.project.ModelProperties;
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

    private @Nullable String pendingSelectTab;

    public SettingsDialog(Frame owner, Chrome chrome) {
        super(owner, "Settings");
        this.chrome = chrome;
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setSize(800, 600);
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

        // Project Settings Panel
        // Pass dialog buttons to project panel for enabling/disabling during build agent run
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

        ai.brokk.concurrent.LoggingFuture.supplyAsync(() -> SettingsData.load(chrome.getProject()))
                .thenAccept(data -> SwingUtil.runOnEdt(() -> {
                    if (!isDisplayable() || !isShowing()) {
                        return;
                    }
                    if (data.buildDetails() == null) {
                        awaitBuildDetailsAndPopulate(data);
                    } else {
                        populateUIFromData(data);

                        if (pendingSelectTab != null) {
                            String tab = pendingSelectTab;
                            pendingSelectTab = null;
                            selectTab(SettingsDialog.this, tab);
                        }
                    }
                }))
                .exceptionally(e -> {
                    logger.error("Failed to load settings", e);
                    SwingUtil.runOnEdt(() -> {
                        chrome.toolError("Failed to load settings: " + e.getMessage(), "Settings Error");
                        dispose();
                    });
                    return null;
                });
    }

    private void awaitBuildDetailsAndPopulate(SettingsData partialData) {
        var project = chrome.getProject();

        var future = project.getBuildDetailsFuture();
        boolean completed = ai.brokk.gui.MaterialOptionPane.showBlockingProgressDialog(
                this, "Waiting for build settings inference...", "Project Initialization", future);

        if (completed) {
            try {
                var details = requireNonNull(future.getNow(null));
                populateUIFromData(new SettingsData(
                        partialData.jvmMemorySettings(),
                        partialData.brokkApiKey(),
                        partialData.accountBalance(),
                        partialData.favoriteModels(),
                        partialData.isPaidSubscriber(),
                        details,
                        partialData.styleGuide(),
                        partialData.commitMessageFormat()));
            } catch (Exception e) {
                logger.error("Error retrieving build details", e);
                dispose();
            }
        } else {
            // User cancelled
            dispose();
        }
    }

    /**
     * Triggers a settings reload and schedules a tab selection once the reload is complete.
     * Must be called on the EDT.
     */
    void reloadSettingsAndSelectTab(String targetTabName) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        this.pendingSelectTab = targetTabName;
        loadSettingsInBackground();
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

    private static final String DIALOG_KEY = "SettingsDialog";

    /**
     * Shows settings dialog. If one is already open, brings it to front.
     */
    public static SettingsDialog showSettingsDialog(Chrome chrome, String targetTabName) {
        // Check if dialog is already open
        var existing = (SettingsDialog) chrome.getOpenDialog(DIALOG_KEY);
        if (existing != null && existing.isDisplayable()) {
            existing.toFront();
            existing.requestFocus();
            selectTab(existing, targetTabName);
            return existing;
        }

        var dialog = new SettingsDialog(chrome.getFrame(), chrome);
        chrome.registerOpenDialog(DIALOG_KEY, dialog);

        // Load settings after dialog construction but before showing
        // This ensures any background file writes (e.g., style guide generation) have completed
        dialog.loadSettingsInBackground();

        selectTab(dialog, targetTabName);
        dialog.setVisible(true);
        return dialog;
    }

    private static @Nullable JTabbedPane findNestedTabbedPane(Component component) {
        if (component instanceof JTabbedPane jTabbedPane) {
            return jTabbedPane;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JTabbedPane found = findNestedTabbedPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static void selectTab(SettingsDialog dialog, String targetTabName) {
        boolean tabSelected = false;
        int globalTabIndex = -1;
        int projectTabIndex = -1;
        int advancedTabIndex = -1;
        for (int i = 0; i < dialog.tabbedPane.getTabCount(); i++) {
            String title = dialog.tabbedPane.getTitleAt(i);
            if ("Global".equals(title)) {
                globalTabIndex = i;
            } else if ("Project".equals(title)) {
                projectTabIndex = i;
            } else if ("Advanced".equals(title)) {
                advancedTabIndex = i;
            }
        }

        if (targetTabName.equals("Global") && globalTabIndex != -1 && dialog.tabbedPane.isEnabledAt(globalTabIndex)) {
            dialog.tabbedPane.setSelectedIndex(globalTabIndex);
            tabSelected = true;
        } else if (targetTabName.equals("Project")
                && projectTabIndex != -1
                && dialog.tabbedPane.isEnabledAt(projectTabIndex)) {
            dialog.tabbedPane.setSelectedIndex(projectTabIndex);
            tabSelected = true;
        } else if (targetTabName.equals("Advanced")
                && advancedTabIndex != -1
                && dialog.tabbedPane.isEnabledAt(advancedTabIndex)) {
            dialog.tabbedPane.setSelectedIndex(advancedTabIndex);
            tabSelected = true;
        } else {
            // Check Global sub-tabs
            JTabbedPane globalSubTabs = dialog.globalSettingsPanel.getGlobalSubTabbedPane();
            for (int i = 0; i < globalSubTabs.getTabCount(); i++) {
                if (targetTabName.equals(globalSubTabs.getTitleAt(i))) {
                    if (globalTabIndex != -1 && dialog.tabbedPane.isEnabledAt(globalTabIndex)) {
                        dialog.tabbedPane.setSelectedIndex(globalTabIndex);
                        if (globalSubTabs.isEnabledAt(i)
                                || targetTabName.equals(SettingsAdvancedPanel.MODELS_TAB_TITLE)) {
                            globalSubTabs.setSelectedIndex(i);
                            tabSelected = true;
                        }
                    }
                    break;
                }
            }

            // If not found in Global, check Project sub-tabs
            if (!tabSelected && projectTabIndex != -1 && dialog.tabbedPane.isEnabledAt(projectTabIndex)) {
                JTabbedPane projectSubTabs = dialog.projectSettingsPanel.getProjectSubTabbedPane();
                for (int i = 0; i < projectSubTabs.getTabCount(); i++) {
                    if (targetTabName.equals(projectSubTabs.getTitleAt(i))) {
                        dialog.tabbedPane.setSelectedIndex(projectTabIndex);
                        if (projectSubTabs.isEnabledAt(i)) {
                            projectSubTabs.setSelectedIndex(i);
                            tabSelected = true;
                        }
                        break;
                    }
                }
            }

            // If not found in Global or Project, check Advanced sub-tabs
            if (!tabSelected && advancedTabIndex != -1 && dialog.tabbedPane.isEnabledAt(advancedTabIndex)) {
                JTabbedPane advancedSubTabs = dialog.advancedSettingsPanel.getAdvancedSubTabbedPane();
                for (int i = 0; i < advancedSubTabs.getTabCount(); i++) {
                    if (targetTabName.equals(advancedSubTabs.getTitleAt(i))) {
                        dialog.tabbedPane.setSelectedIndex(advancedTabIndex);
                        if (advancedSubTabs.isEnabledAt(i)
                                || targetTabName.equals(SettingsAdvancedPanel.MODELS_TAB_TITLE)) {
                            advancedSubTabs.setSelectedIndex(i);
                            tabSelected = true;
                        }
                        break;
                    }
                }

                // If not found in Advanced sub-tabs directly, check for nested tabs (e.g., Models > Model Roles)
                if (!tabSelected) {
                    for (int i = 0; i < advancedSubTabs.getTabCount(); i++) {
                        Component tabComponent = advancedSubTabs.getComponentAt(i);
                        // Look for nested JTabbedPane within the tab's component hierarchy
                        JTabbedPane nestedTabbedPane = findNestedTabbedPane(tabComponent);
                        if (nestedTabbedPane != null) {
                            for (int j = 0; j < nestedTabbedPane.getTabCount(); j++) {
                                if (targetTabName.equals(nestedTabbedPane.getTitleAt(j))) {
                                    dialog.tabbedPane.setSelectedIndex(advancedTabIndex);
                                    advancedSubTabs.setSelectedIndex(i); // Select the parent tab (e.g., Models)
                                    nestedTabbedPane.setSelectedIndex(j); // Select the nested tab (e.g., Model Roles)
                                    tabSelected = true;
                                    break;
                                }
                            }
                        }
                        if (tabSelected) break;
                    }
                }
            }
        }
        if (!tabSelected) {
            logger.warn("Could not find or select target settings tab: {}", targetTabName);
        }
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
    public record SettingsData(
            // Global settings
            MainProject.JvmMemorySettings jvmMemorySettings,
            String brokkApiKey,
            String accountBalance,
            List<Service.FavoriteModel> favoriteModels,
            boolean isPaidSubscriber,

            // Project-specific settings (nullable if no project)
            @Nullable BuildAgent.BuildDetails buildDetails,
            @Nullable String styleGuide,
            @Nullable String commitMessageFormat) {
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
            var balanceResult = loadAccountBalanceWithPaidStatus(apiKey); // network I/O
            var models = MainProject.loadFavoriteModels(); // file I/O

            // If empty, create default and save
            if (project != null && models.isEmpty()) {
                var currentCodeConfig = project.getMainProject().getModelConfig(ModelProperties.ModelType.CODE);
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

            if (project != null) {
                var future = project.getBuildDetailsFuture();
                if (future.isDone() && !future.isCancelled()) {
                    buildDetails = future.getNow(BuildAgent.BuildDetails.EMPTY);
                }
                // If null, the caller (SettingsDialog) will handle the modal await logic
                styleGuide = project.getStyleGuide();
                commitFormat = project.getCommitMessageFormat();
            }

            return new SettingsData(
                    jvmSettings,
                    apiKey,
                    balanceResult.displayString(),
                    models,
                    balanceResult.isPaid(),
                    buildDetails,
                    styleGuide,
                    commitFormat);
        }

        /**
         * Result of loading balance: both the display string and paid status.
         */
        private record BalanceResult(String displayString, boolean isPaid) {}

        /**
         * Loads account balance via network call and determines paid status.
         * Uses the backend's `is_subscribed` attribute to determine subscription status.
         * Safe to call off EDT.
         */
        @Blocking
        private static BalanceResult loadAccountBalanceWithPaidStatus(String apiKey) {
            if (apiKey.isBlank()) {
                return new BalanceResult("No API key configured", false);
            }

            try {
                var balanceInfo = Service.getBalanceInfo(apiKey);
                String displayString = String.format("$%.2f", balanceInfo.balance());
                // Use backend's is_subscribed flag to determine paid status
                boolean isPaid = balanceInfo.isSubscribed();
                return new BalanceResult(displayString, isPaid);
            } catch (IllegalArgumentException e) {
                return new BalanceResult("Invalid API key format", false);
            } catch (IOException e) {
                logger.warn("Failed to load account balance", e);
                return new BalanceResult("Error loading balance: " + e.getMessage(), false);
            }
        }
    }
}
