package io.github.jbellis.brokk.gui.dialogs;

import static io.github.jbellis.brokk.gui.Constants.H_GAP;
import static io.github.jbellis.brokk.gui.Constants.V_GAP;

import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.components.ModelSelector;
import io.github.jbellis.brokk.mcp.McpServer;
import io.github.jbellis.brokk.util.Environment;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/** A modal dialog to configure the tools available to the Architect agent. */
public class ArchitectOptionsDialog {

    private static boolean isCodeIntelConfigured(IProject project) {
        var langs = project.getAnalyzerLanguages();
        return !langs.isEmpty() && !(langs.size() == 1 && langs.contains(Languages.NONE));
    }

    /**
     * Shows a modal dialog synchronously on the Event Dispatch Thread (EDT) to configure Architect tools and returns
     * the chosen options, or null if cancelled. This method blocks the calling thread until the dialog is closed.
     * Remembers the last selection for the current session.
     *
     * @param chrome The main application window reference for positioning and theme.
     * @return The selected ArchitectChoices (options + worktree preference), or null if the dialog was cancelled.
     */
    @Nullable
    public static ArchitectChoices showDialogAndWait(Chrome chrome) {
        var contextManager = chrome.getContextManager();
        var resultHolder = new AtomicReference<ArchitectChoices>();

        SwingUtil.runOnEdt(() -> {
            var project = chrome.getProject();

            final var supportsInterproceduralAnalysis =
                    contextManager.getAnalyzerWrapper().providesInterproceduralAnalysis();

            boolean codeIntelConfigured = isCodeIntelConfigured(project);

            var currentOptions = project.getArchitectOptions();
            boolean currentRunInWorktree = project.getArchitectRunInWorktree();

            var dialog = new JDialog(chrome.getFrame(), "Plan Options", true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setLayout(new BorderLayout(10, 10));

            var mainPanel = new JPanel();
            mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
            mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            // Model selectors
            var planningSelector = new ModelSelector(chrome);

            Service.ModelConfig planningDefault = project.getArchitectModelConfig();
            planningSelector.selectConfig(planningDefault);

            // Wrap each selector in a titled panel with insets
            var planningPanel = new JPanel(new BorderLayout());
            planningPanel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder("Planning Model"),
                    BorderFactory.createEmptyBorder(H_GAP, H_GAP, V_GAP, H_GAP)));
            planningPanel.add(planningSelector.getComponent(), BorderLayout.CENTER);

            var selectorsRow = new JPanel(new GridLayout(1, 1, 10, 0));
            selectorsRow.add(planningPanel);
            selectorsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            selectorsRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
            mainPanel.add(selectorsRow);

            // Group panels
            var subAgentsPanel = createTitledGroupPanel("Sub-agents");
            var workspaceToolsPanel = createTitledGroupPanel("Workspace tools");
            var externalToolsPanel = createTitledGroupPanel("External tools");
            var gitToolsPanel = createTitledGroupPanel("Git tools");

            // Sub-agents
            var codeCb = addCheckboxRow(subAgentsPanel, "Code Agent", "Allow invoking the Code Agent to modify files");
            codeCb.setSelected(currentOptions.includeCodeAgent());

            var searchCb = addCheckboxRow(
                    subAgentsPanel,
                    "Search Agent",
                    "Allow invoking the Search Agent to find information beyond the current Workspace");
            searchCb.setSelected(currentOptions.includeSearchAgent());

            // Workspace tools
            var contextCb = addCheckboxRow(
                    workspaceToolsPanel, "Deep Scan", "Begin by calling Deep Scan to update the Workspace");
            contextCb.setSelected(currentOptions.includeContextAgent());

            var validationCb = addCheckboxRow(
                    workspaceToolsPanel, "Dynamic Validation", "Infer test files to include with each Code Agent call");
            validationCb.setSelected(currentOptions.includeValidationAgent());

            var analyzerCb = addCheckboxRow(
                    workspaceToolsPanel,
                    "Code Intelligence Tools",
                    "Allow direct querying of code structure (e.g., find usages, call graphs)");
            analyzerCb.setSelected(currentOptions.includeAnalyzerTools() && codeIntelConfigured);
            analyzerCb.setEnabled(supportsInterproceduralAnalysis && codeIntelConfigured);
            if (!codeIntelConfigured) {
                analyzerCb.setToolTipText(
                        "Code Intelligence is not configured. Please configure languages in Project Settings.");
            } else if (!supportsInterproceduralAnalysis) {
                analyzerCb.setToolTipText("Code Intelligence tools for %s are not yet available"
                        .formatted(project.getAnalyzerLanguages()));
            }

            var workspaceCb = addCheckboxRow(
                    workspaceToolsPanel,
                    "Workspace Management Tools",
                    "Allow adding/removing files, URLs, or text to/from the Workspace");
            workspaceCb.setSelected(currentOptions.includeWorkspaceTools());

            // External tools
            var shellCbRef = new AtomicReference<JCheckBox>();
            if (Boolean.getBoolean("brokk.architectshell")) {
                var shellCb = addCheckboxRow(
                        externalToolsPanel,
                        "Sandboxed Shell Command",
                        "Allow executing shell commands inside a sandbox");
                boolean sandboxAvailable = Environment.isSandboxAvailable();
                shellCb.setEnabled(sandboxAvailable);
                shellCb.setSelected(currentOptions.includeShellCommand() && sandboxAvailable);
                if (!sandboxAvailable) {
                    shellCb.setToolTipText("Sandbox execution is not available on this platform.");
                }
                shellCbRef.set(shellCb);
            }

            var askHumanCb = addCheckboxRow(
                    externalToolsPanel,
                    "Ask-a-Human",
                    "Allow the agent to request guidance from the user via a dialog");
            askHumanCb.setSelected(currentOptions.includeAskHuman());

            // Git tools
            var gitState = GitState.from(project);
            var commitCb = createCommitCheckbox(currentOptions, gitToolsPanel, gitState);
            var prCb = createPrCheckbox(project, currentOptions, gitToolsPanel, gitState);

            // Keep commit & PR checkboxes consistent
            prCb.addActionListener(e -> {
                if (prCb.isSelected()) commitCb.setSelected(true);
            });
            commitCb.addActionListener(e -> {
                if (!commitCb.isSelected()) prCb.setSelected(false);
            });

            // Worktree Checkbox in Git tools group
            var worktreeCb = new JCheckBox();
            worktreeCb.setAlignmentY(0f);
            worktreeCb.setToolTipText(
                    "Create and run the Architect agent in a new Git worktree based on the current commit.");
            boolean worktreesSupported = gitState.gitAvailable()
                    && gitState.repo() != null
                    && gitState.repo().supportsWorktrees();
            worktreeCb.setEnabled(worktreesSupported);
            worktreeCb.setSelected(currentRunInWorktree && worktreesSupported);

            if (!gitState.gitAvailable()) {
                worktreeCb.setToolTipText("Git is not configured for this project.");
            } else if (!worktreesSupported) {
                worktreeCb.setToolTipText(
                        "Git worktrees are not supported by your Git version or repository configuration.");
            }

            var worktreeLabel = new JLabel(
                    "<html>Run in New Git worktree<br><i><font size='-2'>Create a new worktree for the Architect to work in, leaving your current one open for other tasks. The Architect will start with a copy of the current Workspace</font></i></html>");
            worktreeLabel.setAlignmentY(0f);
            worktreeLabel.setToolTipText(worktreeCb.getToolTipText());

            worktreeCb.addPropertyChangeListener(
                    "toolTipText", evt -> worktreeLabel.setToolTipText((String) evt.getNewValue()));

            worktreeLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    worktreeCb.doClick();
                }
            });

            var worktreeRow = new JPanel();
            worktreeRow.setLayout(new BoxLayout(worktreeRow, BoxLayout.X_AXIS));
            worktreeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            worktreeRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
            worktreeRow.add(worktreeCb);
            worktreeRow.add(Box.createHorizontalStrut(5));
            worktreeRow.add(worktreeLabel);
            gitToolsPanel.add(worktreeRow);

            // Add grouped panels to main
            mainPanel.add(subAgentsPanel);
            mainPanel.add(Box.createVerticalStrut(10));
            mainPanel.add(workspaceToolsPanel);
            mainPanel.add(Box.createVerticalStrut(10));
            mainPanel.add(externalToolsPanel);
            mainPanel.add(Box.createVerticalStrut(10));
            mainPanel.add(gitToolsPanel);

            // MCP Tools section
            mainPanel.add(Box.createVerticalStrut(10));
            var mcpToolsPanel = createTitledGroupPanel("MCP Tools");
            var mcpServers = project.getMcpConfig().servers();
            final var serverCheckboxMap = new LinkedHashMap<JCheckBox, McpServer>();
            var preselectedMcpTools = currentOptions.selectedMcpTools();

            if (mcpServers.isEmpty()) {
                mcpToolsPanel.add(new JLabel("No MCP servers configured in Settings."));
            } else {
                var selectionPanel = new JPanel();
                selectionPanel.setLayout(new BoxLayout(selectionPanel, BoxLayout.Y_AXIS));
                selectionPanel.setBorder(new EmptyBorder(0, 0, 0, 0));

                for (var server : mcpServers) {
                    var checkbox = new JCheckBox(server.name());
                    // Pre-select if any previously selected tool belongs to this server
                    boolean preselect = preselectedMcpTools.stream()
                            .anyMatch(t -> t.server().equals(server));
                    checkbox.setSelected(preselect);
                    serverCheckboxMap.put(checkbox, server);
                    selectionPanel.add(checkbox);
                }

                var scrollPane = new JScrollPane(selectionPanel);
                scrollPane.setPreferredSize(new Dimension(300, 150));
                mcpToolsPanel.add(scrollPane);
            }

            mainPanel.add(mcpToolsPanel);

            dialog.add(new JScrollPane(mainPanel), BorderLayout.CENTER);

            // Buttons
            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            var okButton = new JButton("OK");
            var cancelButton = new JButton("Cancel");
            buttonPanel.add(okButton);
            buttonPanel.add(cancelButton);
            dialog.add(buttonPanel, BorderLayout.SOUTH);

            // Actions
            okButton.addActionListener(e -> {
                Service.ModelConfig selectedPlanning = planningSelector.getModel();
                Service.ModelConfig selectedCode = project.getCodeModelConfig();

                boolean hasImages = contextManager
                        .topContext()
                        .allFragments()
                        .anyMatch(f -> !f.isText() && !f.getType().isOutputFragment());

                if (hasImages) {
                    var nonVision = new java.util.LinkedHashSet<String>();
                    var svc = contextManager.getService();

                    var planningModel = svc.getModel(selectedPlanning);
                    if (planningModel == null || !svc.supportsVision(planningModel)) {
                        nonVision.add(selectedPlanning.name() + " (Planning)");
                    }
                    var codeModel = svc.getModel(selectedCode);
                    if (codeModel == null || !svc.supportsVision(codeModel)) {
                        nonVision.add(selectedCode.name() + " (Code)");
                    }
                    if (searchCb.isSelected()) {
                        var searchModel = contextManager.getSearchModel();
                        if (!svc.supportsVision(searchModel)) {
                            nonVision.add(svc.nameOf(searchModel) + " (Search)");
                        }
                    }

                    if (!nonVision.isEmpty()) {
                        String msg =
                                "<html>The operation involves images, but these model(s) do not support vision:<br>"
                                        + String.join(", ", nonVision)
                                        + "<br><br>Please select vision-capable models.</html>";
                        JOptionPane.showMessageDialog(
                                dialog, msg, "Model Vision Support Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                }

                project.setArchitectModelConfig(selectedPlanning);

                var selectedMcpTools = new ArrayList<ArchitectAgent.McpTool>();
                for (var entry : serverCheckboxMap.entrySet()) {
                    if (entry.getKey().isSelected()) {
                        var server = entry.getValue();
                        var tools = server.tools();
                        if (tools != null) {
                            for (var toolName : tools) {
                                selectedMcpTools.add(new ArchitectAgent.McpTool(server, toolName));
                            }
                        }
                    }
                }

                var shellCheckbox = shellCbRef.get();

                var selectedOptions = new ArchitectAgent.ArchitectOptions(
                        selectedPlanning,
                        selectedCode,
                        contextCb.isSelected(),
                        validationCb.isSelected(),
                        supportsInterproceduralAnalysis && codeIntelConfigured && analyzerCb.isSelected(),
                        workspaceCb.isSelected(),
                        codeCb.isSelected(),
                        searchCb.isSelected(),
                        askHumanCb.isSelected(),
                        commitCb.isSelected(),
                        prCb.isSelected(),
                        shellCheckbox != null && shellCheckbox.isSelected(),
                        selectedMcpTools);

                boolean runInWorktreeSelected = worktreeCb.isSelected();

                project.setArchitectOptions(selectedOptions, runInWorktreeSelected);

                resultHolder.set(new ArchitectChoices(selectedOptions, runInWorktreeSelected));
                dialog.dispose();
            });

            cancelButton.addActionListener(e -> dialog.dispose());

            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    resultHolder.compareAndSet(null, null);
                }
            });

            dialog.getRootPane()
                    .registerKeyboardAction(
                            e -> {
                                resultHolder.compareAndSet(null, null);
                                dialog.dispose();
                            },
                            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                            JComponent.WHEN_IN_FOCUSED_WINDOW);

            dialog.pack();
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
        });

        return resultHolder.get();
    }

    private static JPanel createTitledGroupPanel(String title) {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createTitledBorder(title));
        return panel;
    }

    private static JCheckBox addCheckboxRow(JPanel container, String text, String description) {
        var html = "<html>" + text + "<br><i><font size='-2'>" + description + "</font></i></html>";

        var cb = new JCheckBox();
        cb.setAlignmentY(0f);

        var label = new JLabel(html);
        label.setAlignmentY(0f);

        cb.addPropertyChangeListener("toolTipText", evt -> label.setToolTipText((String) evt.getNewValue()));

        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                cb.doClick();
            }
        });

        var row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        row.add(cb);
        row.add(Box.createHorizontalStrut(5));
        row.add(label);

        container.add(row);
        return cb;
    }

    private record GitState(
            boolean gitAvailable, boolean onDefaultBranch, String defaultBranchName, @Nullable GitRepo repo) {
        static GitState from(@Nullable IProject project) {
            if (project == null || !project.hasGit()) {
                return new GitState(false, false, "", null);
            }
            var repo = (GitRepo) project.getRepo();
            try {
                var defaultBranchName = repo.getDefaultBranch();
                var onDefaultBranch = Objects.equals(repo.getCurrentBranch(), defaultBranchName);
                return new GitState(true, onDefaultBranch, defaultBranchName, repo);
            } catch (GitAPIException e) {
                return new GitState(false, false, "", null);
            }
        }
    }

    private static JCheckBox createCommitCheckbox(
            ArchitectAgent.ArchitectOptions currentOptions, JPanel targetPanel, GitState gitState) {
        var commitUsable = gitState.gitAvailable();

        var commitCb = addCheckboxRow(targetPanel, "Commit changes", "Stage & commit all current edits.");
        commitCb.setEnabled(commitUsable);
        commitCb.setSelected(commitUsable && currentOptions.includeGitCommit());

        if (!gitState.gitAvailable()) {
            commitCb.setToolTipText("No Git repository detected for this project.");
        }
        return commitCb;
    }

    private static JCheckBox createPrCheckbox(
            @Nullable IProject project,
            ArchitectAgent.ArchitectOptions currentOptions,
            JPanel targetPanel,
            GitState gitState) {
        boolean prDisabled = !gitState.gitAvailable()
                || gitState.onDefaultBranch()
                || project == null
                || !GitHubAuth.tokenPresent(project)
                || gitState.repo() == null
                || gitState.repo().getRemoteUrl("origin") == null;

        var prCb = addCheckboxRow(
                targetPanel,
                "Create PR (includes push)",
                "Pushes current branch and opens a pull request. Disabled on default branch or without token.");
        prCb.setEnabled(!prDisabled);
        prCb.setSelected(!prDisabled && currentOptions.includeGitCreatePr());

        if (!gitState.gitAvailable()) {
            prCb.setToolTipText("No Git repository detected for this project.");
        } else if (gitState.onDefaultBranch()) {
            prCb.setToolTipText(
                    "Cannot create PR from the default branch (%s).".formatted(gitState.defaultBranchName()));
        } else if (project == null || !GitHubAuth.tokenPresent(project)) {
            prCb.setToolTipText("No GitHub credentials found (e.g. GITHUB_TOKEN environment variable).");
        } else if (gitState.repo() == null || gitState.repo().getRemoteUrl("origin") == null) {
            prCb.setToolTipText("Git repository does not have a remote named 'origin'.");
        }
        return prCb;
    }
}
