package ai.brokk.gui.dialogs;

import ai.brokk.IConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.util.BuildVerifier;
import ai.brokk.util.Environment;
import ai.brokk.util.EnvironmentJava;
import ai.brokk.util.ShellConfig;
import com.google.common.io.Files;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.BorderFactory;
import javax.swing.SwingWorker;
import javax.swing.plaf.basic.BasicComboBoxEditor;
import javax.swing.table.AbstractTableModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Extracted Build settings panel formerly residing inside SettingsProjectPanel.
 * Responsible for presenting and persisting build/lint/test executor settings and interacting with BuildAgent.
 */
public class SettingsProjectBuildPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(SettingsProjectBuildPanel.class);

    private static class ScrollablePanel extends JPanel implements Scrollable {
        public ScrollablePanel(LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 32;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
    // Action command constants
    private static final String ACTION_INFER = "infer";
    private static final String ACTION_CANCEL = "cancel";

    private final Chrome chrome;
    private final SettingsDialog parentDialog;
    private final JButton okButtonParent;
    private final JButton cancelButtonParent;
    private final JButton applyButtonParent;
    private final IProject project;

    // UI components
    private JTextField buildCleanCommandField = new JTextField();
    private JTextField allTestsCommandField = new JTextField();
    private JTextField afterTaskListCommandField = new JTextField();

    private JRadioButton runAllTestsRadio = new JRadioButton(IProject.CodeAgentTestScope.ALL.toString());
    private JRadioButton runTestsInWorkspaceRadio = new JRadioButton(IProject.CodeAgentTestScope.WORKSPACE.toString());

    private record TimeoutItem(long seconds) {
        static final TimeoutItem NO_TIMEOUT = new TimeoutItem(-1);

        @Override
        public String toString() {
            if (this.equals(NO_TIMEOUT) || seconds == -1) return "No timeout";
            return String.valueOf(seconds);
        }
    }

    private JComboBox<TimeoutItem> runTimeoutComboBox = createTimeoutComboBox();
    private JComboBox<TimeoutItem> testTimeoutComboBox = createTimeoutComboBox();

    private TimeoutItem lastValidRunTimeout = TimeoutItem.NO_TIMEOUT;
    private TimeoutItem lastValidTestTimeout = TimeoutItem.NO_TIMEOUT;

    private JProgressBar buildProgressBar = new JProgressBar();
    private MaterialButton inferBuildDetailsButton = new MaterialButton("Infer Build Details");
    private JCheckBox setJavaHomeCheckbox = new JCheckBox("Set JAVA_HOME to");
    private JdkSelector jdkSelector = new JdkSelector();
    private JComboBox<Language> primaryLanguageComboBox = new JComboBox<>();

    // Modules UI
    private final List<BuildAgent.ModuleBuildEntry> modulesList = new ArrayList<>();
    private final ModulesTableModel modulesTableModel = new ModulesTableModel();
    private final JTable modulesTable = new JTable(modulesTableModel);

    // Executor configuration UI
    private JTextField executorArgsField = new JTextField(20);
    private MaterialButton testExecutorButton = new MaterialButton("Test");
    private MaterialButton resetExecutorButton = new MaterialButton("Reset");
    private JComboBox<Object> commonExecutorsComboBox = new JComboBox<>();

    // System-default executor
    private static final boolean IS_WINDOWS = Environment.isWindows();
    private static final String DEFAULT_EXECUTOR_PATH = IS_WINDOWS ? "powershell.exe" : "/bin/sh";
    private static final String DEFAULT_EXECUTOR_ARGS = IS_WINDOWS ? "-Command" : "-c";

    @Nullable
    private Future<?> manualInferBuildTaskFuture;

    // Pending BuildDetails from agent run, saved on Apply/OK
    @Nullable
    private BuildAgent.BuildDetails pendingBuildDetails;
    // LLM-added patterns from the most recent agent run (for UI highlighting)
    @Nullable
    private Set<String> pendingLlmAddedPatterns;

    private final JPanel bannerPanel;

    private final JPanel contentPanel = new ScrollablePanel(new GridBagLayout());

    public SettingsProjectBuildPanel(
            Chrome chrome, SettingsDialog parentDialog, JButton okButton, JButton cancelButton, JButton applyButton) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.parentDialog = parentDialog;
        this.okButtonParent = okButton;
        this.cancelButtonParent = cancelButton;
        this.applyButtonParent = applyButton;
        this.project = chrome.getProject();
        this.bannerPanel = createBanner();

        initComponents();

        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
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

        var close = new MaterialButton("×");
        close.setMargin(new Insets(0, 4, 0, 4));
        close.addActionListener(e -> {
            p.setVisible(false);
        });
        p.add(close, BorderLayout.EAST);
        p.setVisible(false); // Initially hidden
        return p;
    }

    private JComboBox<TimeoutItem> createTimeoutComboBox() {
        var combo = new JComboBox<>(new TimeoutItem[] {
            TimeoutItem.NO_TIMEOUT,
            new TimeoutItem(30),
            new TimeoutItem(60),
            new TimeoutItem(120),
            new TimeoutItem(300),
            new TimeoutItem(600),
            new TimeoutItem(1800),
            new TimeoutItem(3600),
            new TimeoutItem(10800)
        });
        combo.setEditable(true);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                if (value instanceof TimeoutItem item) {
                    value = item.toString();
                }
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        });
        return combo;
    }

    private void initComponents() {
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 0, 5, 0); // Spacing between titled boxes
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        int row = 0;

        // Add banner at the top
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.insets = new Insets(2, 2, 2, 2);
        contentPanel.add(bannerPanel, gbc);
        gbc.insets = new Insets(5, 0, 5, 0);

        // --- 1. Language Configuration Panel ---
        var languagePanel = new JPanel(new GridBagLayout());
        languagePanel.setBorder(BorderFactory.createTitledBorder("Language Configuration"));
        var langGbc = new GridBagConstraints();
        langGbc.insets = new Insets(2, 2, 2, 2);
        langGbc.fill = GridBagConstraints.HORIZONTAL;
        int langRow = 0;

        // Primary language
        langGbc.gridx = 0;
        langGbc.gridy = langRow;
        langGbc.weightx = 0.0;
        langGbc.anchor = GridBagConstraints.WEST;
        langGbc.fill = GridBagConstraints.NONE;
        languagePanel.add(new JLabel("Primary language:"), langGbc);
        langGbc.gridx = 1;
        langGbc.gridy = langRow++;
        langGbc.weightx = 1.0;
        langGbc.fill = GridBagConstraints.HORIZONTAL;
        languagePanel.add(primaryLanguageComboBox, langGbc);

        // JDK selection controls
        langGbc.gridx = 0;
        langGbc.gridy = langRow;
        langGbc.weightx = 0.0;
        langGbc.fill = GridBagConstraints.NONE;
        languagePanel.add(setJavaHomeCheckbox, langGbc);

        jdkSelector.setEnabled(false);
        jdkSelector.setBrowseParent(parentDialog);
        langGbc.gridx = 1;
        langGbc.gridy = langRow++;
        langGbc.weightx = 1.0;
        langGbc.fill = GridBagConstraints.HORIZONTAL;
        languagePanel.add(jdkSelector, langGbc);

        primaryLanguageComboBox.addActionListener(e -> {
            var sel = (Language) primaryLanguageComboBox.getSelectedItem();
            updateJdkControlsVisibility(sel);
            if (sel == Languages.JAVA) {
                populateJdkControlsFromProject();
            }
        });
        updateJdkControlsVisibility(project.computedBuildLanguage());
        setJavaHomeCheckbox.addActionListener(e -> jdkSelector.setEnabled(setJavaHomeCheckbox.isSelected()));

        gbc.gridy = row++;
        contentPanel.add(languagePanel, gbc);

        // --- 2. Build Configuration Panel ---
        var buildConfigPanel = new JPanel(new GridBagLayout());
        buildConfigPanel.setBorder(BorderFactory.createTitledBorder("Build Configuration"));
        var buildGbc = new GridBagConstraints();
        buildGbc.insets = new Insets(2, 2, 2, 2);
        buildGbc.fill = GridBagConstraints.HORIZONTAL;
        int buildRow = 0;

        // Build/Lint Command
        buildGbc.gridx = 0;
        buildGbc.gridy = buildRow;
        buildGbc.weightx = 0.0;
        buildGbc.anchor = GridBagConstraints.WEST;
        buildConfigPanel.add(new JLabel("Build/Lint Command:"), buildGbc);
        buildGbc.gridx = 1;
        buildGbc.gridy = buildRow++;
        buildGbc.weightx = 1.0;
        buildConfigPanel.add(buildCleanCommandField, buildGbc);

        // Test All Command
        buildGbc.gridx = 0;
        buildGbc.gridy = buildRow;
        buildGbc.weightx = 0.0;
        buildConfigPanel.add(new JLabel("Test All Command:"), buildGbc);
        buildGbc.gridx = 1;
        buildGbc.gridy = buildRow++;
        buildGbc.weightx = 1.0;
        buildConfigPanel.add(allTestsCommandField, buildGbc);

        // Post-Task List Command
        buildGbc.gridx = 0;
        buildGbc.gridy = buildRow;
        buildGbc.weightx = 0.0;
        buildConfigPanel.add(new JLabel("Post-Task List Command:"), buildGbc);
        buildGbc.gridx = 1;
        buildGbc.gridy = buildRow++;
        buildGbc.weightx = 1.0;
        buildConfigPanel.add(afterTaskListCommandField, buildGbc);
        var afterTaskListInfo = new JLabel(
                "Command to run after all tasks in a task list complete successfully (e.g., full test suite)");
        afterTaskListInfo.setFont(afterTaskListInfo
                .getFont()
                .deriveFont(Font.ITALIC, afterTaskListInfo.getFont().getSize() * 0.9f));
        buildGbc.gridx = 1;
        buildGbc.gridy = buildRow++;
        buildGbc.insets = new Insets(0, 2, 8, 2);
        buildConfigPanel.add(afterTaskListInfo, buildGbc);
        buildGbc.insets = new Insets(2, 2, 2, 2); // Reset insets

        // Code Agent Tests
        buildGbc.gridx = 0;
        buildGbc.gridy = buildRow;
        buildGbc.weightx = 0.0;
        buildGbc.anchor = GridBagConstraints.WEST;
        buildGbc.fill = GridBagConstraints.NONE;
        buildConfigPanel.add(new JLabel("Code Agent Tests:"), buildGbc);
        var testScopeGroup = new ButtonGroup();
        testScopeGroup.add(runAllTestsRadio);
        testScopeGroup.add(runTestsInWorkspaceRadio);
        var radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        radioPanel.setOpaque(false);
        radioPanel.add(runAllTestsRadio);
        radioPanel.add(runTestsInWorkspaceRadio);
        buildGbc.gridx = 1;
        buildGbc.gridy = buildRow++;
        buildGbc.weightx = 1.0;
        buildGbc.fill = GridBagConstraints.HORIZONTAL;
        buildConfigPanel.add(radioPanel, buildGbc);

        // Run Command Timeout
        buildGbc.gridx = 0;
        buildGbc.gridy = buildRow;
        buildGbc.weightx = 0.0;
        buildGbc.anchor = GridBagConstraints.WEST;
        buildGbc.fill = GridBagConstraints.NONE;
        buildConfigPanel.add(new JLabel("Run Command Timeout (sec):"), buildGbc);

        buildGbc.gridx = 1;
        buildGbc.gridy = buildRow++;
        buildGbc.weightx = 1.0;
        buildGbc.fill = GridBagConstraints.HORIZONTAL;
        buildConfigPanel.add(runTimeoutComboBox, buildGbc);

        // Test Command Timeout
        buildGbc.gridx = 0;
        buildGbc.gridy = buildRow;
        buildGbc.weightx = 0.0;
        buildGbc.fill = GridBagConstraints.NONE;
        buildConfigPanel.add(new JLabel("Test Command Timeout (sec):"), buildGbc);

        buildGbc.gridx = 1;
        buildGbc.gridy = buildRow++;
        buildGbc.weightx = 1.0;
        buildGbc.fill = GridBagConstraints.HORIZONTAL;
        buildConfigPanel.add(testTimeoutComboBox, buildGbc);

        setupTimeoutValidation(runTimeoutComboBox, true);
        setupTimeoutValidation(testTimeoutComboBox, false);

        // Infer/Verify buttons
        var buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        inferBuildDetailsButton.setActionCommand(ACTION_INFER);
        inferBuildDetailsButton.setName("inferBuildDetailsButton");
        buttonsPanel.add(inferBuildDetailsButton);
        var verifyBuildButton = new MaterialButton("Verify Configuration");
        verifyBuildButton.setName("verifyBuildButton");
        verifyBuildButton.addActionListener(e -> verifyBuildConfiguration());
        buttonsPanel.add(verifyBuildButton);

        buildGbc.gridx = 1;
        buildGbc.gridy = buildRow++;
        buildGbc.weightx = 1.0;
        buildGbc.weighty = 0.0;
        buildGbc.fill = GridBagConstraints.HORIZONTAL;
        buildGbc.anchor = GridBagConstraints.WEST;
        buildConfigPanel.add(buttonsPanel, buildGbc);

        // --- 3. Modules Configuration Panel ---
        var modulesPanel = new JPanel(new BorderLayout(5, 5));
        modulesPanel.setBorder(BorderFactory.createTitledBorder("Modules"));
        modulesPanel.setPreferredSize(new Dimension(400, 200));

        modulesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        modulesTable.getTableHeader().setReorderingAllowed(false);
        modulesTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && modulesTable.getSelectedRow() != -1) {
                    editModule();
                }
            }
        });

        var scrollPane = new JScrollPane(modulesTable);
        modulesPanel.add(scrollPane, BorderLayout.CENTER);

        var moduleButtons = new JPanel(new GridBagLayout());
        var mbGbc = new GridBagConstraints();
        mbGbc.gridx = 0;
        mbGbc.gridy = 0;
        mbGbc.fill = GridBagConstraints.HORIZONTAL;
        mbGbc.insets = new Insets(0, 5, 2, 0);

        var addBtn = new MaterialButton("+");
        addBtn.addActionListener(e -> addModule());
        moduleButtons.add(addBtn, mbGbc);

        var removeBtn = new MaterialButton("-");
        removeBtn.addActionListener(e -> removeModule());
        mbGbc.gridy++;
        moduleButtons.add(removeBtn, mbGbc);

        var editBtn = new MaterialButton("Edit");
        editBtn.addActionListener(e -> editModule());
        mbGbc.gridy++;
        moduleButtons.add(editBtn, mbGbc);

        var upBtn = new MaterialButton("▲");
        upBtn.addActionListener(e -> moveModule(-1));
        mbGbc.gridy++;
        moduleButtons.add(upBtn, mbGbc);

        var downBtn = new MaterialButton("▼");
        downBtn.addActionListener(e -> moveModule(1));
        mbGbc.gridy++;
        moduleButtons.add(downBtn, mbGbc);

        modulesPanel.add(moduleButtons, BorderLayout.EAST);

        gbc.gridy = row++;
        gbc.weighty = 0.5;
        gbc.fill = GridBagConstraints.BOTH;
        contentPanel.add(modulesPanel, gbc);
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Progress bar for Build Agent
        JPanel progressWrapper = new JPanel(new BorderLayout());
        progressWrapper.setPreferredSize(buildProgressBar.getPreferredSize());
        progressWrapper.add(buildProgressBar, BorderLayout.CENTER);
        buildProgressBar.setIndeterminate(true);
        buildGbc.gridx = 1;
        buildGbc.gridy = buildRow++;
        buildGbc.fill = GridBagConstraints.HORIZONTAL;
        buildGbc.anchor = GridBagConstraints.EAST;
        buildConfigPanel.add(progressWrapper, buildGbc);

        gbc.gridy = row++;
        contentPanel.add(buildConfigPanel, gbc);

        // --- 3. Shell Configuration Panel ---
        var shellConfigPanel = new JPanel(new GridBagLayout());
        shellConfigPanel.setBorder(BorderFactory.createTitledBorder("Shell Configuration"));
        var shellGbc = new GridBagConstraints();
        shellGbc.insets = new Insets(2, 2, 2, 2);
        shellGbc.fill = GridBagConstraints.HORIZONTAL;
        int shellRow = 0;

        // Execute with
        shellGbc.gridx = 0;
        shellGbc.gridy = shellRow;
        shellGbc.weightx = 0.0;
        shellGbc.anchor = GridBagConstraints.WEST;
        shellGbc.fill = GridBagConstraints.NONE;
        shellConfigPanel.add(new JLabel("Execute with:"), shellGbc);
        shellGbc.gridx = 1;
        shellGbc.gridy = shellRow++;
        shellGbc.weightx = 1.0;
        shellGbc.fill = GridBagConstraints.HORIZONTAL;
        shellConfigPanel.add(commonExecutorsComboBox, shellGbc);

        // Default parameters
        shellGbc.gridx = 0;
        shellGbc.gridy = shellRow;
        shellGbc.weightx = 0.0;
        shellGbc.fill = GridBagConstraints.NONE;
        shellConfigPanel.add(new JLabel("Default parameters:"), shellGbc);
        shellGbc.gridx = 1;
        shellGbc.gridy = shellRow++;
        shellGbc.weightx = 1.0;
        shellGbc.fill = GridBagConstraints.HORIZONTAL;
        shellConfigPanel.add(executorArgsField, shellGbc);

        // Test / Reset buttons
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
        shellGbc.gridx = 1;
        shellGbc.gridy = shellRow++;
        shellGbc.weightx = 1.0;
        shellGbc.fill = GridBagConstraints.HORIZONTAL;
        shellGbc.anchor = GridBagConstraints.WEST;
        shellConfigPanel.add(executorTestPanel, shellGbc);

        gbc.gridy = row++;
        contentPanel.add(shellConfigPanel, gbc);

        // Agent running check, listeners, and glue
        CompletableFuture<BuildAgent.BuildDetails> detailsFuture = project.getBuildDetailsFuture();
        boolean initialAgentRunning = !detailsFuture.isDone();

        buildProgressBar.setVisible(initialAgentRunning);
        if (initialAgentRunning) {
            setButtonToInferenceInProgress(false);

            detailsFuture.whenCompleteAsync(
                    (result, ex) -> {
                        SwingUtilities.invokeLater(() -> {
                            if (manualInferBuildTaskFuture == null) {
                                setButtonToReadyState();
                            }
                        });
                    },
                    ForkJoinPool.commonPool());
        }

        inferBuildDetailsButton.addActionListener(e -> runBuildAgent());
        initializeExecutorUI();

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        contentPanel.add(Box.createVerticalGlue(), gbc);

        // Populate initial values
        populatePrimaryLanguageComboBox();
        var selectedLang = project.computedBuildLanguage();
        primaryLanguageComboBox.setSelectedItem(selectedLang);
        updateJdkControlsVisibility(selectedLang);
        if (selectedLang == Languages.JAVA) {
            populateJdkControlsFromProject();
        }

        // Load build panel settings (project may or may not have details yet)
        if (project.hasBuildDetails()) {
            try {
                loadBuildPanelSettings();
            } catch (Exception e) {
                logger.warn(
                        "Could not load build details for settings panel, using EMPTY. Error: {}", e.getMessage(), e);
            }
        } else {
            // When initial details are not ready, they'll be applied when project.getBuildDetailsFuture completes
            detailsFuture.whenCompleteAsync(
                    (detailsResult, ex) -> {
                        SwingUtilities.invokeLater(() -> {
                            try {
                                if (detailsResult != null
                                        && !Objects.equals(detailsResult, BuildAgent.BuildDetails.EMPTY)) {
                                    // No LLM patterns when loading from storage - don't highlight
                                    updateBuildDetailsFieldsFromAgent(detailsResult, null);
                                }
                            } catch (Exception e) {
                                logger.warn("Error while applying build details from future: {}", e.getMessage(), e);
                            }
                        });
                    },
                    ForkJoinPool.commonPool());
        }
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

    private void runVerificationUI(Window owner, String title, Function<Consumer<String>, String> task) {
        var verifyDialog = new BaseThemedDialog(owner, title);
        verifyDialog.setSize(600, 400);
        verifyDialog.setLocationRelativeTo(owner);

        var outputArea = new JTextArea();
        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        var scrollPane = new JScrollPane(outputArea);

        var progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);

        var closeButton = new MaterialButton("Close");
        closeButton.setEnabled(false);
        closeButton.addActionListener(e -> verifyDialog.dispose());

        var cancelButton = new MaterialButton("Cancel");

        var bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.add(progressBar);
        bottomPanel.add(cancelButton);
        bottomPanel.add(closeButton);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        var root = verifyDialog.getContentRoot();
        root.setLayout(new BorderLayout(5, 5));
        root.add(scrollPane, BorderLayout.CENTER);
        root.add(bottomPanel, BorderLayout.SOUTH);

        SwingWorker<String, String> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                return task.apply(s -> publish(s));
            }

            @Override
            protected void process(List<String> chunks) {
                for (String chunk : chunks) {
                    outputArea.append(chunk);
                }
            }

            @Override
            protected void done() {
                progressBar.setVisible(false);
                cancelButton.setEnabled(false);
                closeButton.setEnabled(true);
                try {
                    String result = get();
                    outputArea.append("\n--- VERIFICATION COMPLETE ---\n");
                    outputArea.append(result);
                } catch (CancellationException | InterruptedException e) {
                    outputArea.append("\n--- VERIFICATION CANCELLED ---\n");
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                } catch (Exception e) {
                    logger.error("Error during build verification", e);
                    outputArea.append("\n--- An unexpected error occurred during verification ---\n");
                    outputArea.append(e.toString());
                }
                outputArea.setCaretPosition(outputArea.getDocument().getLength());
            }
        };

        cancelButton.addActionListener(e -> worker.cancel(true));

        worker.execute();
        verifyDialog.setVisible(true);
    }

    private void verifyBuildConfiguration() {
        runVerificationUI(parentDialog, "Verifying Build Configuration", publish -> {
            var envVars = computeEnvFromUi();

            // Step 1: Build/Lint command
            String buildCmd = buildCleanCommandField.getText().trim();
            if (!buildCmd.isEmpty()) {
                publish.accept("--- Verifying Build/Lint Command ---\n");
                publish.accept("$ " + buildCmd + "\n");
                var result = BuildVerifier.verifyStreaming(project, buildCmd, envVars, line -> publish.accept(line + "\n"));
                if (result.success()) {
                    publish.accept("\nSUCCESS: Build/Lint command completed successfully.\n\n");
                } else {
                    publish.accept("\nERROR: Build/Lint command failed.\n");
                    publish.accept(result.output() + "\n");
                    return "Build/Lint command failed.";
                }
            } else {
                publish.accept("--- Skipping empty Build/Lint Command ---\n\n");
            }

            if (Thread.interrupted()) return "Cancelled";

            // Step 2: Test All command
            String testAllCmd = allTestsCommandField.getText().trim();
            if (!testAllCmd.isEmpty()) {
                publish.accept("--- Verifying Test All Command ---\n");
                publish.accept("$ " + testAllCmd + "\n");
                var result = BuildVerifier.verifyStreaming(project, testAllCmd, envVars, line -> publish.accept(line + "\n"));
                if (result.success()) {
                    publish.accept("\nSUCCESS: Test All command completed successfully.\n\n");
                } else {
                    publish.accept("\nERROR: Test All command failed.\n");
                    publish.accept(result.output() + "\n");
                    return "Test All command failed.";
                }
            } else {
                publish.accept("--- Skipping empty Test All Command ---\n\n");
            }

            if (Thread.interrupted()) return "Cancelled";

            // Step 3: Verify Modules
            if (!modulesList.isEmpty()) {
                for (var module : modulesList) {
                    if (Thread.interrupted()) return "Cancelled";
                    publish.accept("--- Verifying Module: [" + module.alias() + "] ---\n");

                    // Verify Module Build/Lint
                    String mBuild = module.buildLintCommand().trim();
                    if (!mBuild.isEmpty()) {
                        publish.accept("Verifying Build/Lint: $ " + mBuild + "\n");
                        var result = BuildVerifier.verifyStreaming(project, mBuild, envVars, line -> publish.accept(line + "\n"));
                        if (result.success()) {
                            publish.accept("SUCCESS: Build/Lint for module [" + module.alias() + "] passed.\n");
                        } else {
                            publish.accept("\nERROR: Build/Lint failed for module [" + module.alias() + "].\n");
                            publish.accept(result.output() + "\n");
                            return "Build/Lint command failed for module " + module.alias() + ".";
                        }
                    }

                    if (Thread.interrupted()) return "Cancelled";

                    // Verify Module Test All
                    String mTest = module.testAllCommand().trim();
                    if (!mTest.isEmpty()) {
                        publish.accept("Verifying Test All: $ " + mTest + "\n");
                        var result = BuildVerifier.verifyStreaming(project, mTest, envVars, line -> publish.accept(line + "\n"));
                        if (result.success()) {
                            publish.accept("SUCCESS: Test All for module [" + module.alias() + "] passed.\n\n");
                        } else {
                            publish.accept("\nERROR: Test All failed for module [" + module.alias() + "].\n");
                            publish.accept(result.output() + "\n");
                            return "Test All command failed for module " + module.alias() + ".";
                        }
                    } else {
                        publish.accept("\n");
                    }
                }
            }

            return "Verification successful!";
        });
    }

    private void runBuildAgent() {
        String action = inferBuildDetailsButton.getActionCommand();

        if (ACTION_CANCEL.equals(action)) {
            // We're in cancel mode - cancel the running task
            if (manualInferBuildTaskFuture != null && !manualInferBuildTaskFuture.isDone()) {
                boolean cancelled = manualInferBuildTaskFuture.cancel(true);
                logger.debug("Build agent cancellation requested, result: {}", cancelled);
            }
            return;
        }

        var cm = chrome.getContextManager();
        var proj = project;

        setBuildControlsEnabled(false); // Disable controls in this panel
        setButtonToInferenceInProgress(true); // true = set Cancel text (manual agent)

        manualInferBuildTaskFuture = cm.submitExclusiveAction(() -> {
            try {
                chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Starting Build Agent...");
                var agent = new BuildAgent(
                        proj,
                        cm.getLlm(cm.getService().getScanModel(), "Infer build details", TaskResult.Type.NONE),
                        cm.getToolRegistry());
                var newBuildDetails = agent.execute();

                // Check if task was cancelled during execution
                if (Thread.currentThread().isInterrupted()) {
                    logger.info("Build Agent completed but thread was interrupted - treating as cancellation");
                    throw new InterruptedException("Build Agent cancelled by user");
                }

                if (Objects.equals(newBuildDetails, BuildAgent.BuildDetails.EMPTY)) {
                    logger.info("Build Agent returned EMPTY - no build configuration found");
                    SwingUtilities.invokeLater(() -> {
                        String message =
                                "Could not determine build configuration - project structure may be unsupported or incomplete.";
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, message);
                        JOptionPane.showMessageDialog(
                                SettingsProjectBuildPanel.this,
                                message,
                                "No Build Configuration Found",
                                JOptionPane.INFORMATION_MESSAGE);
                    });
                } else {
                    var llmPatterns = agent.getLlmAddedPatterns();
                    SwingUtilities.invokeLater(() -> {
                        // Store pending details for later save on Apply/OK
                        pendingBuildDetails = newBuildDetails;
                        pendingLlmAddedPatterns = llmPatterns;
                        updateBuildDetailsFieldsFromAgent(newBuildDetails, llmPatterns);
                        chrome.showNotification(
                                IConsoleIO.NotificationRole.INFO, "Build Agent finished. Review and apply settings.");
                    });
                }
            } catch (InterruptedException ex) {
                logger.info("Build Agent execution cancelled by user");
                Thread.currentThread().interrupt(); // Restore interrupt status
                showBuildAgentCancelledNotification();
            } catch (Exception ex) {
                // Check if this is a wrapped InterruptedException (BuildAgent wraps it in RuntimeException)
                Throwable cause = ex.getCause();
                if (cause instanceof InterruptedException) {
                    logger.info("Build Agent execution cancelled by user (wrapped exception)");
                    Thread.currentThread().interrupt(); // Restore interrupt status
                    showBuildAgentCancelledNotification();
                    return;
                }
                // Not a cancellation - treat as error
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
        buildProgressBar.setVisible(!enabled);

        Stream.of(
                        buildCleanCommandField,
                        allTestsCommandField,
                        afterTaskListCommandField,
                        runAllTestsRadio,
                        runTestsInWorkspaceRadio,
                        // Parent dialog buttons
                        okButtonParent,
                        cancelButtonParent,
                        applyButtonParent)
                .filter(Objects::nonNull)
                .forEach(control -> control.setEnabled(enabled));
    }

    private void updateBuildDetailsFieldsFromAgent(
            BuildAgent.BuildDetails details, @Nullable Set<String> llmAddedPatterns) {
        SwingUtilities.invokeLater(() -> {
            // Update this panel's fields - only overwrite if agent provided a value
            if (!details.buildLintCommand().isBlank()) {
                buildCleanCommandField.setText(details.buildLintCommand());
            }
            if (!details.testAllCommand().isBlank()) {
                allTestsCommandField.setText(details.testAllCommand());
            }
            if (!details.afterTaskListCommand().isBlank()) {
                afterTaskListCommandField.setText(details.afterTaskListCommand());
            }

            if (!details.modules().isEmpty()) {
                modulesList.clear();
                modulesList.addAll(details.modules());
                modulesTableModel.fireTableDataChanged();
            }

            // Also refresh the CI exclusions list in the parent SettingsProjectPanel
            try {
                var spp = parentDialog.getProjectPanel();
                spp.updateExclusionPatternsFromAgent(details.exclusionPatterns(), llmAddedPatterns);
            } catch (Exception ex) {
                logger.warn("Failed to update CI exclusions list from agent details: {}", ex.getMessage(), ex);
            }

            logger.trace("UI fields updated with new BuildDetails from agent: {}", details);
        });
    }

    public void loadBuildPanelSettings() {
        BuildAgent.BuildDetails details;
        try {
            details = project.awaitBuildDetails();
        } catch (Exception e) {
            logger.warn("Could not load build details for settings panel, using EMPTY. Error: {}", e.getMessage(), e);
            details = BuildAgent.BuildDetails.EMPTY; // Fallback to EMPTY
            chrome.toolError("Error loading build details: " + e.getMessage() + ". Using defaults.");
        }

        buildCleanCommandField.setText(details.buildLintCommand());
        allTestsCommandField.setText(details.testAllCommand());
        afterTaskListCommandField.setText(details.afterTaskListCommand());

        modulesList.clear();
        modulesList.addAll(details.modules());
        modulesTableModel.fireTableDataChanged();

        if (project.getCodeAgentTestScope() == IProject.CodeAgentTestScope.ALL) {
            runAllTestsRadio.setSelected(true);
        } else {
            runTestsInWorkspaceRadio.setSelected(true);
        }

        var mainProject = project.getMainProject();
        long runTimeout = mainProject.getRunCommandTimeoutSeconds();
        selectTimeoutInCombo(runTimeoutComboBox, runTimeout);

        long testTimeout = mainProject.getTestCommandTimeoutSeconds();
        selectTimeoutInCombo(testTimeoutComboBox, testTimeout);

        populateJdkControlsFromProject();

        var selectedLang = project.computedBuildLanguage();
        primaryLanguageComboBox.setSelectedItem(selectedLang);
        updateJdkControlsVisibility(selectedLang);
        if (selectedLang == Languages.JAVA) {
            populateJdkControlsFromProject();
        }

        // Load executor configuration
        ShellConfig shellConfig = project.getShellConfig();
        String executorPath = shellConfig.executable();
        String executorArgs = String.join(" ", shellConfig.args());

        // Try to find the executor in the common list or set it as a string
        boolean found = false;
        for (int i = 0; i < commonExecutorsComboBox.getItemCount(); i++) {
            Object item = commonExecutorsComboBox.getItemAt(i);
            String path = item instanceof ShellConfig sc ? sc.executable() : item.toString();
            if (executorPath.equalsIgnoreCase(path)) {
                commonExecutorsComboBox.setSelectedIndex(i);
                found = true;
                break;
            }
        }
        if (!found) {
            commonExecutorsComboBox.setSelectedItem(executorPath);
        }
        executorArgsField.setText(executorArgs);

        logger.trace("Build panel settings loaded/reloaded with details: {}", details);
    }

    public boolean applySettings() {
        // Persist build-related settings to project.
        // Use pendingBuildDetails for build commands if available (from recent BuildAgent run),
        // but always read exclusion patterns from disk (saveCiExclusions() just updated them)
        var diskDetails = project.awaitBuildDetails();
        var baseDetails = pendingBuildDetails != null ? pendingBuildDetails : diskDetails;
        var newBuildLint = buildCleanCommandField.getText();
        var newTestAll = allTestsCommandField.getText();
        var newAfterTaskList = afterTaskListCommandField.getText();

        // Primary language
        var selectedPrimaryLang = (Language) primaryLanguageComboBox.getSelectedItem();

        // Build environment variables map
        var envVars = new HashMap<>(baseDetails.environmentVariables());
        // JAVA_HOME is now managed via project.setJdk() and stored in workspace.properties
        envVars.remove("JAVA_HOME");
        envVars.remove("VIRTUAL_ENV");
        if (selectedPrimaryLang == Languages.PYTHON) {
            envVars.put("VIRTUAL_ENV", ".venv");
        }

        // Always use exclusion patterns from disk - Code Intelligence panel is the source of truth
        var newDetails = new BuildAgent.BuildDetails(
                newBuildLint,
                newTestAll,
                "", // Global testSome is deprecated in UI
                diskDetails.exclusionPatterns(),
                envVars,
                diskDetails.maxBuildAttempts(),
                newAfterTaskList,
                new ArrayList<>(modulesList));

        // Compare against what's currently saved on disk
        var currentDetails = project.awaitBuildDetails();
        if (!newDetails.equals(currentDetails)) {
            project.saveBuildDetails(newDetails);
            logger.debug("Applied Build Details changes.");
        }

        // Clear pending details after save
        pendingBuildDetails = null;

        MainProject.CodeAgentTestScope selectedScope =
                runAllTestsRadio.isSelected() ? IProject.CodeAgentTestScope.ALL : IProject.CodeAgentTestScope.WORKSPACE;
        if (selectedScope != project.getCodeAgentTestScope()) {
            project.setCodeAgentTestScope(selectedScope);
            logger.debug("Applied Code Agent Test Scope: {}", selectedScope);
        }

        var mainProject = project.getMainProject();

        commitTimeout(runTimeoutComboBox, true);
        long runTimeout = lastValidRunTimeout.seconds();
        if (runTimeout != mainProject.getRunCommandTimeoutSeconds()) {
            mainProject.setRunCommandTimeoutSeconds(runTimeout);
            logger.debug("Applied Run Command Timeout: {} seconds", runTimeout);
        }

        commitTimeout(testTimeoutComboBox, false);
        long testTimeout = lastValidTestTimeout.seconds();
        if (testTimeout != mainProject.getTestCommandTimeoutSeconds()) {
            mainProject.setTestCommandTimeoutSeconds(testTimeout);
            logger.debug("Applied Test Command Timeout: {} seconds", testTimeout);
        }

        // JDK Controls (only for Java)
        if (selectedPrimaryLang == Languages.JAVA) {
            if (setJavaHomeCheckbox.isSelected()) {
                String rawPath = jdkSelector.getSelectedJdkPath();
                if (!validateAndApplyJdkOverride(rawPath)) {
                    return false;
                }
            } else {
                project.setJdk(null);
                logger.debug("Removed JDK Home override");
            }
        }

        if (selectedPrimaryLang != null && selectedPrimaryLang != project.computedBuildLanguage()) {
            project.setBuildLanguage(selectedPrimaryLang);
            logger.debug("Applied Primary Language: {}", selectedPrimaryLang);
        }

        // Apply executor configuration
        var currentShellConfig = project.getShellConfig();
        var selectedExecutorObj = commonExecutorsComboBox.getSelectedItem();
        String newExecutorPath = "";
        if (selectedExecutorObj instanceof ShellConfig sc) {
            newExecutorPath = sc.executable();
        } else if (selectedExecutorObj != null) {
            newExecutorPath = selectedExecutorObj.toString().trim();
        }
        String newExecutorArgs = executorArgsField.getText().trim();

        List<String> argsList = Arrays.asList(newExecutorArgs.split("\\s+"));
        var newShellConfig = new ShellConfig(newExecutorPath, argsList);

        if (!Objects.equals(currentShellConfig, newShellConfig)) {
            project.setShellConfig(newShellConfig);
            logger.debug("Applied Shell Configuration: {}", newShellConfig);
        }

        return true;
    }

    /**
     * Validation result for JDK override.
     * @param jdkToPersist path or sentinel to save if valid
     * @param errorMessage error message if invalid, null if valid
     */
    record JdkOverrideValidation(@Nullable String jdkToPersist, @Nullable String errorMessage) {
        boolean isValid() {
            return errorMessage == null;
        }
    }

    /**
     * Validation result for timeout combo values.
     * @param seconds the parsed seconds (-1 for no timeout)
     * @param errorMessage error message if invalid, null if valid
     */
    record TimeoutValidation(long seconds, @Nullable String errorMessage) {
        boolean isValid() {
            return errorMessage == null;
        }
    }

    static TimeoutValidation validateTimeout(@Nullable Object selectedItem) {
        if (selectedItem instanceof TimeoutItem item) {
            return new TimeoutValidation(item.seconds(), null);
        }

        if (selectedItem instanceof String s) {
            String clean = s.trim();
            if (clean.equalsIgnoreCase("no timeout") || clean.isEmpty()) {
                return new TimeoutValidation(-1, null);
            }
            try {
                long val = Long.parseLong(clean);
                if (val < 30 && val != -1) {
                    return new TimeoutValidation(val, "Timeout must be at least 30 seconds.");
                }
                return new TimeoutValidation(val, null);
            } catch (NumberFormatException e) {
                return new TimeoutValidation(0, "Please enter a valid numeric value for seconds.");
            }
        }

        return new TimeoutValidation(Environment.DEFAULT_TIMEOUT.toSeconds(), null);
    }

    static JdkOverrideValidation validateJdkOverride(@Nullable String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return new JdkOverrideValidation(null, "Please select a valid JDK path.");
        }

        if (EnvironmentJava.JAVA_HOME_SENTINEL.equals(rawPath)) {
            return new JdkOverrideValidation(EnvironmentJava.JAVA_HOME_SENTINEL, null);
        }

        String normalized = JdkSelector.normalizeJdkPath(rawPath);
        if (normalized.isBlank()) {
            return new JdkOverrideValidation(null, "Please select a valid JDK path.");
        }

        try {
            Path path = Path.of(normalized);
            String error = JdkSelector.validateJdkPath(path);
            if (error != null) {
                return new JdkOverrideValidation(null, error);
            }
            return new JdkOverrideValidation(normalized, null);
        } catch (InvalidPathException e) {
            return new JdkOverrideValidation(null, "The provided path is invalid: " + e.getMessage());
        }
    }

    boolean validateAndApplyJdkOverride(@Nullable String rawPath) {
        var result = validateJdkOverride(rawPath);
        if (!result.isValid()) {
            JOptionPane.showMessageDialog(this, result.errorMessage(), "Invalid JDK Path", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        String toPersist = result.jdkToPersist();
        project.setJdk(toPersist);
        if (EnvironmentJava.JAVA_HOME_SENTINEL.equals(toPersist)) {
            logger.debug("Applied JDK Home sentinel: {}", toPersist);
        } else {
            logger.debug("Applied JDK Home: {}", toPersist);
        }
        return true;
    }

    public void showBuildBanner() {
        bannerPanel.setVisible(true);
    }

    private void populateJdkControlsFromProject() {
        String effectiveJdk = project.getJdk();
        boolean hasOverride = project.hasJdkOverride();

        setJavaHomeCheckbox.setSelected(hasOverride);
        jdkSelector.setEnabled(hasOverride);
        // Show the effective JDK (detected or explicit) in the selector
        jdkSelector.loadJdksAsync(effectiveJdk);
    }

    private void updateJdkControlsVisibility(@Nullable Language selected) {
        boolean isJava = selected == Languages.JAVA;
        setJavaHomeCheckbox.setVisible(isJava);
        jdkSelector.setVisible(isJava);
    }

    private Map<String, String> computeEnvFromUi() {
        var env = new HashMap<String, String>();
        var selected = (Language) primaryLanguageComboBox.getSelectedItem();
        if (selected == Languages.JAVA) {
            if (setJavaHomeCheckbox.isSelected()) {
                String sel = jdkSelector.getSelectedJdkPath();
                if (sel != null && !sel.isBlank()) {
                    env.put("JAVA_HOME", JdkSelector.normalizeJdkPath(sel));
                }
            } else {
                // If checkbox is NOT selected, we explicitly pass the sentinel to prevent
                // BuildVerifier from falling back to project.getJdk()
                env.put("JAVA_HOME", EnvironmentJava.JAVA_HOME_SENTINEL);
            }
        }
        if (selected == Languages.PYTHON) {
            env.put("VIRTUAL_ENV", ".venv");
        }
        return env;
    }

    private void populatePrimaryLanguageComboBox() {
        var detected = findLanguagesInProject();
        var configured = project.computedBuildLanguage();
        if (!detected.contains(configured)) {
            detected.add(configured);
        }
        // Sort by display name
        detected.sort(Comparator.comparing(Language::name));
        primaryLanguageComboBox.setModel(new DefaultComboBoxModel<>(detected.toArray(Language[]::new)));
    }

    private List<Language> findLanguagesInProject() {
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

    private void initializeExecutorUI() {
        // Set up tooltips
        executorArgsField.setToolTipText("Arguments to pass to executor (default: " + DEFAULT_EXECUTOR_ARGS + ")");
        executorArgsField.setText(DEFAULT_EXECUTOR_ARGS); // Set default value

        // Populate common executors dropdown
        var commonExecutors = ShellConfig.getCommonExecutors();
        commonExecutorsComboBox.setModel(new DefaultComboBoxModel<>(commonExecutors));
        commonExecutorsComboBox.setEditable(true);
        commonExecutorsComboBox.setToolTipText("Path to custom command executor (shell, interpreter, etc.)");

        commonExecutorsComboBox.setEditor(new BasicComboBoxEditor() {
            @Override
            public void setItem(Object anObject) {
                if (anObject instanceof ShellConfig sc) {
                    super.setItem(sc.executable());
                } else {
                    super.setItem(anObject);
                }
            }
        });

        commonExecutorsComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                if (value instanceof ShellConfig sc) {
                    value = sc.executable();
                }
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        });

        commonExecutorsComboBox.addActionListener(e -> {
            var selected = commonExecutorsComboBox.getSelectedItem();
            if (selected instanceof ShellConfig sc) {
                executorArgsField.setText(String.join(" ", sc.args()));
            } else if (selected instanceof String selectedPath) {
                for (int i = 0; i < commonExecutorsComboBox.getItemCount(); i++) {
                    Object item = commonExecutorsComboBox.getItemAt(i);
                    if (item instanceof ShellConfig sc && sc.executable().equals(selectedPath)) {
                        executorArgsField.setText(String.join(" ", sc.args()));
                        break;
                    }
                }
            }
        });

        // pre-select the system default if present
        for (int i = 0; i < commonExecutors.length; i++) {
            if (commonExecutors[i].executable().equalsIgnoreCase(DEFAULT_EXECUTOR_PATH)) {
                commonExecutorsComboBox.setSelectedIndex(i);
                break;
            }
        }

        // Reset button action
        resetExecutorButton.addActionListener(e -> resetExecutor());

        // Test executor button action
        testExecutorButton.addActionListener(e -> testExecutor());
    }

    private void resetExecutor() {
        var defaultConfig = ShellConfig.basic();
        executorArgsField.setText(String.join(" ", defaultConfig.args()));
        project.setShellConfig(null);

        for (int i = 0; i < commonExecutorsComboBox.getItemCount(); i++) {
            Object item = commonExecutorsComboBox.getItemAt(i);
            String path = item instanceof ShellConfig sc ? sc.executable() : item.toString();
            if (defaultConfig.executable().equalsIgnoreCase(path)) {
                commonExecutorsComboBox.setSelectedIndex(i);
                break;
            }
        }
    }

    private void testExecutor() {
        var selectedItem = commonExecutorsComboBox.getSelectedItem();
        String executorPath =
                selectedItem instanceof ShellConfig sc ? sc.executable() : Objects.toString(selectedItem, "");
        String executorArgs = executorArgsField.getText().trim();

        if (executorPath.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Please specify an executor path first.",
                    "No Executor Specified",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (executorArgs.isEmpty()) {
            executorArgs = DEFAULT_EXECUTOR_ARGS;
        }

        testExecutorButton.setEnabled(false);
        testExecutorButton.setText("Testing...");

        final String finalExecutorPath = executorPath;
        final String finalExecutorArgs = executorArgs;

        SwingWorker<ShellConfig.ValidationResult, Void> worker = new SwingWorker<>() {
            @Override
            protected ShellConfig.ValidationResult doInBackground() {
                String[] argsArray = finalExecutorArgs.split("\\s+");
                var config = new ShellConfig(finalExecutorPath, Arrays.asList(argsArray));
                return config.validate();
            }

            @Override
            protected void done() {
                testExecutorButton.setEnabled(true);
                testExecutorButton.setText("Test");

                try {
                    var result = get();
                    if (result.success()) {
                        JOptionPane.showMessageDialog(
                                SettingsProjectBuildPanel.this,
                                result.message(),
                                "Executor Test Successful",
                                JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(
                                SettingsProjectBuildPanel.this,
                                result.message(),
                                "Test Failed",
                                JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        worker.execute();
    }

    private void showBuildAgentCancelledNotification() {
        SwingUtilities.invokeLater(() -> {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Build Inference Agent cancelled.");
            JOptionPane.showMessageDialog(
                    SettingsProjectBuildPanel.this,
                    "Build Inference Agent cancelled.",
                    "Build Cancelled",
                    JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private void selectTimeoutInCombo(JComboBox<TimeoutItem> combo, long seconds) {
        TimeoutItem itemToSelect;
        if (seconds == -1) {
            itemToSelect = TimeoutItem.NO_TIMEOUT;
        } else {
            itemToSelect = null;
            for (int i = 0; i < combo.getItemCount(); i++) {
                var item = combo.getItemAt(i);
                if (item.seconds() == seconds) {
                    itemToSelect = item;
                    break;
                }
            }
            if (itemToSelect == null) {
                itemToSelect = new TimeoutItem(seconds);
            }
        }

        combo.setSelectedItem(itemToSelect);
        if (combo == runTimeoutComboBox) {
            lastValidRunTimeout = itemToSelect;
        } else if (combo == testTimeoutComboBox) {
            lastValidTestTimeout = itemToSelect;
        }
    }

    private void setupTimeoutValidation(JComboBox<TimeoutItem> combo, boolean isRunTimeout) {
        Component editor = combo.getEditor().getEditorComponent();
        if (editor instanceof JTextField textField) {
            textField.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    commitTimeout(combo, isRunTimeout);
                }
            });
            textField.addActionListener(e -> commitTimeout(combo, isRunTimeout));
        }
    }

    private void commitTimeout(JComboBox<TimeoutItem> combo, boolean isRunTimeout) {
        Object item = combo.getEditor().getItem();
        var validation = validateTimeout(item);

        if (validation.isValid()) {
            long seconds = validation.seconds();
            TimeoutItem validItem = (seconds == -1) ? TimeoutItem.NO_TIMEOUT : new TimeoutItem(seconds);
            combo.setSelectedItem(validItem);
            if (isRunTimeout) {
                lastValidRunTimeout = validItem;
            } else {
                lastValidTestTimeout = validItem;
            }
        } else {
            JOptionPane.showMessageDialog(
                    this, validation.errorMessage(), "Invalid Timeout", JOptionPane.ERROR_MESSAGE);
            // Revert to last valid
            combo.setSelectedItem(isRunTimeout ? lastValidRunTimeout : lastValidTestTimeout);
        }
    }

    private void addModule() {
        var entry = new BuildAgent.ModuleBuildEntry("", "", "", "", "");
        var dialog = new ModuleEditDialog(parentDialog, entry);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            modulesList.add(dialog.getResult());
            modulesTableModel.fireTableDataChanged();
        }
    }

    private void editModule() {
        int row = modulesTable.getSelectedRow();
        if (row == -1) return;
        var entry = modulesList.get(row);
        var dialog = new ModuleEditDialog(parentDialog, entry);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            modulesList.set(row, dialog.getResult());
            modulesTableModel.fireTableDataChanged();
        }
    }

    private void removeModule() {
        int row = modulesTable.getSelectedRow();
        if (row != -1) {
            modulesList.remove(row);
            modulesTableModel.fireTableDataChanged();
        }
    }

    private void moveModule(int delta) {
        int row = modulesTable.getSelectedRow();
        if (row == -1) return;
        int next = row + delta;
        if (next >= 0 && next < modulesList.size()) {
            Collections.swap(modulesList, row, next);
            modulesTableModel.fireTableDataChanged();
            modulesTable.setRowSelectionInterval(next, next);
        }
    }

    private class ModulesTableModel extends AbstractTableModel {
        private final String[] columns = {"Alias", "Path"};

        @Override
        public int getRowCount() {
            return modulesList.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int col) {
            return columns[col];
        }

        @Override
        public Class<?> getColumnClass(int col) {
            return String.class;
        }

        @Override
        public Object getValueAt(int row, int col) {
            var m = modulesList.get(row);
            return switch (col) {
                case 0 -> m.alias();
                case 1 -> m.relativePath();
                default -> "";
            };
        }
    }

    private class ModuleEditDialog extends BaseThemedDialog {
        private final JTextField aliasField = new JTextField();
        private final JTextField pathField = new JTextField();
        private final JTextField buildCmdField = new JTextField();
        private final JTextField testAllField = new JTextField();
        private final JTextField testSomeField = new JTextField();
        private boolean saved = false;

        public ModuleEditDialog(Window owner, BuildAgent.ModuleBuildEntry entry) {
            super(owner, "Edit Module");
            setSize(500, 320);
            setLocationRelativeTo(owner);
            setModal(true);

            aliasField.setText(entry.alias());
            pathField.setText(entry.relativePath());
            buildCmdField.setText(entry.buildLintCommand());
            testAllField.setText(entry.testAllCommand());
            testSomeField.setText(entry.testSomeCommand());

            var p = getContentRoot();
            p.setLayout(new GridBagLayout());
            var gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.fill = GridBagConstraints.HORIZONTAL;

            int row = 0;
            addField(p, "Alias:", aliasField, gbc, row++);
            addField(p, "Relative Path:", pathField, gbc, row++);
            addField(p, "Build/Lint Command:", buildCmdField, gbc, row++);
            addField(p, "Test All Command:", testAllField, gbc, row++);
            addField(p, "Test Some Command:", testSomeField, gbc, row++);

            var testSomeNote = new JLabel(
                    "<html>Mustache variables {{#files}}, {{#classes}}, or {{#fqclasses}} will be interpolated with filenames, class names, or fully-qualified class names, respectively</html>");
            testSomeNote.setFont(testSomeNote
                    .getFont()
                    .deriveFont(Font.ITALIC, testSomeNote.getFont().getSize() * 0.9f));
            gbc.gridx = 1;
            gbc.gridy = row++;
            gbc.insets = new Insets(0, 5, 8, 5);
            p.add(testSomeNote, gbc);
            gbc.insets = new Insets(5, 5, 5, 5); // Reset

            var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            var verify = new MaterialButton("Verify");
            verify.addActionListener(e -> verifyModule());
            var ok = new MaterialButton("Ok");
            SwingUtil.applyPrimaryButtonStyle(ok);
            ok.addActionListener(e -> {
                saved = true;
                dispose();
            });
            var cancel = new MaterialButton("Cancel");
            cancel.addActionListener(e -> dispose());
            buttons.add(verify);
            buttons.add(ok);
            buttons.add(cancel);

            gbc.gridy = row;
            gbc.gridx = 0;
            gbc.gridwidth = 2;
            gbc.weighty = 1.0;
            gbc.anchor = GridBagConstraints.SOUTH;
            p.add(buttons, gbc);
        }

        private void verifyModule() {
            String alias = aliasField.getText().trim();
            String buildLint = buildCmdField.getText().trim();
            String testAll = testAllField.getText().trim();

            runVerificationUI(this, "Verifying Module: " + alias, publish -> {
                var envVars = SettingsProjectBuildPanel.this.computeEnvFromUi();

                if (!buildLint.isEmpty()) {
                    publish.accept("--- Verifying Build/Lint Command ---\n");
                    publish.accept("$ " + buildLint + "\n");
                    var result = BuildVerifier.verifyStreaming(project, buildLint, envVars, line -> publish.accept(line + "\n"));
                    if (result.success()) {
                        publish.accept("\nSUCCESS: Build/Lint command completed successfully.\n\n");
                    } else {
                        publish.accept("\nERROR: Build/Lint command failed.\n");
                        publish.accept(result.output() + "\n");
                        return "Build/Lint command failed.";
                    }
                }

                if (Thread.interrupted()) return "Cancelled";

                if (!testAll.isEmpty()) {
                    publish.accept("--- Verifying Test All Command ---\n");
                    publish.accept("$ " + testAll + "\n");
                    var result = BuildVerifier.verifyStreaming(project, testAll, envVars, line -> publish.accept(line + "\n"));
                    if (result.success()) {
                        publish.accept("\nSUCCESS: Test All command completed successfully.\n\n");
                    } else {
                        publish.accept("\nERROR: Test All command failed.\n");
                        publish.accept(result.output() + "\n");
                        return "Test All command failed.";
                    }
                }

                return "Verification successful!";
            });
        }

        private void addField(JPanel p, String label, JTextField field, GridBagConstraints gbc, int row) {
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0.0;
            p.add(new JLabel(label), gbc);
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            p.add(field, gbc);
        }

        public boolean isSaved() {
            return saved;
        }

        public BuildAgent.ModuleBuildEntry getResult() {
            return new BuildAgent.ModuleBuildEntry(
                    aliasField.getText().trim(),
                    pathField.getText().trim(),
                    buildCmdField.getText().trim(),
                    testAllField.getText().trim(),
                    testSomeField.getText().trim());
        }
    }
}
