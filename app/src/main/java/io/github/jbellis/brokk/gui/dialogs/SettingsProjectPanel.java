package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.IssueProvider;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.MainProject.DataRetentionPolicy;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.issues.FilterOptions;
import io.github.jbellis.brokk.issues.IssuesProviderConfig;
import io.github.jbellis.brokk.issues.JiraFilterOptions;
import io.github.jbellis.brokk.issues.JiraIssueService;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.ExecutorConfig;
import io.github.jbellis.brokk.util.ExecutorValidator;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.BorderFactory;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class SettingsProjectPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(SettingsProjectPanel.class);
    public static final int BUILD_TAB_INDEX = 1; // General(0), Build(1), Data Retention(2)

    // Action command constants for build details inference button
    private static final String ACTION_INFER = "infer";
    private static final String ACTION_CANCEL = "cancel";

    private final Chrome chrome;
    private final SettingsDialog parentDialog;

    // UI Components managed by this panel
    private JComboBox<IProject.AnalyzerRefresh> cpgRefreshComboBox = new JComboBox<>(new IProject.AnalyzerRefresh[] {
        IProject.AnalyzerRefresh.AUTO, IProject.AnalyzerRefresh.ON_RESTART, IProject.AnalyzerRefresh.MANUAL
    });
    private JTextField buildCleanCommandField = new JTextField();
    private JTextField allTestsCommandField = new JTextField();
    private JTextField someTestsCommandField = new JTextField();

    @Nullable
    private DataRetentionPanel dataRetentionPanelInner;

    private JTextArea styleGuideArea = new JTextArea(5, 40);
    private JTextArea commitFormatArea = new JTextArea(5, 40);

    @Nullable
    private JTextArea reviewGuideArea;

    private DefaultListModel<String> excludedDirectoriesListModel = new DefaultListModel<>();
    private JList<String> excludedDirectoriesList = new JList<>(excludedDirectoriesListModel);
    private JScrollPane excludedScrollPane = new JScrollPane(excludedDirectoriesList);
    private JButton addExcludedDirButton = new JButton("Add");
    private JButton removeExcludedDirButton = new JButton("Remove");

    private JTextField languagesDisplayField = new JTextField(20);
    private JButton editLanguagesButton = new JButton("Edit");
    private Set<io.github.jbellis.brokk.analyzer.Language> currentAnalyzerLanguagesForDialog = new HashSet<>();
    private JRadioButton runAllTestsRadio = new JRadioButton(IProject.CodeAgentTestScope.ALL.toString());
    private JRadioButton runTestsInWorkspaceRadio = new JRadioButton(IProject.CodeAgentTestScope.WORKSPACE.toString());
    private JSpinner buildTimeoutSpinner =
            new JSpinner(new SpinnerNumberModel((int) Environment.DEFAULT_TIMEOUT.toSeconds(), 1, 10800, 1));
    private JProgressBar buildProgressBar = new JProgressBar();
    private JButton inferBuildDetailsButton = new JButton("Infer Build Details");
    private JCheckBox setJavaHomeCheckbox = new JCheckBox("Set JAVA_HOME to");
    private JdkSelector jdkSelector = new JdkSelector();
    private JComboBox<Language> primaryLanguageComboBox = new JComboBox<>();

    // Executor configuration UI
    private JTextField executorPathField = new JTextField(20);
    private JTextField executorArgsField = new JTextField(20);
    private JButton testExecutorButton = new JButton("Test");
    private JButton resetExecutorButton = new JButton("Reset");
    private JComboBox<String> commonExecutorsComboBox = new JComboBox<>();

    // System-default executor
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    private static final String DEFAULT_EXECUTOR_PATH = IS_WINDOWS ? "cmd.exe" : "/bin/sh";
    private static final String DEFAULT_EXECUTOR_ARGS = IS_WINDOWS ? "/c" : "-c";

    @Nullable
    private Future<?> manualInferBuildTaskFuture;
    // Buttons from parent dialog that might need to be disabled/enabled by build agent
    private final JButton okButtonParent;
    private final JButton cancelButtonParent;
    private final JButton applyButtonParent;
    private JTabbedPane projectSubTabbedPane = new JTabbedPane(JTabbedPane.TOP);

    // Issue Provider related UI
    private JComboBox<io.github.jbellis.brokk.issues.IssueProviderType> issueProviderTypeComboBox =
            new JComboBox<>(io.github.jbellis.brokk.issues.IssueProviderType.values());
    private CardLayout issueProviderCardLayout = new CardLayout();
    private JPanel issueProviderConfigPanel = new JPanel(issueProviderCardLayout);

    // GitHub specific fields (will be part of the GitHub card)
    private JTextField githubOwnerField = new JTextField(20);
    private JTextField githubRepoField = new JTextField(20);
    private JTextField githubHostField = new JTextField(20);
    private JCheckBox githubOverrideCheckbox = new JCheckBox("Fetch issues from a different GitHub repository");

    private static final String NONE_CARD = "None";
    private static final String GITHUB_CARD = "GitHub";
    private static final String JIRA_CARD = "Jira";

    // Jira specific fields (will be part of the Jira card)
    private JTextField jiraProjectKeyField = new JTextField();
    private JTextField jiraBaseUrlField = new JTextField();
    private JPasswordField jiraApiTokenField = new JPasswordField();
    private JButton testJiraConnectionButton = new JButton("Test Jira Connection");
    private final JPanel bannerPanel;

    // Holds the analyzer configuration panels so we can persist their settings when the user clicks Apply/OK.
    private final LinkedHashMap<Language, AnalyzerSettingsPanel> analyzerSettingsCache = new LinkedHashMap<>();

    public SettingsProjectPanel(
            Chrome chrome, SettingsDialog parentDialog, JButton okButton, JButton cancelButton, JButton applyButton) {
        this.chrome = chrome;
        this.parentDialog = parentDialog;
        this.okButtonParent = okButton;
        this.cancelButtonParent = cancelButton;
        this.applyButtonParent = applyButton;
        this.bannerPanel = createBanner();

        setLayout(new BorderLayout());
        initComponents();
        loadSettings(); // Load settings after components are initialized
    }

    private JPanel createBanner() {
        var p = new JPanel(new BorderLayout(5, 0));
        Color infoBackground = UIManager.getColor("info");
        p.setBackground(infoBackground != null ? infoBackground : new Color(255, 255, 204)); // Pale yellow fallback
        p.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        var msg = new JLabel(
                """
                            Build Agent has completed inspecting your project, \
                            please review the build configuration.
                        """);
        p.add(msg, BorderLayout.CENTER);

        var close = new JButton("×");
        close.setMargin(new Insets(0, 4, 0, 4));
        close.addActionListener(e -> {
            p.setVisible(false);
        });
        p.add(close, BorderLayout.EAST);
        p.setVisible(false); // Initially hidden
        return p;
    }

    private void initComponents() {
        var project = chrome.getProject();
        this.setEnabled(true); // Ensure panel is enabled if project exists

        // General Tab (formerly Other)
        var generalPanel = createGeneralPanel();
        projectSubTabbedPane.addTab("General", null, generalPanel, "General project settings");

        // Build Tab
        var buildPanel = createBuildPanel(project);
        projectSubTabbedPane.addTab("Build", null, buildPanel, "Build configuration and Code Intelligence settings");

        // Issues Tab (New)
        var issuesPanel = createIssuesPanel();
        projectSubTabbedPane.addTab("Issues", null, issuesPanel, "Issue tracker integration settings");

        // Code Intelligence Tab (replaces old Analyzers tab)
        var codeIntPanel = createCodeIntelligencePanel();
        projectSubTabbedPane.addTab(
                "Code Intelligence", null, codeIntPanel, "Code intelligence settings and analyzers");

        // Data Retention Tab
        dataRetentionPanelInner = new DataRetentionPanel(project, this);
        projectSubTabbedPane.addTab(
                "Data Retention", null, dataRetentionPanelInner, "Data retention policy for this project");

        // Jira Tab is now removed, its contents moved to the "Issues" tab's Jira card.

        add(projectSubTabbedPane, BorderLayout.CENTER);

        // Handle initial loading state for Build Details
        if (!project.hasBuildDetails()) {
            projectSubTabbedPane.setEnabledAt(BUILD_TAB_INDEX, false);
            buildProgressBar.setVisible(true);
            inferBuildDetailsButton.setEnabled(false);

            project.getBuildDetailsFuture()
                    .whenCompleteAsync(
                            (@Nullable BuildAgent.BuildDetails detailsResult, @Nullable Throwable ex) -> {
                                SwingUtilities.invokeLater(() -> {
                                    projectSubTabbedPane.setEnabledAt(BUILD_TAB_INDEX, true);
                                    buildProgressBar.setVisible(false);
                                    inferBuildDetailsButton.setEnabled(true);

                                    if (ex != null) {
                                        logger.error("Initial build details determination failed", ex);
                                        chrome.toolError(
                                                "Failed to determine initial build details: " + ex.getMessage());
                                    } else {
                                        if (Objects.equals(detailsResult, BuildAgent.BuildDetails.EMPTY)) {
                                            logger.warn("Initial Build Agent returned empty details. Using defaults.");
                                            chrome.systemOutput(
                                                    "Initial Build Agent completed but found no specific details. Using defaults.");
                                        } else {
                                            logger.info("Initial build details determined successfully.");
                                            chrome.systemOutput(
                                                    "Initial build details determined. Settings panel updated.");
                                        }
                                    }
                                    loadBuildPanelSettings(); // Load settings for the build panel now
                                });
                            },
                            ForkJoinPool.commonPool());
        } else { // Project exists and details are already available
            buildProgressBar.setVisible(false);
            inferBuildDetailsButton.setEnabled(true);
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

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.NONE;
        generalPanel.add(new JLabel("Style Guide:"), gbc);
        styleGuideArea.setWrapStyleWord(true);
        styleGuideArea.setLineWrap(true);
        var styleScrollPane = new JScrollPane(styleGuideArea);
        styleScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        styleScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.weighty = 0.5;
        gbc.fill = GridBagConstraints.BOTH;
        generalPanel.add(styleScrollPane, gbc);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        var styleGuideInfo = new JLabel(
                "<html>The Style Guide is used by the Code Agent to help it conform to your project's style.</html>");
        styleGuideInfo.setFont(styleGuideInfo
                .getFont()
                .deriveFont(Font.ITALIC, styleGuideInfo.getFont().getSize() * 0.9f));
        gbc.insets = new Insets(0, 2, 8, 2);
        generalPanel.add(styleGuideInfo, gbc);

        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.NONE;
        generalPanel.add(new JLabel("Commit Format:"), gbc);
        commitFormatArea.setWrapStyleWord(true);
        commitFormatArea.setLineWrap(true);
        var commitFormatScrollPane = new JScrollPane(commitFormatArea);
        commitFormatScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commitFormatScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        var commitFormatPanel = new JPanel(new BorderLayout(5, 0));
        commitFormatPanel.add(commitFormatScrollPane, BorderLayout.CENTER);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.weighty = 0.5;
        gbc.fill = GridBagConstraints.BOTH;
        generalPanel.add(commitFormatPanel, gbc);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        var commitFormatInfo = new JLabel(
                "<html>This informs the LLM how to structure the commit message suggestions it makes.</html>");
        commitFormatInfo.setFont(commitFormatInfo
                .getFont()
                .deriveFont(Font.ITALIC, commitFormatInfo.getFont().getSize() * 0.9f));
        gbc.insets = new Insets(0, 2, 8, 2); // Increased bottom inset
        generalPanel.add(commitFormatInfo, gbc);

        gbc.insets = new Insets(2, 2, 2, 2);

        var project = chrome.getProject();
        boolean showReviewGuide = project.isGitHubRepo();

        if (showReviewGuide) {
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0.0;
            gbc.anchor = GridBagConstraints.NORTHWEST;
            gbc.fill = GridBagConstraints.NONE;
            generalPanel.add(new JLabel("Review Guide:"), gbc);
            reviewGuideArea = new JTextArea(5, 40);
            reviewGuideArea.setWrapStyleWord(true);
            reviewGuideArea.setLineWrap(true);
            var reviewGuideScrollPane = new JScrollPane(reviewGuideArea);
            reviewGuideScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            reviewGuideScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            gbc.gridx = 1;
            gbc.gridy = row++;
            gbc.weightx = 1.0;
            gbc.weighty = 0.5;
            gbc.fill = GridBagConstraints.BOTH;
            generalPanel.add(reviewGuideScrollPane, gbc);

            gbc.gridx = 1;
            gbc.gridy = row++;
            gbc.weightx = 1.0;
            gbc.weighty = 0.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.NORTHWEST;
            var reviewGuideInfo = new JLabel(
                    "<html>The Review Guide is used to auto-populate the Instructions when capturing a pull request.</html>");
            reviewGuideInfo.setFont(reviewGuideInfo
                    .getFont()
                    .deriveFont(Font.ITALIC, reviewGuideInfo.getFont().getSize() * 0.9f));
            gbc.insets = new Insets(0, 2, 8, 2);
            generalPanel.add(reviewGuideInfo, gbc);
        }

        gbc.weighty = 0.0; // Reset for any future components
        gbc.gridy = row; // Use current row for glue
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.VERTICAL;
        gbc.weighty = 1.0; // Add glue to push content up
        generalPanel.add(Box.createVerticalGlue(), gbc);

        return generalPanel;
    }

    private JPanel createIssuesPanel() {
        var issuesPanel = new JPanel(new BorderLayout(5, 5));
        issuesPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Provider selection
        var providerSelectionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        providerSelectionPanel.add(new JLabel("Issue Provider:"));
        // Custom renderer to use getDisplayName
        issueProviderTypeComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof io.github.jbellis.brokk.issues.IssueProviderType type) {
                    setText(type.getDisplayName());
                }
                return this;
            }
        });
        providerSelectionPanel.add(issueProviderTypeComboBox);
        issuesPanel.add(providerSelectionPanel, BorderLayout.NORTH);

        // Configuration area using CardLayout
        // issueProviderConfigPanel is initialized at field declaration with issueProviderCardLayout

        // --- None Card ---
        var noneCard = new JPanel(new BorderLayout());
        var noneLabel = new JLabel("No issue provider configured.");
        noneLabel.setHorizontalAlignment(SwingConstants.CENTER);
        noneLabel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        noneCard.add(noneLabel, BorderLayout.CENTER);
        issueProviderConfigPanel.add(noneCard, NONE_CARD);

        // --- GitHub Card ---
        var gitHubCard = new JPanel(new GridBagLayout());
        gitHubCard.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        var gbcGitHub = new GridBagConstraints();
        gbcGitHub.insets = new Insets(2, 2, 2, 2);
        gbcGitHub.fill = GridBagConstraints.HORIZONTAL;
        int githubRow = 0;

        gbcGitHub.gridx = 0;
        gbcGitHub.gridy = githubRow++;
        gbcGitHub.gridwidth = 2;
        gbcGitHub.weightx = 0.0;
        gbcGitHub.anchor = GridBagConstraints.NORTHWEST;
        gitHubCard.add(githubOverrideCheckbox, gbcGitHub);

        gbcGitHub.gridwidth = 1; // Reset gridwidth
        gbcGitHub.gridx = 0;
        gbcGitHub.gridy = githubRow;
        gbcGitHub.weightx = 0.0;
        gbcGitHub.fill = GridBagConstraints.NONE;
        gitHubCard.add(new JLabel("Owner:"), gbcGitHub);
        gbcGitHub.gridx = 1;
        gbcGitHub.gridy = githubRow++;
        gbcGitHub.weightx = 1.0;
        gbcGitHub.fill = GridBagConstraints.HORIZONTAL;
        gitHubCard.add(githubOwnerField, gbcGitHub);

        gbcGitHub.gridx = 0;
        gbcGitHub.gridy = githubRow;
        gbcGitHub.weightx = 0.0;
        gbcGitHub.fill = GridBagConstraints.NONE;
        gitHubCard.add(new JLabel("Repository:"), gbcGitHub);
        gbcGitHub.gridx = 1;
        gbcGitHub.gridy = githubRow++;
        gbcGitHub.weightx = 1.0;
        gbcGitHub.fill = GridBagConstraints.HORIZONTAL;
        gitHubCard.add(githubRepoField, gbcGitHub);

        gbcGitHub.gridx = 0;
        gbcGitHub.gridy = githubRow;
        gbcGitHub.weightx = 0.0;
        gbcGitHub.fill = GridBagConstraints.NONE;
        gitHubCard.add(new JLabel("Host (optional):"), gbcGitHub);
        githubHostField.setToolTipText("e.g., github.mycompany.com (leave blank for github.com)");
        gbcGitHub.gridx = 1;
        gbcGitHub.gridy = githubRow++;
        gbcGitHub.weightx = 1.0;
        gbcGitHub.fill = GridBagConstraints.HORIZONTAL;
        gitHubCard.add(githubHostField, gbcGitHub);

        var ghInfoLabel = new JLabel(
                "<html>If not overridden, issues are fetched from the project's own GitHub repository. Uses global GitHub token. Specify host for GitHub Enterprise.</html>");
        ghInfoLabel.setFont(ghInfoLabel
                .getFont()
                .deriveFont(Font.ITALIC, ghInfoLabel.getFont().getSize() * 0.9f));
        gbcGitHub.gridx = 0;
        gbcGitHub.gridy = githubRow++;
        gbcGitHub.gridwidth = 2;
        gbcGitHub.insets = new Insets(8, 2, 2, 2);
        gitHubCard.add(ghInfoLabel, gbcGitHub);

        // Enable/disable owner/repo/host fields based on checkbox
        githubOverrideCheckbox.addActionListener(e -> {
            boolean selected = githubOverrideCheckbox.isSelected();
            githubOwnerField.setEnabled(selected);
            githubRepoField.setEnabled(selected);
            githubHostField.setEnabled(selected);
            if (!selected) {
                // Optionally clear or reset fields if needed when unchecked
                githubOwnerField.setText("");
                githubRepoField.setText("");
                githubHostField.setText("");
            }
        });
        // Initial state
        githubOwnerField.setEnabled(false);
        githubRepoField.setEnabled(false);
        githubHostField.setEnabled(false);

        gbcGitHub.gridx = 0;
        gbcGitHub.gridy = githubRow;
        gbcGitHub.gridwidth = 2;
        gbcGitHub.weighty = 1.0;
        gbcGitHub.fill = GridBagConstraints.VERTICAL;
        gitHubCard.add(Box.createVerticalGlue(), gbcGitHub);
        issueProviderConfigPanel.add(gitHubCard, GITHUB_CARD);

        // --- Jira Card (reuses components) ---
        var jiraCard = new JPanel(new GridBagLayout());
        jiraCard.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0)); // Padding for the card content
        var gbcJira = new GridBagConstraints();
        gbcJira.insets = new Insets(2, 2, 2, 2);
        gbcJira.fill = GridBagConstraints.HORIZONTAL;
        int jiraRow = 0;

        // Jira Base URL
        gbcJira.gridx = 0;
        gbcJira.gridy = jiraRow;
        gbcJira.weightx = 0.0;
        gbcJira.anchor = GridBagConstraints.NORTHWEST;
        gbcJira.fill = GridBagConstraints.NONE;
        jiraCard.add(new JLabel("Jira Base URL:"), gbcJira);
        gbcJira.gridx = 1;
        gbcJira.gridy = jiraRow++;
        gbcJira.weightx = 1.0;
        gbcJira.fill = GridBagConstraints.HORIZONTAL;
        jiraCard.add(jiraBaseUrlField, gbcJira);
        var baseUrlInfo = new JLabel(
                "<html>The base URL of your Jira instance (e.g., https://yourcompany.atlassian.net).</html>");
        baseUrlInfo.setFont(baseUrlInfo
                .getFont()
                .deriveFont(Font.ITALIC, baseUrlInfo.getFont().getSize() * 0.9f));
        gbcJira.gridx = 1;
        gbcJira.gridy = jiraRow++;
        gbcJira.insets = new Insets(0, 2, 8, 2);
        jiraCard.add(baseUrlInfo, gbcJira);
        gbcJira.insets = new Insets(2, 2, 2, 2);

        // Jira API Token
        gbcJira.gridx = 0;
        gbcJira.gridy = jiraRow;
        gbcJira.weightx = 0.0;
        gbcJira.anchor = GridBagConstraints.NORTHWEST;
        gbcJira.fill = GridBagConstraints.NONE;
        jiraCard.add(new JLabel("Jira API Token:"), gbcJira);
        gbcJira.gridx = 1;
        gbcJira.gridy = jiraRow++;
        gbcJira.weightx = 1.0;
        gbcJira.fill = GridBagConstraints.HORIZONTAL;
        jiraCard.add(jiraApiTokenField, gbcJira);
        var apiTokenInfo =
                new JLabel("<html>Your Jira API token. Refer to Atlassian documentation for how to create one.</html>");
        apiTokenInfo.setFont(apiTokenInfo
                .getFont()
                .deriveFont(Font.ITALIC, apiTokenInfo.getFont().getSize() * 0.9f));
        gbcJira.gridx = 1;
        gbcJira.gridy = jiraRow++;
        gbcJira.insets = new Insets(0, 2, 8, 2);
        jiraCard.add(apiTokenInfo, gbcJira);
        gbcJira.insets = new Insets(2, 2, 2, 2);

        // Jira Project Key
        gbcJira.gridx = 0;
        gbcJira.gridy = jiraRow;
        gbcJira.weightx = 0.0;
        gbcJira.anchor = GridBagConstraints.NORTHWEST;
        gbcJira.fill = GridBagConstraints.NONE;
        jiraCard.add(new JLabel("Jira Project Key:"), gbcJira);
        gbcJira.gridx = 1;
        gbcJira.gridy = jiraRow++;
        gbcJira.weightx = 1.0;
        gbcJira.fill = GridBagConstraints.HORIZONTAL;
        jiraCard.add(jiraProjectKeyField, gbcJira);
        var jiraProjectKeyInfo = new JLabel(
                "<html>The key of your Jira project (e.g., CASSANDRA). Used to scope issue searches.</html>");
        jiraProjectKeyInfo.setFont(jiraProjectKeyInfo
                .getFont()
                .deriveFont(Font.ITALIC, jiraProjectKeyInfo.getFont().getSize() * 0.9f));
        gbcJira.gridx = 1;
        gbcJira.gridy = jiraRow++;
        gbcJira.insets = new Insets(0, 2, 8, 2);
        jiraCard.add(jiraProjectKeyInfo, gbcJira);
        gbcJira.insets = new Insets(2, 2, 2, 2);

        // Test Connection Button
        testJiraConnectionButton.addActionListener(e -> testJiraConnectionAction());
        gbcJira.gridx = 1;
        gbcJira.gridy = jiraRow++;
        gbcJira.weightx = 0.0;
        gbcJira.fill = GridBagConstraints.NONE;
        gbcJira.anchor = GridBagConstraints.EAST;
        jiraCard.add(testJiraConnectionButton, gbcJira);

        // Vertical glue
        gbcJira.gridx = 0;
        gbcJira.gridy = jiraRow;
        gbcJira.gridwidth = 2;
        gbcJira.weighty = 1.0;
        gbcJira.fill = GridBagConstraints.VERTICAL;
        jiraCard.add(Box.createVerticalGlue(), gbcJira);
        issueProviderConfigPanel.add(jiraCard, JIRA_CARD);

        issuesPanel.add(issueProviderConfigPanel, BorderLayout.CENTER);

        // Action listener for provider selection
        issueProviderTypeComboBox.addActionListener(e -> {
            io.github.jbellis.brokk.issues.IssueProviderType selectedType =
                    (io.github.jbellis.brokk.issues.IssueProviderType) issueProviderTypeComboBox.getSelectedItem();
            if (selectedType == null)
                selectedType = io.github.jbellis.brokk.issues.IssueProviderType.NONE; // Should not happen with enum
            switch (selectedType) {
                case JIRA:
                    issueProviderCardLayout.show(issueProviderConfigPanel, JIRA_CARD);
                    break;
                case GITHUB:
                    issueProviderCardLayout.show(issueProviderConfigPanel, GITHUB_CARD);
                    break;
                case NONE:
                default:
                    issueProviderCardLayout.show(issueProviderConfigPanel, NONE_CARD);
                    break;
            }
        });
        return issuesPanel;
    }

    /**
     * Creates the Analyzers tab that lists the languages for which analyzers are currently configured in the project.
     * This is read-only for now but provides a foundation for adding per-analyzer options in the future.
     */
    private JPanel createCodeIntelligencePanel() {
        var panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var project = chrome.getProject();
        var projectRoot = project.getRoot();

        // Determine the languages to show: union of detected languages and currently enabled analyzers
        var detected = findLanguagesInProject(project);
        var configured = new ArrayList<>(project.getAnalyzerLanguages());
        // Merge into a single ordered list (alphabetical by name)
        var langSet = new HashSet<Language>();
        langSet.addAll(detected);
        langSet.addAll(configured);
        var languagesToShow = new ArrayList<>(langSet);
        languagesToShow.sort(Comparator.comparing(Language::name));

        // Ensure currentAnalyzerLanguagesForDialog has initial values if empty
        if (currentAnalyzerLanguagesForDialog.isEmpty()) {
            currentAnalyzerLanguagesForDialog.addAll(project.getAnalyzerLanguages());
        }

        // Table model for languages
        class LanguagesTableModel extends AbstractTableModel {
            private final List<Language> rows;

            LanguagesTableModel(List<Language> rows) {
                this.rows = rows;
            }

            @Override
            public int getRowCount() {
                return rows.size();
            }

            @Override
            public int getColumnCount() {
                return 2; // Live, Language
            }

            @Override
            public String getColumnName(int column) {
                return switch (column) {
                    case 0 -> "Live";
                    case 1 -> "Language";
                    default -> "";
                };
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case 0 -> Boolean.class;
                    default -> String.class;
                };
            }

            @Override
            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return columnIndex == 0; // only the checkbox is editable
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                var lang = rows.get(rowIndex);
                return switch (columnIndex) {
                    case 0 -> currentAnalyzerLanguagesForDialog.contains(lang);
                    case 1 -> lang.name();
                    default -> "";
                };
            }

            @Override
            public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
                if (columnIndex != 0) return;
                var lang = rows.get(rowIndex);
                boolean live = Boolean.TRUE.equals(aValue);
                if (live) currentAnalyzerLanguagesForDialog.add(lang);
                else currentAnalyzerLanguagesForDialog.remove(lang);
                fireTableCellUpdated(rowIndex, 0);
            }
        }

        var tableModel = new LanguagesTableModel(languagesToShow);
        var table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.setAutoCreateRowSorter(true);

        // Simple renderer for language column (center-left)
        var langRenderer = new DefaultTableCellRenderer();
        langRenderer.setHorizontalAlignment(SwingConstants.LEFT);
        table.getColumnModel().getColumn(1).setCellRenderer(langRenderer);

        // Keep the checkbox column narrow; compute a tight preferred width for the Language column and cap it.
        table.getColumnModel().getColumn(0).setMaxWidth(60);

        // Compute preferred width for Language column based on content and header, then cap it
        var headerRenderer = table.getTableHeader().getDefaultRenderer();
        var langCol = table.getColumnModel().getColumn(1);
        int pref = 0;

        // Header width
        var headerComp =
                headerRenderer.getTableCellRendererComponent(table, langCol.getHeaderValue(), false, false, -1, 1);
        pref = Math.max(pref, headerComp.getPreferredSize().width);

        // Cells width
        for (int r = 0; r < tableModel.getRowCount(); r++) {
            var comp = table.getCellRenderer(r, 1)
                    .getTableCellRendererComponent(table, tableModel.getValueAt(r, 1), false, false, r, 1);
            pref = Math.max(pref, comp.getPreferredSize().width);
        }

        // Add some padding
        pref += 16;
        langCol.setPreferredWidth(pref);
        langCol.setMaxWidth(pref);

        var leftScroll = new JScrollPane(table);
        // Size viewport to table's preferred size so WEST honors content-based sizing
        table.setPreferredScrollableViewportSize(table.getPreferredSize());
        // Titled border + inner horizontal padding to match Language Details panel
        leftScroll.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Detected Languages"), BorderFactory.createEmptyBorder(0, 8, 0, 8)));

        // Right detail panel
        var rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBorder(BorderFactory.createTitledBorder("Language Details"));
        var noSelectionLabel = new JLabel("Select a language to view settings.");
        noSelectionLabel.setHorizontalAlignment(SwingConstants.CENTER);
        noSelectionLabel.setVerticalAlignment(SwingConstants.TOP);
        rightPanel.add(noSelectionLabel, BorderLayout.NORTH);

        // Toolbar with global controls (refresh controls)
        var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        toolbar.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

        toolbar.add(new JLabel("Analyzer Refresh:"));
        toolbar.add(cpgRefreshComboBox);

        var refreshBtn = new JButton("Refresh Now");
        refreshBtn.addActionListener(e -> {
            chrome.systemOutput("Requesting analyzer refresh...");
            try {
                chrome.getContextManager().requestRebuild();
            } catch (Exception ex) {
                logger.error("Failed to request rebuild", ex);
                chrome.toolError("Failed to request rebuild: " + ex.getMessage());
            }
        });
        toolbar.add(refreshBtn);

        // CI Exclusions panel (moved here from Build tab)
        var ciPanel = new JPanel(new GridBagLayout());
        var gbcCi = new GridBagConstraints();
        gbcCi.insets = new Insets(2, 2, 2, 2);
        gbcCi.fill = GridBagConstraints.HORIZONTAL;
        int ciRow = 0;

        gbcCi.gridx = 0;
        gbcCi.gridy = ciRow;
        gbcCi.weightx = 0.0;
        gbcCi.anchor = GridBagConstraints.NORTHWEST;
        ciPanel.add(new JLabel("CI Exclusions:"), gbcCi);
        excludedDirectoriesList.setVisibleRowCount(3);
        gbcCi.gridx = 1;
        gbcCi.gridy = ciRow++;
        gbcCi.weightx = 1.0;
        gbcCi.weighty = 0.5;
        gbcCi.fill = GridBagConstraints.BOTH;
        ciPanel.add(this.excludedScrollPane, gbcCi);

        var excludedButtonsPanel2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        excludedButtonsPanel2.add(this.addExcludedDirButton);
        excludedButtonsPanel2.add(Box.createHorizontalStrut(5));
        excludedButtonsPanel2.add(this.removeExcludedDirButton);
        gbcCi.gridy = ciRow++;
        gbcCi.weighty = 0.0;
        gbcCi.fill = GridBagConstraints.HORIZONTAL;
        gbcCi.anchor = GridBagConstraints.WEST;
        ciPanel.add(excludedButtonsPanel2, gbcCi);

        // Wire add/remove actions for the exclusions buttons (moved here)
        this.addExcludedDirButton.addActionListener(e -> {
            String newDir = JOptionPane.showInputDialog(
                    parentDialog,
                    "Enter directory to exclude (e.g., target/, build/):",
                    "Add Excluded Directory",
                    JOptionPane.PLAIN_MESSAGE);
            if (newDir != null && !newDir.trim().isEmpty()) {
                String trimmedNewDir = newDir.trim();
                List<String> currentElements = Collections.list(excludedDirectoriesListModel.elements());
                if (!currentElements.contains(trimmedNewDir)) { // Avoid duplicates if user adds same dir again
                    currentElements.add(trimmedNewDir);
                }
                currentElements.sort(String::compareToIgnoreCase);

                excludedDirectoriesListModel.clear();
                currentElements.forEach(excludedDirectoriesListModel::addElement);
            }
        });
        this.removeExcludedDirButton.addActionListener(e -> {
            int[] selectedIndices = excludedDirectoriesList.getSelectedIndices();
            for (int i = selectedIndices.length - 1; i >= 0; i--)
                excludedDirectoriesListModel.removeElementAt(selectedIndices[i]);
        });

        // Compose a north container that holds toolbar only
        var northContainer = new JPanel(new BorderLayout());
        northContainer.add(toolbar, BorderLayout.NORTH);

        // Place the north container; exclusions panel will be shown below the languages split
        panel.add(northContainer, BorderLayout.NORTH);

        // Selection listener for table -> show right panel content
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int sel = table.getSelectedRow();
            rightPanel.removeAll();

            if (sel < 0) {
                // No selection: show the default text at the top
                rightPanel.add(noSelectionLabel, BorderLayout.NORTH);
                rightPanel.revalidate();
                rightPanel.repaint();
                return;
            }

            // Convert view index to model index when sorter is active
            int modelRow = table.convertRowIndexToModel(sel);
            var lang = languagesToShow.get(modelRow);

            // Create (or reuse) analyzer settings panel for this language
            AnalyzerSettingsPanel settingsPanel = analyzerSettingsCache.computeIfAbsent(
                    lang,
                    l -> AnalyzerSettingsPanel.createAnalyzersPanel(
                            SettingsProjectPanel.this,
                            l,
                            projectRoot,
                            chrome.getContextManager().getIo()));
            // ensure zero margin for embedded analyzer panels
            settingsPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

            // File count label above per-language settings (formatted with commas and padded to match details panel)
            int fileCount = project.getFiles(lang).size();
            var fileCountLabel = new JLabel("Files: " + String.format("%,d", fileCount));
            fileCountLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
            fileCountLabel.setFont(
                    fileCountLabel.getFont().deriveFont(fileCountLabel.getFont().getSize() * 0.95f));

            var wrapper = new JPanel(new BorderLayout());
            // Add horizontal padding to match the Files label's spacing; keep analyzer panel itself borderless
            wrapper.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            wrapper.add(fileCountLabel, BorderLayout.NORTH);
            wrapper.add(settingsPanel, BorderLayout.CENTER);
            rightPanel.add(wrapper, BorderLayout.NORTH);

            rightPanel.revalidate();
            rightPanel.repaint();
        });

        // Side-by-side layout: left list, right details (no JSplitPane)
        var centerPanel = new JPanel(new BorderLayout(8, 0));
        // Let the WEST scroll pane size itself based on the table's preferred size
        centerPanel.add(leftScroll, BorderLayout.WEST);
        centerPanel.add(rightPanel, BorderLayout.CENTER);

        panel.add(centerPanel, BorderLayout.CENTER);

        // Preselect the language with the most associated files so details show immediately.
        // Perform selection on the EDT so we don't inadvertently trigger other side-effects.
        if (tableModel.getRowCount() > 0) {
            int maxModelIdx = 0;
            int maxCount = -1;
            for (int i = 0; i < languagesToShow.size(); i++) {
                int cnt = project.getFiles(languagesToShow.get(i)).size();
                if (cnt > maxCount) {
                    maxCount = cnt;
                    maxModelIdx = i;
                }
            }
            int viewIdx = table.convertRowIndexToView(maxModelIdx);
            if (viewIdx >= 0) {
                SwingUtilities.invokeLater(() -> {
                    table.getSelectionModel().setSelectionInterval(viewIdx, viewIdx);
                    // Ensure selected row is visible
                    table.scrollRectToVisible(table.getCellRect(viewIdx, 0, true));
                });
            }
        }

        // Add excluded directories panel below the languages configuration
        ciPanel.setBorder(BorderFactory.createTitledBorder("Excluded directories"));
        panel.add(ciPanel, BorderLayout.SOUTH);

        return panel;
    }

    private void testJiraConnectionAction() {
        String baseUrl = jiraBaseUrlField.getText().trim();
        String token = new String(jiraApiTokenField.getPassword()).trim();

        if (baseUrl.isEmpty() || token.isEmpty()) {
            JOptionPane.showMessageDialog(
                    SettingsProjectPanel.this,
                    "Please fill in Jira Base URL and API Token.",
                    "Missing Information",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        testJiraConnectionButton.setEnabled(false);
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                String currentBaseUrl = jiraBaseUrlField.getText().trim();
                String currentApiToken = new String(jiraApiTokenField.getPassword()).trim();
                String currentProjectKey = jiraProjectKeyField.getText().trim(); // Needed for listIssues

                var jiraConfig =
                        new IssuesProviderConfig.JiraConfig(currentBaseUrl, currentApiToken, currentProjectKey);
                var testProvider = new IssueProvider(io.github.jbellis.brokk.issues.IssueProviderType.JIRA, jiraConfig);

                JiraIssueService testService = new JiraIssueService(testProvider, chrome.getProject());
                try {
                    FilterOptions filterOptions = new JiraFilterOptions(null, null, null, null, null, null);
                    testService.listIssues(filterOptions); // This will use the temporary provider
                    return "Connection successful!";
                } catch (IOException ioException) {
                    logger.warn("Jira connection test failed: {}", ioException.getMessage());
                    return "Connection failed: " + ioException.getMessage();
                } catch (Exception ex) {
                    logger.error("Unexpected error during Jira connection test: {}", ex.getMessage(), ex);
                    return "Connection failed with unexpected error: " + ex.getMessage();
                }
            }

            @Override
            protected void done() {
                try {
                    String result = get();
                    if (result.startsWith("Connection successful")) {
                        JOptionPane.showMessageDialog(
                                SettingsProjectPanel.this,
                                result,
                                "Jira Connection Test",
                                JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(
                                SettingsProjectPanel.this,
                                result,
                                "Jira Connection Test Failed",
                                JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception ex) {
                    String errorMessage = "An unexpected error occurred during the test: " + ex.getMessage();
                    logger.error(errorMessage, ex);
                    JOptionPane.showMessageDialog(
                            SettingsProjectPanel.this,
                            errorMessage,
                            "Jira Connection Test Error",
                            JOptionPane.ERROR_MESSAGE);
                } finally {
                    testJiraConnectionButton.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private JPanel createBuildPanel(IProject project) {
        var buildPanel = new JPanel(new GridBagLayout());
        buildPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        // Add banner at the top
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        buildPanel.add(bannerPanel, gbc);
        gbc.gridwidth = 1; // Reset gridwidth

        // Primary language at top
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        buildPanel.add(new JLabel("Primary language:"), gbc);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        buildPanel.add(primaryLanguageComboBox, gbc);

        // JDK selection controls (visible only if primary language is Java)
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        buildPanel.add(setJavaHomeCheckbox, gbc);

        jdkSelector.setEnabled(false);
        jdkSelector.setBrowseParent(parentDialog);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        buildPanel.add(jdkSelector, gbc);

        primaryLanguageComboBox.addActionListener(e -> {
            var sel = (Language) primaryLanguageComboBox.getSelectedItem();
            updateJdkControlsVisibility(sel);
            if (sel == Languages.JAVA) {
                populateJdkControlsFromProject();
            }
        });

        // Initial visibility based on current project setting
        updateJdkControlsVisibility(project.getBuildLanguage());

        setJavaHomeCheckbox.addActionListener(e -> jdkSelector.setEnabled(setJavaHomeCheckbox.isSelected()));

        // Build/Lint Command (moved below primary language)
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        buildPanel.add(new JLabel("Build/Lint Command:"), gbc);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        buildPanel.add(buildCleanCommandField, gbc);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        buildPanel.add(new JLabel("Test All Command:"), gbc);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        buildPanel.add(allTestsCommandField, gbc);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        buildPanel.add(new JLabel("Test Some Command:"), gbc);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        buildPanel.add(someTestsCommandField, gbc);
        var testSomeInfo = new JLabel(
                "<html>Mustache variables {{#files}}, {{#classes}}, or {{#fqclasses}} will be interpolated with filenames, class names, or fully-qualified class names, respectively</html>");
        testSomeInfo.setFont(testSomeInfo
                .getFont()
                .deriveFont(Font.ITALIC, testSomeInfo.getFont().getSize() * 0.9f));
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.insets = new Insets(0, 2, 8, 2);
        buildPanel.add(testSomeInfo, gbc);
        gbc.insets = new Insets(2, 2, 2, 2); // Reset insets

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        buildPanel.add(new JLabel("Code Agent Tests:"), gbc);
        var testScopeGroup = new ButtonGroup();
        testScopeGroup.add(runAllTestsRadio);
        testScopeGroup.add(runTestsInWorkspaceRadio);
        var radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        radioPanel.setOpaque(false);
        radioPanel.add(runAllTestsRadio);
        radioPanel.add(runTestsInWorkspaceRadio);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        buildPanel.add(radioPanel, gbc);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        buildPanel.add(new JLabel("Run Command Timeout (sec):"), gbc);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        buildPanel.add(buildTimeoutSpinner, gbc);

        // Executor configuration section
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        buildPanel.add(new JLabel("Execute with:"), gbc);

        // Path field + dropdown in same row
        var executorSelectPanel = new JPanel(new GridBagLayout());
        var gbcInner = new GridBagConstraints();
        gbcInner.fill = GridBagConstraints.HORIZONTAL;
        gbcInner.weightx = 1.0;
        executorSelectPanel.add(executorPathField, gbcInner);
        gbcInner.weightx = 0;
        gbcInner.fill = GridBagConstraints.NONE;
        gbcInner.anchor = GridBagConstraints.WEST;
        gbcInner.insets = new Insets(0, 5, 0, 0);
        executorSelectPanel.add(commonExecutorsComboBox, gbcInner);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        buildPanel.add(executorSelectPanel, gbc);

        // Default args row
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        buildPanel.add(new JLabel("Default parameters:"), gbc);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        buildPanel.add(executorArgsField, gbc);

        // Test / Reset buttons + info
        var executorInfoLabel = new JLabel(
                "<html>Custom executors work in all modes. Approved executors work in sandbox mode. Default args: \""
                        + DEFAULT_EXECUTOR_ARGS + "\"</html>");
        executorInfoLabel.setFont(executorInfoLabel
                .getFont()
                .deriveFont(Font.ITALIC, executorInfoLabel.getFont().getSize() * 0.9f));

        var executorTestPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        executorTestPanel.add(testExecutorButton);
        executorTestPanel.add(Box.createHorizontalStrut(5));
        executorTestPanel.add(resetExecutorButton);
        executorTestPanel.add(Box.createHorizontalStrut(10));
        executorTestPanel.add(executorInfoLabel);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        buildPanel.add(executorTestPanel, gbc);

        // CI exclusions moved to Code Intelligence tab above; preserve layout spacing
        row += 2;

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 0.0;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        inferBuildDetailsButton.setActionCommand(ACTION_INFER); // Default action is "infer"
        buildPanel.add(inferBuildDetailsButton, gbc);

        // Check if initial build details inference is running
        CompletableFuture<BuildAgent.BuildDetails> detailsFuture = project.getBuildDetailsFuture();
        boolean initialAgentRunning = !detailsFuture.isDone();

        // --- Progress Bar for Build Agent ---
        // Create a wrapper panel with fixed height to reserve space
        JPanel progressWrapper = new JPanel(new BorderLayout());
        progressWrapper.setPreferredSize(buildProgressBar.getPreferredSize());
        progressWrapper.add(buildProgressBar, BorderLayout.CENTER);
        buildProgressBar.setIndeterminate(true);

        buildProgressBar.setVisible(initialAgentRunning); // Show progress bar if initial agent is running
        gbc.gridx = 1; // Align with input fields (right column)
        gbc.gridy = row++; // Next available row
        gbc.fill = GridBagConstraints.HORIZONTAL; // Let progress bar fill width
        gbc.anchor = GridBagConstraints.EAST;
        buildPanel.add(progressWrapper, gbc);
        // Initialize button based on the state of the initial build agent
        if (initialAgentRunning) {
            setButtonToInferenceInProgress(false); // false = don't set Cancel text (initial agent)

            // Add a listener to reset the button when the initial agent completes
            detailsFuture.whenCompleteAsync((result, ex) -> {
                SwingUtilities.invokeLater(() -> {
                    // inferBuildDetailsButton is non-null
                    if (manualInferBuildTaskFuture == null) {
                        setButtonToReadyState();
                    }
                });
            });
        }

        inferBuildDetailsButton.addActionListener(e -> runBuildAgent());

        // Initialize executor UI components
        initializeExecutorUI();

        // Vertical glue to push all build panel content up
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        buildPanel.add(Box.createVerticalGlue(), gbc);

        return buildPanel;
    }

    private void setButtonToInferenceInProgress(boolean showCancelButton) {
        inferBuildDetailsButton.setToolTipText("build inference in progress");
        buildProgressBar.setVisible(true);

        if (showCancelButton) {
            inferBuildDetailsButton.setText("Cancel");
            inferBuildDetailsButton.setActionCommand(ACTION_CANCEL);
            inferBuildDetailsButton.setEnabled(true);
        } else {
            // Initial agent running - disable the button
            inferBuildDetailsButton.setEnabled(false);
        }
    }

    private void setButtonToReadyState() {
        inferBuildDetailsButton.setText("Infer Build Details");
        inferBuildDetailsButton.setActionCommand(ACTION_INFER);
        inferBuildDetailsButton.setEnabled(true);
        inferBuildDetailsButton.setToolTipText(null);
        buildProgressBar.setVisible(false);
    }

    private void runBuildAgent() {
        String action = inferBuildDetailsButton.getActionCommand();

        if (ACTION_CANCEL.equals(action)) {
            // We're in cancel mode - cancel the running task
            if (manualInferBuildTaskFuture != null && !manualInferBuildTaskFuture.isDone()) {
                boolean cancelled = manualInferBuildTaskFuture.cancel(true);
                logger.debug("Build agent cancellation requested, result: {}", cancelled);
                // Button state will be reset in the finally block of the task
            }
            return;
        }

        var cm = chrome.getContextManager();
        var proj = chrome.getProject();

        setBuildControlsEnabled(false); // Disable controls in this panel
        setButtonToInferenceInProgress(true); // true = set Cancel text (manual agent)

        manualInferBuildTaskFuture = cm.submitUserTask("Running Build Agent", () -> {
            try {
                chrome.systemOutput("Starting Build Agent...");
                var agent = new BuildAgent(
                        proj, cm.getLlm(cm.getSearchModel(), "Infer build details"), cm.getToolRegistry());
                var newBuildDetails = agent.execute();

                if (Objects.equals(newBuildDetails, BuildAgent.BuildDetails.EMPTY)) {
                    logger.warn("Build Agent returned null or empty details, considering it an error.");
                    // When cancel button is pressed, we need to show a different kind of message
                    boolean isCancellation = ACTION_CANCEL.equals(inferBuildDetailsButton.getActionCommand());

                    SwingUtilities.invokeLater(() -> {
                        if (isCancellation) {
                            logger.info("Build Agent execution cancelled by user");
                            chrome.systemOutput("Build Inference Agent cancelled.");
                            JOptionPane.showMessageDialog(
                                    SettingsProjectPanel.this,
                                    "Build Inference Agent cancelled.",
                                    "Build Cancelled",
                                    JOptionPane.INFORMATION_MESSAGE);
                        } else {
                            SwingUtilities.invokeLater(() -> {
                                String errorMessage =
                                        "Build Agent failed to determine build details. Please check agent logs.";
                                chrome.toolError(errorMessage);
                                JOptionPane.showMessageDialog(
                                        SettingsProjectPanel.this,
                                        errorMessage,
                                        "Build Agent Error",
                                        JOptionPane.ERROR_MESSAGE);
                                // Do not save or update UI with empty details
                            });
                        }
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
                    chrome.toolError(errorMessage);
                    JOptionPane.showMessageDialog(
                            parentDialog, errorMessage, "Build Agent Error", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    setBuildControlsEnabled(true);
                    setButtonToReadyState();
                    manualInferBuildTaskFuture = null;
                });
            }
        });
    }

    private void setBuildControlsEnabled(boolean enabled) {
        // The 'enabled' state is determined by the caller;
        // this panel's overall enabled state (due to project presence) is handled in initComponents.
        buildProgressBar.setVisible(!enabled);

        Stream.of(
                        buildCleanCommandField,
                        allTestsCommandField,
                        someTestsCommandField,
                        runAllTestsRadio,
                        runTestsInWorkspaceRadio,
                        cpgRefreshComboBox,
                        editLanguagesButton,
                        excludedScrollPane,
                        excludedDirectoriesList,
                        addExcludedDirButton,
                        removeExcludedDirButton,
                        // Parent dialog buttons
                        okButtonParent,
                        cancelButtonParent,
                        applyButtonParent)
                .filter(Objects::nonNull) // Filter out null components (e.g., optional parent buttons)
                .forEach(control -> control.setEnabled(enabled));
    }

    private void updateBuildDetailsFieldsFromAgent(BuildAgent.BuildDetails details) {
        SwingUtilities.invokeLater(() -> {
            buildCleanCommandField.setText(details.buildLintCommand());
            allTestsCommandField.setText(details.testAllCommand());
            someTestsCommandField.setText(details.testSomeCommand());
            excludedDirectoriesListModel.clear();
            var sortedExcludedDirs =
                    details.excludedDirectories().stream().sorted().toList();
            for (String dir : sortedExcludedDirs) excludedDirectoriesListModel.addElement(dir);
            logger.trace("UI fields updated with new BuildDetails from agent: {}", details);
        });
    }

    private void updateLanguagesDisplayField() {
        // languagesDisplayField and currentAnalyzerLanguagesForDialog are initialized at declaration and non-null.
        String cdl = currentAnalyzerLanguagesForDialog.stream()
                .map(lang -> lang.name())
                .sorted()
                .collect(Collectors.joining(", "));
        languagesDisplayField.setText(cdl.isEmpty() ? "None" : cdl);
    }

    public void loadSettings() {
        var project = chrome.getProject();

        // General Tab
        styleGuideArea.setText(project.getStyleGuide());
        commitFormatArea.setText(project.getCommitMessageFormat());
        if (reviewGuideArea != null) {
            reviewGuideArea.setText(project.getReviewGuide());
        }

        // Issues Tab
        IssueProvider currentProvider = project.getIssuesProvider();
        issueProviderTypeComboBox.setSelectedItem(currentProvider.type());

        githubOwnerField.setEnabled(false); // Default state
        githubRepoField.setEnabled(false);
        githubHostField.setEnabled(false); // Default state for host field
        githubOverrideCheckbox.setSelected(false);

        switch (currentProvider.type()) {
            case JIRA:
                if (currentProvider.config() instanceof IssuesProviderConfig.JiraConfig jiraConfig) {
                    jiraBaseUrlField.setText(jiraConfig.baseUrl());
                    jiraApiTokenField.setText(jiraConfig.apiToken());
                    jiraProjectKeyField.setText(jiraConfig.projectKey());
                }
                issueProviderCardLayout.show(issueProviderConfigPanel, JIRA_CARD);
                break;
            case GITHUB:
                if (currentProvider.config() instanceof IssuesProviderConfig.GithubConfig githubConfig) {
                    if (!githubConfig.isDefault()) {
                        githubOwnerField.setText(githubConfig.owner());
                        githubRepoField.setText(githubConfig.repo());
                        githubHostField.setText(githubConfig.host()); // Load host
                        githubOwnerField.setEnabled(true);
                        githubRepoField.setEnabled(true);
                        githubHostField.setEnabled(true); // Enable host field if override is active
                        githubOverrideCheckbox.setSelected(true);
                    } else {
                        // Fields remain disabled and empty, checkbox unchecked
                        githubOwnerField.setText("");
                        githubRepoField.setText("");
                        githubHostField.setText("");
                    }
                }
                issueProviderCardLayout.show(issueProviderConfigPanel, GITHUB_CARD);
                break;
            case NONE:
            default:
                issueProviderCardLayout.show(issueProviderConfigPanel, NONE_CARD);
                break;
        }

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

        BuildAgent.BuildDetails details;
        try {
            // This call is now safe as it's guarded by hasBuildDetails() or called after awaitBuildDetails()
            details = project.loadBuildDetails();
        } catch (Exception e) {
            logger.warn("Could not load build details for settings panel, using EMPTY. Error: {}", e.getMessage(), e);
            details = BuildAgent.BuildDetails.EMPTY; // Fallback to EMPTY
            chrome.toolError("Error loading build details: " + e.getMessage() + ". Using defaults.");
        }

        buildCleanCommandField.setText(details.buildLintCommand());
        allTestsCommandField.setText(details.testAllCommand());
        someTestsCommandField.setText(details.testSomeCommand());

        if (project.getCodeAgentTestScope() == IProject.CodeAgentTestScope.ALL) {
            runAllTestsRadio.setSelected(true);
        } else {
            runTestsInWorkspaceRadio.setSelected(true);
        }

        buildTimeoutSpinner.setValue((int) project.getMainProject().getRunCommandTimeoutSeconds());
        populateJdkControlsFromProject();

        var currentRefresh = project.getAnalyzerRefresh();
        cpgRefreshComboBox.setSelectedItem(
                currentRefresh == IProject.AnalyzerRefresh.UNSET ? IProject.AnalyzerRefresh.AUTO : currentRefresh);

        // Primary language
        populatePrimaryLanguageComboBox();
        var selectedLang = project.getBuildLanguage();
        primaryLanguageComboBox.setSelectedItem(selectedLang);
        updateJdkControlsVisibility(selectedLang);
        if (selectedLang == Languages.JAVA) {
            populateJdkControlsFromProject();
        }

        currentAnalyzerLanguagesForDialog = new HashSet<>(project.getAnalyzerLanguages());
        updateLanguagesDisplayField();

        excludedDirectoriesListModel.clear();
        var sortedExcludedDirs = details.excludedDirectories().stream().sorted().toList();
        for (String dir : sortedExcludedDirs) {
            excludedDirectoriesListModel.addElement(dir);
        }

        // Load executor configuration
        String executorPath = project.getCommandExecutor();
        String executorArgs = project.getExecutorArgs();

        executorPathField.setText(executorPath != null ? executorPath : DEFAULT_EXECUTOR_PATH);
        executorArgsField.setText(executorArgs != null ? executorArgs : DEFAULT_EXECUTOR_ARGS);

        logger.trace("Build panel settings loaded/reloaded with details: {}", details);
    }

    public boolean applySettings() {
        var project = chrome.getProject();

        // General Tab
        project.saveStyleGuide(styleGuideArea.getText());
        project.setCommitMessageFormat(commitFormatArea.getText());
        if (reviewGuideArea != null) {
            project.saveReviewGuide(reviewGuideArea.getText());
        }

        // Issues Tab
        io.github.jbellis.brokk.issues.IssueProviderType selectedType =
                (io.github.jbellis.brokk.issues.IssueProviderType) issueProviderTypeComboBox.getSelectedItem();
        IssueProvider newProviderToSet;

        switch (selectedType) {
            case JIRA:
                String baseUrl = jiraBaseUrlField.getText().trim();
                String apiToken = new String(jiraApiTokenField.getPassword()).trim();
                String projectKey = jiraProjectKeyField.getText().trim();
                newProviderToSet = IssueProvider.jira(baseUrl, apiToken, projectKey);
                break;
            case GITHUB:
                if (githubOverrideCheckbox.isSelected()) {
                    String owner = githubOwnerField.getText().trim();
                    String repo = githubRepoField.getText().trim();
                    String host = githubHostField.getText().trim();
                    newProviderToSet = IssueProvider.github(owner, repo, host);
                } else {
                    newProviderToSet = IssueProvider.github(); // Default GitHub (empty owner, repo, host)
                }
                break;
            case NONE:
            default:
                newProviderToSet = IssueProvider.none();
                break;
        }
        project.setIssuesProvider(newProviderToSet);

        // Build Tab
        var currentDetails = project.loadBuildDetails();
        var newBuildLint = buildCleanCommandField.getText();
        var newTestAll = allTestsCommandField.getText();
        var newTestSome = someTestsCommandField.getText();
        // buildInstructionsArea removed

        var newExcludedDirs = new HashSet<String>();
        for (int i = 0; i < excludedDirectoriesListModel.getSize(); i++)
            newExcludedDirs.add(excludedDirectoriesListModel.getElementAt(i));

        var newDetails = new BuildAgent.BuildDetails(newBuildLint, newTestAll, newTestSome, newExcludedDirs);
        if (!newDetails.equals(currentDetails)) {
            project.saveBuildDetails(newDetails);
            logger.debug("Applied Build Details changes.");
        }

        MainProject.CodeAgentTestScope selectedScope =
                runAllTestsRadio.isSelected() ? IProject.CodeAgentTestScope.ALL : IProject.CodeAgentTestScope.WORKSPACE;
        if (selectedScope != project.getCodeAgentTestScope()) {
            project.setCodeAgentTestScope(selectedScope);
            logger.debug("Applied Code Agent Test Scope: {}", selectedScope);
        }

        var mainProject = project.getMainProject();
        long timeout = ((Number) buildTimeoutSpinner.getValue()).longValue();
        if (timeout != mainProject.getRunCommandTimeoutSeconds()) {
            mainProject.setRunCommandTimeoutSeconds(timeout);
            logger.debug("Applied Run Command Timeout: {} seconds", timeout);
        }

        var selectedRefresh = (IProject.AnalyzerRefresh) cpgRefreshComboBox.getSelectedItem();
        if (selectedRefresh != project.getAnalyzerRefresh()) {
            project.setAnalyzerRefresh(selectedRefresh);
            logger.debug("Applied Code Intelligence Refresh: {}", selectedRefresh);
        }

        if (!currentAnalyzerLanguagesForDialog.equals(project.getAnalyzerLanguages())) {
            project.setAnalyzerLanguages(currentAnalyzerLanguagesForDialog);
            logger.debug("Applied Code Intelligence Languages: {}", currentAnalyzerLanguagesForDialog);
            chrome.getContextManager().requestRebuild();
        }

        // Primary language
        var selectedPrimaryLang = (Language) primaryLanguageComboBox.getSelectedItem();
        if (selectedPrimaryLang != null && selectedPrimaryLang != project.getBuildLanguage()) {
            project.setBuildLanguage(selectedPrimaryLang);
            logger.debug("Applied Primary Language: {}", selectedPrimaryLang);
        }

        // JDK Controls (only for Java)
        if (selectedPrimaryLang == Languages.JAVA) {
            if (setJavaHomeCheckbox.isSelected()) {
                var selPath = jdkSelector.getSelectedJdkPath();
                if (selPath != null && !selPath.isBlank()) {
                    project.setJdk(selPath);
                }
            } else {
                project.setJdk(BuildAgent.JAVA_HOME_SENTINEL);
            }
        }

        // Apply executor configuration
        String currentExecutorPath = project.getCommandExecutor();
        String currentExecutorArgs = project.getExecutorArgs();
        String newExecutorPath = executorPathField.getText().trim();
        String newExecutorArgs = executorArgsField.getText().trim();

        // Set to null if empty to clear the configuration
        String pathToSet = newExecutorPath.isEmpty() ? null : newExecutorPath;
        String argsToSet = newExecutorArgs.isEmpty() ? null : newExecutorArgs;

        if (!Objects.equals(currentExecutorPath, pathToSet)) {
            project.setCommandExecutor(pathToSet);
            logger.debug("Applied Custom Executor Path: {}", pathToSet);
        }

        if (!Objects.equals(currentExecutorArgs, argsToSet)) {
            project.setExecutorArgs(argsToSet);
            logger.debug("Applied Custom Executor Args: {}", argsToSet);
        }

        // Data Retention Tab
        if (dataRetentionPanelInner != null) dataRetentionPanelInner.applyPolicy();

        /* Persist any analyzer-specific settings (currently only the Java JDK home). */
        for (AnalyzerSettingsPanel panel : analyzerSettingsCache.values()) {
            panel.saveSettings();
        }

        // After applying data retention, model list might need refresh
        chrome.getContextManager().submitBackgroundTask("Refreshing models due to policy change", () -> {
            chrome.getContextManager().reloadModelsAsync();
        });

        return true;
    }

    public void showBuildBanner() {
        bannerPanel.setVisible(true);
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

    @Override
    public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
        // Word wrap not applicable to settings project panel
        SwingUtilities.updateComponentTreeUI(this);
    }

    private void populateJdkControlsFromProject() {
        var project = chrome.getProject();
        var desired = project.getJdk();

        boolean useCustomJdk = desired != null && !BuildAgent.JAVA_HOME_SENTINEL.equals(desired);
        setJavaHomeCheckbox.setSelected(useCustomJdk);
        jdkSelector.setEnabled(useCustomJdk);

        // Always populate the selector; it will select 'desired' if provided
        jdkSelector.loadJdksAsync(desired);
    }

    private void updateJdkControlsVisibility(@Nullable Language selected) {
        boolean isJava = selected == Languages.JAVA;
        setJavaHomeCheckbox.setVisible(isJava);
        jdkSelector.setVisible(isJava);
    }

    private void populatePrimaryLanguageComboBox() {
        var project = chrome.getProject();
        var detected = findLanguagesInProject(project);
        var configured = project.getBuildLanguage();
        if (!detected.contains(configured)) {
            detected.add(configured);
        }
        // Sort by display name
        detected.sort(Comparator.comparing(Language::name));
        primaryLanguageComboBox.setModel(new DefaultComboBoxModel<>(detected.toArray(Language[]::new)));
    }

    private List<Language> findLanguagesInProject(IProject project) {
        Set<Language> langs = new HashSet<>();
        Set<io.github.jbellis.brokk.analyzer.ProjectFile> filesToScan =
                project.hasGit() ? project.getRepo().getTrackedFiles() : project.getAllFiles();
        for (var pf : filesToScan) {
            String extension =
                    com.google.common.io.Files.getFileExtension(pf.absPath().toString());
            if (!extension.isEmpty()) {
                var lang = Languages.fromExtension(extension);
                if (lang != Languages.NONE) {
                    langs.add(lang);
                }
            }
        }
        return new ArrayList<>(langs);
    }

    private void initializeExecutorUI() {
        // Set up tooltips
        executorPathField.setToolTipText("Path to custom command executor (shell, interpreter, etc.)");
        executorArgsField.setToolTipText("Arguments to pass to executor (default: " + DEFAULT_EXECUTOR_ARGS + ")");
        executorArgsField.setText(DEFAULT_EXECUTOR_ARGS); // Set default value

        // Populate common executors dropdown
        var commonExecutors = ExecutorValidator.getCommonExecutors();
        commonExecutorsComboBox.setModel(new DefaultComboBoxModel<>(commonExecutors));
        // pre-select the system default if present
        for (int i = 0; i < commonExecutors.length; i++) {
            if (commonExecutors[i].equalsIgnoreCase(DEFAULT_EXECUTOR_PATH)) {
                commonExecutorsComboBox.setSelectedIndex(i);
                break;
            }
        }

        // Reset button action
        resetExecutorButton.addActionListener(e -> resetExecutor());

        // Common executors selection action
        commonExecutorsComboBox.addActionListener(e -> {
            String selected = (String) commonExecutorsComboBox.getSelectedItem();
            if (selected != null && !selected.isEmpty()) {
                executorPathField.setText(selected);
            }
        });

        // Test executor button action
        testExecutorButton.addActionListener(e -> testExecutor());
    }

    private void resetExecutor() {
        // Restore defaults
        executorPathField.setText(DEFAULT_EXECUTOR_PATH);
        executorArgsField.setText(DEFAULT_EXECUTOR_ARGS);

        // Clear the project configuration immediately
        var project = chrome.getProject();
        project.setCommandExecutor(null);
        project.setExecutorArgs(null);

        // Reset combo-box to default option if available
        for (int i = 0; i < commonExecutorsComboBox.getItemCount(); i++) {
            if (DEFAULT_EXECUTOR_PATH.equalsIgnoreCase(commonExecutorsComboBox.getItemAt(i))) {
                commonExecutorsComboBox.setSelectedIndex(i);
                break;
            }
        }
    }

    private void testExecutor() {
        String executorPath = executorPathField.getText().trim();
        String executorArgs = executorArgsField.getText().trim();

        if (executorPath.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Please specify an executor path first.",
                    "No Executor Specified",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Default args if empty
        if (executorArgs.isEmpty()) {
            executorArgs = DEFAULT_EXECUTOR_ARGS;
        }

        testExecutorButton.setEnabled(false);
        testExecutorButton.setText("Testing...");

        // Make variables effectively final for use in inner class
        final String finalExecutorPath = executorPath;
        final String finalExecutorArgs = executorArgs;

        SwingWorker<ExecutorValidator.ValidationResult, Void> worker = new SwingWorker<>() {
            @Override
            protected ExecutorValidator.ValidationResult doInBackground() {
                String[] argsArray = finalExecutorArgs.split("\\s+");
                var config = new ExecutorConfig(finalExecutorPath, Arrays.asList(argsArray));
                return ExecutorValidator.validateExecutor(config);
            }

            @Override
            protected void done() {
                testExecutorButton.setEnabled(true);
                testExecutorButton.setText("Test");

                try {
                    var result = get();
                    if (result.success()) {
                        String[] argsArray = finalExecutorArgs.split("\\s+");
                        var config = new ExecutorConfig(finalExecutorPath, Arrays.asList(argsArray));
                        String sandboxInfo = ExecutorValidator.getSandboxLimitation(config);

                        JOptionPane.showMessageDialog(
                                SettingsProjectPanel.this,
                                result.message() + "\n\n" + sandboxInfo,
                                "Executor Test Successful",
                                JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(
                                SettingsProjectPanel.this, result.message(), "Test Failed", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception ex) {
                    logger.error("Error during executor test", ex);
                    JOptionPane.showMessageDialog(
                            SettingsProjectPanel.this,
                            "Test failed with error: " + ex.getMessage(),
                            "Executor Test Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    // Static inner class DataRetentionPanel (Copied and adapted from SettingsDialog)
    public static class DataRetentionPanel extends JPanel {
        private final IProject project;
        private final @Nullable SettingsProjectPanel parentProjectPanel; // For triggering model refresh
        private final ButtonGroup policyGroup;
        private final JRadioButton improveRadio;
        private final JLabel improveDescLabel;
        private final JRadioButton minimalRadio;
        private final JLabel minimalDescLabel;
        private final JLabel orgDisabledLabel;
        private final JLabel infoLabel;

        public DataRetentionPanel(IProject project, @Nullable SettingsProjectPanel parentProjectPanel) {
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
            improveDescLabel = new JLabel(
                    "<html>Allow Brokk and/or its partners to use requests from this project to train models and improve the Brokk service.</html>");
            improveDescLabel.setFont(improveDescLabel
                    .getFont()
                    .deriveFont(Font.ITALIC, improveDescLabel.getFont().getSize() * 0.9f));

            minimalRadio = new JRadioButton(DataRetentionPolicy.MINIMAL.getDisplayName());
            minimalRadio.putClientProperty("policy", DataRetentionPolicy.MINIMAL);
            policyGroup.add(minimalRadio);
            minimalDescLabel = new JLabel(
                    "<html>Brokk will not share data from this project with anyone and will restrict its use to the minimum necessary to provide the Brokk service.</html>");
            minimalDescLabel.setFont(minimalDescLabel
                    .getFont()
                    .deriveFont(Font.ITALIC, minimalDescLabel.getFont().getSize() * 0.9f));

            orgDisabledLabel = new JLabel("<html><b>Data sharing is disabled by your organization.</b></html>");
            infoLabel = new JLabel(
                    "<html>Data retention policy affects which AI models are allowed. In particular, Deepseek models are not available under the Essential Use Only policy, since Deepseek will train on API requests independently of Brokk.</html>");
            infoLabel.setFont(infoLabel.getFont().deriveFont(infoLabel.getFont().getSize() * 0.9f));

            layoutControls();
            loadPolicy();
        }

        private void layoutControls() {
            removeAll();
            var gbc = new GridBagConstraints();
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
                improveRadio.setVisible(true);
                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(0, 25, 10, 5);
                add(improveDescLabel, gbc);
                improveDescLabel.setVisible(true);
                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(5, 5, 0, 5);
                add(minimalRadio, gbc);
                minimalRadio.setVisible(true);
                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(0, 25, 10, 5);
                add(minimalDescLabel, gbc);
                minimalDescLabel.setVisible(true);
                orgDisabledLabel.setVisible(false);
            } else {
                improveRadio.setVisible(false);
                improveDescLabel.setVisible(false);
                minimalRadio.setVisible(false);
                minimalDescLabel.setVisible(false);
                gbc.gridx = 0;
                gbc.gridy = y++;
                gbc.insets = new Insets(5, 5, 10, 5);
                add(orgDisabledLabel, gbc);
                orgDisabledLabel.setVisible(true);
            }
            gbc.insets = new Insets(15, 5, 5, 5);
            infoLabel.setVisible(true);
            gbc.gridx = 0;
            gbc.gridy = y++;
            add(infoLabel, gbc);
            gbc.gridx = 0;
            gbc.gridy = y;
            gbc.weighty = 1.0;
            gbc.fill = GridBagConstraints.VERTICAL;
            add(Box.createVerticalGlue(), gbc);
            revalidate();
            repaint();
        }

        public void refreshStateAndUI() {
            layoutControls();
            loadPolicy();
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
                    if (parentProjectPanel != null) {
                        // Trigger model list refresh in parent dialog or chrome context manager
                        parentProjectPanel
                                .parentDialog
                                .getChrome()
                                .getContextManager()
                                .reloadModelsAsync();
                        // Also need to refresh model selection UI in SettingsGlobalPanel
                        parentProjectPanel.parentDialog.refreshGlobalModelsPanelPostPolicyChange();
                    }
                }
            } // else this is standalone data retention dialog
        }
    }
}
