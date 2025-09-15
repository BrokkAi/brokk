package io.github.jbellis.brokk.gui.dialogs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dependencies.DependenciesPanel;
import io.github.jbellis.brokk.util.FileUtil;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
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

public class ImportRustPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(ImportRustPanel.class);

    private final Chrome chrome;
    private final Path dependenciesRoot;

    private final JTextField searchField = new JTextField();

    // Simpler columns: Name (name + version), Kind, Files
    private final DefaultTableModel directModel = new DefaultTableModel(new Object[] {"Name", "Kind", "Files"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 2 ? Long.class : String.class;
        }
    };
    private final DefaultTableModel transitiveModel = new DefaultTableModel(new Object[] {"Name", "Files"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 1 ? Long.class : String.class;
        }
    };

    private final JTable directTable = new JTable(directModel);
    private final JTable transitiveTable = new JTable(transitiveModel);
    private final TableRowSorter<DefaultTableModel> directSorter = new TableRowSorter<>(directModel);
    private final TableRowSorter<DefaultTableModel> transitiveSorter = new TableRowSorter<>(transitiveModel);

    private final List<Consumer<@Nullable PackageRow>> selectionListeners = new ArrayList<>();
    private @Nullable Runnable doubleClickListener;
    private @Nullable DependenciesPanel.DependencyLifecycleListener lifecycleListener;

    // Resolved data
    private final Map<String, PackageRow> displayToPkgDirect = new LinkedHashMap<>();
    private final Map<String, PackageRow> displayToPkgTransitive = new LinkedHashMap<>();

    public ImportRustPanel(Chrome chrome) {
        super(new BorderLayout(5, 5));
        this.chrome = chrome;
        this.dependenciesRoot = chrome.getProject()
                .getRoot()
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.DEPENDENCIES_DIR);

        initUi();
        loadMetadata();
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
        int dView = directTable.getSelectedRow();
        if (dView >= 0) {
            int m = directTable.convertRowIndexToModel(dView);
            String key = (String) directModel.getValueAt(m, 0);
            return displayToPkgDirect.get(key);
        }
        int tView = transitiveTable.getSelectedRow();
        if (tView >= 0) {
            int m = transitiveTable.convertRowIndexToModel(tView);
            String key = (String) transitiveModel.getValueAt(m, 0);
            return displayToPkgTransitive.get(key);
        }
        return null;
    }

    public boolean initiateImport() {
        var selected = getSelectedPackage();
        if (selected == null) return false;

        var depName = selected.name() + "-" + selected.version();

        @Nullable final DependenciesPanel.DependencyLifecycleListener currentListener = lifecycleListener;
        if (currentListener != null) {
            SwingUtilities.invokeLater(() -> currentListener.dependencyImportStarted(depName));
        }

        var manifestPath = selected.manifestPath();
        if (manifestPath == null || !Files.exists(manifestPath)) {
            SwingUtilities.invokeLater(() -> {
                chrome.toolError(
                        "Could not locate crate sources in local Cargo cache for " + depName
                                + ".\nPlease run 'cargo build' in your project, then retry.",
                        "Rust Import");
            });
            return false;
        }

        var sourceRoot = requireNonNull(requireNonNull(manifestPath).getParent());
        var targetRoot = dependenciesRoot.resolve(depName);

        chrome.getContextManager().submitBackgroundTask("Copying Rust crate: " + depName, () -> {
            try {
                Files.createDirectories(targetRoot.getParent());
                if (Files.exists(targetRoot)) {
                    if (!FileUtil.deleteRecursively(targetRoot)) {
                        throw new IOException("Failed to delete existing destination: " + targetRoot);
                    }
                }
                copyRustCrate(sourceRoot, targetRoot);
                SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput(
                            "Rust crate copied to " + targetRoot + ". Reopen project to incorporate the new files.");
                    if (currentListener != null) currentListener.dependencyImportFinished(depName);
                });
            } catch (Exception ex) {
                logger.error("Error copying Rust crate {} from {} to {}", depName, sourceRoot, targetRoot, ex);
                SwingUtilities.invokeLater(
                        () -> chrome.toolError("Error copying Rust crate: " + ex.getMessage(), "Rust Import"));
            }
            return null;
        });

        return true;
    }

    private void initUi() {
        searchField.setColumns(30);
        searchField.setToolTipText("Type to filter Rust crates");
        searchField.putClientProperty("JTextField.placeholderText", "Search");
        add(searchField, BorderLayout.NORTH);

        directTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        transitiveTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);

        directTable.setRowSorter(directSorter);
        transitiveTable.setRowSorter(transitiveSorter);
        directSorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        transitiveSorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));

        var tablesPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        var directScroll = new JScrollPane(directTable);
        directScroll.setBorder(BorderFactory.createTitledBorder("Direct Dependencies (normal/build/dev)"));
        var transitiveScroll = new JScrollPane(transitiveTable);
        transitiveScroll.setBorder(BorderFactory.createTitledBorder("Transitive Dependencies"));
        tablesPanel.add(directScroll);
        tablesPanel.add(transitiveScroll);

        add(tablesPanel, BorderLayout.CENTER);

        searchField.getDocument().addDocumentListener(new SimpleDocumentListener() {
            @Override
            public void update(DocumentEvent e) {
                var text = requireNonNull(searchField).getText().trim();
                if (text.isEmpty()) {
                    directSorter.setRowFilter(null);
                    transitiveSorter.setRowFilter(null);
                } else {
                    String expr = "(?i)" + Pattern.quote(text);
                    RowFilter<DefaultTableModel, Integer> rf = RowFilter.regexFilter(expr);
                    directSorter.setRowFilter(rf);
                    transitiveSorter.setRowFilter(rf);
                }
            }
        });

        directTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            if (directTable.getSelectedRow() >= 0) {
                transitiveTable.clearSelection();
            }
            var selected = getSelectedPackage();
            for (var l : selectionListeners) l.accept(selected);
        });
        transitiveTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            if (transitiveTable.getSelectedRow() >= 0) {
                directTable.clearSelection();
            }
            var selected = getSelectedPackage();
            for (var l : selectionListeners) l.accept(selected);
        });

        var mouseListener = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && doubleClickListener != null) {
                    doubleClickListener.run();
                }
            }
        };
        directTable.addMouseListener(mouseListener);
        transitiveTable.addMouseListener(mouseListener);

        // Initial placeholder
        directModel.setRowCount(0);
        directModel.addRow(new Object[] {"Loading...", "", 0L});
        transitiveModel.setRowCount(0);
        transitiveModel.addRow(new Object[] {"Loading...", 0L});
    }

    private void loadMetadata() {
        chrome.getContextManager().submitBackgroundTask("Reading cargo metadata", () -> {
            try {
                var meta = runCargoMetadata();
                SwingUtilities.invokeLater(() -> populateTables(meta));
            } catch (Exception ex) {
                logger.warn("Failed to read cargo metadata", ex);
                SwingUtilities.invokeLater(
                        () -> chrome.toolError("Failed to read cargo metadata: " + ex.getMessage(), "Rust Import"));
            }
            return null;
        });
    }

    private void populateTables(CargoMetadata meta) {
        displayToPkgDirect.clear();
        displayToPkgTransitive.clear();

        // Index packages
        var idToPkg = new LinkedHashMap<String, CargoPackage>();
        var nameToPkgs = new LinkedHashMap<String, List<CargoPackage>>();
        for (var p : meta.packages) {
            idToPkg.put(p.id, p);
            nameToPkgs.computeIfAbsent(p.name, k -> new ArrayList<>()).add(p);
        }

        var workspaceIds = new LinkedHashSet<>(meta.workspace_members);

        // Determine direct dependencies by declared deps on workspace members, include all kinds
        var directByNameKinds = new LinkedHashMap<String, Set<String>>();
        for (var wsId : workspaceIds) {
            var ws = idToPkg.get(wsId);
            if (ws == null) continue;
            for (var dep : ws.dependencies) {
                var kind = dep.kind == null ? "normal" : dep.kind;
                directByNameKinds
                        .computeIfAbsent(dep.name, k -> new LinkedHashSet<>())
                        .add(kind);
            }
        }

        // Build direct rows by mapping names to actual packages[] with that name (excluding workspace members)
        var directRows = new ArrayList<PackageRow>();
        for (var e : directByNameKinds.entrySet()) {
            var name = e.getKey();
            var kinds = String.join(",", e.getValue());
            var pkgs = nameToPkgs.getOrDefault(name, List.of());
            var candidates =
                    pkgs.stream().filter(p -> !workspaceIds.contains(p.id)).toList();
            if (candidates.isEmpty()) continue;

            // Choose highest version lexicographically as a simple heuristic
            var chosen = candidates.stream()
                    .max(Comparator.comparing(p -> p.version))
                    .orElse(candidates.get(0));

            var path = chosen.manifest_path == null ? null : Paths.get(chosen.manifest_path);
            long files = countRustFiles(path);
            var row = new PackageRow(chosen.name, chosen.version, kinds, normalizeSource(chosen.source), path, files);
            directRows.add(row);
        }
        directRows.sort(Comparator.comparing(PackageRow::name).thenComparing(PackageRow::version));

        // Transitive = all non-workspace, excluding ones already in directRows
        var directNames = new LinkedHashSet<String>();
        for (var r : directRows) directNames.add(r.name());
        var transitiveRows = new ArrayList<PackageRow>();
        for (var p : meta.packages) {
            if (workspaceIds.contains(p.id)) continue;
            if (directNames.contains(p.name)) continue;
            var path = p.manifest_path == null ? null : Paths.get(p.manifest_path);
            long files = countRustFiles(path);
            transitiveRows.add(new PackageRow(p.name, p.version, "", normalizeSource(p.source), path, files));
        }
        // Deduplicate by name-version (keep first occurrence)
        var seen = new LinkedHashSet<String>();
        transitiveRows.removeIf(r -> !seen.add(r.name() + ":" + r.version()));
        transitiveRows.sort(Comparator.comparing(PackageRow::name).thenComparing(PackageRow::version));

        // Populate models
        directModel.setRowCount(0);
        for (var r : directRows) {
            directModel.addRow(new Object[] {displayKey(r), r.kinds(), r.filesCount()});
            displayToPkgDirect.put(displayKey(r), r);
        }

        transitiveModel.setRowCount(0);
        for (var r : transitiveRows) {
            transitiveModel.addRow(new Object[] {displayKey(r), r.filesCount()});
            displayToPkgTransitive.put(displayKey(r), r);
        }

        // Render files column similar to Java panel
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

        if (directTable.getColumnModel().getColumnCount() > 2) {
            TableColumn filesColumn = directTable.getColumnModel().getColumn(2);
            filesColumn.setCellRenderer(filesRenderer);
            int headerWidth = directTable
                    .getTableHeader()
                    .getDefaultRenderer()
                    .getTableCellRendererComponent(directTable, filesColumn.getHeaderValue(), false, false, -1, 2)
                    .getPreferredSize()
                    .width;
            filesColumn.setMaxWidth(headerWidth + 20);
        }
        if (transitiveTable.getColumnModel().getColumnCount() > 1) {
            TableColumn filesColumn = transitiveTable.getColumnModel().getColumn(1);
            filesColumn.setCellRenderer(filesRenderer);
            int headerWidth = transitiveTable
                    .getTableHeader()
                    .getDefaultRenderer()
                    .getTableCellRendererComponent(transitiveTable, filesColumn.getHeaderValue(), false, false, -1, 1)
                    .getPreferredSize()
                    .width;
            filesColumn.setMaxWidth(headerWidth + 20);
        }

        directSorter.sort();
        transitiveSorter.sort();

        if (directModel.getRowCount() > 0) {
            directTable.setRowSelectionInterval(0, 0);
        } else if (transitiveModel.getRowCount() > 0) {
            transitiveTable.setRowSelectionInterval(0, 0);
        }
    }

    private static String displayKey(PackageRow r) {
        return r.name() + " " + r.version();
    }

    private static String normalizeSource(@Nullable String source) {
        if (source == null) return "path";
        if (source.startsWith("registry+")) return "registry";
        if (source.startsWith("git+")) return "git";
        return source;
    }

    private CargoMetadata runCargoMetadata() throws IOException, InterruptedException {
        var projectRoot = chrome.getProject().getRoot().toFile();

        var pb = new ProcessBuilder("cargo", "metadata", "--format-version", "1");
        pb.directory(projectRoot);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        var process = pb.start();
        var stdout = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line).append('\n');
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("cargo metadata failed with exit code " + exit);
        }

        var mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(stdout.toString(), CargoMetadata.class);
    }

    private static void copyRustCrate(Path source, Path destination) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    var rel = source.relativize(src);
                    if (rel.toString().startsWith("target")) return; // skip build artifacts
                    var dst = destination.resolve(rel);
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        String name = src.getFileName().toString().toLowerCase(Locale.ROOT);
                        boolean isRs = name.endsWith(".rs");
                        boolean isManifest = name.equals("cargo.toml") || name.equals("cargo.lock");
                        boolean isDoc =
                                name.startsWith("readme") || name.startsWith("license") || name.startsWith("copying");
                        if (isRs || isManifest || isDoc) {
                            Files.createDirectories(dst.getParent());
                            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static long countRustFiles(@Nullable Path manifestPath) {
        if (manifestPath == null) return 0L;
        var root = manifestPath.getParent();
        if (root == null) return 0L;
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> !Files.isDirectory(p))
                    .map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    .filter(name -> name.endsWith(".rs")
                            || name.equals("cargo.toml")
                            || name.equals("cargo.lock")
                            || name.startsWith("readme")
                            || name.startsWith("license")
                            || name.startsWith("copying"))
                    .count();
        } catch (IOException e) {
            return 0L;
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

    // Data model

    public record PackageRow(
            String name, String version, String kinds, String source, @Nullable Path manifestPath, long filesCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CargoMetadata {
        public List<CargoPackage> packages = List.of();
        public List<String> workspace_members = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CargoPackage {
        public String id = "";
        public String name = "";
        public String version = "";
        public @Nullable String manifest_path;
        public @Nullable String source;
        public List<Dependency> dependencies = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Dependency {
        public String name = "";
        public @Nullable String kind;
    }
}
