package ai.brokk.gui.dialogs;

import ai.brokk.AbstractProject;
import ai.brokk.IConsoleIO;
import ai.brokk.IProject;
import ai.brokk.IssueProvider;
import ai.brokk.MainProject;
import ai.brokk.MainProject.DataRetentionPolicy;
import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.Icons;
import ai.brokk.issues.FilterOptions;
import ai.brokk.issues.IssueProviderType;
import ai.brokk.issues.IssuesProviderConfig;
import ai.brokk.issues.JiraFilterOptions;
import ai.brokk.issues.JiraIssueService;
import com.google.common.io.Files;
import java.awt.*;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.BorderFactory;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class SettingsProjectPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(SettingsProjectPanel.class);
    public static final int BUILD_TAB_INDEX = 1; // General(0), Build(1), Data Retention(2)

    private final Chrome chrome;
    private final SettingsDialog parentDialog;

    // General UI Components
    private JTextArea styleGuideArea = new JTextArea(5, 40);
    private JTextArea commitFormatArea = new JTextArea(5, 40);

    @Nullable
    private JTextArea reviewGuideArea;

    // CI Exclusions (moved to build panel; kept list model only for short-term compatibility removal)
    // Analyzer-related UI
    private DefaultListModel<String> excludedDirectoriesListModel = new DefaultListModel<>();
    private JList<String> excludedDirectoriesList = new JList<>(excludedDirectoriesListModel);
    private JScrollPane excludedScrollPane = new JScrollPane(excludedDirectoriesList);
    private MaterialButton addExcludedDirButton = new MaterialButton();
    private MaterialButton removeExcludedDirButton = new MaterialButton();

    private Set<Language> currentAnalyzerLanguagesForDialog = new HashSet<>();

    private JTabbedPane projectSubTabbedPane = new JTabbedPane(JTabbedPane.TOP);

    // Issue Provider related UI
    private JComboBox<IssueProviderType> issueProviderTypeComboBox = new JComboBox<>(IssueProviderType.values());
    private CardLayout issueProviderCardLayout = new CardLayout();
    private JPanel issueProviderConfigPanel = new JPanel(issueProviderCardLayout);

    // GitHub specific fields
    private JTextField githubOwnerField = new JTextField(20);
    private JTextField githubRepoField = new JTextField(20);
    private JTextField githubHostField = new JTextField(20);
    private JCheckBox githubOverrideCheckbox = new JCheckBox("Fetch issues from a different GitHub repository");

    private static final String NONE_CARD = "None";
    private static final String GITHUB_CARD = "GitHub";
    private static final String JIRA_CARD = "Jira";

    // Jira specific fields
    private JTextField jiraProjectKeyField = new JTextField();
    private JTextField jiraBaseUrlField = new JTextField();
    private JPasswordField jiraApiTokenField = new JPasswordField();
    private MaterialButton testJiraConnectionButton = new MaterialButton("Test Jira Connection");

    // Holds the analyzer configuration panels so we can persist their settings when the user clicks Apply/OK.
    private final LinkedHashMap<Language, AnalyzerSettingsPanel> analyzerSettingsCache = new LinkedHashMap<>();

    // Buttons from parent dialog that might need to be disabled/enabled by build agent
    private final JButton okButtonParent;
    private final JButton cancelButtonParent;
    private final JButton applyButtonParent;

    // Build panel instance (extracted)
    private SettingsProjectBuildPanel buildPanelInstance;

    @Nullable
    private LanguagesTableModel languagesTableModel;

    // Pre-generated style guide content (Optional.empty() means read from disk)
    private final Optional<String> providedStyleGuide;

    public SettingsProjectPanel(
            Chrome chrome, SettingsDialog parentDialog, JButton okButton, JButton cancelButton, JButton applyButton) {
        this(chrome, parentDialog, okButton, cancelButton, applyButton, Optional.empty());
    }

    public SettingsProjectPanel(
            Chrome chrome,
            SettingsDialog parentDialog,
            JButton okButton,
            JButton cancelButton,
            JButton applyButton,
            Optional<String> providedStyleGuide) {
        this.chrome = chrome;
        this.parentDialog = parentDialog;
        this.okButtonParent = okButton;
        this.cancelButtonParent = cancelButton;
        this.applyButtonParent = applyButton;
        this.providedStyleGuide = providedStyleGuide;

        setLayout(new BorderLayout());
        initComponents();
        // NOTE: loadSettings() is now called explicitly after dialog construction
        // to avoid race condition with background file writes
    }

    private void initComponents() {
        var project = chrome.getProject();
        this.setEnabled(true); // Ensure panel is enabled if project exists

        // General Tab (formerly Other)
        var generalPanel = createGeneralPanel();
        projectSubTabbedPane.addTab("General", null, generalPanel, "General project settings");

        // Build Tab - extracted into its own class
        buildPanelInstance = new SettingsProjectBuildPanel(
                chrome, parentDialog, okButtonParent, cancelButtonParent, applyButtonParent);
        projectSubTabbedPane.addTab(
                "Build", null, buildPanelInstance, "Build configuration and Code Intelligence settings");

        // Issues Tab (New)
        var issuesPanel = createIssuesPanel();
        projectSubTabbedPane.addTab("Issues", null, issuesPanel, "Issue tracker integration settings");

        // Code Intelligence Tab (replaces old Analyzers tab)
        var codeIntPanel = createCodeIntelligencePanel();
        projectSubTabbedPane.addTab(
                "Code Intelligence", null, codeIntPanel, "Code intelligence settings and analyzers");

        // Data Retention Tab
        var dataRetentionPanelInner = new DataRetentionPanel(project, this);
        projectSubTabbedPane.addTab(
                "Data Retention", null, dataRetentionPanelInner, "Data retention policy for this project");

        add(projectSubTabbedPane, BorderLayout.CENTER);
    }

    public JTabbedPane getProjectSubTabbedPane() {
        return projectSubTabbedPane;
    }

    // Update CI exclusions list model safely and consistently (EDT, sorted, deduped case-insensitively)
    public void updateExcludedDirectories(@Nullable Collection<String> dirs) {
        Runnable r = () -> {
            try {
                var unique = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
                if (dirs != null) {
                    unique.addAll(dirs);
                }
                excludedDirectoriesListModel.clear();
                for (String d : unique) {
                    excludedDirectoriesListModel.addElement(d);
                }
            } catch (Exception ex) {
                logger.warn("Failed to update CI exclusions list model: {}", ex.getMessage(), ex);
                excludedDirectoriesListModel.clear();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
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

        // Migration button (visible only if migration is pending)
        var migrateButton = new MaterialButton("Migrate style.md to AGENTS.md");
        migrateButton.addActionListener(e -> {
            var mp = chrome.getProject();
            if (mp instanceof MainProject mainProject) {
                migrateButton.setEnabled(false);
                boolean success = mainProject.performStyleMdToAgentsMdMigration(chrome);
                if (success) {
                    // Migration succeeded - hide button and refresh UI
                    migrateButton.setVisible(false);
                    SwingUtilities.invokeLater(() -> loadSettings());
                } else {
                    // Migration failed - re-enable button
                    SwingUtilities.invokeLater(() -> migrateButton.setEnabled(true));
                }
            }
        });

        // Check if migration button should be visible
        try {
            var projectRoot = chrome.getProject().getMasterRootPathForConfig();
            var brokkDir = projectRoot.resolve(AbstractProject.BROKK_DIR);
            boolean styleExists = java.nio.file.Files.exists(brokkDir.resolve("style.md"));
            boolean agentsExists = java.nio.file.Files.exists(projectRoot.resolve("AGENTS.md"));
            migrateButton.setVisible(styleExists && !agentsExists);
            if (migrateButton.isVisible()) {
                migrateButton.setToolTipText("Migrate .brokk/style.md to AGENTS.md at project root");
            }
        } catch (Exception ex) {
            migrateButton.setVisible(false);
        }

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(5, 2, 15, 2); // Extra bottom padding to separate from next section
        generalPanel.add(migrateButton, gbc);

        // Reset for next components
        gbc.gridwidth = 1;
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
                if (value instanceof IssueProviderType type) {
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
            IssueProviderType selectedType = (IssueProviderType) issueProviderTypeComboBox.getSelectedItem();
            if (selectedType == null) selectedType = IssueProviderType.NONE; // Should not happen with enum
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

        languagesTableModel = new LanguagesTableModel(languagesToShow);
        var table = new JTable(languagesTableModel);
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
        for (int r = 0; r < languagesTableModel.getRowCount(); r++) {
            var comp = table.getCellRenderer(r, 1)
                    .getTableCellRendererComponent(table, languagesTableModel.getValueAt(r, 1), false, false, r, 1);
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

        var refreshBtn = new MaterialButton("Refresh Code Intelligence Now");
        refreshBtn.addActionListener(e -> {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Requesting analyzer refresh...");
            try {
                chrome.getContextManager().requestRebuild();
            } catch (Exception ex) {
                logger.error("Failed to request rebuild", ex);
                chrome.toolError("Failed to request rebuild: " + ex.getMessage());
            }
        });
        toolbar.add(refreshBtn);

        // User-configured CI Exclusions are stored in BuildDetails and can be edited here.
        var ciPanel = new JPanel(new GridBagLayout());
        var gbcCi = new GridBagConstraints();
        gbcCi.insets = new Insets(2, 2, 2, 2);
        gbcCi.fill = GridBagConstraints.HORIZONTAL;
        int ciRow = 0;

        gbcCi.gridx = 0;
        gbcCi.gridy = ciRow;
        gbcCi.weightx = 0.0;
        gbcCi.anchor = GridBagConstraints.NORTHWEST;
        var ciExclusionsLabel = new JLabel("CI Exclusions:");
        ciExclusionsLabel.setToolTipText(
                "<html>User-configured directories to exclude from Code Intelligence.<br>Additional exclusions from .gitignore and for unmanaged dependencies are applied automatically.</html>");
        ciPanel.add(ciExclusionsLabel, gbcCi);
        excludedDirectoriesList.setVisibleRowCount(3);
        gbcCi.gridx = 1;
        gbcCi.gridy = ciRow++;
        gbcCi.weightx = 1.0;
        gbcCi.weighty = 0.5;
        gbcCi.fill = GridBagConstraints.BOTH;
        ciPanel.add(this.excludedScrollPane, gbcCi);

        var excludedButtonsPanel2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        this.addExcludedDirButton.setIcon(Icons.ADD);
        this.addExcludedDirButton.setToolTipText("Add");
        this.removeExcludedDirButton.setIcon(Icons.REMOVE);
        this.removeExcludedDirButton.setToolTipText("Remove");

        excludedButtonsPanel2.add(this.addExcludedDirButton);
        excludedButtonsPanel2.add(Box.createHorizontalStrut(5));
        excludedButtonsPanel2.add(this.removeExcludedDirButton);
        gbcCi.gridy = ciRow++;
        gbcCi.weighty = 0.0;
        gbcCi.fill = GridBagConstraints.HORIZONTAL;
        gbcCi.anchor = GridBagConstraints.WEST;
        ciPanel.add(excludedButtonsPanel2, gbcCi);

        // Wire add/remove actions for the exclusions buttons.
        this.addExcludedDirButton.addActionListener(e -> {
            String newDir = JOptionPane.showInputDialog(
                    parentDialog,
                    "Enter directory to exclude (e.g., target/, build/):",
                    "Add Excluded Directory",
                    JOptionPane.PLAIN_MESSAGE);
            if (newDir != null && !newDir.trim().isEmpty()) {
                String trimmedNewDir = newDir.trim();
                List<String> currentElements = Collections.list(excludedDirectoriesListModel.elements());
                if (!currentElements.contains(trimmedNewDir)) { // Avoid duplicates
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
            int fileCount = project.getAnalyzableFiles(lang).size();
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
        if (languagesTableModel.getRowCount() > 0) {
            int maxModelIdx = 0;
            int maxCount = -1;
            for (int i = 0; i < languagesToShow.size(); i++) {
                int cnt = project.getAnalyzableFiles(languagesToShow.get(i)).size();
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

    private class LanguagesTableModel extends AbstractTableModel {
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
                var testProvider = new IssueProvider(IssueProviderType.JIRA, jiraConfig);

                JiraIssueService testService = new JiraIssueService(testProvider, chrome.getProject());
                try {
                    FilterOptions filterOptions = new JiraFilterOptions(null, null, null, null, null, null);
                    testService.listIssues(filterOptions); // This will use the temporary provider
                    return "Connection successful!";
                } catch (IOException ioException) {
                    logger.warn("Jira connection test failed: {}", ioException.getMessage());
                    return "Connection failed: " + ioException.getMessage();
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

    public void loadSettings() {
        var project = chrome.getProject();

        // General Tab
        // Use provided style guide if available (fresh generation), otherwise read from disk
        String styleGuide = providedStyleGuide.orElseGet(() -> project.getStyleGuide());
        styleGuideArea.setText(styleGuide);
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

        loadCodeIntelligenceSettings();
        loadCiExclusionsFromBuildDetails();

        // Build Tab - delegate to buildPanelInstance
        buildPanelInstance.loadBuildPanelSettings();
    }

    private void loadCodeIntelligenceSettings() {
        var project = chrome.getProject();
        currentAnalyzerLanguagesForDialog.clear();
        currentAnalyzerLanguagesForDialog.addAll(project.getAnalyzerLanguages());
        if (languagesTableModel != null) {
            languagesTableModel.fireTableDataChanged();
        }
    }

    private void loadCiExclusionsFromBuildDetails() {
        var project = chrome.getProject();
        try {
            BuildDetails details = project.loadBuildDetails();
            updateExcludedDirectories(details.excludedDirectories());
        } catch (Exception ex) {
            logger.warn("Failed to load BuildDetails for CI exclusions: {}", ex.getMessage(), ex);
            updateExcludedDirectories(List.of());
        }
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
        IssueProviderType selectedType = (IssueProviderType) issueProviderTypeComboBox.getSelectedItem();
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

        // Persist CI exclusions from Code Intelligence panel into BuildDetails BEFORE build panel applies its settings
        saveCiExclusions();

        // Delegate build-related persistence to extracted build panel
        try {
            buildPanelInstance.applySettings();
        } catch (Exception e) {
            logger.error("Error applying build settings", e);
        }

        // Data Retention Tab and Analyzer-specific settings are handled elsewhere (if present)
        setAnalyzerLanguages();

        for (AnalyzerSettingsPanel panel : analyzerSettingsCache.values()) {
            panel.saveSettings();
        }

        return true;
    }

    private void saveCiExclusions() {
        var project = chrome.getProject();
        try {
            var currentDetails = project.loadBuildDetails();

            Set<String> excludesSet = Collections.list(excludedDirectoriesListModel.elements()).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> {
                        try {
                            return Path.of(s).normalize().toString();
                        } catch (InvalidPathException ex) {
                            return s;
                        }
                    })
                    .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));

            var newDetails = new BuildDetails(
                    currentDetails.buildLintCommand(),
                    currentDetails.testAllCommand(),
                    currentDetails.testSomeCommand(),
                    excludesSet,
                    currentDetails.environmentVariables());

            if (!newDetails.equals(currentDetails)) {
                project.saveBuildDetails(newDetails);
                logger.debug("Saved CI exclusions from Code Intelligence panel into BuildDetails: {}", excludesSet);
            }

            // Refresh the UI to reflect canonicalized values
            updateExcludedDirectories(excludesSet);
        } catch (Exception e) {
            logger.warn("Failed to persist CI exclusions before applying build settings: {}", e.toString(), e);
        }
    }

    private void setAnalyzerLanguages() {
        var project = chrome.getProject();
        Set<Language> currentLangs = project.getAnalyzerLanguages();
        if (!currentLangs.equals(currentAnalyzerLanguagesForDialog)) {
            project.setAnalyzerLanguages(currentAnalyzerLanguagesForDialog);
        }
    }

    public void showBuildBanner() {
        buildPanelInstance.showBuildBanner();
    }

    public void refreshDataRetentionPanel() {
        // No-op here; DataRetentionPanel is managed when created
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

    private List<Language> findLanguagesInProject(IProject project) {
        Set<Language> langs = new HashSet<>();
        Set<ProjectFile> filesToScan = project.hasGit() ? project.getRepo().getTrackedFiles() : project.getAllFiles();
        for (var pf : filesToScan) {
            String extension = Files.getFileExtension(pf.absPath().toString());
            if (!extension.isEmpty()) {
                var lang = Languages.fromExtension(extension);
                if (lang != Languages.NONE) {
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
                                .reloadService();
                        // Also need to refresh model selection UI in SettingsGlobalPanel
                        parentProjectPanel.parentDialog.refreshGlobalModelsPanelPostPolicyChange();
                    }
                }
            } // else this is standalone data retention dialog
        }
    }
}
