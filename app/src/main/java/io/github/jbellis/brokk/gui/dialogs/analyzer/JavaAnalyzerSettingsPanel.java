package io.github.jbellis.brokk.gui.dialogs.analyzer;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.lsp.jdt.SharedJdtLspServer;
import io.github.jbellis.brokk.gui.dialogs.SettingsProjectPanel;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.util.prefs.Preferences;

/**
 * Configuration panel for the Java analyzer – lets the user choose the JDK
 * home directory.  Persisted in the user preferences under a key that is
 * unique per project.
 */
public final class JavaAnalyzerSettingsPanel extends AnalyzerSettingsPanel {

    private static final String PREF_KEY_PREFIX = "analyzer.java.jdkHome.";
    private final JTextField jdkHomeField = new JTextField(30);
    private final JButton browseButton = new JButton("Browse...");

    public JavaAnalyzerSettingsPanel(SettingsProjectPanel parent, Language language, Path projectRoot, IConsoleIO io) {
        super(new BorderLayout(5, 5), language, projectRoot, io);
        logger.debug("JavaAnalyzerConfigPanel initialised");

        add(new JLabel("JDK Home:"), BorderLayout.WEST);

        var centre = new JPanel(new BorderLayout(5, 0));
        centre.add(jdkHomeField, BorderLayout.CENTER);
        centre.add(browseButton, BorderLayout.EAST);
        add(centre, BorderLayout.CENTER);

        loadSettings();

        browseButton.addActionListener(e -> {
            var chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                jdkHomeField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        // Prevent this panel from stretching vertically inside the BoxLayout container
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
    }

    /* Preference-key scoped to the current project root so different projects
       can keep independent JDK selections. */
    private String getPrefKey() {
        return PREF_KEY_PREFIX + Integer.toHexString(projectRoot.hashCode());
    }

    private void loadSettings() {
        final Preferences prefs = Preferences.userNodeForPackage(SettingsProjectPanel.class);
        String jdkHome = prefs.get(getPrefKey(), "");
        if (jdkHome.isEmpty()) {
            jdkHome = System.getProperty("java.home");
        }
        jdkHomeField.setText(jdkHome);
    }

    @Override
    public void saveSettings() {
        final String value = jdkHomeField.getText().trim();
        if (value.isEmpty()) {
            consoleIO.systemNotify("Please specify a valid JDK home directory.",
                                   "Invalid JDK Path",
                                   JOptionPane.WARNING_MESSAGE);
            return;
        }

        final Path jdkPath;
        try {
            jdkPath = Path.of(value).normalize().toAbsolutePath();
        } catch (InvalidPathException ex) {
            consoleIO.systemNotify("The path \"" + value + "\" is not a valid file-system path.",
                                   "Invalid JDK Path",
                                   JOptionPane.ERROR_MESSAGE);
            logger.warn("Invalid JDK path string: {}", value, ex);
            return;
        }

        if (!Files.isDirectory(jdkPath)) {
            consoleIO.systemNotify("The path \"" + jdkPath + "\" does not exist or is not a directory.",
                                   "Invalid JDK Path",
                                   JOptionPane.ERROR_MESSAGE);
            return;
        }

        final boolean hasJavac = Files.isRegularFile(jdkPath.resolve("bin/javac")) ||
                Files.isRegularFile(jdkPath.resolve("bin/javac.exe"));
        final boolean hasJava = Files.isRegularFile(jdkPath.resolve("bin/java")) ||
                Files.isRegularFile(jdkPath.resolve("bin/java.exe"));

        if (!hasJavac || !hasJava) {
            consoleIO.systemNotify("The directory \"" + jdkPath + "\" does not appear to be a valid JDK home.",
                                   "Invalid JDK Path",
                                   JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            // Wait synchronously so we can detect errors and notify the user immediately
            SharedJdtLspServer.getInstance()
                           .updateWorkspaceJdk(projectRoot, jdkPath)
                           .join();
        } catch (Exception ex) {
            consoleIO.systemNotify("Failed to apply the selected JDK to the Java analyzer. Please check the logs for details.",
                                   "JDK Update Failed",
                                   JOptionPane.ERROR_MESSAGE);
            logger.error("Failed updating workspace JDK to {}", jdkPath, ex);
            return;
        }

        // Persist the preference only if everything succeeded
        final Preferences prefs = Preferences.userNodeForPackage(SettingsProjectPanel.class);
        prefs.put(getPrefKey(), value);
        logger.debug("Saved Java analyzer JDK home: {}", value);
    }

}
