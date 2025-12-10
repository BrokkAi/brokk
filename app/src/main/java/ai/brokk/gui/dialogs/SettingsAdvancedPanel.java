package ai.brokk.gui.dialogs;

import ai.brokk.AbstractService;
import ai.brokk.Service;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.dialogs.SettingsDialog.SettingsData;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.JDeploySettingsUtil;
import ai.brokk.mcp.McpConfig;
import ai.brokk.mcp.McpServer;
import ai.brokk.project.MainProject;
import ai.brokk.util.GlobalUiSettings;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class SettingsAdvancedPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(SettingsAdvancedPanel.class);

    public static final String MODELS_TAB_TITLE = "Models";

    private final Chrome chrome;
    private final SettingsDialog parentDialog;

    private final JTabbedPane advancedSubTabbedPane = new JTabbedPane(JTabbedPane.TOP);

    // General tab
    private final JRadioButton memoryAutoRadio = new JRadioButton("Automatic (recommended)");
    private final JRadioButton memoryManualRadio = new JRadioButton("Manual:");
    private final JSpinner memorySpinner = new JSpinner();
    private final JCheckBox instructionsTabInsertIndentationCheckbox =
            new JCheckBox("Tab inserts indentation in Instructions (Code-style)");
    private final JCheckBox advancedModeCheckbox =
            new JCheckBox("Enable Advanced Mode (show all UI)");
    private final JCheckBox skipCommitGateEzCheckbox =
            new JCheckBox("Skip commit gate in EZ mode");
    private final JComboBox<String> watchServiceImplCombo =
            new JComboBox<>(new String[] {"Default (auto)", "Legacy", "Native"});

    // Startup tab
    private final JRadioButton startupOpenLastRadio =
            new JRadioButton("Open last project (recommended)");
    private final JRadioButton startupOpenAllRadio =
            new JRadioButton("Reopen all previously open projects");
    private final JCheckBox persistPerProjectWindowCheckbox =
            new JCheckBox("Save window position per project (recommended)");

    // Notifications tab
    private final JCheckBox showCostNotificationsCheckbox =
            new JCheckBox("Show LLM cost notifications");
    private final JCheckBox showFreeInternalLLMCheckbox =
            new JCheckBox("Show free internal LLM calls");
    private final JCheckBox showErrorNotificationsCheckbox =
            new JCheckBox("Show error notifications");
    private final JCheckBox showConfirmNotificationsCheckbox =
            new JCheckBox("Show confirmation notifications");
    private final JCheckBox showInfoNotificationsCheckbox =
            new JCheckBox("Show info notifications");

    // Models tab
    private JTable quickModelsTable = new JTable();
    private FavoriteModelsTableModel quickModelsTableModel =
            new FavoriteModelsTableModel(new ArrayList<>());
    private JComboBox<Service.FavoriteModel> preferredCodeModelCombo = new JComboBox<>();
    private JComboBox<Service.FavoriteModel> primaryModelCombo = new JComboBox<>();
    private JComboBox<String> otherModelsVendorCombo =
            new JComboBox<>(new String[] {"Default"});
    @Nullable
    private JLabel otherModelsVendorLabel;
    @Nullable
    private JPanel otherModelsVendorHolder;
    private boolean plannerModelSyncListenerRegistered = false;

    public record AdvancedValues(
            MainProject.JvmMemorySettings jvmMemorySettings,
            MainProject.StartupOpenMode startupOpenMode,
            boolean persistPerProjectBounds,
            boolean instructionsTabInsertIndentation,
            boolean advancedMode,
            boolean skipCommitGateEzMode,
            List<Service.FavoriteModel> favoriteModels,
            @Nullable Service.FavoriteModel selectedCodeFavorite,
            @Nullable Service.FavoriteModel selectedPrimaryFavorite,
            String otherModelsVendor,
            boolean showCostNotifications,
            boolean showFreeInternalLLMCostNotifications,
            boolean showErrorNotifications,
            boolean showConfirmNotifications,
            boolean showInfoNotifications,
            String watchServiceImplPreference) {}

    public SettingsAdvancedPanel(Chrome chrome, SettingsDialog parentDialog) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        this.chrome = chrome;
        this.parentDialog = parentDialog;
        setLayout(new BorderLayout());
        initComponents();
        setEnabled(false);
    }

    public void populateFromData(SettingsData data) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        setEnabled(true);
        populateGeneralTab(data);
        populateStartupTab();
        populateNotificationsTab();
        populateQuickModelsTab(data);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setEnabledRecursive(this, enabled);
    }

    private void setEnabledRecursive(Component c, boolean enabled) {
        c.setEnabled(enabled);
        if (c instanceof JPanel panel) {
            for (Component child : panel.getComponents()) {
                setEnabledRecursive(child, enabled);
            }
        }
    }

    public AdvancedValues collectAdvancedValues() {
        boolean jvmAutomatic = memoryAutoRadio.isSelected();
        int jvmMb = ((Number) memorySpinner.getValue()).intValue();
        var jvmSettings = new MainProject.JvmMemorySettings(jvmAutomatic, jvmMb);

        MainProject.StartupOpenMode startupMode = startupOpenAllRadio.isSelected()
                ? MainProject.StartupOpenMode.ALL
                : MainProject.StartupOpenMode.LAST;

        boolean persistPerProject = persistPerProjectWindowCheckbox.isSelected();
        boolean instructionsIndent = instructionsTabInsertIndentationCheckbox.isSelected();
        boolean advancedMode = advancedModeCheckbox.isSelected();
        boolean skipEzGate = skipCommitGateEzCheckbox.isSelected();

        // Watch service implementation preference (no persistence here)
        String watchPrefSelected;
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

        if (quickModelsTable.isEditing()) {
            quickModelsTable.getCellEditor().stopCellEditing();
        }
        var favoriteModels = quickModelsTableModel.getFavorites();
        if (favoriteModels.isEmpty()) {
            var currentCodeConfig = chrome.getProject().getMainProject().getCodeModelConfig();
            favoriteModels = List.of(new Service.FavoriteModel("default", currentCodeConfig));
            quickModelsTableModel.setFavorites(favoriteModels);
        }

        Service.FavoriteModel selectedCodeFavorite =
                (Service.FavoriteModel) preferredCodeModelCombo.getSelectedItem();
        Service.FavoriteModel selectedPrimaryFavorite =
                (Service.FavoriteModel) primaryModelCombo.getSelectedItem();
        String vendor = (String) otherModelsVendorCombo.getSelectedItem();
        if (vendor == null) {
            vendor = "Default";
        }

        return new AdvancedValues(
                jvmSettings,
                startupMode,
                persistPerProject,
                instructionsIndent,
                advancedMode,
                skipEzGate,
                favoriteModels,
                selectedCodeFavorite,
                selectedPrimaryFavorite,
                vendor,
                showCostNotificationsCheckbox.isSelected(),
                showFreeInternalLLMCheckbox.isSelected(),
                showErrorNotificationsCheckbox.isSelected(),
                showConfirmNotificationsCheckbox.isSelected(),
                showInfoNotificationsCheckbox.isSelected(),
                watchPrefSelected);
    }

    /**
     * Apply advanced/global settings managed by this panel (JVM memory, startup behavior,
     * notifications, advanced mode, favorite models, model roles, etc).
     */
    public boolean applySettings() {
        AdvancedValues values = collectAdvancedValues();

        // JVM memory
        MainProject.setJvmMemorySettings(values.jvmMemorySettings());
        JDeploySettingsUtil.updateJvmMemorySettings(values.jvmMemorySettings());

        // Startup behavior
        MainProject.setStartupOpenMode(values.startupOpenMode());

        // UI / instructions behavior
        GlobalUiSettings.savePersistPerProjectBounds(values.persistPerProjectBounds());
        GlobalUiSettings.saveInstructionsTabInsertIndentation(values.instructionsTabInsertIndentation());
        GlobalUiSettings.saveAdvancedMode(values.advancedMode());
        GlobalUiSettings.saveSkipCommitGateInEzMode(values.skipCommitGateEzMode());

        // Notifications
        GlobalUiSettings.saveShowCostNotifications(values.showCostNotifications());
        GlobalUiSettings.saveShowFreeInternalLLMCostNotifications(values.showFreeInternalLLMCostNotifications());
        GlobalUiSettings.saveShowErrorNotifications(values.showErrorNotifications());
        GlobalUiSettings.saveShowConfirmNotifications(values.showConfirmNotifications());
        GlobalUiSettings.saveShowInfoNotifications(values.showInfoNotifications());

        // Favorite models
        MainProject.saveFavoriteModels(values.favoriteModels());

        // Model roles (code / architect)
        var mainProject = chrome.getProject().getMainProject();
        if (values.selectedCodeFavorite() != null) {
            mainProject.setCodeModelConfig(values.selectedCodeFavorite().config());
        }
        if (values.selectedPrimaryFavorite() != null) {
            mainProject.setArchitectModelConfig(values.selectedPrimaryFavorite().config());
        }

        // Vendor preference for other models
        String vendor = values.otherModelsVendor();
        if (vendor == null || vendor.isBlank() || "Default".equalsIgnoreCase(vendor)) {
            MainProject.setOtherModelsVendorPreference("");
        } else {
            MainProject.setOtherModelsVendorPreference(vendor.trim());
        }

        // Watch service implementation preference
        MainProject.setWatchServiceImplPreference(values.watchServiceImplPreference());

        return true;
    }

    public JTabbedPane getAdvancedSubTabbedPane() {
        return advancedSubTabbedPane;
    }

    private void initComponents() {
        var generalPanel = createGeneralPanel();
        advancedSubTabbedPane.addTab("General", null, generalPanel, "Advanced general settings");

        var modelsPanel = createQuickModelsPanel();
        advancedSubTabbedPane.addTab(MODELS_TAB_TITLE, null, modelsPanel, "Configure models and favorites");

        var startupPanel = createStartupPanel();
        advancedSubTabbedPane.addTab("Startup", null, startupPanel, "Advanced startup behavior");

        var notificationsPanel = createNotificationsPanel();
        advancedSubTabbedPane.addTab("Notifications", null, notificationsPanel, "Notification preferences");

        add(advancedSubTabbedPane, BorderLayout.CENTER);
    }

    private void populateGeneralTab(SettingsData data) {
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
                }
            }
        } catch (Exception ignore) {
        }

        advancedModeCheckbox.setSelected(GlobalUiSettings.isAdvancedMode());
        skipCommitGateEzCheckbox.setSelected(GlobalUiSettings.isSkipCommitGateInEzMode());
        skipCommitGateEzCheckbox.setVisible(!GlobalUiSettings.isAdvancedMode());
        instructionsTabInsertIndentationCheckbox.setSelected(
                GlobalUiSettings.isInstructionsTabInsertIndentation());

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
    }

    private void populateStartupTab() {
        var startupMode = MainProject.getStartupOpenMode();
        if (startupMode == MainProject.StartupOpenMode.ALL) {
            startupOpenAllRadio.setSelected(true);
        } else {
            startupOpenLastRadio.setSelected(true);
        }
        persistPerProjectWindowCheckbox.setSelected(
                GlobalUiSettings.isPersistPerProjectBounds());
    }

    private void populateNotificationsTab() {
        showCostNotificationsCheckbox.setSelected(
                GlobalUiSettings.isShowCostNotifications());
        showFreeInternalLLMCheckbox.setSelected(
                GlobalUiSettings.isShowFreeInternalLLMCostNotifications());
        showErrorNotificationsCheckbox.setSelected(
                GlobalUiSettings.isShowErrorNotifications());
        showConfirmNotificationsCheckbox.setSelected(
                GlobalUiSettings.isShowConfirmNotifications());
        showInfoNotificationsCheckbox.setSelected(
                GlobalUiSettings.isShowInfoNotifications());
    }

    private void populateQuickModelsTab(SettingsData data) {
        var service = chrome.getContextManager().getService();
        var loadedFavorites = data.favoriteModels();
        quickModelsTableModel.setFavorites(loadedFavorites);

        var favoriteRenderer = new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, @Nullable Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
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

        var currentCodeConfig = chrome.getProject().getMainProject().getCodeModelConfig();
        preferredCodeModelCombo.removeAllItems();
        for (Service.FavoriteModel favorite : loadedFavorites) {
            preferredCodeModelCombo.addItem(favorite);
        }
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

        var currentPlannerConfig = chrome.getProject().getMainProject().getArchitectModelConfig();
        primaryModelCombo.removeAllItems();
        for (Service.FavoriteModel favorite : loadedFavorites) {
            primaryModelCombo.addItem(favorite);
        }
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

        var availableNames = service.getAvailableModels().keySet();
        var vendors = new ArrayList<String>();
        vendors.add("Default");
        if (availableNames.contains(Service.HAIKU_4_5)) {
            vendors.add("Anthropic");
        }
        if (availableNames.contains(Service.GPT_5_NANO)
                && availableNames.contains(Service.GPT_5_MINI)) {
            vendors.add("OpenAI");
        }

        otherModelsVendorCombo.setModel(
                new javax.swing.DefaultComboBoxModel<>(vendors.toArray(new String[0])));

        String persistedVendor = MainProject.getOtherModelsVendorPreference();
        String vendorToSelect;
        if (!persistedVendor.isBlank() && vendors.contains(persistedVendor)) {
            vendorToSelect = persistedVendor;
        } else {
            vendorToSelect = "Default";
        }
        otherModelsVendorCombo.setSelectedItem(vendorToSelect);

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

        if (!plannerModelSyncListenerRegistered) {
            chrome.getInstructionsPanel().addModelSelectionListener(cfg -> {
                try {
                    boolean found = false;
                    for (int i = 0; i < primaryModelCombo.getItemCount(); i++) {
                        Service.FavoriteModel fm = primaryModelCombo.getItemAt(i);
                        if (fm != null && fm.config().equals(cfg)) {
                            int idx = i;
                            SwingUtilities.invokeLater(
                                    () -> primaryModelCombo.setSelectedIndex(idx));
                            found = true;
                            break;
                        }
                    }
                    if (!found && primaryModelCombo.getItemCount() > 0) {
                        SwingUtilities.invokeLater(
                                () -> primaryModelCombo.setSelectedIndex(0));
                    }
                } catch (Exception ex) {
                    logger.debug("Planner model sync listener failed (non-fatal)", ex);
                }
            });
            plannerModelSyncListenerRegistered = true;
        }
    }

    private JPanel createGeneralPanel() {
        var panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("JVM Memory Allocation:"), gbc);

        var memoryGroup = new ButtonGroup();
        memoryGroup.add(memoryAutoRadio);
        memoryGroup.add(memoryManualRadio);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        var autoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        autoPanel.add(memoryAutoRadio);
        panel.add(autoPanel, gbc);

        var spinnerModel = new SpinnerNumberModel(4096, 512, 16384, 128);
        try {
            long maxBytes = Runtime.getRuntime().maxMemory();
            int detectedMb;
            if (maxBytes <= 0 || maxBytes == Long.MAX_VALUE) {
                detectedMb = 4096;
            } else {
                detectedMb = (int) Math.round(maxBytes / 1024.0 / 1024.0);
                if (detectedMb < 512) detectedMb = 512;
                if (detectedMb > 16384) detectedMb = 16384;
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
        manualPanel.add(new javax.swing.Box.Filler(
                new java.awt.Dimension(5, 0),
                new java.awt.Dimension(5, 0),
                new java.awt.Dimension(5, 0)));
        manualPanel.add(memorySpinner);
        manualPanel.add(new javax.swing.Box.Filler(
                new java.awt.Dimension(5, 0),
                new java.awt.Dimension(5, 0),
                new java.awt.Dimension(5, 0)));
        manualPanel.add(new JLabel("MB"));

        gbc.gridy = row++;
        panel.add(manualPanel, gbc);

        memoryAutoRadio.addActionListener(e -> memorySpinner.setEnabled(false));
        memoryManualRadio.addActionListener(e -> memorySpinner.setEnabled(true));

        memoryAutoRadio.setSelected(true);
        memorySpinner.setEnabled(false);

        var restartLabel = new JLabel(
                "Restart required after changing memory settings");
        restartLabel.setFont(restartLabel.getFont().deriveFont(java.awt.Font.ITALIC));
        gbc.gridy = row++;
        gbc.insets = new Insets(0, 25, 2, 5);
        panel.add(restartLabel, gbc);
        gbc.insets = new Insets(2, 5, 2, 5);

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

        var watchNote = new JLabel(
                "<html><i>Changing this will require restarting Brokk to fully take effect.</i></html>");
        gbc.gridy = row++;
        gbc.insets = new Insets(0, 25, 2, 5);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(watchNote, gbc);

        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        panel.add(javax.swing.Box.createVerticalGlue(), gbc);

        return panel;
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

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        startupPanel.add(persistPerProjectWindowCheckbox, gbc);

        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        startupPanel.add(javax.swing.Box.createVerticalGlue(), gbc);

        return startupPanel;
    }

    private JPanel createNotificationsPanel() {
        var panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel("Notification preferences:"), gbc);
        gbc.gridwidth = 1;

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(showErrorNotificationsCheckbox, gbc);

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(showConfirmNotificationsCheckbox, gbc);

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(showInfoNotificationsCheckbox, gbc);

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(showCostNotificationsCheckbox, gbc);

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(showFreeInternalLLMCheckbox, gbc);

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(javax.swing.Box.createVerticalGlue(), gbc);

        return panel;
    }

    private JPanel createQuickModelsPanel() {
        var service = chrome.getContextManager().getService();
        var availableModelNames =
                service.getAvailableModels().keySet().stream().sorted().toArray(String[]::new);
        var reasoningLevels = Service.ReasoningLevel.values();

        quickModelsTableModel = new FavoriteModelsTableModel(new ArrayList<>());
        quickModelsTable = new JTable(quickModelsTableModel);
        quickModelsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        quickModelsTable.setRowHeight(quickModelsTable.getRowHeight() + 4);

        var sorter = new TableRowSorter<>(quickModelsTableModel);
        sorter.setComparator(2, java.util.Comparator.comparingInt(Service.ReasoningLevel::ordinal));
        boolean showServiceTiers = Boolean.getBoolean("brokk.servicetiers");
        if (showServiceTiers) {
            sorter.setComparator(
                    3, java.util.Comparator.comparingInt(Service.ProcessingTier::ordinal));
        }
        quickModelsTable.setRowSorter(sorter);

        TableColumn aliasColumn = quickModelsTable.getColumnModel().getColumn(0);
        aliasColumn.setPreferredWidth(100);

        TableColumn modelColumn = quickModelsTable.getColumnModel().getColumn(1);
        var modelComboBoxEditor = new JComboBox<>(availableModelNames);
        modelColumn.setCellEditor(new javax.swing.DefaultCellEditor(modelComboBoxEditor));
        modelColumn.setPreferredWidth(200);

        TableColumn reasoningColumn = quickModelsTable.getColumnModel().getColumn(2);
        var reasoningComboBoxEditor = new JComboBox<>(reasoningLevels);
        reasoningColumn.setCellEditor(
                new ReasoningCellEditor(reasoningComboBoxEditor, service, quickModelsTable));
        reasoningColumn.setCellRenderer(new ReasoningCellRenderer(service));
        reasoningColumn.setPreferredWidth(100);

        if (showServiceTiers) {
            TableColumn processingColumn = quickModelsTable.getColumnModel().getColumn(3);
            var processingComboBoxEditor =
                    new JComboBox<>(Service.ProcessingTier.values());
            processingColumn.setCellEditor(
                    new ProcessingTierCellEditor(
                            processingComboBoxEditor, service, quickModelsTable));
            processingColumn.setCellRenderer(new ProcessingTierCellRenderer(service));
            processingColumn.setPreferredWidth(120);
        } else {
            quickModelsTable.removeColumn(
                    quickModelsTable.getColumnModel().getColumn(3));
        }

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var addButton = new MaterialButton();
        addButton.setIcon(ai.brokk.gui.util.Icons.ADD);
        addButton.setToolTipText("Add favorite model");
        var removeButton = new MaterialButton();
        removeButton.setIcon(ai.brokk.gui.util.Icons.REMOVE);
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
                    new Service.FavoriteModel(
                            "new-alias", new Service.ModelConfig(defaultModel)));
            int modelRowIndex = quickModelsTableModel.getRowCount() - 1;
            int viewRowIndex =
                    quickModelsTable.convertRowIndexToView(modelRowIndex);
            quickModelsTable.setRowSelectionInterval(viewRowIndex, viewRowIndex);
            quickModelsTable.scrollRectToVisible(
                    quickModelsTable.getCellRect(viewRowIndex, 0, true));
            quickModelsTable.editCellAt(viewRowIndex, 0);
            Component editorComponent =
                    quickModelsTable.getEditorComponent();
            if (editorComponent != null) {
                editorComponent.requestFocusInWindow();
            }
        });

        removeButton.addActionListener(e -> {
            int rowCount = quickModelsTableModel.getRowCount();
            if (rowCount <= 1) {
                JOptionPane.showMessageDialog(
                        this,
                        "At least one favorite model is required.",
                        "Cannot Remove",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            int viewRow = quickModelsTable.getSelectedRow();
            if (viewRow != -1) {
                if (quickModelsTable.isEditing()) {
                    quickModelsTable.getCellEditor().stopCellEditing();
                }
                int modelRow =
                        quickModelsTable.convertRowIndexToModel(viewRow);
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

            var defaultFavorites =
                    new ArrayList<>(MainProject.DEFAULT_FAVORITE_MODELS);

            quickModelsTableModel.setFavorites(defaultFavorites);
            if (!defaultFavorites.isEmpty()) {
                int viewRowIndex =
                        quickModelsTable.convertRowIndexToView(0);
                quickModelsTable.setRowSelectionInterval(
                        viewRowIndex, viewRowIndex);
            }

            JOptionPane.showMessageDialog(
                    this,
                    "Favorite models restored to defaults.",
                    "Defaults Restored",
                    JOptionPane.INFORMATION_MESSAGE);
        });

        Runnable updateRemoveButtonEnabled = () -> {
            boolean hasSelection =
                    quickModelsTable.getSelectedRow() != -1;
            int rowCount = quickModelsTableModel.getRowCount();
            removeButton.setEnabled(hasSelection && rowCount > 1);
        };
        quickModelsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateRemoveButtonEnabled.run();
            }
        });
        quickModelsTableModel.addTableModelListener(e -> updateRemoveButtonEnabled.run());
        quickModelsTableModel.addTableModelListener(
                e -> SwingUtilities.invokeLater(
                        this::refreshFavoriteModelCombosPreservingSelection));
        updateRemoveButtonEnabled.run();

        var rolesPanel = new JPanel(new GridBagLayout());
        var gbcRoles = new GridBagConstraints();
        gbcRoles.insets = new Insets(5, 5, 5, 5);
        gbcRoles.anchor = GridBagConstraints.WEST;
        gbcRoles.fill = GridBagConstraints.HORIZONTAL;

        gbcRoles.gridx = 0;
        gbcRoles.gridy = 0;
        gbcRoles.weightx = 0.0;
        gbcRoles.fill = GridBagConstraints.NONE;
        rolesPanel.add(new JLabel("Primary Model:"), gbcRoles);

        primaryModelCombo = new JComboBox<>();
        var primaryComboHolder = new JPanel(new BorderLayout(0, 0));
        primaryComboHolder.add(primaryModelCombo, BorderLayout.CENTER);
        var primaryHelpButton = new MaterialButton();
        primaryHelpButton.setIcon(ai.brokk.gui.util.Icons.HELP);
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

        gbcRoles.gridx = 0;
        gbcRoles.gridy = 1;
        gbcRoles.weightx = 0.0;
        gbcRoles.fill = GridBagConstraints.NONE;
        rolesPanel.add(new JLabel("Lutz Code Model:"), gbcRoles);

        preferredCodeModelCombo = new JComboBox<>();
        var codeComboHolder = new JPanel(new BorderLayout(0, 0));
        codeComboHolder.add(preferredCodeModelCombo, BorderLayout.CENTER);
        var codeHelpButton = new MaterialButton();
        codeHelpButton.setIcon(ai.brokk.gui.util.Icons.HELP);
        codeHelpButton.setToolTipText(
                "This model is used by Lutz mode to implement tasks.");
        codeHelpButton.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        codeHelpButton.setContentAreaFilled(false);
        codeHelpButton.setFocusPainted(false);
        codeHelpButton.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
        codeComboHolder.add(codeHelpButton, BorderLayout.EAST);

        gbcRoles.gridx = 1;
        gbcRoles.weightx = 1.0;
        gbcRoles.fill = GridBagConstraints.HORIZONTAL;
        rolesPanel.add(codeComboHolder, gbcRoles);

        gbcRoles.gridx = 0;
        gbcRoles.gridy = 2;
        gbcRoles.weightx = 0.0;
        gbcRoles.fill = GridBagConstraints.NONE;
        otherModelsVendorLabel = new JLabel("Vendor for Other Models:");
        rolesPanel.add(otherModelsVendorLabel, gbcRoles);

        otherModelsVendorCombo =
                new JComboBox<>(new String[] {"Anthropic", "Default", "OpenAI"});
        otherModelsVendorCombo.setToolTipText(
                "Selects the default models for Quick, Quick Edit, Quickest, and Scan operations.");
        otherModelsVendorHolder = new JPanel(new BorderLayout(0, 0));
        otherModelsVendorHolder.add(otherModelsVendorCombo, BorderLayout.CENTER);

        String defaultQuick = MainProject.getDefaultQuickModelConfig().name();
        String defaultQuickEdit =
                MainProject.getDefaultQuickEditModelConfig().name();
        String defaultQuickest =
                MainProject.getDefaultQuickestModelConfig().name();
        String defaultScan = MainProject.getDefaultScanModelConfig().name();

        String vendorTooltip =
                "<html><div style='width: 340px;'>"
                        + "Selecting a vendor sets Quick, Quick Edit, Quickest, and Scan to vendor defaults.<br/><br/>"
                        + "<b>OpenAI:</b> Quick=gpt-5-nano; Quick Edit=gpt-5-nano; Quickest=gpt-5-nano; Scan=gpt-5-mini<br/>"
                        + "<b>Anthropic:</b> Quick=claude-haiku-4-5; Quick Edit=claude-haiku-4-5; Quickest=claude-haiku-4-5; Scan=claude-haiku-4-5<br/>"
                        + "<b>Default:</b> Quick="
                        + defaultQuick
                        + "; Quick Edit="
                        + defaultQuickEdit
                        + "; Quickest="
                        + defaultQuickest
                        + "; Scan="
                        + defaultScan
                        + "</div></html>";

        var vendorHelpButton = new MaterialButton();
        vendorHelpButton.setIcon(ai.brokk.gui.util.Icons.HELP);
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

        gbcRoles.gridx = 0;
        gbcRoles.gridy = 3;
        gbcRoles.weighty = 1.0;
        gbcRoles.fill = GridBagConstraints.BOTH;
        gbcRoles.gridwidth = 2;
        rolesPanel.add(javax.swing.Box.createVerticalGlue(), gbcRoles);
        gbcRoles.gridwidth = 1;

        var rolesButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        var defaultsRolesButton = new MaterialButton("Defaults");
        defaultsRolesButton.setToolTipText(
                "Restore default model role selections.");
        rolesButtonsPanel.add(defaultsRolesButton);

        gbcRoles.gridx = 0;
        gbcRoles.gridy = 4;
        gbcRoles.weighty = 0.0;
        gbcRoles.fill = GridBagConstraints.HORIZONTAL;
        gbcRoles.gridwidth = 2;
        gbcRoles.insets = new Insets(10, 5, 5, 5);
        rolesPanel.add(rolesButtonsPanel, gbcRoles);

        defaultsRolesButton.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(
                    SettingsAdvancedPanel.this,
                    "This will restore default model role selections.\nYou will lose your old settings. Continue?",
                    "Restore Default Model Roles",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }

            otherModelsVendorCombo.setSelectedItem("Default");

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
                    SettingsAdvancedPanel.this,
                    "Model role selections restored to defaults. Click Apply to save.",
                    "Defaults Restored",
                    JOptionPane.INFORMATION_MESSAGE);
        });

        var favoritesPanel = new JPanel(new BorderLayout(5, 5));
        favoritesPanel.add(new JScrollPane(quickModelsTable), BorderLayout.CENTER);
        favoritesPanel.add(buttonPanel, BorderLayout.SOUTH);

        var modelsTabbed = new JTabbedPane(JTabbedPane.TOP);
        modelsTabbed.addTab(
                "Favorites",
                null,
                favoritesPanel,
                "Manage favorite model aliases");
        modelsTabbed.addTab(
                "Model Roles",
                null,
                new JPanel(new BorderLayout(5, 5)) {
                    {
                        add(rolesPanel, BorderLayout.NORTH);
                        add(javax.swing.Box.createVerticalGlue(), BorderLayout.CENTER);
                        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
                    }
                },
                "Assign models for specific roles");

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
                    Service.FavoriteModel fav =
                            preferredCodeModelCombo.getItemAt(i);
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
                    Service.FavoriteModel fav =
                            primaryModelCombo.getItemAt(i);
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

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        SwingUtilities.updateComponentTreeUI(this);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
        SwingUtilities.updateComponentTreeUI(this);
    }

    private static class FavoriteModelsTableModel extends AbstractTableModel {
        private List<Service.FavoriteModel> favorites;
        private final String[] columnNames = {"Alias", "Model Name", "Reasoning", "Processing Tier"};

        FavoriteModelsTableModel(List<Service.FavoriteModel> initialFavorites) {
            this.favorites = new ArrayList<>(initialFavorites);
        }

        void setFavorites(List<Service.FavoriteModel> newFavorites) {
            this.favorites = new ArrayList<>(newFavorites);
            fireTableDataChanged();
        }

        List<Service.FavoriteModel> getFavorites() {
            return new ArrayList<>(favorites);
        }

        void addFavorite(Service.FavoriteModel favorite) {
            favorites.add(favorite);
            fireTableRowsInserted(favorites.size() - 1, favorites.size() - 1);
        }

        void removeFavorite(int rowIndex) {
            if (favorites.size() <= 1) {
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
                logger.error(
                        "Error setting value at ({}, {}) to {}",
                        rowIndex,
                        columnIndex,
                        aValue,
                        e);
                return;
            }
            if (!newFavorite.equals(oldFavorite)) {
                favorites.set(rowIndex, newFavorite);
                fireTableCellUpdated(rowIndex, columnIndex);
                if (columnIndex == 1) {
                    fireTableCellUpdated(rowIndex, 2);
                    fireTableCellUpdated(rowIndex, 3);
                }
            }
        }
    }

    private static class ReasoningCellRenderer extends DefaultTableCellRenderer {
        private final AbstractService service;

        ReasoningCellRenderer(AbstractService service) {
            this.service = service;
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label =
                    (JLabel) super.getTableCellRendererComponent(
                            table, value, isSelected, hasFocus, row, column);
            int modelRow = table.convertRowIndexToModel(row);
            String modelName =
                    (String) table.getModel().getValueAt(modelRow, 1);
            if (modelName != null && !service.supportsReasoningEffort(modelName)) {
                label.setText("Off");
                label.setEnabled(false);
                label.setToolTipText(
                        "Reasoning effort not supported by " + modelName);
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
                        label.isEnabled()
                                ? table.getForeground()
                                : UIManager.getColor("Label.disabledForeground"));
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

    private static class ReasoningCellEditor extends javax.swing.DefaultCellEditor {
        private final AbstractService service;
        private final JTable table;
        private final JComboBox<Service.ReasoningLevel> comboBox;

        ReasoningCellEditor(
                JComboBox<Service.ReasoningLevel> comboBox,
                AbstractService service,
                JTable table) {
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
            String modelName =
                    (String) table.getModel().getValueAt(modelRow, 1);
            boolean supports = modelName != null
                    && service.supportsReasoningEffort(modelName);
            Component editorComponent =
                    super.getTableCellEditorComponent(
                            table, value, isSelected, row, column);
            editorComponent.setEnabled(supports);
            comboBox.setEnabled(supports);
            if (!supports) {
                comboBox.setSelectedItem(Service.ReasoningLevel.DEFAULT);
                comboBox.setToolTipText(
                        "Reasoning effort not supported by " + modelName);
                comboBox.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> list,
                            @Nullable Object val,
                            int i,
                            boolean sel,
                            boolean foc) {
                        JLabel label =
                                (JLabel) super.getListCellRendererComponent(
                                        list, val, i, sel, foc);
                        if (i == -1) {
                            label.setText("Off");
                            label.setForeground(
                                    UIManager.getColor(
                                            "ComboBox.disabledForeground"));
                        } else if (val instanceof Service.ReasoningLevel lvl) {
                            label.setText(lvl.toString());
                        } else {
                            label.setText(val == null ? "" : val.toString());
                        }
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
                String modelName =
                        (String) table.getModel().getValueAt(modelRow, 1);
                return modelName != null
                        && service.supportsReasoningEffort(modelName);
            }
            return super.isCellEditable(anEvent);
        }

        @Override
        public Object getCellEditorValue() {
            return comboBox.isEnabled()
                    ? super.getCellEditorValue()
                    : Service.ReasoningLevel.DEFAULT;
        }
    }

    private static class ProcessingTierCellRenderer extends DefaultTableCellRenderer {
        private final AbstractService service;

        ProcessingTierCellRenderer(AbstractService service) {
            this.service = service;
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label =
                    (JLabel) super.getTableCellRendererComponent(
                            table, value, isSelected, hasFocus, row, column);
            int modelRow = table.convertRowIndexToModel(row);
            String modelName =
                    (String) table.getModel().getValueAt(modelRow, 1);
            if (modelName != null && !service.supportsProcessingTier(modelName)) {
                label.setText("Off");
                label.setEnabled(false);
                label.setToolTipText(
                        "Processing tiers not supported by " + modelName);
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
                        label.isEnabled()
                                ? table.getForeground()
                                : UIManager.getColor("Label.disabledForeground"));
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

    private static class ProcessingTierCellEditor extends javax.swing.DefaultCellEditor {
        private final AbstractService service;
        private final JTable table;
        private final JComboBox<Service.ProcessingTier> comboBox;

        ProcessingTierCellEditor(
                JComboBox<Service.ProcessingTier> comboBox,
                AbstractService service,
                JTable table) {
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
            String modelName =
                    (String) table.getModel().getValueAt(modelRow, 1);
            boolean supports = modelName != null
                    && service.supportsProcessingTier(modelName);
            Component editorComponent =
                    super.getTableCellEditorComponent(
                            table, value, isSelected, row, column);
            editorComponent.setEnabled(supports);
            comboBox.setEnabled(supports);
            if (!supports) {
                comboBox.setSelectedItem(Service.ProcessingTier.DEFAULT);
                comboBox.setToolTipText(
                        "Processing tiers not supported by " + modelName);
                comboBox.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> list,
                            @Nullable Object val,
                            int i,
                            boolean sel,
                            boolean foc) {
                        JLabel label =
                                (JLabel) super.getListCellRendererComponent(
                                        list, val, i, sel, foc);
                        if (i == -1) {
                            label.setText("Off");
                            label.setForeground(
                                    UIManager.getColor(
                                            "ComboBox.disabledForeground"));
                        } else if (val instanceof Service.ProcessingTier p) {
                            label.setText(p.toString());
                        } else {
                            label.setText(val == null ? "" : val.toString());
                        }
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
                String modelName =
                        (String) table.getModel().getValueAt(modelRow, 1);
                return modelName != null
                        && service.supportsProcessingTier(modelName);
            }
            return super.isCellEditable(anEvent);
        }

        @Override
        public Object getCellEditorValue() {
            return comboBox.isEnabled()
                    ? super.getCellEditorValue()
                    : Service.ProcessingTier.DEFAULT;
        }
    }
}
