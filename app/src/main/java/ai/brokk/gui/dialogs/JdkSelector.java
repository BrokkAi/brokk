package ai.brokk.gui.dialogs;

import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.util.EnvironmentJava;
import ai.brokk.util.FileUtil;
import ai.brokk.util.PathNormalizer;
import eu.hansolo.fx.jdkmon.tools.Distro;
import eu.hansolo.fx.jdkmon.tools.Finder;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Reusable JDK selector component that wraps a JComboBox with a Browse... button. - Discovers installed JDKs
 * asynchronously. - Allows selecting a custom JDK directory via a file chooser. - Exposes the selected JDK path.
 */
public class JdkSelector extends JPanel {
    private static final Logger logger = LogManager.getLogger(JdkSelector.class);
    private static final int ABBREVIATED_PATH_MAX_LENGTH = 35;

    private final JComboBox<JdkItem> combo = new JComboBox<>();
    private final JButton browseButton = new JButton("Browse...");
    private @Nullable Component browseParent;

    public JdkSelector() {
        super(new BorderLayout(5, 0));
        combo.setPrototypeDisplayValue(new JdkItem("OpenJDK 21 (x64)", "/opt/jdk-21"));
        combo.setRenderer(new JdkItemRenderer());
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
        LoggingFuture.supplyAsync(JdkSelector::discoverInstalledJdks, ForkJoinPool.commonPool())
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

        if (EnvironmentJava.JAVA_HOME_SENTINEL.equals(path)) {
            selectOrCreateSentinel();
            return;
        }

        String normalized = normalizeJdkPath(path);
        if (normalized.isBlank()) {
            return;
        }

        // Non-sentinel: try to match against existing items (discovered or custom)
        for (int i = 0; i < combo.getItemCount(); i++) {
            var it = combo.getItemAt(i);
            if (normalized.equals(it.path) && !EnvironmentJava.JAVA_HOME_SENTINEL.equals(it.path)) {
                combo.setSelectedIndex(i);
                return;
            }
        }

        // Custom entry for non-sentinel path
        var custom = new JdkItem(createDisplayName(normalized), normalized);
        combo.addItem(custom);
        combo.setSelectedItem(custom);
    }

    private void selectOrCreateSentinel() {
        String label = computeSentinelLabel();
        JdkItem existingSentinel = null;

        // Clean up duplicates and find existing to preserve position if possible
        for (int i = combo.getItemCount() - 1; i >= 0; i--) {
            var it = combo.getItemAt(i);
            if (EnvironmentJava.JAVA_HOME_SENTINEL.equals(it.path)) {
                if (existingSentinel == null) {
                    existingSentinel = it;
                } else {
                    combo.removeItemAt(i);
                }
            }
        }

        if (existingSentinel != null && label.equals(existingSentinel.display)) {
            combo.setSelectedItem(existingSentinel);
            return;
        }

        var newItem = new JdkItem(label, EnvironmentJava.JAVA_HOME_SENTINEL);
        if (existingSentinel != null) {
            int idx = -1;
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (combo.getItemAt(i) == existingSentinel) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                combo.removeItemAt(idx);
                combo.insertItemAt(newItem, idx);
                combo.setSelectedIndex(idx);
                return;
            }
        }

        combo.addItem(newItem);
        combo.setSelectedItem(newItem);
    }

    private String computeSentinelLabel() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null || javaHome.isBlank()) {
            return "System JAVA_HOME";
        }

        // Try to find matching discovered JDK for a friendlier display
        for (int i = 0; i < combo.getItemCount(); i++) {
            var it = combo.getItemAt(i);
            if (!EnvironmentJava.JAVA_HOME_SENTINEL.equals(it.path) && javaHome.equals(it.path)) {
                return "System JAVA_HOME (" + it.display + ")";
            }
        }

        return "System JAVA_HOME (" + FileUtil.abbreviatePath(javaHome) + ")";
    }

    private static String createDisplayName(String path) {
        return "Custom JDK: " + FileUtil.abbreviatePath(path);
    }

    @VisibleForTesting
    void setDiscoveredJdksForTesting(List<String> jdkPaths) {
        var items = jdkPaths.stream()
                .map(p -> new JdkItem("Discovered JDK: " + FileUtil.abbreviatePath(p), p))
                .toList();
        combo.setModel(new DefaultComboBoxModel<>(items.toArray(JdkItem[]::new)));
    }

    @VisibleForTesting
    List<String> getItemPathsForTesting() {
        var paths = new ArrayList<String>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            paths.add(combo.getItemAt(i).path);
        }
        return paths;
    }

    @VisibleForTesting
    List<String> getItemDisplaysForTesting() {
        var displays = new ArrayList<String>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            displays.add(combo.getItemAt(i).display);
        }
        return displays;
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

        return isValidJdkPath(Path.of(jdkPath));
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
        var finder = new Finder();
        var distros = finder.getDistributions();
        var items = new ArrayList<JdkItem>();
        for (Distro d : distros) {
            var name = d.getName();
            var ver = d.getVersion();
            var arch = d.getArchitecture();
            var path = d.getPath() != null && !d.getPath().isBlank() ? d.getPath() : d.getLocation();
            if (path == null || path.isBlank()) continue;

            path = normalizeJdkPath(path);
            if (path.isBlank()) continue;

            // Only include valid JDKs (not JREs)
            if (!isValidJdk(path)) {
                logger.debug("Skipping JRE installation at: {}", path);
                continue;
            }

            var label = String.format("%s %s (%s)", name, ver, arch);
            items.add(new JdkItem(label, path));
        }
        items.sort(Comparator.comparing(a -> a.display));
        return items;
    }

    /**
     * Normalizes a JDK path: canonicalizes environment path strings and resolves macOS "Contents/Home" if pointing to a
     * bundle root.
     */
    public static String normalizeJdkPath(String rawPath) {
        String canonical = PathNormalizer.canonicalizeEnvPathValue(rawPath);
        if (canonical.isBlank()) return "";

        try {
            Path path = Path.of(canonical);
            // On macOS, if the selected path is a bundle root, use Contents/Home instead
            Path contentsHome = path.resolve("Contents").resolve("Home");
            if (validateJdkPath(contentsHome) == null) {
                return PathNormalizer.canonicalizeEnvPathValue(contentsHome.toString());
            }
        } catch (Exception e) {
            logger.debug("Error during JDK path normalization for {}: {}", rawPath, e.getMessage());
        }

        return canonical;
    }

    private static class JdkItemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof JdkItem item) {
                setText(item.display);
                setToolTipText(item.path);
            }
            return this;
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
