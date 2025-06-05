package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.MainProject.DataRetentionPolicy;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

public class SettingsProjectPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(SettingsProjectPanel.class);
    private static final int BUILD_TAB_INDEX = 1; // General(0), Build(1), Data Retention(2)

    private final Chrome chrome;
    private final SettingsDialog parentDialog;

    // UI Components managed by this panel
    private JComboBox<MainProject.CpgRefresh> cpgRefreshComboBox;
    private JTextField buildCleanCommandField;
    private JTextField allTestsCommandField;
    private JTextField someTestsCommandField; // Added for testSomeCommand
    private DataRetentionPanel dataRetentionPanelInner; // Renamed to avoid conflict if outer is also named this
    private JTextArea styleGuideArea;
    private JTextArea commitFormatArea;
    // buildInstructionsArea and instructionsScrollPane removed
    private JList<String> excludedDirectoriesList;
    private DefaultListModel<String> excludedDirectoriesListModel;
    private JScrollPane excludedScrollPane;
    private JButton addExcludedDirButton;
    private JButton removeExcludedDirButton;
    private JTextField languagesDisplayField;
    private JButton editLanguagesButton;
    private Set<io.github.jbellis.brokk.analyzer.Language> currentAnalyzerLanguagesForDialog;
    private JRadioButton runAllTestsRadio;
    private JRadioButton runTestsInWorkspaceRadio;
    private JProgressBar buildProgressBar;
    private JButton rerunBuildButton; // Added to manage state during build agent run

    // Buttons from parent dialog that might need to be disabled/enabled by build agent
    private final JButton okButtonParent;
    private final JButton cancelButtonParent;
    private final JButton applyButtonParent;
    private JTabbedPane projectSubTabbedPane;


    public SettingsProjectPanel(Chrome chrome, SettingsDialog parentDialog, JButton okButton, JButton cancelButton, JButton applyButton) {
        this.chrome = chrome;
        this.parentDialog = parentDialog;
        this.okButtonParent = okButton;
        this.cancelButtonParent = cancelButton;
        this.applyButtonParent = applyButton;

        setLayout(new BorderLayout());
        initComponents();
        loadSettings(); // Load settings after components are initialized
    }

    private void initComponents() {
        var project = chrome.getProject();
        if (project == null) {
            add(new JLabel("No project is open. Project settings are unavailable."), BorderLayout.CENTER);
            this.setEnabled(false); // Disable the whole panel
            return;
        }
        this.setEnabled(true); // Ensure panel is enabled if project exists

        projectSubTabbedPane = new JTabbedPane(JTabbedPane.TOP);

        // General Tab (formerly Other)
        var generalPanel = createGeneralPanel();
        projectSubTabbedPane.addTab("General", null, generalPanel, "General project settings");

        // Build Tab
        var buildPanel = createBuildPanel(project);
        projectSubTabbedPane.addTab("Build", null, buildPanel, "Build configuration and Code Intelligence settings");

        // Data Retention Tab
        dataRetentionPanelInner = new DataRetentionPanel(project, this);
        projectSubTabbedPane.addTab("Data Retention", null, dataRetentionPanelInner, "Data retention policy for this project");

        add(projectSubTabbedPane, BorderLayout.CENTER);

        // Handle initial loading state for Build Details
        if (!project.hasBuildDetails()) {
            projectSubTabbedPane.setEnabledAt(BUILD_TAB_INDEX, false);
            if (buildProgressBar != null) {
                buildProgressBar.setVisible(true);
            }
            if (rerunBuildButton != null) {
                rerunBuildButton.setEnabled(false);
            }

            project.getBuildDetailsFuture().whenCompleteAsync((detailsResult, ex) -> {
                SwingUtilities.invokeLater(() -> {
                    projectSubTabbedPane.setEnabledAt(BUILD_TAB_INDEX, true);
                    if (buildProgressBar != null) {
                        buildProgressBar.setVisible(false);
                    }
                    if (rerunBuildButton != null) {
                        rerunBuildButton.setEnabled(true);
                    }

                    if (ex != null) {
                        logger.error("Initial build details determination failed", ex);
                        chrome.toolErrorRaw("Failed to determine initial build details: " + ex.getMessage());
                    } else {
                        if (detailsResult == BuildAgent.BuildDetails.EMPTY) {
                            logger.warn("Initial Build Agent returned empty details. Using defaults.");
                            chrome.systemOutput("Initial Build Agent completed but found no specific details. Using defaults.");
                        } else {
                            logger.info("Initial build details determined successfully.");
                            chrome.systemOutput("Initial build details determined. Settings panel updated.");
                        }
                    }
                    loadBuildPanelSettings(); // Load settings for the build panel now
                });
            }, ForkJoinPool.commonPool());
        } else { // Project exists and details are already available
            if (buildProgressBar != null) {
                buildProgressBar.setVisible(false);
            }
            if (rerunBuildButton != null) {
                rerunBuildButton.setEnabled(true);
            }
        }
    }

    public JTabbedPane getProjectSubTabbedPane() {
        return projectSubTabbedPane;
    }

    private JPanel createGeneralPanel() {
        var generalPanel = new JPanel(new GridBagLayout());
        generalPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST; gbc.fill = GridBagConstraints.NONE;
        generalPanel.add(new JLabel("Style Guide:"), gbc);
        styleGuideArea = new JTextArea(5, 40);
        styleGuideArea.setWrapStyleWord(true); styleGuideArea.setLineWrap(true);
        var styleScrollPane = new JScrollPane(styleGuideArea);
        styleScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        styleScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0; gbc.weighty = 0.5; gbc.fill = GridBagConstraints.BOTH;
        generalPanel.add(styleScrollPane, gbc);

        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0; gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.anchor = GridBagConstraints.NORTHWEST;
        var styleGuideInfo = new JLabel("<html>The Style Guide is used by the Code Agent to help it conform to your project's style.</html>");
        styleGuideInfo.setFont(styleGuideInfo.getFont().deriveFont(Font.ITALIC, styleGuideInfo.getFont().getSize() * 0.9f));
        gbc.insets = new Insets(0, 2, 8, 2);
        generalPanel.add(styleGuideInfo, gbc);

        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST; gbc.fill = GridBagConstraints.NONE;
        generalPanel.add(new JLabel("Commit Format:"), gbc);
        commitFormatArea = new JTextArea(5, 40);
        commitFormatArea.setWrapStyleWord(true); commitFormatArea.setLineWrap(true);
        var commitFormatScrollPane = new JScrollPane(commitFormatArea);
        commitFormatScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commitFormatScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        var commitFormatPanel = new JPanel(new BorderLayout(5, 0));
        commitFormatPanel.add(commitFormatScrollPane, BorderLayout.CENTER);
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0; gbc.weighty = 0.5; gbc.fill = GridBagConstraints.BOTH;
        generalPanel.add(commitFormatPanel, gbc);

        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0; gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.anchor = GridBagConstraints.NORTHWEST;
        var commitFormatInfo = new JLabel("<html>This informs the LLM how to structure the commit message suggestions it makes.</html>");
        commitFormatInfo.setFont(commitFormatInfo.getFont().deriveFont(Font.ITALIC, commitFormatInfo.getFont().getSize() * 0.9f));
        gbc.insets = new Insets(0, 2, 2, 2);
        generalPanel.add(commitFormatInfo, gbc);

        gbc.weighty = 0.0; // Reset for any future components
        gbc.gridy = row;
        gbc.gridx = 0; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.VERTICAL; gbc.weighty = 1.0; // Add glue to push content up
        generalPanel.add(Box.createVerticalGlue(), gbc);


        return generalPanel;
    }

    private JPanel createBuildPanel(IProject project) {
        var buildPanel = new JPanel(new GridBagLayout());
        buildPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        buildCleanCommandField = new JTextField();
        allTestsCommandField = new JTextField();
        someTestsCommandField = new JTextField(); // Added for testSomeCommand

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        buildPanel.add(new JLabel("Build/Lint Command:"), gbc);
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0;
        buildPanel.add(buildCleanCommandField, gbc);

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        buildPanel.add(new JLabel("Test All Command:"), gbc);
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0;
        buildPanel.add(allTestsCommandField, gbc);

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        buildPanel.add(new JLabel("Test Some Command:"), gbc);
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0;
        buildPanel.add(someTestsCommandField, gbc);
        var testSomeInfo = new JLabel("<html>Use a placeholder like {{FILE_OR_CLASS_PATH}} for the part that will be replaced.</html>");
        testSomeInfo.setFont(testSomeInfo.getFont().deriveFont(Font.ITALIC, testSomeInfo.getFont().getSize() * 0.9f));
        gbc.gridx = 1; gbc.gridy = row++; gbc.insets = new Insets(0, 2, 8, 2);
        buildPanel.add(testSomeInfo, gbc);
        gbc.insets = new Insets(2, 2, 2, 2); // Reset insets

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.WEST; gbc.fill = GridBagConstraints.NONE;
        buildPanel.add(new JLabel("Code Agent Tests:"), gbc);
        runAllTestsRadio = new JRadioButton(IProject.CodeAgentTestScope.ALL.toString());
        runTestsInWorkspaceRadio = new JRadioButton(IProject.CodeAgentTestScope.WORKSPACE.toString());
        var testScopeGroup = new ButtonGroup();
        testScopeGroup.add(runAllTestsRadio); testScopeGroup.add(runTestsInWorkspaceRadio);
        var radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        radioPanel.setOpaque(false); radioPanel.add(runAllTestsRadio); radioPanel.add(runTestsInWorkspaceRadio);
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        buildPanel.add(radioPanel, gbc);

        // Removed Build Instructions Area and its ScrollPane

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0; gbc.weighty = 0.0; // Ensure weighty is reset before this
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.anchor = GridBagConstraints.WEST;
        buildPanel.add(new JLabel("CI Refresh:"), gbc);
        cpgRefreshComboBox = new JComboBox<>(new MainProject.CpgRefresh[]{IProject.CpgRefresh.AUTO, IProject.CpgRefresh.ON_RESTART, IProject.CpgRefresh.MANUAL});
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0;
        buildPanel.add(cpgRefreshComboBox, gbc);

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.WEST;
        buildPanel.add(new JLabel("CI Languages:"), gbc);
        languagesDisplayField = new JTextField(20); languagesDisplayField.setEditable(false);
        currentAnalyzerLanguagesForDialog = new HashSet<>(); // Initialized in loadSettings
        this.editLanguagesButton = new JButton("Edit");
        this.editLanguagesButton.addActionListener(e -> showLanguagesDialog(project));
        var languagesPanel = new JPanel(new BorderLayout(5, 0));
        languagesPanel.add(languagesDisplayField, BorderLayout.CENTER); languagesPanel.add(this.editLanguagesButton, BorderLayout.EAST);
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        buildPanel.add(languagesPanel, gbc);

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.NORTHWEST;
        buildPanel.add(new JLabel("CI Exclusions:"), gbc);
        excludedDirectoriesListModel = new DefaultListModel<>();
        excludedDirectoriesList = new JList<>(excludedDirectoriesListModel); excludedDirectoriesList.setVisibleRowCount(3);
        this.excludedScrollPane = new JScrollPane(excludedDirectoriesList);
        gbc.gridx = 1; gbc.gridy = row; gbc.weightx = 1.0; gbc.weighty = 0.5; gbc.fill = GridBagConstraints.BOTH;
        buildPanel.add(this.excludedScrollPane, gbc);

        var excludedButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        this.addExcludedDirButton = new JButton("Add"); this.removeExcludedDirButton = new JButton("Remove");
        excludedButtonsPanel.add(this.addExcludedDirButton); excludedButtonsPanel.add(this.removeExcludedDirButton);
        gbc.gridy = row + 1; gbc.weighty = 0.0; gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST; gbc.insets = new Insets(2, 0, 2, 2);
        buildPanel.add(excludedButtonsPanel, gbc);
        row += 2; gbc.insets = new Insets(2, 2, 2, 2);

        this.addExcludedDirButton.addActionListener(e -> {
            String newDir = JOptionPane.showInputDialog(parentDialog, "Enter directory to exclude (e.g., target/, build/):", "Add Excluded Directory", JOptionPane.PLAIN_MESSAGE);
            if (newDir != null && !newDir.trim().isEmpty()) {
                String trimmedNewDir = newDir.trim();
                excludedDirectoriesListModel.addElement(trimmedNewDir);
                var elements = new ArrayList<String>();
                for (int i = 0; i < excludedDirectoriesListModel.getSize(); i++) elements.add(excludedDirectoriesListModel.getElementAt(i));
                elements.sort(String::compareToIgnoreCase);
                excludedDirectoriesListModel.clear();
                for (String element : elements) excludedDirectoriesListModel.addElement(element);
            }
        });
        this.removeExcludedDirButton.addActionListener(e -> {
            int[] selectedIndices = excludedDirectoriesList.getSelectedIndices();
            for (int i = selectedIndices.length - 1; i >= 0; i--) excludedDirectoriesListModel.removeElementAt(selectedIndices[i]);
        });

        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 0.0; gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.EAST;
        rerunBuildButton = new JButton("Run Build Agent"); // Assign to field
        buildPanel.add(rerunBuildButton, gbc);

        buildProgressBar = new JProgressBar(); buildProgressBar.setIndeterminate(true); buildProgressBar.setVisible(false);
        gbc.gridx = 1; gbc.gridy = row++; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.anchor = GridBagConstraints.EAST;
        buildPanel.add(buildProgressBar, gbc);

        rerunBuildButton.addActionListener(e -> runBuildAgent());
        
        // Vertical glue to push all build panel content up
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.weighty = 1.0; gbc.fill = GridBagConstraints.VERTICAL;
        buildPanel.add(Box.createVerticalGlue(), gbc);


        return buildPanel;
    }

    private void runBuildAgent() {
        var cm = chrome.getContextManager();
        var proj = chrome.getProject();
        if (proj == null) {
            chrome.toolErrorRaw("No project is open.");
            return;
        }

        setBuildControlsEnabled(false); // Disable controls in this panel
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
                        JOptionPane.showMessageDialog(parentDialog, errorMessage, "Build Agent Error", JOptionPane.ERROR_MESSAGE);
                    });
                } else {
                    // Do not save here, only update UI fields. applySettings will save.
                    SwingUtilities.invokeLater(() -> {
                        updateBuildDetailsFieldsFromAgent(newBuildDetails);
                        chrome.systemOutput("Build Agent finished. Review and apply settings.");
                    });
                }
            } catch (Exception ex) {
                logger.error("Error running Build Agent", ex);
                SwingUtilities.invokeLater(() -> {
                    String errorMessage = "Build Agent failed: " + ex.getMessage();
                    chrome.toolErrorRaw(errorMessage);
                    JOptionPane.showMessageDialog(parentDialog, errorMessage, "Build Agent Error", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    setBuildControlsEnabled(true); // Re-enable controls in this panel
                    rerunBuildButton.setEnabled(true);
                });
            }
        });
    }


    private void setBuildControlsEnabled(boolean enabled) {
        boolean effectiveEnabled = enabled; // Project must be open for this panel to be visible/active

        buildProgressBar.setVisible(!enabled);

        List<Component> controlsToManage = new ArrayList<>(List.of(
                buildCleanCommandField, allTestsCommandField, someTestsCommandField,
                runAllTestsRadio, runTestsInWorkspaceRadio,
                // instructionsScrollPane, buildInstructionsArea removed
                cpgRefreshComboBox,
                editLanguagesButton,
                excludedScrollPane, excludedDirectoriesList,
                addExcludedDirButton, removeExcludedDirButton
                // Note: rerunBuildButton is handled separately by its action listener
        ));
        // Add parent dialog buttons if they were passed
        if (okButtonParent != null) controlsToManage.add(okButtonParent);
        if (cancelButtonParent != null) controlsToManage.add(cancelButtonParent);
        if (applyButtonParent != null) controlsToManage.add(applyButtonParent);


        for (Component control : controlsToManage) {
            if (control != null) control.setEnabled(effectiveEnabled);
        }
    }

    private void updateBuildDetailsFieldsFromAgent(BuildAgent.BuildDetails details) {
        if (details == null) return;
        SwingUtilities.invokeLater(() -> {
            buildCleanCommandField.setText(details.buildLintCommand());
            allTestsCommandField.setText(details.testAllCommand());
            someTestsCommandField.setText(details.testSomeCommand());
            excludedDirectoriesListModel.clear();
            var sortedExcludedDirs = details.excludedDirectories().stream().sorted().toList();
            for (String dir : sortedExcludedDirs) excludedDirectoriesListModel.addElement(dir);
            logger.trace("UI fields updated with new BuildDetails from agent: {}", details);
        });
    }

    private void updateLanguagesDisplayField() {
        if (languagesDisplayField == null || currentAnalyzerLanguagesForDialog == null) return;
        String cdl = currentAnalyzerLanguagesForDialog.stream()
                .map(lang -> lang.name())
                .sorted()
                .collect(Collectors.joining(", "));
        languagesDisplayField.setText(cdl.isEmpty() ? "None" : cdl);
    }

    private void showLanguagesDialog(IProject project) {
        var dialog = new JDialog(parentDialog, "Select Languages for Analysis", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(300, 400);
        dialog.setLocationRelativeTo(parentDialog);

        var languageCheckBoxMapLocal = new java.util.LinkedHashMap<io.github.jbellis.brokk.analyzer.Language, JCheckBox>();
        var languagesInProject = new HashSet<io.github.jbellis.brokk.analyzer.Language>();
        if (project.getRoot() != null) {
            Set<io.github.jbellis.brokk.analyzer.ProjectFile> filesToScan = project.hasGit() ? project.getRepo().getTrackedFiles() : project.getAllFiles();
            for (var pf : filesToScan) {
                String extension = com.google.common.io.Files.getFileExtension(pf.absPath().toString());
                if (!extension.isEmpty()) {
                    var lang = io.github.jbellis.brokk.analyzer.Language.fromExtension(extension);
                    if (lang != io.github.jbellis.brokk.analyzer.Language.NONE) languagesInProject.add(lang);
                }
            }
        }

        var checkBoxesPanel = new JPanel();
        checkBoxesPanel.setLayout(new BoxLayout(checkBoxesPanel, BoxLayout.PAGE_AXIS));
        checkBoxesPanel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        var sortedLanguagesToShow = languagesInProject.stream().sorted(java.util.Comparator.comparing(io.github.jbellis.brokk.analyzer.Language::name)).toList();
        for (var lang : sortedLanguagesToShow) {
            var checkBox = new JCheckBox(lang.name());
            checkBox.setSelected(currentAnalyzerLanguagesForDialog.contains(lang));
            languageCheckBoxMapLocal.put(lang, checkBox);
            checkBoxesPanel.add(checkBox);
        }
        if (sortedLanguagesToShow.isEmpty()) checkBoxesPanel.add(new JLabel("No analyzable languages detected."));

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okBtn = new JButton("OK"); var cancelBtn = new JButton("Cancel");
        buttonPanel.add(okBtn); buttonPanel.add(cancelBtn);
        okBtn.addActionListener(e -> {
            currentAnalyzerLanguagesForDialog.clear();
            for (var entry : languageCheckBoxMapLocal.entrySet()) if (entry.getValue().isSelected()) currentAnalyzerLanguagesForDialog.add(entry.getKey());
            updateLanguagesDisplayField();
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());
        dialog.add(new JScrollPane(checkBoxesPanel), BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    public void loadSettings() {
        var project = chrome.getProject();
        if (project == null) return; // Panel is disabled if no project

        // General Tab
        styleGuideArea.setText(project.getStyleGuide());
        commitFormatArea.setText(project.getCommitMessageFormat());

        // Build Tab - Load settings only if details are available
        // If not available, the whenCompleteAsync callback from initComponents will call loadBuildPanelSettings
        if (project.hasBuildDetails()) {
            loadBuildPanelSettings();
        }

        // Data Retention Tab
        if (dataRetentionPanelInner != null) dataRetentionPanelInner.loadPolicy();
    }

    private void loadBuildPanelSettings() {
        var project = chrome.getProject();
        if (project == null) return; // Should not happen if panel is active

        BuildAgent.BuildDetails details;
        try {
            // This call is now safe as it's guarded by hasBuildDetails() or called after awaitBuildDetails()
            details = project.loadBuildDetails();
        } catch (Exception e) {
            logger.warn("Could not load build details for settings panel, using EMPTY. Error: {}", e.getMessage(), e);
            details = BuildAgent.BuildDetails.EMPTY; // Fallback to EMPTY
            chrome.toolErrorRaw("Error loading build details: " + e.getMessage() + ". Using defaults.");
        }

        buildCleanCommandField.setText(details.buildLintCommand());
        allTestsCommandField.setText(details.testAllCommand());
        someTestsCommandField.setText(details.testSomeCommand());

        if (project.getCodeAgentTestScope() == IProject.CodeAgentTestScope.ALL) {
            runAllTestsRadio.setSelected(true);
        } else {
            runTestsInWorkspaceRadio.setSelected(true);
        }

        var currentRefresh = project.getAnalyzerRefresh();
        cpgRefreshComboBox.setSelectedItem(currentRefresh == IProject.CpgRefresh.UNSET ? IProject.CpgRefresh.AUTO : currentRefresh);

        currentAnalyzerLanguagesForDialog = new HashSet<>(project.getAnalyzerLanguages());
        updateLanguagesDisplayField();

        excludedDirectoriesListModel.clear();
        var sortedExcludedDirs = details.excludedDirectories().stream().sorted().toList();
        for (String dir : sortedExcludedDirs) {
            excludedDirectoriesListModel.addElement(dir);
        }
        logger.trace("Build panel settings loaded/reloaded with details: {}", details);
    }

    public boolean applySettings() {
        var project = chrome.getProject();
        if (project == null) return true; // No project, nothing to apply

        // General Tab
        project.saveStyleGuide(styleGuideArea.getText());
        project.setCommitMessageFormat(commitFormatArea.getText());

        // Build Tab
        var currentDetails = project.loadBuildDetails();
        var newBuildLint = buildCleanCommandField.getText();
        var newTestAll = allTestsCommandField.getText();
        var newTestSome = someTestsCommandField.getText();
        // buildInstructionsArea removed

        var newExcludedDirs = new HashSet<String>();
        for (int i = 0; i < excludedDirectoriesListModel.getSize(); i++) newExcludedDirs.add(excludedDirectoriesListModel.getElementAt(i));

        var newDetails = new BuildAgent.BuildDetails(newBuildLint, newTestAll, newTestSome, newExcludedDirs);
        if (!newDetails.equals(currentDetails)) {
            project.saveBuildDetails(newDetails);
            logger.debug("Applied Build Details changes.");
        }

        MainProject.CodeAgentTestScope selectedScope = runAllTestsRadio.isSelected() ? IProject.CodeAgentTestScope.ALL : IProject.CodeAgentTestScope.WORKSPACE;
        if (selectedScope != project.getCodeAgentTestScope()) {
            project.setCodeAgentTestScope(selectedScope);
            logger.debug("Applied Code Agent Test Scope: {}", selectedScope);
        }

        var selectedRefresh = (MainProject.CpgRefresh) cpgRefreshComboBox.getSelectedItem();
        if (selectedRefresh != project.getAnalyzerRefresh()) {
            project.setAnalyzerRefresh(selectedRefresh);
            logger.debug("Applied Code Intelligence Refresh: {}", selectedRefresh);
        }

        if (!currentAnalyzerLanguagesForDialog.equals(project.getAnalyzerLanguages())) {
            project.setAnalyzerLanguages(currentAnalyzerLanguagesForDialog);
            logger.debug("Applied Code Intelligence Languages: {}", currentAnalyzerLanguagesForDialog);
        }

        // Data Retention Tab
        if (dataRetentionPanelInner != null) dataRetentionPanelInner.applyPolicy();
        
        // After applying data retention, model list might need refresh
        chrome.getContextManager().submitBackgroundTask("Refreshing models due to policy change", () -> {
            chrome.getContextManager().reloadModelsAsync();
        });


        return true;
    }
    
    public void refreshDataRetentionPanel() {
        if (dataRetentionPanelInner != null) {
            dataRetentionPanelInner.refreshStateAndUI();
        }
    }


    @Override
    public void applyTheme(GuiTheme guiTheme) {
        SwingUtilities.updateComponentTreeUI(this);
    }

    // Static inner class DataRetentionPanel (Copied and adapted from SettingsDialog)
    public static class DataRetentionPanel extends JPanel {
        private final IProject project;
        private final SettingsProjectPanel parentProjectPanel; // For triggering model refresh
        private final ButtonGroup policyGroup;
        private final JRadioButton improveRadio;
        private final JLabel improveDescLabel;
        private final JRadioButton minimalRadio;
        private final JLabel minimalDescLabel;
        private final JLabel orgDisabledLabel;
        private final JLabel infoLabel;

        public DataRetentionPanel(IProject project, SettingsProjectPanel parentProjectPanel) {
            super(new GridBagLayout());
            this.project = project;
            this.parentProjectPanel = parentProjectPanel;
            this.policyGroup = new ButtonGroup();

            setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            var gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;

            improveRadio = new JRadioButton(DataRetentionPolicy.IMPROVE_BROKK.getDisplayName());
            improveRadio.putClientProperty("policy", DataRetentionPolicy.IMPROVE_BROKK);
            policyGroup.add(improveRadio);
            improveDescLabel = new JLabel("<html>Allow Brokk and/or its partners to use requests from this project to train models and improve the Brokk service.</html>");
            improveDescLabel.setFont(improveDescLabel.getFont().deriveFont(Font.ITALIC, improveDescLabel.getFont().getSize() * 0.9f));

            minimalRadio = new JRadioButton(DataRetentionPolicy.MINIMAL.getDisplayName());
            minimalRadio.putClientProperty("policy", DataRetentionPolicy.MINIMAL);
            policyGroup.add(minimalRadio);
            minimalDescLabel = new JLabel("<html>Brokk will not share data from this project with anyone and will restrict its use to the minimum necessary to provide the Brokk service.</html>");
            minimalDescLabel.setFont(minimalDescLabel.getFont().deriveFont(Font.ITALIC, minimalDescLabel.getFont().getSize() * 0.9f));

            orgDisabledLabel = new JLabel("<html><b>Data sharing is disabled by your organization.</b></html>");
            infoLabel = new JLabel("<html>Data retention policy affects which AI models are allowed. In particular, Deepseek models are not available under the Essential Use Only policy, since Deepseek will train on API requests independently of Brokk.</html>");
            infoLabel.setFont(infoLabel.getFont().deriveFont(infoLabel.getFont().getSize() * 0.9f));

            layoutControls();
            loadPolicy();
        }

        private void layoutControls() {
            removeAll();
            var gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5); gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            int y = 0;
            boolean dataSharingAllowedByOrg = project.isDataShareAllowed();

            if (dataSharingAllowedByOrg) {
                gbc.gridx = 0; gbc.gridy = y++; gbc.insets = new Insets(5, 5, 0, 5); add(improveRadio, gbc); improveRadio.setVisible(true);
                gbc.gridx = 0; gbc.gridy = y++; gbc.insets = new Insets(0, 25, 10, 5); add(improveDescLabel, gbc); improveDescLabel.setVisible(true);
                gbc.gridx = 0; gbc.gridy = y++; gbc.insets = new Insets(5, 5, 0, 5); add(minimalRadio, gbc); minimalRadio.setVisible(true);
                gbc.gridx = 0; gbc.gridy = y++; gbc.insets = new Insets(0, 25, 10, 5); add(minimalDescLabel, gbc); minimalDescLabel.setVisible(true);
                orgDisabledLabel.setVisible(false);
            } else {
                improveRadio.setVisible(false); improveDescLabel.setVisible(false);
                minimalRadio.setVisible(false); minimalDescLabel.setVisible(false);
                gbc.gridx = 0; gbc.gridy = y++; gbc.insets = new Insets(5, 5, 10, 5); add(orgDisabledLabel, gbc); orgDisabledLabel.setVisible(true);
            }
            gbc.insets = new Insets(15, 5, 5, 5); infoLabel.setVisible(true);
            gbc.gridx = 0; gbc.gridy = y++; add(infoLabel, gbc);
            gbc.gridx = 0; gbc.gridy = y; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.VERTICAL; add(Box.createVerticalGlue(), gbc);
            revalidate(); repaint();
        }

        public void refreshStateAndUI() {
            layoutControls(); loadPolicy();
        }

        public void loadPolicy() {
            if (project.isDataShareAllowed()) {
                var currentPolicy = project.getDataRetentionPolicy();
                if (currentPolicy == DataRetentionPolicy.IMPROVE_BROKK) improveRadio.setSelected(true);
                else if (currentPolicy == DataRetentionPolicy.MINIMAL) minimalRadio.setSelected(true);
                else policyGroup.clearSelection();
            }
        }

        public DataRetentionPolicy getSelectedPolicy() {
            if (!project.isDataShareAllowed()) return DataRetentionPolicy.MINIMAL;
            if (improveRadio.isSelected()) return DataRetentionPolicy.IMPROVE_BROKK;
            if (minimalRadio.isSelected()) return DataRetentionPolicy.MINIMAL;
            return DataRetentionPolicy.UNSET;
        }

        public void applyPolicy() {
            var selectedPolicy = getSelectedPolicy();
            var oldPolicy = project.getDataRetentionPolicy();
            if (selectedPolicy != DataRetentionPolicy.UNSET) {
                project.setDataRetentionPolicy(selectedPolicy);
                if (selectedPolicy != oldPolicy) {
                     logger.debug("Applied Data Retention Policy: {}", selectedPolicy);
                     // Trigger model list refresh in parent dialog or chrome context manager
                     parentProjectPanel.parentDialog.getChrome().getContextManager().reloadModelsAsync();
                     // Also need to refresh model selection UI in SettingsGlobalPanel
                     parentProjectPanel.parentDialog.refreshGlobalModelsPanelPostPolicyChange();
                }
            }
        }
    }
}
