package ai.brokk.gui.dialogs;

import ai.brokk.ContextManager;
import ai.brokk.GitHubAuth;
import ai.brokk.SessionManager;
import ai.brokk.agents.ReviewScope;
import ai.brokk.git.CommitInfo;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.ExceptionAwareSwingWorker;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.FuzzyComboBox;
import ai.brokk.gui.components.GitHubAppInstallLabel;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.MaterialLoadingButton;
import ai.brokk.gui.git.GitHubErrorUtil;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.swing.*;
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

    private FuzzyComboBox<String> sourceBranchComboBox;
    private FuzzyComboBox<String> targetBranchComboBox;

    private JTextField titleField;
    private JTextArea descriptionArea;
    private JLabel descriptionHintLabel; // Hint for description generation source
    private JLabel branchFlowLabel;
    private GitHubAppInstallLabel gitHubRepoInstallWarningLabel;
    private MaterialLoadingButton createPrButton; // Field for the Create PR button
    private Runnable flowUpdater;
    private List<CommitInfo> currentCommits = Collections.emptyList();

    private JTabbedPane middleTabbedPane;
    private JPanel sessionsTabPanel;
    private JTable sessionsTable;
    private javax.swing.table.DefaultTableModel sessionsTableModel;
    private JCheckBox selectAllSessionsCheckbox;

    private final CompletableFuture<Void> sessionSyncFuture;

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
        this.sessionSyncFuture = this.contextManager.syncSessionsAsync();
        buildLayout();
        setLocationRelativeTo(chrome.getFrame()); // Center after layout is built
    }

    @Nullable
    private SuggestPrDetailsWorker currentSuggestPrDetailsWorker;

    public CreatePullRequestDialog(Frame owner, Chrome chrome, ContextManager contextManager) {
        this(owner, chrome, contextManager, null); // delegate
    }

    private void initializeDialog() {
        setSize(1000, 750);
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

        // --- top panel: branch selectors + PR info ---------------------------------------------
        var topPanel = new JPanel(new BorderLayout());
        topPanel.add(createBranchSelectorPanel(), BorderLayout.NORTH);
        topPanel.add(createPrInfoPanel(), BorderLayout.CENTER);

        // --- middle: tabbed content (Sessions only) --------------------------------------------
        middleTabbedPane = new JTabbedPane();
        sessionsTabPanel = createSessionsTabPanel();
        middleTabbedPane.addTab("Sessions", null, sessionsTabPanel, "Overlapping Brokk sessions");

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

        // Create combo boxes immediately with loading placeholder
        sourceBranchComboBox = FuzzyComboBox.forStrings(List.of("Loading..."));
        targetBranchComboBox = FuzzyComboBox.forStrings(List.of("Loading..."));
        sourceBranchComboBox.setEnabled(false);
        targetBranchComboBox.setEnabled(false);

        var row = 0;
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

    private int addBranchSelectorToPanel(JPanel parent, String labelText, FuzzyComboBox<String> comboBox, int row) {
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
        var target = targetBranchComboBox.getSelectedItem();
        var source = sourceBranchComboBox.getSelectedItem();
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
        Consumer<String> branchChangedListener = branch -> {
            // Immediately update flow label for responsiveness before async refresh.
            this.currentCommits = Collections.emptyList();
            this.sourceBranchNeedsPush = false;
            this.unpushedCommitCount = 0;
            this.flowUpdater.run();
            // Full UI update, including button state, will be handled by refreshCommitList.
            refreshCommitList();
        };

        targetBranchComboBox.setSelectionChangeListener(branchChangedListener);
        sourceBranchComboBox.setSelectionChangeListener(branchChangedListener);
    }

    private void updateCommitRelatedUI(List<CommitInfo> newCommits) {
        Collections.reverse(newCommits);
        this.currentCommits = newCommits;
        this.flowUpdater.run();
        updateCreatePrButtonState();
        updateSessionsTab(newCommits);
    }

    private void refreshCommitList() {
        var sourceBranch = sourceBranchComboBox.getSelectedItem();
        var targetBranch = targetBranchComboBox.getSelectedItem();

        if (sourceBranch == null || targetBranch == null) {
            updateCommitRelatedUI(Collections.emptyList());
            return;
        }

        var contextName = targetBranch + " ← " + sourceBranch;

        if (sourceBranch.equals(targetBranch)) {
            updateCommitRelatedUI(Collections.emptyList());
            return;
        }

        contextManager.submitBackgroundTask("Fetching commits for " + contextName, () -> {
            try {
                var repo = contextManager.getProject().getRepo();
                if (!(repo instanceof GitRepo gitRepo)) {
                    SwingUtilities.invokeLater(() -> {
                        this.sourceBranchNeedsPush = false;
                        this.unpushedCommitCount = 0;
                        updateCommitRelatedUI(Collections.emptyList());
                    });
                    return Collections.emptyList();
                }

                // Use GitWorkflowService to get branch diff information
                var branchDiff = workflowService.diffBetweenBranches(targetBranch, sourceBranch);

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
                    updateCommitRelatedUI(branchDiff.commits());
                });
                return branchDiff.commits();
            } catch (Exception e) {
                logger.error("Error fetching branch diff or suggesting PR details for " + contextName, e);
                SwingUtilities.invokeLater(() -> {
                    this.sourceBranchNeedsPush = false; // Reset on error
                    this.unpushedCommitCount = 0;
                    updateCommitRelatedUI(Collections.emptyList());
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
        var sourceBranch = sourceBranchComboBox.getSelectedItem();
        var targetBranch = targetBranchComboBox.getSelectedItem();

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
        return "<html>"
                + blockers.stream().map(b -> "• " + b).collect(java.util.stream.Collectors.joining("<br>"))
                + "</html>";
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

        CompletableFuture.runAsync(() -> {
            try {
                var localBranches = gitRepo.listLocalBranches();
                var remoteBranches = gitRepo.listRemoteBranches();

                var targetBranches = getTargetBranches(remoteBranches);
                var sourceBranches = getSourceBranches(localBranches, remoteBranches);

                SwingUtil.runOnEdt(() -> {
                    populateBranchDropdowns(targetBranches, sourceBranches);
                    try {
                        setDefaultBranchSelections(gitRepo, targetBranches, sourceBranches, localBranches);

                        // If caller asked for a specific source branch, honour it *after*
                        // defaults have been applied (so this wins).
                        if (preselectedSourceBranch != null && sourceBranches.contains(preselectedSourceBranch)) {
                            sourceBranchComboBox.setSelectedItem(preselectedSourceBranch);
                        }

                        // Set up listeners AFTER default items are selected to avoid premature firing during setItems()
                        setupBranchListeners();

                        this.flowUpdater.run(); // Update label based on defaults
                        refreshCommitList(); // Load commits based on defaults, which will also call flowUpdater and
                        // update button state
                    } catch (GitAPIException e) {
                        logger.error("Error setting default branch selections", e);
                        updateCommitRelatedUI(Collections.emptyList());
                    }
                });
            } catch (GitAPIException e) {
                logger.error("Error loading branches for PR dialog", e);
                SwingUtil.runOnEdt(() -> {
                    targetBranchComboBox.setItems(List.of("(Error loading branches)"));
                    sourceBranchComboBox.setItems(List.of("(Error loading branches)"));
                    targetBranchComboBox.setEnabled(false);
                    sourceBranchComboBox.setEnabled(false);
                    updateCommitRelatedUI(Collections.emptyList());
                });
            }
        });
    }

    private List<String> getTargetBranches(List<String> remoteBranches) {
        return remoteBranches.stream()
                .filter(branch -> !branch.endsWith("/HEAD"))
                .sorted()
                .toList();
    }

    private List<String> getSourceBranches(List<String> localBranches, List<String> remoteBranches) {
        // Show local branches first, then remote branches (instead of mixing them alphabetically)
        var sortedLocal = localBranches.stream().sorted().toList();
        var sortedRemote = remoteBranches.stream().sorted().toList();
        return Stream.concat(sortedLocal.stream(), sortedRemote.stream())
                .distinct()
                .toList();
    }

    private void populateBranchDropdowns(List<String> targetBranches, List<String> sourceBranches) {
        assert SwingUtilities.isEventDispatchThread() : "populateBranchDropdowns must run on EDT";

        // Update items using new setItems() method
        targetBranchComboBox.setItems(targetBranches);
        sourceBranchComboBox.setItems(sourceBranches);

        // Re-enable combo boxes after loading
        targetBranchComboBox.setEnabled(true);
        sourceBranchComboBox.setEnabled(true);
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
        if (targetBranches.isEmpty()) {
            return null;
        }

        var repo = contextManager.getProject().getRepo();
        if (!(repo instanceof GitRepo gitRepo)) {
            return targetBranches.getFirst();
        }

        // Get preferred remote name (origin or fallback)
        var preferredRemote = gitRepo.remote().getOriginRemoteNameWithFallback();
        if (preferredRemote != null) {
            // Try main/master on preferred remote first
            var preferredMain = preferredRemote + "/main";
            if (targetBranches.contains(preferredMain)) {
                return preferredMain;
            }

            var preferredMaster = preferredRemote + "/master";
            if (targetBranches.contains(preferredMaster)) {
                return preferredMaster;
            }
        }

        // Fall back to any remote's main/master
        var anyMain = targetBranches.stream().filter(b -> b.endsWith("/main")).findFirst();
        if (anyMain.isPresent()) {
            return anyMain.get();
        }

        var anyMaster =
                targetBranches.stream().filter(b -> b.endsWith("/master")).findFirst();
        if (anyMaster.isPresent()) {
            return anyMaster.get();
        }

        // Last resort: first branch alphabetically
        return targetBranches.getFirst();
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
        private final List<UUID> sessionIds;
        private final TextAreaConsoleIO streamingIO;

        SuggestPrDetailsWorker(String sourceBranch, String targetBranch, List<UUID> sessionIds) {
            super(chrome);
            this.sourceBranch = sourceBranch;
            this.targetBranch = targetBranch;
            this.sessionIds = List.copyOf(sessionIds);

            // Dialog takes responsibility for the title field UI while generation runs.
            SwingUtilities.invokeLater(() -> {
                titleField.setEnabled(false);
                titleField.setText("Generating title");
                titleField.setCaretPosition(0);
            });

            // Create a TextAreaConsoleIO for streaming description tokens. Use a descriptive initial message.
            this.streamingIO = new TextAreaConsoleIO(descriptionArea, chrome, "Generating description...\nThinking");
        }

        @Override
        protected GitWorkflow.PrSuggestion doInBackground() throws GitAPIException, InterruptedException {
            return workflowService.suggestPullRequestDetails(sourceBranch, targetBranch, streamingIO, sessionIds);
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
                // Ensure description streaming finishes and UI is updated
                streamingIO.onComplete();

                // Title is managed by the dialog: set and re-enable it.
                titleField.setText(suggestion.title());
                titleField.setEnabled(true);
                titleField.setCaretPosition(0);

                // Description area gets final content from the suggestion (tool execution result).
                descriptionArea.setText(suggestion.description());
                descriptionArea.setCaretPosition(0);

                showDescriptionHint(suggestion.usedCommitMessages());
            });
        }
    }

    private JPanel createSessionsTabPanel() {
        var panel = new JPanel(new BorderLayout());
        sessionsTableModel = new javax.swing.table.DefaultTableModel(new Object[] {"", "Session Name", "Tasks"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }
        };

        sessionsTable = new JTable(sessionsTableModel);
        sessionsTable.getColumnModel().getColumn(0).setMaxWidth(30);
        sessionsTable.setRowHeight(24);

        sessionsTableModel.addTableModelListener(e -> {
            int selectedCount = 0;
            for (int i = 0; i < sessionsTableModel.getRowCount(); i++) {
                if (Boolean.TRUE.equals(sessionsTableModel.getValueAt(i, 0))) {
                    selectedCount++;
                }
            }
            updateSessionsTabTitle(selectedCount);
        });

        selectAllSessionsCheckbox = new JCheckBox("Select/Deselect All", true);
        selectAllSessionsCheckbox.addActionListener(e -> {
            boolean selected = selectAllSessionsCheckbox.isSelected();
            for (int i = 0; i < sessionsTableModel.getRowCount(); i++) {
                sessionsTableModel.setValueAt(selected, i, 0);
            }
        });

        var headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        headerPanel.add(selectAllSessionsCheckbox);

        panel.add(headerPanel, BorderLayout.NORTH);
        panel.add(new JScrollPane(sessionsTable), BorderLayout.CENTER);
        return panel;
    }

    private final Map<Integer, UUID> rowToSessionId = new HashMap<>();

    private void updateSessionsTab(List<CommitInfo> commits) {
        if (commits.isEmpty()) {
            SwingUtilities.invokeLater(() -> {
                sessionsTableModel.setRowCount(0);
                rowToSessionId.clear();
                updateSessionsTabTitle(0);
            });
            return;
        }

        contextManager.submitBackgroundTask("Finding overlapping sessions", () -> {
            List<UUID> sessionIds = ReviewScope.findOverlappingSessions(contextManager, commits);
            var sessionInfos = new ArrayList<SessionManager.SessionInfo>();
            var taskCounts = new HashMap<UUID, Integer>();

            var sm = contextManager.getProject().getSessionManager();
            var allSessions = sm.listSessions().stream()
                    .filter(s -> sessionIds.contains(s.id()))
                    .toList();

            for (var info : allSessions) {
                sessionInfos.add(info);
                taskCounts.put(info.id(), sm.countAiResponses(info.id()));
            }

            SwingUtilities.invokeLater(() -> {
                sessionsTableModel.setRowCount(0);
                rowToSessionId.clear();
                for (int i = 0; i < sessionInfos.size(); i++) {
                    var info = sessionInfos.get(i);
                    sessionsTableModel.addRow(new Object[] {true, info.name(), taskCounts.getOrDefault(info.id(), 0)});
                    rowToSessionId.put(i, info.id());
                }
                updateSessionsTabTitle(sessionInfos.size());
            });
            return null;
        });
    }

    private void updateSessionsTabTitle(int count) {
        int idx = middleTabbedPane.indexOfComponent(sessionsTabPanel);
        if (idx < 0) return;
        String title = count == 1 ? "1 Session" : count + " Sessions";
        middleTabbedPane.setTitleAt(idx, title);
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

        // Capture session IDs on EDT
        List<UUID> selectedSessionUuids = new ArrayList<>();
        for (int i = 0; i < sessionsTableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(sessionsTableModel.getValueAt(i, 0))) {
                UUID id = rowToSessionId.get(i);
                if (id != null) selectedSessionUuids.add(id);
            }
        }

        contextManager.submitExclusiveAction(() -> {
            try {
                // Wait for background session sync to complete before proceeding
                try {
                    sessionSyncFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    logger.warn("Timed out waiting for session sync, proceeding anyway");
                } catch (InterruptedException | ExecutionException e) {
                    logger.warn("Session sync failed, proceeding anyway: {}", e.getMessage());
                }

                // Gather details on the EDT before going to background if they come from Swing components
                final String title = titleField.getText().trim();
                String body = descriptionArea.getText().trim();

                if (!selectedSessionUuids.isEmpty()) {
                    String ids = selectedSessionUuids.stream()
                            .map(UUID::toString)
                            .collect(java.util.stream.Collectors.joining(","));
                    body += "\n\nbrokk-session-ids:" + ids;
                }

                final String sourceBranch = sourceBranchComboBox.getSelectedItem();
                final String targetBranch = targetBranchComboBox.getSelectedItem();

                // Ensure selectedItem calls are safe
                if (sourceBranch == null || targetBranch == null) {
                    throw new IllegalStateException("Source or target branch not selected.");
                }

                var prUrl = workflowService.createPullRequest(sourceBranch, targetBranch, title, body);

                // Make sessions public
                var sm = contextManager.getProject().getSessionManager();
                for (UUID sessionId : selectedSessionUuids) {
                    sm.makePublicAsync(sessionId);
                }

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
                    String message;
                    if (GitHubErrorUtil.isNoCommitsBetweenError(ex)) {
                        var sourceBranch = sourceBranchComboBox.getSelectedItem();
                        var targetBranch = targetBranchComboBox.getSelectedItem();
                        var base = targetBranch != null ? targetBranch : "the target branch";
                        var head = sourceBranch != null ? sourceBranch : "the source branch";
                        message = GitHubErrorUtil.formatNoCommitsBetweenError(base, head);
                    } else {
                        var exMessage = ex.getMessage();
                        message =
                                "Unable to create Pull Request:\n" + (exMessage != null ? exMessage : "Unknown error");
                    }

                    chrome.toolError(message, "PR Creation Error");
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
        SwingUtilities.invokeLater(() -> descriptionHintLabel.setVisible(show));
    }

    private void spawnSuggestPrDetailsWorker(String sourceBranch, String targetBranch) {
        SwingUtilities.invokeLater(() -> showDescriptionHint(false));
        // Cancel previous background LLM calls
        if (currentSuggestPrDetailsWorker != null) {
            currentSuggestPrDetailsWorker.cancel(true);
        }

        // Capture session IDs on EDT
        List<UUID> selectedSessionUuids = new ArrayList<>();
        for (int i = 0; i < sessionsTableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(sessionsTableModel.getValueAt(i, 0))) {
                UUID id = rowToSessionId.get(i);
                if (id != null) selectedSessionUuids.add(id);
            }
        }

        currentSuggestPrDetailsWorker =
                new SuggestPrDetailsWorker(sourceBranch, targetBranch, selectedSessionUuids);
        currentSuggestPrDetailsWorker.execute();
    }
}
