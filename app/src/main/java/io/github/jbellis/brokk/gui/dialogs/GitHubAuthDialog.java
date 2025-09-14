package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.github.DeviceFlowModels;
import io.github.jbellis.brokk.github.GitHubDeviceFlowService;
import io.github.jbellis.brokk.util.Environment;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.CompletableFuture;
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
    public interface AuthCallback {
        void onComplete(boolean success, @Nullable String token, String errorMessage);
    }

    private final JLabel statusLabel;
    private final JLabel userCodeLabel;
    private final JLabel instructionsLabel;
    private final JButton copyAndOpenButton;
    private final JButton cancelButton;
    private final JProgressBar progressBar;

    private @Nullable AuthCallback authCallback;
    private GitHubDeviceFlowService deviceFlowService;
    private @Nullable CompletableFuture<Void> authenticationFuture;
    private @Nullable DeviceFlowModels.DeviceCodeResponse currentDeviceCodeResponse;

    public GitHubAuthDialog(Window parent) {
        this(parent, getDefaultClientId());
    }

    public GitHubAuthDialog(Window parent, String clientId) {
        super(parent, "Connect to GitHub", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        logger.info("Initializing GitHub Auth Dialog with client_id: {}", clientId);

        this.statusLabel = new JLabel();
        this.userCodeLabel = new JLabel();
        this.instructionsLabel = new JLabel();
        this.copyAndOpenButton = new JButton("Copy & Open Browser");
        this.cancelButton = new JButton("Cancel");
        this.progressBar = new JProgressBar();

        buildUI();
        setupEventHandlers();

        setSize(450, 250);
        setLocationRelativeTo(parent);
        setResizable(false);

        this.deviceFlowService = new GitHubDeviceFlowService(clientId);
        updateStatus(AuthStatus.STARTING);
    }

    private static String getDefaultClientId() {
        // TODO: Replace with environment variable when moving to production
        var clientId = System.getenv("BROKK_GITHUB_CLIENT_ID");
        if (clientId != null && !clientId.isBlank()) {
            return clientId;
        }

        // Hardcoded client ID for Brokk GitHub App
        return "Iv23liZ3oStCdzu0xkHI";
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

        cancelButton.setPreferredSize(new Dimension(80, 30));
        buttonPanel.add(cancelButton);

        mainPanel.add(buttonPanel, BorderLayout.SOUTH);
        setContentPane(mainPanel);
    }

    private void setupEventHandlers() {
        copyAndOpenButton.addActionListener(this::onCopyAndOpenBrowser);
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

    public void setAuthCallback(AuthCallback callback) {
        this.authCallback = callback;
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

        authenticationFuture = CompletableFuture.runAsync(() -> {
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
        userCodeLabel.setText("Code: " + response.userCode());
        userCodeLabel.setVisible(true);

        instructionsLabel.setText("<html>1. Click 'Copy & Open Browser' below<br>"
                + "2. Paste the code when prompted on GitHub<br>"
                + "3. Authorize Brokk to access your account</html>");

        copyAndOpenButton.setVisible(true);
        copyAndOpenButton.setEnabled(true);
        getRootPane().setDefaultButton(copyAndOpenButton);

        // Store the response for the combined action
        currentDeviceCodeResponse = response;
    }

    private void onCopyAndOpenBrowser(ActionEvent e) {
        var deviceCodeResponse = getCurrentDeviceCodeResponse();
        if (deviceCodeResponse != null) {
            try {
                // First, copy the code to clipboard
                var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                var stringSelection = new StringSelection(deviceCodeResponse.userCode());
                clipboard.setContents(stringSelection, null);
                logger.info("Device code copied to clipboard: {}", deviceCodeResponse.userCode());

                // Then open the browser
                Environment.openInBrowser(deviceCodeResponse.verificationUri(), this);

                // Start polling for token
                startPolling(deviceCodeResponse);

            } catch (Exception ex) {
                logger.error("Failed to copy code or open browser", ex);
                showError("Failed to copy code or open browser: " + ex.getMessage());
            }
        }
    }

    private @Nullable DeviceFlowModels.DeviceCodeResponse getCurrentDeviceCodeResponse() {
        return currentDeviceCodeResponse;
    }

    @SuppressWarnings("RedundantNullCheck")
    private void startPolling(DeviceFlowModels.DeviceCodeResponse response) {
        logger.info("Starting token polling for device code");
        updateStatus(AuthStatus.POLLING);

        copyAndOpenButton.setEnabled(false);

        deviceFlowService
                .pollForToken(response.deviceCode(), response.interval())
                .whenComplete((tokenResponse, throwable) -> {
                    SwingUtilities.invokeLater(() -> {
                        if (throwable != null) {
                            logger.error("Token polling failed", throwable);
                            updateStatus(AuthStatus.ERROR);
                            showError("Authentication failed: " + throwable.getMessage());
                            return;
                        }

                        switch (tokenResponse.result()) {
                            case SUCCESS -> {
                                updateStatus(AuthStatus.SUCCESS);
                                var token = tokenResponse.token();
                                if (token != null && token.accessToken() != null) {
                                    completeAuthentication(true, token.accessToken(), "Success");
                                } else {
                                    logger.error(
                                            "Token or accessToken was null: token={}, accessToken={}",
                                            token,
                                            token != null ? token.accessToken() : "N/A");
                                    completeAuthentication(false, "", "Token was null or invalid");
                                }
                            }
                            case DENIED -> {
                                updateStatus(AuthStatus.ERROR);
                                showError("Authentication denied by user");
                                completeAuthentication(false, "", "User denied access");
                            }
                            case EXPIRED -> {
                                updateStatus(AuthStatus.ERROR);
                                showError("Authentication code expired. Please try again.");
                                completeAuthentication(false, "", "Code expired");
                            }
                            case ERROR -> {
                                updateStatus(AuthStatus.ERROR);
                                showError("Authentication error: " + tokenResponse.errorMessage());
                                completeAuthentication(false, "", tokenResponse.errorMessage());
                            }
                            default -> {
                                updateStatus(AuthStatus.ERROR);
                                showError("Unexpected result: " + tokenResponse.result());
                                completeAuthentication(false, "", "Unexpected result");
                            }
                        }
                    });
                });
    }

    private void onCancel(ActionEvent e) {
        logger.info("GitHub authentication cancelled by user");
        updateStatus(AuthStatus.CANCELLED);

        if (authenticationFuture != null && !authenticationFuture.isDone()) {
            authenticationFuture.cancel(true);
        }

        completeAuthentication(false, "", "Cancelled by user");
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

    private void completeAuthentication(boolean success, String token, String errorMessage) {
        var callback = authCallback;
        if (callback != null) {
            SwingUtilities.invokeLater(() -> callback.onComplete(success, token, errorMessage));
        }

        cleanup();
        dispose();
    }

    @SuppressWarnings("RedundantNullCheck")
    private void cleanup() {
        if (authenticationFuture != null && !authenticationFuture.isDone()) {
            authenticationFuture.cancel(true);
        }

        if (deviceFlowService != null) {
            // Run shutdown on background thread to avoid blocking EDT
            CompletableFuture.runAsync(() -> deviceFlowService.shutdown());
        }
    }

    @Override
    public void dispose() {
        cleanup();
        super.dispose();
    }
}
