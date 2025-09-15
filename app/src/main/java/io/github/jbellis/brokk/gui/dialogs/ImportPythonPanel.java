package io.github.jbellis.brokk.gui.dialogs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dependencies.DependenciesPanel;
import io.github.jbellis.brokk.util.FileUtil;
import java.awt.BorderLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Import Python dependencies from discovered virtual environments' site-packages. - Single list (no direct/transitive
 * split). - Deduplicate same name+version across venvs (keep the first occurrence). - Copy selected package's source
 * files from venv to project's dependencies directory.
 */
public class ImportPythonPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(ImportPythonPanel.class);

    private static final List<String> DOC_PREFIXES = List.of("readme", "license", "copying");

    private final Chrome chrome;
    private final Path dependenciesRoot;

    private final JTextField searchField = new JTextField();
    private final DefaultTableModel model = new DefaultTableModel(new Object[] {"Name", "Files"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 1 ? Long.class : String.class;
        }
    };
    private final JTable table = new JTable(model);
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);

    private final List<Consumer<@Nullable PackageRow>> selectionListeners = new ArrayList<>();
    private @Nullable Runnable doubleClickListener;
    private @Nullable DependenciesPanel.DependencyLifecycleListener lifecycleListener;

    private final Map<String, PackageRow> displayToPackage = new LinkedHashMap<>();

    public ImportPythonPanel(Chrome chrome) {
        super(new BorderLayout(5, 5));
        this.chrome = chrome;
        this.dependenciesRoot = chrome.getProject()
                .getRoot()
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.DEPENDENCIES_DIR);
        initUi();
        loadPackages();
    }

    public void setLifecycleListener(@Nullable DependenciesPanel.DependencyLifecycleListener lifecycleListener) {
        this.lifecycleListener = lifecycleListener;
    }

    public void addSelectionListener(Consumer<@Nullable PackageRow> listener) {
        selectionListeners.add(listener);
    }

    public void addDoubleClickListener(Runnable onDoubleClick) {
        this.doubleClickListener = onDoubleClick;
    }

    public @Nullable PackageRow getSelectedPackage() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return null;
        int modelRow = table.convertRowIndexToModel(viewRow);
        String key = (String) model.getValueAt(modelRow, 0);
        return displayToPackage.get(key);
    }

    public boolean initiateImport() {
        var selected = getSelectedPackage();
        if (selected == null) return false;

        var depName = selected.name() + "-" + selected.version();

        @Nullable final DependenciesPanel.DependencyLifecycleListener currentListener = lifecycleListener;
        if (currentListener != null) {
            SwingUtilities.invokeLater(() -> currentListener.dependencyImportStarted(depName));
        }

        var sitePackages = selected.sitePackages();
        if (!Files.exists(sitePackages)) {
            SwingUtilities.invokeLater(() -> {
                chrome.toolError(
                        "Could not locate site-packages for " + depName
                                + ". Ensure your virtual environment exists and is built.",
                        "Python Import");
            });
            return false;
        }

        var targetRoot = dependenciesRoot.resolve(depName);

        chrome.getContextManager().submitBackgroundTask("Copying Python package: " + depName, () -> {
            try {
                Files.createDirectories(targetRoot.getParent());
                if (Files.exists(targetRoot)) {
                    if (!FileUtil.deleteRecursively(targetRoot)) {
                        throw new IOException("Failed to delete existing destination: " + targetRoot);
                    }
                }
                copyFiles(selected, targetRoot);
                SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput("Python package copied to " + targetRoot
                            + ". Reopen project to incorporate the new files.");
                    if (currentListener != null) currentListener.dependencyImportFinished(depName);
                });
            } catch (Exception ex) {
                logger.error("Error copying Python package {} from {} to {}", depName, sitePackages, targetRoot, ex);
                SwingUtilities.invokeLater(
                        () -> chrome.toolError("Error copying Python package: " + ex.getMessage(), "Python Import"));
            }
            return null;
        });

        return true;
    }

    private void initUi() {
        searchField.setColumns(30);
        searchField.setToolTipText("Type to filter Python packages");
        searchField.putClientProperty("JTextField.placeholderText", "Search");
        add(searchField, BorderLayout.NORTH);

        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.setRowSorter(sorter);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));

        var filesRenderer = new DefaultTableCellRenderer() {
            private final NumberFormat fmt = NumberFormat.getIntegerInstance();

            @Override
            protected void setValue(Object value) {
                if (value instanceof Number n) {
                    setText(fmt.format(n.longValue()));
                } else {
                    setText("");
                }
            }
        };
        filesRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

        var scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        // placeholder row
        model.setRowCount(0);
        model.addRow(new Object[] {"Loading...", null});

        searchField.getDocument().addDocumentListener(new SimpleDocumentListener() {
            @Override
            public void update(DocumentEvent e) {
                var text = requireNonNull(searchField).getText().trim();
                if (text.isEmpty()) {
                    sorter.setRowFilter(null);
                } else {
                    String expr = "(?i)" + Pattern.quote(text);
                    sorter.setRowFilter(RowFilter.regexFilter(expr));
                }
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            var selected = getSelectedPackage();
            for (var l : selectionListeners) l.accept(selected);
        });

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && doubleClickListener != null) {
                    doubleClickListener.run();
                }
            }
        });

        if (table.getColumnModel().getColumnCount() > 1) {
            TableColumn filesColumn = table.getColumnModel().getColumn(1);
            filesColumn.setCellRenderer(filesRenderer);
            int headerWidth = table.getTableHeader()
                    .getDefaultRenderer()
                    .getTableCellRendererComponent(table, filesColumn.getHeaderValue(), false, false, -1, 1)
                    .getPreferredSize()
                    .width;
            filesColumn.setMaxWidth(headerWidth + 20);
        }
    }

    private void loadPackages() {
        chrome.getContextManager().submitBackgroundTask("Scanning Python virtual environments", () -> {
            try {
                var project = chrome.getProject();
                var candidates = Language.PYTHON.getDependencyCandidates(project);
                var rows = new ArrayList<PackageRow>();
                var seen = new LinkedHashSet<String>();

                for (var cand : candidates) {
                    try {
                        var site = resolveSitePackages(cand);
                        if (site == null || !Files.isDirectory(site)) continue;
                        rows.addAll(findPackagesInSitePackages(site, seen));
                    } catch (Exception ex) {
                        logger.debug("Skipping candidate {} due to error: {}", cand, ex.toString());
                    }
                }

                rows.sort((a, b) -> {
                    int n = a.name().compareToIgnoreCase(b.name());
                    if (n != 0) return n;
                    return a.version().compareToIgnoreCase(b.version());
                });

                SwingUtilities.invokeLater(() -> populateTable(rows));
            } catch (Exception ex) {
                logger.warn("Error scanning Python environments", ex);
                SwingUtilities.invokeLater(
                        () -> chrome.toolError("Error scanning Python environments: " + ex.getMessage(), "Scan Error"));
            }
            return null;
        });
    }

    private @Nullable Path resolveSitePackages(Path candidate) {
        try {
            if (!Files.isDirectory(candidate)) return null;

            var fileName = candidate.getFileName();
            var leaf = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);

            // If candidate is already a site-packages directory
            if (leaf.equals("site-packages")) {
                return candidate;
            }

            // Windows: <venv>\Lib\site-packages
            var windowsSite = candidate.resolve("Lib").resolve("site-packages");
            if (Files.isDirectory(windowsSite)) {
                return windowsSite;
            }

            // POSIX: <venv>/lib/pythonX.Y/site-packages
            var libDir = candidate.resolve("lib");
            if (Files.isDirectory(libDir)) {
                try (var stream = Files.list(libDir)) {
                    for (var pythonDir : stream.toList()) {
                        var bn = pythonDir.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (bn.startsWith("python")) {
                            var site = pythonDir.resolve("site-packages");
                            if (Files.isDirectory(site)) {
                                return site;
                            }
                        }
                    }
                }
            }
        } catch (IOException ignore) {
            // ignore
        }
        return null;
    }

    private List<PackageRow> findPackagesInSitePackages(Path sitePackages, Set<String> seen) throws IOException {
        var rows = new ArrayList<PackageRow>();
        try (var stream = Files.list(sitePackages)) {
            for (var p : stream.toList()) {
                var name = p.getFileName().toString();
                if (name.endsWith(".dist-info") || name.endsWith(".egg-info")) {
                    var row = parseDistribution(sitePackages, p);
                    if (row == null) continue;
                    String key = (row.name() + " " + row.version()).toLowerCase(Locale.ROOT);
                    if (!seen.add(key)) continue; // collapse duplicates across venvs
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private @Nullable PackageRow parseDistribution(Path sitePackages, Path distInfoDir) {
        try {
            var meta = readMetadata(distInfoDir);
            if (meta == null) return null;

            var relFiles = enumerateInstalledFiles(sitePackages, distInfoDir, meta.name);
            long count = relFiles.size();

            String display = meta.name + " " + meta.version;
            return new PackageRow(meta.name, meta.version, display, sitePackages, relFiles, count, distInfoDir);
        } catch (Exception ex) {
            logger.debug("Failed to parse distribution at {}: {}", distInfoDir, ex.toString());
            return null;
        }
    }

    private static boolean isAllowedFile(String fileNameLower) {
        if (fileNameLower.endsWith(".py") || fileNameLower.endsWith(".pyi")) return true;
        for (var prefix : DOC_PREFIXES) {
            if (fileNameLower.startsWith(prefix)) return true;
        }
        return false;
    }

    private List<Path> enumerateInstalledFiles(Path sitePackages, Path distInfoDir, String distName)
            throws IOException {
        // Try wheel RECORD first (CSV of files)
        var record = distInfoDir.resolve("RECORD");
        if (Files.exists(record)) {
            var rels = new ArrayList<Path>();
            for (var line : Files.readAllLines(record, UTF_8)) {
                if (line.isEmpty()) continue;
                String pathStr = line.split(",", 2)[0];
                Path rel = Paths.get(pathStr);
                Path abs = rel.isAbsolute() ? rel : sitePackages.resolve(rel).normalize();
                if (!abs.startsWith(sitePackages)) continue;
                if (Files.isDirectory(abs)) continue;
                String nameLower = abs.getFileName().toString().toLowerCase(Locale.ROOT);
                if (isAllowedFile(nameLower)) {
                    rels.add(sitePackages.relativize(abs));
                }
            }
            if (!rels.isEmpty()) return rels;
        }

        // Try egg-info installed-files fallback
        var installedFiles = distInfoDir.resolve("installed-files.txt");
        if (Files.exists(installedFiles)) {
            var rels = new ArrayList<Path>();
            for (var line : Files.readAllLines(installedFiles, UTF_8)) {
                if (line.isBlank()) continue;
                Path rel = Paths.get(line.trim());
                Path abs = rel.isAbsolute() ? rel : sitePackages.resolve(rel).normalize();
                if (!abs.startsWith(sitePackages)) continue;
                if (Files.isDirectory(abs)) continue;
                String nameLower = abs.getFileName().toString().toLowerCase(Locale.ROOT);
                if (isAllowedFile(nameLower)) {
                    rels.add(sitePackages.relativize(abs));
                }
            }
            if (!rels.isEmpty()) return rels;
        }

        // Heuristic fallback: look for a top-level module or package matching normalized name
        var normalized = distName.toLowerCase(Locale.ROOT).replace('-', '_');
        var rels = new ArrayList<Path>();
        var dirCandidate = sitePackages.resolve(normalized);
        var fileCandidate = sitePackages.resolve(normalized + ".py");
        if (Files.isDirectory(dirCandidate)) {
            try (var walk = Files.walk(dirCandidate)) {
                for (var abs : walk.filter(p -> !Files.isDirectory(p)).toList()) {
                    var lower = abs.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (isAllowedFile(lower)) {
                        rels.add(sitePackages.relativize(abs));
                    }
                }
            }
        } else if (Files.exists(fileCandidate)) {
            rels.add(sitePackages.relativize(fileCandidate));
        }

        // Always include METADATA and LICENSE-like files from dist-info if present
        var meta = distInfoDir.resolve("METADATA");
        if (Files.exists(meta)) rels.add(sitePackages.relativize(meta));
        try (var s = Files.list(distInfoDir)) {
            for (var f : s.toList()) {
                var lower = f.getFileName().toString().toLowerCase(Locale.ROOT);
                for (var prefix : DOC_PREFIXES) {
                    if (lower.startsWith(prefix)) {
                        rels.add(sitePackages.relativize(f));
                        break;
                    }
                }
            }
        } catch (IOException ignore) {
            // ignore
        }

        return rels;
    }

    private static @Nullable Meta readMetadata(Path distInfoDir) throws IOException {
        Path meta = Files.exists(distInfoDir.resolve("METADATA"))
                ? distInfoDir.resolve("METADATA")
                : distInfoDir.resolve("PKG-INFO");
        if (!Files.exists(meta)) return null;

        String name = "";
        String version = "";
        try (var reader = Files.newBufferedReader(meta, UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.regionMatches(true, 0, "Name:", 0, 5)) {
                    name = line.substring(5).trim();
                } else if (line.regionMatches(true, 0, "Version:", 0, 8)) {
                    version = line.substring(8).trim();
                }
                if (!name.isEmpty() && !version.isEmpty()) break;
            }
        }
        if (name.isEmpty() || version.isEmpty()) return null;
        return new Meta(name, version);
    }

    private void populateTable(List<PackageRow> rows) {
        displayToPackage.clear();

        model.setRowCount(0);
        for (var r : rows) {
            model.addRow(new Object[] {r.displayName(), r.filesCount()});
            displayToPackage.put(r.displayName(), r);
        }

        sorter.sort();

        if (model.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private void copyFiles(PackageRow row, Path destination) throws IOException {
        var site = row.sitePackages();
        Files.createDirectories(destination);

        for (var rel : row.relativeFiles()) {
            var src = site.resolve(rel);
            if (!Files.exists(src) || Files.isDirectory(src)) continue;
            var dst = destination.resolve(rel);
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private interface SimpleDocumentListener extends javax.swing.event.DocumentListener {
        void update(javax.swing.event.DocumentEvent e);

        @Override
        default void insertUpdate(javax.swing.event.DocumentEvent e) {
            update(e);
        }

        @Override
        default void removeUpdate(javax.swing.event.DocumentEvent e) {
            update(e);
        }

        @Override
        default void changedUpdate(javax.swing.event.DocumentEvent e) {
            update(e);
        }
    }

    private record Meta(String name, String version) {}

    public record PackageRow(
            String name,
            String version,
            String displayName,
            Path sitePackages,
            List<Path> relativeFiles,
            long filesCount,
            Path distInfoDir) {}
}
