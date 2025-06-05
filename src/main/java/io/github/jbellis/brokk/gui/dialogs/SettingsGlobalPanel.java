package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.components.BrowserLabel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseMotionListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SettingsGlobalPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(SettingsGlobalPanel.class);
    public static final String MODELS_TAB_TITLE = "Models"; // Used for targeting this tab

    private final Chrome chrome;
    private final SettingsDialog parentDialog; // To access project for data retention refresh

    // UI Components managed by this panel
    private JTextField brokkKeyField;
    private JRadioButton brokkProxyRadio;
    private JRadioButton localhostProxyRadio;
    private JComboBox<String> architectModelComboBox;
    private JComboBox<Service.ReasoningLevel> architectReasoningComboBox;
    private JComboBox<String> codeModelComboBox;
    private JComboBox<Service.ReasoningLevel> codeReasoningComboBox;
    private JComboBox<String> askModelComboBox;
    private JComboBox<Service.ReasoningLevel> askReasoningComboBox;
    private JComboBox<String> searchModelComboBox;
    private JComboBox<Service.ReasoningLevel> searchReasoningComboBox;
    private JRadioButton lightThemeRadio;
    private JRadioButton darkThemeRadio;
    private JTable quickModelsTable;
    private FavoriteModelsTableModel quickModelsTableModel;
    private JTextField balanceField;
    private BrowserLabel signupLabel;
    private JTextField gitHubTokenField;
    private JTabbedPane globalSubTabbedPane;
    private JPanel defaultModelsPanel;


    public SettingsGlobalPanel(Chrome chrome, SettingsDialog parentDialog) {
        this.chrome = chrome;
        this.parentDialog = parentDialog;
        setLayout(new BorderLayout());
        initComponents();
        loadSettings();
    }

    private void initComponents() {
        globalSubTabbedPane = new JTabbedPane(JTabbedPane.TOP);

        // Service Tab
        var servicePanel = createServicePanel();
        globalSubTabbedPane.addTab("Service", null, servicePanel, "Service configuration");

        // Appearance Tab
        var appearancePanel = createAppearancePanel();
        globalSubTabbedPane.addTab("Appearance", null, appearancePanel, "Theme settings");

        // Default Models Tab
        defaultModelsPanel = createModelsPanel(chrome.getProject()); // Store reference
        globalSubTabbedPane.addTab(MODELS_TAB_TITLE, null, defaultModelsPanel, "Default model selection and configuration");

        // Quick Models Tab
        var quickModelsPanel = createQuickModelsPanel();
        globalSubTabbedPane.addTab("Quick Models", null, quickModelsPanel, "Define model aliases (shortcuts)");

        // GitHub Tab (conditionally added)
        var project = chrome.getProject();
        if (project != null && project.isGitHubRepo()) {
            var gitHubPanel = createGitHubPanel();
            globalSubTabbedPane.addTab("GitHub", null, gitHubPanel, "GitHub integration settings");
        }

        // Initial enablement of Models tab content
        updateModelsPanelEnablement();

        add(globalSubTabbedPane, BorderLayout.CENTER);
    }

    public JTabbedPane getGlobalSubTabbedPane() {
        return globalSubTabbedPane;
    }
    
    public void updateModelsPanelEnablement() {
        boolean projectIsOpen = (chrome.getProject() != null);
        defaultModelsPanel.setEnabled(projectIsOpen);
        setEnabledRecursive(defaultModelsPanel, projectIsOpen);

        // Special handling for JComboBox renderers if they show "Off" when disabled
        var service = chrome.getContextManager().getService();
        if (architectReasoningComboBox != null) updateReasoningComboBox(architectModelComboBox, architectReasoningComboBox, service);
        if (codeReasoningComboBox != null) updateReasoningComboBox(codeModelComboBox, codeReasoningComboBox, service);
        if (askReasoningComboBox != null) updateReasoningComboBox(askModelComboBox, askReasoningComboBox, service);
        if (searchReasoningComboBox != null) updateReasoningComboBox(searchModelComboBox, searchReasoningComboBox, service);
    }

    private void setEnabledRecursive(Container container, boolean enabled) {
        for (Component c : container.getComponents()) {
            c.setEnabled(enabled);
            if (c instanceof Container) {
                setEnabledRecursive((Container) c, enabled);
            }
        }
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

        if (Project.getProxySetting() == Project.LlmProxySetting.STAGING) {
            var proxyInfoLabel = new JLabel("Proxy has been set to STAGING in ~/.brokk/brokk.properties. Changing it back must be done in the same place.");
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

        var explanationLabel = new JLabel("<html>This token is used to access GitHub APIs. It should have read access to Pull Requests and Issues.</html>");
        explanationLabel.setFont(explanationLabel.getFont().deriveFont(Font.ITALIC, explanationLabel.getFont().getSize() * 0.9f));
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
        if (this.signupLabel == null) {
            logger.warn("signupLabel is null, cannot update visibility.");
            return;
        }
        String currentPersistedKey = Project.getBrokkKey(); // Read from persistent store
        boolean keyIsEffectivelyPresent = currentPersistedKey != null && !currentPersistedKey.trim().isEmpty();
        this.signupLabel.setVisible(!keyIsEffectivelyPresent);
    }

    private JPanel createAppearancePanel() {
        var appearancePanel = new JPanel(new GridBagLayout());
        appearancePanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

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
        var availableModelNames = models.getAvailableModels().keySet().stream().sorted().toArray(String[]::new);
        var reasoningLevels = Service.ReasoningLevel.values();

        quickModelsTableModel = new FavoriteModelsTableModel(new ArrayList<>()); // Initial empty, loaded in loadSettings
        quickModelsTable = new JTable(quickModelsTableModel);
        quickModelsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        quickModelsTable.setRowHeight(quickModelsTable.getRowHeight() + 4);

        TableColumn aliasColumn = quickModelsTable.getColumnModel().getColumn(0);
        aliasColumn.setPreferredWidth(100);

        TableColumn modelColumn = quickModelsTable.getColumnModel().getColumn(1);
        var modelComboBoxEditor = new JComboBox<>(availableModelNames);
        modelColumn.setCellEditor(new DefaultCellEditor(modelComboBoxEditor));
        modelColumn.setPreferredWidth(200);

        TableColumn reasoningColumn = quickModelsTable.getColumnModel().getColumn(2);
        var reasoningComboBoxEditor = new JComboBox<>(reasoningLevels);
        reasoningColumn.setCellEditor(new ReasoningCellEditor(reasoningComboBoxEditor, models, quickModelsTable));
        reasoningColumn.setCellRenderer(new ReasoningCellRenderer(models, quickModelsTable));
        reasoningColumn.setPreferredWidth(100);

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
            quickModelsTableModel.addFavorite(new Service.FavoriteModel("new-alias", defaultModel, Service.ReasoningLevel.DEFAULT));
            int newRowIndex = quickModelsTableModel.getRowCount() - 1;
            quickModelsTable.setRowSelectionInterval(newRowIndex, newRowIndex);
            quickModelsTable.scrollRectToVisible(quickModelsTable.getCellRect(newRowIndex, 0, true));
            quickModelsTable.editCellAt(newRowIndex, 0);
            Component editorComponent = quickModelsTable.getEditorComponent();
            if (editorComponent != null) {
                editorComponent.requestFocusInWindow();
            }
        });

        removeButton.addActionListener(e -> {
            int selectedRow = quickModelsTable.getSelectedRow();
            if (selectedRow != -1) {
                if (quickModelsTable.isEditing()) {
                    quickModelsTable.getCellEditor().stopCellEditing();
                }
                quickModelsTableModel.removeFavorite(selectedRow);
            }
        });

        panel.add(new JScrollPane(quickModelsTable), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createModelsPanel(Project project) {
        var panel = new JPanel(new GridBagLayout()); // Always create, enable/disable content
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        if (project == null) {
            // Add a placeholder label if no project, actual model controls are added below
            // but will be disabled by updateModelsPanelEnablement if project is null
            var placeholderPanel = new JPanel(new BorderLayout());
            placeholderPanel.add(new JLabel("No project is open. Model settings are project-specific."), BorderLayout.CENTER);
            // Add this placeholder to the main panel, so it's also subject to setEnabledRecursive
            panel.setLayout(new BorderLayout()); // Change layout to accommodate placeholder
            panel.add(placeholderPanel, BorderLayout.CENTER);
            return panel; // Return early, as model controls depend on project
        }
        // If project is not null, proceed to set up the GridBagLayout and controls
        panel.setLayout(new GridBagLayout()); // Reset layout if it was changed for placeholder

        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.EAST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;

        var models = chrome.getContextManager().getService();
        var availableModels = models.getAvailableModels().keySet().stream().sorted().toArray(String[]::new);
        var reasoningLevels = Service.ReasoningLevel.values();

        var modelComboRenderer = new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof String modelName) {
                    label.setToolTipText(generateModelTooltipText(modelName, models));
                } else {
                    label.setToolTipText(null);
                }
                return label;
            }
        };

        Runnable updateReasoningAndTooltipState = () -> {
            updateReasoningComboBox(architectModelComboBox, architectReasoningComboBox, models);
            updateReasoningComboBox(codeModelComboBox, codeReasoningComboBox, models);
            updateReasoningComboBox(askModelComboBox, askReasoningComboBox, models);
            updateReasoningComboBox(searchModelComboBox, searchReasoningComboBox, models);

            if (architectModelComboBox.getSelectedItem() instanceof String modelName)
                architectModelComboBox.setToolTipText(generateModelTooltipText(modelName, models));
            else architectModelComboBox.setToolTipText(null);

            if (codeModelComboBox.getSelectedItem() instanceof String modelName)
                codeModelComboBox.setToolTipText(generateModelTooltipText(modelName, models));
            else codeModelComboBox.setToolTipText(null);

            if (askModelComboBox.getSelectedItem() instanceof String modelName)
                askModelComboBox.setToolTipText(generateModelTooltipText(modelName, models));
            else askModelComboBox.setToolTipText(null);

            if (searchModelComboBox.getSelectedItem() instanceof String modelName)
                searchModelComboBox.setToolTipText(generateModelTooltipText(modelName, models));
            else searchModelComboBox.setToolTipText(null);
        };

        int row = 0;

        architectModelComboBox = new JComboBox<>(availableModels);
        architectModelComboBox.setRenderer(modelComboRenderer);
        architectModelComboBox.addActionListener(e -> updateReasoningAndTooltipState.run());
        addHoverTooltipUpdater(architectModelComboBox, models);
        architectReasoningComboBox = new JComboBox<>(reasoningLevels);
        row = addModelSection(panel, gbc, row, "Architect", architectModelComboBox, architectReasoningComboBox, "The Architect plans and executes multi-step projects, calling other agents and tools as needed");

        codeModelComboBox = new JComboBox<>(availableModels);
        codeModelComboBox.setRenderer(modelComboRenderer);
        codeModelComboBox.addActionListener(e -> updateReasoningAndTooltipState.run());
        addHoverTooltipUpdater(codeModelComboBox, models);
        codeReasoningComboBox = new JComboBox<>(reasoningLevels);
        row = addModelSection(panel, gbc, row, "Code", codeModelComboBox, codeReasoningComboBox, "Used when invoking the Code Agent manually or via Architect");

        askModelComboBox = new JComboBox<>(availableModels);
        askModelComboBox.setRenderer(modelComboRenderer);
        askModelComboBox.addActionListener(e -> updateReasoningAndTooltipState.run());
        addHoverTooltipUpdater(askModelComboBox, models);
        askReasoningComboBox = new JComboBox<>(reasoningLevels);
        row = addModelSection(panel, gbc, row, "Ask", askModelComboBox, askReasoningComboBox, "Answers questions about the current Workspace contents");

        searchModelComboBox = new JComboBox<>(availableModels);
        searchModelComboBox.setRenderer(modelComboRenderer);
        searchModelComboBox.addActionListener(e -> updateReasoningAndTooltipState.run());
        addHoverTooltipUpdater(searchModelComboBox, models);
        searchReasoningComboBox = new JComboBox<>(reasoningLevels);
        addModelSection(panel, gbc, row, "Search", searchModelComboBox, searchReasoningComboBox, "Searches the project for information described in natural language; also used for Deep Scan");

        gbc.gridy = row + 2; // Ensure glue is after the last section
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        panel.add(Box.createVerticalGlue(), gbc);

        SwingUtilities.invokeLater(updateReasoningAndTooltipState);
        return panel;
    }

    private void addHoverTooltipUpdater(JComboBox<String> comboBox, Service service) {
        final String MOUSE_MOTION_LISTENER_KEY = "hoverTooltipMouseMotionListener";
        comboBox.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                JComboBox<?> sourceComboBox = (JComboBox<?>) e.getSource();
                Object popup = sourceComboBox.getUI().getAccessibleChild(sourceComboBox, 0);
                if (popup instanceof BasicComboPopup basicPopup) {
                    JList<?> list = basicPopup.getList();
                    MouseMotionListener existingListener = (MouseMotionListener) list.getClientProperty(MOUSE_MOTION_LISTENER_KEY);
                    if (existingListener != null) list.removeMouseMotionListener(existingListener);

                    MouseMotionListener newListener = new MouseMotionAdapter() {
                        @Override
                        public void mouseMoved(MouseEvent me) {
                            JList<?> mousedList = (JList<?>) me.getSource();
                            int index = mousedList.locationToIndex(me.getPoint());
                            String tooltipText = null;
                            if (index != -1 && mousedList.getModel().getElementAt(index) instanceof String modelName) {
                                tooltipText = generateModelTooltipText(modelName, service);
                            }
                            sourceComboBox.setToolTipText(tooltipText);
                        }
                    };
                    list.addMouseMotionListener(newListener);
                    list.putClientProperty(MOUSE_MOTION_LISTENER_KEY, newListener);
                }
            }

            private void cleanupListenerAndResetTooltip(PopupMenuEvent e) {
                JComboBox<?> sourceComboBox = (JComboBox<?>) e.getSource();
                if (sourceComboBox.getSelectedItem() instanceof String modelName) {
                    sourceComboBox.setToolTipText(generateModelTooltipText(modelName, service));
                } else {
                    sourceComboBox.setToolTipText(null);
                }
                Object popup = sourceComboBox.getUI().getAccessibleChild(sourceComboBox, 0);
                if (popup instanceof BasicComboPopup basicPopup) {
                    JList<?> list = basicPopup.getList();
                    MouseMotionListener listener = (MouseMotionListener) list.getClientProperty(MOUSE_MOTION_LISTENER_KEY);
                    if (listener != null) {
                        list.removeMouseMotionListener(listener);
                        list.putClientProperty(MOUSE_MOTION_LISTENER_KEY, null);
                    }
                }
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { cleanupListenerAndResetTooltip(e); }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { cleanupListenerAndResetTooltip(e); }
        });
    }

    private void refreshBalanceDisplay() {
        if (this.balanceField == null) return;
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

    private String generateModelTooltipText(String modelName, Service service) {
        if (modelName == null || service == null) return null;
        var pricing = service.getModelPricing(modelName);
        if (pricing == null || pricing.bands().isEmpty()) return "Price information not available.";
        if (pricing.bands().stream().allMatch(b -> b.inputCostPerToken() == 0 && b.cachedInputCostPerToken() == 0 && b.outputCostPerToken() == 0)) return "Free";
        if (pricing.bands().size() > 1) {
            return "<html>" + pricing.bands().stream()
                    .map(b -> String.format("%s: $%.2f per 1M in, $%.2f per 1M out", b.getDescription(), b.inputCostPerToken() * 1e6, b.outputCostPerToken() * 1e6))
                    .collect(java.util.stream.Collectors.joining("<br/>")) + "</html>";
        }
        var band = pricing.bands().getFirst();
        return String.format("$%.2f per 1M in, $%.2f per 1M out", band.inputCostPerToken() * 1e6, band.outputCostPerToken() * 1e6);
    }

    private int addModelSection(JPanel panel, GridBagConstraints gbc, int startRow, String typeLabel,
                                JComboBox<String> modelCombo, JComboBox<Service.ReasoningLevel> reasoningCombo, String explanation) {
        var savedInsets = gbc.insets;
        gbc.insets = new Insets(startRow == 0 ? 4 : 14, 4, 4, 4);
        gbc.anchor = GridBagConstraints.EAST;
        gbc.gridx = 0; gbc.gridy = startRow;
        panel.add(new JLabel(typeLabel), gbc);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 1;
        panel.add(modelCombo, gbc);

        gbc.insets = new Insets(4, 4, 2, 4);
        gbc.anchor = GridBagConstraints.EAST;
        gbc.gridx = 0; gbc.gridy = startRow + 1;
        panel.add(new JLabel("Reasoning"), gbc);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 1;
        panel.add(reasoningCombo, gbc);

        var tip = new JTextArea(explanation);
        tip.setEditable(false); tip.setFocusable(false); tip.setLineWrap(true); tip.setWrapStyleWord(true);
        tip.setOpaque(false); tip.setBorder(null);
        tip.setFont(tip.getFont().deriveFont(Font.ITALIC, tip.getFont().getSize() * 0.9f));
        gbc.insets = new Insets(startRow == 0 ? 4 : 14, 10, 2, 4);
        gbc.gridx = 2; gbc.gridy = startRow; gbc.gridheight = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        panel.add(tip, gbc);

        gbc.insets = savedInsets; gbc.gridheight = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        return startRow + 2;
    }

    private void updateReasoningComboBox(JComboBox<String> modelComboBox, JComboBox<Service.ReasoningLevel> reasoningComboBox, Service service) {
        if (modelComboBox == null || reasoningComboBox == null) return;
        String selectedModelName = (String) modelComboBox.getSelectedItem();
        boolean supportsReasoning = selectedModelName != null && service.supportsReasoningEffort(selectedModelName);
        reasoningComboBox.setEnabled(supportsReasoning);
        reasoningComboBox.setToolTipText(supportsReasoning ? "Select reasoning effort" : "Reasoning effort not supported by this model");
        if (!supportsReasoning) {
            reasoningComboBox.setSelectedItem(Service.ReasoningLevel.DEFAULT);
            reasoningComboBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (index == -1 && !reasoningComboBox.isEnabled()) {
                        label.setText("Off");
                        label.setForeground(UIManager.getColor("ComboBox.disabledForeground"));
                    } else if (value instanceof Service.ReasoningLevel level) {
                        label.setText(level.toString());
                    } else {
                        label.setText(value == null ? "" : value.toString());
                    }
                    return label;
                }
            });
        } else {
            reasoningComboBox.setRenderer(new DefaultListCellRenderer());
        }
    }

    public void loadSettings() {
        // Service Tab
        brokkKeyField.setText(Project.getBrokkKey());
        refreshBalanceDisplay();
        updateSignupLabelVisibility();
        if (brokkProxyRadio != null && localhostProxyRadio != null) { // STAGING check in createServicePanel handles this
            if (Project.getProxySetting() == Project.LlmProxySetting.BROKK) {
                brokkProxyRadio.setSelected(true);
            } else {
                localhostProxyRadio.setSelected(true);
            }
        }

        // Appearance Tab
        if (Project.getTheme().equals("dark")) {
            darkThemeRadio.setSelected(true);
        } else {
            lightThemeRadio.setSelected(true);
        }

        // Models Tab (Project specific, loaded if project exists)
        var project = chrome.getProject();
        if (project != null) {
            var architectConfig = project.getArchitectModelConfig();
            architectModelComboBox.setSelectedItem(architectConfig.name());
            architectReasoningComboBox.setSelectedItem(architectConfig.reasoning());

            var codeConfig = project.getCodeModelConfig();
            codeModelComboBox.setSelectedItem(codeConfig.name());
            codeReasoningComboBox.setSelectedItem(codeConfig.reasoning());

            var askConfig = project.getAskModelConfig();
            askModelComboBox.setSelectedItem(askConfig.name());
            askReasoningComboBox.setSelectedItem(askConfig.reasoning());

            var searchConfig = project.getSearchModelConfig();
            searchModelComboBox.setSelectedItem(searchConfig.name());
            searchReasoningComboBox.setSelectedItem(searchConfig.reasoning());

            // Initial update of reasoning combo states
            var service = chrome.getContextManager().getService();
            updateReasoningComboBox(architectModelComboBox, architectReasoningComboBox, service);
            updateReasoningComboBox(codeModelComboBox, codeReasoningComboBox, service);
            updateReasoningComboBox(askModelComboBox, askReasoningComboBox, service);
            updateReasoningComboBox(searchModelComboBox, searchReasoningComboBox, service);
        }
        updateModelsPanelEnablement(); // Re-check enablement based on project presence

        // Quick Models Tab
        quickModelsTableModel.setFavorites(Project.loadFavoriteModels());

        // GitHub Tab
        if (gitHubTokenField != null) { // Only if panel was created
            gitHubTokenField.setText(Project.getGitHubToken());
        }
    }

    public boolean applySettings() {
        // Service Tab
        String currentBrokkKeyInSettings = Project.getBrokkKey();
        String newBrokkKeyFromField = brokkKeyField.getText().trim();
        boolean keyStateChangedInUI = !newBrokkKeyFromField.equals(currentBrokkKeyInSettings);

        if (keyStateChangedInUI) {
            if (!newBrokkKeyFromField.isEmpty()) {
                try {
                    Service.validateKey(newBrokkKeyFromField);
                    Project.setBrokkKey(newBrokkKeyFromField);
                    refreshBalanceDisplay();
                    updateSignupLabelVisibility();
                    parentDialog.triggerDataRetentionPolicyRefresh(); // Key change might affect org policy
                } catch (IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(this, "Invalid Brokk Key", "Invalid Key", JOptionPane.ERROR_MESSAGE);
                    return false;
                } catch (IOException ex) { // Network error, but allow saving
                    JOptionPane.showMessageDialog(this, "Network error: " + ex.getMessage() + ". Key saved, but validation failed.", "Network Error", JOptionPane.WARNING_MESSAGE);
                    Project.setBrokkKey(newBrokkKeyFromField);
                    refreshBalanceDisplay();
                    updateSignupLabelVisibility();
                    parentDialog.triggerDataRetentionPolicyRefresh();
                }
            } else { // newBrokkKeyFromField is empty
                Project.setBrokkKey(newBrokkKeyFromField);
                refreshBalanceDisplay();
                updateSignupLabelVisibility();
                parentDialog.triggerDataRetentionPolicyRefresh();
            }
        }

        if (brokkProxyRadio != null && localhostProxyRadio != null) { // Not STAGING
            Project.LlmProxySetting proxySetting = brokkProxyRadio.isSelected() ? Project.LlmProxySetting.BROKK : Project.LlmProxySetting.LOCALHOST;
            if (proxySetting != Project.getProxySetting()) {
                 Project.setLlmProxySetting(proxySetting);
                 logger.debug("Applied LLM Proxy Setting: {}", proxySetting);
                 // Consider notifying user about restart if changed. Dialog does this.
            }
        }

        // Appearance Tab
        boolean newIsDark = darkThemeRadio.isSelected();
        String newTheme = newIsDark ? "dark" : "light";
        if (!newTheme.equals(Project.getTheme())) {
            chrome.switchTheme(newIsDark);
            logger.debug("Applied Theme: {}", newTheme);
        }

        // Models Tab (Project specific)
        var project = chrome.getProject();
        if (project != null) {
            applyModelConfig(project, architectModelComboBox, architectReasoningComboBox, project::getArchitectModelConfig, project::setArchitectModelConfig);
            applyModelConfig(project, codeModelComboBox, codeReasoningComboBox, project::getCodeModelConfig, project::setCodeModelConfig);
            applyModelConfig(project, askModelComboBox, askReasoningComboBox, project::getAskModelConfig, project::setAskModelConfig);
            applyModelConfig(project, searchModelComboBox, searchReasoningComboBox, project::getSearchModelConfig, project::setSearchModelConfig);
        }

        // Quick Models Tab
        if (quickModelsTable.isEditing()) {
            quickModelsTable.getCellEditor().stopCellEditing();
        }
        Project.saveFavoriteModels(quickModelsTableModel.getFavorites());
        // chrome.getQuickContextActions().reloadFavoriteModels(); // Commented out due to missing method in Chrome

        // GitHub Tab
        if (gitHubTokenField != null) {
            String newToken = gitHubTokenField.getText().trim();
            if (!newToken.equals(Project.getGitHubToken())) {
                Project.setGitHubToken(newToken);
                GitHubAuth.invalidateInstance();
                logger.debug("Applied GitHub Token");
            }
        }
        return true;
    }

    private void applyModelConfig(Project project,
                                  JComboBox<String> modelCombo,
                                  JComboBox<Service.ReasoningLevel> reasoningCombo,
                                  java.util.function.Supplier<Service.ModelConfig> currentConfigGetter,
                                  java.util.function.Consumer<Service.ModelConfig> configSetter) {
        if (modelCombo == null || reasoningCombo == null || !modelCombo.isEnabled()) return; // Not initialized or panel disabled

        String selectedModelName = (String) modelCombo.getSelectedItem();
        Service.ReasoningLevel selectedReasoning = (Service.ReasoningLevel) reasoningCombo.getSelectedItem();
        boolean supportsReasoning = selectedModelName != null && chrome.getContextManager().getService().supportsReasoningEffort(selectedModelName);
        Service.ReasoningLevel effectiveSelectedReasoning = supportsReasoning ? selectedReasoning : Service.ReasoningLevel.DEFAULT;
        Service.ModelConfig currentConfig = currentConfigGetter.get();

        if (selectedModelName != null && (!selectedModelName.equals(currentConfig.name()) || effectiveSelectedReasoning != currentConfig.reasoning())) {
            configSetter.accept(new Service.ModelConfig(selectedModelName, effectiveSelectedReasoning));
        }
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        SwingUtilities.updateComponentTreeUI(this);
    }

    // --- Inner Classes for Quick Models Table (Copied from SettingsDialog) ---
    private static class FavoriteModelsTableModel extends AbstractTableModel {
        private List<Service.FavoriteModel> favorites;
        private final String[] columnNames = {"Alias", "Model Name", "Reasoning"};

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

        @Override public int getRowCount() { return favorites.size(); }
        @Override public int getColumnCount() { return columnNames.length; }
        @Override public String getColumnName(int column) { return columnNames[column]; }
        @Override public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 0, 1 -> String.class;
                case 2 -> Service.ReasoningLevel.class;
                default -> Object.class;
            };
        }
        @Override public boolean isCellEditable(int rowIndex, int columnIndex) { return true; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            Service.FavoriteModel favorite = favorites.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> favorite.alias();
                case 1 -> favorite.modelName();
                case 2 -> favorite.reasoning();
                default -> null;
            };
        }
        @Override public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (rowIndex < 0 || rowIndex >= favorites.size()) return;
            Service.FavoriteModel oldFavorite = favorites.get(rowIndex);
            Service.FavoriteModel newFavorite = oldFavorite;
            try {
                switch (columnIndex) {
                    case 0: if (aValue instanceof String alias) newFavorite = new Service.FavoriteModel(alias.trim(), oldFavorite.modelName(), oldFavorite.reasoning()); break;
                    case 1: if (aValue instanceof String modelName) newFavorite = new Service.FavoriteModel(oldFavorite.alias(), modelName, oldFavorite.reasoning()); break;
                    case 2: if (aValue instanceof Service.ReasoningLevel reasoning) newFavorite = new Service.FavoriteModel(oldFavorite.alias(), oldFavorite.modelName(), reasoning); break;
                }
            } catch (Exception e) {
                logger.error("Error setting value at ({}, {}) to {}", rowIndex, columnIndex, aValue, e);
                return;
            }
            if (!newFavorite.equals(oldFavorite)) {
                favorites.set(rowIndex, newFavorite);
                fireTableCellUpdated(rowIndex, columnIndex);
                if (columnIndex == 1) fireTableCellUpdated(rowIndex, 2);
            }
        }
    }

    private static class ReasoningCellRenderer extends DefaultTableCellRenderer {
        private final Service models;
        public ReasoningCellRenderer(Service service, JTable table) { this.models = service; }
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String modelName = (String) table.getModel().getValueAt(row, 1);
            if (modelName != null && !models.supportsReasoningEffort(modelName)) {
                label.setText("Off"); label.setEnabled(false); label.setToolTipText("Reasoning effort not supported by " + modelName);
            } else if (value instanceof Service.ReasoningLevel level) {
                label.setText(level.toString()); label.setEnabled(true); label.setToolTipText("Select reasoning effort");
            } else {
                label.setText(value == null ? "" : value.toString()); label.setEnabled(true); label.setToolTipText(null);
            }
            if (!isSelected) {
                label.setBackground(table.getBackground());
                label.setForeground(label.isEnabled() ? table.getForeground() : UIManager.getColor("Label.disabledForeground"));
            } else {
                label.setBackground(table.getSelectionBackground());
                label.setForeground(label.isEnabled() ? table.getSelectionForeground() : UIManager.getColor("Label.disabledForeground"));
            }
            return label;
        }
    }

    private static class ReasoningCellEditor extends DefaultCellEditor {
        private final Service models; private final JTable table; private final JComboBox<Service.ReasoningLevel> comboBox;
        public ReasoningCellEditor(JComboBox<Service.ReasoningLevel> comboBox, Service service, JTable table) {
            super(comboBox); this.comboBox = comboBox; this.models = service; this.table = table; setClickCountToStart(1);
        }
        @Override public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            String modelName = (String) table.getModel().getValueAt(row, 1);
            boolean supportsReasoning = modelName != null && models.supportsReasoningEffort(modelName);
            Component editorComponent = super.getTableCellEditorComponent(table, value, isSelected, row, column);
            editorComponent.setEnabled(supportsReasoning); comboBox.setEnabled(supportsReasoning);
            if (!supportsReasoning) {
                comboBox.setSelectedItem(Service.ReasoningLevel.DEFAULT);
                comboBox.setToolTipText("Reasoning effort not supported by " + modelName);
                comboBox.setRenderer(new DefaultListCellRenderer() {
                    @Override public Component getListCellRendererComponent(JList<?> list, Object val, int i, boolean sel, boolean foc) {
                        JLabel label = (JLabel) super.getListCellRendererComponent(list, val, i, sel, foc);
                        if (i == -1) { label.setText("Off"); label.setForeground(UIManager.getColor("ComboBox.disabledForeground")); }
                        else if (val instanceof Service.ReasoningLevel lvl) label.setText(lvl.toString()); else label.setText(val == null ? "" : val.toString());
                        return label;
                    }
                });
            } else {
                comboBox.setToolTipText("Select reasoning effort"); comboBox.setRenderer(new DefaultListCellRenderer()); comboBox.setSelectedItem(value);
            }
            return editorComponent;
        }
        @Override public boolean isCellEditable(java.util.EventObject anEvent) {
            if (table != null) { int editingRow = table.getEditingRow(); if (editingRow != -1) { String modelName = (String) table.getModel().getValueAt(editingRow, 1); return modelName != null && models.supportsReasoningEffort(modelName); } }
            return super.isCellEditable(anEvent);
        }
        @Override public Object getCellEditorValue() { return comboBox.isEnabled() ? super.getCellEditorValue() : Service.ReasoningLevel.DEFAULT; }
    }
}
