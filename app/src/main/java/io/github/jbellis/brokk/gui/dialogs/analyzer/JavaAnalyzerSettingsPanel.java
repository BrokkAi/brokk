package io.github.jbellis.brokk.gui.dialogs.analyzer;

import io.github.jbellis.brokk.analyzer.JavaAnalyzer;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.gui.dialogs.SettingsProjectPanel;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.prefs.Preferences;

/**
 * Configuration panel for the Java analyzer – lets the user choose the JDK
 * home directory.  Persisted in the user preferences under a key that is
 * unique per project.
 */
public final class JavaAnalyzerSettingsPanel extends AnalyzerSettingsPanel {

    public JavaAnalyzerSettingsPanel(SettingsProjectPanel parent, Language language, Path projectRoot) {
        super(new BorderLayout(5, 5), language, projectRoot);
        logger.debug("JavaAnalyzerConfigPanel initialised");

        add(new JLabel("JDK Home:"), BorderLayout.WEST);

        var centre = new JPanel(new BorderLayout(5, 0));
        centre.add(jdkHomeField, BorderLayout.CENTER);
        centre.add(browseButton, BorderLayout.EAST);
        add(centre, BorderLayout.CENTER);

        loadSettings();

        browseButton.addActionListener(e -> {
            var chooser = new javax.swing.JFileChooser();
            chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(parent) == javax.swing.JFileChooser.APPROVE_OPTION) {
                jdkHomeField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
    }

    private static final String PREF_KEY_PREFIX = "analyzer.java.jdkHome.";
    private final JTextField jdkHomeField = new JTextField(30);
    private final JButton browseButton = new JButton("Browse…");


    /* Preference-key scoped to the current project root so different projects
       can keep independent JDK selections. */
    private String getPrefKey() {
        return PREF_KEY_PREFIX + Integer.toHexString(projectRoot.hashCode());
    }

    private void loadSettings() {
        Preferences prefs = Preferences.userNodeForPackage(SettingsProjectPanel.class);
        String jdkHome = prefs.get(getPrefKey(), "");
        if (jdkHome.isEmpty()) {
            jdkHome = System.getProperty("java.home");
        }
        jdkHomeField.setText(jdkHome);
    }

    @Override
    public void saveSettings() {
        String value = jdkHomeField.getText().trim();
        Preferences prefs = Preferences.userNodeForPackage(SettingsProjectPanel.class);
        prefs.put(getPrefKey(), value);

        /* todo A future enhancement could pass this to the analyzer so that it
           immediately refreshes, if/when such an API is available. */
        logger.debug("Saved Java analyzer JDK home: {}", value);
    }

}
