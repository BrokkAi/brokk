package io.github.jbellis.brokk.gui.dialogs;

import eu.hansolo.fx.jdkmon.tools.Distro;
import eu.hansolo.fx.jdkmon.tools.Finder;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.IssueProvider;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.MainProject.DataRetentionPolicy;
import io.github.jbellis.brokk.McpConfig;
import io.github.jbellis.brokk.McpServer;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.dialogs.analyzer.AnalyzerSettingsPanel;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.issues.FilterOptions;
import io.github.jbellis.brokk.issues.IssuesProviderConfig;
import io.github.jbellis.brokk.issues.JiraFilterOptions;
import io.github.jbellis.brokk.issues.JiraIssueService;
import io.github.jbellis.brokk.util.Environment;
import java.awt.*;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.BorderFactory;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentListener;
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
    private JComboBox<MainProject.CpgRefresh> cpgRefreshComboBox = new JComboBox<>(new MainProject.CpgRefresh[] {
        IProject.CpgRefresh.AUTO, IProject.CpgRefresh.ON_RESTART, IProject.CpgRefresh.MANUAL
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

    private DefaultListModel<McpServer> mcpServersListModel = new DefaultListModel<>();
    private JList<McpServer> mcpServersList = new JList<>(mcpServersListModel);

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
    private JComboBox<JdkItem> jdkComboBox = new JComboBox<>();
    private JComboBox<Language> primaryLanguageComboBox = new JComboBox<>();

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
    private final List<AnalyzerSettingsPanel> analyzerSettingsPanels = new ArrayList<>();

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

        // Analyzers Tab (New)
        var analyzersPanel = createAnalyzersPanel();
        projectSubTabbedPane.addTab("Analyzers", null, analyzersPanel, "Code analyzers configured for this project");

        // Data Retention Tab
        dataRetentionPanelInner = new DataRetentionPanel(project, this);
        projectSubTabbedPane.addTab(
                "Data Retention", null, dataRetentionPanelInner, "Data retention policy for this project");

        // MCP Servers Tab
        var mcpPanel = createMcpPanel();
        projectSubTabbedPane.addTab("MCP Servers", null, mcpPanel, "MCP server configuration");

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
    private JPanel createAnalyzersPanel() {
        final var analyzersPanel = new JPanel(new BorderLayout(5, 5));
        analyzersPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        final Set<Language> analyzerLanguages = chrome.getProject().getAnalyzerLanguages();
        final Path projectRoot = chrome.getProject().getRoot();

        if (analyzerLanguages.isEmpty()) {
            final var noneLabel = new JLabel("No analyzers configured for this project.");
            noneLabel.setHorizontalAlignment(SwingConstants.CENTER);
            analyzersPanel.add(noneLabel, BorderLayout.CENTER);
        } else {
            /* Build a vertical list of sub-panels – one per analyzer */
            final var container = new JPanel();
            container.setLayout(new BoxLayout(container, BoxLayout.PAGE_AXIS));
            container.setBorder(BorderFactory.createEmptyBorder());

            analyzerLanguages.forEach(language -> {
                final AnalyzerSettingsPanel panel = AnalyzerSettingsPanel.createAnalyzersPanel(
                        SettingsProjectPanel.this,
                        language,
                        projectRoot,
                        chrome.getContextManager().getIo());
                analyzerSettingsPanels.add(panel);
                final var languageLabel = new JLabel(language.name());
                languageLabel.setHorizontalAlignment(SwingConstants.LEFT);
                container.add(languageLabel);
                container.add(panel);
            });

            final JScrollPane scrollPane = new JScrollPane(container);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            analyzersPanel.add(scrollPane, BorderLayout.CENTER);
        }

        return analyzersPanel;
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
                        SettingsProjectPanel.this,
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
            if (!displayToken.regionMatches(true, 0, "Bearer ", 0, 7)) {
                displayToken = "Bearer " + displayToken;
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

        JLabel urlErrorLabel = createMcpServerUrlErrorLabel();
        urlField.getDocument().addDocumentListener(createUrlValidationListener(urlField, urlErrorLabel));

        JPanel panel = new JPanel(new GridLayout(0, 1));
        panel.add(new JLabel("Name:"));
        panel.add(nameField);
        panel.add(new JLabel("URL:"));
        panel.add(urlField);
        panel.add(urlErrorLabel);
        panel.add(useTokenCheckbox);
        panel.add(tokenLabel);
        panel.add(tokenPanel);

        var optionPane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        final var dialog = optionPane.createDialog(SettingsProjectPanel.this, title);

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
                List<String> existingTools = (existing != null) ? existing.tools() : null;
                McpServer newServer = createMcpServerFromInputs(name, rawUrl, useToken, tokenField, existingTools);

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
     * <p>This method also normalizes bearer token inputs to start with "Bearer " and updates the provided tokenField
     * with the normalized value (so the user sees the normalized form).
     */
    private @Nullable McpServer createMcpServerFromInputs(
            String name,
            String rawUrl,
            boolean useToken,
            JPasswordField tokenField,
            @Nullable List<String> existingTools) {

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
                if (!raw.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    raw = "Bearer " + raw;
                    // Update displayed field so the user sees the normalized form
                    tokenField.setText(raw);
                }
                token = raw;
            } else {
                token = null; // empty token => treat as null
            }
        }

        return new McpServer(name, url, existingTools, token);
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

        jdkComboBox.setEnabled(false);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        buildPanel.add(jdkComboBox, gbc);

        primaryLanguageComboBox.addActionListener(e -> {
            var sel = (Language) primaryLanguageComboBox.getSelectedItem();
            updateJdkControlsVisibility(sel);
            if (sel == Language.JAVA) {
                populateJdkControlsFromProject();
            }
        });

        // Initial visibility based on current project setting
        updateJdkControlsVisibility(project.getBuildLanguage());

        setJavaHomeCheckbox.addActionListener(e -> jdkComboBox.setEnabled(setJavaHomeCheckbox.isSelected()));

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
                "<html>Use a placeholder like {{FILE_OR_CLASS_PATH}} for the part that will be replaced.</html>");
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

        // Removed Build Instructions Area and its ScrollPane

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.weighty = 0.0; // Ensure weighty is reset before this
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        buildPanel.add(new JLabel("CI Refresh:"), gbc);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        buildPanel.add(cpgRefreshComboBox, gbc);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.WEST;
        buildPanel.add(new JLabel("CI Languages:"), gbc);
        languagesDisplayField.setEditable(false);
        // currentAnalyzerLanguagesForDialog is initialized at declaration and populated in loadBuildPanelSettings
        this.editLanguagesButton.addActionListener(e -> showLanguagesDialog(project));
        var languagesPanel = new JPanel(new BorderLayout(5, 0));
        languagesPanel.add(languagesDisplayField, BorderLayout.CENTER);
        languagesPanel.add(this.editLanguagesButton, BorderLayout.EAST);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        buildPanel.add(languagesPanel, gbc);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        buildPanel.add(new JLabel("CI Exclusions:"), gbc);
        excludedDirectoriesList.setVisibleRowCount(3);
        // excludedScrollPane is initialized at declaration with excludedDirectoriesList
        gbc.gridx = 1;
        gbc.gridy = row;
        gbc.weightx = 1.0;
        gbc.weighty = 0.5;
        gbc.fill = GridBagConstraints.BOTH;
        buildPanel.add(this.excludedScrollPane, gbc);

        var excludedButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        excludedButtonsPanel.add(this.addExcludedDirButton);
        excludedButtonsPanel.add(this.removeExcludedDirButton);
        gbc.gridy = row + 1;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(2, 0, 2, 2);
        buildPanel.add(excludedButtonsPanel, gbc);
        row += 2;
        gbc.insets = new Insets(2, 2, 2, 2);

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

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 0.0;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
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

    private void showLanguagesDialog(IProject project) {
        var dialog = new JDialog(parentDialog, "Select Languages for Analysis", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(300, 400);
        dialog.setLocationRelativeTo(parentDialog);

        var languageCheckBoxMapLocal = new LinkedHashMap<Language, JCheckBox>();
        var languagesInProject = new HashSet<io.github.jbellis.brokk.analyzer.Language>();
        project.getRoot();
        Set<io.github.jbellis.brokk.analyzer.ProjectFile> filesToScan =
                project.hasGit() ? project.getRepo().getTrackedFiles() : project.getAllFiles();
        for (var pf : filesToScan) {
            String extension =
                    com.google.common.io.Files.getFileExtension(pf.absPath().toString());
            if (!extension.isEmpty()) {
                var lang = io.github.jbellis.brokk.analyzer.Language.fromExtension(extension);
                if (lang != io.github.jbellis.brokk.analyzer.Language.NONE) languagesInProject.add(lang);
            }
        }

        var checkBoxesPanel = new JPanel();
        checkBoxesPanel.setLayout(new BoxLayout(checkBoxesPanel, BoxLayout.PAGE_AXIS));
        checkBoxesPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var sortedLanguagesToShow = languagesInProject.stream()
                .sorted(Comparator.comparing(io.github.jbellis.brokk.analyzer.Language::name))
                .toList();
        for (var lang : sortedLanguagesToShow) {
            var checkBox = new JCheckBox(lang.name());
            checkBox.setSelected(currentAnalyzerLanguagesForDialog.contains(lang));
            languageCheckBoxMapLocal.put(lang, checkBox);
            checkBoxesPanel.add(checkBox);
        }
        if (sortedLanguagesToShow.isEmpty()) checkBoxesPanel.add(new JLabel("No analyzable languages detected."));

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okBtn = new JButton("OK");
        var cancelBtn = new JButton("Cancel");
        buttonPanel.add(okBtn);
        buttonPanel.add(cancelBtn);
        okBtn.addActionListener(e -> {
            currentAnalyzerLanguagesForDialog.clear();
            for (var entry : languageCheckBoxMapLocal.entrySet())
                if (entry.getValue().isSelected()) currentAnalyzerLanguagesForDialog.add(entry.getKey());
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

        // MCP Servers Tab
        mcpServersListModel.clear();
        var mcpConfig = chrome.getProject().getMcpConfig();
        for (McpServer server : mcpConfig.servers()) {
            mcpServersListModel.addElement(server);
        }
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
                currentRefresh == IProject.CpgRefresh.UNSET ? IProject.CpgRefresh.AUTO : currentRefresh);

        // Primary language
        populatePrimaryLanguageComboBox();
        var selectedLang = project.getBuildLanguage();
        primaryLanguageComboBox.setSelectedItem(selectedLang);
        updateJdkControlsVisibility(selectedLang);
        if (selectedLang == Language.JAVA) {
            populateJdkControlsFromProject();
        }

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

        var selectedRefresh = (MainProject.CpgRefresh) cpgRefreshComboBox.getSelectedItem();
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
        if (selectedPrimaryLang == Language.JAVA) {
            if (setJavaHomeCheckbox.isSelected()) {
                var sel = (JdkItem) jdkComboBox.getSelectedItem();
                if (sel != null && !sel.path.isBlank()) {
                    project.setJdk(sel.path);
                }
            } else {
                project.setJdk(BuildAgent.JAVA_HOME_SENTINEL);
            }
        }

        // Data Retention Tab
        if (dataRetentionPanelInner != null) dataRetentionPanelInner.applyPolicy();

        /* Persist any analyzer-specific settings (currently only the Java JDK home). */
        for (AnalyzerSettingsPanel panel : analyzerSettingsPanels) {
            panel.saveSettings();
        }

        // MCP Servers Tab
        var servers = new ArrayList<McpServer>();
        for (int i = 0; i < mcpServersListModel.getSize(); i++) {
            servers.add(mcpServersListModel.getElementAt(i));
        }
        var newMcpConfig = new McpConfig(servers);
        project.setMcpConfig(newMcpConfig);

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

    private static class JdkItem {
        final String display;
        final String path;

        JdkItem(String display, String path) {
            this.display = display;
            this.path = path;
        }

        @Override
        public String toString() {
            return display;
        }
    }

    private List<JdkItem> discoverInstalledJdks() {
        try {
            var finder = new Finder();
            var distros = finder.getDistributions();
            var items = new ArrayList<JdkItem>();
            for (Distro d : distros) {
                var name = d.getName();
                var ver = d.getVersion();
                var arch = d.getArchitecture();
                var path = d.getPath() != null && !d.getPath().isBlank() ? d.getPath() : d.getLocation();
                if (path == null || path.isBlank()) continue;
                var label = String.format("%s %s (%s)", name, ver, arch);
                items.add(new JdkItem(label, path));
            }
            items.sort(Comparator.comparing(it -> it.display));
            return items;
        } catch (Throwable t) {
            logger.warn("Failed to discover installed JDKs", t);
            return List.of();
        }
    }

    private void populateJdkControlsFromProject() {
        var project = chrome.getProject();
        var desired = project.getJdk();

        // Initialize controls immediately on EDT without blocking
        boolean useCustomJdk = desired != null && !BuildAgent.JAVA_HOME_SENTINEL.equals(desired);
        setJavaHomeCheckbox.setSelected(useCustomJdk);
        jdkComboBox.setEnabled(useCustomJdk);

        // Perform discovery asynchronously to avoid blocking the UI
        CompletableFuture.supplyAsync(this::discoverInstalledJdks, ForkJoinPool.commonPool())
                .whenComplete((List<JdkItem> items, @Nullable Throwable ex) -> {
                    if (ex != null) {
                        logger.warn("JDK discovery failed: {}", ex.getMessage(), ex);
                        items = List.of();
                    }
                    var finalItems = items;
                    SwingUtilities.invokeLater(() -> {
                        jdkComboBox.setModel(new DefaultComboBoxModel<>(finalItems.toArray(JdkItem[]::new)));

                        // Only try to select if user intends to set JAVA_HOME
                        if (setJavaHomeCheckbox.isSelected()) {
                            boolean matched = false;
                            for (int i = 0; i < jdkComboBox.getItemCount(); i++) {
                                var it = jdkComboBox.getItemAt(i);
                                if (desired != null && desired.equals(it.path)) {
                                    jdkComboBox.setSelectedIndex(i);
                                    matched = true;
                                    break;
                                }
                            }
                            if (!matched
                                    && desired != null
                                    && !desired.isBlank()
                                    && !BuildAgent.JAVA_HOME_SENTINEL.equals(desired)) {
                                var custom = new JdkItem("Custom JDK: " + desired, desired);
                                jdkComboBox.addItem(custom);
                                jdkComboBox.setSelectedItem(custom);
                            }
                            jdkComboBox.setEnabled(true);
                        } else {
                            jdkComboBox.setEnabled(false);
                        }
                        logger.trace(
                                "JDK discovery completed; combo box populated with {} items",
                                jdkComboBox.getItemCount());
                    });
                });
    }

    private void updateJdkControlsVisibility(@Nullable Language selected) {
        boolean isJava = selected == Language.JAVA;
        setJavaHomeCheckbox.setVisible(isJava);
        jdkComboBox.setVisible(isJava);
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
                var lang = io.github.jbellis.brokk.analyzer.Language.fromExtension(extension);
                if (lang != io.github.jbellis.brokk.analyzer.Language.NONE) {
                    langs.add(lang);
                }
            }
        }
        return new ArrayList<>(langs);
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
