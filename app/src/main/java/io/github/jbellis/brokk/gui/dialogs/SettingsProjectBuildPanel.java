package io.github.jbellis.brokk.gui.dialogs;

import com.google.common.io.Files;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.ExecutorConfig;
import io.github.jbellis.brokk.util.ExecutorValidator;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.BorderFactory;
import javax.swing.SwingWorker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Extracted Build settings panel formerly residing inside SettingsProjectPanel.
 * Responsible for presenting and persisting build/lint/test executor settings and interacting with BuildAgent.
 */
public class SettingsProjectBuildPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(SettingsProjectBuildPanel.class);
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
    private JTextField someTestsCommandField = new JTextField();

    private JRadioButton runAllTestsRadio = new JRadioButton(IProject.CodeAgentTestScope.ALL.toString());
    private JRadioButton runTestsInWorkspaceRadio = new JRadioButton(IProject.CodeAgentTestScope.WORKSPACE.toString());
    private JSpinner buildTimeoutSpinner =
            new JSpinner(new SpinnerNumberModel((int) Environment.DEFAULT_TIMEOUT.toSeconds(), 1, 10800, 1));
    private JProgressBar buildProgressBar = new JProgressBar();
    private MaterialButton inferBuildDetailsButton = new MaterialButton("Infer Build Details");
    private JCheckBox setJavaHomeCheckbox = new JCheckBox("Set JAVA_HOME to");
    private JdkSelector jdkSelector = new JdkSelector();
    private JComboBox<Language> primaryLanguageComboBox = new JComboBox<>();

    // Executor configuration UI
    private JTextField executorPathField = new JTextField(20);
    private JTextField executorArgsField = new JTextField(20);
    private MaterialButton testExecutorButton = new MaterialButton("Test");
    private MaterialButton resetExecutorButton = new MaterialButton("Reset");
    private JComboBox<String> commonExecutorsComboBox = new JComboBox<>();

    // System-default executor
    private static final boolean IS_WINDOWS = Environment.isWindows();
    private static final String DEFAULT_EXECUTOR_PATH = IS_WINDOWS ? "powershell.exe" : "/bin/sh";
    private static final String DEFAULT_EXECUTOR_ARGS = IS_WINDOWS ? "-Command" : "-c";

    @Nullable
    private Future<?> manualInferBuildTaskFuture;

    private final JPanel bannerPanel;

    public SettingsProjectBuildPanel(
            Chrome chrome, SettingsDialog parentDialog, JButton okButton, JButton cancelButton, JButton applyButton) {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        this.chrome = chrome;
        this.parentDialog = parentDialog;
        this.okButtonParent = okButton;
        this.cancelButtonParent = cancelButton;
        this.applyButtonParent = applyButton;
        this.project = chrome.getProject();
        this.bannerPanel = createBanner();

        initComponents();
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
        this.add(bannerPanel, gbc);
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
        updateJdkControlsVisibility(project.getBuildLanguage());
        setJavaHomeCheckbox.addActionListener(e -> jdkSelector.setEnabled(setJavaHomeCheckbox.isSelected()));

        gbc.gridy = row++;
        this.add(languagePanel, gbc);

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

        // Test Some Command
        buildGbc.gridx = 0;
        buildGbc.gridy = buildRow;
        buildGbc.weightx = 0.0;
        buildConfigPanel.add(new JLabel("Test Some Command:"), buildGbc);
        buildGbc.gridx = 1;
        buildGbc.gridy = buildRow++;
        buildGbc.weightx = 1.0;
        buildConfigPanel.add(someTestsCommandField, buildGbc);
        var testSomeInfo = new JLabel(
                "<html>Mustache variables {{#files}}, {{#classes}}, or {{#fqclasses}} will be interpolated with filenames, class names, or fully-qualified class names, respectively</html>");
        testSomeInfo.setFont(testSomeInfo
                .getFont()
                .deriveFont(Font.ITALIC, testSomeInfo.getFont().getSize() * 0.9f));
        buildGbc.gridx = 1;
        buildGbc.gridy = buildRow++;
        buildGbc.insets = new Insets(0, 2, 8, 2);
        buildConfigPanel.add(testSomeInfo, buildGbc);
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
        buildConfigPanel.add(buildTimeoutSpinner, buildGbc);

        // Infer/Verify buttons
        var buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        inferBuildDetailsButton.setActionCommand(ACTION_INFER);
        buttonsPanel.add(inferBuildDetailsButton);
        var verifyBuildButton = new MaterialButton("Verify Configuration");
        verifyBuildButton.addActionListener(e -> verifyBuildConfiguration());
        buttonsPanel.add(verifyBuildButton);
        buildGbc.gridx = 1;
        buildGbc.gridy = buildRow++;
        buildGbc.weightx = 0.0;
        buildGbc.weighty = 0.0;
        buildGbc.fill = GridBagConstraints.NONE;
        buildGbc.anchor = GridBagConstraints.WEST;
        buildConfigPanel.add(buttonsPanel, buildGbc);

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
        this.add(buildConfigPanel, gbc);

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
        shellGbc.gridx = 1;
        shellGbc.gridy = shellRow++;
        shellGbc.weightx = 1.0;
        shellGbc.fill = GridBagConstraints.HORIZONTAL;
        shellConfigPanel.add(executorSelectPanel, shellGbc);

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
        this.add(shellConfigPanel, gbc);

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
        this.add(Box.createVerticalGlue(), gbc);

        // Populate initial values
        populatePrimaryLanguageComboBox();
        var selectedLang = project.getBuildLanguage();
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
                                    updateBuildDetailsFieldsFromAgent(detailsResult);
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

    private void verifyBuildConfiguration() {
        var verifyDialog = new JDialog(parentDialog, "Verifying Build Configuration", true);
        verifyDialog.setSize(600, 400);
        verifyDialog.setLocationRelativeTo(parentDialog);

        var outputArea = new JTextArea();
        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        var scrollPane = new JScrollPane(outputArea);

        var progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);

        var closeButton = new MaterialButton("Close");
        closeButton.setEnabled(false);
        closeButton.addActionListener(e -> verifyDialog.dispose());

        var bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.add(progressBar, BorderLayout.CENTER);
        bottomPanel.add(closeButton, BorderLayout.EAST);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        verifyDialog.setLayout(new BorderLayout(5, 5));
        verifyDialog.add(scrollPane, BorderLayout.CENTER);
        verifyDialog.add(bottomPanel, BorderLayout.SOUTH);

        SwingWorker<String, String> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws InterruptedException {
                var root = project.getRoot();

                // Step 1: Build/Lint command
                String buildCmd = buildCleanCommandField.getText().trim();
                if (!buildCmd.isEmpty()) {
                    publish("--- Verifying Build/Lint Command ---\n");
                    publish("$ " + buildCmd + "\n");
                    try {
                        var execCfg = ExecutorConfig.fromProject(project);
                        var envVars = computeEnvFromUi();
                        Environment.instance.runShellCommand(
                                buildCmd,
                                root,
                                line -> publish(line + "\n"),
                                Environment.DEFAULT_TIMEOUT,
                                execCfg,
                                envVars);
                        publish("\nSUCCESS: Build/Lint command completed successfully.\n\n");
                    } catch (Environment.SubprocessException e) {
                        publish("\nERROR: Build/Lint command failed.\n");
                        publish(e.getMessage() + "\n");
                        publish(e.getOutput() + "\n");
                        return "Build/Lint command failed.";
                    }
                } else {
                    publish("--- Skipping empty Build/Lint Command ---\n\n");
                }

                // Step 2: Test All command
                String testAllCmd = allTestsCommandField.getText().trim();
                if (!testAllCmd.isEmpty()) {
                    publish("--- Verifying Test All Command ---\n");
                    publish("$ " + testAllCmd + "\n");
                    try {
                        var execCfg = ExecutorConfig.fromProject(project);
                        var envVars = computeEnvFromUi();
                        Environment.instance.runShellCommand(
                                testAllCmd,
                                root,
                                line -> publish(line + "\n"),
                                Environment.DEFAULT_TIMEOUT,
                                execCfg,
                                envVars);
                        publish("\nSUCCESS: Test All command completed successfully.\n\n");
                    } catch (Environment.SubprocessException e) {
                        publish("\nERROR: Test All command failed.\n");
                        publish(e.getMessage() + "\n");
                        publish(e.getOutput() + "\n");
                        return "Test All command failed.";
                    }
                } else {
                    publish("--- Skipping empty Test All Command ---\n\n");
                }

                // Step 3: Test Some command
                String testSomeTemplate = someTestsCommandField.getText().trim();
                if (!testSomeTemplate.isEmpty()) {
                    publish("--- Verifying Test Some Command Template ---\n");
                    publish("Template: " + testSomeTemplate + "\n");
                    String listKey;
                    List<String> items = List.of("placeholder");
                    if (testSomeTemplate.contains("{{#files}}")) {
                        listKey = "files";
                        items = List.of("src/test/java/com/example/Placeholder.java");
                    } else if (testSomeTemplate.contains("{{#fqclasses}}")) {
                        listKey = "fqclasses";
                        items = List.of("com.example.Placeholder");
                    } else if (testSomeTemplate.contains("{{#classes}}")) {
                        listKey = "classes";
                        items = List.of("Placeholder");
                    } else {
                        publish(
                                "\nWARNING: 'Test Some' command does not contain {{#files}}, {{#classes}}, or {{#fqclasses}}.\n");
                        publish("Cannot perform mock interpolation. Will run the command as-is.\n");
                        listKey = null;
                    }

                    String interpolatedCmd;
                    if (listKey != null) {
                        interpolatedCmd = BuildAgent.interpolateMustacheTemplate(testSomeTemplate, items, listKey);
                        publish("Interpolated command with placeholder: " + interpolatedCmd + "\n");
                    } else {
                        interpolatedCmd = testSomeTemplate;
                    }

                    publish("$ " + interpolatedCmd + "\n");
                    try {
                        var execCfg = ExecutorConfig.fromProject(project);
                        var envVars = computeEnvFromUi();
                        Environment.instance.runShellCommand(
                                interpolatedCmd,
                                root,
                                line -> publish(line + "\n"),
                                Environment.DEFAULT_TIMEOUT,
                                execCfg,
                                envVars);
                        publish(
                                "\nSUCCESS: 'Test Some' command executed without errors (this is unexpected for a placeholder test).\n\n");
                    } catch (Environment.FailureException e) {
                        publish(
                                "\nSUCCESS: 'Test Some' command executed and failed as expected for a placeholder test.\n");
                        publish("This confirms the command and template syntax are valid.\n\n");
                        // This is the expected success path.
                    } catch (Environment.SubprocessException e) {
                        publish("\nERROR: 'Test Some' command failed to execute.\n");
                        publish("This may indicate an invalid executable or a syntax error in the command.\n");
                        publish(e.getMessage() + "\n");
                        publish(e.getOutput() + "\n");
                        return "'Test Some' command is invalid.";
                    }
                } else {
                    publish("--- Skipping empty Test Some Command ---\n\n");
                }

                return "Verification successful!";
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
                closeButton.setEnabled(true);
                try {
                    String result = get();
                    outputArea.append("\n--- VERIFICATION COMPLETE ---\n");
                    outputArea.append(result);
                } catch (Exception e) {
                    logger.error("Error during build verification", e);
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        outputArea.append("\n--- VERIFICATION CANCELLED ---\n");
                    } else {
                        outputArea.append("\n--- An unexpected error occurred during verification ---\n");
                        outputArea.append(e.toString());
                    }
                }
                outputArea.setCaretPosition(outputArea.getDocument().getLength());
            }
        };
        worker.execute();
        verifyDialog.setVisible(true); // This blocks until dialog is closed
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
                        proj, cm.getLlm(cm.getService().getScanModel(), "Infer build details"), cm.getToolRegistry());
                var newBuildDetails = agent.execute();

                if (Objects.equals(newBuildDetails, BuildAgent.BuildDetails.EMPTY)) {
                    logger.warn("Build Agent returned null or empty details, considering it an error.");
                    boolean isCancellation = ACTION_CANCEL.equals(inferBuildDetailsButton.getActionCommand());

                    SwingUtilities.invokeLater(() -> {
                        if (isCancellation) {
                            logger.info("Build Agent execution cancelled by user");
                            chrome.showNotification(
                                    IConsoleIO.NotificationRole.INFO, "Build Inference Agent cancelled.");
                            JOptionPane.showMessageDialog(
                                    SettingsProjectBuildPanel.this,
                                    "Build Inference Agent cancelled.",
                                    "Build Cancelled",
                                    JOptionPane.INFORMATION_MESSAGE);
                        } else {
                            String errorMessage =
                                    "Build Agent failed to determine build details. Please check agent logs.";
                            chrome.toolError(errorMessage);
                            JOptionPane.showMessageDialog(
                                    SettingsProjectBuildPanel.this,
                                    errorMessage,
                                    "Build Agent Error",
                                    JOptionPane.ERROR_MESSAGE);
                        }
                    });
                } else {
                    SwingUtilities.invokeLater(() -> {
                        updateBuildDetailsFieldsFromAgent(newBuildDetails);
                        chrome.showNotification(
                                IConsoleIO.NotificationRole.INFO, "Build Agent finished. Review and apply settings.");
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
        buildProgressBar.setVisible(!enabled);

        Stream.of(
                        buildCleanCommandField,
                        allTestsCommandField,
                        someTestsCommandField,
                        runAllTestsRadio,
                        runTestsInWorkspaceRadio,
                        // Parent dialog buttons
                        okButtonParent,
                        cancelButtonParent,
                        applyButtonParent)
                .filter(Objects::nonNull)
                .forEach(control -> control.setEnabled(enabled));
    }

    private void updateBuildDetailsFieldsFromAgent(BuildAgent.BuildDetails details) {
        SwingUtilities.invokeLater(() -> {
            // Update this panel's fields
            buildCleanCommandField.setText(details.buildLintCommand());
            allTestsCommandField.setText(details.testAllCommand());
            someTestsCommandField.setText(details.testSomeCommand());

            // Also refresh the CI exclusions list model in the parent SettingsProjectPanel
            try {
                var spp = parentDialog.getProjectPanel();
                spp.updateExcludedDirectories(details.excludedDirectories());
            } catch (Exception ex) {
                logger.warn("Failed to update CI exclusions list from agent details: {}", ex.getMessage(), ex);
            }

            logger.trace("UI fields updated with new BuildDetails from agent: {}", details);
        });
    }

    public void loadBuildPanelSettings() {
        BuildAgent.BuildDetails details;
        try {
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

        var selectedLang = project.getBuildLanguage();
        primaryLanguageComboBox.setSelectedItem(selectedLang);
        updateJdkControlsVisibility(selectedLang);
        if (selectedLang == Languages.JAVA) {
            populateJdkControlsFromProject();
        }

        // Load executor configuration
        String executorPath = project.getCommandExecutor();
        String executorArgs = project.getExecutorArgs();

        executorPathField.setText(executorPath != null ? executorPath : DEFAULT_EXECUTOR_PATH);
        executorArgsField.setText(executorArgs != null ? executorArgs : DEFAULT_EXECUTOR_ARGS);

        logger.trace("Build panel settings loaded/reloaded with details: {}", details);
    }

    public boolean applySettings() {
        // Persist build-related settings to project.
        var currentDetails = project.loadBuildDetails();
        var newBuildLint = buildCleanCommandField.getText();
        var newTestAll = allTestsCommandField.getText();
        var newTestSome = someTestsCommandField.getText();

        // Primary language
        var selectedPrimaryLang = (Language) primaryLanguageComboBox.getSelectedItem();

        // Build environment variables map
        var envVars = new HashMap<>(currentDetails.environmentVariables());
        envVars.remove("JAVA_HOME");
        envVars.remove("VIRTUAL_ENV");
        if (selectedPrimaryLang == Languages.JAVA) {
            if (setJavaHomeCheckbox.isSelected()) {
                var selPath = jdkSelector.getSelectedJdkPath();
                if (selPath != null && !selPath.isBlank()) {
                    envVars.put("JAVA_HOME", selPath);
                }
            }
        } else if (selectedPrimaryLang == Languages.PYTHON) {
            envVars.put("VIRTUAL_ENV", ".venv");
        }

        var newDetails = new BuildAgent.BuildDetails(
                newBuildLint, newTestAll, newTestSome, currentDetails.excludedDirectories(), envVars);
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

        // JDK Controls (only for Java)
        if (selectedPrimaryLang != null && selectedPrimaryLang != project.getBuildLanguage()) {
            project.setBuildLanguage(selectedPrimaryLang);
            logger.debug("Applied Primary Language: {}", selectedPrimaryLang);
        }

        // Apply executor configuration
        String currentExecutorPath = project.getCommandExecutor();
        String currentExecutorArgs = project.getExecutorArgs();
        String newExecutorPath = executorPathField.getText().trim();
        String newExecutorArgs = executorArgsField.getText().trim();

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

        return true;
    }

    public void showBuildBanner() {
        bannerPanel.setVisible(true);
    }

    private void populateJdkControlsFromProject() {
        BuildAgent.BuildDetails details = project.awaitBuildDetails();
        var env = details.environmentVariables();
        String desired = env.get("JAVA_HOME");

        boolean useCustomJdk = desired != null && !desired.isBlank();
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

    private Map<String, String> computeEnvFromUi() {
        var env = new HashMap<String, String>();
        var selected = (Language) primaryLanguageComboBox.getSelectedItem();
        if (selected == Languages.JAVA && setJavaHomeCheckbox.isSelected()) {
            String sel = jdkSelector.getSelectedJdkPath();
            if (sel != null && !sel.isBlank()) {
                env.put("JAVA_HOME", sel);
            }
        }
        if (selected == Languages.PYTHON) {
            env.put("VIRTUAL_ENV", ".venv");
        }
        return env;
    }

    private void populatePrimaryLanguageComboBox() {
        var detected = findLanguagesInProject();
        var configured = project.getBuildLanguage();
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
        executorPathField.setText(DEFAULT_EXECUTOR_PATH);
        executorArgsField.setText(DEFAULT_EXECUTOR_ARGS);

        project.setCommandExecutor(null);
        project.setExecutorArgs(null);

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

        if (executorArgs.isEmpty()) {
            executorArgs = DEFAULT_EXECUTOR_ARGS;
        }

        testExecutorButton.setEnabled(false);
        testExecutorButton.setText("Testing...");

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
                                SettingsProjectBuildPanel.this,
                                result.message() + "\n\n" + sandboxInfo,
                                "Executor Test Successful",
                                JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(
                                SettingsProjectBuildPanel.this,
                                result.message(),
                                "Test Failed",
                                JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception ex) {
                    logger.error("Error during executor test", ex);
                    JOptionPane.showMessageDialog(
                            SettingsProjectBuildPanel.this,
                            "Test failed with error: " + ex.getMessage(),
                            "Executor Test Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }
}
