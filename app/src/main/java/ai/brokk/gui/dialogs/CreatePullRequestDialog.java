package ai.brokk.gui.dialogs;

import ai.brokk.ContextManager;
import ai.brokk.GitHubAuth;
import ai.brokk.context.Context;
import ai.brokk.context.DiffService;
import ai.brokk.difftool.ui.BrokkDiffPanel;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.difftool.utils.ColorUtil;
import ai.brokk.git.CommitInfo;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.ExceptionAwareSwingWorker;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.GitHubAppInstallLabel;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.MaterialLoadingButton;
import ai.brokk.gui.git.GitCommitBrowserPanel;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.widgets.FileStatusTable;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.jetbrains.annotations.Nullable;

public class CreatePullRequestDialog extends BaseThemedDialog {
    private static final Logger logger = LogManager.getLogger(CreatePullRequestDialog.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitWorkflow workflowService;
    private JComboBox<String> sourceBranchComboBox;
    private JComboBox<String> targetBranchComboBox;
    private JTextField titleField;
    private JTextArea descriptionArea;
    private JLabel descriptionHintLabel; // Hint for description generation source
    private GitCommitBrowserPanel commitBrowserPanel;
    private FileStatusTable fileStatusTable;
    private JLabel branchFlowLabel;
    private GitHubAppInstallLabel gitHubRepoInstallWarningLabel;
    private MaterialLoadingButton createPrButton; // Field for the Create PR button
    private Runnable flowUpdater;
    private List<CommitInfo> currentCommits = Collections.emptyList();

    // Review tab components
    private JTabbedPane middleTabbedPane;
    private JPanel reviewTabPlaceholder;

    @Nullable
    private JComponent aggregatedChangesPanel;

    @Nullable
    private String mergeBaseCommit = null;

    private volatile boolean sourceBranchNeedsPush = false;
    private volatile int unpushedCommitCount = 0;

    /**
     * Optional branch name that should be pre-selected as the source branch when the dialog opens. May be {@code null}.
     */
    @Nullable
    private final String preselectedSourceBranch;

    @SuppressWarnings("NullAway.Init")
    public CreatePullRequestDialog(
            @Nullable Frame owner,
            Chrome chrome,
            ContextManager contextManager,
            @Nullable String preselectedSourceBranch) {
        super(owner, "Create a Pull Request", Dialog.ModalityType.MODELESS);
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.workflowService = new GitWorkflow(contextManager);
        this.preselectedSourceBranch = preselectedSourceBranch;

        initializeDialog();
        buildLayout();
        setLocationRelativeTo(chrome.getFrame()); // Center after layout is built
    }

    @Nullable
    private SuggestPrDetailsWorker currentSuggestPrDetailsWorker;

    public CreatePullRequestDialog(Frame owner, Chrome chrome, ContextManager contextManager) {
        this(owner, chrome, contextManager, null); // delegate
    }

    private void initializeDialog() {
        setSize(1000, 1000);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    }

    @Override
    public void dispose() {
        if (currentSuggestPrDetailsWorker != null) {
            currentSuggestPrDetailsWorker.cancel(true);
        }
        super.dispose();
    }

    private void buildLayout() {
        var root = getContentRoot();
        root.setLayout(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); // Add padding

        // --- top panel: branch selectors -------------------------------------------------------
        var topPanel = new JPanel(new BorderLayout());
        var branchSelectorPanel = createBranchSelectorPanel();
        topPanel.add(branchSelectorPanel, BorderLayout.NORTH);

        // --- title and description panel ----------------------------------------------------
        var prInfoPanel = createPrInfoPanel();
        topPanel.add(prInfoPanel, BorderLayout.CENTER);

        // --- middle: commit browser and file list ---------------------------------------------
        commitBrowserPanel = new GitCommitBrowserPanel(
                chrome,
                contextManager,
                () -> {
                    /* no-op */
                },
                GitCommitBrowserPanel.Options.FOR_PULL_REQUEST);
        // The duplicate initializations that were here have been removed.
        // commitBrowserPanel and fileStatusTable are now initialized once above.
        fileStatusTable = new FileStatusTable();

        // Review tab placeholder
        reviewTabPlaceholder = new JPanel(new BorderLayout());
        var reviewPlaceholderLabel = new JLabel("Select branches to see diff summary", SwingConstants.CENTER);
        reviewPlaceholderLabel.setBorder(new EmptyBorder(20, 0, 20, 0));
        reviewTabPlaceholder.add(reviewPlaceholderLabel, BorderLayout.CENTER);

        middleTabbedPane = new JTabbedPane();
        middleTabbedPane.addTab("Commits", null, commitBrowserPanel, "Commits included in this pull request");
        middleTabbedPane.addTab("Review", null, reviewTabPlaceholder, "Aggregated diff summary");

        // --- bottom: buttons ------------------------------------------------------------------
        var buttonPanel = createButtonPanel();

        // --- add all content to the root panel managed by BaseThemedDialog ---
        root.add(topPanel, BorderLayout.NORTH);
        root.add(middleTabbedPane, BorderLayout.CENTER);
        root.add(buttonPanel, BorderLayout.SOUTH);

        // flow-label updater
        this.flowUpdater = createFlowUpdater();

        // initial data load
        loadBranches();
        setupInputListeners(); // Setup listeners for title and description
        updateCreatePrButtonState(); // Initial state for PR button based on (empty) title/desc
        // Non-blocking preflight to warn if the app is not installed for this repo
        scheduleRepoInstallPrecheck();
    }

    private JPanel createPrInfoPanel() {
        var panel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 10, 5, 10);
        gbc.anchor = GridBagConstraints.WEST;

        // Title
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Title:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        titleField = new JTextField();
        panel.add(titleField, gbc);

        // Description
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST; // Align label to top
        panel.add(new JLabel("Description:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0; // Allow description to take vertical space
        gbc.fill = GridBagConstraints.BOTH;
        descriptionArea = new JTextArea(10, 20); // Initial rows and columns
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        var scrollPane = new JScrollPane(descriptionArea);
        panel.add(scrollPane, gbc);

        // Hint label for description generation source
        descriptionHintLabel =
                new JLabel("<html>Description generated from commit messages as the diff was too large.</html>");
        descriptionHintLabel.setFont(descriptionHintLabel
                .getFont()
                .deriveFont(Font.ITALIC, descriptionHintLabel.getFont().getSize() * 0.9f));
        descriptionHintLabel.setVisible(false); // Initially hidden
        gbc.gridx = 1;
        gbc.gridy = 2; // Position below the description area
        gbc.weighty = 0; // Don't take extra vertical space
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST; // Align to top-left of its cell
        panel.add(descriptionHintLabel, gbc);

        return panel;
    }

    private JPanel createBranchSelectorPanel() {
        var branchPanel = new JPanel(new GridBagLayout());
        var row = 0;

        // Create combo boxes first
        targetBranchComboBox = new JComboBox<>();
        sourceBranchComboBox = new JComboBox<>();

        // Then add them to the panel
        row = addBranchSelectorToPanel(branchPanel, "Target branch:", targetBranchComboBox, row);
        row = addBranchSelectorToPanel(branchPanel, "Source branch:", sourceBranchComboBox, row);

        this.branchFlowLabel = createBranchFlowIndicator(branchPanel, row); // Assign to field
        row++;

        // Repo-install preflight warning (hidden by default). Click opens app installation page.
        gitHubRepoInstallWarningLabel = new GitHubAppInstallLabel(
                this,
                "<html><b>Warning:</b> Brokk GitHub App is not installed for this repository. "
                        + "<a href=\"\">Install the app</a>.</html>",
                new Color(184, 134, 11));
        gitHubRepoInstallWarningLabel.setVisible(false);
        var warnGbc = createGbc(0, row);
        warnGbc.gridwidth = 2;
        warnGbc.fill = GridBagConstraints.HORIZONTAL;
        branchPanel.add(gitHubRepoInstallWarningLabel, warnGbc);

        // setupBranchListeners is now called in loadBranches after defaults are set
        // loadBranches is called after the main layout is built

        return branchPanel;
    }

    private int addBranchSelectorToPanel(JPanel parent, String labelText, JComboBox<String> comboBox, int row) {
        var gbc = createGbc(0, row);
        parent.add(new JLabel(labelText), gbc);

        gbc = createGbc(1, row);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        parent.add(comboBox, gbc);

        return row + 1;
    }

    private JLabel createBranchFlowIndicator(JPanel parent, int row) {
        var branchFlowLabel = new JLabel("", SwingConstants.CENTER);
        branchFlowLabel.setFont(branchFlowLabel.getFont().deriveFont(Font.BOLD));

        var gbc = createGbc(0, row);
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        parent.add(branchFlowLabel, gbc);

        return branchFlowLabel;
    }

    private void scheduleRepoInstallPrecheck() {
        if (!GitHubAuth.tokenPresent()) {
            SwingUtil.runOnEdt(() -> gitHubRepoInstallWarningLabel.setVisible(false));
            return;
        }

        CompletableFuture.runAsync(() -> {
            boolean needsInstall = false;
            try {
                var auth = GitHubAuth.getOrCreateInstance(contextManager.getProject());
                needsInstall = !GitHubAuth.isBrokkAppInstalledForRepo(auth.getOwner(), auth.getRepoName());
            } catch (Exception e) {
                logger.debug("Could not preflight-check app installation for repo", e);
                needsInstall = false; // don't show warning on unknown
            }
            final boolean show = needsInstall;
            SwingUtil.runOnEdt(() -> {
                if (gitHubRepoInstallWarningLabel.isDisplayable()) {
                    gitHubRepoInstallWarningLabel.setVisible(show);
                }
            });
        });
    }

    /** Simple immutable holder for commits and changed files between two branches. */
    // Fix the UnnecessaryLambda warning by implementing updateBranchFlow as a method
    private void updateBranchFlow() {
        var target = (String) targetBranchComboBox.getSelectedItem();
        var source = (String) sourceBranchComboBox.getSelectedItem();
        if (target != null && source != null) {
            String text = target + " ← " + source + " (" + currentCommits.size() + " commits)";
            if (sourceBranchNeedsPush && unpushedCommitCount > 0) {
                text += " • " + unpushedCommitCount + " unpushed";
            }
            this.branchFlowLabel.setText(text);
            this.branchFlowLabel.setForeground(UIManager.getColor("Label.foreground"));
            this.branchFlowLabel.setToolTipText(null);
        } else {
            this.branchFlowLabel.setText("");
        }
        updateCreatePrButtonText();
    }

    private Runnable createFlowUpdater() {
        return this::updateBranchFlow;
    }

    private void setupBranchListeners() {
        ActionListener branchChangedListener = e -> {
            // Immediately update flow label for responsiveness before async refresh.
            this.currentCommits = Collections.emptyList();
            this.sourceBranchNeedsPush = false;
            this.unpushedCommitCount = 0;
            this.flowUpdater.run();
            // Full UI update, including button state, will be handled by refreshCommitList.
            refreshCommitList();
        };
        targetBranchComboBox.addActionListener(branchChangedListener);
        sourceBranchComboBox.addActionListener(branchChangedListener);
    }

    private void updateCommitRelatedUI(
            List<CommitInfo> newCommits, List<GitRepo.ModifiedFile> newFiles, String commitPanelMessage) {
        Collections.reverse(newCommits);
        this.currentCommits = newCommits;
        commitBrowserPanel.setCommits(newCommits, Set.of(), false, false, commitPanelMessage);
        fileStatusTable.setFiles(newFiles);
        this.flowUpdater.run();
        updateCreatePrButtonState();
        updateReviewTab(newFiles);
    }

    private void refreshCommitList() {
        this.mergeBaseCommit = null; // Ensure merge base is reset for each refresh

        var sourceBranch = (String) sourceBranchComboBox.getSelectedItem();
        var targetBranch = (String) targetBranchComboBox.getSelectedItem();

        if (sourceBranch == null || targetBranch == null) {
            updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), "Select branches");
            return;
        }

        var contextName = targetBranch + " ← " + sourceBranch;

        if (sourceBranch.equals(targetBranch)) {
            updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), contextName);
            return;
        }

        contextManager.submitBackgroundTask("Fetching commits for " + contextName, () -> {
            try {
                var repo = contextManager.getProject().getRepo();
                if (!(repo instanceof GitRepo gitRepo)) {
                    String nonGitMessage = "Project is not a Git repository.";
                    SwingUtilities.invokeLater(() -> {
                        this.sourceBranchNeedsPush = false;
                        this.unpushedCommitCount = 0;
                        updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), nonGitMessage);
                    });
                    return Collections.emptyList();
                }

                // Use GitWorkflowService to get branch diff information
                var branchDiff = workflowService.diffBetweenBranches(sourceBranch, targetBranch);
                this.mergeBaseCommit = branchDiff.mergeBase(); // Store merge base from service
                logger.debug(
                        "Calculated merge base between {} and {}: {}",
                        sourceBranch,
                        targetBranch,
                        this.mergeBaseCommit);

                // Check if source branch needs push (for UI indicator)
                boolean needsPush = gitRepo.remote().branchNeedsPush(sourceBranch);
                final int unpushedCount;
                if (needsPush) {
                    unpushedCount =
                            gitRepo.remote().getUnpushedCommitIds(sourceBranch).size();
                } else {
                    unpushedCount = 0;
                }

                if (branchDiff.commits().isEmpty()) {
                    cancelGenerationWorkersAndClearFields();
                } else {
                    spawnSuggestPrDetailsWorker(sourceBranch, targetBranch);
                }

                SwingUtilities.invokeLater(() -> {
                    this.sourceBranchNeedsPush = needsPush;
                    this.unpushedCommitCount = unpushedCount;
                    updateCommitRelatedUI(branchDiff.commits(), branchDiff.files(), contextName);
                });
                return branchDiff.commits();
            } catch (Exception e) {
                logger.error("Error fetching branch diff or suggesting PR details for " + contextName, e);
                SwingUtilities.invokeLater(() -> {
                    this.sourceBranchNeedsPush = false; // Reset on error
                    this.unpushedCommitCount = 0;
                    updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), contextName + " (error)");
                    cancelGenerationWorkersAndClearFields(); // Also clear fields on error
                    descriptionArea.setText("(Could not generate PR details due to error)");
                    titleField.setText("");
                });
                throw e;
            }
        });
    }

    private void updateCreatePrButtonState() {
        List<String> blockers = getCreatePrBlockers();
        createPrButton.setEnabled(blockers.isEmpty());
        createPrButton.setToolTipText(blockers.isEmpty() ? null : formatBlockersTooltip(blockers));
    }

    private void updateCreatePrButtonText() {
        String buttonText = sourceBranchNeedsPush ? "Push and Create PR" : "Create PR";
        String tooltip = sourceBranchNeedsPush
                ? "This will push your local branch to origin and then create a pull request"
                : null;
        createPrButton.setText(buttonText);
        if (!createPrButton.isEnabled()) {
            // Don't override the blockers tooltip when button is disabled
            return;
        }
        createPrButton.setToolTipText(tooltip);
    }

    private List<String> getCreatePrBlockers() {
        var blockers = new ArrayList<String>();
        var sourceBranch = (String) sourceBranchComboBox.getSelectedItem();
        var targetBranch = (String) targetBranchComboBox.getSelectedItem();

        if (currentCommits.isEmpty()) {
            blockers.add("No commits to include in the pull request.");
        }
        if (sourceBranch == null) {
            blockers.add("Source branch not selected.");
        }
        if (targetBranch == null) {
            blockers.add("Target branch not selected.");
        }
        // This check should only be added if both branches are selected, otherwise it's redundant.
        if (sourceBranch != null && sourceBranch.equals(targetBranch)) {
            blockers.add("Source and target branches cannot be the same.");
        }
        if (titleField.getText() == null || titleField.getText().trim().isEmpty()) {
            blockers.add("Title cannot be empty.");
        }
        if (descriptionArea.getText() == null
                || descriptionArea.getText().trim().isEmpty()) {
            blockers.add("Description cannot be empty.");
        }
        return List.copyOf(blockers);
    }

    private @Nullable String formatBlockersTooltip(List<String> blockers) {
        if (blockers.isEmpty()) {
            return null;
        }
        var sb = new StringBuilder("<html>");
        for (String blocker : blockers) {
            sb.append("• ").append(blocker).append("<br>");
        }
        sb.append("</html>");
        return sb.toString();
    }

    /** Checks whether the dialog has sufficient information to enable PR creation. */
    private boolean isCreatePrReady() {
        return getCreatePrBlockers().isEmpty();
    }

    private void setupInputListeners() {
        DocumentListener inputChangedListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateCreatePrButtonState();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateCreatePrButtonState();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateCreatePrButtonState();
            }
        };
        titleField.getDocument().addDocumentListener(inputChangedListener);
        descriptionArea.getDocument().addDocumentListener(inputChangedListener);
    }

    private JPanel createButtonPanel() {
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        // Initialize with longer text to calculate preferred size, then lock it
        this.createPrButton = new MaterialLoadingButton("Push and Create PR", null, chrome, null);
        this.createPrButton.addActionListener(e -> createPullRequest());

        // Style Create PR button as primary action (bright blue with white text)
        SwingUtil.applyPrimaryButtonStyle(this.createPrButton);

        // Lock preferred size to accommodate the longer text variant
        this.createPrButton.setPreferredSize(this.createPrButton.getPreferredSize());
        buttonPanel.add(this.createPrButton);

        var cancelButton = new MaterialButton("Cancel");
        cancelButton.addActionListener(e -> dispose());
        buttonPanel.add(cancelButton);

        return buttonPanel;
    }

    private GridBagConstraints createGbc(int x, int y) {
        var gbc = new GridBagConstraints();
        gbc.gridx = x;
        gbc.gridy = y;
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.anchor = GridBagConstraints.WEST;
        return gbc;
    }

    private void loadBranches() {
        var repo = contextManager.getProject().getRepo();
        if (!(repo instanceof GitRepo gitRepo)) {
            logger.warn("PR dialog opened for non-Git repository");
            return;
        }

        try {
            var localBranches = gitRepo.listLocalBranches();
            var remoteBranches = gitRepo.listRemoteBranches();

            var targetBranches = getTargetBranches(remoteBranches);
            var sourceBranches = getSourceBranches(localBranches, remoteBranches);

            populateBranchDropdowns(targetBranches, sourceBranches);
            setDefaultBranchSelections(gitRepo, targetBranches, sourceBranches, localBranches);

            // If caller asked for a specific source branch, honour it *after*
            // defaults have been applied (so this wins).
            if (preselectedSourceBranch != null && sourceBranches.contains(preselectedSourceBranch)) {
                sourceBranchComboBox.setSelectedItem(preselectedSourceBranch);
            }

            // Listeners must be set up AFTER default items are selected to avoid premature firing.
            setupBranchListeners();

            this.flowUpdater.run(); // Update label based on defaults
            refreshCommitList(); // Load commits based on defaults, which will also call flowUpdater and update button
            // state
            // updateCreatePrButtonState(); // No longer needed here, refreshCommitList handles it
        } catch (GitAPIException e) {
            logger.error("Error loading branches for PR dialog", e);
            // Ensure UI is updated consistently on error, using the new helper method
            SwingUtilities.invokeLater(() ->
                    updateCommitRelatedUI(Collections.emptyList(), Collections.emptyList(), "Error loading branches"));
        }
    }

    private List<String> getTargetBranches(List<String> remoteBranches) {
        return remoteBranches.stream()
                .filter(branch -> branch.startsWith("origin/"))
                .filter(branch -> !branch.equals("origin/HEAD"))
                .toList();
    }

    private List<String> getSourceBranches(List<String> localBranches, List<String> remoteBranches) {
        return Stream.concat(localBranches.stream(), remoteBranches.stream())
                .distinct()
                .sorted()
                .toList();
    }

    private void populateBranchDropdowns(List<String> targetBranches, List<String> sourceBranches) {
        targetBranchComboBox.setModel(new DefaultComboBoxModel<>(targetBranches.toArray(new String[0])));
        sourceBranchComboBox.setModel(new DefaultComboBoxModel<>(sourceBranches.toArray(new String[0])));
    }

    private void setDefaultBranchSelections(
            GitRepo gitRepo, List<String> targetBranches, List<String> sourceBranches, List<String> localBranches)
            throws GitAPIException {
        var defaultTarget = findDefaultTargetBranch(targetBranches);
        if (defaultTarget != null) {
            targetBranchComboBox.setSelectedItem(defaultTarget);
        }

        selectDefaultSourceBranch(gitRepo, sourceBranches, localBranches);
    }

    @Nullable
    private String findDefaultTargetBranch(List<String> targetBranches) {
        if (targetBranches.contains("origin/main")) {
            return "origin/main";
        }
        if (targetBranches.contains("origin/master")) {
            return "origin/master";
        }
        return targetBranches.isEmpty() ? null : targetBranches.getFirst();
    }

    private void selectDefaultSourceBranch(GitRepo gitRepo, List<String> sourceBranches, List<String> localBranches)
            throws GitAPIException {
        var currentBranch = gitRepo.getCurrentBranch();
        if (sourceBranches.contains(currentBranch)) {
            sourceBranchComboBox.setSelectedItem(currentBranch);
        } else if (!localBranches.isEmpty()) {
            sourceBranchComboBox.setSelectedItem(localBranches.getFirst());
        } else if (!sourceBranches.isEmpty()) {
            sourceBranchComboBox.setSelectedItem(sourceBranches.getFirst());
        }
    }

    public static void show(Frame owner, Chrome chrome, ContextManager contextManager) {
        CreatePullRequestDialog dialog = new CreatePullRequestDialog(owner, chrome, contextManager);
        dialog.setVisible(true);
    }

    /** Convenience helper to open the dialog with a pre-selected source branch. */
    public static void show(
            @Nullable Frame owner, Chrome chrome, ContextManager contextManager, @Nullable String sourceBranch) {
        CreatePullRequestDialog dialog = new CreatePullRequestDialog(owner, chrome, contextManager, sourceBranch);
        dialog.setVisible(true);
    }

    /** SwingWorker to suggest PR title and description using GitWorkflowService with streaming. */
    private class SuggestPrDetailsWorker extends ExceptionAwareSwingWorker<GitWorkflow.PrSuggestion, Void> {
        private final String sourceBranch;
        private final String targetBranch;
        private final PrDetailsConsoleIO streamingIO;

        SuggestPrDetailsWorker(String sourceBranch, String targetBranch) {
            super(chrome);
            this.sourceBranch = sourceBranch;
            this.targetBranch = targetBranch;
            this.streamingIO = new PrDetailsConsoleIO(titleField, descriptionArea, chrome);
        }

        @Override
        protected GitWorkflow.PrSuggestion doInBackground() throws GitAPIException, InterruptedException {
            return workflowService.suggestPullRequestDetails(sourceBranch, targetBranch, streamingIO);
        }

        @Override
        protected void done() {
            // First invoke centralized exception handling (logs, uploads, and notifies user)
            super.done();

            // If successful, update UI
            GitWorkflow.PrSuggestion suggestion;
            try {
                suggestion = get();
            } catch (InterruptedException | ExecutionException ignored) {
                // Already handled by ExceptionAwareSwingWorker.done()
                return;
            }
            SwingUtilities.invokeLater(() -> {
                streamingIO.onComplete();
                titleField.setText(suggestion.title());
                descriptionArea.setText(suggestion.description());
                titleField.setCaretPosition(0);
                descriptionArea.setCaretPosition(0);
                showDescriptionHint(suggestion.usedCommitMessages());
            });
        }
    }

    private void createPullRequest() {
        if (!isCreatePrReady()) {
            // This should ideally not happen if button state is managed correctly,
            // but as a safeguard:
            chrome.toolError(
                    "Cannot create Pull Request. Please check details and ensure branch is pushed.",
                    "PR Creation Error");
            return;
        }

        // Pre-flight: ensure GitHub account is connected
        if (!GitHubAuth.tokenPresent()) {
            int choice = chrome.showConfirmDialog(
                    """
                    You are not connected to GitHub.

                    To create a Pull Request, connect your GitHub account.

                    Would you like to open Settings now?
                    """,
                    "Connect GitHub Account",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                SettingsDialog.showSettingsDialog(chrome, SettingsDialog.GITHUB_SETTINGS_TAB_NAME);
            }
            return;
        }

        createPrButton.setLoading(true, "Creating PR…");

        contextManager.submitExclusiveAction(() -> {
            try {
                // Gather details on the EDT before going to background if they come from Swing components
                final String title = titleField.getText().trim();
                final String body = descriptionArea.getText().trim();
                // Removed duplicate declarations of title and body
                final String sourceBranch = (String) sourceBranchComboBox.getSelectedItem();
                final String targetBranch = (String) targetBranchComboBox.getSelectedItem();

                // Ensure selectedItem calls are safe
                if (sourceBranch == null || targetBranch == null) {
                    throw new IllegalStateException("Source or target branch not selected.");
                }

                var prUrl = workflowService.createPullRequest(sourceBranch, targetBranch, title, body);

                // Success: Open in browser and dispose dialog
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(prUrl);
                } else {
                    logger.warn("Desktop browse action not supported, cannot open PR URL automatically: {}", prUrl);
                    SwingUtilities.invokeLater(() -> chrome.systemNotify(
                            "PR created: " + prUrl, "Pull Request Created", JOptionPane.INFORMATION_MESSAGE));
                }
                SwingUtilities.invokeLater(this::dispose);

            } catch (TransportException ex) {
                logger.error("Pull Request creation failed due to transport/push error", ex);
                SwingUtilities.invokeLater(() -> {
                    String errorMessage;
                    if (GitRepo.isGitHubPermissionDenied(ex)) {
                        errorMessage =
                                """
                                Push to repository was denied. This usually means:

                                1. Missing or invalid GitHub token
                                   → Go to Settings → Global → GitHub and verify your token

                                2. You don't have write access to this repository
                                   → Verify you own or are a collaborator on this repository

                                3. Brokk GitHub App is not installed for this repository
                                   → Go to Settings → Global → GitHub to install the app
                                """;
                    } else {
                        errorMessage = "Push failed: " + ex.getMessage();
                    }

                    // Show error message
                    chrome.toolError(errorMessage, "Push Permission Denied");
                    if (isDisplayable()) {
                        createPrButton.setLoading(false, null);
                    }
                });
            } catch (Exception ex) {
                logger.error("Pull Request creation failed", ex);
                SwingUtilities.invokeLater(() -> {
                    chrome.toolError("Unable to create Pull Request:\n" + ex.getMessage(), "PR Creation Error");
                    if (isDisplayable()) {
                        createPrButton.setLoading(false, null);
                    }
                });
            }
            return null; // Void task
        });
    }

    private void cancelGenerationWorkersAndClearFields() {
        if (currentSuggestPrDetailsWorker != null) {
            currentSuggestPrDetailsWorker.cancel(true);
            currentSuggestPrDetailsWorker = null;
        }
        SwingUtilities.invokeLater(() -> {
            titleField.setText("");
            descriptionArea.setText("");
            showDescriptionHint(false);
            updateCreatePrButtonState();
        });
    }

    private void showDescriptionHint(boolean show) {
        SwingUtilities.invokeLater(() -> {
            descriptionHintLabel.setVisible(show);
        });
    }

    private void spawnSuggestPrDetailsWorker(String sourceBranch, String targetBranch) {
        SwingUtilities.invokeLater(() -> showDescriptionHint(false));
        // Cancel previous background LLM calls
        if (currentSuggestPrDetailsWorker != null) {
            currentSuggestPrDetailsWorker.cancel(true);
        }

        currentSuggestPrDetailsWorker = new SuggestPrDetailsWorker(sourceBranch, targetBranch);
        currentSuggestPrDetailsWorker.execute();
    }

    // --- Review Tab Methods ---

    private void updateReviewTab(List<GitRepo.ModifiedFile> files) {
        // Set loading state first
        SwingUtilities.invokeLater(() -> {
            reviewTabPlaceholder.removeAll();
            var loadingLabel = new JLabel("Computing diff summary...", SwingConstants.CENTER);
            loadingLabel.setBorder(new EmptyBorder(20, 0, 20, 0));
            reviewTabPlaceholder.add(loadingLabel, BorderLayout.CENTER);
            reviewTabPlaceholder.revalidate();
            reviewTabPlaceholder.repaint();
        });

        // Compute cumulative changes in background
        contextManager.submitBackgroundTask("Computing review diff", () -> {
            var changes = computeCumulativeChanges(files);
            var prepared = DiffService.preparePerFileSummaries(changes);
            SwingUtilities.invokeLater(() -> updateReviewTabContent(changes, prepared));
            return changes;
        });
    }

    private DiffService.CumulativeChanges computeCumulativeChanges(List<GitRepo.ModifiedFile> files) {
        if (mergeBaseCommit == null || files.isEmpty()) {
            return new DiffService.CumulativeChanges(0, 0, 0, List.of());
        }

        var repo = contextManager.getProject().getRepo();
        if (!(repo instanceof GitRepo)) {
            return new DiffService.CumulativeChanges(0, 0, 0, List.of());
        }

        // Get the source branch for right-side content (committed content, not working tree)
        var sourceBranch = (String) sourceBranchComboBox.getSelectedItem();
        if (sourceBranch == null) {
            return new DiffService.CumulativeChanges(0, 0, 0, List.of());
        }

        // Convert List<GitRepo.ModifiedFile> to Set for DiffService.summarizeDiff
        Set<ai.brokk.git.IGitRepo.ModifiedFile> fileSet = new HashSet<>(files);

        // Use DiffService to summarize changes between merge base and source branch
        return DiffService.summarizeDiff(repo, mergeBaseCommit, sourceBranch, fileSet);
    }

    private void updateReviewTabContent(
            DiffService.CumulativeChanges res, List<Map.Entry<String, Context.DiffEntry>> prepared) {
        assert SwingUtilities.isEventDispatchThread() : "updateReviewTabContent must run on EDT";

        // Dispose any previous diff panel
        if (aggregatedChangesPanel instanceof BrokkDiffPanel diffPanel) {
            try {
                diffPanel.dispose();
            } catch (Throwable t) {
                logger.debug("Ignoring error disposing previous BrokkDiffPanel", t);
            }
        }
        aggregatedChangesPanel = null;

        reviewTabPlaceholder.removeAll();

        // Update tab title with stats
        updateReviewTabTitle(res);

        if (res.filesChanged() == 0) {
            var none = new JLabel("No changes to review.", SwingConstants.CENTER);
            none.setBorder(new EmptyBorder(20, 0, 20, 0));
            reviewTabPlaceholder.add(none, BorderLayout.CENTER);
            reviewTabPlaceholder.revalidate();
            reviewTabPlaceholder.repaint();
            return;
        }

        try {
            var aggregatedPanel = buildAggregatedChangesPanel(prepared);
            reviewTabPlaceholder.add(aggregatedPanel, BorderLayout.CENTER);
        } catch (Throwable t) {
            logger.warn("Failed to build aggregated Changes panel", t);
            var err = new JLabel("Unable to display aggregated changes.", SwingConstants.CENTER);
            err.setBorder(new EmptyBorder(20, 0, 20, 0));
            reviewTabPlaceholder.add(err, BorderLayout.CENTER);
            aggregatedChangesPanel = null;
        }
        reviewTabPlaceholder.revalidate();
        reviewTabPlaceholder.repaint();
    }

    private void updateReviewTabTitle(DiffService.CumulativeChanges res) {
        int idx = middleTabbedPane.indexOfComponent(reviewTabPlaceholder);
        if (idx < 0) return;

        if (res.filesChanged() == 0) {
            middleTabbedPane.setTitleAt(idx, "Review (0)");
            middleTabbedPane.setToolTipTextAt(idx, "No changes to review");
        } else {
            boolean isDark = chrome.getTheme().isDarkTheme();
            Color plusColor = ThemeColors.getColor(isDark, "diff_added_fg");
            Color minusColor = ThemeColors.getColor(isDark, "diff_deleted_fg");
            String htmlTitle = String.format(
                    "<html>Review (%d, <span style='color:%s'>+%d</span>/<span style='color:%s'>-%d</span>)</html>",
                    res.filesChanged(),
                    ColorUtil.toHex(plusColor),
                    res.totalAdded(),
                    ColorUtil.toHex(minusColor),
                    res.totalDeleted());
            middleTabbedPane.setTitleAt(idx, htmlTitle);
            middleTabbedPane.setToolTipTextAt(
                    idx,
                    String.format(
                            "Cumulative changes: %d files, +%d/-%d",
                            res.filesChanged(), res.totalAdded(), res.totalDeleted()));
        }
    }

    private JPanel buildAggregatedChangesPanel(List<Map.Entry<String, Context.DiffEntry>> prepared) {
        var wrapper = new JPanel(new BorderLayout());

        // Build header
        var headerPanel = new JPanel(new BorderLayout(8, 0));
        headerPanel.setOpaque(false);
        headerPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        String targetBranch = (String) targetBranchComboBox.getSelectedItem();
        String baselineLabelText = targetBranch != null ? "Comparing vs " + targetBranch : "Branch-based changes";
        var baselineLabel = new JLabel(baselineLabelText);
        baselineLabel.setFont(baselineLabel.getFont().deriveFont(Font.BOLD));
        headerPanel.add(baselineLabel, BorderLayout.WEST);

        wrapper.add(headerPanel, BorderLayout.NORTH);

        // Build diff panel
        var builder = new BrokkDiffPanel.Builder(chrome.getTheme(), contextManager)
                .setMultipleCommitsContext(false)
                .setRootTitle("PR Changes");

        // Use precomputed list in stable order; do not call Context.DiffEntry::title here
        for (var entry : prepared) {
            String title = entry.getKey();
            Context.DiffEntry de = entry.getValue();
            var left = new BufferSource.StringSource(de.oldContent(), title + " (base)");
            var right = new BufferSource.StringSource(de.newContent(), title);
            builder.leftSource(left).rightSource(right);
        }

        var diffPanel = builder.build();
        aggregatedChangesPanel = diffPanel;
        wrapper.add(diffPanel, BorderLayout.CENTER);

        return wrapper;
    }
}
