package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.SettingsChangeListener;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;

import javax.swing.*;
import java.awt.*;

public class GitHubTokenMissingPanel extends JPanel implements SettingsChangeListener {

    public GitHubTokenMissingPanel(Chrome chrome) {
        super(new FlowLayout(FlowLayout.LEFT));
        var tokenMissingLabel = new JLabel("GitHub token not configured.");
        tokenMissingLabel.setFont(tokenMissingLabel.getFont().deriveFont(Font.ITALIC));
        add(tokenMissingLabel);
        JButton settingsButton = new JButton("Settings");
        settingsButton.addActionListener(e -> SettingsDialog.showSettingsDialog(chrome, SettingsDialog.GITHUB_SETTINGS_TAB_NAME));
        add(settingsButton);
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
        setVisible(token == null || token.trim().isEmpty());
    }

    @Override
    public void gitHubTokenChanged() {
        SwingUtilities.invokeLater(this::updateVisibility);
    }
}
