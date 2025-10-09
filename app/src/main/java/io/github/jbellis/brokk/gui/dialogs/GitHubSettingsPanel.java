package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.SettingsChangeListener;
import io.github.jbellis.brokk.github.BackgroundGitHubAuth;
import io.github.jbellis.brokk.github.DeviceFlowModels;
import io.github.jbellis.brokk.github.GitHubAuthConfig;
import io.github.jbellis.brokk.github.GitHubDeviceFlowService;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.util.Environment;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class GitHubSettingsPanel extends JPanel implements SettingsChangeListener {
    private static final Logger logger = LogManager.getLogger(GitHubSettingsPanel.class);

    private final IContextManager contextManager;
    private final Component parentComponent;

    @Nullable
    private MaterialButton gitHubConnectButton;

    @Nullable
    private MaterialButton gitHubDisconnectButton;

    @Nullable
    private JLabel gitHubStatusLabel;

    @Nullable
    private JProgressBar gitHubProgressBar;

    @Nullable
    private JLabel gitHubDeviceCodeLabel;

    @Nullable
    private MaterialButton gitHubContinueBrowserButton;

    @Nullable
    private JPanel deviceCodePanel;

    @Nullable
    private JPanel browserButtonPanel;

    @Nullable
    private JLabel gitHubSuccessMessageLabel;

    @Nullable
    private JLabel gitHubInstallAppLabel;

    @Nullable
    private Timer authProgressTimer;

    @Nullable
    private GitHubDeviceFlowService deviceFlowService;

    @Nullable
    private DeviceFlowModels.DeviceCodeResponse currentDeviceCodeResponse;

    private boolean browserOpenedForCurrentCode = false;

    public GitHubSettingsPanel(IContextManager contextManager, Component parentComponent) {
        this.contextManager = contextManager;
        this.parentComponent = parentComponent;
        setLayout(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        initComponents();
        updateGitHubPanelUi();

        // Register for settings change notifications
        MainProject.addSettingsChangeListener(this);
    }

    private void initComponents() {
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        // Row: Label + Buttons
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        add(new JLabel("GitHub Account:"), gbc);

        var actionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        gitHubConnectButton = new MaterialButton("Connect GitHub Account...");
        gitHubConnectButton.addActionListener(e -> startGitHubIntegration());
        actionsPanel.add(gitHubConnectButton);

        gitHubDisconnectButton = new MaterialButton("Disconnect");
        gitHubDisconnectButton.addActionListener(e -> {
            // Clear token and invalidate client
            MainProject.setGitHubToken("");
            GitHubAuth.invalidateInstance();
            SwingUtilities.invokeLater(() -> {
                updateGitHubPanelUi();
                JOptionPane.showMessageDialog(
                        this,
                        "Disconnected from GitHub. You can reconnect any time.",
                        "GitHub",
                        JOptionPane.INFORMATION_MESSAGE);
            });
        });
        actionsPanel.add(gitHubDisconnectButton);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        add(actionsPanel, gbc);

        // Row: Explanation
        var explanationLabel = new JLabel("<html>Connect your GitHub account using Brokk's GitHub App.</html>");
        explanationLabel.setFont(explanationLabel
                .getFont()
                .deriveFont(Font.ITALIC, explanationLabel.getFont().getSize() * 0.9f));
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(2, 5, 8, 5);
        add(explanationLabel, gbc);
        gbc.insets = new Insets(2, 5, 2, 5);

        // Row: Status
        gitHubStatusLabel = new JLabel("");
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        add(gitHubStatusLabel, gbc);

        // Row: Device Code (initially hidden)
        deviceCodePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        gitHubDeviceCodeLabel = new JLabel("");
        gitHubDeviceCodeLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        deviceCodePanel.add(gitHubDeviceCodeLabel);
        deviceCodePanel.setVisible(false);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);
        add(deviceCodePanel, gbc);
        gbc.insets = new Insets(2, 5, 2, 5); // Reset insets

        // Row: Continue Browser Button (initially hidden, centered)
        browserButtonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        gitHubContinueBrowserButton = new MaterialButton("Continue in Browser");
        gitHubContinueBrowserButton.addActionListener(e -> onContinueInBrowser());
        browserButtonPanel.add(gitHubContinueBrowserButton);
        browserButtonPanel.setVisible(false);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(10, 5, 10, 5);
        add(browserButtonPanel, gbc);
        gbc.anchor = GridBagConstraints.WEST; // Reset anchor
        gbc.insets = new Insets(2, 5, 2, 5); // Reset insets

        // Row: Progress Bar (initially hidden)
        gitHubProgressBar = new JProgressBar();
        gitHubProgressBar.setIndeterminate(true);
        gitHubProgressBar.setString("Waiting for GitHub authorization...");
        gitHubProgressBar.setStringPainted(true);
        gitHubProgressBar.setVisible(false);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        add(gitHubProgressBar, gbc);

        // Row: Success Message (initially hidden)
        gitHubSuccessMessageLabel = new JLabel();
        gitHubSuccessMessageLabel.setFont(gitHubSuccessMessageLabel.getFont().deriveFont(Font.BOLD));
        gitHubSuccessMessageLabel.setForeground(new Color(0, 128, 0)); // Green color
        gitHubSuccessMessageLabel.setVisible(false);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        add(gitHubSuccessMessageLabel, gbc);

        // Row: Installation App Reminder (initially hidden)
        gitHubInstallAppLabel = new JLabel(
                "<html>To use Brokk with your repositories, <a href=\"\">install the GitHub App</a>.</html>");
        gitHubInstallAppLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        gitHubInstallAppLabel.setFont(gitHubInstallAppLabel
                .getFont()
                .deriveFont(Font.PLAIN, gitHubInstallAppLabel.getFont().getSize() * 0.9f));
        gitHubInstallAppLabel.setVisible(false);
        gitHubInstallAppLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                try {
                    Environment.openInBrowser(
                            "https://github.com/apps/brokkai/installations/select_target",
                            SwingUtilities.getWindowAncestor(parentComponent));
                } catch (Exception ex) {
                    logger.error("Failed to open GitHub App installation page", ex);
                }
            }
        });
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        add(gitHubInstallAppLabel, gbc);

        // Filler
        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        add(Box.createVerticalGlue(), gbc);
    }

    private void updateGitHubPanelUi() {
        boolean connected = !MainProject.getGitHubToken().trim().isEmpty();
        boolean authInProgress = BackgroundGitHubAuth.isAuthInProgress();
        boolean wasAuthenticating = authProgressTimer != null;
        boolean justCompleted = wasAuthenticating && !authInProgress && connected;

        if (gitHubStatusLabel != null) {
            if (authInProgress) {
                gitHubStatusLabel.setText("Status: Authenticating...");
            } else if (currentDeviceCodeResponse != null && !connected) {
                gitHubStatusLabel.setText("Status: Waiting for you to authorize in browser");
            } else {
                if (connected) {
                    String username = GitHubAuth.getAuthenticatedUsername();
                    if (username != null) {
                        gitHubStatusLabel.setText("Status: Connected as @" + username);
                    } else {
                        gitHubStatusLabel.setText("Status: Connected");
                    }
                } else {
                    gitHubStatusLabel.setText("Status: Not connected");
                }
            }
            gitHubStatusLabel.setFont(gitHubStatusLabel.getFont().deriveFont(Font.ITALIC));
        }

        if (gitHubProgressBar != null) {
            gitHubProgressBar.setVisible(authInProgress);
        }

        // Show success message and app installation reminder when authentication just completed
        if (gitHubSuccessMessageLabel != null && gitHubInstallAppLabel != null) {
            if (justCompleted) {
                gitHubSuccessMessageLabel.setText("Successfully connected to GitHub!");
                gitHubSuccessMessageLabel.setVisible(true);
                gitHubInstallAppLabel.setVisible(true);
                // Hide both messages after 10 seconds
                var successLabel = gitHubSuccessMessageLabel;
                var installLabel = gitHubInstallAppLabel;
                Timer hideMessageTimer = new Timer(10000, e -> {
                    successLabel.setVisible(false);
                    installLabel.setVisible(false);
                });
                hideMessageTimer.setRepeats(false);
                hideMessageTimer.start();
            } else if (!connected) {
                gitHubSuccessMessageLabel.setVisible(false);
                gitHubInstallAppLabel.setVisible(false);
            }
        }

        boolean showingDeviceCode = currentDeviceCodeResponse != null && !connected;
        boolean showingBrowserButton = showingDeviceCode && !browserOpenedForCurrentCode;

        if (deviceCodePanel != null) {
            deviceCodePanel.setVisible(showingDeviceCode);
        }

        if (browserButtonPanel != null) {
            browserButtonPanel.setVisible(showingBrowserButton);
        }

        if (gitHubConnectButton != null) {
            gitHubConnectButton.setEnabled(!connected && !authInProgress && !showingDeviceCode);
        }
        if (gitHubDisconnectButton != null) {
            gitHubDisconnectButton.setEnabled(connected && !authInProgress);
        }

        // Manage progress monitoring timer
        if ((authInProgress || showingDeviceCode) && authProgressTimer == null) {
            // Start timer to periodically update UI while auth is in progress
            authProgressTimer = new Timer(1000, e -> updateGitHubPanelUi());
            authProgressTimer.start();
        } else if (!authInProgress && !showingDeviceCode && authProgressTimer != null) {
            // Stop timer when auth is complete
            authProgressTimer.stop();
            authProgressTimer = null;
            currentDeviceCodeResponse = null;
            browserOpenedForCurrentCode = false;
        }
    }

    private void startGitHubIntegration() {
        logger.info("Starting inline GitHub integration");

        // Initialize device flow service with dedicated scheduler
        var executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SettingsGitHubAuth-Scheduler");
            t.setDaemon(true);
            return t;
        });
        deviceFlowService = new GitHubDeviceFlowService(GitHubAuthConfig.getClientId(), executor);

        // Start device code request in background
        CompletableFuture.runAsync(() -> {
            try {
                var service = deviceFlowService;
                if (service == null) {
                    throw new IllegalStateException("Device flow service not initialized");
                }
                var deviceCodeResponse = service.requestDeviceCode();
                SwingUtilities.invokeLater(() -> showDeviceCode(deviceCodeResponse));
            } catch (Exception e) {
                logger.error("Failed to request device code", e);
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(
                            this,
                            "Failed to start GitHub authentication: " + e.getMessage(),
                            "GitHub Authentication Error",
                            JOptionPane.ERROR_MESSAGE);
                    updateGitHubPanelUi();
                });
            }
        });
    }

    private void showDeviceCode(DeviceFlowModels.DeviceCodeResponse response) {
        currentDeviceCodeResponse = response;
        browserOpenedForCurrentCode = false; // Reset flag for new device code

        if (response.hasCompleteUri()) {
            // With complete URI, no code entry needed
            if (gitHubDeviceCodeLabel != null) {
                gitHubDeviceCodeLabel.setText("Ready to authenticate with GitHub");
            }
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

            if (gitHubDeviceCodeLabel != null) {
                gitHubDeviceCodeLabel.setText("CODE: " + response.userCode() + " (copied to clipboard)");
            }
        }

        // Start background authentication immediately when device code is shown
        BackgroundGitHubAuth.startBackgroundAuth(response, contextManager);

        updateGitHubPanelUi();
    }

    private void onContinueInBrowser() {
        if (currentDeviceCodeResponse != null) {
            try {
                // Open the browser (authentication already started when device code was shown)
                Environment.openInBrowser(
                        currentDeviceCodeResponse.getPreferredVerificationUri(),
                        SwingUtilities.getWindowAncestor(parentComponent));
                logger.info("Opened browser to GitHub verification page");

                // Mark browser as opened and hide only the browser button
                browserOpenedForCurrentCode = true;
                if (browserButtonPanel != null) {
                    browserButtonPanel.setVisible(false);
                }

            } catch (Exception ex) {
                logger.error("Failed to open browser", ex);
                JOptionPane.showMessageDialog(
                        this,
                        "Failed to open browser: " + ex.getMessage(),
                        "GitHub Authentication Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public void loadSettings() {
        updateGitHubPanelUi();
    }

    public boolean applySettings() {
        updateGitHubPanelUi();
        return true;
    }

    // SettingsChangeListener implementation
    @Override
    public void gitHubTokenChanged() {
        SwingUtilities.invokeLater(this::updateGitHubPanelUi);
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        MainProject.removeSettingsChangeListener(this);

        // Stop progress timer if running
        if (authProgressTimer != null) {
            authProgressTimer.stop();
            authProgressTimer = null;
        }
    }
}
