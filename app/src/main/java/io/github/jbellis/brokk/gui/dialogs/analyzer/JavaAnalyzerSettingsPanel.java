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
    private static final String PREF_MEMORY_KEY_PREFIX = "analyzer.java.memory.";
    private static final int DEFAULT_MEMORY_MB = 2048;
    private static final int MIN_MEMORY_MB = 512;
    
    private final JTextField jdkHomeField = new JTextField(30);
    private final JButton browseButton = new JButton("Browse...");
    private final JSpinner memorySpinner;

    public JavaAnalyzerSettingsPanel(SettingsProjectPanel parent, Language language, Path projectRoot, IConsoleIO io) {
        super(new BorderLayout(), language, projectRoot, io);
        logger.debug("JavaAnalyzerConfigPanel initialised");

        // Create a panel with GridBagLayout for the actual content
        var contentPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // JDK Home row
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0;
        contentPanel.add(new JLabel("JDK Home:"), gbc);

        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0;
        contentPanel.add(jdkHomeField, gbc);

        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0.0;
        contentPanel.add(browseButton, gbc);

        // Memory row
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        contentPanel.add(new JLabel("Language Server Memory (MB):"), gbc);

        // Calculate max memory based on system
        long maxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        // If maxMemory returns Long.MAX_VALUE, use total memory instead
        if (maxMemoryMB > 100000) {
            maxMemoryMB = Runtime.getRuntime().totalMemory() / (1024 * 1024) * 4; // Assume 4x current heap as reasonable max
        }
        memorySpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_MEMORY_MB, MIN_MEMORY_MB, (int)Math.min(maxMemoryMB, 32768), 256));
        
        gbc.gridx = 1; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        contentPanel.add(memorySpinner, gbc);

        // Add the content panel to this BorderLayout panel
        add(contentPanel, BorderLayout.CENTER);

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

    private String getMemoryPrefKey() {
        return PREF_MEMORY_KEY_PREFIX + Integer.toHexString(projectRoot.hashCode());
    }

    private void loadSettings() {
        final Preferences prefs = Preferences.userNodeForPackage(SettingsProjectPanel.class);
        String jdkHome = prefs.get(getPrefKey(), "");
        if (jdkHome.isEmpty()) {
            jdkHome = System.getProperty("java.home");
        }
        jdkHomeField.setText(jdkHome);
        
        int memory = prefs.getInt(getMemoryPrefKey(), DEFAULT_MEMORY_MB);
        memorySpinner.setValue(memory);
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

        // Check if the JDK path has actually changed
        final Preferences prefs = Preferences.userNodeForPackage(SettingsProjectPanel.class);
        final String previousValue = prefs.get(getPrefKey(), "");
        final boolean pathChanged = !value.equals(previousValue);

        if (pathChanged) {
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
            logger.debug("Updated Java analyzer JDK home: {}", value);
        } else {
            logger.debug("Java analyzer JDK home unchanged: {}", value);
        }

        // Persist the preference (even if unchanged, to ensure it's saved)
        prefs.put(getPrefKey(), value);
        
        // Save memory setting
        int memoryMB = (Integer) memorySpinner.getValue();
        prefs.putInt(getMemoryPrefKey(), memoryMB);
        
        // Update the SharedJdtLspServer with the new memory setting
        SharedJdtLspServer.getInstance().setMemoryMB(memoryMB);
    }

}
