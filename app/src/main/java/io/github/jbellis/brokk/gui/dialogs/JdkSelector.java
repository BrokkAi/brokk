package io.github.jbellis.brokk.gui.dialogs;

import eu.hansolo.fx.jdkmon.tools.Distro;
import eu.hansolo.fx.jdkmon.tools.Finder;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Reusable JDK selector component that wraps a JComboBox with a Browse... button. - Discovers installed JDKs
 * asynchronously. - Allows selecting a custom JDK directory via a file chooser. - Exposes the selected JDK path.
 */
public class JdkSelector extends JPanel {
    private static final Logger logger = LogManager.getLogger(JdkSelector.class);

    private final JComboBox<JdkItem> combo = new JComboBox<>();
    private final JButton browseButton = new JButton("Browse...");
    private @Nullable Component browseParent;

    public JdkSelector() {
        super(new BorderLayout(5, 0));
        combo.setPrototypeDisplayValue(new JdkItem("OpenJDK 21 (x64)", "/opt/jdk-21"));
        add(combo, BorderLayout.CENTER);
        add(browseButton, BorderLayout.EAST);

        browseButton.addActionListener(e -> {
            var chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            var parent = browseParent != null ? browseParent : SwingUtilities.getWindowAncestor(this);
            int result = chooser.showOpenDialog(parent);
            if (result == JFileChooser.APPROVE_OPTION) {
                var file = chooser.getSelectedFile();
                if (file != null) {
                    setSelectedJdkPath(file.getAbsolutePath());
                }
            }
        });
    }

    public void setBrowseParent(@Nullable Component parent) {
        this.browseParent = parent;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        combo.setEnabled(enabled);
        browseButton.setEnabled(enabled);
    }

    /**
     * Populate the combo asynchronously with discovered JDKs and select the given desired path if provided. If the
     * desired path is not among discovered ones, a "Custom JDK" entry will be added and selected.
     */
    public void loadJdksAsync(@Nullable String desiredPath) {
        CompletableFuture.supplyAsync(JdkSelector::discoverInstalledJdks, ForkJoinPool.commonPool())
                .whenComplete((List<JdkItem> items, @Nullable Throwable ex) -> {
                    if (ex != null) {
                        logger.warn("JDK discovery failed: {}", ex.getMessage(), ex);
                        items = List.of();
                    }
                    final var discovered = items;
                    SwingUtilities.invokeLater(() -> {
                        combo.setModel(new DefaultComboBoxModel<>(discovered.toArray(JdkItem[]::new)));
                        if (desiredPath != null && !desiredPath.isBlank()) {
                            setSelectedJdkPath(desiredPath);
                        } else {
                            // No desired path: select first item if available
                            if (combo.getItemCount() > 0) {
                                combo.setSelectedIndex(0);
                            }
                        }
                        logger.trace("JDKs loaded into selector: {}", combo.getItemCount());
                    });
                });
    }

    /**
     * Ensure the given path is in the combo (adding a Custom entry if needed) and select it.
     *
     * @param path the JDK path to select
     * @throws IllegalArgumentException if the path is not a valid JDK
     */
    public void setSelectedJdkPath(@Nullable String path) {
        if (path == null || path.isBlank()) {
            // Select the system default (first item if it exists)
            if (combo.getItemCount() > 0) {
                combo.setSelectedIndex(0);
            }
            return;
        }

        // Expand environment variables if present
        String expandedPath = expandEnvironmentVariables(path);

        // Validate the expanded path before adding it
        var validationError = validateJdkPath(expandedPath);
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }

