package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.Project.DataRetentionPolicy;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.components.BrowserLabel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SettingsDialog extends JDialog {
    public static final String MODELS_TAB = "Models";

    private static final Logger logger = LogManager.getLogger(SettingsDialog.class);

    private final Chrome chrome;
    private final JTabbedPane tabbedPane;
    private final JPanel projectPanel; // Keep a reference to enable/disable

    // Dialog-level buttons
    private final JButton okButton;
    private final JButton cancelButton;
    private final JButton applyButton;

    // Brokk Key field (Global)
    private JTextField brokkKeyField;
    // Global proxy selection
    private JRadioButton brokkProxyRadio;
    private JRadioButton localhostProxyRadio;
    // Project fields
    private JComboBox<Project.CpgRefresh> cpgRefreshComboBox; // ComboBox for CPG refresh
    private JTextField buildCleanCommandField;
    private JTextField allTestsCommandField;
    private JTextArea buildInstructionsArea;
    private DataRetentionPanel dataRetentionPanel; // Reference to the new panel
    // Model selection combo boxes (initialized in createModelsPanel)
    private JComboBox<String> architectModelComboBox;
    private JComboBox<String> codeModelComboBox;
    private JComboBox<Service.ReasoningLevel> codeReasoningComboBox;
    private JComboBox<String> askModelComboBox;
    private JComboBox<Service.ReasoningLevel> askReasoningComboBox;
    private JComboBox<String> searchModelComboBox;
    private JComboBox<Service.ReasoningLevel> searchReasoningComboBox;
    private JComboBox<Service.ReasoningLevel> architectReasoningComboBox;
    // Theme radio buttons (Global)
    private JRadioButton lightThemeRadio;
    private JRadioButton darkThemeRadio;
    // Project -> General tab specific fields
    private JTextArea styleGuideArea;
    private JTextArea commitFormatArea;
    // Project -> Build tab / Code Intelligence specific fields
    private JScrollPane instructionsScrollPane;
    private JList<String> excludedDirectoriesList;
    private DefaultListModel<String> excludedDirectoriesListModel;
    private JScrollPane excludedScrollPane;
    private JButton addExcludedDirButton;
    private JButton removeExcludedDirButton;
    private JTextField languagesDisplayField;
    private JButton editLanguagesButtonInProjectPanel;
    private Set<io.github.jbellis.brokk.analyzer.Language> currentAnalyzerLanguagesForDialog;
    private JRadioButton runAllTestsRadio;
    private JRadioButton runTestsInWorkspaceRadio;
    // Quick Models Tab components
    private JTable quickModelsTable;
    private FavoriteModelsTableModel quickModelsTableModel;
    // Balance field (Global -> Service)
    private JTextField balanceField;
    // Signup Label (Global -> Service)
    private BrowserLabel signupLabel;
    // Build progress bar
    private JProgressBar buildProgressBar;


    public SettingsDialog(Frame owner, Chrome chrome) {
        super(owner, "Settings", true); // Modal dialog
        this.chrome = chrome;
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setSize(600, 440);
        setLocationRelativeTo(owner);

        tabbedPane = new JTabbedPane(JTabbedPane.LEFT); // Tabs on the left

        // Global Settings Panel
        var globalPanel = createGlobalPanel();
        tabbedPane.addTab("Global", null, globalPanel, "Global application settings");

        // Project Settings Panel
        projectPanel = createProjectPanel();
        tabbedPane.addTab("Project", null, projectPanel, "Settings specific to the current project");

        // Enable/disable project-dependent tab and its contents based on whether a project is open
        var projectIsOpen = chrome.getProject() != null;

        projectPanel.setEnabled(projectIsOpen);
        // Iterating through components of projectPanel's main content (which is the subTabbedPane or a JLabel)
        Component projectActualContent = projectPanel.getComponent(0); // Assuming first child is the main content
        if (projectActualContent != null) {
            projectActualContent.setEnabled(projectIsOpen);
            if (projectActualContent instanceof Container) {
                for (Component comp : ((Container) projectActualContent).getComponents()) {
                    comp.setEnabled(projectIsOpen);
                    if (comp instanceof JTabbedPane) { // If it's the sub-tabbed pane, enable/disable its tabs too
                        JTabbedPane subTabs = (JTabbedPane) comp;
                        for (int i = 0; i < subTabs.getTabCount(); i++) {
                            subTabs.setEnabledAt(i, projectIsOpen);
                            // And the components within those tabs
                            Component tabContent = subTabs.getComponentAt(i);
                            if (tabContent != null) {
                                tabContent.setEnabled(projectIsOpen);
                                if (tabContent instanceof Container) {
                                    for (Component innerComp : ((Container) tabContent).getComponents()) {
                                        innerComp.setEnabled(projectIsOpen);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        tabbedPane.setEnabledAt(tabbedPane.indexOfComponent(projectPanel), projectIsOpen); // Enable/disable the Project tab itself

        // Buttons Panel
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        okButton = new JButton("OK");
        cancelButton = new JButton("Cancel");
        applyButton = new JButton("Apply"); // Added Apply button

        okButton.addActionListener(e -> {
            if (applySettings()) {
                dispose();
            }
        });
        cancelButton.addActionListener(e -> dispose());
        applyButton.addActionListener(e -> applySettings()); // applySettings shows errors, dialog stays open

        // Add Escape key binding to close the dialog (like Cancel)
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dispose();
            }
        });

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(applyButton);

        // Layout
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tabbedPane, BorderLayout.CENTER);
        getContentPane().add(buttonPanel, BorderLayout.SOUTH);
    }

    private JTabbedPane createGlobalPanel() {
        var globalSubTabbedPane = new JTabbedPane(JTabbedPane.TOP);

        // Service Tab
        var servicePanel = createServicePanel();
        globalSubTabbedPane.addTab("Service", null, servicePanel, "Service configuration");

        // Appearance Tab
        var appearancePanel = createAppearancePanel();
        globalSubTabbedPane.addTab("Appearance", null, appearancePanel, "Theme settings");

        // Default Models Tab (Renamed)
        var project = chrome.getProject(); // Models settings depend on project context
        var defaultModelsPanel = createModelsPanel(project);
        globalSubTabbedPane.addTab(MODELS_TAB, null, defaultModelsPanel, "Default model selection and configuration");

        // Quick Models Tab
        var quickModelsPanel = createQuickModelsPanel();
        globalSubTabbedPane.addTab("Quick Models", null, quickModelsPanel, "Define model aliases (shortcuts)");


        // Enable/disable components within the Default Models panel based on project state
        // The Default Models tab itself (within globalSubTabbedPane) remains enabled.
        boolean projectIsOpen = (project != null);
        defaultModelsPanel.setEnabled(projectIsOpen);
        for (Component comp : defaultModelsPanel.getComponents()) {
            comp.setEnabled(projectIsOpen);
            // If a component is a container (like a sub-panel for reasoning), recursively enable/disable its children.
            // This is particularly important if createModelsPanel returns a panel that itself contains other panels/components.
            if (comp instanceof Container) {
                setEnabledRecursive((Container) comp, projectIsOpen);
            }
        }
        // Special handling for JComboBox renderers if they show "Off" when disabled
        if (architectReasoningComboBox != null)
            updateReasoningComboBox(architectModelComboBox, architectReasoningComboBox, chrome.getContextManager().getService());
        if (codeReasoningComboBox != null)
            updateReasoningComboBox(codeModelComboBox, codeReasoningComboBox, chrome.getContextManager().getService());
        if (askReasoningComboBox != null)
            updateReasoningComboBox(askModelComboBox, askReasoningComboBox, chrome.getContextManager().getService());
        if (searchReasoningComboBox != null)
            updateReasoningComboBox(searchModelComboBox, searchReasoningComboBox, chrome.getContextManager().getService());


        return globalSubTabbedPane;
    }


    // --- Panel Creation Methods ---

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
        servicePanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); // Padding for content
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        // Brokk Key Input
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        servicePanel.add(new JLabel("Brokk Key:"), gbc);

        brokkKeyField = new JTextField(20);
        brokkKeyField.setText(Project.getBrokkKey());
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        servicePanel.add(brokkKeyField, gbc);

        // Balance Display
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        servicePanel.add(new JLabel("Balance:"), gbc);

        this.balanceField = new JTextField("Loading..."); // Assign to member field
        this.balanceField.setEditable(false);
        this.balanceField.setColumns(8);
        var balanceDisplayPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        balanceDisplayPanel.add(this.balanceField);
        var topUpUrl = Service.TOP_UP_URL;
        var topUpLabel = new BrowserLabel(topUpUrl, "Top Up");
        balanceDisplayPanel.add(topUpLabel);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 0.0; // Don't let balance field grow
        gbc.fill = GridBagConstraints.NONE;
        servicePanel.add(balanceDisplayPanel, gbc);

        refreshBalanceDisplay(); // Initial balance fetch

        // Sign-up/login link
        var signupUrl = "https://brokk.ai";
        this.signupLabel = new BrowserLabel(signupUrl, "Sign up or get your key at " + signupUrl);
        this.signupLabel.setFont(this.signupLabel.getFont().deriveFont(Font.ITALIC));
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(2, 5, 8, 5); // Extra bottom margin for spacing
        servicePanel.add(this.signupLabel, gbc);
        gbc.insets = new Insets(2, 5, 2, 5); // Reset insets

        // LLM Proxy Setting
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

            if (Project.getProxySetting() == Project.LlmProxySetting.BROKK) {
                brokkProxyRadio.setSelected(true);
            } else {
                localhostProxyRadio.setSelected(true);
            }

            gbc.gridx = 1;
            gbc.gridy = row++;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            servicePanel.add(brokkProxyRadio, gbc);

            gbc.gridy = row++;
            servicePanel.add(localhostProxyRadio, gbc);

            var proxyInfoLabel = new JLabel("Brokk will look for a litellm proxy on localhost:4000");
            proxyInfoLabel.setFont(proxyInfoLabel.getFont().deriveFont(Font.ITALIC));
            gbc.insets = new Insets(0, 25, 2, 5); // Indent
            gbc.gridy = row++;
            servicePanel.add(proxyInfoLabel, gbc);

            var restartLabel = new JLabel("Restart required after changing proxy settings");
            restartLabel.setFont(restartLabel.getFont().deriveFont(Font.ITALIC));
            gbc.gridy = row++;
            servicePanel.add(restartLabel, gbc);
            gbc.insets = new Insets(2, 5, 2, 5); // Reset insets
        }

        // Add vertical glue to push content up
        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        servicePanel.add(Box.createVerticalGlue(), gbc);

        updateSignupLabelVisibility(); // Set initial visibility based on current key
        return servicePanel;
    }

    private void updateSignupLabelVisibility() {
        if (this.signupLabel == null) {
            logger.warn("signupLabel is null, cannot update visibility.");
            return;
        }
        String currentPersistedKey = Project.getBrokkKey();
        boolean keyIsEffectivelyPresent = currentPersistedKey != null && !currentPersistedKey.trim().isEmpty();
        this.signupLabel.setVisible(!keyIsEffectivelyPresent); // Show label if key is NOT present/empty
    }

    private JPanel createAppearancePanel() {
        var appearancePanel = new JPanel(new GridBagLayout());
        appearancePanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); // Padding for content
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

        if (Project.getTheme().equals("dark")) {
            darkThemeRadio.setSelected(true);
        } else {
            lightThemeRadio.setSelected(true);
        }
        lightThemeRadio.putClientProperty("theme", false);
        darkThemeRadio.putClientProperty("theme", true);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        appearancePanel.add(lightThemeRadio, gbc);

        gbc.gridy = row++;
        appearancePanel.add(darkThemeRadio, gbc);

        // Add vertical glue to push content up
        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        appearancePanel.add(Box.createVerticalGlue(), gbc);
        return appearancePanel;
    }

    private JPanel createQuickModelsPanel() {
        var panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var models = chrome.getContextManager().getService(); // Needed for available models & reasoning support check
        var availableModelNames = models.getAvailableModels().keySet().stream().sorted().toArray(String[]::new);
        var reasoningLevels = Service.ReasoningLevel.values();

        // Table Model
        quickModelsTableModel = new FavoriteModelsTableModel(Project.loadFavoriteModels());

        // Table
        quickModelsTable = new JTable(quickModelsTableModel);
        quickModelsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        quickModelsTable.setRowHeight(quickModelsTable.getRowHeight() + 4); // Add padding

        // Column Setup
        // Alias Column (default editor is fine)
        TableColumn aliasColumn = quickModelsTable.getColumnModel().getColumn(0);
        aliasColumn.setPreferredWidth(100);

        // Model Name Column (ComboBox editor)
        TableColumn modelColumn = quickModelsTable.getColumnModel().getColumn(1);
        var modelComboBox = new JComboBox<>(availableModelNames);
        modelColumn.setCellEditor(new DefaultCellEditor(modelComboBox));
        modelColumn.setPreferredWidth(200);

        // Reasoning Column (ComboBox editor, potentially disabled)
        TableColumn reasoningColumn = quickModelsTable.getColumnModel().getColumn(2);
        var reasoningComboBox = new JComboBox<>(reasoningLevels);
        // Custom renderer/editor to handle disabling based on model support
        reasoningColumn.setCellEditor(new ReasoningCellEditor(reasoningComboBox, models, quickModelsTable));
        reasoningColumn.setCellRenderer(new ReasoningCellRenderer(models, quickModelsTable));
        reasoningColumn.setPreferredWidth(100);

        // Add/Remove Buttons
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var addButton = new JButton("Add");
        var removeButton = new JButton("Remove");
        buttonPanel.add(addButton);
        buttonPanel.add(removeButton);

        addButton.addActionListener(e -> {
            // Stop editing before adding a new row
            if (quickModelsTable.isEditing()) {
                quickModelsTable.getCellEditor().stopCellEditing();
            }
            quickModelsTableModel.addFavorite(new Service.FavoriteModel("new-alias", availableModelNames[0], Service.ReasoningLevel.DEFAULT));
            int newRowIndex = quickModelsTableModel.getRowCount() - 1;
            quickModelsTable.setRowSelectionInterval(newRowIndex, newRowIndex);
            quickModelsTable.scrollRectToVisible(quickModelsTable.getCellRect(newRowIndex, 0, true));
            quickModelsTable.editCellAt(newRowIndex, 0); // Start editing the alias
            quickModelsTable.getEditorComponent().requestFocusInWindow();
        });

        removeButton.addActionListener(e -> {
            int selectedRow = quickModelsTable.getSelectedRow();
            if (selectedRow != -1) {
                // Stop editing before removing row
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


    private JPanel createProjectPanel() {
        var project = chrome.getProject();
        // Name change for clarity, this is the panel returned for the "Project" tab
        var projectTabRootPanel = new JPanel(new BorderLayout());
        if (project == null) {
            projectTabRootPanel.add(new JLabel("No project is open."), BorderLayout.CENTER);
            return projectTabRootPanel;
        }

        // Create a sub-tabbed pane for Project settings: General, Build, Data Retention
        var subTabbedPane = new JTabbedPane(JTabbedPane.TOP);

        // ----- Build Tab -----
        var buildPanel = new JPanel(new GridBagLayout());
        buildPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        // Initialize Build fields for new BuildDetails
        buildCleanCommandField = new JTextField(); // used for Build/Lint Command
        allTestsCommandField = new JTextField(); // used for Test All Command
        // Set the text area for build instructions to be 10 lines tall
        buildInstructionsArea = new JTextArea(10, 20);
        buildInstructionsArea.setWrapStyleWord(true);
        buildInstructionsArea.setLineWrap(true);
        instructionsScrollPane = new JScrollPane(buildInstructionsArea);
        instructionsScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        instructionsScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        var details = project.getBuildDetails();
        logger.trace("Initial Build Details: {}", details);
        // Build/Lint Command
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        buildPanel.add(new JLabel("Build/Lint Command:"), gbc);
        buildCleanCommandField.setText(details.buildLintCommand());
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        buildPanel.add(buildCleanCommandField, gbc);

        // Test All Command
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        buildPanel.add(new JLabel("Test All Command:"), gbc);
        allTestsCommandField.setText(details.testAllCommand());
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        buildPanel.add(allTestsCommandField, gbc);

        // Code Agent Tests Setting
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        buildPanel.add(new JLabel("Code Agent Tests:"), gbc);

        runAllTestsRadio = new JRadioButton(Project.CodeAgentTestScope.ALL.toString());
        runTestsInWorkspaceRadio = new JRadioButton(Project.CodeAgentTestScope.WORKSPACE.toString());
        var testScopeGroup = new ButtonGroup();
        testScopeGroup.add(runAllTestsRadio);
        testScopeGroup.add(runTestsInWorkspaceRadio);

        var currentTestScope = project.getCodeAgentTestScope();
        if (currentTestScope == Project.CodeAgentTestScope.ALL) {
            runAllTestsRadio.setSelected(true);
        } else {
            runTestsInWorkspaceRadio.setSelected(true); // Default
        }

        var radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); // No gaps
        radioPanel.setOpaque(false); // Make transparent to inherit buildPanel's background
        radioPanel.add(runAllTestsRadio);
        radioPanel.add(runTestsInWorkspaceRadio);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        buildPanel.add(radioPanel, gbc);

        // Build Instructions
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        buildPanel.add(new JLabel("Build Instructions:"), gbc);
        buildInstructionsArea.setText(details.instructions());
        gbc.gridx = 1;
        gbc.gridy = row; // Current row for instructions
        gbc.weightx = 1.0;
        gbc.weighty = 1.0; // Allow build instructions to take up remaining vertical space
        gbc.fill = GridBagConstraints.BOTH;
        buildPanel.add(this.instructionsScrollPane, gbc);
        row++; // Increment row after instructions

        // ----- Code Intelligence Settings integrated into Build Panel -----
        // CPG Refresh
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.weighty = 0.0; // Reset weighty
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        buildPanel.add(new JLabel("CI Refresh:"), gbc); // CI for Code Intelligence
        cpgRefreshComboBox = new JComboBox<>(new Project.CpgRefresh[]{Project.CpgRefresh.AUTO, Project.CpgRefresh.ON_RESTART, Project.CpgRefresh.MANUAL});
        var currentRefresh = project.getAnalyzerRefresh();
        cpgRefreshComboBox.setSelectedItem(currentRefresh == Project.CpgRefresh.UNSET ? Project.CpgRefresh.AUTO : currentRefresh);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        buildPanel.add(cpgRefreshComboBox, gbc);

        // Analyze Languages (CDL + Edit Button)
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.WEST;
        buildPanel.add(new JLabel("CI Languages:"), gbc);

        languagesDisplayField = new JTextField(20);
        languagesDisplayField.setEditable(false);
        currentAnalyzerLanguagesForDialog = new HashSet<>(project.getAnalyzerLanguages());
        updateLanguagesDisplayField(); // Populate the CDL

        this.editLanguagesButtonInProjectPanel = new JButton("Edit");
        this.editLanguagesButtonInProjectPanel.addActionListener(e -> showLanguagesDialog(project));

        var languagesPanel = new JPanel(new BorderLayout(5, 0)); // 5px hgap
        languagesPanel.add(languagesDisplayField, BorderLayout.CENTER);
        languagesPanel.add(this.editLanguagesButtonInProjectPanel, BorderLayout.EAST);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        buildPanel.add(languagesPanel, gbc);

        // Excluded Directories
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST; // Align label top-left for list
        buildPanel.add(new JLabel("CI Exclusions:"), gbc);

        excludedDirectoriesListModel = new DefaultListModel<>();
        var sortedExcludedDirs = (details != null ? details.excludedDirectories() : Set.<String>of())
                .stream().sorted().toList();
        for (String dir : sortedExcludedDirs) {
            excludedDirectoriesListModel.addElement(dir);
        }
        excludedDirectoriesList = new JList<>(excludedDirectoriesListModel);
        excludedDirectoriesList.setVisibleRowCount(3); // Adjusted row count
        this.excludedScrollPane = new JScrollPane(excludedDirectoriesList);

        gbc.gridx = 1;
        gbc.gridy = row;
        gbc.weightx = 1.0;
        gbc.weighty = 0.5; // Allow list to take some vertical space
        gbc.fill = GridBagConstraints.BOTH;
        buildPanel.add(this.excludedScrollPane, gbc);

        // Buttons for Excluded Directories
        var excludedButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        this.addExcludedDirButton = new JButton("Add");
        this.removeExcludedDirButton = new JButton("Remove");
        excludedButtonsPanel.add(this.addExcludedDirButton);
        excludedButtonsPanel.add(this.removeExcludedDirButton);

        gbc.gridy = row + 1; // Position buttons below the list (same row logically, but next grid cell down)
        gbc.weighty = 0.0;   // Buttons don't take vertical space
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(2, 0, 2, 2); // Adjust insets for button panel
        buildPanel.add(excludedButtonsPanel, gbc);
        row += 2; // Increment row for list and buttons
        gbc.insets = new Insets(2, 2, 2, 2); // Reset insets

        this.addExcludedDirButton.addActionListener(e -> {
            String newDir = JOptionPane.showInputDialog(SettingsDialog.this,
                                                        "Enter directory to exclude (e.g., target/, build/):",
                                                        "Add Excluded Directory",
                                                        JOptionPane.PLAIN_MESSAGE);
            if (newDir != null && !newDir.trim().isEmpty()) {
                String trimmedNewDir = newDir.trim();
                excludedDirectoriesListModel.addElement(trimmedNewDir);
                var elements = new ArrayList<String>();
                for (int i = 0; i < excludedDirectoriesListModel.getSize(); i++) {
                    elements.add(excludedDirectoriesListModel.getElementAt(i));
                }
                elements.sort(String::compareToIgnoreCase);
                excludedDirectoriesListModel.clear();
                for (String element : elements) {
                    excludedDirectoriesListModel.addElement(element);
                }
            }
        });

        this.removeExcludedDirButton.addActionListener(e -> {
            int[] selectedIndices = excludedDirectoriesList.getSelectedIndices();
            for (int i = selectedIndices.length - 1; i >= 0; i--) {
                excludedDirectoriesListModel.removeElementAt(selectedIndices[i]);
            }
        });

        // --- Run Build Agent button (moved to bottom) ---
        gbc.gridx = 1; // Align with input fields (right column)
        gbc.gridy = row++; // Next available row
        gbc.weightx = 0.0; // Don't stretch button horizontally
        gbc.weighty = 0.0; // Don't stretch button vertically
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST; // Align button to the right
        var rerunBuildButton = new JButton("Run Build Agent");
        buildPanel.add(rerunBuildButton, gbc);

        // --- Progress Bar for Build Agent ---
        buildProgressBar = new JProgressBar();
        buildProgressBar.setIndeterminate(true);
        buildProgressBar.setVisible(false);
        gbc.gridx = 1; // Align with input fields (right column)
        gbc.gridy = row++; // Next available row
        gbc.fill = GridBagConstraints.HORIZONTAL; // Let progress bar fill width
        gbc.anchor = GridBagConstraints.EAST;
        buildPanel.add(buildProgressBar, gbc);

        rerunBuildButton.addActionListener(e -> {
            var cm = chrome.getContextManager();
            var proj = chrome.getProject();
            if (proj == null) {
                chrome.toolErrorRaw("No project is open.");
                return;
            }

            // List of controls to disable/enable
            setBuildControlsEnabled(false);
            rerunBuildButton.setEnabled(false);

            cm.submitUserTask("Running Build Agent", () -> {
                try {
                    chrome.systemOutput("Starting Build Agent...");
                    var agent = new BuildAgent(proj, cm.getLlm(cm.getSearchModel(), "Infer build details"), cm.getToolRegistry());
                    var newBuildDetails = agent.execute();

                    if (newBuildDetails == BuildAgent.BuildDetails.EMPTY) {
                        logger.warn("Build Agent returned empty details, considering it an error.");
                        SwingUtilities.invokeLater(() -> {
                            String errorMessage = "Build Agent failed to determine build details. Please check agent logs.";
                            chrome.toolErrorRaw(errorMessage);
                            JOptionPane.showMessageDialog(SettingsDialog.this, errorMessage, "Build Agent Error", JOptionPane.ERROR_MESSAGE);
                            // Do not save or update UI with empty details
                        });
                    } else {
                        proj.saveBuildDetails(newBuildDetails);
                        SwingUtilities.invokeLater(() -> {
                            updateBuildDetailsFieldsFromAgent(newBuildDetails);
                            chrome.systemOutput("Build Agent finished. Settings updated.");
                        });
                    }
                } catch (Exception ex) {
                    logger.error("Error running Build Agent", ex);
                    SwingUtilities.invokeLater(() -> {
                        String errorMessage = "Build Agent failed: " + ex.getMessage();
                        chrome.toolErrorRaw(errorMessage);
                        JOptionPane.showMessageDialog(SettingsDialog.this, errorMessage, "Build Agent Error", JOptionPane.ERROR_MESSAGE);
                    });
                } finally {
                    SwingUtilities.invokeLater(() -> {
                        setBuildControlsEnabled(true);
                        rerunBuildButton.setEnabled(true);
                    });
                }
            });
        });
        // No anchor reset needed if it's the last component in this GridBagLayout sequence for buildPanel

        // ----- Other Tab (now General Tab) -----
        var otherPanel = new JPanel(new GridBagLayout());
        otherPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        // Re-initialize gbc for otherPanel to avoid conflicts
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row = 0; // Reset row for otherPanel

        // --- Style Guide Editor ---
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST; // Align label top-left
        gbc.fill = GridBagConstraints.NONE;
        otherPanel.add(new JLabel("Style Guide:"), gbc);

        styleGuideArea = new JTextArea(5, 40); // Reduced height
        styleGuideArea.setText(project.getStyleGuide()); // Load current style guide
        styleGuideArea.setWrapStyleWord(true);
        styleGuideArea.setLineWrap(true);
        var styleScrollPane = new JScrollPane(styleGuideArea);
        styleScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        styleScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        gbc.gridx = 1;
        gbc.gridy = row++; // Increment row after adding style guide
        gbc.weightx = 1.0;
        gbc.weighty = 1.0; // Allow style guide area to grow vertically
        gbc.fill = GridBagConstraints.BOTH; // Fill available space
        gbc.weighty = 0.5; // Give it half the vertical weight
        otherPanel.add(styleScrollPane, gbc);
        row++; // Move to next row

        // --- Style Guide Explanation ---
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.weighty = 0.0; // No vertical weight for label
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        var styleGuideInfo = new JLabel("<html>The Style Guide is used by the Code Agent to help it conform to your project's style.</html>");
        styleGuideInfo.setFont(styleGuideInfo.getFont().deriveFont(Font.ITALIC, styleGuideInfo.getFont().getSize() * 0.9f));
        gbc.insets = new Insets(0, 2, 8, 2); // Add bottom margin
        otherPanel.add(styleGuideInfo, gbc);

        // --- Commit Message Format ---
        gbc.insets = new Insets(2, 2, 2, 2); // Reset insets
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST; // Align label top-left
        gbc.fill = GridBagConstraints.NONE;
        otherPanel.add(new JLabel("Commit Format:"), gbc);

        commitFormatArea = new JTextArea(5, 40); // Same height as style guide initially
        commitFormatArea.setText(project.getCommitMessageFormat());
        commitFormatArea.setWrapStyleWord(true);
        commitFormatArea.setLineWrap(true);
        var commitFormatScrollPane = new JScrollPane(commitFormatArea);
        commitFormatScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commitFormatScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        // Use a sub-panel to hold the text area and reset button together
        var commitFormatPanel = new JPanel(new BorderLayout(5, 0)); // 5px horizontal gap
        commitFormatPanel.add(commitFormatScrollPane, BorderLayout.CENTER);

        gbc.gridx = 1;
        gbc.gridy = row++; // Increment row
        gbc.weightx = 1.0;
        gbc.weighty = 0.5; // Give it the other half of vertical weight
        gbc.fill = GridBagConstraints.BOTH; // Fill available space
        otherPanel.add(commitFormatPanel, gbc); // Add the sub-panel

        // --- Commit Format Explanation ---
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.weighty = 0.0; // No vertical weight for label
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        var commitFormatInfo = new JLabel("<html>This informs the LLM how to structure the commit message suggestions it makes.</html>");
        commitFormatInfo.setFont(commitFormatInfo.getFont().deriveFont(Font.ITALIC, commitFormatInfo.getFont().getSize() * 0.9f));
        gbc.insets = new Insets(0, 2, 2, 2); // Small bottom margin
        otherPanel.add(commitFormatInfo, gbc);

        // Reset weighty for subsequent components if any were added below
        gbc.weighty = 0.0;
        gbc.insets = new Insets(2, 2, 2, 2); // Reset insets


        // Add General tab, then Build tab
        subTabbedPane.addTab("General", otherPanel); // Renamed from "Other"
        subTabbedPane.addTab("Build", buildPanel);

        // ----- Data Retention Tab -----
        dataRetentionPanel = new DataRetentionPanel(project); // Create the panel instance
        subTabbedPane.addTab("Data Retention", dataRetentionPanel); // Add the new tab

        projectTabRootPanel.add(subTabbedPane, BorderLayout.CENTER);
        return projectTabRootPanel;
    }

    private void setBuildControlsEnabled(boolean enabled) {
        // Determine effective enabled state based on project presence if we are re-enabling
        boolean effectiveEnabled = enabled ? (chrome.getProject() != null) : false;

        buildProgressBar.setVisible(!enabled); // Progress bar visible when controls are disabled

        List<Component> controlsToManage = List.of(
                buildCleanCommandField, allTestsCommandField,
                runAllTestsRadio, runTestsInWorkspaceRadio,
                instructionsScrollPane, buildInstructionsArea,
                cpgRefreshComboBox,
                editLanguagesButtonInProjectPanel,
                excludedScrollPane, excludedDirectoriesList,
                addExcludedDirButton, removeExcludedDirButton,
                okButton, cancelButton, applyButton
        );

        for (Component control : controlsToManage) {
            control.setEnabled(effectiveEnabled);
        }
    }

    private void updateBuildDetailsFieldsFromAgent(BuildAgent.BuildDetails details) {
        if (details == null) {
            logger.warn("Attempted to update build details fields with null details.");
            return;
        }
        // Ensure updates happen on the EDT, though this method is expected to be called from within one.
        SwingUtilities.invokeLater(() -> {
            buildCleanCommandField.setText(details.buildLintCommand());
            allTestsCommandField.setText(details.testAllCommand());
            buildInstructionsArea.setText(details.instructions());
            excludedDirectoriesListModel.clear();
            var sortedExcludedDirs = details.excludedDirectories().stream().sorted().toList();
            for (String dir : sortedExcludedDirs) {
                excludedDirectoriesListModel.addElement(dir);
            }
            logger.trace("UI fields updated with new BuildDetails from agent: {}", details);
        });
    }

    private void updateLanguagesDisplayField() {
        if (languagesDisplayField == null || currentAnalyzerLanguagesForDialog == null) {
            return;
        }
        String cdl = currentAnalyzerLanguagesForDialog.stream()
                .map(lang -> lang.name()) // Use lambda expression for clarity and to aid type inference
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
        languagesDisplayField.setText(cdl.isEmpty() ? "None" : cdl);
    }

    private void showLanguagesDialog(Project project) {
        var dialog = new JDialog(this, "Select Languages for Analysis", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(300, 400);
        dialog.setLocationRelativeTo(this);

        // Preserve Order
        var languageCheckBoxMapLocal = new java.util.LinkedHashMap<io.github.jbellis.brokk.analyzer.Language, JCheckBox>();
        var languagesInProject = new HashSet<io.github.jbellis.brokk.analyzer.Language>();
        if (project.getRoot() != null) {
            Set<io.github.jbellis.brokk.analyzer.ProjectFile> filesToScan;
            if (project.hasGit()) {
                filesToScan = project.getRepo().getTrackedFiles();
            } else {
                filesToScan = project.getAllFiles();
            }

            for (var pf : filesToScan) {
                String extension = com.google.common.io.Files.getFileExtension(pf.absPath().toString());
                if (!extension.isEmpty()) {
                    var lang = io.github.jbellis.brokk.analyzer.Language.fromExtension(extension);
                    if (lang != io.github.jbellis.brokk.analyzer.Language.NONE) {
                        languagesInProject.add(lang);
                    }
                }
            }
        }

        var checkBoxesPanel = new JPanel();
        checkBoxesPanel.setLayout(new BoxLayout(checkBoxesPanel, BoxLayout.PAGE_AXIS));
        checkBoxesPanel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));

        var sortedLanguagesToShow = languagesInProject.stream()
                .sorted(java.util.Comparator.comparing(io.github.jbellis.brokk.analyzer.Language::name))
                .toList();

        for (var lang : sortedLanguagesToShow) {
            var checkBox = new JCheckBox(lang.name());
            checkBox.setSelected(currentAnalyzerLanguagesForDialog.contains(lang)); // Use current dialog state
            languageCheckBoxMapLocal.put(lang, checkBox);
            checkBoxesPanel.add(checkBox);
        }
        if (sortedLanguagesToShow.isEmpty()) {
            checkBoxesPanel.add(new JLabel("No analyzable languages detected in the project."));
        }

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new JButton("OK");
        var cancelButton = new JButton("Cancel");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);

        okButton.addActionListener(e -> {
            currentAnalyzerLanguagesForDialog.clear();
            for (var entry : languageCheckBoxMapLocal.entrySet()) {
                if (entry.getValue().isSelected()) {
                    currentAnalyzerLanguagesForDialog.add(entry.getKey());
                }
            }
            updateLanguagesDisplayField();
            dialog.dispose();
        });

        cancelButton.addActionListener(e -> dialog.dispose());

        dialog.add(new JScrollPane(checkBoxesPanel), BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }


    /**
     * Static inner class to encapsulate the Data Retention settings UI and logic.
     */
    public static class DataRetentionPanel extends JPanel {
        private final Project project;
        private final ButtonGroup policyGroup;
        private final JRadioButton improveRadio;
        private final JLabel improveDescLabel;
        private final JRadioButton minimalRadio;
        private final JLabel minimalDescLabel;
        private final JLabel orgDisabledLabel;
        private final JLabel infoLabel;

        public DataRetentionPanel(Project project) {
            super(new GridBagLayout());
            this.project = project;
            this.policyGroup = new ButtonGroup();

            setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            var gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            int y = 0;

            // --- Improve Brokk Radio Button ---
            improveRadio = new JRadioButton(DataRetentionPolicy.IMPROVE_BROKK.getDisplayName());
            improveRadio.putClientProperty("policy", DataRetentionPolicy.IMPROVE_BROKK);
            policyGroup.add(improveRadio);

            // --- Improve Brokk Description Label ---
            improveDescLabel = new JLabel("<html>Allow Brokk and/or its partners to use requests from this project " + "to train models and improve the Brokk service.</html>");
            improveDescLabel.setFont(improveDescLabel.getFont().deriveFont(Font.ITALIC, improveDescLabel.getFont().getSize() * 0.9f));

            // --- Minimal Radio Button ---
            minimalRadio = new JRadioButton(DataRetentionPolicy.MINIMAL.getDisplayName());
            minimalRadio.putClientProperty("policy", DataRetentionPolicy.MINIMAL);
            policyGroup.add(minimalRadio);

            // --- Minimal Description Label ---
            minimalDescLabel = new JLabel("<html>Brokk will not share data from this project with anyone and " + "will restrict its use to the minimum necessary to provide the Brokk service.</html>");
            minimalDescLabel.setFont(minimalDescLabel.getFont().deriveFont(Font.ITALIC, minimalDescLabel.getFont().getSize() * 0.9f));

            // --- Organization Disabled Label ---
            orgDisabledLabel = new JLabel("<html><b>Data sharing is disabled by your organization.</b></html>");

            boolean dataSharingAllowedByOrg = project.isDataShareAllowed();

            if (dataSharingAllowedByOrg) {
                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(5, 5, 0, 5);
                add(improveRadio, gbc);

                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(0, 25, 10, 5);
                add(improveDescLabel, gbc);

                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(5, 5, 0, 5);
                add(minimalRadio, gbc);

                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(0, 25, 10, 5);
                add(minimalDescLabel, gbc);

                // Hide the org disabled label if previously shown
                orgDisabledLabel.setVisible(false);
            } else {
                // Hide all the data sharing controls
                improveRadio.setVisible(false);
                improveDescLabel.setVisible(false);
                minimalRadio.setVisible(false);
                minimalDescLabel.setVisible(false);

                // Show the "disabled by org" message
                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(5, 5, 10, 5);
                add(orgDisabledLabel, gbc);
                orgDisabledLabel.setVisible(true);
            }

            // --- Informational Text ---
            gbc.insets = new Insets(15, 5, 5, 5); // Add spacing above info text
            infoLabel = new JLabel("<html>Data retention policy affects which AI models are allowed. In particular, Deepseek models are not available under the Essential Use Only policy, since Deepseek will train on API requests independently of Brokk.</html>");
            infoLabel.setFont(infoLabel.getFont().deriveFont(infoLabel.getFont().getSize() * 0.9f));
            gbc.gridx = 0;
            gbc.gridy = y++;
            add(infoLabel, gbc);

            // Add vertical glue to push components to the top
            gbc.gridx = 0;
            gbc.gridy = y;
            gbc.weighty = 1.0;
            gbc.fill = GridBagConstraints.VERTICAL;
            add(Box.createVerticalGlue(), gbc);

            // Initial layout and policy load
            layoutControls();
            loadPolicy();
        }

        /**
         * Sets up the UI controls based on whether data sharing is allowed by the organization.
         * This method clears existing components and re-adds them.
         */
        private void layoutControls() {
            removeAll(); // Clear existing components before re-adding

            var gbc = new GridBagConstraints(); // Re-initialize GBC for local use
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            int y = 0;

            boolean dataSharingAllowedByOrg = project.isDataShareAllowed();

            if (dataSharingAllowedByOrg) {
                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(5, 5, 0, 5);
                add(improveRadio, gbc);
                improveRadio.setVisible(true); // Ensure visible

                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(0, 25, 10, 5);
                add(improveDescLabel, gbc);
                improveDescLabel.setVisible(true); // Ensure visible

                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(5, 5, 0, 5);
                add(minimalRadio, gbc);
                minimalRadio.setVisible(true); // Ensure visible

                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(0, 25, 10, 5);
                add(minimalDescLabel, gbc);
                minimalDescLabel.setVisible(true); // Ensure visible

                orgDisabledLabel.setVisible(false); // Ensure this is not visible
            } else {
                improveRadio.setVisible(false);
                improveDescLabel.setVisible(false);
                minimalRadio.setVisible(false);
                minimalDescLabel.setVisible(false);

                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(5, 5, 10, 5);
                add(orgDisabledLabel, gbc);
                orgDisabledLabel.setVisible(true); // Ensure visible
            }

            gbc.insets = new Insets(15, 5, 5, 5);
            infoLabel.setVisible(true); // Always visible
            gbc.gridx = 0;
            gbc.gridy = y++;
            add(infoLabel, gbc);

            gbc.gridx = 0;
            gbc.gridy = y;
            gbc.weighty = 1.0;
            gbc.fill = GridBagConstraints.VERTICAL;
            add(Box.createVerticalGlue(), gbc); // Always add glue

            revalidate();
            repaint();
        }

        /**
         * Refreshes the UI based on the current data sharing allowance and reloads the policy.
         */
        public void refreshStateAndUI() {
            layoutControls();
            loadPolicy();
        }

        /**
         * Loads the current policy from the project and selects the corresponding radio button
         * if data sharing is allowed by the organization.
         */
        public void loadPolicy() {
            if (project.isDataShareAllowed()) {
                var currentPolicy = project.getDataRetentionPolicy();
                if (currentPolicy == DataRetentionPolicy.IMPROVE_BROKK) {
                    improveRadio.setSelected(true);
                } else if (currentPolicy == DataRetentionPolicy.MINIMAL) {
                    minimalRadio.setSelected(true);
                } else {
                    policyGroup.clearSelection(); // No selection for UNSET
                }
            }
            // If data sharing is not allowed, radio buttons are hidden,
            // and project.getDataRetentionPolicy() will return MINIMAL.
            // No specific UI update for radio buttons is needed here.
        }

        /**
         * Returns the currently selected policy. If data sharing is disabled by the organization,
         * this will always be MINIMAL. Otherwise, it's based on the radio button selection.
         * Returns UNSET if no radio button is selected and sharing is allowed.
         */
        public DataRetentionPolicy getSelectedPolicy() {
            if (!project.isDataShareAllowed()) {
                return DataRetentionPolicy.MINIMAL;
            }
            if (improveRadio.isSelected()) {
                return DataRetentionPolicy.IMPROVE_BROKK;
            } else if (minimalRadio.isSelected()) {
                return DataRetentionPolicy.MINIMAL;
            } else {
                return DataRetentionPolicy.UNSET;
            }
        }

        /**
         * Saves the currently selected policy (or MINIMAL if org disallows sharing)
         * back to the project if it's a valid policy (not UNSET).
         */
        public void applyPolicy() {
            var selectedPolicy = getSelectedPolicy();
            // project.setDataRetentionPolicy already asserts that policy is not UNSET.
            if (selectedPolicy != DataRetentionPolicy.UNSET) {
                project.setDataRetentionPolicy(selectedPolicy);
            }
        }
    }

    /**
     * Creates the panel that lets the user pick per-role models and – where
     * supported – their “reasoning effort”.  The new layout keeps every row
     * on a tidy four-column grid and lets both the model and reasoning
     * combo-boxes grow with the window, so the dialog remains readable even
     * when long model names are present.
     *
     * If the project is null, a placeholder panel is returned.
     */
    /**
     * Builds the “Models” tab in a compact two-column grid:
     * <p>
     * Architect  [model-combo]
     * Reasoning  [reasoning-combo]
     * <explanation>
     * <p>
     * Each model type gets its own block separated by extra vertical padding.
     * Labels are right-aligned; we omit the words “Model:” / “Reasoning:” and all
     * colons, per user request.  Combo-boxes keep their preferred size (no weightx).
     *
     * @param project The current project, or null if no project is open.
     * @return A JPanel for the Models tab.
     */
    private JPanel createModelsPanel(Project project) {
        if (project == null) {
            var placeholderPanel = new JPanel(new BorderLayout());
            placeholderPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            placeholderPanel.add(new JLabel("No project is open. Model settings are project-specific."), BorderLayout.CENTER);
            return placeholderPanel;
        }

        var panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.EAST;      // right-align labels
        gbc.fill = GridBagConstraints.NONE;      // combos keep preferred width
        gbc.weightx = 0.0;                          // “don’t weightx anything”

        var models = chrome.getContextManager().getService();
        var availableModels = models.getAvailableModels()
                .keySet()
                .stream()
                .sorted()
                .toArray(String[]::new);
        var reasoningLevels = Service.ReasoningLevel.values();

        // will (de)activate reasoning dropdowns when model changes
        Runnable updateReasoningState = () -> {
            updateReasoningComboBox(architectModelComboBox, architectReasoningComboBox, models);
            updateReasoningComboBox(codeModelComboBox, codeReasoningComboBox, models);
            updateReasoningComboBox(askModelComboBox, askReasoningComboBox, models);
            updateReasoningComboBox(searchModelComboBox, searchReasoningComboBox, models);
        };

        int row = 0;   // running row counter

        /* ---------------- Architect -------------------------------------- */
        var architectConfig = project.getArchitectModelConfig();
        architectModelComboBox = new JComboBox<>(availableModels);
        architectModelComboBox.setSelectedItem(architectConfig.name());
        architectModelComboBox.addActionListener(e -> updateReasoningState.run());

        architectReasoningComboBox = new JComboBox<>(reasoningLevels);
        architectReasoningComboBox.setSelectedItem(architectConfig.reasoning());

        row = addModelSection(panel, gbc, row,
                              "Architect",
                              architectModelComboBox,
                              architectReasoningComboBox,
                              "The Architect plans and executes multi-step projects, calling other agents and tools as needed");

        /* ---------------- Code ------------------------------------------- */
        var codeConfig = project.getCodeModelConfig();
        codeModelComboBox = new JComboBox<>(availableModels);
        codeModelComboBox.setSelectedItem(codeConfig.name());
        codeModelComboBox.addActionListener(e -> updateReasoningState.run());

        codeReasoningComboBox = new JComboBox<>(reasoningLevels);
        codeReasoningComboBox.setSelectedItem(codeConfig.reasoning());

        row = addModelSection(panel, gbc, row,
                              "Code",
                              codeModelComboBox,
                              codeReasoningComboBox,
                              "Used when invoking the Code Agent manually or via Architect");

        /* ---------------- Ask -------------------------------------------- */
        var askConfig = project.getAskModelConfig();
        askModelComboBox = new JComboBox<>(availableModels);
        askModelComboBox.setSelectedItem(askConfig.name());
        askModelComboBox.addActionListener(e -> updateReasoningState.run());

        askReasoningComboBox = new JComboBox<>(reasoningLevels);
        askReasoningComboBox.setSelectedItem(askConfig.reasoning());

        row = addModelSection(panel, gbc, row,
                              "Ask",
                              askModelComboBox,
                              askReasoningComboBox,
                              "Answers questions about the current Workspace contents");

        /* ---------------- Search ----------------------------------------- */
        var searchConfig = project.getSearchModelConfig();
        searchModelComboBox = new JComboBox<>(availableModels);
        searchModelComboBox.setSelectedItem(searchConfig.name());
        searchModelComboBox.addActionListener(e -> updateReasoningState.run());

        searchReasoningComboBox = new JComboBox<>(reasoningLevels);
        searchReasoningComboBox.setSelectedItem(searchConfig.reasoning());

        row = addModelSection(panel, gbc, row,
                              "Search",
                              searchModelComboBox,
                              searchReasoningComboBox,
                              "Searches the project for information described in natural language; also used for Deep Scan");

        /* push everything up */
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 3; // Span all three columns
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        panel.add(Box.createVerticalGlue(), gbc);

        SwingUtilities.invokeLater(updateReasoningState);   // initial enable/disable

        // Return the panel directly, without wrapping in a scroll pane
        return panel;
    }

    private void refreshBalanceDisplay() {
        if (this.balanceField == null) {
            logger.warn("balanceField is null, cannot refresh balance display.");
            return;
        }
        this.balanceField.setText("Loading...");

        var contextManager = chrome.getContextManager();
        var models = contextManager.getService();
        contextManager.submitBackgroundTask("Refreshing user balance", () -> {
            try {
                float balance = models.getUserBalance(); // This uses the current key context
                SwingUtilities.invokeLater(() -> this.balanceField.setText(String.format("$%.2f", balance)));
            } catch (java.io.IOException e) {
                logger.debug("Failed to refresh user balance", e);
                SwingUtilities.invokeLater(() -> this.balanceField.setText("Error"));
            }
        });
    }

    /**
     * Adds one “model / reasoning / explanation” block to the grid-bag panel and
     * returns the next free row index.
     * <p>
     * Layout (three columns):
     * <label>        [model-combo]        <italic explanation (spans 2 rows)>
     * Reasoning      [reasoning-combo]
     * (A 10-pixel top-margin separates blocks.)
     */
    private int addModelSection(JPanel panel,
                                GridBagConstraints gbc,
                                int startRow,
                                String typeLabel,
                                JComboBox<String> modelCombo,
                                JComboBox<Service.ReasoningLevel> reasoningCombo,
                                String explanation)
    {
        /* ---------- model row ------------------------------------------------ */
        var savedInsets = gbc.insets;
        gbc.insets = new Insets(startRow == 0 ? 4 : 14, 4, 4, 4);   // extra top-pad
        gbc.anchor = GridBagConstraints.EAST;
        gbc.gridx = 0;
        gbc.gridy = startRow;
        panel.add(new JLabel(typeLabel), gbc);

        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 1;
        panel.add(modelCombo, gbc);

        /* ---------- reasoning row ------------------------------------------- */
        gbc.insets = new Insets(4, 4, 2, 4);
        gbc.anchor = GridBagConstraints.EAST;
        gbc.gridx = 0;
        gbc.gridy = startRow + 1;
        panel.add(new JLabel("Reasoning"), gbc);

        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 1;
        panel.add(reasoningCombo, gbc);
        gbc.insets = savedInsets;        // restore

        /* ---------- explanatory text (wraps & centers) ---------------------- */
        var tip = new JTextArea(explanation);
        tip.setEditable(false);
        tip.setFocusable(false);
        tip.setLineWrap(true);
        tip.setWrapStyleWord(true);
        tip.setOpaque(false);
        tip.setBorder(null);
        tip.setFont(tip.getFont()
                            .deriveFont(Font.ITALIC,
                                        tip.getFont().getSize() * 0.9f));

        gbc.insets = new Insets(startRow == 0 ? 4 : 14, 10, 2, 4);
        gbc.gridx = 2;                     // third column
        gbc.gridy = startRow;              // top row of the block
        gbc.gridheight = 2;                     // span two rows
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        panel.add(tip, gbc);

        /* ---------- restore defaults for caller ----------------------------- */
        gbc.insets = savedInsets;
        gbc.gridheight = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;

        return startRow + 2;   // next free row
    }

    private boolean applySettings() {
        // Apply Global Settings

        // -- Apply Brokk Key --
        String currentBrokkKeyInSettings = Project.getBrokkKey();
        String newBrokkKeyFromField = brokkKeyField.getText().trim();
        boolean keyStateChangedInUI = !newBrokkKeyFromField.equals(currentBrokkKeyInSettings);

        if (keyStateChangedInUI) {
            if (!newBrokkKeyFromField.isEmpty()) {
                try {
                    Service.validateKey(newBrokkKeyFromField);
                    // Key validation passed (no exception)
                    Project.setBrokkKey(newBrokkKeyFromField); // Set the key if validation is successful
                    logger.debug("Applied Brokk Key: {}", "****");
                    refreshBalanceDisplay();
                    updateSignupLabelVisibility(); // Update based on newly set valid key
                    // Re-evaluate data sharing allowance as key change might affect it
                    if (chrome.getProject() != null && dataRetentionPanel != null) {
                        dataRetentionPanel.refreshStateAndUI();
                    }
                } catch (IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(this, "Invalid Brokk Key", "Invalid Key", JOptionPane.ERROR_MESSAGE);
                    // Do not set the invalid key in Project. Do not update signup label visibility based on this attempt.
                    return false; // Validation failed, do not proceed
                } catch (IOException ex) { // Network error, but we still allow saving the key
                    JOptionPane.showMessageDialog(this, "Network error: " + ex.getMessage(), "Unable to reach Brokk service to validate key.", JOptionPane.ERROR_MESSAGE);
                    Project.setBrokkKey(newBrokkKeyFromField); // Set the new key (accepted despite network error)
                    logger.debug("Applied Brokk Key (network error during validation): {}", "****");
                    refreshBalanceDisplay();
                    updateSignupLabelVisibility(); // Update based on newly set accepted key
                    // Re-evaluate data sharing allowance
                    if (chrome.getProject() != null && dataRetentionPanel != null) {
                        dataRetentionPanel.refreshStateAndUI();
                    }
                }
            } else { // newBrokkKeyFromField is empty
                Project.setBrokkKey(newBrokkKeyFromField); // Set empty key
                logger.debug("Applied Brokk Key: <empty>");
                refreshBalanceDisplay();
                updateSignupLabelVisibility(); // Label will become visible
                // Re-evaluate data sharing allowance
                if (chrome.getProject() != null && dataRetentionPanel != null) {
                    dataRetentionPanel.refreshStateAndUI();
                }
            }
        }

        // -- Apply Quick Models --
        // Stop editing if a cell is currently being edited
        if (quickModelsTable != null && quickModelsTable.isEditing()) {
            quickModelsTable.getCellEditor().stopCellEditing();
        }
        if (quickModelsTableModel != null) {
            Project.saveFavoriteModels(quickModelsTableModel.getFavorites());
            // TODO: Need a way to notify the main application (e.g., Quick Context menu) about changes
        }

        // -- Apply LLM Proxy Setting --
        if (brokkProxyRadio != null && localhostProxyRadio != null) {
            Project.LlmProxySetting proxySetting = brokkProxyRadio.isSelected()
                                                   ? Project.LlmProxySetting.BROKK
                                                   : Project.LlmProxySetting.LOCALHOST;
            Project.setLlmProxySetting(proxySetting);
            logger.debug("Applied LLM Proxy Setting: {}", proxySetting);
        }


        // -- Apply Theme --
        if (darkThemeRadio != null && lightThemeRadio != null) { // Ensure they were initialized
            boolean newIsDark = darkThemeRadio.isSelected();
            String newTheme = newIsDark ? "dark" : "light";
            if (!newTheme.equals(Project.getTheme())) {
                chrome.switchTheme(newIsDark); // switchTheme calls Project.setTheme internally
                logger.debug("Applied Theme: {}", newTheme);
            }
        } else {
            // This case should ideally not be hit if panel construction is correct.
            logger.warn("Theme radio buttons not initialized, cannot apply theme settings.");
        }

        // Apply Project Settings (if project is open)
        // Model settings are also project-specific and handled below.
        var project = chrome.getProject();
        // --- Apply settings from "Project" tab ---
        // (This checks tabbedPane.isEnabledAt(1) implicitly by project != null,
        // as the tab's enabled state is tied to project presence)

        // Get current details to compare against and preserve non-editable fields
        var currentDetails = project.getBuildDetails();

        // Read potentially edited values from Build tab
        var newBuildLint = buildCleanCommandField.getText();
        var newTestAll = allTestsCommandField.getText();
        var newInstructions = buildInstructionsArea.getText();

        // Create a list from the DefaultListModel for excluded directories
        var newExcludedDirs = new HashSet<String>();
        if (excludedDirectoriesListModel != null) { // Check if initialized (project open)
            for (int i = 0; i < excludedDirectoriesListModel.getSize(); i++) {
                newExcludedDirs.add(excludedDirectoriesListModel.getElementAt(i));
            }
        }

        // Create a new BuildDetails record with updated fields
        var newDetails = new BuildAgent.BuildDetails(currentDetails.buildFiles(),
                                                     currentDetails.dependencies(),
                                                     newBuildLint,
                                                     newTestAll,
                                                     newInstructions,
                                                     newExcludedDirs);
        logger.trace("Applying Build Details: {}", newDetails);

        // Only save if details have actually changed
        if (!newDetails.equals(currentDetails)) {
            project.saveBuildDetails(newDetails);
            logger.debug("Applied Build Details changes.");
        }

        // Apply CPG Refresh Setting (now in Build Tab)
        if (cpgRefreshComboBox != null) {
            var selectedRefresh = (Project.CpgRefresh) cpgRefreshComboBox.getSelectedItem();
            if (selectedRefresh != project.getAnalyzerRefresh()) {
                project.setAnalyzerRefresh(selectedRefresh);
                logger.debug("Applied Code Intelligence Refresh: {}", selectedRefresh);
            }
        }

        // Apply Code Intelligence Languages
        if (currentAnalyzerLanguagesForDialog != null) {
            // Only update if the set of languages has changed
            if (!currentAnalyzerLanguagesForDialog.equals(project.getAnalyzerLanguages())) {
                project.setAnalyzerLanguages(currentAnalyzerLanguagesForDialog);
                logger.debug("Applied Code Intelligence Languages: {}", currentAnalyzerLanguagesForDialog);
                // Consider notifying user about potential analyzer rebuild if behavior changes
            }
        }

        // Apply Code Agent Test Scope (from Build Tab)
        if (runAllTestsRadio != null && runTestsInWorkspaceRadio != null) { // Check initialized
            Project.CodeAgentTestScope selectedScope = runAllTestsRadio.isSelected()
                                                       ? Project.CodeAgentTestScope.ALL
                                                       : Project.CodeAgentTestScope.WORKSPACE;
            if (selectedScope != project.getCodeAgentTestScope()) {
                project.setCodeAgentTestScope(selectedScope);
                logger.debug("Applied Code Agent Test Scope: {}", selectedScope);
            }
        }

        // Apply Style Guide
        var currentStyleGuide = project.getStyleGuide();
        var newStyleGuide = styleGuideArea.getText(); // Get text from the text area
        if (!newStyleGuide.equals(currentStyleGuide)) {
            project.saveStyleGuide(newStyleGuide);
            logger.debug("Applied Style Guide changes.");
        }

        // Apply Commit Message Format
        var currentCommitFormat = project.getCommitMessageFormat();
        var newCommitFormat = commitFormatArea.getText(); // Get text from the text area
        // The setter handles checking for changes and null/blank/default values
        project.setCommitMessageFormat(newCommitFormat);
        if (!newCommitFormat.trim().equals(currentCommitFormat)
                && !newCommitFormat.trim().equals(Project.DEFAULT_COMMIT_MESSAGE_FORMAT)
                && !newCommitFormat.isBlank()) {
            logger.debug("Applied Commit Message Format changes.");
        } else if (!newCommitFormat.trim().equals(currentCommitFormat)
                && (newCommitFormat.isBlank() || newCommitFormat.trim().equals(Project.DEFAULT_COMMIT_MESSAGE_FORMAT))) {
            logger.debug("Reset Commit Message Format to default.");
        }


        // Apply Data Retention Policy
        if (dataRetentionPanel != null) { // Check if the panel was created
            dataRetentionPanel.applyPolicy();
        }

        // Apply Model Selections and Reasoning Levels
        applyModelConfig(project, architectModelComboBox, architectReasoningComboBox,
                         project::getArchitectModelConfig, project::setArchitectModelConfig);

        applyModelConfig(project, codeModelComboBox, codeReasoningComboBox,
                         project::getCodeModelConfig, project::setCodeModelConfig);

        applyModelConfig(project, askModelComboBox, askReasoningComboBox,
                         project::getAskModelConfig, project::setAskModelConfig);


        applyModelConfig(project, searchModelComboBox, searchReasoningComboBox,
                         project::getSearchModelConfig, project::setSearchModelConfig);

        chrome.getContextManager().submitBackgroundTask("Refreshing models due to policy change", () -> {
            chrome.getContextManager().reloadModelsAsync();
        });

        return true; // All settings applied successfully or with non-blocking errors
    }


    /**
     * Helper method to apply model configuration (name and reasoning level).
     */
    private void applyModelConfig(Project project,
                                  JComboBox<String> modelCombo,
                                  JComboBox<Service.ReasoningLevel> reasoningCombo,
                                  java.util.function.Supplier<Service.ModelConfig> currentConfigGetter,
                                  java.util.function.Consumer<Service.ModelConfig> configSetter)
    {
        if (modelCombo == null || reasoningCombo == null) { // Check if combo boxes were initialized
            return;
        }

        String selectedModelName = (String) modelCombo.getSelectedItem();
        Service.ReasoningLevel selectedReasoning = (Service.ReasoningLevel) reasoningCombo.getSelectedItem();

        // Determine the effective reasoning level based on model support
        boolean supportsReasoning = selectedModelName != null && chrome.getContextManager().getService().supportsReasoningEffort(selectedModelName);
        Service.ReasoningLevel effectiveSelectedReasoning = supportsReasoning ? selectedReasoning : Service.ReasoningLevel.DEFAULT;

        Service.ModelConfig currentConfig = currentConfigGetter.get();

        if (selectedModelName != null &&
                (!selectedModelName.equals(currentConfig.name()) || effectiveSelectedReasoning != currentConfig.reasoning())) {
            configSetter.accept(new Service.ModelConfig(selectedModelName, effectiveSelectedReasoning));
        }
    }

    /**
     * Updates the enabled state and selection of a reasoning combo box based on the selected model.
     */
    private void updateReasoningComboBox(JComboBox<String> modelComboBox, JComboBox<Service.ReasoningLevel> reasoningComboBox, Service service) {
        if (modelComboBox == null || reasoningComboBox == null) return; // Not initialized yet

        String selectedModelName = (String) modelComboBox.getSelectedItem();
        boolean supportsReasoning = selectedModelName != null && service.supportsReasoningEffort(selectedModelName);

        reasoningComboBox.setEnabled(supportsReasoning);
        reasoningComboBox.setToolTipText(supportsReasoning ? "Select reasoning effort" : "Reasoning effort not supported by this model");

        if (!supportsReasoning) {
            // Set underlying item to DEFAULT, but render as "Off" when disabled
            reasoningComboBox.setSelectedItem(Service.ReasoningLevel.DEFAULT);
            reasoningComboBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    // For the selected item display in a disabled box (index == -1), show "Off"
                    // Otherwise, show normal enum values for the dropdown list itself.
                    if (index == -1 && !reasoningComboBox.isEnabled()) {
                        label.setText("Off");
                        label.setForeground(UIManager.getColor("ComboBox.disabledForeground")); // Use standard disabled color
                    } else if (value instanceof Service.ReasoningLevel level) {
                        label.setText(level.toString()); // Use standard enum toString for dropdown items
                    } else {
                        label.setText(value == null ? "" : value.toString()); // Handle null or unexpected types
                    }
                    return label;
                }
            });
        } else {
            // Restore default renderer when enabled - needed if switching from non-supported to supported model
            reasoningComboBox.setRenderer(new DefaultListCellRenderer());
            // The actual selection is handled by the initial load or retained from previous state
        }
    }

    /**
     * Displays a standalone, modal dialog forcing the user to choose a data retention policy.
     * This should be called when a project is opened and has no policy set.
     *
     * @param project The project requiring a policy.
     * @param owner   The parent frame for the dialog.
     * @return true if a policy was selected and OK'd, false if cancelled or closed.
     */
    public static boolean showStandaloneDataRetentionDialog(Project project, Frame owner) {
        // This dialog should only be shown if the project's policy is UNSET.
        // If data sharing is disabled by the org, project.getDataRetentionPolicy() will return MINIMAL, not UNSET.
        // Therefore, project.isDataShareAllowed() must be true if this dialog is shown.
        assert project.isDataShareAllowed() : "Standalone data retention dialog should not be shown if data sharing is disabled by organization";

        var dialog = new JDialog(owner, "Data Retention Policy Required", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE); // Prevent closing without selection initially
        dialog.setSize(600, 400);
        dialog.setLocationRelativeTo(owner);

        var retentionPanel = new DataRetentionPanel(project);

        var contentPanel = new JPanel(new BorderLayout(10, 10));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        contentPanel.add(retentionPanel, BorderLayout.CENTER);

        // Add note about changing settings later
        var noteLabel = new JLabel("<html>(You can change this setting later under Project -> Data Retention in the main Settings dialog.)</html>");
        noteLabel.setFont(noteLabel.getFont().deriveFont(Font.PLAIN, noteLabel.getFont().getSize() * 0.9f));
        contentPanel.add(noteLabel, BorderLayout.NORTH);

        var okButton = new JButton("OK");
        var cancelButton = new JButton("Cancel"); // Added Cancel button
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton); // Add Cancel button to panel
        contentPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Holder for the dialog result
        final boolean[] dialogResult = {false}; // Default to false (cancelled)

        okButton.addActionListener(e -> {
            var selectedPolicy = retentionPanel.getSelectedPolicy();
            if (selectedPolicy == DataRetentionPolicy.UNSET) {
                JOptionPane.showMessageDialog(dialog, "Please select a data retention policy to continue.", "Selection Required", JOptionPane.WARNING_MESSAGE);
            } else {
                retentionPanel.applyPolicy();
                dialogResult[0] = true; // Policy selected and applied
                dialog.dispose();
            }
        });

        cancelButton.addActionListener(e -> {
            dialogResult[0] = false; // Cancelled
            dialog.dispose();
        });

        // Handle the window close ('X') button - treat as Cancel
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dialogResult[0] = false; // Cancelled via 'X'
                dialog.dispose();
            }
        });

        dialog.setContentPane(contentPanel);
        okButton.requestFocusInWindow(); // Request focus on OK button
        dialog.setVisible(true); // Show the modal dialog and block until closed

        return dialogResult[0]; // Return the outcome
    }

    /**
     * Opens the Settings dialog, optionally pre‑selecting an inner tab (e.g. “Models”)
     * inside the Project settings.  If the requested tab is not found—or no project
     * is open—the dialog falls back to its default view.
     */
    public static void showSettingsDialog(Chrome chrome, String targetTabName) {
        var dialog = new SettingsDialog(chrome.getFrame(), chrome);

        if (targetTabName != null) {
            boolean tabSelected = false;

            // Determine if target is a Global sub-tab
            boolean isGlobalSubTab = "Service".equals(targetTabName) ||
                    "Appearance".equals(targetTabName) ||
                    MODELS_TAB.equals(targetTabName);

            // Determine if target is a Project sub-tab
            boolean isProjectSubTab = "General".equals(targetTabName) ||
                    "Build".equals(targetTabName) ||
                    "Data Retention".equals(targetTabName);

            if (isGlobalSubTab) {
                // Select "Global" top-level tab first
                for (int i = 0; i < dialog.tabbedPane.getTabCount(); i++) {
                    if ("Global".equals(dialog.tabbedPane.getTitleAt(i))) {
                        if (dialog.tabbedPane.isEnabledAt(i)) {
                            dialog.tabbedPane.setSelectedIndex(i);
                            // Now select the sub-tab
                            Component globalTabContent = dialog.tabbedPane.getComponentAt(i);
                            if (globalTabContent instanceof JTabbedPane globalSubTabbedPane) {
                                for (int j = 0; j < globalSubTabbedPane.getTabCount(); j++) {
                                    if (targetTabName.equals(globalSubTabbedPane.getTitleAt(j))) {
                                        // The "Models" sub-tab's content might be disabled, but the tab itself is selectable.
                                        // Its content (the panel from createModelsPanel) handles its own enabled state.
                                        globalSubTabbedPane.setSelectedIndex(j);
                                        tabSelected = true;
                                        break;
                                    }
                                }
                            }
                        } else {
                            logger.warn("Top-level 'Global' tab is unexpectedly disabled.");
                        }
                        break;
                    }
                }
            } else if (isProjectSubTab && chrome.getProject() != null) {
                // Select "Project" top-level tab first
                for (int i = 0; i < dialog.tabbedPane.getTabCount(); i++) {
                    if ("Project".equals(dialog.tabbedPane.getTitleAt(i))) {
                        if (dialog.tabbedPane.isEnabledAt(i)) {
                            dialog.tabbedPane.setSelectedIndex(i);
                            // Now select the sub-tab within Project
                            // dialog.projectPanel is the root panel for the "Project" tab.
                            Component projectTabContent = dialog.projectPanel;
                            if (projectTabContent instanceof JPanel) {
                                for (var comp : ((JPanel) projectTabContent).getComponents()) {
                                    if (comp instanceof JTabbedPane subTabbedPane) {
                                        for (int j = 0; j < subTabbedPane.getTabCount(); j++) {
                                            if (targetTabName.equals(subTabbedPane.getTitleAt(j))) {
                                                if (subTabbedPane.isEnabledAt(j)) {
                                                    subTabbedPane.setSelectedIndex(j);
                                                    tabSelected = true;
                                                } else {
                                                    logger.warn("Project sub-tab '{}' is disabled.", targetTabName);
                                                }
                                                break;
                                            }
                                        }
                                        break; // Found the inner JTabbedPane
                                    }
                                }
                            }
                        } else {
                            logger.warn("Top-level 'Project' tab is disabled, cannot select sub-tab '{}'.", targetTabName);
                        }
                        break;
                    }
                }
            } else {
                // Try to select a top-level tab directly (e.g., if targetTabName is "Global" or "Project")
                for (int i = 0; i < dialog.tabbedPane.getTabCount(); i++) {
                    if (targetTabName.equals(dialog.tabbedPane.getTitleAt(i))) {
                        if (dialog.tabbedPane.isEnabledAt(i)) {
                            dialog.tabbedPane.setSelectedIndex(i);
                            tabSelected = true;
                        } else {
                            logger.warn("Target tab '{}' is disabled, cannot select.", targetTabName);
                        }
                        break;
                    }
                }
            }

            if (!tabSelected) {
                logger.warn("Could not find or select target settings tab: {}", targetTabName);
            }
        }
        dialog.setVisible(true); // show the modal dialog
    }

    // --- Inner Classes for Quick Models Table ---

    /**
     * TableModel for managing FavoriteModel data.
     */
    private static class FavoriteModelsTableModel extends AbstractTableModel {
        private final List<Service.FavoriteModel> favorites;
        private final String[] columnNames = {"Alias", "Model Name", "Reasoning"};

        public FavoriteModelsTableModel(List<Service.FavoriteModel> initialFavorites) {
            // Work on a mutable copy
            this.favorites = new ArrayList<>(initialFavorites);
        }

        public List<Service.FavoriteModel> getFavorites() {
            // Return an immutable copy or the direct list depending on desired behavior outside the dialog
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
                case 0 -> String.class; // Alias
                case 1 -> String.class; // Model Name (uses JComboBox editor)
                case 2 -> Service.ReasoningLevel.class; // Reasoning (uses JComboBox editor)
                default -> Object.class;
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true; // All cells are editable
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Service.FavoriteModel favorite = favorites.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> favorite.alias();
                case 1 -> favorite.modelName();
                case 2 -> favorite.reasoning();
                default -> null;
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (rowIndex < 0 || rowIndex >= favorites.size()) return;

            Service.FavoriteModel oldFavorite = favorites.get(rowIndex);
            Service.FavoriteModel newFavorite = oldFavorite; // Start with old values

            try {
                switch (columnIndex) {
                    case 0: // Alias
                        if (aValue instanceof String alias) {
                            newFavorite = new Service.FavoriteModel(alias.trim(), oldFavorite.modelName(), oldFavorite.reasoning());
                        }
                        break;
                    case 1: // Model Name
                        if (aValue instanceof String modelName) {
                            // If model changes, potentially reset reasoning if new model doesn't support the old level (though editor should handle this)
                            // For simplicity, we just update the model name here. The editor/renderer handles enabling.
                            newFavorite = new Service.FavoriteModel(oldFavorite.alias(), modelName, oldFavorite.reasoning());
                        }
                        break;
                    case 2: // Reasoning
                        if (aValue instanceof Service.ReasoningLevel reasoning) {
                            newFavorite = new Service.FavoriteModel(oldFavorite.alias(), oldFavorite.modelName(), reasoning);
                        }
                        break;
                }
            } catch (Exception e) {
                logger.error("Error setting value at ({}, {}) to {}", rowIndex, columnIndex, aValue, e);
                return; // Prevent saving invalid state
            }


            // Only update if the favorite actually changed
            if (!newFavorite.equals(oldFavorite)) {
                favorites.set(rowIndex, newFavorite);
                // Notify listeners about the change in the specific cell and potentially related cells (like reasoning if model changed)
                fireTableCellUpdated(rowIndex, columnIndex);
                if (columnIndex == 1) { // If model changed, reasoning display might need update
                    fireTableCellUpdated(rowIndex, 2);
                }
            }
        }
    }

    /**
     * Custom cell renderer for the Reasoning column.
     * Displays "Off" if the model in the same row doesn't support reasoning.
     */
    private static class ReasoningCellRenderer extends DefaultTableCellRenderer {
        private final Service models;
        private final JTable table;

        public ReasoningCellRenderer(Service service, JTable table) {
            this.models = service;
            this.table = table;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column)
        {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            // Get the model name from the 'Model Name' column (index 1) in the current row
            String modelName = (String) table.getModel().getValueAt(row, 1);

            if (modelName != null && !models.supportsReasoningEffort(modelName)) {
                label.setText("Off");
                label.setEnabled(false); // Visually indicate disabled state
                label.setToolTipText("Reasoning effort not supported by " + modelName);
            } else if (value instanceof Service.ReasoningLevel level) {
                label.setText(level.toString());
                label.setEnabled(true);
                label.setToolTipText("Select reasoning effort");
            } else {
                label.setText(value == null ? "" : value.toString()); // Handle null or unexpected types
                label.setEnabled(true); // Default to enabled if model support is unknown or value is wrong type
                label.setToolTipText(null);
            }

            // Ensure background/foreground colors are correct for selection/non-selection
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


    /**
     * Custom cell editor for the Reasoning column.
     * Disables the ComboBox if the model in the same row doesn't support reasoning.
     */
    private static class ReasoningCellEditor extends DefaultCellEditor {
        private final Service models;
        private final JTable table;
        private final JComboBox<Service.ReasoningLevel> comboBox; // Keep reference to the actual combo

        public ReasoningCellEditor(JComboBox<Service.ReasoningLevel> comboBox, Service service, JTable table) {
            super(comboBox);
            this.comboBox = comboBox; // Store the combo box instance
            this.models = service;
            this.table = table;
            setClickCountToStart(1); // Start editing on a single click
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            // Get the model name from the 'Model Name' column (index 1)
            String modelName = (String) table.getModel().getValueAt(row, 1);
            boolean supportsReasoning = modelName != null && models.supportsReasoningEffort(modelName);

            // Enable/disable the editor component (the ComboBox)
            Component editorComponent = super.getTableCellEditorComponent(table, value, isSelected, row, column);
            editorComponent.setEnabled(supportsReasoning);
            comboBox.setEnabled(supportsReasoning); // Explicitly enable/disable the combo

            if (!supportsReasoning) {
                // If not supported, ensure the displayed value is DEFAULT (rendered as "Off")
                comboBox.setSelectedItem(Service.ReasoningLevel.DEFAULT);
                comboBox.setToolTipText("Reasoning effort not supported by " + modelName);
                // Use the same renderer logic as the cell renderer for consistency
                comboBox.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                        JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                        if (index == -1) { // Display value in the editor box when disabled
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
                comboBox.setToolTipText("Select reasoning effort");
                // Restore default renderer when enabled
                comboBox.setRenderer(new DefaultListCellRenderer());
                // Set the actual value from the model
                comboBox.setSelectedItem(value);
            }

            return editorComponent;
        }

        @Override
        public boolean isCellEditable(java.util.EventObject anEvent) {
            // Prevent editing if reasoning is not supported by the model in this row
            if (table != null) {
                int editingRow = table.getEditingRow();
                if (editingRow != -1) {
                    String modelName = (String) table.getModel().getValueAt(editingRow, 1);
                    return modelName != null && models.supportsReasoningEffort(modelName);
                }
            }
            // Default behavior if table/row info isn't available (shouldn't happen in normal use)
            return super.isCellEditable(anEvent);
        }

        @Override
        public Object getCellEditorValue() {
            // If the editor was disabled, return DEFAULT, otherwise return the selected item.
            return comboBox.isEnabled() ? super.getCellEditorValue() : Service.ReasoningLevel.DEFAULT;
        }
    }


}
