package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.components.BrowserLabel;
import io.github.jbellis.brokk.util.Environment;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class SettingsGlobalPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(SettingsGlobalPanel.class);
    public static final String MODELS_TAB_TITLE = "Favorite Models"; // Used for targeting this tab

    private final Chrome chrome;
    private final SettingsDialog parentDialog; // To access project for data retention refresh

    // UI Components managed by this panel
    private JTextField brokkKeyField = new JTextField();

    @Nullable
    private JRadioButton brokkProxyRadio; // Can be null if STAGING

    @Nullable
    private JRadioButton localhostProxyRadio; // Can be null if STAGING

    private JRadioButton lightThemeRadio = new JRadioButton("Light");
    private JRadioButton darkThemeRadio = new JRadioButton("Dark");
    private JCheckBox wordWrapCheckbox = new JCheckBox("Enable word wrap");
    private JTable quickModelsTable = new JTable();
    private FavoriteModelsTableModel quickModelsTableModel = new FavoriteModelsTableModel(new ArrayList<>());
    private JTextField balanceField = new JTextField();
    private BrowserLabel signupLabel = new BrowserLabel("", ""); // Initialized with dummy values

    @Nullable
    private JTextField gitHubTokenField; // Null if GitHub tab not shown

    @Nullable
    private JRadioButton uiScaleAutoRadio; // Hidden on macOS

    @Nullable
    private JRadioButton uiScaleCustomRadio; // Hidden on macOS

    @Nullable
    private JComboBox<String> uiScaleCombo; // Hidden on macOS

    // JVM memory settings controls (General tab)
    private JRadioButton memoryAutoRadio = new JRadioButton("Automatic (recommended)");
    private JRadioButton memoryManualRadio = new JRadioButton("Manual:");
    private JSpinner memorySpinner = new JSpinner();

    private JSpinner terminalFontSizeSpinner = new JSpinner();

    private JTabbedPane globalSubTabbedPane = new JTabbedPane(JTabbedPane.TOP);

    public SettingsGlobalPanel(Chrome chrome, SettingsDialog parentDialog) {
        this.chrome = chrome;
        this.parentDialog = parentDialog;
        setLayout(new BorderLayout());
        initComponents(); // This will fully initialize or conditionally initialize fields
        loadSettings();
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

        // Quick Models Tab
        var quickModelsPanel = createQuickModelsPanel();
        globalSubTabbedPane.addTab(MODELS_TAB_TITLE, null, quickModelsPanel, "Define model aliases (shortcuts)");

        // GitHub Tab (conditionally added)
        var project = chrome.getProject();
        boolean shouldShowGitHubTab = project.isGitHubRepo();

        if (shouldShowGitHubTab) {
            var gitHubPanel = createGitHubPanel();
            globalSubTabbedPane.addTab(
                    SettingsDialog.GITHUB_SETTINGS_TAB_NAME, null, gitHubPanel, "GitHub integration settings");
        }

        add(globalSubTabbedPane, BorderLayout.CENTER);
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

        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        servicePanel.add(Box.createVerticalGlue(), gbc);

        return servicePanel;
    }

    private JPanel createGitHubPanel() {
        var gitHubPanel = new JPanel(new GridBagLayout());
        gitHubPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gitHubPanel.add(new JLabel("GitHub Token:"), gbc);

        gitHubTokenField = new JTextField(20);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gitHubPanel.add(gitHubTokenField, gbc);

        var explanationLabel = new JLabel(
                "<html>This token is used to access GitHub APIs. It should have read and write access to Pull Requests and Issues.</html>");
        explanationLabel.setFont(explanationLabel
                .getFont()
                .deriveFont(Font.ITALIC, explanationLabel.getFont().getSize() * 0.9f));
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(2, 5, 8, 5);
        gitHubPanel.add(explanationLabel, gbc);
        gbc.insets = new Insets(2, 5, 2, 5);

        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        gitHubPanel.add(Box.createVerticalGlue(), gbc);

        return gitHubPanel;
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
        darkThemeRadio = new JRadioButton("Dark");
        var themeGroup = new ButtonGroup();
        themeGroup.add(lightThemeRadio);
        themeGroup.add(darkThemeRadio);

        lightThemeRadio.putClientProperty("theme", false); // Custom property for easy identification
        darkThemeRadio.putClientProperty("theme", true);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        appearancePanel.add(lightThemeRadio, gbc);

        gbc.gridy = row++;
        appearancePanel.add(darkThemeRadio, gbc);

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

        gbc.insets = new Insets(2, 5, 2, 5); // reset spacing

        // UI Scale controls (hidden on macOS)
        if (!Environment.isMacOs()) {
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

    private JPanel createQuickModelsPanel() {
        var panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var models = chrome.getContextManager().getService();
        var availableModelNames =
                models.getAvailableModels().keySet().stream().sorted().toArray(String[]::new);
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
        reasoningColumn.setCellEditor(new ReasoningCellEditor(reasoningComboBoxEditor, models, quickModelsTable));
        reasoningColumn.setCellRenderer(new ReasoningCellRenderer(models));
        reasoningColumn.setPreferredWidth(100);

        if (showServiceTiers) {
            TableColumn processingColumn = quickModelsTable.getColumnModel().getColumn(3);
            var processingComboBoxEditor = new JComboBox<>(Service.ProcessingTier.values());
            processingColumn.setCellEditor(
                    new ProcessingTierCellEditor(processingComboBoxEditor, models, quickModelsTable));
            processingColumn.setCellRenderer(new ProcessingTierCellRenderer(models));
            processingColumn.setPreferredWidth(120);
        } else {
            // Remove the Processing Tier column from the view when service tiers are disabled
            quickModelsTable.removeColumn(quickModelsTable.getColumnModel().getColumn(3));
        }

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var addButton = new JButton("Add");
        var removeButton = new JButton("Remove");
        buttonPanel.add(addButton);
        buttonPanel.add(removeButton);

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
            int viewRow = quickModelsTable.getSelectedRow();
            if (viewRow != -1) {
                if (quickModelsTable.isEditing()) {
                    quickModelsTable.getCellEditor().stopCellEditing();
                }
                int modelRow = quickModelsTable.convertRowIndexToModel(viewRow);
                quickModelsTableModel.removeFavorite(modelRow);
            }
        });

        panel.add(new JScrollPane(quickModelsTable), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        return panel;
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

    public void loadSettings() {
        // General Tab - JVM Memory
        try {
            var mem = MainProject.getJvmMemorySettings();
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

        // Service Tab
        brokkKeyField.setText(MainProject.getBrokkKey());
        refreshBalanceDisplay();
        updateSignupLabelVisibility();
        if (brokkProxyRadio != null
                && localhostProxyRadio != null) { // STAGING check in createServicePanel handles this
            if (MainProject.getProxySetting() == MainProject.LlmProxySetting.BROKK) {
                brokkProxyRadio.setSelected(true);
            } else {
                localhostProxyRadio.setSelected(true);
            }
        }

        // Appearance Tab
        if (MainProject.getTheme().equals("dark")) {
            darkThemeRadio.setSelected(true);
        } else {
            lightThemeRadio.setSelected(true);
        }

        // Code Block Layout
        wordWrapCheckbox.setSelected(MainProject.getCodeBlockWrapMode());

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

        // Quick Models Tab
        quickModelsTableModel.setFavorites(MainProject.loadFavoriteModels());

        // GitHub Tab
        if (gitHubTokenField != null) { // Only if panel was created
            gitHubTokenField.setText(MainProject.getGitHubToken());
        }
    }

    public boolean applySettings() {
        // Service Tab
        String currentBrokkKeyInSettings = MainProject.getBrokkKey();
        String newBrokkKeyFromField = brokkKeyField.getText().trim();
        boolean keyStateChangedInUI = !newBrokkKeyFromField.equals(currentBrokkKeyInSettings);

        if (keyStateChangedInUI) {
            if (!newBrokkKeyFromField.isEmpty()) {
                try {
                    Service.validateKey(newBrokkKeyFromField);
                    MainProject.setBrokkKey(newBrokkKeyFromField);
                    refreshBalanceDisplay();
                    updateSignupLabelVisibility();
                    parentDialog.triggerDataRetentionPolicyRefresh(); // Key change might affect org policy
                } catch (IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(this, "Invalid Brokk Key", "Invalid Key", JOptionPane.ERROR_MESSAGE);
                    return false;
                } catch (IOException ex) { // Network error, but allow saving
                    JOptionPane.showMessageDialog(
                            this,
                            "Network error: " + ex.getMessage() + ". Key saved, but validation failed.",
                            "Network Error",
                            JOptionPane.WARNING_MESSAGE);
                    MainProject.setBrokkKey(newBrokkKeyFromField);
                    refreshBalanceDisplay();
                    updateSignupLabelVisibility();
                    parentDialog.triggerDataRetentionPolicyRefresh();
                }
            } else { // newBrokkKeyFromField is empty
                MainProject.setBrokkKey(newBrokkKeyFromField);
                refreshBalanceDisplay();
                updateSignupLabelVisibility();
                parentDialog.triggerDataRetentionPolicyRefresh();
            }
        }

        if (brokkProxyRadio != null && localhostProxyRadio != null) { // Not STAGING
            MainProject.LlmProxySetting proxySetting = brokkProxyRadio.isSelected()
                    ? MainProject.LlmProxySetting.BROKK
                    : MainProject.LlmProxySetting.LOCALHOST;
            if (proxySetting != MainProject.getProxySetting()) {
                MainProject.setLlmProxySetting(proxySetting);
                logger.debug("Applied LLM Proxy Setting: {}", proxySetting);
                // Consider notifying user about restart if changed. Dialog does this.
            }
        }

        // General Tab - JVM Memory
        try {
            boolean automatic = memoryAutoRadio.isSelected();
            int mb = ((Number) memorySpinner.getValue()).intValue();
            MainProject.setJvmMemorySettings(new MainProject.JvmMemorySettings(automatic, mb));
            logger.debug(
                    "Applied JVM memory settings: mode={}, mb={}",
                    automatic ? "auto" : "manual",
                    automatic ? "n/a" : mb);
        } catch (Exception e) {
            logger.warn("Failed to persist JVM memory settings", e);
        }

        // Appearance Tab
        boolean newIsDark = darkThemeRadio.isSelected();
        boolean newWrapMode = wordWrapCheckbox.isSelected();
        String newTheme = newIsDark ? "dark" : "light";
        boolean currentWrapMode = MainProject.getCodeBlockWrapMode();

        // Check if either theme or wrap mode changed
        boolean themeChanged = !newTheme.equals(MainProject.getTheme());
        boolean wrapChanged = newWrapMode != currentWrapMode;

        if (themeChanged || wrapChanged) {
            // Save wrap mode setting globally
            if (wrapChanged) {
                MainProject.setCodeBlockWrapMode(newWrapMode);
                logger.debug("Applied Code Block Wrap Mode: {}", newWrapMode);
            }

            // Apply theme and wrap mode changes via unified Chrome method
            if (themeChanged || wrapChanged) {
                chrome.switchThemeAndWrapMode(newIsDark, newWrapMode);
                logger.debug("Applied Theme: {} and Wrap Mode: {}", newTheme, newWrapMode);
            }
        }

        // UI Scale preference (if present; hidden on macOS)
        if (uiScaleAutoRadio != null && uiScaleCustomRadio != null && uiScaleCombo != null) {
            String before = MainProject.getUiScalePref();
            if (uiScaleAutoRadio.isSelected()) {
                if (!"auto".equalsIgnoreCase(before)) {
                    MainProject.setUiScalePrefAuto();
                    parentDialog.markRestartNeededForUiScale();
                    logger.debug("Applied UI scale preference: auto");
                }
            } else {
                String txt = String.valueOf(uiScaleCombo.getSelectedItem()).trim();
                var allowed = java.util.Set.of("1.0", "2.0", "3.0", "4.0", "5.0");
                if (!allowed.contains(txt)) {
                    JOptionPane.showMessageDialog(
                            this,
                            "Select a scale from 1.0, 2.0, 3.0, 4.0, or 5.0.",
                            "Invalid UI Scale",
                            JOptionPane.ERROR_MESSAGE);
                    return false;
                }
                if (!txt.equals(before)) {
                    double v = Double.parseDouble(txt);
                    MainProject.setUiScalePrefCustom(v);
                    parentDialog.markRestartNeededForUiScale();
                    logger.debug("Applied UI scale preference: {}", v);
                }
            }
        }

        // Terminal font size
        float newTerminalFontSize = ((Double) terminalFontSizeSpinner.getValue()).floatValue();
        if (newTerminalFontSize != MainProject.getTerminalFontSize()) {
            MainProject.setTerminalFontSize(newTerminalFontSize);
            chrome.updateTerminalFontSize();
            logger.debug("Applied Terminal Font Size: {}", newTerminalFontSize);
        }

        // Quick Models Tab
        if (quickModelsTable.isEditing()) {
            quickModelsTable.getCellEditor().stopCellEditing();
        }
        MainProject.saveFavoriteModels(quickModelsTableModel.getFavorites());
        // chrome.getQuickContextActions().reloadFavoriteModels(); // Commented out due to missing method in Chrome

        // GitHub Tab
        if (gitHubTokenField != null) {
            String newToken = gitHubTokenField.getText().trim();
            if (!newToken.equals(MainProject.getGitHubToken())) {
                MainProject.setGitHubToken(newToken);
                GitHubAuth.invalidateInstance();
                logger.debug("Applied GitHub Token");
            }
        }

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
        private final Service models;

        public ReasoningCellRenderer(Service service) {
            this.models = service;
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label =
                    (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            int modelRow = table.convertRowIndexToModel(row);
            String modelName = (String) table.getModel().getValueAt(modelRow, 1);
            if (modelName != null && !models.supportsReasoningEffort(modelName)) {
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
        private final Service models;
        private final JTable table;
        private final JComboBox<Service.ReasoningLevel> comboBox;

        public ReasoningCellEditor(JComboBox<Service.ReasoningLevel> comboBox, Service service, JTable table) {
            super(comboBox);
            this.comboBox = comboBox;
            this.models = service;
            this.table = table;
            setClickCountToStart(1);
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table, Object value, boolean isSelected, int row, int column) {
            int modelRow = table.convertRowIndexToModel(row);
            String modelName = (String) table.getModel().getValueAt(modelRow, 1);
            boolean supportsReasoning = modelName != null && models.supportsReasoningEffort(modelName);
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
        public boolean isCellEditable(java.util.EventObject anEvent) {
            int editingRow = table.getEditingRow();
            if (editingRow != -1) {
                int modelRow = table.convertRowIndexToModel(editingRow);
                String modelName = (String) table.getModel().getValueAt(modelRow, 1);
                return modelName != null && models.supportsReasoningEffort(modelName);
            }
            return super.isCellEditable(anEvent);
        }

        @Override
        public Object getCellEditorValue() {
            return comboBox.isEnabled() ? super.getCellEditorValue() : Service.ReasoningLevel.DEFAULT;
        }
    }

    private static class ProcessingTierCellRenderer extends DefaultTableCellRenderer {
        private final Service models;

        public ProcessingTierCellRenderer(Service service) {
            this.models = service;
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label =
                    (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            int modelRow = table.convertRowIndexToModel(row);
            String modelName = (String) table.getModel().getValueAt(modelRow, 1);
            if (modelName != null && !models.supportsProcessingTier(modelName)) {
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
        private final Service models;
        private final JTable table;
        private final JComboBox<Service.ProcessingTier> comboBox;

        public ProcessingTierCellEditor(JComboBox<Service.ProcessingTier> comboBox, Service service, JTable table) {
            super(comboBox);
            this.comboBox = comboBox;
            this.models = service;
            this.table = table;
            setClickCountToStart(1);
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table, Object value, boolean isSelected, int row, int column) {
            int modelRow = table.convertRowIndexToModel(row);
            String modelName = (String) table.getModel().getValueAt(modelRow, 1);
            boolean supports = modelName != null && models.supportsProcessingTier(modelName);
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
        public boolean isCellEditable(java.util.EventObject anEvent) {
            int editingRow = table.getEditingRow();
            if (editingRow != -1) {
                int modelRow = table.convertRowIndexToModel(editingRow);
                String modelName = (String) table.getModel().getValueAt(modelRow, 1);
                return modelName != null && models.supportsProcessingTier(modelName);
            }
            return super.isCellEditable(anEvent);
        }

        @Override
        public Object getCellEditorValue() {
            return comboBox.isEnabled() ? super.getCellEditorValue() : Service.ProcessingTier.DEFAULT;
        }
    }
}
