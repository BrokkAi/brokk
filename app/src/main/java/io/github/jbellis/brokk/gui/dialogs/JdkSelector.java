package io.github.jbellis.brokk.gui.dialogs;

import eu.hansolo.fx.jdkmon.tools.Distro;
import eu.hansolo.fx.jdkmon.tools.Finder;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
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
            return;
        }

        // Validate the path before adding it
        var validationError = validateJdkPath(path);
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }

        int matchedIdx = -1;
        for (int i = 0; i < combo.getItemCount(); i++) {
            var it = combo.getItemAt(i);
            if (path.equals(it.path)) {
                matchedIdx = i;
                break;
            }
        }
        if (matchedIdx >= 0) {
            combo.setSelectedIndex(matchedIdx);
        } else {
            var custom = new JdkItem("Custom JDK: " + path, path);
            combo.addItem(custom);
            combo.setSelectedItem(custom);
        }
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

            // Sort with prioritization: non-JDeploy JDKs first, then JDeploy JDKs
            items.sort((a, b) -> {
                boolean aIsJDeploy = isJDeployJdk(a.path);
                boolean bIsJDeploy = isJDeployJdk(b.path);

                if (aIsJDeploy != bIsJDeploy) {
                    return aIsJDeploy ? 1 : -1; // Non-JDeploy first
                }
                return a.display.compareTo(b.display); // Alphabetical within groups
            });

            return items;
        } catch (Throwable t) {
            logger.warn("Failed to discover installed JDKs", t);
            return List.of();
        }
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

    /** Check if the given JDK path is from JDeploy based on path pattern and runtime detection. */
    private static boolean isJDeployJdk(@Nullable String path) {
        if (path == null) return false;

        // check if we're currently running from JDeploy
        return System.getProperty("jdeploy.war.path") != null || System.getenv("JDEPLOY_HOME") != null;
    }

    private static class JdkItem {
        final String display;
        final String path;

        JdkItem(String display, String path) {
            this.display = display;
            this.path = path;
        }

        @Override
        public String toString() {
            return display;
        }
    }
}
