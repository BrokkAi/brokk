package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.github.BackgroundGitHubAuth;
import io.github.jbellis.brokk.github.DeviceFlowModels;
import io.github.jbellis.brokk.github.GitHubAuthConfig;
import io.github.jbellis.brokk.github.GitHubDeviceFlowService;
import io.github.jbellis.brokk.util.Environment;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class GitHubAuthDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(GitHubAuthDialog.class);

    public enum AuthStatus {
        STARTING("Preparing GitHub authentication..."),
        CODE_RECEIVED("Waiting for authorization..."),
        POLLING("Checking authorization status..."),
        SUCCESS("Successfully connected to GitHub!"),
        CANCELLED("Authentication cancelled"),
        ERROR("Authentication failed");

        private final String message;

        AuthStatus(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    @FunctionalInterface
    public interface DialogCompletionCallback {
        void onDialogComplete(boolean cancelled);
    }

    private final JLabel statusLabel;
    private final JLabel userCodeLabel;
    private final JLabel instructionsLabel;
    private final JButton copyAndOpenButton;
    private final JButton continueBackgroundButton;
    private final JButton cancelButton;
    private final JProgressBar progressBar;

    private @Nullable DialogCompletionCallback completionCallback;
    private GitHubDeviceFlowService deviceFlowService;
    private @Nullable CompletableFuture<Void> authenticationFuture;
    private @Nullable DeviceFlowModels.DeviceCodeResponse currentDeviceCodeResponse;
    private final IContextManager contextManager;
    private final ScheduledExecutorService scheduledExecutor;

    public GitHubAuthDialog(Window parent, IContextManager contextManager) {
        this(parent, contextManager, GitHubAuthConfig.getClientId());
    }

    public GitHubAuthDialog(Window parent, IContextManager contextManager, String clientId) {
        super(parent, "Connect to GitHub", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        logger.info("Initializing GitHub Auth Dialog with client_id: {}", clientId);

        this.contextManager = contextManager;
        this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GitHubAuth-Scheduler");
            t.setDaemon(true);
            return t;
        });
        this.statusLabel = new JLabel();
        this.userCodeLabel = new JLabel();
        this.instructionsLabel = new JLabel();
        this.copyAndOpenButton = new JButton("Copy & Open Browser");
        this.continueBackgroundButton = new JButton("Continue in Background");
        this.cancelButton = new JButton("Cancel");
        this.progressBar = new JProgressBar();

        buildUI();
        setupEventHandlers();

        setSize(450, 250);
        setLocationRelativeTo(parent);
        setResizable(false);

        this.deviceFlowService = new GitHubDeviceFlowService(clientId, scheduledExecutor);
        updateStatus(AuthStatus.STARTING);
    }

    private void buildUI() {
        var mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(new EmptyBorder(20, 20, 20, 20));

        var contentPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        contentPanel.add(statusLabel, gbc);

        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        progressBar.setIndeterminate(true);
        progressBar.setVisible(true);
        contentPanel.add(progressBar, gbc);

        gbc.gridy++;
        gbc.gridwidth = 2;
        instructionsLabel.setText("Preparing authentication...");
        contentPanel.add(instructionsLabel, gbc);

        gbc.gridy++;
        userCodeLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        userCodeLabel.setVisible(false);
        contentPanel.add(userCodeLabel, gbc);

        mainPanel.add(contentPanel, BorderLayout.CENTER);

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        copyAndOpenButton.setVisible(false);
        copyAndOpenButton.setPreferredSize(new Dimension(180, 30));
        buttonPanel.add(copyAndOpenButton);

        continueBackgroundButton.setVisible(false);
        continueBackgroundButton.setPreferredSize(new Dimension(150, 30));
        buttonPanel.add(continueBackgroundButton);

        cancelButton.setPreferredSize(new Dimension(80, 30));
        buttonPanel.add(cancelButton);

        mainPanel.add(buttonPanel, BorderLayout.SOUTH);
        setContentPane(mainPanel);
    }

    private void setupEventHandlers() {
        copyAndOpenButton.addActionListener(this::onCopyAndOpenBrowser);
        continueBackgroundButton.addActionListener(this::onContinueInBackground);
        cancelButton.addActionListener(this::onCancel);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                onCancel(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "window_close"));
            }
        });

        getRootPane()
                .registerKeyboardAction(
                        this::onCancel,
                        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);

        getRootPane()
                .registerKeyboardAction(
                        this::onCopyAndOpenBrowser,
                        KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    public void setCompletionCallback(DialogCompletionCallback callback) {
        this.completionCallback = callback;
    }

    @Override
    public void setVisible(boolean visible) {
        if (visible) {
            startDeviceFlow();
        }
        super.setVisible(visible);
    }

    private void startDeviceFlow() {
        logger.info("Starting GitHub device flow authentication");
        updateStatus(AuthStatus.STARTING);

        authenticationFuture = ((ContextManager) contextManager)
                .submitBackgroundTask("GitHub device flow authentication", () -> {
                    try {
                        var deviceCodeResponse = deviceFlowService.requestDeviceCode();

                        SwingUtilities.invokeLater(() -> {
                            currentDeviceCodeResponse = deviceCodeResponse;
                            showDeviceCode(deviceCodeResponse);
                            updateStatus(AuthStatus.CODE_RECEIVED);
                        });

                    } catch (Exception e) {
                        logger.error("Failed to start device flow", e);
                        SwingUtilities.invokeLater(() -> {
                            updateStatus(AuthStatus.ERROR);
                            String errorMessage = buildDetailedErrorMessage(e);
                            showError(errorMessage);
                            completeDialog(true);
                        });
                    }
                });
    }

    private String buildDetailedErrorMessage(Exception e) {
        if (e instanceof io.github.jbellis.brokk.github.DeviceFlowException dfe) {
            String baseMessage =
                    switch (dfe.getErrorType()) {
                        case NETWORK_ERROR -> "Network connection failed. Please check your internet connection.";
                        case INVALID_RESPONSE ->
                            "GitHub returned an invalid response. The service may be temporarily unavailable.";
                        case RATE_LIMITED -> "Too many requests. Please wait a moment and try again.";
                        case SERVER_ERROR -> "GitHub server error. Please try again later.";
                        default -> "GitHub authentication failed.";
                    };

            // Include GitHub-specific details if available
            String details = e.getMessage();
            if (details != null && !details.isEmpty()) {
                return baseMessage + "\n\nDetails: " + details;
            }
            return baseMessage;
        }

        // For other exceptions, provide general guidance
        return "Failed to connect to GitHub.\n\n" + "Details: " + e.getMessage() + "\n\n"
                + "Please check your internet connection and try again.";
    }

    private void showDeviceCode(DeviceFlowModels.DeviceCodeResponse response) {
        if (response.hasCompleteUri()) {
            // With complete URI, no code entry needed
            userCodeLabel.setVisible(false);
            instructionsLabel.setText("<html>Click 'Continue in Browser' to open GitHub.<br>"
                    + "Authorization will be handled automatically.</html>");
            copyAndOpenButton.setText("Continue in Browser");
        } else {
            // Without complete URI, need to copy code for manual entry
            try {
                var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                var stringSelection = new StringSelection(response.userCode());
                clipboard.setContents(stringSelection, null);
                logger.info("Device code automatically copied to clipboard");
            } catch (Exception ex) {
                logger.error("Failed to copy code to clipboard", ex);
            }

            userCodeLabel.setText("Code: " + response.userCode() + " (copied to clipboard)");
            userCodeLabel.setVisible(true);
            instructionsLabel.setText("<html>Your device code has been copied to your clipboard.<br>"
                    + "Click 'Continue in Browser' to open GitHub and paste the code.</html>");
            copyAndOpenButton.setText("Continue in Browser");
        }

        copyAndOpenButton.setVisible(true);
        copyAndOpenButton.setEnabled(true);

        // Hide the continue in background button for now
        continueBackgroundButton.setVisible(false);

        getRootPane().setDefaultButton(copyAndOpenButton);

        // Store the response for the combined action
        currentDeviceCodeResponse = response;
    }

    private void onCopyAndOpenBrowser(ActionEvent e) {
        var deviceCodeResponse = getCurrentDeviceCodeResponse();
        if (deviceCodeResponse != null) {
            try {
                // Open the browser (code already copied)
                Environment.openInBrowser(deviceCodeResponse.getPreferredVerificationUri(), this);
                logger.info("Opened browser to GitHub verification page");

                // Start background authentication
                BackgroundGitHubAuth.startBackgroundAuth(deviceCodeResponse, contextManager);

                // Close dialog immediately - auth continues in background
                completeDialog(false);

            } catch (Exception ex) {
                logger.error("Failed to open browser", ex);
                showError("Failed to open browser: " + ex.getMessage());
                completeDialog(true);
            }
        }
    }

    private @Nullable DeviceFlowModels.DeviceCodeResponse getCurrentDeviceCodeResponse() {
        return currentDeviceCodeResponse;
    }

    private void onContinueInBackground(ActionEvent e) {
        var deviceCodeResponse = getCurrentDeviceCodeResponse();
        if (deviceCodeResponse != null) {
            logger.info("Continuing GitHub authentication in background");

            // Cancel any existing background auth and start new one
            BackgroundGitHubAuth.startBackgroundAuth(deviceCodeResponse, contextManager);

            // Close dialog immediately - auth continues in background
            completeDialog(false);
        }
    }

    private void onCancel(ActionEvent e) {
        logger.info("GitHub authentication cancelled by user");
        updateStatus(AuthStatus.CANCELLED);

        if (authenticationFuture != null && !authenticationFuture.isDone()) {
            authenticationFuture.cancel(true);
        }

        completeDialog(true);
    }

    private void updateStatus(AuthStatus status) {
        statusLabel.setText(status.getMessage());

        switch (status) {
            case STARTING -> {
                progressBar.setIndeterminate(true);
                progressBar.setVisible(true);
            }
            case CODE_RECEIVED -> {
                // Keep progress bar visible during user action phase
                progressBar.setIndeterminate(true);
                progressBar.setVisible(true);
            }
            case POLLING -> {
                progressBar.setIndeterminate(true);
                progressBar.setVisible(true);
            }
            case SUCCESS, ERROR, CANCELLED -> {
                progressBar.setVisible(false);
            }
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "GitHub Authentication Error", JOptionPane.ERROR_MESSAGE);
    }

    private void completeDialog(boolean cancelled) {
        notifyCompletion(cancelled);
        cleanup();
        dispose();
    }

    private void notifyCompletion(boolean cancelled) {
        var callback = completionCallback;
        if (callback != null) {
            SwingUtilities.invokeLater(() -> callback.onDialogComplete(cancelled));
            completionCallback = null; // Prevent duplicate notifications
        }
    }

    private void cleanup() {
        if (authenticationFuture != null && !authenticationFuture.isDone()) {
            authenticationFuture.cancel(true);
        }

        scheduledExecutor.shutdown();
    }

    @Override
    public void dispose() {
        notifyCompletion(true); // Assume cancelled if directly disposed
        cleanup();
        super.dispose();
    }
}
