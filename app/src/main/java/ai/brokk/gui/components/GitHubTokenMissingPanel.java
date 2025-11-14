package ai.brokk.gui.components;

import ai.brokk.MainProject;
import ai.brokk.SettingsChangeListener;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.dialogs.SettingsDialog;
import java.awt.*;
import javax.swing.*;

public class GitHubTokenMissingPanel extends JPanel implements SettingsChangeListener {

    public GitHubTokenMissingPanel(Chrome chrome) {
        super(new FlowLayout(FlowLayout.LEFT));
        var tokenMissingLabel = new JLabel("GitHub access token not configured.");
        tokenMissingLabel.setFont(tokenMissingLabel.getFont().deriveFont(Font.ITALIC));
        add(tokenMissingLabel);
        MaterialButton settingsButton = new MaterialButton("Settings");
        settingsButton.addActionListener(
                e -> SettingsDialog.showSettingsDialog(chrome, SettingsDialog.GITHUB_SETTINGS_TAB_NAME));
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
        setVisible(token.trim().isEmpty());
    }

    @Override
    public void gitHubTokenChanged() {
        SwingUtilities.invokeLater(this::updateVisibility);
    }
}
