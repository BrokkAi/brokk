package ai.brokk.gui.dialogs;

import ai.brokk.AbstractService;
import ai.brokk.Service;
import ai.brokk.SettingsChangeListener;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil.ThemedIcon;
import ai.brokk.gui.components.BrowserLabel;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.McpToolTable;
import ai.brokk.gui.components.SpinnerIconUtil;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.Icons;
import ai.brokk.gui.util.JDeploySettingsUtil;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.mcp.HttpMcpServer;
import ai.brokk.mcp.McpConfig;
import ai.brokk.mcp.McpServer;
import ai.brokk.mcp.McpUtils;
import ai.brokk.mcp.StdioMcpServer;
import ai.brokk.project.MainProject;
import ai.brokk.util.Environment;
import ai.brokk.util.GlobalUiSettings;
import io.modelcontextprotocol.spec.McpSchema;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EventObject;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class SettingsGlobalPanel extends JPanel implements ThemeAware, SettingsChangeListener {
    private static final Logger logger = LogManager.getLogger(SettingsGlobalPanel.class);
    public static final String MODELS_TAB_TITLE = "Models"; // Used for targeting this tab

    private final Chrome chrome;
    private final SettingsDialog parentDialog; // To access project for data retention refresh

    // UI Components managed by this panel
    private JTextField brokkKeyField = new JTextField();

    @Nullable
    private JRadioButton brokkProxyRadio; // Can be null if STAGING

    @Nullable
    private JRadioButton localhostProxyRadio; // Can be null if STAGING

    private JRadioButton lightThemeRadio = new JRadioButton("Light");
    private JRadioButton lightPlusThemeRadio = new JRadioButton("Light+");
    private JRadioButton darkThemeRadio = new JRadioButton("Dark");
    private JRadioButton darkPlusThemeRadio = new JRadioButton("Dark+");
    private JRadioButton highContrastThemeRadio = new JRadioButton("High Contrast");
    private JCheckBox wordWrapCheckbox = new JCheckBox("Enable word wrap");
    private JCheckBox classicBrokkViewCheckbox = new JCheckBox("Enable Classic (Horizontal) View");
    private JRadioButton diffSideBySideRadio = new JRadioButton("Side-by-Side");
    private JRadioButton diffUnifiedRadio = new JRadioButton("Unified");
    private JTable quickModelsTable = new JTable();
    private FavoriteModelsTableModel quickModelsTableModel = new FavoriteModelsTableModel(new ArrayList<>());
    private JComboBox<Service.FavoriteModel> preferredCodeModelCombo = new JComboBox<>();
    private JComboBox<Service.FavoriteModel> primaryModelCombo = new JComboBox<>();
    private JComboBox<String> otherModelsVendorCombo = new JComboBox<>();
    private JComboBox<String> watchServiceImplCombo =
            new JComboBox<>(new String[] {"Default (auto)", "Legacy", "Native"});

    @Nullable
    private JLabel otherModelsVendorLabel;

    @Nullable
    private JPanel otherModelsVendorHolder;

    private JTextField balanceField = new JTextField();
    private BrowserLabel signupLabel = new BrowserLabel("", ""); // Initialized with dummy values
    private JCheckBox showCostNotificationsCheckbox = new JCheckBox("Show LLM cost notifications");
    private JCheckBox showFreeInternalLLMCheckbox = new JCheckBox("Show free internal LLM calls");
    private JCheckBox showErrorNotificationsCheckbox = new JCheckBox("Show error notifications");
    private JCheckBox showConfirmNotificationsCheckbox = new JCheckBox("Show confirmation notifications");
    private JCheckBox showInfoNotificationsCheckbox = new JCheckBox("Show info notifications");

    @Nullable
    private JCheckBox forceToolEmulationCheckbox; // Dev-only

    private GitHubSettingsPanel gitHubSettingsPanel;

    private DefaultListModel<McpServer> mcpServersListModel = new DefaultListModel<>();
    private boolean plannerModelSyncListenerRegistered = false;
    private JList<McpServer> mcpServersList = new JList<>(mcpServersListModel);

    @Nullable
    private JRadioButton uiScaleAutoRadio; // Hidden on macOS or with the JBR

    @Nullable
    private JRadioButton uiScaleCustomRadio; // Hidden on macOS or with the JBR

    @Nullable
    private JComboBox<String> uiScaleCombo; // Hidden on macOS or with the JBR

    // JVM memory settings controls (General tab)
    private JRadioButton memoryAutoRadio = new JRadioButton("Automatic (recommended)");
    private JRadioButton memoryManualRadio = new JRadioButton("Manual:");
    private JSpinner memorySpinner = new JSpinner();

    private JCheckBox instructionsTabInsertIndentationCheckbox =
            new JCheckBox("Tab inserts indentation in Instructions (Code-style)");

    private JSpinner terminalFontSizeSpinner = new JSpinner();

    private JRadioButton startupOpenLastRadio = new JRadioButton("Open last project (recommended)");
    private JRadioButton startupOpenAllRadio = new JRadioButton("Reopen all previously open projects");
    private JCheckBox persistPerProjectWindowCheckbox = new JCheckBox("Save window position per project (recommended)");

    // Advanced mode (General tab)
    private JCheckBox advancedModeCheckbox = new JCheckBox("Enable Advanced Mode (show all UI)");
    private JCheckBox skipCommitGateEzCheckbox = new JCheckBox("Skip commit gate in EZ mode");

    private JTabbedPane globalSubTabbedPane = new JTabbedPane(JTabbedPane.TOP);

    /**
     * Constructor for creating panel without data (will be populated later).
     * Panel starts in disabled state until data is loaded.
     */
    public SettingsGlobalPanel(Chrome chrome, SettingsDialog parentDialog) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        this.chrome = chrome;
        this.parentDialog = parentDialog;
        setLayout(new BorderLayout());
        initComponents(); // This will fully initialize or conditionally initialize fields

        // Disable panel until data is loaded
        setEnabled(false);

        // Register for settings change notifications
        MainProject.addSettingsChangeListener(this);
    }

    /**
     * Populates UI fields from pre-loaded settings data. No I/O operations.
     * Must be called on EDT. Enables the panel after populating.
     */
    public void populateFromData(SettingsData data) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        // Enable panel now that we have data
        setEnabled(true);

        populateGeneralTab(data);
        populateServiceTab(data);
        populateAppearanceTab();
        populateStartupTab();
        populateNotificationsTab();
        populateQuickModelsTab(data);
        populateGitHubTab();
        populateMcpServersTab();
    }

    /**
     * Override to recursively enable/disable all child components.
     * JPanel's default setEnabled only affects the panel itself.
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setEnabledRecursive(this, enabled);
    }

    private void setEnabledRecursive(Container container, boolean enabled) {
        for (Component comp : container.getComponents()) {
            comp.setEnabled(enabled);
            if (comp instanceof Container c) {
                setEnabledRecursive(c, enabled);
            }
        }
    }

    private void populateGeneralTab(SettingsData data) {
        // JVM Memory
        try {
            var mem = data.jvmMemorySettings();
            if (mem.automatic()) {
                memoryAutoRadio.setSelected(true);
                memorySpinner.setEnabled(false);
            } else {
                memoryManualRadio.setSelected(true);
                memorySpinner.setEnabled(true);
                try {
                    int v = mem.manualMb();
                    SpinnerNumberModel model = (SpinnerNumberModel) memorySpinner.getModel();
                    int min = ((Number) model.getMinimum()).intValue();
                    int max = ((Number) model.getMaximum()).intValue();
                    if (v < min) v = min;
                    if (v > max) v = max;
                    int step = model.getStepSize().intValue();
                    if (step > 0) {
                        int rem = v % step;
                        if (rem != 0) v = v - rem + (rem >= step / 2 ? step : 0);
                    }
                    memorySpinner.setValue(v);
                } catch (Exception ignore) {
                    // leave spinner as-is
                }
            }
        } catch (Exception ignore) {
            // Use defaults if there is any problem reading settings
        }

        // Advanced Mode
        advancedModeCheckbox.setSelected(GlobalUiSettings.isAdvancedMode());

        // EZ-mode skip commit gate
        skipCommitGateEzCheckbox.setSelected(GlobalUiSettings.isSkipCommitGateInEzMode());
        skipCommitGateEzCheckbox.setVisible(!GlobalUiSettings.isAdvancedMode());

        // File watcher implementation preference
        try {
            String pref = MainProject.getWatchServiceImplPreference();
            String selection;
            if ("legacy".equalsIgnoreCase(pref)) {
                selection = "Legacy";
            } else if ("native".equalsIgnoreCase(pref)) {
                selection = "Native";
            } else {
                selection = "Default (auto)";
            }
            watchServiceImplCombo.setSelectedItem(selection);
        } catch (Exception ex) {
            // Non-fatal: default to auto
            watchServiceImplCombo.setSelectedItem("Default (auto)");
        }
    }

    private void populateServiceTab(SettingsData data) {
        brokkKeyField.setText(data.brokkApiKey());
        balanceField.setText(data.accountBalance()); // Pre-loaded from background
        updateSignupLabelVisibility();

        if (brokkProxyRadio != null && localhostProxyRadio != null) {
            if (MainProject.getProxySetting() == MainProject.LlmProxySetting.BROKK) {
                brokkProxyRadio.setSelected(true);
            } else {
                localhostProxyRadio.setSelected(true);
            }
        }

        if (forceToolEmulationCheckbox != null) {
            forceToolEmulationCheckbox.setSelected(MainProject.getForceToolEmulation());
        }
    }

    private void populateAppearanceTab() {
        // Theme
        String currentTheme = MainProject.getTheme();
        switch (currentTheme) {
            case GuiTheme.THEME_DARK -> darkThemeRadio.setSelected(true);
            case GuiTheme.THEME_DARK_PLUS -> darkPlusThemeRadio.setSelected(true);
            case GuiTheme.THEME_LIGHT_PLUS -> lightPlusThemeRadio.setSelected(true);
            case GuiTheme.THEME_HIGH_CONTRAST -> highContrastThemeRadio.setSelected(true);
            default -> lightThemeRadio.setSelected(true);
        }

        // Code Block Layout
        wordWrapCheckbox.setSelected(MainProject.getCodeBlockWrapMode());
        classicBrokkViewCheckbox.setSelected(!GlobalUiSettings.isVerticalActivityLayout());

        // UI Scale (if present; hidden on macOS)
        if (uiScaleAutoRadio != null && uiScaleCustomRadio != null && uiScaleCombo != null) {
            String pref = MainProject.getUiScalePref();
            if ("auto".equalsIgnoreCase(pref)) {
                uiScaleAutoRadio.setSelected(true);
                uiScaleCombo.setSelectedItem("1.0");
                uiScaleCombo.setEnabled(false);
            } else {
                uiScaleCustomRadio.setSelected(true);
                var model = (DefaultComboBoxModel<String>) uiScaleCombo.getModel();
                String selected = pref;
                boolean found = false;
                for (int i = 0; i < model.getSize(); i++) {
                    if (pref.equals(model.getElementAt(i))) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    try {
                        double v = Double.parseDouble(pref);
                        int nearest = (int) Math.round(v);
                        if (nearest < 1) nearest = 1;
                        if (nearest > 5) nearest = 5;
                        selected = nearest + ".0";
                    } catch (NumberFormatException ignore) {
                        selected = "1.0";
                    }
                }
                uiScaleCombo.setSelectedItem(selected);
                uiScaleCombo.setEnabled(true);
            }
        }

        terminalFontSizeSpinner.setValue((double) MainProject.getTerminalFontSize());

        // Diff View preference
        boolean unified = GlobalUiSettings.isDiffUnifiedView();
        diffUnifiedRadio.setSelected(unified);
        diffSideBySideRadio.setSelected(!unified);
    }

    private void populateStartupTab() {
        var startupMode = MainProject.getStartupOpenMode();
        if (startupMode == MainProject.StartupOpenMode.ALL) {
            startupOpenAllRadio.setSelected(true);
        } else {
            startupOpenLastRadio.setSelected(true);
        }

        persistPerProjectWindowCheckbox.setSelected(GlobalUiSettings.isPersistPerProjectBounds());
        instructionsTabInsertIndentationCheckbox.setSelected(GlobalUiSettings.isInstructionsTabInsertIndentation());
    }

    private void populateNotificationsTab() {
        showCostNotificationsCheckbox.setSelected(GlobalUiSettings.isShowCostNotifications());
        showFreeInternalLLMCheckbox.setSelected(GlobalUiSettings.isShowFreeInternalLLMCostNotifications());
        showErrorNotificationsCheckbox.setSelected(GlobalUiSettings.isShowErrorNotifications());
        showConfirmNotificationsCheckbox.setSelected(GlobalUiSettings.isShowConfirmNotifications());
        showInfoNotificationsCheckbox.setSelected(GlobalUiSettings.isShowInfoNotifications());
    }

    private void populateQuickModelsTab(SettingsData data) {
        var service = chrome.getContextManager().getService();
        var loadedFavorites = data.favoriteModels();
        quickModelsTableModel.setFavorites(loadedFavorites);

        // Set up renderer for both combos to display favorite alias
        var favoriteRenderer = new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, @Nullable Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Service.FavoriteModel fm) {
                    String name = fm.config().name();
                    String alias = fm.alias();
                    lbl.setText(alias.isBlank() ? name : alias + " (" + name + ")");
                }
                return lbl;
            }
        };
        preferredCodeModelCombo.setRenderer(favoriteRenderer);
        primaryModelCombo.setRenderer(favoriteRenderer);

        // Populate Lutz Code Model combo with favorites
        var currentCodeConfig = chrome.getProject().getMainProject().getCodeModelConfig();
        preferredCodeModelCombo.removeAllItems();
        for (Service.FavoriteModel favorite : loadedFavorites) {
            preferredCodeModelCombo.addItem(favorite);
        }
        // Select the item whose config matches the current code config
        boolean foundCode = false;
        for (int i = 0; i < preferredCodeModelCombo.getItemCount(); i++) {
            Service.FavoriteModel fm = preferredCodeModelCombo.getItemAt(i);
            if (fm != null && fm.config().equals(currentCodeConfig)) {
                preferredCodeModelCombo.setSelectedIndex(i);
                foundCode = true;
                break;
            }
        }
        if (!foundCode && preferredCodeModelCombo.getItemCount() > 0) {
            preferredCodeModelCombo.setSelectedIndex(0);
        }

        // Populate Primary Model combo with favorites
        var currentPlannerConfig = chrome.getProject().getMainProject().getPrimaryModelConfig();
        primaryModelCombo.removeAllItems();
        for (Service.FavoriteModel favorite : loadedFavorites) {
            primaryModelCombo.addItem(favorite);
        }
        // Select the item whose config matches the current planner config
        boolean foundPrimary = false;
        for (int i = 0; i < primaryModelCombo.getItemCount(); i++) {
            Service.FavoriteModel fm = primaryModelCombo.getItemAt(i);
            if (fm != null && fm.config().equals(currentPlannerConfig)) {
                primaryModelCombo.setSelectedIndex(i);
                foundPrimary = true;
                break;
            }
        }
        if (!foundPrimary && primaryModelCombo.getItemCount() > 0) {
            primaryModelCombo.setSelectedIndex(0);
        }

        // Build vendor list based on model availability
        var availableNames = service.getAvailableModels().keySet();
        var vendors = new ArrayList<String>();
        vendors.add("Default");
        if (availableNames.contains(Service.HAIKU_4_5)) {
            vendors.add("Anthropic");
        }
        if (availableNames.contains(Service.GPT_5_NANO) && availableNames.contains(Service.GPT_5_MINI)) {
            vendors.add("OpenAI");
        }

        // Rebuild combo with available vendors
        otherModelsVendorCombo.setModel(new DefaultComboBoxModel<>(vendors.toArray(new String[0])));

        // Apply persisted preference if present and valid; otherwise fall back to Default
        String persistedVendor = MainProject.getOtherModelsVendorPreference();
        String vendorToSelect;
        if (!persistedVendor.isBlank() && vendors.contains(persistedVendor)) {
            vendorToSelect = persistedVendor;
        } else {
            vendorToSelect = "Default";
        }
        otherModelsVendorCombo.setSelectedItem(vendorToSelect);

        // Hide vendor row if only one option remains
        boolean hideVendorRow = vendors.size() <= 1;
        if (otherModelsVendorLabel != null) {
            otherModelsVendorLabel.setVisible(!hideVendorRow);
        }
        if (otherModelsVendorHolder != null) {
            otherModelsVendorHolder.setVisible(!hideVendorRow);
            if (otherModelsVendorHolder.getParent() != null) {
                otherModelsVendorHolder.getParent().revalidate();
                otherModelsVendorHolder.getParent().repaint();
            }
        }

        // Add listener to sync primary combo when model changes in InstructionsPanel
        if (!plannerModelSyncListenerRegistered) {
            chrome.getInstructionsPanel().addModelSelectionListener(cfg -> {
                try {
                    // Sync primary combo with the selected config by equality
                    boolean found = false;
                    for (int i = 0; i < primaryModelCombo.getItemCount(); i++) {
                        Service.FavoriteModel fm = primaryModelCombo.getItemAt(i);
                        if (fm != null && fm.config().equals(cfg)) {
                            int finalI = i;
                            SwingUtilities.invokeLater(() -> primaryModelCombo.setSelectedIndex(finalI));
                            found = true;
                            break;
                        }
                    }
                    if (!found && primaryModelCombo.getItemCount() > 0) {
                        SwingUtilities.invokeLater(() -> primaryModelCombo.setSelectedIndex(0));
                    }
                } catch (Exception ex) {
                    logger.debug("Planner model sync listener failed (non-fatal)", ex);
                }
            });
            plannerModelSyncListenerRegistered = true;
        }
    }

    private void populateGitHubTab() {
        gitHubSettingsPanel.loadSettings();
    }

    private void populateMcpServersTab() {
        mcpServersListModel.clear();
        var mcpConfig = chrome.getProject().getMainProject().getMcpConfig();
        for (McpServer server : mcpConfig.servers()) {
            mcpServersListModel.addElement(server);
        }
    }

    private void initComponents() {
        // globalSubTabbedPane is already initialized

        // General Tab
        var generalPanel = createGeneralPanel();
        globalSubTabbedPane.addTab("General", null, generalPanel, "General settings");

        // Service Tab
        var servicePanel = createServicePanel();
        globalSubTabbedPane.addTab("Service", null, servicePanel, "Service configuration");

        // Appearance Tab
        var appearancePanel = createAppearancePanel();
        globalSubTabbedPane.addTab("Appearance", null, appearancePanel, "Theme settings");

        // Models Tab
        var modelsPanel = createQuickModelsPanel();
        globalSubTabbedPane.addTab(MODELS_TAB_TITLE, null, modelsPanel, "Configure models and favorites");

        // GitHub Tab (always shown - global account configuration)
        gitHubSettingsPanel = new GitHubSettingsPanel(chrome.getContextManager(), this);
        globalSubTabbedPane.addTab(
                SettingsDialog.GITHUB_SETTINGS_TAB_NAME, null, gitHubSettingsPanel, "GitHub integration settings");

        // MCP Servers Tab
        var mcpPanel = createMcpPanel();
        globalSubTabbedPane.addTab("MCP Servers", null, mcpPanel, "MCP server configuration");

        // Startup Tab
        var startupPanel = createStartupPanel();
        globalSubTabbedPane.addTab("Startup", null, startupPanel, "Startup behavior");

        // Keybindings Tab (wrapped in a scroll pane to ensure visibility on smaller screens)
        var keybindingsPanel = createKeybindingsPanel();
        var keybindingsScroll = new JScrollPane(keybindingsPanel);
        keybindingsScroll.setBorder(BorderFactory.createEmptyBorder());
        keybindingsScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        keybindingsScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        globalSubTabbedPane.addTab("Keybindings", null, keybindingsScroll, "Configure keyboard shortcuts");

        var notificationsPanel = createNotificationsPanel();
        globalSubTabbedPane.addTab("Notifications", null, notificationsPanel, "Notification preferences");

        add(globalSubTabbedPane, BorderLayout.CENTER);
    }

    /**
     * Asynchronously saves a keybinding to persistent storage and provides optional UI feedback.
     *
     * @param id                 the keybinding id (e.g., "instructions.submit").
     * @param stroke             the KeyStroke to persist.
     * @param refreshKeybindings whether to call chrome.refreshKeybindings() on success.
     * @param onSuccessMessage   optional Runnable to execute on success (e.g., to show a dialog).
     * @param dialogParent       the component to use as parent for JOptionPane dialogs.
     * @param failureContext     short string describing what failed (e.g., "save keybinding", "clear keybinding").
     */
    private void saveKeybindingAsync(
            String id,
            KeyStroke stroke,
            boolean refreshKeybindings,
            @Nullable Runnable onSuccessMessage,
            Component dialogParent,
            String failureContext) {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                GlobalUiSettings.saveKeybinding(id, stroke);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get(); // Check if background save succeeded
                    if (refreshKeybindings) {
                        try {
                            chrome.refreshKeybindings();
                        } catch (Exception ex) {
                            logger.debug("refreshKeybindings failed (non-fatal)", ex);
                        }
                    }
                    if (onSuccessMessage != null) {
                        onSuccessMessage.run();
                    }
                } catch (Exception ex) {
                    logger.error("Failed to {}", failureContext, ex);
                    JOptionPane.showMessageDialog(
                            dialogParent,
                            "Failed to " + failureContext + ": " + ex.getMessage(),
                            "Save Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private JPanel createKeybindingsPanel() {
        var panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        final AtomicInteger row = new AtomicInteger(0);

        class RowAdder {
            void add(String id, String label) {
                KeyStroke def = defaultFor(id);
                KeyStroke cur = GlobalUiSettings.getKeybinding(id, def);

                int r = row.getAndIncrement();
                var gbcLabel = new GridBagConstraints();
                gbcLabel.insets = new Insets(4, 6, 4, 6);
                gbcLabel.anchor = GridBagConstraints.WEST;
                gbcLabel.gridx = 0;
                gbcLabel.gridy = r;
                gbcLabel.weightx = 0.0;
                panel.add(new JLabel(label + ":"), gbcLabel);

                var field = new JTextField(formatKeyStroke(cur));
                field.setEditable(false);
                var gbcField = new GridBagConstraints();
                gbcField.insets = new Insets(4, 6, 4, 6);
                gbcField.fill = GridBagConstraints.HORIZONTAL;
                gbcField.gridx = 1;
                gbcField.gridy = r;
                gbcField.weightx = 1.0;
                panel.add(field, gbcField);

                var setBtn = new MaterialButton("Set");
                var gbcSet = new GridBagConstraints();
                gbcSet.insets = new Insets(4, 6, 4, 6);
                gbcSet.gridx = 2;
                gbcSet.gridy = r;
                gbcSet.weightx = 0.0;
                panel.add(setBtn, gbcSet);

                var clearBtn = new MaterialButton("Clear");
                var gbcClear = new GridBagConstraints();
                gbcClear.insets = new Insets(4, 6, 4, 6);
                gbcClear.gridx = 3;
                gbcClear.gridy = r;
                gbcClear.weightx = 0.0;
                panel.add(clearBtn, gbcClear);

                setBtn.addActionListener(ev -> {
                    KeyStroke captured = captureKeyStroke(panel);
                    if ("global.closeWindow".equals(id)
                            && captured.getKeyCode() == KeyEvent.VK_ESCAPE
                            && captured.getModifiers() == 0) {
                        JOptionPane.showMessageDialog(
                                panel,
                                "ESC alone cannot be used for Close Window.",
                                "Invalid Shortcut",
                                JOptionPane.WARNING_MESSAGE);
                        return;
                    }

                    // Check for conflicts
                    String conflictingAction = findConflictingKeybinding(captured, id);
                    if (conflictingAction != null) {
                        int result = JOptionPane.showConfirmDialog(
                                panel,
                                String.format(
                                        "This shortcut is already used by '%s'. Do you want to reassign it?",
                                        conflictingAction),
                                "Shortcut Conflict",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE);
                        if (result != JOptionPane.YES_OPTION) {
                            return;
                        }
                    }

                    // Update field and save keybinding in background
                    field.setText(formatKeyStroke(captured));
                    Runnable onSuccess = () -> JOptionPane.showMessageDialog(panel, "Saved and applied.");
                    saveKeybindingAsync(id, captured, true, onSuccess, panel, "save keybinding");
                });

                clearBtn.addActionListener(ev -> {
                    field.setText(formatKeyStroke(def));
                    saveKeybindingAsync(id, def, false, null, panel, "clear keybinding");
                });
            }
        }

        RowAdder adder = new RowAdder();
        // Instructions panel
        adder.add("instructions.submit", "Submit (Enter)");
        adder.add("instructions.toggleMode", "Toggle Code/Ask/Lutz");

        // Global text editing
        adder.add("global.undo", "Undo");
        adder.add("global.redo", "Redo");
        adder.add("global.copy", "Copy");
        adder.add("global.paste", "Paste");

        // Voice and interface
        adder.add("global.toggleMicrophone", "Toggle Microphone");

        // Panel navigation
        adder.add("panel.switchToProjectFiles", "Switch to Project Files");
        adder.add("panel.switchToDependencies", "Switch to Dependencies");
        adder.add("panel.switchToChanges", "Switch to Changes");
        adder.add("panel.switchToWorktrees", "Switch to Worktrees");
        adder.add("panel.switchToLog", "Switch to Log");
        adder.add("panel.switchToPullRequests", "Switch to Pull Requests");
        adder.add("panel.switchToIssues", "Switch to Issues");

        // Drawer navigation
        adder.add("drawer.toggleTerminal", "Toggle Terminal Drawer");
        adder.add("drawer.toggleDependencies", "Toggle Dependencies Drawer");
        adder.add("drawer.switchToTerminal", "Switch to Terminal Tab");
        adder.add("drawer.switchToTasks", "Switch to Tasks Tab");

        // View controls
        adder.add("view.zoomIn", "Zoom In");
        adder.add("view.zoomInAlt", "Zoom In (Alt)");
        adder.add("view.zoomOut", "Zoom Out");
        adder.add("view.resetZoom", "Reset Zoom");

        // General navigation
        adder.add("global.openSettings", "Open Settings");
        adder.add("global.closeWindow", "Close Window");

        // Workspace actions
        adder.add("workspace.attachContext", "Add Content to Workspace");
        adder.add("workspace.attachFilesAndSummarize", "Attach Files + Summarize");

        // Add global reset button
        var resetAllBtn = new JButton("Reset All to Defaults");
        resetAllBtn.setToolTipText("Reset all keybindings to their default values");
        var gbcReset = new GridBagConstraints();
        gbcReset.gridx = 0;
        gbcReset.gridy = row.getAndIncrement();
        gbcReset.gridwidth = 4;
        gbcReset.insets = new Insets(20, 6, 6, 6);
        gbcReset.anchor = GridBagConstraints.CENTER;
        gbcReset.fill = GridBagConstraints.NONE;
        panel.add(resetAllBtn, gbcReset);

        resetAllBtn.addActionListener(ev -> {
            int result = JOptionPane.showConfirmDialog(
                    panel,
                    "This will reset ALL keybindings to their default values. Are you sure?",
                    "Reset All Keybindings",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (result == JOptionPane.YES_OPTION) {
                resetAllKeybindingsToDefaults();
                // Refresh the keybindings
                SwingUtilities.invokeLater(() -> {
                    try {
                        chrome.refreshKeybindings();
                        JOptionPane.showMessageDialog(
                                panel,
                                "All keybindings have been reset to defaults. Please close and reopen this settings dialog to see the updated values.");
                    } catch (Exception ex) {
                        logger.debug("Failed to refresh keybindings after reset", ex);
                    }
                });
            }
        });

        var gbcSpacer = new GridBagConstraints();
        gbcSpacer.gridx = 0;
        gbcSpacer.gridy = row.get();
        gbcSpacer.weightx = 1.0;
        gbcSpacer.weighty = 1.0;
        gbcSpacer.fill = GridBagConstraints.BOTH;
        panel.add(Box.createGlue(), gbcSpacer);

        return panel;
    }

    private static String formatKeyStroke(KeyStroke ks) {
        try {
            int modifiers = ks.getModifiers();
            int keyCode = ks.getKeyCode();
            String modText = InputEvent.getModifiersExText(modifiers);
            String keyText = KeyEvent.getKeyText(keyCode);
            if (modText == null || modText.isBlank()) return keyText;
            return modText + "+" + keyText;
        } catch (Exception e) {
            return ks.toString();
        }
    }

    private static KeyStroke captureKeyStroke(Component parent) {
        final KeyStroke[] result = new KeyStroke[1];
        final KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        KeyEventDispatcher[] ref = new KeyEventDispatcher[1];

        var dialog = new BaseThemedDialog((Window) null, "Press new shortcut");
        var label = new JLabel("Press the desired key combination now (ESC to cancel)...");
        label.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        dialog.getContentRoot().add(label);
        dialog.setSize(420, 140);
        dialog.setLocationRelativeTo(parent);

        KeyEventDispatcher dispatcher = e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED) return false;
            int code = e.getKeyCode();
            // Ignore pure modifier presses; wait for a real key
            if (code == KeyEvent.VK_SHIFT
                    || code == KeyEvent.VK_CONTROL
                    || code == KeyEvent.VK_ALT
                    || code == KeyEvent.VK_META) {
                return true;
            }
            if (code == KeyEvent.VK_ESCAPE && e.getModifiersEx() == 0) {
                result[0] = null; // cancel
            } else {
                result[0] = KeyStroke.getKeyStroke(code, e.getModifiersEx());
            }
            dialog.dispose();
            return true;
        };
        ref[0] = dispatcher;
        kfm.addKeyEventDispatcher(ref[0]);

        try {
            dialog.setFocusable(true);
            dialog.setFocusableWindowState(true);
            dialog.setAlwaysOnTop(true);
            dialog.setVisible(true);
        } finally {
            if (ref[0] != null) kfm.removeKeyEventDispatcher(ref[0]);
        }
        return result[0] == null ? KeyStroke.getKeyStroke(0, 0) : result[0];
    }

    /** Checks if a KeyStroke conflicts with any existing keybinding. */
    private static @Nullable String findConflictingKeybinding(KeyStroke newKeyStroke, String excludeId) {
        if (newKeyStroke.getKeyCode() == 0) return null;

        // List of all keybinding IDs to check
        String[] allKeybindingIds = {
            "instructions.submit",
            "instructions.toggleMode",
            "global.undo",
            "global.redo",
            "global.copy",
            "global.paste",
            "global.toggleMicrophone",
            "global.openSettings",
            "global.closeWindow",
            "panel.switchToProjectFiles",
            "panel.switchToDependencies",
            "panel.switchToChanges",
            "panel.switchToWorktrees",
            "panel.switchToLog",
            "panel.switchToPullRequests",
            "panel.switchToIssues",
            "drawer.toggleTerminal",
            "drawer.toggleDependencies",
            "drawer.switchToTerminal",
            "drawer.switchToTasks",
            "view.zoomIn",
            "view.zoomInAlt",
            "view.zoomOut",
            "view.resetZoom",
            "workspace.attachContext",
            "workspace.attachFilesAndSummarize"
        };

        for (String id : allKeybindingIds) {
            if (id.equals(excludeId)) continue; // Skip the one we're setting

            KeyStroke existing = GlobalUiSettings.getKeybinding(id, defaultFor(id));
            if (existing.equals(newKeyStroke)) {
                return getKeybindingDisplayName(id);
            }
        }
        return null;
    }

    /** Gets a human-readable display name for a keybinding ID. */
    private static String getKeybindingDisplayName(String id) {
        return switch (id) {
            case "instructions.submit" -> "Submit (Enter)";
            case "instructions.toggleMode" -> "Toggle Code/Ask/Lutz";
            case "global.undo" -> "Undo";
            case "global.redo" -> "Redo";
            case "global.copy" -> "Copy";
            case "global.paste" -> "Paste";
            case "global.toggleMicrophone" -> "Toggle Microphone";
            case "global.openSettings" -> "Open Settings";
            case "global.closeWindow" -> "Close Window";
            case "panel.switchToProjectFiles" -> "Switch to Project Files";
            case "panel.switchToDependencies" -> "Switch to Dependencies";
            case "panel.switchToChanges" -> "Switch to Changes";
            case "panel.switchToWorktrees" -> "Switch to Worktrees";
            case "panel.switchToLog" -> "Switch to Log";
            case "panel.switchToPullRequests" -> "Switch to Pull Requests";
            case "panel.switchToIssues" -> "Switch to Issues";
            case "drawer.toggleTerminal" -> "Toggle Terminal Drawer";
            case "drawer.toggleDependencies" -> "Toggle Dependencies Drawer";
            case "drawer.switchToTerminal" -> "Switch to Terminal Tab";
            case "drawer.switchToTasks" -> "Switch to Tasks Tab";
            case "view.zoomIn" -> "Zoom In";
            case "view.zoomInAlt" -> "Zoom In (Alt)";
            case "view.zoomOut" -> "Zoom Out";
            case "view.resetZoom" -> "Reset Zoom";
            case "workspace.attachContext" -> "Add Content to Workspace";
            case "workspace.attachFilesAndSummarize" -> "Attach Files + Summarize";
            default -> id;
        };
    }

    /** Resets all keybindings to their default values. */
    private static void resetAllKeybindingsToDefaults() {
        String[] allKeybindingIds = {
            "instructions.submit",
            "instructions.toggleMode",
            "global.undo",
            "global.redo",
            "global.copy",
            "global.paste",
            "global.toggleMicrophone",
            "global.openSettings",
            "global.closeWindow",
            "panel.switchToProjectFiles",
            "panel.switchToDependencies",
            "panel.switchToChanges",
            "panel.switchToWorktrees",
            "panel.switchToLog",
            "panel.switchToPullRequests",
            "panel.switchToIssues",
            "drawer.toggleTerminal",
            "drawer.toggleDependencies",
            "drawer.switchToTerminal",
            "drawer.switchToTasks",
            "view.zoomIn",
            "view.zoomInAlt",
            "view.zoomOut",
            "view.resetZoom",
            "workspace.attachContext",
            "workspace.attachFilesAndSummarize"
        };

        for (String id : allKeybindingIds) {
            KeyStroke defaultValue = defaultFor(id);
            GlobalUiSettings.saveKeybinding(id, defaultValue);
        }
    }

    public JTabbedPane getGlobalSubTabbedPane() {
        return globalSubTabbedPane;
    }

    private JPanel createGeneralPanel() {
        var panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        // Title
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("JVM Memory Allocation:"), gbc);

        // Radio group
        var memoryGroup = new ButtonGroup();
        memoryGroup.add(memoryAutoRadio);
        memoryGroup.add(memoryManualRadio);

        // Auto option
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        var autoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        autoPanel.add(memoryAutoRadio);
        panel.add(autoPanel, gbc);

        // Manual option with spinner
        var spinnerModel = new SpinnerNumberModel(4096, 512, 16384, 128);
        try {
            long maxBytes = Runtime.getRuntime().maxMemory();
            int detectedMb;
            if (maxBytes <= 0 || maxBytes == Long.MAX_VALUE) {
                detectedMb = 4096; // fallback when not detectable
            } else {
                detectedMb = (int) Math.round(maxBytes / 1024.0 / 1024.0);
                if (detectedMb < 512) detectedMb = 512;
                if (detectedMb > 16384) detectedMb = 16384;
                // Snap to nearest 128MB for nicer alignment with step size
                detectedMb = ((detectedMb + 64) / 128) * 128;
            }
            spinnerModel.setValue(detectedMb);
        } catch (Exception ignored) {
            spinnerModel.setValue(4096);
        }
        memorySpinner.setModel(spinnerModel);
        memorySpinner.setEditor(new JSpinner.NumberEditor(memorySpinner, "#0"));

        var manualPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        manualPanel.add(memoryManualRadio);
        manualPanel.add(Box.createHorizontalStrut(5));
        manualPanel.add(memorySpinner);
        manualPanel.add(Box.createHorizontalStrut(5));
        manualPanel.add(new JLabel("MB"));

        gbc.gridy = row++;
        panel.add(manualPanel, gbc);

        // Listeners to enable/disable spinner
        memoryAutoRadio.addActionListener(e -> memorySpinner.setEnabled(false));
        memoryManualRadio.addActionListener(e -> memorySpinner.setEnabled(true));

        // Default selection
        memoryAutoRadio.setSelected(true);
        memorySpinner.setEnabled(false);

        // Restart note
        var restartLabel = new JLabel("Restart required after changing memory settings");
        restartLabel.setFont(restartLabel.getFont().deriveFont(Font.ITALIC));
        gbc.gridy = row++;
        gbc.insets = new Insets(0, 25, 2, 5);
        panel.add(restartLabel, gbc);
        gbc.insets = new Insets(2, 5, 2, 5);

        // Instructions panel behavior
        gbc.insets = new Insets(10, 5, 2, 5);
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Instructions Input:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(instructionsTabInsertIndentationCheckbox, gbc);

        // Advanced Mode
        gbc.insets = new Insets(10, 5, 2, 5);
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Interface:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(advancedModeCheckbox, gbc);

        skipCommitGateEzCheckbox.setToolTipText(
                "When EZ mode is enabled, skip the commit confirmation gate before applying changes.");
        skipCommitGateEzCheckbox.setVisible(!GlobalUiSettings.isAdvancedMode());
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(skipCommitGateEzCheckbox, gbc);

        advancedModeCheckbox.addActionListener(e -> {
            boolean advanced = advancedModeCheckbox.isSelected();
            skipCommitGateEzCheckbox.setVisible(!advanced);
            panel.revalidate();
            panel.repaint();
        });

        // File watcher implementation selector
        gbc.insets = new Insets(10, 5, 2, 5);
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("File watcher implementation:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        var watchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        watchPanel.add(watchServiceImplCombo);
        panel.add(watchPanel, gbc);

        var watchNote =
                new JLabel("<html><i>Changing this will require restarting Brokk to fully take effect.</i></html>");
        gbc.gridy = row++;
        gbc.insets = new Insets(0, 25, 2, 5);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(watchNote, gbc);

        gbc.insets = new Insets(2, 5, 2, 5);

        // Filler
        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        panel.add(Box.createVerticalGlue(), gbc);

        return panel;
    }

    private JPanel createServicePanel() {
        var servicePanel = new JPanel(new GridBagLayout());
        servicePanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        servicePanel.add(new JLabel("Brokk Key:"), gbc);

        brokkKeyField = new JTextField(20);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        servicePanel.add(brokkKeyField, gbc);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        servicePanel.add(new JLabel("Balance:"), gbc);

        this.balanceField = new JTextField("Loading...");
        this.balanceField.setEditable(false);
        this.balanceField.setColumns(8);
        var balanceDisplayPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        balanceDisplayPanel.add(this.balanceField);
        var topUpUrl = Service.TOP_UP_URL;
        var topUpLabel = new BrowserLabel(topUpUrl, "Top Up");
        balanceDisplayPanel.add(topUpLabel);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        servicePanel.add(balanceDisplayPanel, gbc);

        var signupUrl = "https://brokk.ai";
        this.signupLabel = new BrowserLabel(signupUrl, "Sign up or get your key at " + signupUrl);
        this.signupLabel.setFont(this.signupLabel.getFont().deriveFont(Font.ITALIC));
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(2, 5, 8, 5);
        servicePanel.add(this.signupLabel, gbc);
        gbc.insets = new Insets(2, 5, 2, 5);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        servicePanel.add(new JLabel("LLM Proxy:"), gbc);

        if (MainProject.getProxySetting() == MainProject.LlmProxySetting.STAGING) {
            var proxyInfoLabel = new JLabel(
                    "Proxy has been set to STAGING in ~/.brokk/brokk.properties. Changing it back must be done in the same place.");
            proxyInfoLabel.setFont(proxyInfoLabel.getFont().deriveFont(Font.ITALIC));
            gbc.gridx = 1;
            gbc.gridy = row++;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            servicePanel.add(proxyInfoLabel, gbc);
        } else {
            brokkProxyRadio = new JRadioButton("Brokk");
            localhostProxyRadio = new JRadioButton("Localhost");
            var proxyGroup = new ButtonGroup();
            proxyGroup.add(brokkProxyRadio);
            proxyGroup.add(localhostProxyRadio);

            gbc.gridx = 1;
            gbc.gridy = row++;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            servicePanel.add(brokkProxyRadio, gbc);

            gbc.gridy = row++;
            servicePanel.add(localhostProxyRadio, gbc);

            var proxyInfoLabel = new JLabel("Brokk will look for a litellm proxy on localhost:4000");
            proxyInfoLabel.setFont(proxyInfoLabel.getFont().deriveFont(Font.ITALIC));
            gbc.insets = new Insets(0, 25, 2, 5);
            gbc.gridy = row++;
            servicePanel.add(proxyInfoLabel, gbc);

            var restartLabel = new JLabel("Restart required after changing proxy settings");
            restartLabel.setFont(restartLabel.getFont().deriveFont(Font.ITALIC));
            gbc.gridy = row++;
            servicePanel.add(restartLabel, gbc);
            gbc.insets = new Insets(2, 5, 2, 5);
        }

        // Dev-only: Force tool emulation checkbox
        if (Boolean.getBoolean("brokk.devmode")) {
            forceToolEmulationCheckbox = new JCheckBox(
                    "[Dev Mode] Force tool emulation (also show empty workspace chips)",
                    MainProject.getForceToolEmulation());
            forceToolEmulationCheckbox.setToolTipText(
                    "Development override: emulate tool calls. Also forces the UI to show visually-empty workspace chips for debugging and testing only.\n\n"
                            + "Dev note: When enabled, the UI will also show empty/placeholder workspace chips to aid debugging.");
            gbc.gridx = 1;
            gbc.gridy = row++;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            servicePanel.add(forceToolEmulationCheckbox, gbc);
        }

        // Cost notifications moved to Notifications tab

        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        servicePanel.add(Box.createVerticalGlue(), gbc);

        return servicePanel;
    }

    private void updateSignupLabelVisibility() {
        String currentPersistedKey = MainProject.getBrokkKey(); // Read from persistent store
        boolean keyIsEffectivelyPresent = !currentPersistedKey.trim().isEmpty();
        this.signupLabel.setVisible(!keyIsEffectivelyPresent);
    }

    private JPanel createAppearancePanel() {
        var appearancePanel = new JPanel(new GridBagLayout());
        appearancePanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        // Theme
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        appearancePanel.add(new JLabel("Theme:"), gbc);

        lightThemeRadio = new JRadioButton("Light");
        lightPlusThemeRadio = new JRadioButton("Light+");
        darkThemeRadio = new JRadioButton("Dark");
        darkPlusThemeRadio = new JRadioButton("Dark+");
        highContrastThemeRadio = new JRadioButton("High Contrast");
        var themeGroup = new ButtonGroup();
        themeGroup.add(lightThemeRadio);
        themeGroup.add(lightPlusThemeRadio);
        themeGroup.add(darkThemeRadio);
        themeGroup.add(darkPlusThemeRadio);
        themeGroup.add(highContrastThemeRadio);

        lightThemeRadio.putClientProperty("theme", GuiTheme.THEME_LIGHT);
        lightPlusThemeRadio.putClientProperty("theme", GuiTheme.THEME_LIGHT_PLUS);
        darkThemeRadio.putClientProperty("theme", GuiTheme.THEME_DARK);
        darkPlusThemeRadio.putClientProperty("theme", GuiTheme.THEME_DARK_PLUS);
        highContrastThemeRadio.putClientProperty("theme", GuiTheme.THEME_HIGH_CONTRAST);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        appearancePanel.add(lightThemeRadio, gbc);

        gbc.gridy = row++;
        appearancePanel.add(lightPlusThemeRadio, gbc);

        gbc.gridy = row++;
        appearancePanel.add(darkThemeRadio, gbc);

        gbc.gridy = row++;
        appearancePanel.add(darkPlusThemeRadio, gbc);

        gbc.gridy = row++;
        appearancePanel.add(highContrastThemeRadio, gbc);

        // Word wrap for code blocks
        gbc.insets = new Insets(10, 5, 2, 5); // spacing before next section
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        appearancePanel.add(new JLabel("Code Block Layout:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        appearancePanel.add(wordWrapCheckbox, gbc);

        // Vertical Activity Layout (now labeled as Classic Brokk View)
        gbc.insets = new Insets(10, 5, 2, 5);
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        appearancePanel.add(new JLabel("Activity Layout:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        appearancePanel.add(classicBrokkViewCheckbox, gbc);

        gbc.insets = new Insets(2, 5, 2, 5); // reset spacing

        // Diff View
        gbc.insets = new Insets(10, 5, 2, 5); // spacing before next section
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        appearancePanel.add(new JLabel("Diff View:"), gbc);

        var diffViewGroup = new ButtonGroup();
        diffViewGroup.add(diffSideBySideRadio);
        diffViewGroup.add(diffUnifiedRadio);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        appearancePanel.add(diffSideBySideRadio, gbc);

        gbc.gridy = row++;
        appearancePanel.add(diffUnifiedRadio, gbc);

        gbc.insets = new Insets(2, 5, 2, 5); // reset spacing
        //
        // Detect JetBrains Runtime (JBR) and defer to its HiDPI handling if present
        var vmVendor = System.getProperty("java.vm.vendor", "");
        var runtimeName = System.getProperty("java.runtime.name", "");
        boolean isJbr = vmVendor.toLowerCase(Locale.ROOT).contains("jetbrains")
                || runtimeName.toLowerCase(Locale.ROOT).contains("jetbrains");

        // UI Scale controls (hidden on macOS)
        if (!Environment.isMacOs() && !isJbr) {
            gbc.insets = new Insets(10, 5, 2, 5); // spacing before next section
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0.0;
            gbc.fill = GridBagConstraints.NONE;
            appearancePanel.add(new JLabel("UI Scale:"), gbc);

            uiScaleAutoRadio = new JRadioButton("Auto (recommended)");
            uiScaleCustomRadio = new JRadioButton("Custom:");
            var scaleGroup = new ButtonGroup();
            scaleGroup.add(uiScaleAutoRadio);
            scaleGroup.add(uiScaleCustomRadio);

            uiScaleCombo = new JComboBox<>();
            final JComboBox<String> combo = uiScaleCombo;
            var uiScaleModel = new DefaultComboBoxModel<String>();
            uiScaleModel.addElement("1.0");
            uiScaleModel.addElement("2.0");
            uiScaleModel.addElement("3.0");
            uiScaleModel.addElement("4.0");
            uiScaleModel.addElement("5.0");
            combo.setModel(uiScaleModel);
            combo.setEnabled(false);

            uiScaleAutoRadio.addActionListener(e -> combo.setEnabled(false));
            uiScaleCustomRadio.addActionListener(e -> combo.setEnabled(true));

            gbc.gridx = 1;
            gbc.gridy = row++;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            appearancePanel.add(uiScaleAutoRadio, gbc);

            var customPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            customPanel.add(uiScaleCustomRadio);
            customPanel.add(combo);

            gbc.gridy = row++;
            appearancePanel.add(customPanel, gbc);

            var restartLabel = new JLabel("Restart required after changing UI scale");
            restartLabel.setFont(restartLabel.getFont().deriveFont(Font.ITALIC));
            gbc.gridy = row++;
            gbc.insets = new Insets(0, 25, 2, 5);
            appearancePanel.add(restartLabel, gbc);
            gbc.insets = new Insets(2, 5, 2, 5);
        } else {
            uiScaleAutoRadio = null;
            uiScaleCustomRadio = null;
            uiScaleCombo = null;
        }

        // Terminal font size
        gbc.insets = new Insets(10, 5, 2, 5); // spacing
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        appearancePanel.add(new JLabel("Terminal Font Size:"), gbc);

        var fontSizeModel = new SpinnerNumberModel(11.0, 8.0, 36.0, 0.5);
        terminalFontSizeSpinner.setModel(fontSizeModel);
        terminalFontSizeSpinner.setEditor(new JSpinner.NumberEditor(terminalFontSizeSpinner, "#0.0"));

        var terminalFontSizePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        terminalFontSizePanel.add(terminalFontSizeSpinner);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        appearancePanel.add(terminalFontSizePanel, gbc);

        gbc.insets = new Insets(2, 5, 2, 5); // reset insets

        // filler
        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        appearancePanel.add(Box.createVerticalGlue(), gbc);
        return appearancePanel;
    }

    private JPanel createStartupPanel() {
        var startupPanel = new JPanel(new GridBagLayout());
        startupPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        startupPanel.add(new JLabel("On startup:"), gbc);

        var group = new ButtonGroup();
        group.add(startupOpenLastRadio);
        group.add(startupOpenAllRadio);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        startupPanel.add(startupOpenLastRadio, gbc);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        startupPanel.add(startupOpenAllRadio, gbc);

        // Per-project window bounds persistence
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        startupPanel.add(persistPerProjectWindowCheckbox, gbc);

        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        startupPanel.add(Box.createVerticalGlue(), gbc);

        return startupPanel;
    }

    private JPanel createQuickModelsPanel() {
        var service = chrome.getContextManager().getService();
        var availableModelNames =
                service.getAvailableModels().keySet().stream().sorted().toArray(String[]::new);
        var reasoningLevels = Service.ReasoningLevel.values();

        quickModelsTableModel =
                new FavoriteModelsTableModel(new ArrayList<>()); // Initial empty, loaded in loadSettings
        quickModelsTable = new JTable(quickModelsTableModel);
        quickModelsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        quickModelsTable.setRowHeight(quickModelsTable.getRowHeight() + 4);

        // Enable sorting by clicking on column headers
        var sorter = new TableRowSorter<>(quickModelsTableModel);
        // Sort the Reasoning column using enum ordinal to preserve natural order
        sorter.setComparator(2, Comparator.comparingInt(Service.ReasoningLevel::ordinal));
        var showServiceTiers = Boolean.getBoolean("brokk.servicetiers");
        if (showServiceTiers) {
            // Sort the Processing Tier column similarly when tiers are enabled
            sorter.setComparator(3, Comparator.comparingInt(Service.ProcessingTier::ordinal));
        }
        quickModelsTable.setRowSorter(sorter);

        TableColumn aliasColumn = quickModelsTable.getColumnModel().getColumn(0);
        aliasColumn.setPreferredWidth(100);

        TableColumn modelColumn = quickModelsTable.getColumnModel().getColumn(1);
        var modelComboBoxEditor = new JComboBox<>(availableModelNames);
        modelColumn.setCellEditor(new DefaultCellEditor(modelComboBoxEditor));
        modelColumn.setPreferredWidth(200);

        TableColumn reasoningColumn = quickModelsTable.getColumnModel().getColumn(2);
        var reasoningComboBoxEditor = new JComboBox<>(reasoningLevels);
        reasoningColumn.setCellEditor(new ReasoningCellEditor(reasoningComboBoxEditor, service, quickModelsTable));
        reasoningColumn.setCellRenderer(new ReasoningCellRenderer(service));
        reasoningColumn.setPreferredWidth(100);

        if (showServiceTiers) {
            TableColumn processingColumn = quickModelsTable.getColumnModel().getColumn(3);
            var processingComboBoxEditor = new JComboBox<>(Service.ProcessingTier.values());
            processingColumn.setCellEditor(
                    new ProcessingTierCellEditor(processingComboBoxEditor, service, quickModelsTable));
            processingColumn.setCellRenderer(new ProcessingTierCellRenderer(service));
            processingColumn.setPreferredWidth(120);
        } else {
            // Remove the Processing Tier column from the view when service tiers are disabled
            quickModelsTable.removeColumn(quickModelsTable.getColumnModel().getColumn(3));
        }

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var addButton = new MaterialButton();
        addButton.setIcon(Icons.ADD);
        addButton.setToolTipText("Add favorite model");
        var removeButton = new MaterialButton();
        removeButton.setIcon(Icons.REMOVE);
        removeButton.setToolTipText("Remove selected favorite model");
        var defaultsButton = new MaterialButton("Defaults");
        defaultsButton.setToolTipText("Restore default favorite models");
        buttonPanel.add(addButton);
        buttonPanel.add(removeButton);
        buttonPanel.add(defaultsButton);

        addButton.addActionListener(e -> {
            if (quickModelsTable.isEditing()) {
                quickModelsTable.getCellEditor().stopCellEditing();
            }
            String defaultModel = availableModelNames.length > 0 ? availableModelNames[0] : "";
            quickModelsTableModel.addFavorite(
                    new Service.FavoriteModel("new-alias", new Service.ModelConfig(defaultModel)));
            int modelRowIndex = quickModelsTableModel.getRowCount() - 1;
            int viewRowIndex = quickModelsTable.convertRowIndexToView(modelRowIndex);
            quickModelsTable.setRowSelectionInterval(viewRowIndex, viewRowIndex);
            quickModelsTable.scrollRectToVisible(quickModelsTable.getCellRect(viewRowIndex, 0, true));
            quickModelsTable.editCellAt(viewRowIndex, 0);
            Component editorComponent = quickModelsTable.getEditorComponent();
            if (editorComponent != null) {
                editorComponent.requestFocusInWindow();
            }
        });

        removeButton.addActionListener(e -> {
            int rowCount = quickModelsTableModel.getRowCount();
            if (rowCount <= 1) {
                JOptionPane.showMessageDialog(
                        this, "At least one favorite model is required.", "Cannot Remove", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int viewRow = quickModelsTable.getSelectedRow();
            if (viewRow != -1) {
                if (quickModelsTable.isEditing()) {
                    quickModelsTable.getCellEditor().stopCellEditing();
                }
                int modelRow = quickModelsTable.convertRowIndexToModel(viewRow);
                quickModelsTableModel.removeFavorite(modelRow);
            }
        });

        defaultsButton.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(
                    this,
                    "This will replace all your current favorite models with the default set.\n\nAre you sure you want to restore defaults?",
                    "Restore Default Favorite Models",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (result != JOptionPane.YES_OPTION) {
                return;
            }

            if (quickModelsTable.isEditing()) {
                quickModelsTable.getCellEditor().stopCellEditing();
            }

            // Restore the predefined default favorite models
            var defaultFavorites = new ArrayList<>(MainProject.DEFAULT_FAVORITE_MODELS);

            // Restore defaults and select the first one
            quickModelsTableModel.setFavorites(defaultFavorites);
            if (!defaultFavorites.isEmpty()) {
                int viewRowIndex = quickModelsTable.convertRowIndexToView(0);
                quickModelsTable.setRowSelectionInterval(viewRowIndex, viewRowIndex);
            }

            JOptionPane.showMessageDialog(
                    this,
                    "Favorite models restored to defaults.",
                    "Defaults Restored",
                    JOptionPane.INFORMATION_MESSAGE);
        });

        // Keep Remove disabled when only one row remains or when there's no selection
        Runnable updateRemoveButtonEnabled = () -> {
            boolean hasSelection = quickModelsTable.getSelectedRow() != -1;
            int rowCount = quickModelsTableModel.getRowCount();
            removeButton.setEnabled(hasSelection && rowCount > 1);
        };
        quickModelsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateRemoveButtonEnabled.run();
        });
        quickModelsTableModel.addTableModelListener(e -> updateRemoveButtonEnabled.run());
        quickModelsTableModel.addTableModelListener(
                e -> SwingUtilities.invokeLater(() -> refreshFavoriteModelCombosPreservingSelection()));
        // Initialize enabled state
        updateRemoveButtonEnabled.run();

        // Create Model Roles panel (simplified)
        var rolesPanel = new JPanel(new GridBagLayout());
        var gbcRoles = new GridBagConstraints();
        gbcRoles.insets = new Insets(5, 5, 5, 5);
        gbcRoles.anchor = GridBagConstraints.WEST;
        gbcRoles.fill = GridBagConstraints.HORIZONTAL;

        // Row 0: Primary Model
        gbcRoles.gridx = 0;
        gbcRoles.gridy = 0;
        gbcRoles.weightx = 0.0;
        gbcRoles.fill = GridBagConstraints.NONE;
        rolesPanel.add(new JLabel("Primary Model:"), gbcRoles);

        primaryModelCombo = new JComboBox<>();
        var primaryComboHolder = new JPanel(new BorderLayout(0, 0));
        primaryComboHolder.add(primaryModelCombo, BorderLayout.CENTER);
        var primaryHelpButton = new MaterialButton();
        primaryHelpButton.setIcon(Icons.HELP);
        primaryHelpButton.setToolTipText(
                "This model is used by Lutz mode for planning, coding simple tasks, and answering questions.");
        primaryHelpButton.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        primaryHelpButton.setContentAreaFilled(false);
        primaryHelpButton.setFocusPainted(false);
        primaryHelpButton.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
        primaryComboHolder.add(primaryHelpButton, BorderLayout.EAST);

        gbcRoles.gridx = 1;
        gbcRoles.weightx = 1.0;
        gbcRoles.fill = GridBagConstraints.HORIZONTAL;
        rolesPanel.add(primaryComboHolder, gbcRoles);

        // Row 1: Lutz Code Model
        gbcRoles.gridx = 0;
        gbcRoles.gridy = 1;
        gbcRoles.weightx = 0.0;
        gbcRoles.fill = GridBagConstraints.NONE;
        rolesPanel.add(new JLabel("Lutz Code Model:"), gbcRoles);

        preferredCodeModelCombo = new JComboBox<>();
        var codeComboHolder = new JPanel(new BorderLayout(0, 0));
        codeComboHolder.add(preferredCodeModelCombo, BorderLayout.CENTER);
        var codeHelpButton = new MaterialButton();
        codeHelpButton.setIcon(Icons.HELP);
        codeHelpButton.setToolTipText("This model is used by Lutz mode to implement tasks.");
        codeHelpButton.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        codeHelpButton.setContentAreaFilled(false);
        codeHelpButton.setFocusPainted(false);
        codeHelpButton.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
        codeComboHolder.add(codeHelpButton, BorderLayout.EAST);

        gbcRoles.gridx = 1;
        gbcRoles.weightx = 1.0;
        gbcRoles.fill = GridBagConstraints.HORIZONTAL;
        rolesPanel.add(codeComboHolder, gbcRoles);

        // Row 2: Vendor for Other Models
        gbcRoles.gridx = 0;
        gbcRoles.gridy = 2;
        gbcRoles.weightx = 0.0;
        gbcRoles.fill = GridBagConstraints.NONE;
        otherModelsVendorLabel = new JLabel("Vendor for Other Models:");
        rolesPanel.add(otherModelsVendorLabel, gbcRoles);

        otherModelsVendorCombo = new JComboBox<>(new String[] {"Anthropic", "Default", "OpenAI"});
        otherModelsVendorCombo.setToolTipText(
                "Selects the default models for Quick, Quick Edit, Quickest, and Scan operations.");
        otherModelsVendorHolder = new JPanel(new BorderLayout(0, 0));
        otherModelsVendorHolder.add(otherModelsVendorCombo, BorderLayout.CENTER);

        String defaultQuick = MainProject.getDefaultQuickModelConfig().name();
        String defaultQuickEdit = MainProject.getDefaultQuickEditModelConfig().name();
        String defaultQuickest = MainProject.getDefaultQuickestModelConfig().name();
        String defaultScan = MainProject.getDefaultScanModelConfig().name();

        String vendorTooltip = "<html><div style='width: 340px;'>"
                + "Selecting a vendor sets Quick, Quick Edit, Quickest, and Scan to vendor defaults.<br/><br/>"
                + "<b>OpenAI:</b> Quick=gpt-5-nano; Quick Edit=gpt-5-nano; Quickest=gpt-5-nano; Scan=gpt-5-mini<br/>"
                + "<b>Anthropic:</b> Quick=claude-haiku-4-5; Quick Edit=claude-haiku-4-5; Quickest=claude-haiku-4-5; Scan=claude-haiku-4-5<br/>"
                + "<b>Default:</b> Quick=" + defaultQuick
                + "; Quick Edit=" + defaultQuickEdit
                + "; Quickest=" + defaultQuickest
                + "; Scan=" + defaultScan
                + "</div></html>";

        var vendorHelpButton = new MaterialButton();
        vendorHelpButton.setIcon(Icons.HELP);
        vendorHelpButton.setToolTipText(vendorTooltip);
        vendorHelpButton.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        vendorHelpButton.setContentAreaFilled(false);
        vendorHelpButton.setFocusPainted(false);
        vendorHelpButton.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
        otherModelsVendorHolder.add(vendorHelpButton, BorderLayout.EAST);

        gbcRoles.gridx = 1;
        gbcRoles.weightx = 1.0;
        gbcRoles.fill = GridBagConstraints.HORIZONTAL;
        rolesPanel.add(otherModelsVendorHolder, gbcRoles);

        // Row 3: Spacer
        gbcRoles.gridx = 0;
        gbcRoles.gridy = 3;
        gbcRoles.weighty = 1.0;
        gbcRoles.fill = GridBagConstraints.BOTH;
        gbcRoles.gridwidth = 2;
        rolesPanel.add(Box.createVerticalGlue(), gbcRoles);
        gbcRoles.gridwidth = 1;

        // Add Defaults button for Model Roles
        var rolesButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        var defaultsRolesButton = new MaterialButton("Defaults");
        defaultsRolesButton.setToolTipText("Restore default model role selections.");
        rolesButtonsPanel.add(defaultsRolesButton);

        gbcRoles.gridx = 0;
        gbcRoles.gridy = 4;
        gbcRoles.weighty = 0.0;
        gbcRoles.fill = GridBagConstraints.HORIZONTAL;
        gbcRoles.gridwidth = 2;
        gbcRoles.insets = new Insets(10, 5, 5, 5);
        rolesPanel.add(rolesButtonsPanel, gbcRoles);

        // Wire up the Defaults button action
        defaultsRolesButton.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(
                    SettingsGlobalPanel.this,
                    "This will restore default model role selections.\nYou will lose your old settings. Continue?",
                    "Restore Default Model Roles",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }

            // Set vendor to Default
            otherModelsVendorCombo.setSelectedItem("Default");

            // Set Primary to GPT_5 by matching config name, fallback to index 0
            boolean foundPrimary = false;
            for (int i = 0; i < primaryModelCombo.getItemCount(); i++) {
                Service.FavoriteModel fm = primaryModelCombo.getItemAt(i);
                if (fm != null && Service.GPT_5.equals(fm.config().name())) {
                    primaryModelCombo.setSelectedIndex(i);
                    foundPrimary = true;
                    break;
                }
            }
            if (!foundPrimary && primaryModelCombo.getItemCount() > 0) {
                primaryModelCombo.setSelectedIndex(0);
            }

            // Set Lutz Code to HAIKU_4_5 by matching config name, fallback to index 0
            boolean foundCode = false;
            for (int i = 0; i < preferredCodeModelCombo.getItemCount(); i++) {
                Service.FavoriteModel fm = preferredCodeModelCombo.getItemAt(i);
                if (fm != null && Service.HAIKU_4_5.equals(fm.config().name())) {
                    preferredCodeModelCombo.setSelectedIndex(i);
                    foundCode = true;
                    break;
                }
            }
            if (!foundCode && preferredCodeModelCombo.getItemCount() > 0) {
                preferredCodeModelCombo.setSelectedIndex(0);
            }

            JOptionPane.showMessageDialog(
                    SettingsGlobalPanel.this,
                    "Model role selections restored to defaults. Click Apply to save.",
                    "Defaults Restored",
                    JOptionPane.INFORMATION_MESSAGE);
        });

        // Create Favorites panel (table + buttons)
        var favoritesPanel = new JPanel(new BorderLayout(5, 5));
        favoritesPanel.add(new JScrollPane(quickModelsTable), BorderLayout.CENTER);
        favoritesPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Create nested tabbed pane for Favorites and Model Roles
        var modelsTabbed = new JTabbedPane(JTabbedPane.TOP);
        modelsTabbed.addTab("Favorites", null, favoritesPanel, "Manage favorite model aliases");
        modelsTabbed.addTab(
                "Model Roles",
                null,
                new JPanel(new BorderLayout(5, 5)) {
                    {
                        add(rolesPanel, BorderLayout.NORTH);
                        add(Box.createVerticalGlue(), BorderLayout.CENTER);
                        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
                    }
                },
                "Assign models for specific roles");

        // Create container panel to return
        var container = new JPanel(new BorderLayout(5, 5));
        container.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        container.add(modelsTabbed, BorderLayout.CENTER);

        return container;
    }

    private void refreshFavoriteModelCombosPreservingSelection() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshFavoriteModelCombosPreservingSelection);
            return;
        }
        try {
            Service.ModelConfig prevCodeCfg = null;
            Service.ModelConfig prevPrimaryCfg = null;
            Object selCode = preferredCodeModelCombo.getSelectedItem();
            if (selCode instanceof Service.FavoriteModel fmCode) {
                prevCodeCfg = fmCode.config();
            }
            Object selPrimary = primaryModelCombo.getSelectedItem();
            if (selPrimary instanceof Service.FavoriteModel fmPrimary) {
                prevPrimaryCfg = fmPrimary.config();
            }

            var favorites = quickModelsTableModel.getFavorites();

            preferredCodeModelCombo.removeAllItems();
            for (Service.FavoriteModel fav : favorites) {
                preferredCodeModelCombo.addItem(fav);
            }
            boolean codeSelected = false;
            if (prevCodeCfg != null) {
                for (int i = 0; i < preferredCodeModelCombo.getItemCount(); i++) {
                    Service.FavoriteModel fav = preferredCodeModelCombo.getItemAt(i);
                    if (fav != null && prevCodeCfg.equals(fav.config())) {
                        preferredCodeModelCombo.setSelectedIndex(i);
                        codeSelected = true;
                        break;
                    }
                }
            }
            if (!codeSelected && preferredCodeModelCombo.getItemCount() > 0) {
                preferredCodeModelCombo.setSelectedIndex(0);
            }

            primaryModelCombo.removeAllItems();
            for (Service.FavoriteModel fav : favorites) {
                primaryModelCombo.addItem(fav);
            }
            boolean primarySelected = false;
            if (prevPrimaryCfg != null) {
                for (int i = 0; i < primaryModelCombo.getItemCount(); i++) {
                    Service.FavoriteModel fav = primaryModelCombo.getItemAt(i);
                    if (fav != null && prevPrimaryCfg.equals(fav.config())) {
                        primaryModelCombo.setSelectedIndex(i);
                        primarySelected = true;
                        break;
                    }
                }
            }
            if (!primarySelected && primaryModelCombo.getItemCount() > 0) {
                primaryModelCombo.setSelectedIndex(0);
            }

            preferredCodeModelCombo.revalidate();
            preferredCodeModelCombo.repaint();
            primaryModelCombo.revalidate();
            primaryModelCombo.repaint();
        } catch (Exception e) {
            logger.warn("Failed to refresh favorite model combos", e);
        }
    }

    private void refreshBalanceDisplay() {
        this.balanceField.setText("Loading...");
        var contextManager = chrome.getContextManager();
        var models = contextManager.getService();
        contextManager.submitBackgroundTask("Refreshing user balance", () -> {
            try {
                float balance = models.getUserBalance();
                SwingUtilities.invokeLater(() -> this.balanceField.setText(String.format("$%.2f", balance)));
            } catch (IOException e) {
                logger.debug("Failed to refresh user balance", e);
                SwingUtilities.invokeLater(() -> this.balanceField.setText("Error"));
            }
        });
    }

    private JPanel createNotificationsPanel() {
        var panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        // Section title
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel("Notification preferences:"), gbc);
        gbc.gridwidth = 1;

        // Error
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(showErrorNotificationsCheckbox, gbc);

        // Confirm
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(showConfirmNotificationsCheckbox, gbc);

        // Info
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(showInfoNotificationsCheckbox, gbc);

        // Cost
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(showCostNotificationsCheckbox, gbc);

        // Free internal LLM call cost (applies to gemini-2.0-flash and gemini-2.0-flash-light)
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(showFreeInternalLLMCheckbox, gbc);

        // Filler
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(Box.createVerticalGlue(), gbc);

        return panel;
    }

    public boolean applySettings() {
        // === PHASE 1: Validation ===

        // Validate API key
        String currentBrokkKeyInSettings = MainProject.getBrokkKey();
        String newBrokkKeyFromField = brokkKeyField.getText().trim();
        boolean keyStateChangedInUI = !newBrokkKeyFromField.equals(currentBrokkKeyInSettings);

        if (keyStateChangedInUI && !newBrokkKeyFromField.isEmpty()) {
            try {
                Service.validateKey(newBrokkKeyFromField);
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, "Invalid Brokk Key", "Invalid Key", JOptionPane.ERROR_MESSAGE);
                return false;
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(
                        this,
                        "Network error: " + ex.getMessage() + ". Key saved, but validation failed.",
                        "Network Error",
                        JOptionPane.WARNING_MESSAGE);
            }
        }

        // Validate UI Scale
        String uiScale;
        if (uiScaleAutoRadio != null && uiScaleCustomRadio != null && uiScaleCombo != null) {
            if (uiScaleAutoRadio.isSelected()) {
                uiScale = "auto";
            } else {
                String txt = String.valueOf(uiScaleCombo.getSelectedItem()).trim();
                var allowed = Set.of("1.0", "2.0", "3.0", "4.0", "5.0");
                if (!allowed.contains(txt)) {
                    JOptionPane.showMessageDialog(
                            this,
                            "Select a scale from 1.0, 2.0, 3.0, 4.0, or 5.0.",
                            "Invalid UI Scale",
                            JOptionPane.ERROR_MESSAGE);
                    return false;
                }
                uiScale = txt;
            }
        } else {
            uiScale = MainProject.getUiScalePref();
        }

        // GitHub Tab validation
        if (!gitHubSettingsPanel.applySettings()) {
            return false;
        }

        // === PHASE 2: Gather all values from UI ===

        // Service settings - preserve current if radio buttons are null (STAGING mode)
        MainProject.LlmProxySetting proxySetting;
        if (brokkProxyRadio == null) {
            proxySetting = MainProject.getProxySetting(); // Keep current (e.g., STAGING)
        } else {
            proxySetting = brokkProxyRadio.isSelected()
                    ? MainProject.LlmProxySetting.BROKK
                    : MainProject.LlmProxySetting.LOCALHOST;
        }
        var forceToolEmulation = (forceToolEmulationCheckbox != null) && forceToolEmulationCheckbox.isSelected();

        // Appearance settings
        String newTheme = GuiTheme.THEME_LIGHT;
        if (lightThemeRadio.isSelected()) {
            newTheme = (String) lightThemeRadio.getClientProperty("theme");
        } else if (lightPlusThemeRadio.isSelected()) {
            newTheme = (String) lightPlusThemeRadio.getClientProperty("theme");
        } else if (darkThemeRadio.isSelected()) {
            newTheme = (String) darkThemeRadio.getClientProperty("theme");
        } else if (darkPlusThemeRadio.isSelected()) {
            newTheme = (String) darkPlusThemeRadio.getClientProperty("theme");
        } else if (highContrastThemeRadio.isSelected()) {
            newTheme = (String) highContrastThemeRadio.getClientProperty("theme");
        }
        boolean newWrapMode = wordWrapCheckbox.isSelected();
        float terminalFontSize = ((Double) terminalFontSizeSpinner.getValue()).floatValue();

        // Startup settings
        var startupMode =
                startupOpenAllRadio.isSelected() ? MainProject.StartupOpenMode.ALL : MainProject.StartupOpenMode.LAST;

        // General settings
        boolean jvmAutomatic = memoryAutoRadio.isSelected();
        int jvmMb = ((Number) memorySpinner.getValue()).intValue();

        // File watcher preference from UI
        String watchPrefSelected = "default";
        try {
            String raw = String.valueOf(watchServiceImplCombo.getSelectedItem());
            if ("Legacy".equalsIgnoreCase(raw)) {
                watchPrefSelected = "legacy";
            } else if ("Native".equalsIgnoreCase(raw)) {
                watchPrefSelected = "native";
            } else {
                watchPrefSelected = "default";
            }
        } catch (Exception ex) {
            watchPrefSelected = "default";
        }

        // Model settings
        if (quickModelsTable.isEditing()) {
            quickModelsTable.getCellEditor().stopCellEditing();
        }
        var favoriteModels = quickModelsTableModel.getFavorites();
        if (favoriteModels.isEmpty()) {
            var currentCodeConfig = chrome.getProject().getMainProject().getCodeModelConfig();
            favoriteModels = List.of(new Service.FavoriteModel("default", currentCodeConfig));
            quickModelsTableModel.setFavorites(favoriteModels);
        }

        var mcpServers = new ArrayList<McpServer>();
        for (int i = 0; i < mcpServersListModel.getSize(); i++) {
            mcpServers.add(mcpServersListModel.getElementAt(i));
        }
        var mcpConfig = new McpConfig(mcpServers);

        // Primary & Lutz Code Model saves from favorite model combos
        Service.FavoriteModel selectedCodeFavorite = (Service.FavoriteModel) preferredCodeModelCombo.getSelectedItem();
        Service.FavoriteModel selectedPrimaryFavorite = (Service.FavoriteModel) primaryModelCombo.getSelectedItem();

        // Vendor mapping for Other Models (Quick, Quick Edit, Quickest, Scan)
        var selectedVendor = (String) otherModelsVendorCombo.getSelectedItem();

        // UI preferences
        boolean advancedMode = advancedModeCheckbox.isSelected();
        boolean verticalLayout = !classicBrokkViewCheckbox.isSelected();
        boolean persistPerProject = persistPerProjectWindowCheckbox.isSelected();
        boolean instructionsIndent = instructionsTabInsertIndentationCheckbox.isSelected();
        boolean diffUnified = diffUnifiedRadio.isSelected();

        // Capture "before" values for change detection (must be done before saving)
        String previousTheme = MainProject.getTheme();
        boolean previousWrapMode = MainProject.getCodeBlockWrapMode();
        boolean previousAdvancedMode = GlobalUiSettings.isAdvancedMode();
        boolean previousVerticalLayout = GlobalUiSettings.isVerticalActivityLayout();
        String previousUiScale = MainProject.getUiScalePref();

        // === PHASE 3: Create grouped settings records ===

        var serviceSettings = new MainProject.ServiceSettings(newBrokkKeyFromField, proxySetting, forceToolEmulation);
        var appearanceSettings = new MainProject.AppearanceSettings(newTheme, newWrapMode, uiScale, terminalFontSize);
        var startupSettings = new MainProject.StartupSettings(startupMode);
        var generalSettings = new MainProject.GeneralSettings(new MainProject.JvmMemorySettings(jvmAutomatic, jvmMb));
        var modelSettings = new MainProject.ModelSettings(favoriteModels, mcpConfig);

        var notificationSettings = new GlobalUiSettings.NotificationSettings(
                showCostNotificationsCheckbox.isSelected(),
                showFreeInternalLLMCheckbox.isSelected(),
                showErrorNotificationsCheckbox.isSelected(),
                showConfirmNotificationsCheckbox.isSelected(),
                showInfoNotificationsCheckbox.isSelected());
        var uiPreferences = new GlobalUiSettings.UiPreferences(
                advancedMode, verticalLayout, persistPerProject, instructionsIndent, diffUnified);

        // === PHASE 4: Atomic save (2 writes total) ===

        MainProject.saveAllGlobalSettings(
                serviceSettings, appearanceSettings, startupSettings, generalSettings, modelSettings);

        // Persist watch service implementation preference (separate global property)
        try {
            MainProject.setWatchServiceImplPreference(watchPrefSelected);
        } catch (Exception ex) {
            logger.debug("Failed to persist watch service implementation preference (non-fatal)", ex);
        }
        GlobalUiSettings.saveAllUiSettings(notificationSettings, uiPreferences);
        GlobalUiSettings.saveSkipCommitGateInEzMode(skipCommitGateEzCheckbox.isSelected());

        // Persist force tool emulation immediately so runtime behavior reflects the user's choice.
        MainProject.setForceToolEmulation(forceToolEmulation);

        // === PHASE 5: Side effects after successful save ===

        // Determine what changed for side effects (using previously captured values)
        boolean themeChanged = !newTheme.equals(previousTheme);
        boolean wrapChanged = newWrapMode != previousWrapMode;
        boolean advancedModeChanged = advancedMode != previousAdvancedMode;
        boolean verticalLayoutChanged = verticalLayout != previousVerticalLayout;
        boolean uiScaleChanged = !uiScale.equals(previousUiScale);

        // API key side effects
        if (keyStateChangedInUI) {
            refreshBalanceDisplay();
            updateSignupLabelVisibility();
            parentDialog.triggerDataRetentionPolicyRefresh();
            chrome.getContextManager().reloadService();
        }

        // Theme and wrap mode side effects
        if (themeChanged || wrapChanged) {
            chrome.switchThemeAndWrapMode(newTheme, newWrapMode);
        }

        // Advanced mode side effects
        if (advancedModeChanged) {
            try {
                chrome.applyAdvancedModeVisibility();
            } catch (Exception ex) {
                logger.debug("Failed to apply advanced mode visibility (non-fatal)", ex);
            }
        }

        // Vertical layout side effects
        if (verticalLayoutChanged) {
            JOptionPane.showMessageDialog(
                    this,
                    "Restart required: Changing Activity Layout will take effect after restarting Brokk.",
                    "Restart Required",
                    JOptionPane.INFORMATION_MESSAGE);
        }

        // UI scale side effects
        if (uiScaleChanged) {
            parentDialog.markRestartNeededForUiScale();
        }

        // JVM memory side effects
        JDeploySettingsUtil.updateJvmMemorySettings(generalSettings.jvmMemory());

        // Terminal font size side effects
        chrome.updateTerminalFontSize();

        // Save Lutz Code Model (from favorite config)
        if (selectedCodeFavorite != null) {
            chrome.getProject().getMainProject().setCodeModelConfig(selectedCodeFavorite.config());
        }

        // Save Primary Model (from favorite config)
        if (selectedPrimaryFavorite != null) {
            chrome.getProject().getMainProject().setPrimaryModelConfig(selectedPrimaryFavorite.config());
            chrome.getInstructionsPanel().selectPlannerModelConfig(selectedPrimaryFavorite.config());
        }

        String previousVendorPref = MainProject.getOtherModelsVendorPreference();

        // Persist selected vendor preference for "other models" regardless of choice
        MainProject.setOtherModelsVendorPreference(selectedVendor != null ? selectedVendor : "");

        // Apply vendor mappings for Quick, Quick Edit, Quickest, Scan
        if ("OpenAI".equals(selectedVendor)) {
            chrome.getProject().getMainProject().setQuickModelConfig(new Service.ModelConfig(Service.GPT_5_NANO));
            chrome.getProject().getMainProject().setQuickEditModelConfig(new Service.ModelConfig(Service.GPT_5_NANO));
            chrome.getProject().getMainProject().setQuickestModelConfig(new Service.ModelConfig(Service.GPT_5_NANO));
            chrome.getProject().getMainProject().setScanModelConfig(new Service.ModelConfig(Service.GPT_5_MINI));
        } else if ("Anthropic".equals(selectedVendor)) {
            chrome.getProject().getMainProject().setQuickModelConfig(new Service.ModelConfig(Service.HAIKU_4_5));
            chrome.getProject().getMainProject().setQuickEditModelConfig(new Service.ModelConfig(Service.HAIKU_4_5));
            chrome.getProject().getMainProject().setQuickestModelConfig(new Service.ModelConfig(Service.HAIKU_4_5));
            chrome.getProject().getMainProject().setScanModelConfig(new Service.ModelConfig(Service.HAIKU_4_5));
        } else if ("Default".equals(selectedVendor)) {
            var mp = chrome.getProject().getMainProject();
            mp.setQuickModelConfig(MainProject.getDefaultQuickModelConfig());
            mp.setQuickEditModelConfig(MainProject.getDefaultQuickEditModelConfig());
            mp.setQuickestModelConfig(MainProject.getDefaultQuickestModelConfig());
            mp.setScanModelConfig(MainProject.getDefaultScanModelConfig());
        }

        // Reload service if vendor changed so new Quick/Scan models take effect immediately
        String prev = previousVendorPref;
        String now = selectedVendor;
        if (!prev.equals(now)) {
            try {
                chrome.getContextManager().reloadService();
            } catch (Exception ex) {
                logger.debug("Service reload after vendor change failed (non-fatal)", ex);
            }
        }

        logger.debug("Applied all settings successfully (2 atomic writes)");
        return true;
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        SwingUtilities.updateComponentTreeUI(this);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
        // Word wrap not applicable to settings global panel
        SwingUtilities.updateComponentTreeUI(this);
    }

    private JLabel createMcpServerUrlErrorLabel() {
        return createErrorLabel("Invalid URL");
    }

    private JLabel createErrorLabel(String text) {
        var label = new JLabel(text);
        var errorColor = UIManager.getColor("Label.errorForeground");
        if (errorColor == null) {
            errorColor = new Color(219, 49, 49);
        }
        label.setForeground(errorColor);
        label.setVisible(false);
        return label;
    }

    private boolean isUrlValid(String text) {
        text = text.trim();
        if (text.isEmpty()) {
            return false;
        }
        try {
            URL u = new URI(text).toURL();
            String host = u.getHost();
            return host != null && !host.isEmpty();
        } catch (URISyntaxException | MalformedURLException ex) {
            return false;
        }
    }

    private JPanel createMcpPanel() {
        var mcpPanel = new JPanel(new BorderLayout(5, 5));
        mcpPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Server list
        mcpServersList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mcpServersList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof HttpMcpServer server) {
                    setText(server.name() + " (" + server.url() + ")");
                } else if (value instanceof StdioMcpServer server) {
                    setText(server.name() + " (" + server.command() + ")");
                }
                return this;
            }
        });
        var scrollPane = new JScrollPane(mcpServersList);
        mcpPanel.add(scrollPane, BorderLayout.CENTER);

        // Buttons
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var addHttpButton = new MaterialButton("Add HTTP...");
        var addStdioButton = new MaterialButton("Add Stdio...");
        var editButton = new MaterialButton("Edit...");
        var removeButton = new MaterialButton("Remove");

        // Enable and wire up action listeners for MCP server management.
        addHttpButton.setEnabled(true);
        addStdioButton.setEnabled(true);
        editButton.setEnabled(false);
        removeButton.setEnabled(false);

        // Enable Edit/Remove when a server is selected
        mcpServersList.addListSelectionListener(e -> {
            boolean hasSelection = !mcpServersList.isSelectionEmpty();
            editButton.setEnabled(hasSelection);
            removeButton.setEnabled(hasSelection);
        });

        // Add new HTTP MCP server (name + url). Tools can be fetched later.
        addHttpButton.addActionListener(e -> showMcpServerDialog("Add HTTP MCP Server", null, server -> {
            mcpServersListModel.addElement(server);
            mcpServersList.setSelectedValue(server, true);
        }));

        // Add new Stdio MCP server via JSON configuration
        addStdioButton.addActionListener(e -> showStdioMcpServerDialog("Add Stdio MCP Server", null, server -> {
            mcpServersListModel.addElement(server);
            mcpServersList.setSelectedValue(server, true);
        }));

        // Edit selected MCP server
        editButton.addActionListener(e -> {
            int idx = mcpServersList.getSelectedIndex();
            if (idx < 0) return;
            McpServer existing = mcpServersListModel.getElementAt(idx);
            if (existing instanceof HttpMcpServer http) {
                showMcpServerDialog("Edit MCP Server", http, updated -> {
                    mcpServersListModel.setElementAt(updated, idx);
                    mcpServersList.setSelectedIndex(idx);
                });
            } else if (existing instanceof StdioMcpServer stdio) {
                showStdioMcpServerDialog("Edit MCP Server", stdio, updated -> {
                    mcpServersListModel.setElementAt(updated, idx);
                    mcpServersList.setSelectedIndex(idx);
                });
            }
        });

        // Remove selected MCP server with confirmation
        removeButton.addActionListener(e -> {
            int idx = mcpServersList.getSelectedIndex();
            if (idx >= 0) {
                int confirm = JOptionPane.showConfirmDialog(
                        SettingsGlobalPanel.this,
                        "Remove selected MCP server?",
                        "Confirm Remove",
                        JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    mcpServersListModel.removeElementAt(idx);
                }
            }
        });

        buttonPanel.add(addHttpButton);
        buttonPanel.add(addStdioButton);
        buttonPanel.add(editButton);
        buttonPanel.add(removeButton);
        mcpPanel.add(buttonPanel, BorderLayout.SOUTH);

        return mcpPanel;
    }

    private void showMcpServerDialog(String title, @Nullable HttpMcpServer existing, Consumer<McpServer> onSave) {
        final var fetchedTools =
                new AtomicReference<@Nullable List<String>>(existing != null ? existing.tools() : null);
        final var fetchedToolDetails = new AtomicReference<@Nullable List<McpSchema.Tool>>(null);
        JTextField nameField = new JTextField(existing != null ? existing.name() : "");
        JTextField urlField = new JTextField(existing != null ? existing.url().toString() : "");
        JCheckBox useTokenCheckbox = new JCheckBox("Use Bearer Token");
        JPasswordField tokenField = new JPasswordField();
        JLabel tokenLabel = new JLabel("Bearer Token:");
        var showTokenButton = new JToggleButton(Icons.VISIBILITY_OFF);
        showTokenButton.setToolTipText("Show/Hide token");
        showTokenButton.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        showTokenButton.setContentAreaFilled(false);
        showTokenButton.setCursor(new Cursor(Cursor.HAND_CURSOR));

        char defaultEchoChar = tokenField.getEchoChar();
        showTokenButton.addActionListener(ae -> {
            if (showTokenButton.isSelected()) {
                tokenField.setEchoChar((char) 0);
                showTokenButton.setIcon(Icons.VISIBILITY);
            } else {
                tokenField.setEchoChar(defaultEchoChar);
                showTokenButton.setIcon(Icons.VISIBILITY_OFF);
            }
        });

        var tokenPanel = new JPanel(new BorderLayout());
        tokenPanel.add(tokenField, BorderLayout.CENTER);
        tokenPanel.add(showTokenButton, BorderLayout.EAST);

        String existingToken = existing != null ? existing.bearerToken() : null;
        if (existingToken != null && !existingToken.isEmpty()) {
            useTokenCheckbox.setSelected(true);
            String displayToken = existingToken;
            if (displayToken.regionMatches(false, 0, "Bearer ", 0, 7)) {
                displayToken = displayToken.substring(7);
            }
            tokenField.setText(displayToken);
            tokenLabel.setVisible(true);
            tokenPanel.setVisible(true);
        } else {
            tokenLabel.setVisible(false);
            tokenPanel.setVisible(false);
        }

        useTokenCheckbox.addActionListener(ae -> {
            boolean sel = useTokenCheckbox.isSelected();
            tokenLabel.setVisible(sel);
            tokenPanel.setVisible(sel);
            SwingUtilities.invokeLater(() -> {
                Window w = SwingUtilities.getWindowAncestor(tokenPanel);
                if (w != null) w.pack();
                tokenPanel.revalidate();
                tokenPanel.repaint();
            });
        });

        JLabel fetchStatusLabel = new JLabel(" ");

        var toolsTable = new McpToolTable();
        var toolsScrollPane = new JScrollPane(toolsTable);
        toolsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        toolsScrollPane.setPreferredSize(new Dimension(650, 240));
        toolsScrollPane.setVisible(true);

        var errorTextArea = new JTextArea(5, 20);
        errorTextArea.setEditable(false);
        errorTextArea.setLineWrap(true);
        errorTextArea.setWrapStyleWord(true);
        var errorScrollPane = new JScrollPane(errorTextArea);
        errorScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        errorScrollPane.setPreferredSize(new Dimension(650, 240));
        errorScrollPane.setVisible(false);

        if (fetchedTools.get() != null && !fetchedTools.get().isEmpty()) {
            toolsTable.setToolsFromNames(fetchedTools.get());
            toolsScrollPane.setVisible(true);
            errorScrollPane.setVisible(false);
        }

        AtomicReference<String> lastFetchedUrl =
                new AtomicReference<>(existing != null ? existing.url().toString() : null);
        final AtomicLong lastFetchStartedAt = new AtomicLong(0L);

        Runnable fetcher = () -> {
            String rawUrl = urlField.getText().trim();
            if (!isUrlValid(rawUrl)) {
                return;
            }

            URL urlObj;
            try {
                urlObj = new URI(rawUrl).toURL();
            } catch (MalformedURLException | URISyntaxException ex) {
                return;
            }

            String bearerToken = null;
            if (useTokenCheckbox.isSelected()) {
                String rawToken = new String(tokenField.getPassword()).trim();
                if (!rawToken.isEmpty()) {
                    if (rawToken.regionMatches(true, 0, "Bearer ", 0, 7)) {
                        bearerToken = rawToken;
                    } else {
                        bearerToken = "Bearer " + rawToken;
                    }
                }
            }
            final String finalBearerToken = bearerToken;

            // Record the URL and timestamp we are about to fetch to enforce a minimum interval between fetches
            lastFetchedUrl.set(rawUrl);
            lastFetchStartedAt.set(System.currentTimeMillis());

            Callable<List<McpSchema.Tool>> callable = () -> McpUtils.fetchTools(urlObj, finalBearerToken);
            initiateMcpToolsFetch(
                    fetchStatusLabel,
                    callable,
                    toolsTable,
                    toolsScrollPane,
                    errorTextArea,
                    errorScrollPane,
                    fetchedToolDetails,
                    fetchedTools);
        };

        final Timer[] throttleTimer = new Timer[1];
        Runnable validationAction = () -> {
            String current = urlField.getText().trim();
            if (!isUrlValid(current)) {
                return;
            }
            String previous = lastFetchedUrl.get();
            if (previous != null && previous.equals(current)) {
                // Already fetched this URL
                return;
            }
            // Enforce a minimum of 2 seconds between fetches; fetch immediately if enough time has passed
            if (throttleTimer[0] != null && throttleTimer[0].isRunning()) {
                throttleTimer[0].stop();
            }
            long now = System.currentTimeMillis();
            long startedAt = lastFetchStartedAt.get();
            long elapsed = now - startedAt;
            if (startedAt == 0L || elapsed >= 2000L) {
                // First fetch or enough time elapsed; fetch immediately (post-debounce)
                fetcher.run();
            } else {
                int delay = (int) (2000L - elapsed);
                throttleTimer[0] = new Timer(delay, ev -> {
                    String latest = urlField.getText().trim();
                    if (!isUrlValid(latest)) {
                        return;
                    }
                    // Avoid duplicate fetch for the same URL
                    String last = lastFetchedUrl.get();
                    if (last != null && last.equals(latest)) {
                        return;
                    }
                    fetcher.run();
                });
                throttleTimer[0].setRepeats(false);
                throttleTimer[0].start();
            }
        };

        JLabel urlErrorLabel = createMcpServerUrlErrorLabel();
        JLabel nameErrorLabel = createErrorLabel("Duplicate name");
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            private void validateName() {
                String candidate = nameField.getText().trim();
                if (candidate.isEmpty()) {
                    candidate = urlField.getText().trim();
                }
                boolean duplicate = false;
                for (int i = 0; i < mcpServersListModel.getSize(); i++) {
                    McpServer s = mcpServersListModel.getElementAt(i);
                    if (existing != null && s.name().equalsIgnoreCase(existing.name())) {
                        continue;
                    }
                    if (s.name().equalsIgnoreCase(candidate)) {
                        duplicate = true;
                        break;
                    }
                }
                nameErrorLabel.setVisible(duplicate);
                SwingUtilities.invokeLater(() -> {
                    Window w = SwingUtilities.getWindowAncestor(nameField);
                    if (w != null) w.pack();
                });
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                validateName();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                validateName();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                validateName();
            }
        });
        // Initial validation for name field
        SwingUtilities.invokeLater(() -> {
            Window w = SwingUtilities.getWindowAncestor(nameField);
            nameErrorLabel.setVisible(false);
            if (w != null) w.pack();
        });
        urlField.getDocument()
                .addDocumentListener(createUrlValidationListener(urlField, urlErrorLabel, validationAction));

        var panel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.anchor = GridBagConstraints.WEST;

        // Row 0: Name
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("Name:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        panel.add(nameField, gbc);

        // Row 1: Name Error
        gbc.gridx = 1;
        gbc.gridy = 1;
        panel.add(nameErrorLabel, gbc);

        // Row 2: URL
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("URL:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        panel.add(urlField, gbc);

        // Row 3: URL Error
        gbc.gridx = 1;
        gbc.gridy = 3;
        panel.add(urlErrorLabel, gbc);

        // Row 4: Token checkbox
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        panel.add(useTokenCheckbox, gbc);
        gbc.gridwidth = 1;

        // Row 5: Token
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(tokenLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        panel.add(tokenPanel, gbc);

        // Row 6: Fetch Status
        gbc.gridx = 1;
        gbc.gridy = 6;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.WEST;
        panel.add(fetchStatusLabel, gbc);

        // Row 7: Tools Pane
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.insets = new Insets(8, 0, 0, 0);
        panel.add(toolsScrollPane, gbc);
        panel.add(errorScrollPane, gbc);

        var optionPane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        final var dialog = optionPane.createDialog(SettingsGlobalPanel.this, title);
        // Make the MCP dialog wider and resizable to improve readability
        dialog.setResizable(true);
        var preferred = dialog.getPreferredSize();
        int minWidth = Math.max(800, preferred.width);
        int prefHeight = Math.max(500, preferred.height);
        dialog.setMinimumSize(new Dimension(700, 400));
        dialog.setPreferredSize(new Dimension(minWidth, prefHeight));
        dialog.pack();
        dialog.setLocationRelativeTo(SettingsGlobalPanel.this);

        optionPane.addPropertyChangeListener(pce -> {
            if (pce.getSource() != optionPane || !pce.getPropertyName().equals(JOptionPane.VALUE_PROPERTY)) {
                return;
            }
            var value = optionPane.getValue();
            if (value == JOptionPane.UNINITIALIZED_VALUE) {
                return;
            }

            if (value.equals(JOptionPane.OK_OPTION)) {
                String name = nameField.getText().trim();
                String rawUrl = urlField.getText().trim();
                boolean useToken = useTokenCheckbox.isSelected();

                String effectiveName = name.isEmpty() ? rawUrl : name;
                boolean duplicate = false;
                for (int i = 0; i < mcpServersListModel.getSize(); i++) {
                    McpServer s = mcpServersListModel.getElementAt(i);
                    if (existing != null && s.name().equalsIgnoreCase(existing.name())) {
                        continue;
                    }
                    if (s.name().equalsIgnoreCase(effectiveName)) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    nameErrorLabel.setVisible(true);
                    dialog.pack();
                    dialog.setVisible(true);
                    optionPane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                    return;
                }

                HttpMcpServer newServer =
                        createMcpServerFromInputs(name, rawUrl, useToken, tokenField, fetchedTools.get());

                if (newServer != null) {
                    onSave.accept(newServer);
                    dialog.setVisible(false);
                } else {
                    urlErrorLabel.setVisible(true);
                    dialog.pack();
                    dialog.setVisible(true);
                    optionPane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                }
            } else {
                dialog.setVisible(false);
            }
        });

        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                // Trigger initial fetch for existing servers to populate tool descriptions (tooltips)
                String current = urlField.getText().trim();
                if (existing != null && fetchedToolDetails.get() == null && isUrlValid(current)) {
                    fetcher.run();
                }
            }
        });
        dialog.setVisible(true);
    }

    private void showStdioMcpServerDialog(String title, @Nullable StdioMcpServer existing, Consumer<McpServer> onSave) {

        // Inputs
        JTextField nameField = new JTextField(existing != null ? existing.name() : "");
        JTextField commandField = new JTextField(existing != null ? existing.command() : "");

        List<String> initialArgs = existing != null ? existing.args() : new ArrayList<>();
        Map<String, String> initialEnv = existing != null ? existing.env() : Collections.emptyMap();

        ArgsTableModel argsModel = new ArgsTableModel(initialArgs);
        JTable argsTable = new JTable(argsModel);
        argsTable.setFillsViewportHeight(true);
        argsTable.setRowHeight(argsTable.getRowHeight() + 2);

        EnvTableModel envModel = new EnvTableModel(initialEnv);
        JTable envTable = new JTable(envModel);
        envTable.getColumnModel().getColumn(1).setCellRenderer(new EnvVarCellRenderer());
        envTable.setFillsViewportHeight(true);
        envTable.setRowHeight(envTable.getRowHeight() + 2);

        // Duplicate name error label
        JLabel nameErrorLabel = createErrorLabel("Duplicate name");
        nameErrorLabel.setVisible(false);

        // Tools fetching state
        final var fetchedTools =
                new AtomicReference<@Nullable List<String>>(existing != null ? existing.tools() : null);
        final var fetchedToolDetails = new AtomicReference<@Nullable List<McpSchema.Tool>>(null);

        JLabel fetchStatusLabel = new JLabel(" ");

        var toolsTable = new McpToolTable();
        var toolsScrollPane = new JScrollPane(toolsTable);
        toolsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        toolsScrollPane.setPreferredSize(new Dimension(650, 240));
        toolsScrollPane.setVisible(true);

        var fetchErrorTextArea = new JTextArea(5, 20);
        fetchErrorTextArea.setEditable(false);
        fetchErrorTextArea.setLineWrap(true);
        fetchErrorTextArea.setWrapStyleWord(true);
        var fetchErrorScrollPane = new JScrollPane(fetchErrorTextArea);
        fetchErrorScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        fetchErrorScrollPane.setPreferredSize(new Dimension(650, 240));
        fetchErrorScrollPane.setVisible(false);

        if (fetchedTools.get() != null && !fetchedTools.get().isEmpty()) {
            toolsTable.setToolsFromNames(fetchedTools.get());
            toolsScrollPane.setVisible(true);
            fetchErrorScrollPane.setVisible(false);
        }

        // Name validation against duplicates (similar to HTTP dialog)
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            private void validateName() {
                String candidate = nameField.getText().trim();
                if (candidate.isEmpty()) {
                    // fall back to command for duplicate check when empty
                    candidate = commandField.getText().trim();
                }
                boolean duplicate = false;
                for (int i = 0; i < mcpServersListModel.getSize(); i++) {
                    McpServer s = mcpServersListModel.getElementAt(i);
                    if (existing != null && s.name().equalsIgnoreCase(existing.name())) {
                        continue;
                    }
                    if (s.name().equalsIgnoreCase(candidate)) {
                        duplicate = true;
                        break;
                    }
                }
                nameErrorLabel.setVisible(duplicate);
                SwingUtilities.invokeLater(() -> {
                    Window w = SwingUtilities.getWindowAncestor(nameField);
                    if (w != null) w.pack();
                });
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                validateName();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                validateName();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                validateName();
            }
        });

        // Args panel with Add/Remove
        var argsScroll = new JScrollPane(argsTable);
        argsScroll.setPreferredSize(new Dimension(400, 120));
        var argsButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        var addArgButton = new MaterialButton("Add");
        var removeArgButton = new MaterialButton("Remove");
        argsButtons.add(addArgButton);
        argsButtons.add(removeArgButton);
        addArgButton.addActionListener(e -> {
            if (argsTable.isEditing()) argsTable.getCellEditor().stopCellEditing();
            argsModel.addRow();
            int last = argsModel.getRowCount() - 1;
            if (last >= 0) {
                argsTable.setRowSelectionInterval(last, last);
                argsTable.editCellAt(last, 0);
                Component editor = argsTable.getEditorComponent();
                if (editor != null) editor.requestFocusInWindow();
            }
        });
        removeArgButton.addActionListener(e -> {
            int viewRow = argsTable.getSelectedRow();
            if (viewRow != -1) {
                if (argsTable.isEditing()) argsTable.getCellEditor().stopCellEditing();
                int modelRow = argsTable.convertRowIndexToModel(viewRow);
                argsModel.removeRow(modelRow);
            }
        });

        // Env panel with Add/Remove
        var envScroll = new JScrollPane(envTable);
        envScroll.setPreferredSize(new Dimension(400, 150));
        var envButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        var addEnvButton = new MaterialButton("Add");
        var removeEnvButton = new MaterialButton("Remove");
        envButtons.add(addEnvButton);
        envButtons.add(removeEnvButton);
        addEnvButton.addActionListener(e -> {
            if (envTable.isEditing()) envTable.getCellEditor().stopCellEditing();
            envModel.addRow();
            int last = envModel.getRowCount() - 1;
            if (last >= 0) {
                envTable.setRowSelectionInterval(last, last);
                envTable.editCellAt(last, 0);
                Component editor = envTable.getEditorComponent();
                if (editor != null) editor.requestFocusInWindow();
            }
        });
        removeEnvButton.addActionListener(e -> {
            int viewRow = envTable.getSelectedRow();
            if (viewRow != -1) {
                if (envTable.isEditing()) envTable.getCellEditor().stopCellEditing();
                int modelRow = envTable.convertRowIndexToModel(viewRow);
                envModel.removeRow(modelRow);
            }
        });

        // Fetch Tools button
        var fetchButton = new MaterialButton("Fetch Tools");
        fetchButton.addActionListener(e -> {
            String cmd = commandField.getText().trim();
            if (cmd.isEmpty()) {
                fetchErrorTextArea.setText("Command cannot be empty.");
                toolsScrollPane.setVisible(false);
                fetchErrorScrollPane.setVisible(true);
                Window w = SwingUtilities.getWindowAncestor(fetchErrorScrollPane);
                if (w != null) w.pack();
                return;
            }
            List<String> args = new ArrayList<>(argsModel.getArgs());
            Map<String, String> env = envModel.getEnvMap();

            Callable<List<McpSchema.Tool>> callable = () -> McpUtils.fetchTools(cmd, args, env, null);
            initiateMcpToolsFetch(
                    fetchStatusLabel,
                    callable,
                    toolsTable,
                    toolsScrollPane,
                    fetchErrorTextArea,
                    fetchErrorScrollPane,
                    fetchedToolDetails,
                    fetchedTools);
        });

        // Layout
        var panel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;

        // Row 0: Name
        panel.add(new JLabel("Name:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(nameField, gbc);

        // Row 1: Name error
        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(nameErrorLabel, gbc);

        // Row 2: Command
        gbc.insets = new Insets(8, 5, 2, 5);
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Command:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(commandField, gbc);

        // Row 3: Args label
        gbc.insets = new Insets(8, 5, 2, 5);
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        panel.add(new JLabel("Arguments:"), gbc);
        // Row 3: Args table + buttons
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.BOTH;
        var argsContainer = new JPanel(new BorderLayout(5, 5));
        argsContainer.add(argsScroll, BorderLayout.CENTER);
        argsContainer.add(argsButtons, BorderLayout.SOUTH);
        panel.add(argsContainer, gbc);

        // Row 4: Env label
        gbc.insets = new Insets(8, 5, 2, 5);
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        panel.add(new JLabel("Environment:"), gbc);
        // Row 4: Env table + buttons
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.BOTH;
        var envContainer = new JPanel(new BorderLayout(5, 5));
        envContainer.add(envScroll, BorderLayout.CENTER);

        // Buttons row with right-aligned help icon
        var envButtonsRow = new JPanel(new BorderLayout(5, 0));
        envButtonsRow.add(envButtons, BorderLayout.WEST);

        Icon helpIcon = Icons.HELP;
        if (helpIcon instanceof ThemedIcon themedHelpIcon) {
            helpIcon = themedHelpIcon.withSize(14);
        }
        var envHelpButton = new MaterialButton();
        envHelpButton.setIcon(helpIcon);
        envHelpButton.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        envHelpButton.setContentAreaFilled(false);
        envHelpButton.setFocusPainted(false);
        envHelpButton.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
        envHelpButton.setToolTipText(
                "You can use environment variables as values, e.g., $HOME or ${HOME}. If a variable is not set, the literal text is used.");
        envButtonsRow.add(envHelpButton, BorderLayout.EAST);

        envContainer.add(envButtonsRow, BorderLayout.SOUTH);
        panel.add(envContainer, gbc);

        // Row 7: Fetch controls (moved below tools)
        var fetchControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        fetchControls.add(fetchButton);
        fetchControls.add(fetchStatusLabel);
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.gridwidth = 2;
        gbc.weightx = 0.0;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        panel.add(fetchControls, gbc);

        // Row 6: Tools/Error area
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(8, 5, 5, 5);
        panel.add(toolsScrollPane, gbc);
        panel.add(fetchErrorScrollPane, gbc);

        var optionPane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        final var dialog = optionPane.createDialog(SettingsGlobalPanel.this, title);
        dialog.setResizable(true);
        var preferred = dialog.getPreferredSize();
        int minWidth = Math.max(800, preferred.width);
        int prefHeight = Math.max(500, preferred.height);
        dialog.setMinimumSize(new Dimension(700, 400));
        dialog.setPreferredSize(new Dimension(minWidth, prefHeight));
        dialog.pack();
        dialog.setLocationRelativeTo(SettingsGlobalPanel.this);

        optionPane.addPropertyChangeListener(pce -> {
            if (pce.getSource() != optionPane || !pce.getPropertyName().equals(JOptionPane.VALUE_PROPERTY)) {
                return;
            }
            var value = optionPane.getValue();
            if (value == JOptionPane.UNINITIALIZED_VALUE) {
                return;
            }

            if (value.equals(JOptionPane.OK_OPTION)) {
                // Validate and save
                String name = nameField.getText().trim();
                String command = commandField.getText().trim();
                if (name.isEmpty()) {
                    name = command;
                }

                // Duplicate name check
                boolean duplicate = false;
                for (int i = 0; i < mcpServersListModel.getSize(); i++) {
                    McpServer s = mcpServersListModel.getElementAt(i);
                    if (existing != null && s.name().equalsIgnoreCase(existing.name())) {
                        continue;
                    }
                    if (s.name().equalsIgnoreCase(name)) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    nameErrorLabel.setVisible(true);
                    dialog.pack();
                    dialog.setVisible(true);
                    optionPane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                    return;
                }

                if (command.isEmpty()) {
                    fetchErrorTextArea.setText("Command cannot be empty.");
                    toolsScrollPane.setVisible(false);
                    fetchErrorScrollPane.setVisible(true);
                    dialog.pack();
                    dialog.setVisible(true);
                    optionPane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                    return;
                }

                List<String> args = new ArrayList<>(argsModel.getArgs());
                Map<String, String> env = envModel.getEnvMap();
                StdioMcpServer toSave = new StdioMcpServer(name, command, args, env, fetchedTools.get());
                onSave.accept(toSave);
                dialog.setVisible(false);
            } else {
                dialog.setVisible(false);
            }
        });

        dialog.setVisible(true);
    }

    /**
     * Shared helper: create a debounced URL DocumentListener that validates the URL and toggles the provided error
     * label. Debounce interval is 500ms.
     */
    private DocumentListener createUrlValidationListener(
            JTextField urlField, JLabel urlErrorLabel, Runnable onValidUrl) {
        final Timer[] debounceTimer = new Timer[1];
        return new DocumentListener() {
            private void scheduleValidation() {
                if (debounceTimer[0] != null && debounceTimer[0].isRunning()) {
                    debounceTimer[0].stop();
                }
                debounceTimer[0] = new Timer(500, ev -> {
                    String text = urlField.getText().trim();
                    boolean ok = isUrlValid(text);
                    urlErrorLabel.setVisible(!ok);
                    if (ok) {
                        onValidUrl.run();
                    }
                    // Repack containing dialog/window so visibility change is applied
                    SwingUtilities.invokeLater(() -> {
                        Window w = SwingUtilities.getWindowAncestor(urlField);
                        if (w != null) w.pack();
                    });
                });
                debounceTimer[0].setRepeats(false);
                debounceTimer[0].start();
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleValidation();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleValidation();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleValidation();
            }
        };
    }

    /**
     * Helper to validate inputs from Add/Edit dialogs and construct an McpServer. Returns null if validation failed.
     *
     * <p>This method also normalizes bearer token inputs if they start with "Bearer " and updates the provided
     * tokenField with the normalized value (so the user sees the normalized form).
     */
    private @Nullable HttpMcpServer createMcpServerFromInputs(
            String name, String rawUrl, boolean useToken, JPasswordField tokenField, @Nullable List<String> tools) {

        // Validate URL presence and format - inline validation will show error
        if (rawUrl.isEmpty()) {
            return null;
        }

        URL url;
        try {
            var u = new URI(rawUrl).toURL();
            String host = u.getHost();
            if (host == null || host.isEmpty()) throw new MalformedURLException("Missing host");
            url = u;
        } catch (Exception mfe) {
            return null;
        }

        // Name fallback (only check emptiness; name is non-null)
        if (name.isEmpty()) name = rawUrl;

        // Token normalization (non-obstructive)
        String token = null;
        if (useToken) {
            String raw = new String(tokenField.getPassword()).trim();
            if (!raw.isEmpty()) {
                String bearerToken;
                if (raw.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    bearerToken = raw;
                    tokenField.setText(raw.substring(7).trim());
                } else {
                    bearerToken = "Bearer " + raw;
                }
                token = bearerToken;
            } else {
                token = null; // empty token => treat as null
            }
        }

        return new HttpMcpServer(name, url, tools, token);
    }

    /** Initiates an asynchronous MCP tools fetch using the provided Callable and updates UI components accordingly. */
    private void initiateMcpToolsFetch(
            JLabel fetchStatusLabel,
            Callable<List<McpSchema.Tool>> fetcher,
            McpToolTable toolsTable,
            JScrollPane toolsScrollPane,
            JTextArea errorTextArea,
            JScrollPane errorScrollPane,
            AtomicReference<@Nullable List<McpSchema.Tool>> fetchedToolDetails,
            AtomicReference<@Nullable List<String>> fetchedTools) {

        fetchStatusLabel.setIcon(SpinnerIconUtil.getSpinner(this.chrome, true));
        fetchStatusLabel.setText("Fetching...");

        new SwingWorker<List<McpSchema.Tool>, Void>() {
            @Override
            protected List<McpSchema.Tool> doInBackground() throws Exception {
                return fetcher.call();
            }

            @Override
            protected void done() {
                fetchStatusLabel.setIcon(null);
                fetchStatusLabel.setText(" ");
                try {
                    List<McpSchema.Tool> tools = get();
                    fetchedToolDetails.set(tools);
                    var toolNames = tools.stream().map(McpSchema.Tool::name).collect(Collectors.toList());
                    fetchedTools.set(toolNames);

                    toolsTable.setToolsFromDetails(tools);

                    toolsScrollPane.setVisible(true);
                    errorScrollPane.setVisible(false);
                    SwingUtilities.getWindowAncestor(fetchStatusLabel).pack();
                } catch (Exception ex) {
                    var root = ex.getCause() != null ? ex.getCause() : ex;
                    logger.error("Error fetching MCP tools", root);
                    fetchedTools.set(null);
                    fetchedToolDetails.set(null);

                    errorTextArea.setText(root.getMessage());
                    toolsScrollPane.setVisible(false);
                    errorScrollPane.setVisible(true);
                    SwingUtilities.getWindowAncestor(fetchStatusLabel).pack();
                }
            }
        }.execute();
    }

    private static class ArgsTableModel extends AbstractTableModel {
        private final List<String> args;
        private final String[] columnNames = {"Argument"};

        public ArgsTableModel(List<String> args) {
            this.args = new ArrayList<>(args);
        }

        public List<String> getArgs() {
            return args;
        }

        @Override
        public int getRowCount() {
            return args.size();
        }

        @Override
        public int getColumnCount() {
            return 1;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return args.get(rowIndex);
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            args.set(rowIndex, (String) aValue);
            fireTableCellUpdated(rowIndex, columnIndex);
        }

        public void addRow() {
            args.add("");
            fireTableRowsInserted(args.size() - 1, args.size() - 1);
        }

        public void removeRow(int rowIndex) {
            if (rowIndex >= 0 && rowIndex < args.size()) {
                args.remove(rowIndex);
                fireTableRowsDeleted(rowIndex, rowIndex);
            }
        }
    }

    private static class EnvVarCellRenderer extends DefaultTableCellRenderer {
        // Environment variable detection delegated to Environment.detectEnvVarReference
        private static final Border SUCCESS_BORDER;
        private static final Border FAILURE_BORDER;

        static {
            Color successColor = UIManager.getColor("ProgressBar.foreground");
            if (successColor == null) {
                // A green that is visible on both light and dark themes.
                successColor = new Color(0, 176, 80);
            }
            SUCCESS_BORDER = BorderFactory.createMatteBorder(0, 0, 0, 2, successColor);

            Color errorColor = UIManager.getColor("Label.errorForeground");
            if (errorColor == null) {
                // A red that is visible on both light and dark themes.
                errorColor = new Color(219, 49, 49);
            }
            FAILURE_BORDER = BorderFactory.createMatteBorder(0, 0, 0, 2, errorColor);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {

            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            // Reset per-render state; renderer instances are reused.
            setToolTipText(null);
            setBorder(null);

            if (value instanceof String val) {
                String trimmedVal = val.trim();
                var ref = Environment.detectEnvVarReference(trimmedVal);
                if (ref != null) {
                    String varName = ref.name();
                    if (ref.defined()) {
                        setToolTipText("Environment variable '" + varName + "' found.");
                        setBorder(SUCCESS_BORDER);
                    } else {
                        setToolTipText("Environment variable '" + varName
                                + "' not set in Brokk's environment. Using the literal text as-is.");
                        setBorder(FAILURE_BORDER);
                    }
                }
            }
            return this;
        }
    }

    private static class EnvTableModel extends AbstractTableModel {
        private final List<String[]> envVars;
        private final String[] columnNames = {"Variable", "Value"};

        public EnvTableModel(Map<String, String> env) {
            this.envVars = new ArrayList<>(env.entrySet().stream()
                    .map(e -> new String[] {e.getKey(), e.getValue()})
                    .collect(Collectors.toList()));
        }

        public Map<String, String> getEnvMap() {
            return envVars.stream()
                    .filter(p -> p[0] != null && !p[0].trim().isEmpty())
                    .collect(Collectors.toMap(p -> p[0], p -> p[1] != null ? p[1] : "", (v1, v2) -> v2));
        }

        @Override
        public int getRowCount() {
            return envVars.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return envVars.get(rowIndex)[columnIndex];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            envVars.get(rowIndex)[columnIndex] = (String) aValue;
            fireTableCellUpdated(rowIndex, columnIndex);
        }

        public void addRow() {
            envVars.add(new String[] {"", ""});
            fireTableRowsInserted(envVars.size() - 1, envVars.size() - 1);
        }

        public void removeRow(int rowIndex) {
            if (rowIndex >= 0 && rowIndex < envVars.size()) {
                envVars.remove(rowIndex);
                fireTableRowsDeleted(rowIndex, rowIndex);
            }
        }
    }

    // --- Inner Classes for Quick Models Table (Copied from SettingsDialog) ---
    private static class FavoriteModelsTableModel extends AbstractTableModel {
        private List<Service.FavoriteModel> favorites;
        private final String[] columnNames = {"Alias", "Model Name", "Reasoning", "Processing Tier"};

        public FavoriteModelsTableModel(List<Service.FavoriteModel> initialFavorites) {
            this.favorites = new ArrayList<>(initialFavorites);
        }

        public void setFavorites(List<Service.FavoriteModel> newFavorites) {
            this.favorites = new ArrayList<>(newFavorites);
            fireTableDataChanged();
        }

        public List<Service.FavoriteModel> getFavorites() {
            return new ArrayList<>(favorites);
        }

        public void addFavorite(Service.FavoriteModel favorite) {
            favorites.add(favorite);
            fireTableRowsInserted(favorites.size() - 1, favorites.size() - 1);
        }

        public void removeFavorite(int rowIndex) {
            if (favorites.size() <= 1) {
                // Enforce at least one favorite remains
                return;
            }
            if (rowIndex >= 0 && rowIndex < favorites.size()) {
                favorites.remove(rowIndex);
                fireTableRowsDeleted(rowIndex, rowIndex);
            }
        }

        @Override
        public int getRowCount() {
            return favorites.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 0, 1 -> String.class;
                case 2 -> Service.ReasoningLevel.class;
                case 3 -> Service.ProcessingTier.class;
                default -> Object.class;
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public @Nullable Object getValueAt(int rowIndex, int columnIndex) {
            Service.FavoriteModel favorite = favorites.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> favorite.alias();
                case 1 -> favorite.config().name();
                case 2 -> favorite.config().reasoning();
                case 3 -> favorite.config().tier();
                default -> null;
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (rowIndex < 0 || rowIndex >= favorites.size()) return;
            Service.FavoriteModel oldFavorite = favorites.get(rowIndex);
            Service.FavoriteModel newFavorite;
            try {
                newFavorite = switch (columnIndex) {
                    case 0 -> {
                        if (aValue instanceof String alias) {
                            yield new Service.FavoriteModel(alias.trim(), oldFavorite.config());
                        }
                        yield oldFavorite;
                    }
                    case 1 -> {
                        if (aValue instanceof String modelName) {
                            yield new Service.FavoriteModel(
                                    oldFavorite.alias(),
                                    new Service.ModelConfig(
                                            modelName,
                                            oldFavorite.config().reasoning(),
                                            oldFavorite.config().tier()));
                        }
                        yield oldFavorite;
                    }
                    case 2 -> {
                        if (aValue instanceof Service.ReasoningLevel reasoning) {
                            yield new Service.FavoriteModel(
                                    oldFavorite.alias(),
                                    new Service.ModelConfig(
                                            oldFavorite.config().name(),
                                            reasoning,
                                            oldFavorite.config().tier()));
                        }
                        yield oldFavorite;
                    }
                    case 3 -> {
                        if (aValue instanceof Service.ProcessingTier tier) {
                            yield new Service.FavoriteModel(
                                    oldFavorite.alias(),
                                    new Service.ModelConfig(
                                            oldFavorite.config().name(),
                                            oldFavorite.config().reasoning(),
                                            tier));
                        }
                        yield oldFavorite;
                    }
                    default -> oldFavorite;
                };
            } catch (Exception e) {
                logger.error("Error setting value at ({}, {}) to {}", rowIndex, columnIndex, aValue, e);
                return;
            }
            if (!newFavorite.equals(oldFavorite)) {
                favorites.set(rowIndex, newFavorite);
                fireTableCellUpdated(rowIndex, columnIndex);
                if (columnIndex == 1) {
                    fireTableCellUpdated(rowIndex, 2); // If model name changed, reasoning support might change
                    fireTableCellUpdated(rowIndex, 3); // And processing tier support might also change
                }
            }
        }
    }

    private static class ReasoningCellRenderer extends DefaultTableCellRenderer {
        private final AbstractService service;

        public ReasoningCellRenderer(AbstractService service) {
            this.service = service;
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label =
                    (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            int modelRow = table.convertRowIndexToModel(row);
            String modelName = (String) table.getModel().getValueAt(modelRow, 1);
            if (modelName != null && !service.supportsReasoningEffort(modelName)) {
                label.setText("Off");
                label.setEnabled(false);
                label.setToolTipText("Reasoning effort not supported by " + modelName);
            } else if (value instanceof Service.ReasoningLevel level) {
                label.setText(level.toString());
                label.setEnabled(true);
                label.setToolTipText("Select reasoning effort");
            } else {
                label.setText(value == null ? "" : value.toString());
                label.setEnabled(true);
                label.setToolTipText(null);
            }
            if (!isSelected) {
                label.setBackground(table.getBackground());
                label.setForeground(
                        label.isEnabled() ? table.getForeground() : UIManager.getColor("Label.disabledForeground"));
            } else {
                label.setBackground(table.getSelectionBackground());
                label.setForeground(
                        label.isEnabled()
                                ? table.getSelectionForeground()
                                : UIManager.getColor("Label.disabledForeground"));
            }
            return label;
        }
    }

    private static class ReasoningCellEditor extends DefaultCellEditor {
        private final AbstractService service;
        private final JTable table;
        private final JComboBox<Service.ReasoningLevel> comboBox;

        public ReasoningCellEditor(JComboBox<Service.ReasoningLevel> comboBox, AbstractService service, JTable table) {
            super(comboBox);
            this.comboBox = comboBox;
            this.service = service;
            this.table = table;
            setClickCountToStart(1);
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table, Object value, boolean isSelected, int row, int column) {
            int modelRow = table.convertRowIndexToModel(row);
            String modelName = (String) table.getModel().getValueAt(modelRow, 1);
            boolean supportsReasoning = modelName != null && service.supportsReasoningEffort(modelName);
            Component editorComponent = super.getTableCellEditorComponent(table, value, isSelected, row, column);
            editorComponent.setEnabled(supportsReasoning);
            comboBox.setEnabled(supportsReasoning);
            if (!supportsReasoning) {
                comboBox.setSelectedItem(Service.ReasoningLevel.DEFAULT);
                comboBox.setToolTipText("Reasoning effort not supported by " + modelName);
                comboBox.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> list, @Nullable Object val, int i, boolean sel, boolean foc) {
                        JLabel label = (JLabel) super.getListCellRendererComponent(list, val, i, sel, foc);
                        if (i == -1) {
                            label.setText("Off");
                            label.setForeground(UIManager.getColor("ComboBox.disabledForeground"));
                        } else if (val instanceof Service.ReasoningLevel lvl) label.setText(lvl.toString());
                        else label.setText(val == null ? "" : val.toString());
                        return label;
                    }
                });
            } else {
                comboBox.setToolTipText("Select reasoning effort");
                comboBox.setRenderer(new DefaultListCellRenderer());
                comboBox.setSelectedItem(value);
            }
            return editorComponent;
        }

        @Override
        public boolean isCellEditable(EventObject anEvent) {
            int editingRow = table.getEditingRow();
            if (editingRow != -1) {
                int modelRow = table.convertRowIndexToModel(editingRow);
                String modelName = (String) table.getModel().getValueAt(modelRow, 1);
                return modelName != null && service.supportsReasoningEffort(modelName);
            }
            return super.isCellEditable(anEvent);
        }

        @Override
        public Object getCellEditorValue() {
            return comboBox.isEnabled() ? super.getCellEditorValue() : Service.ReasoningLevel.DEFAULT;
        }
    }

    private static class ProcessingTierCellRenderer extends DefaultTableCellRenderer {
        private final AbstractService service;

        public ProcessingTierCellRenderer(AbstractService service) {
            this.service = service;
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label =
                    (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            int modelRow = table.convertRowIndexToModel(row);
            String modelName = (String) table.getModel().getValueAt(modelRow, 1);
            if (modelName != null && !service.supportsProcessingTier(modelName)) {
                label.setText("Off");
                label.setEnabled(false);
                label.setToolTipText("Processing tiers not supported by " + modelName);
            } else if (value instanceof Service.ProcessingTier tier) {
                label.setText(tier.toString());
                label.setEnabled(true);
                label.setToolTipText("Select processing tier");
            } else {
                label.setText(value == null ? "" : value.toString());
                label.setEnabled(true);
                label.setToolTipText(null);
            }
            if (!isSelected) {
                label.setBackground(table.getBackground());
                label.setForeground(
                        label.isEnabled() ? table.getForeground() : UIManager.getColor("Label.disabledForeground"));
            } else {
                label.setBackground(table.getSelectionBackground());
                label.setForeground(
                        label.isEnabled()
                                ? table.getSelectionForeground()
                                : UIManager.getColor("Label.disabledForeground"));
            }
            return label;
        }
    }

    private static class ProcessingTierCellEditor extends DefaultCellEditor {
        private final AbstractService service;
        private final JTable table;
        private final JComboBox<Service.ProcessingTier> comboBox;

        public ProcessingTierCellEditor(
                JComboBox<Service.ProcessingTier> comboBox, AbstractService service, JTable table) {
            super(comboBox);
            this.comboBox = comboBox;
            this.service = service;
            this.table = table;
            setClickCountToStart(1);
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table, Object value, boolean isSelected, int row, int column) {
            int modelRow = table.convertRowIndexToModel(row);
            String modelName = (String) table.getModel().getValueAt(modelRow, 1);
            boolean supports = modelName != null && service.supportsProcessingTier(modelName);
            Component editorComponent = super.getTableCellEditorComponent(table, value, isSelected, row, column);
            editorComponent.setEnabled(supports);
            comboBox.setEnabled(supports);
            if (!supports) {
                comboBox.setSelectedItem(Service.ProcessingTier.DEFAULT);
                comboBox.setToolTipText("Processing tiers not supported by " + modelName);
                comboBox.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> list, @Nullable Object val, int i, boolean sel, boolean foc) {
                        JLabel label = (JLabel) super.getListCellRendererComponent(list, val, i, sel, foc);
                        if (i == -1) {
                            label.setText("Off");
                            label.setForeground(UIManager.getColor("ComboBox.disabledForeground"));
                        } else if (val instanceof Service.ProcessingTier p) label.setText(p.toString());
                        else label.setText(val == null ? "" : val.toString());
                        return label;
                    }
                });
            } else {
                comboBox.setToolTipText("Select processing tier");
                comboBox.setRenderer(new DefaultListCellRenderer());
                comboBox.setSelectedItem(value);
            }
            return editorComponent;
        }

        @Override
        public boolean isCellEditable(EventObject anEvent) {
            int editingRow = table.getEditingRow();
            if (editingRow != -1) {
                int modelRow = table.convertRowIndexToModel(editingRow);
                String modelName = (String) table.getModel().getValueAt(modelRow, 1);
                return modelName != null && service.supportsProcessingTier(modelName);
            }
            return super.isCellEditable(anEvent);
        }

        @Override
        public Object getCellEditorValue() {
            return comboBox.isEnabled() ? super.getCellEditorValue() : Service.ProcessingTier.DEFAULT;
        }
    }

    // SettingsChangeListener implementation
    @Override
    public void gitHubTokenChanged() {
        gitHubSettingsPanel.gitHubTokenChanged();
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        MainProject.removeSettingsChangeListener(this);
    }

    // --- Default keybinding definitions ---
    private static KeyStroke defaultToggleMode() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_M);
    }

    // Global text editing defaults
    private static KeyStroke defaultUndo() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_Z);
    }

    private static KeyStroke defaultRedo() {
        return KeyboardShortcutUtil.createPlatformShiftShortcut(KeyEvent.VK_Z);
    }

    private static KeyStroke defaultCopy() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_C);
    }

    private static KeyStroke defaultPaste() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_V);
    }

    // Voice and interface defaults
    private static KeyStroke defaultToggleMicrophone() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_L);
    }

    // Panel navigation defaults (Alt/Cmd+Number)
    private static KeyStroke defaultSwitchToProjectFiles() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_1, modifier);
    }

    private static KeyStroke defaultSwitchToDependencies() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_2, modifier);
    }

    private static KeyStroke defaultSwitchToChanges() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_3, modifier);
    }

    private static KeyStroke defaultSwitchToWorktrees() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_4, modifier);
    }

    private static KeyStroke defaultSwitchToLog() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_5, modifier);
    }

    private static KeyStroke defaultSwitchToPullRequests() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_6, modifier);
    }

    private static KeyStroke defaultSwitchToIssues() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_7, modifier);
    }

    // General navigation defaults
    private static KeyStroke defaultCloseWindow() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_W);
    }

    private static KeyStroke defaultOpenSettings() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_COMMA);
    }

    // Drawer navigation defaults
    private static KeyStroke defaultToggleTerminalDrawer() {
        return KeyStroke.getKeyStroke(
                KeyEvent.VK_T, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK);
    }

    private static KeyStroke defaultToggleDependenciesDrawer() {
        return KeyStroke.getKeyStroke(
                KeyEvent.VK_D, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK);
    }

    private static KeyStroke defaultSwitchToTerminalTab() {
        return KeyStroke.getKeyStroke(KeyEvent.VK_T, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    private static KeyStroke defaultSwitchToTasksTab() {
        return KeyStroke.getKeyStroke(KeyEvent.VK_K, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    // View control defaults
    private static KeyStroke defaultZoomIn() {
        return KeyStroke.getKeyStroke(
                KeyEvent.VK_PLUS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    private static KeyStroke defaultZoomInAlt() {
        return KeyStroke.getKeyStroke(
                KeyEvent.VK_EQUALS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    private static KeyStroke defaultZoomOut() {
        return KeyStroke.getKeyStroke(
                KeyEvent.VK_MINUS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    private static KeyStroke defaultResetZoom() {
        return KeyStroke.getKeyStroke(KeyEvent.VK_0, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    @SuppressWarnings("UnusedMethod")
    private static KeyStroke defaultFor(String id) {
        return switch (id) {
            // Instructions panel
            case "instructions.submit" -> KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
            case "instructions.toggleMode" -> defaultToggleMode();

            // Global text editing
            case "global.undo" -> defaultUndo();
            case "global.redo" -> defaultRedo();
            case "global.copy" -> defaultCopy();
            case "global.paste" -> defaultPaste();

            // Voice and interface
            case "global.toggleMicrophone" -> defaultToggleMicrophone();

            // Panel navigation
            case "panel.switchToProjectFiles" -> defaultSwitchToProjectFiles();
            case "panel.switchToDependencies" -> defaultSwitchToDependencies();
            case "panel.switchToChanges" -> defaultSwitchToChanges();
            case "panel.switchToWorktrees" -> defaultSwitchToWorktrees();
            case "panel.switchToLog" -> defaultSwitchToLog();
            case "panel.switchToPullRequests" -> defaultSwitchToPullRequests();
            case "panel.switchToIssues" -> defaultSwitchToIssues();

            // Drawer navigation
            case "drawer.toggleTerminal" -> defaultToggleTerminalDrawer();
            case "drawer.toggleDependencies" -> defaultToggleDependenciesDrawer();
            case "drawer.switchToTerminal" -> defaultSwitchToTerminalTab();
            case "drawer.switchToTasks" -> defaultSwitchToTasksTab();

            // View controls
            case "view.zoomIn" -> defaultZoomIn();
            case "view.zoomInAlt" -> defaultZoomInAlt();
            case "view.zoomOut" -> defaultZoomOut();
            case "view.resetZoom" -> defaultResetZoom();

            // General navigation
            case "global.openSettings" -> defaultOpenSettings();
            case "global.closeWindow" -> defaultCloseWindow();

            // Workspace actions
            case "workspace.attachContext" -> KeyboardShortcutUtil.createPlatformShiftShortcut(KeyEvent.VK_I);
            case "workspace.attachFilesAndSummarize" ->
                KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK);

            default ->
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        };
    }
}
