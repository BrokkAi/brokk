package io.github.jbellis.brokk.gui.dialogs;

import eu.hansolo.fx.jdkmon.tools.Distro;
import eu.hansolo.fx.jdkmon.tools.Finder;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** Ensure the given path is in the combo (adding a Custom entry if needed) and select it. */
    public void setSelectedJdkPath(@Nullable String path) {
        if (path == null || path.isBlank()) {
            return;
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

    /** @return the selected JDK path or null if none selected. */
    public @Nullable String getSelectedJdkPath() {
        var sel = (JdkItem) combo.getSelectedItem();
        return sel == null ? null : sel.path;
    }

    /**
     * Centralized JDK validation that checks for both java and javac executables. Handles Windows (.exe) extensions and
     * macOS Contents/Home structure gracefully.
     *
     * @param jdkPath the path to validate as a JDK installation
     * @return true if the path contains a valid JDK, false otherwise
     */
    public static boolean isValidJdk(@Nullable String jdkPath) {
        if (jdkPath == null || jdkPath.isBlank()) {
            return false;
        }

        try {
            Path path = Path.of(jdkPath);
            return isValidJdkPath(path);
        } catch (Exception e) {
            logger.debug("Invalid path format for JDK validation: {}", jdkPath, e);
            return false;
        }
    }

    /**
     * Centralized JDK validation that checks for both java and javac executables. Handles Windows (.exe) extensions and
     * macOS Contents/Home structure gracefully.
     *
     * @param jdkPath the path to validate as a JDK installation
     * @return true if the path contains a valid JDK, false otherwise
     */
    public static boolean isValidJdkPath(@Nullable Path jdkPath) {
        return validateJdkPath(jdkPath) == null;
    }

    /**
     * Detailed JDK validation that returns specific error information. Handles Windows (.exe) extensions and macOS
     * Contents/Home structure gracefully.
     *
     * @param jdkPath the path to validate as a JDK installation
     * @return null if valid, or a detailed error message if invalid
     */
    public static @Nullable String validateJdkPath(@Nullable Path jdkPath) {
        if (jdkPath == null) {
            return "JDK path is null";
        }

        if (!Files.exists(jdkPath)) {
            return "The directory '" + jdkPath + "' does not exist";
        }

        if (!Files.isDirectory(jdkPath)) {
            return "The path '" + jdkPath + "' is not a directory";
        }

        // Check the provided path first
        String directValidationError = validateJdkExecutables(jdkPath);
        if (directValidationError == null) {
            return null; // Valid JDK found at provided path
        }

        // On macOS, try Contents/Home subdirectory (common in .app bundles and some JDK distributions)
        Path contentsHome = jdkPath.resolve("Contents").resolve("Home");
        if (Files.exists(contentsHome) && Files.isDirectory(contentsHome)) {
            String contentsHomeValidationError = validateJdkExecutables(contentsHome);
            if (contentsHomeValidationError == null) {
                return null; // Valid JDK found at Contents/Home
            }
        }

        // Return the original validation error (from the main path)
        return directValidationError;
    }

    /**
     * Validate JDK executables at a specific path and return detailed error information.
     *
     * @param jdkPath the path to check for JDK executables
     * @return null if valid, or a specific error message about what's missing
     */
    private static @Nullable String validateJdkExecutables(@Nullable Path jdkPath) {
        if (jdkPath == null || !Files.exists(jdkPath) || !Files.isDirectory(jdkPath)) {
            return "Invalid directory path";
        }

        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        boolean isWindows = os.contains("win");

        Path binDir = jdkPath.resolve("bin");
        if (!Files.exists(binDir) || !Files.isDirectory(binDir)) {
            return "The directory does not contain a 'bin' subdirectory. Please ensure you're pointing to the JDK home directory (not the bin directory itself).";
        }

        // Check for java executable
        Path javaExe = binDir.resolve(isWindows ? "java.exe" : "java");
        boolean hasJava = Files.isRegularFile(javaExe) && (isWindows || Files.isExecutable(javaExe));

        // Check for javac executable
        Path javacExe = binDir.resolve(isWindows ? "javac.exe" : "javac");
        boolean hasJavac = Files.isRegularFile(javacExe) && (isWindows || Files.isExecutable(javacExe));

        if (!hasJava && !hasJavac) {
            return "The directory does not contain java or javac executables. This appears to be neither a JRE nor a JDK. Please select a valid JDK installation.";
        }

        if (!hasJavac) {
            return "The directory contains java but not javac. This appears to be a JRE (Java Runtime Environment) rather than a JDK (Java Development Kit). Please select a JDK installation that includes development tools.";
        }

        if (!hasJava) {
            return "The directory contains javac but not java. This appears to be an incomplete JDK installation. Please select a complete JDK installation.";
        }

        return null; // Valid JDK
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
            items.sort((a, b) -> a.display.compareTo(b.display));
            return items;
        } catch (Throwable t) {
            logger.warn("Failed to discover installed JDKs", t);
            return List.of();
        }
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
