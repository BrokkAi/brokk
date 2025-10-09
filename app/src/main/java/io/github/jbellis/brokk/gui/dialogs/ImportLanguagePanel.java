package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.Language.DependencyCandidate;
import io.github.jbellis.brokk.analyzer.Language.DependencyKind;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dependencies.DependenciesPanel;
import java.awt.BorderLayout;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import javax.swing.table.TableRowSorter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Generic import panel for language dependencies. It shows a single, sortable table: Name | Kind | Files. The
 * {@link Language} provides package discovery and import.
 */
public class ImportLanguagePanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(ImportLanguagePanel.class);

    private final Chrome chrome;
    private final Language language;

    private final JTextField searchField = new JTextField();

    private final DefaultTableModel model = new DefaultTableModel(new Object[] {"Name", "Kind", "Files"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 2 -> Long.class; // Files
                case 1 -> DependencyKind.class; // Kind
                default -> String.class; // Name
            };
        }
    };

    private final JTable table = new JTable(model);
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);

    private final List<Consumer<@Nullable DependencyCandidate>> selectionListeners = new ArrayList<>();
    private @Nullable Runnable doubleClickListener;
    private @Nullable DependenciesPanel.DependencyLifecycleListener lifecycleListener;

    private final List<DependencyCandidate> currentRows = new ArrayList<>();

    public ImportLanguagePanel(Chrome chrome, Language language) {
        super(new BorderLayout(5, 5));
        this.chrome = chrome;
        this.language = language;
        initUi();
        loadPackages();
    }

    public void setLifecycleListener(@Nullable DependenciesPanel.DependencyLifecycleListener lifecycleListener) {
        this.lifecycleListener = lifecycleListener;
    }

    public void addSelectionListener(Consumer<@Nullable DependencyCandidate> listener) {
        selectionListeners.add(listener);
    }

    public void addDoubleClickListener(Runnable onDoubleClick) {
        this.doubleClickListener = onDoubleClick;
    }

    public @Nullable Language.DependencyCandidate getSelectedPackage() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return null;
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= currentRows.size()) return null;
        return currentRows.get(modelRow);
    }

    public boolean initiateImport() {
        var selected = getSelectedPackage();
        if (selected == null) return false;
        return language.importDependency(chrome, selected, lifecycleListener);
    }

    private void initUi() {
        searchField.setColumns(30);
        searchField.setToolTipText("Type to filter dependencies");
        searchField.putClientProperty("JTextField.placeholderText", "Search");
        add(searchField, BorderLayout.NORTH);

        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.setRowSorter(sorter);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));

        // Renderers
        var filesRenderer = new DefaultTableCellRenderer() {
            private final NumberFormat fmt = NumberFormat.getIntegerInstance();

            @Override
            protected void setValue(Object value) {
                if (value instanceof Number n) setText(fmt.format(n.longValue()));
                else setText("");
            }
        };
        filesRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(2).setCellRenderer(filesRenderer);

        var kindRenderer = new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                setText(value instanceof DependencyKind k ? k.name().toLowerCase(Locale.ROOT) : "");
            }
        };
        table.getColumnModel().getColumn(1).setCellRenderer(kindRenderer);

        var scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        // Show "Kind" column only for fine-grained support
        if (language.getDependencyImportSupport() != Language.ImportSupport.FINE_GRAINED) {
            var cm = table.getColumnModel();
            var kindCol = cm.getColumn(1);
            kindCol.setMinWidth(0);
            kindCol.setMaxWidth(0);
            kindCol.setPreferredWidth(0);

            var headerCm = table.getTableHeader().getColumnModel();
            var headerKindCol = headerCm.getColumn(1);
            headerKindCol.setMinWidth(0);
            headerKindCol.setMaxWidth(0);
            headerKindCol.setPreferredWidth(0);
        }

        // placeholder while loading
        model.setRowCount(0);
        model.addRow(new Object[] {"Loading...", null, null});

        searchField.getDocument().addDocumentListener(new SimpleDocumentListener() {
            @Override
            public void update(javax.swing.event.DocumentEvent e) {
                var text = searchField.getText().trim();
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
            var sel = getSelectedPackage();
            for (var l : selectionListeners) l.accept(sel);
        });

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && doubleClickListener != null) {
                    doubleClickListener.run();
                }
            }
        });
    }

    private void loadPackages() {
        chrome.getContextManager().submitBackgroundTask("Discovering " + language.name() + " dependencies", () -> {
            try {
                var pkgs = language.listDependencyPackages(chrome.getProject());
                SwingUtilities.invokeLater(() -> populate(pkgs));
            } catch (Exception ex) {
                logger.warn("Failed to discover {} dependencies", language.name(), ex);
                SwingUtilities.invokeLater(() -> chrome.toolError(
                        "Failed to discover " + language.name() + " dependencies: " + ex.getMessage(), "Scan Error"));
            }
            return null;
        });
    }

    private void populate(List<DependencyCandidate> pkgs) {
        currentRows.clear();
        currentRows.addAll(pkgs);

        model.setRowCount(0);
        for (var p : pkgs) {
            model.addRow(new Object[] {p.displayName(), p.kind(), p.filesCount()});
        }

        sorter.sort();
        adjustFilesColumnWidth();

        if (model.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private void adjustFilesColumnWidth() {
        assert SwingUtilities.isEventDispatchThread();

        var cm = table.getColumnModel();
        var column = cm.getColumn(2);

        var headerRenderer = column.getHeaderRenderer();
        if (headerRenderer == null) {
            headerRenderer = table.getTableHeader().getDefaultRenderer();
        }
        var headerComp =
                headerRenderer.getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, 2);
        int width = headerComp.getPreferredSize().width;

        int rowCount = table.getRowCount();
        for (int row = 0; row < rowCount; row++) {
            var renderer = table.getCellRenderer(row, 2);
            var comp = renderer.getTableCellRendererComponent(table, table.getValueAt(row, 2), false, false, row, 2);
            int pref = comp.getPreferredSize().width;
            if (pref > width) width = pref;
        }

        int margin = table.getIntercellSpacing().width;
        width = width + margin * 2 + 8;

        column.setPreferredWidth(width);
        column.setMaxWidth(width);
    }

    private interface SimpleDocumentListener extends javax.swing.event.DocumentListener {
        void update(javax.swing.event.DocumentEvent e);

        @Override
        default void insertUpdate(DocumentEvent e) {
            update(e);
        }

        @Override
        default void removeUpdate(DocumentEvent e) {
            update(e);
        }

        @Override
        default void changedUpdate(DocumentEvent e) {
            update(e);
        }
    }
}