        int matchedIdx = -1;
        for (int i = 0; i < combo.getItemCount(); i++) {
            var it = combo.getItemAt(i);
            if (expandedPath.equals(it.path)) {
                matchedIdx = i;
                break;
            }
        }
        if (matchedIdx >= 0) {
            combo.setSelectedIndex(matchedIdx);
        } else {
            var custom = new JdkItem("Custom JDK: " + expandedPath, expandedPath);
            combo.addItem(custom);
            combo.setSelectedItem(custom);
        }
    }

    /**
     * Expand environment variables in the given path. Currently supports $JAVA_HOME and ${JAVA_HOME} patterns.
     *
     * @param path the path that may contain environment variables
     * @return the path with environment variables expanded
     */
    private static String expandEnvironmentVariables(String path) {
        if (path.isBlank()) {
            return path;
        }

        // Handle $JAVA_HOME
        if (path.equals("$JAVA_HOME")) {
            String javaHome = System.getenv("JAVA_HOME");
            return javaHome != null ? javaHome : path;
        }

        // Handle ${JAVA_HOME}
        if (path.equals("${JAVA_HOME}")) {
            String javaHome = System.getenv("JAVA_HOME");
            return javaHome != null ? javaHome : path;
        }

        // Handle paths that start with $JAVA_HOME/ or ${JAVA_HOME}/
        if (path.startsWith("$JAVA_HOME/")) {
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome != null) {
                return javaHome + path.substring("$JAVA_HOME".length());
            }
        }

        if (path.startsWith("${JAVA_HOME}/")) {
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome != null) {
                return javaHome + path.substring("${JAVA_HOME}".length());
            }
        }

        return path;
    }

    /**
     * Validate a JDK path and return a specific error message if invalid.
     *
     * @param path the path to validate
     * @return error message if invalid, null if valid
     */
    private static @Nullable String validateJdkPath(String path) {
        if (path.isBlank()) {
            return "JDK path cannot be empty";
        }

        var jdkDir = new File(path);
        if (!jdkDir.exists()) {
            return "The directory '" + path + "' does not exist";
        }

        if (!jdkDir.isDirectory()) {
            return "The path '" + path + "' is not a directory";
        }

        if (!isValidJdk(path)) {
            if (hasJavaExecutable(path)) {
                return "The directory '" + path
                        + "' appears to be a JRE (Java Runtime Environment) rather than a JDK (Java Development Kit). Please select a JDK installation that includes development tools like javac";
            } else {
                return "The directory '" + path + "' does not appear to be a valid Java installation";
            }
        }

        return null; // Valid JDK
    }

    /** @return the selected JDK path or null if none selected. */
    public @Nullable String getSelectedJdkPath() {
        var sel = (JdkItem) combo.getSelectedItem();
        return sel == null ? null : sel.path;
    }

    private static List<JdkItem> discoverInstalledJdks() {
        try {
            var finder = new Finder();
            var distros = finder.getDistributions();
            var items = new ArrayList<JdkItem>();

            // Add system default entry at the top
            String systemDefaultDisplay = getSystemDefaultDisplay();
            items.add(new JdkItem(systemDefaultDisplay, null));
            for (Distro d : distros) {
                var name = d.getName();
                var ver = d.getVersion();
                var arch = d.getArchitecture();
                var path = d.getPath() != null && !d.getPath().isBlank() ? d.getPath() : d.getLocation();
                if (path == null || path.isBlank()) continue;

                // Normalize to canonical path for consistency if possible
                try {
                    path = new File(path).getCanonicalPath();
                } catch (Exception ignored) {
                    // Fallback to original path
                }

                // Only include valid JDKs (not JREs)
                if (!isValidJdk(path)) {
                    logger.debug("Skipping JRE installation at: {}", path);
                    continue;
                }

                var label = String.format("%s %s (%s)", name, ver, arch);
                items.add(new JdkItem(label, path));
            }

            // Sort alphabetically by display name, but keep system default first
            var systemDefault = items.get(0); // Save the system default
            items.remove(0);
            items.sort((a, b) -> a.display.compareTo(b.display));
            items.add(0, systemDefault); // Put system default back at top

            return items;
        } catch (Throwable t) {
            logger.warn("Failed to discover installed JDKs", t);
            return List.of();
        }
    }

    /**
     * Get display text for the system default JDK by detecting JAVA_HOME or java command location.
     *
     * @return formatted display string for system default
     */
    private static String getSystemDefaultDisplay() {
        try {
            // First try JAVA_HOME
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome != null && !javaHome.isBlank() && isValidJdk(javaHome)) {
                String version = getJdkVersion(javaHome);
                return String.format(
                        "System Default (%s) - %s", version != null ? version : "Unknown version", javaHome);
            }

            // Try to find java executable in PATH
            String javaPath = findJavaInPath();
            if (javaPath != null) {
                // Try to derive JDK home from java executable path
                var javaFile = new File(javaPath);
                var binDir = javaFile.getParentFile();
                if (binDir != null && binDir.getName().equals("bin")) {
                    var jdkHome = binDir.getParent();
                    if (jdkHome != null && isValidJdk(jdkHome)) {
                        String version = getJdkVersion(jdkHome);
                        return String.format(
                                "System Default (%s) - %s", version != null ? version : "Unknown version", jdkHome);
                    }
                }

                // Fall back to showing just the java executable
                String version = getJavaVersionFromExecutable(javaPath);
                return String.format(
                        "System Default (%s) - %s", version != null ? version : "Unknown version", javaPath);
            }

            return "System Default (No JDK detected)";
        } catch (Exception e) {
            logger.debug("Failed to detect system default JDK", e);
            return "System Default (Auto-detect)";
        }
    }

    /** Get JDK version from a JDK home directory by checking release file or running java -version. */
    private static @Nullable String getJdkVersion(String jdkHome) {
        try {
            // Try to read version from release file first (faster)
            var releaseFile = new File(jdkHome, "release");
            if (releaseFile.exists()) {
                var lines = java.nio.file.Files.readAllLines(releaseFile.toPath());
                for (String line : lines) {
                    if (line.startsWith("JAVA_VERSION=")) {
                        String version =
                                line.substring("JAVA_VERSION=".length()).replaceAll("\"", "");
                        return "JDK " + version;
                    }
                }
            }

            // Fall back to running java -version
            var javaExe = new File(jdkHome, "bin/java");
            if (javaExe.exists()) {
                return getJavaVersionFromExecutable(javaExe.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.debug("Failed to get JDK version from {}", jdkHome, e);
        }
        return null;
    }

    /** Get Java version by running java -version command. */
    private static @Nullable String getJavaVersionFromExecutable(String javaPath) {
        try {
            var process = new ProcessBuilder(javaPath, "-version").start();
            process.waitFor();
            var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            // Parse version from output like: openjdk version "21.0.1" 2023-10-17
            for (String line : stderr.split("\n", -1)) {
                if (line.contains("version \"")) {
                    int start = line.indexOf("version \"") + "version \"".length();
                    int end = line.indexOf("\"", start);
                    if (end > start) {
                        String version = line.substring(start, end);
                        String jdkType = line.toLowerCase(Locale.ROOT).contains("openjdk") ? "OpenJDK" : "JDK";
                        return jdkType + " " + version;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to get Java version from {}", javaPath, e);
        }
        return null;
    }

    /** Find java executable in system PATH. */
    private static @Nullable String findJavaInPath() {
        try {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String javaCommand = os.contains("win") ? "java.exe" : "java";

            var process = new ProcessBuilder("which", javaCommand).start();
            if (process.waitFor() == 0) {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            logger.debug("Failed to find java in PATH", e);
        }
        return null;
    }

    /** Check if the given path is a valid JDK (has both java and javac). */
    private static boolean isValidJdk(String path) {
        if (path.isBlank()) return false;

        var javaDir = new File(path);
        var javacPath = new File(javaDir, "bin/javac");

        return javacPath.exists() && javacPath.canExecute();
    }

    /** Check if the given path has a java executable (JRE or JDK). */
    private static boolean hasJavaExecutable(String path) {
        if (path.isBlank()) return false;

        var javaDir = new File(path);
        var javaPath = new File(javaDir, "bin/java");

        return javaPath.exists();
    }

    private static class JdkItem {
        final String display;
        final @Nullable String path;

        JdkItem(String display, @Nullable String path) {
            this.display = display;
            this.path = path;
        }

        @Override
        public String toString() {
            return display;
        }
    }
}
