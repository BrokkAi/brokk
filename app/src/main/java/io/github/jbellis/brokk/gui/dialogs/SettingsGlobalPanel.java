package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.components.BrowserLabel;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.mcp.McpConfig;
import io.github.jbellis.brokk.mcp.McpServer;
import io.github.jbellis.brokk.mcp.McpUtils;
import java.awt.*;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.*;
import javax.swing.event.DocumentListener;
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
    private JTable quickModelsTable = new JTable();
    private FavoriteModelsTableModel quickModelsTableModel = new FavoriteModelsTableModel(new ArrayList<>());
    private JTextField balanceField = new JTextField();
    private BrowserLabel signupLabel = new BrowserLabel("", ""); // Initialized with dummy values

    @Nullable
    private JTextField gitHubTokenField; // Null if GitHub tab not shown

    private DefaultListModel<McpServer> mcpServersListModel = new DefaultListModel<>();
    private JList<McpServer> mcpServersList = new JList<>(mcpServersListModel);

    @Nullable
    private JRadioButton uiScaleAutoRadio; // Hidden on macOS

    @Nullable
    private JRadioButton uiScaleCustomRadio; // Hidden on macOS

    @Nullable
    private JComboBox<String> uiScaleCombo; // Hidden on macOS

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

        // MCP Servers Tab
        var mcpPanel = createMcpPanel();
        globalSubTabbedPane.addTab("MCP Servers", null, mcpPanel, "MCP server configuration");

        add(globalSubTabbedPane, BorderLayout.CENTER);
    }

    public JTabbedPane getGlobalSubTabbedPane() {
        return globalSubTabbedPane;
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

        // Quick Models Tab
        quickModelsTableModel.setFavorites(MainProject.loadFavoriteModels());

        // GitHub Tab
        if (gitHubTokenField != null) { // Only if panel was created
            gitHubTokenField.setText(MainProject.getGitHubToken());
        }

        // MCP Servers Tab
        mcpServersListModel.clear();
        var mcpConfig = chrome.getProject().getMcpConfig();
        for (McpServer server : mcpConfig.servers()) {
            mcpServersListModel.addElement(server);
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

        // Appearance Tab
        boolean newIsDark = darkThemeRadio.isSelected();
        String newTheme = newIsDark ? "dark" : "light";
        if (!newTheme.equals(MainProject.getTheme())) {
            chrome.switchTheme(newIsDark);
            logger.debug("Applied Theme: {}", newTheme);
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

        // MCP Servers Tab
        var servers = new ArrayList<McpServer>();
        for (int i = 0; i < mcpServersListModel.getSize(); i++) {
            servers.add(mcpServersListModel.getElementAt(i));
        }
        var newMcpConfig = new McpConfig(servers);
        chrome.getProject().setMcpConfig(newMcpConfig);

        return true;
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        SwingUtilities.updateComponentTreeUI(this);
    }

    private JLabel createMcpServerUrlErrorLabel() {
        var urlErrorLabel = new JLabel("Invalid URL");
        var errorColor = UIManager.getColor("Label.errorForeground");
        // A fallback to a softer, brownish-red if not defined in the theme
        if (errorColor == null) {
            errorColor = new Color(219, 49, 49);
        }
        urlErrorLabel.setForeground(errorColor);
        urlErrorLabel.setVisible(false);
        return urlErrorLabel;
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
                if (value instanceof McpServer server) {
                    setText(server.name() + " (" + server.url() + ")");
                }
                return this;
            }
        });
        var scrollPane = new JScrollPane(mcpServersList);
        mcpPanel.add(scrollPane, BorderLayout.CENTER);

        // Buttons
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var addButton = new JButton("Add...");
        var editButton = new JButton("Edit...");
        var removeButton = new JButton("Remove");

        // Enable and wire up action listeners for MCP server management.
        addButton.setEnabled(true);
        editButton.setEnabled(false);
        removeButton.setEnabled(false);

        // Enable Edit/Remove when a server is selected
        mcpServersList.addListSelectionListener(e -> {
            boolean hasSelection = !mcpServersList.isSelectionEmpty();
            editButton.setEnabled(hasSelection);
            removeButton.setEnabled(hasSelection);
        });

        // Add new MCP server (name + url). Tools can be fetched later.
        addButton.addActionListener(e -> showMcpServerDialog("Add MCP Server", null, server -> {
            mcpServersListModel.addElement(server);
            mcpServersList.setSelectedValue(server, true);
        }));

        // Edit selected MCP server
        editButton.addActionListener(e -> {
            int idx = mcpServersList.getSelectedIndex();
            if (idx < 0) return;
            McpServer existing = mcpServersListModel.getElementAt(idx);
            showMcpServerDialog("Edit MCP Server", existing, updated -> {
                mcpServersListModel.setElementAt(updated, idx);
                mcpServersList.setSelectedIndex(idx);
            });
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

        buttonPanel.add(addButton);
        buttonPanel.add(editButton);
        buttonPanel.add(removeButton);
        mcpPanel.add(buttonPanel, BorderLayout.SOUTH);

        return mcpPanel;
    }

    private void showMcpServerDialog(
            String title, @Nullable McpServer existing, java.util.function.Consumer<McpServer> onSave) {
        final var fetchedTools = new Object() {
            @Nullable
            List<String> value = existing != null ? existing.tools() : null;
        };
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
                java.awt.Window w = SwingUtilities.getWindowAncestor(tokenPanel);
                if (w != null) w.pack();
                tokenPanel.revalidate();
                tokenPanel.repaint();
            });
        });

        var fetchToolsButton = new JButton("Fetch Tools");
        var toolsTextArea = new JTextArea(5, 20);
        toolsTextArea.setEditable(false);
        var toolsScrollPane = new JScrollPane(toolsTextArea);
        toolsScrollPane.setVisible(false);

        if (fetchedTools.value != null && !fetchedTools.value.isEmpty()) {
            toolsTextArea.setText(String.join("\n", fetchedTools.value));
            toolsScrollPane.setVisible(true);
        }

        fetchToolsButton.addActionListener(e -> {
            String rawUrl = urlField.getText().trim();
            if (rawUrl.isEmpty()) {
                JOptionPane.showMessageDialog(fetchToolsButton, "URL is required.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            URL url;
            try {
                url = new URI(rawUrl).toURL();
            } catch (MalformedURLException | URISyntaxException ex) {
                JOptionPane.showMessageDialog(fetchToolsButton, "Invalid URL.", "Error", JOptionPane.ERROR_MESSAGE);
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

            fetchToolsButton.setEnabled(false);
            fetchToolsButton.setText("Fetching...");

            new SwingWorker<List<String>, Void>() {
                @Override
                protected List<String> doInBackground() {
                    return McpUtils.fetchTools(url, finalBearerToken);
                }

                @Override
                protected void done() {
                    try {
                        List<String> tools = get();
                        fetchedTools.value = tools;
                        if (tools.isEmpty()) {
                            toolsTextArea.setText("No tools found or failed to fetch.");
                        } else {
                            toolsTextArea.setText(String.join("\n", tools));
                        }
                        toolsScrollPane.setVisible(true);
                        SwingUtilities.getWindowAncestor(fetchToolsButton).pack();
                    } catch (Exception ex) {
                        logger.error("Error fetching MCP tools", ex);
                        fetchedTools.value = null;
                        toolsTextArea.setText("Error fetching tools: " + ex.getMessage());
                        toolsScrollPane.setVisible(true);
                        SwingUtilities.getWindowAncestor(fetchToolsButton).pack();
                    } finally {
                        fetchToolsButton.setEnabled(true);
                        fetchToolsButton.setText("Fetch Tools");
                    }
                }
            }.execute();
        });

        JLabel urlErrorLabel = createMcpServerUrlErrorLabel();
        urlField.getDocument().addDocumentListener(createUrlValidationListener(urlField, urlErrorLabel));

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

        // Row 1: URL
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("URL:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        panel.add(urlField, gbc);

        // Row 2: URL Error
        gbc.gridx = 1;
        gbc.gridy = 2;
        panel.add(urlErrorLabel, gbc);

        // Row 3: Token checkbox
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        panel.add(useTokenCheckbox, gbc);
        gbc.gridwidth = 1;

        // Row 4: Token
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(tokenLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        panel.add(tokenPanel, gbc);

        // Row 5: Fetch Tools Button
        gbc.gridx = 1;
        gbc.gridy = 5;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.WEST;
        panel.add(fetchToolsButton, gbc);

        // Row 6: Tools Pane
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.insets = new Insets(8, 0, 0, 0);
        panel.add(toolsScrollPane, gbc);

        var optionPane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        final var dialog = optionPane.createDialog(SettingsGlobalPanel.this, title);

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
                McpServer newServer = createMcpServerFromInputs(name, rawUrl, useToken, tokenField, fetchedTools.value);

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

        dialog.setVisible(true);
    }

    /**
     * Shared helper: create a debounced URL DocumentListener that validates the URL and toggles the provided error
     * label. Debounce interval is 500ms.
     */
    private DocumentListener createUrlValidationListener(JTextField urlField, JLabel urlErrorLabel) {
        final javax.swing.Timer[] debounceTimer = new javax.swing.Timer[1];
        return new DocumentListener() {
            private void scheduleValidation() {
                if (debounceTimer[0] != null && debounceTimer[0].isRunning()) {
                    debounceTimer[0].stop();
                }
                debounceTimer[0] = new javax.swing.Timer(500, ev -> {
                    String text = urlField.getText().trim();
                    boolean ok = true;
                    if (text.isEmpty()) {
                        ok = false;
                    } else {
                        try {
                            URL u = new URI(text).toURL();
                            String host = u.getHost();
                            if (host == null || host.isEmpty()) ok = false;
                        } catch (URISyntaxException | MalformedURLException ex) {
                            ok = false;
                        }
                    }
                    urlErrorLabel.setVisible(!ok);
                    // Repack containing dialog/window so visibility change is applied
                    SwingUtilities.invokeLater(() -> {
                        java.awt.Window w = SwingUtilities.getWindowAncestor(urlField);
                        if (w != null) w.pack();
                    });
                });
                debounceTimer[0].setRepeats(false);
                debounceTimer[0].start();
            }

            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                scheduleValidation();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                scheduleValidation();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
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
    private @Nullable McpServer createMcpServerFromInputs(
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

        return new McpServer(name, url, tools, token);
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
