package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.SettingsChangeListener;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dialogs.GitHubAuthDialog;
import java.awt.*;
import javax.swing.*;

public class GitHubTokenMissingPanel extends JPanel implements SettingsChangeListener {

    public GitHubTokenMissingPanel(Chrome chrome) {
        super(new FlowLayout(FlowLayout.LEFT));
        var tokenMissingLabel = new JLabel("GitHub account not connected.");
        tokenMissingLabel.setFont(tokenMissingLabel.getFont().deriveFont(Font.ITALIC));
        add(tokenMissingLabel);

        JButton connectButton = new JButton("Connect GitHub");
        connectButton.addActionListener(e -> {
            connectButton.setEnabled(false);
            connectButton.setText("Connecting...");

            GitHubAuthDialog authDialog = new GitHubAuthDialog(SwingUtilities.getWindowAncestor(this));

            GitHubAuthDialog.AuthCallback callback = (success, token, errorMessage) -> {
                SwingUtilities.invokeLater(() -> {
                    connectButton.setEnabled(true);
                    connectButton.setText("Connect GitHub");

                    if (success && !token.isEmpty()) {
                        MainProject.setGitHubToken(token);
                        GitHubAuth.invalidateInstance();
                    }
                });
            };
            authDialog.setAuthCallback(callback);

            authDialog.setVisible(true);
        });
        add(connectButton);
        MainProject.addSettingsChangeListener(this);
        updateVisibility();
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        MainProject.removeSettingsChangeListener(this);
    }

    public void updateVisibility() {
        String token = MainProject.getGitHubToken();
        setVisible(token.trim().isEmpty());
    }

    @Override
    public void gitHubTokenChanged() {
        SwingUtilities.invokeLater(this::updateVisibility);
    }
}
