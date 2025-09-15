package io.github.jbellis.brokk.gui.dialogs;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dependencies.DependenciesPanel;
import io.github.jbellis.brokk.util.Decompiler;
import java.awt.BorderLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
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

public class ImportJavaPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(ImportJavaPanel.class);

    private final Chrome chrome;

    private JTextField searchField;
    private JTable table;
    private DefaultTableModel model;
    private TableRowSorter<DefaultTableModel> sorter;

    private Map<String, Path> nameToPath = Map.of();
    private final List<Consumer<@Nullable Path>> selectionListeners = new ArrayList<>();
    private @Nullable Runnable doubleClickListener;
    private @Nullable DependenciesPanel.DependencyLifecycleListener lifecycleListener;

    public ImportJavaPanel(Chrome chrome) {
        super(new BorderLayout(5, 5));
        this.chrome = chrome;
        initUi();
        loadJarCandidates();
    }

    public void setLifecycleListener(@Nullable DependenciesPanel.DependencyLifecycleListener lifecycleListener) {
        this.lifecycleListener = lifecycleListener;
    }

    public void addSelectionListener(Consumer<@Nullable Path> listener) {
        selectionListeners.add(listener);
    }

    public void addDoubleClickListener(Runnable onDoubleClick) {
        this.doubleClickListener = onDoubleClick;
    }

    public @Nullable Path getSelectedJar() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return null;
        int modelRow = table.convertRowIndexToModel(viewRow);
        String displayName = (String) model.getValueAt(modelRow, 0);
        return nameToPath.get(displayName);
    }

    private void initUi() {
        searchField = new JTextField();
        searchField.setColumns(30);
        searchField.setToolTipText("Type to filter JARs");
        searchField.putClientProperty("JTextField.placeholderText", "Search");
        add(searchField, BorderLayout.NORTH);

        model = new DefaultTableModel(new Object[] {"Name", "Files"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 1 ? Long.class : String.class;
            }
        };

        table = new JTable(model);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);

        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));

        if (table.getColumnModel().getColumnCount() > 1) {
            var filesColumn = table.getColumnModel().getColumn(1);
            int headerWidth = table.getTableHeader()
                    .getDefaultRenderer()
                    .getTableCellRendererComponent(table, filesColumn.getHeaderValue(), false, false, -1, 1)
                    .getPreferredSize()
                    .width;
            filesColumn.setMaxWidth(headerWidth + 20);
            var rightAlign = new DefaultTableCellRenderer();
            rightAlign.setHorizontalAlignment(SwingConstants.RIGHT);
            filesColumn.setCellRenderer(rightAlign);
        }

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
            Path selected = getSelectedJar();
            for (var l : selectionListeners) {
                l.accept(selected);
            }
        });

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && doubleClickListener != null) {
                    doubleClickListener.run();
                }
            }
        });

        var scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
    }

    public boolean initiateImport() {
        var sourcePath = getSelectedJar();
        if (sourcePath == null) {
            return false;
        }

        var depName = sourcePath.getFileName().toString();

        @Nullable final DependenciesPanel.DependencyLifecycleListener currentListener = lifecycleListener;
        if (currentListener != null) {
            SwingUtilities.invokeLater(() -> currentListener.dependencyImportStarted(depName));
        }

        Decompiler.decompileJar(
                chrome,
                sourcePath,
                chrome.getContextManager()::submitBackgroundTask,
                () -> SwingUtilities.invokeLater(() -> {
                    if (currentListener != null) currentListener.dependencyImportFinished(depName);
                }));
        return true;
    }

    private void loadJarCandidates() {
        chrome.getContextManager().submitBackgroundTask("Scanning for JAR files", () -> {
            try {
                var paths = Language.JAVA.getDependencyCandidates(chrome.getProject());
                SwingUtilities.invokeLater(() -> populateTable(paths));
            } catch (Exception ex) {
                logger.warn("Error scanning for JAR files", ex);
                SwingUtilities.invokeLater(
                        () -> chrome.toolError("Error scanning for JAR files: " + ex.getMessage(), "Scan Error"));
            }
            return null;
        });
    }

    private void populateTable(List<Path> paths) {
        chrome.getContextManager().submitBackgroundTask("Preparing JAR list", () -> {
            var byFilename = new LinkedHashMap<String, Path>();
            for (var p : paths) {
                var filename = p.getFileName().toString();
                byFilename.putIfAbsent(filename, p);
            }

            var displayToPath = new LinkedHashMap<String, Path>();
            for (var p : byFilename.values()) {
                displayToPath.put(prettyJarName(p), p);
            }

            var counts = new LinkedHashMap<String, Long>();
            for (var e : displayToPath.entrySet()) {
                counts.put(e.getKey(), countJarFiles(e.getValue()));
            }

            SwingUtilities.invokeLater(() -> {
                this.nameToPath = displayToPath;

                model.setRowCount(0);
                for (var e : displayToPath.entrySet()) {
                    var name = e.getKey();
                    var files = counts.getOrDefault(name, 0L);
                    model.addRow(new Object[] {name, files});
                }

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

                if (table.getColumnModel().getColumnCount() > 1) {
                    TableColumn filesColumn = table.getColumnModel().getColumn(1);
                    filesColumn.setCellRenderer(filesRenderer);
                    int pref = filesColumn.getPreferredWidth();
                    filesColumn.setMaxWidth(pref);
                }

                sorter.sort();
                if (model.getRowCount() > 0) {
                    table.setRowSelectionInterval(0, 0);
                }
            });
            return null;
        });
    }

    private static String prettyJarName(Path jarPath) {
        var fileName = jarPath.getFileName().toString();
        int dot = fileName.toLowerCase(Locale.ROOT).lastIndexOf(".jar");
        return (dot > 0) ? fileName.substring(0, dot) : fileName;
    }

    private static long countJarFiles(Path jarPath) {
        try (var zip = new ZipFile(jarPath.toFile())) {
            return zip.stream()
                    .filter(e -> !e.isDirectory())
                    .map(e -> e.getName().toLowerCase(Locale.ROOT))
                    .filter(name -> name.endsWith(".class") || name.endsWith(".java"))
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
}
