package io.github.jbellis.brokk.gui.dependencies;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.BorderUtils;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.Constants;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.dialogs.ImportDependencyDialog;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.util.Decompiler;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import org.jetbrains.annotations.Nullable;

/**
 * Reusable panel for viewing and managing project dependencies. This is a refactoring of the ManageDependenciesDialog
 * content into a JPanel.
 */
public final class DependenciesPanel extends JPanel {

    public static interface DependencyLifecycleListener {
        void dependencyImportStarted(String name);

        void dependencyImportFinished(String name);
    }

    private final Chrome chrome;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final Map<String, ProjectFile> dependencyProjectFileMap = new HashMap<>();
    private final Set<ProjectFile> initialFiles;
    private boolean isProgrammaticChange = false;
    private static final String LOADING = "Loading...";

    private static class NumberRenderer extends DefaultTableCellRenderer {
        public NumberRenderer() {
            setHorizontalAlignment(RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            if (value instanceof Number) {
                value = String.format("%,d", (Number) value);
            }
            return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        }
    }

    private static boolean isTruthyLive(Object v) {
        return v instanceof Boolean b ? b : (v instanceof String s && LOADING.equals(s));
    }

    private static class LiveCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            if (value instanceof Boolean b) {
                var cb = new JCheckBox();
                cb.setSelected(b);
                cb.setHorizontalAlignment(CENTER);
                cb.setOpaque(true);
                if (isSelected) {
                    cb.setBackground(table.getSelectionBackground());
                    cb.setForeground(table.getSelectionForeground());
                } else {
                    cb.setBackground(table.getBackground());
                    cb.setForeground(table.getForeground());
                }
                return cb;
            } else {
                var lbl = new JLabel(LOADING);
                lbl.setHorizontalAlignment(CENTER);
                lbl.setOpaque(isSelected);
                if (isSelected) {
                    lbl.setBackground(table.getSelectionBackground());
                    lbl.setForeground(table.getSelectionForeground());
                }
                return lbl;
            }
        }
    }

    public DependenciesPanel(Chrome chrome) {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Dependencies",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));

        this.chrome = chrome;
        this.initialFiles = chrome.getProject().getAllFiles();

        var contentPanel = new JPanel(new BorderLayout());

        Object[] columnNames = {"Live", "Name", "Files"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Boolean.class;
                if (columnIndex >= 2) return Long.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                if (column != 0) return false;
                Object v = getValueAt(row, 0);
                return v instanceof Boolean;
            }
        };

        table = new JTable(tableModel) {
            @Override
            public @Nullable String getToolTipText(java.awt.event.MouseEvent e) {
                var p = e.getPoint();
                int row = rowAtPoint(p);
                int col = columnAtPoint(p);
                if (row == -1 || col == -1) return null;
                int modelCol = convertColumnIndexToModel(col);
                // Only provide tooltip for the "Name" column (model index 1)
                if (modelCol != 1) return super.getToolTipText(e);

                // Return the same content as shown in the table cell (the Name column)
                Object v = getValueAt(row, col);
                return v != null ? v.toString() : null;
            }
        };
        var sorter = new TableRowSorter<>(tableModel) {
            @Override
            public void toggleSortOrder(int column) {
                var currentKeys = getSortKeys();
                // If this column is already the primary sort column, use the default toggle behavior
                if (!currentKeys.isEmpty() && currentKeys.get(0).getColumn() == column) {
                    super.toggleSortOrder(column);
                    return;
                }
                // For a newly-clicked column, default to DESC for LoC (model column 3), otherwise ASC.
                var defaultOrder = (column == 2) ? SortOrder.DESCENDING : SortOrder.ASCENDING;
                setSortKeys(List.of(new RowSorter.SortKey(column, defaultOrder)));
            }
        };
        table.setRowSorter(sorter);
        var sortKeys = new ArrayList<RowSorter.SortKey>();
        sortKeys.add(new RowSorter.SortKey(0, SortOrder.DESCENDING)); // Enabled first
        // Then by Files (model column 2).
        sortKeys.add(new RowSorter.SortKey(2, SortOrder.DESCENDING));
        sortKeys.add(new RowSorter.SortKey(1, SortOrder.ASCENDING)); // Then by name
        sorter.setSortKeys(sortKeys);

        table.setDefaultRenderer(Long.class, new NumberRenderer());

        TableColumnModel columnModel = table.getColumnModel();
        // Live checkbox column (keep narrow)
        columnModel.getColumn(0).setMaxWidth(columnModel.getColumn(0).getPreferredWidth());
        columnModel.getColumn(0).setCellRenderer(new LiveCellRenderer());
        // Ensure sorting treats "Loading..." as enabled for grouping purposes
        sorter.setComparator(0, (a, b) -> Boolean.compare(isTruthyLive(a), isTruthyLive(b)));
        // Name column width
        columnModel.getColumn(1).setPreferredWidth(200);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);

        var scrollPane = new JScrollPane(table);
        // Ensure no viewport border/inset so the table content can touch the scroll pane border
        scrollPane.setViewportBorder(null);
        // Use the shared focus-aware border so the dependencies table matches the workspace table.
        BorderUtils.addFocusBorder(scrollPane, table);

        // Make the table fill the viewport vertically and remove internal spacing so its edges are flush.
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setBorder(null);

        contentPanel.add(scrollPane, BorderLayout.CENTER);

        // --- South Panel: Buttons (right aligned) ---
        var southContainerPanel = new JPanel(new BorderLayout());

        // Add/Remove on the right
        var addRemovePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, Constants.H_GAP, 0));
        var addButton = new MaterialButton();
        addButton.setIcon(Icons.ADD);
        var removeButton = new MaterialButton();
        removeButton.setIcon(Icons.REMOVE);
        addRemovePanel.add(addButton);
        addRemovePanel.add(removeButton);

        southContainerPanel.add(addRemovePanel, BorderLayout.EAST);
        contentPanel.add(southContainerPanel, BorderLayout.SOUTH);

        // Let the surrounding split pane control the overall height.
        // Make the scroll pane prefer the same size used by the workspace table so behavior matches.
        scrollPane.setPreferredSize(new Dimension(600, 150));
        add(contentPanel, BorderLayout.CENTER);

        // --- Action Listeners ---
        addButton.addActionListener(e -> {
            var listener = new DependencyLifecycleListener() {
                @Override
                public void dependencyImportStarted(String name) {
                    addPendingDependencyRow(name);
                }

                @Override
                public void dependencyImportFinished(String name) {
                    loadDependencies();
                    // Persist changes after a dependency import completes.
                    saveChangesAsync();
                }
            };
            ImportDependencyDialog.show(chrome, listener);
        });

        removeButton.addActionListener(e -> removeSelectedDependency());
        removeButton.setEnabled(false);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                removeButton.setEnabled(table.getSelectedRow() != -1);
            }
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showTablePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showTablePopup(e);
            }
        });

        // Re-compute totals whenever data changes or check-boxes toggle.
        // Also persist changes when the enabled checkbox (column 0) is toggled.
        tableModel.addTableModelListener(e -> {
            // Ignore header/structure change events
            if (e.getFirstRow() == TableModelEvent.HEADER_ROW) return;
            if (isProgrammaticChange) return;

            if (e.getColumn() == 0) {
                int first = e.getFirstRow();
                int last = e.getLastRow();
                for (int row = first; row <= last; row++) {
                    Object v = tableModel.getValueAt(row, 0);
                    if (v instanceof Boolean bool) {
                        String depName = (String) tableModel.getValueAt(row, 1);
                        boolean prev = !bool;

                        // Show "Loading..." while saving the change only when enabling (checking).
                        if (bool) {
                            isProgrammaticChange = true;
                            tableModel.setValueAt(LOADING, row, 0);
                            isProgrammaticChange = false;
                        }

                        final int rowIndex = row;
                        final boolean newVal = bool;
                        final boolean prevVal = prev;
                        saveChangesAsync(Map.of(depName, Boolean.valueOf(bool)))
                                .whenComplete((r, ex) -> SwingUtilities.invokeLater(() -> {
                                    isProgrammaticChange = true;
                                    if (ex != null) {
                                        JOptionPane.showMessageDialog(
                                                DependenciesPanel.this,
                                                "Failed to save dependency changes:\n" + ex.getMessage(),
                                                "Error Saving Dependencies",
                                                JOptionPane.ERROR_MESSAGE);
                                        tableModel.setValueAt(prevVal, rowIndex, 0);
                                    } else {
                                        tableModel.setValueAt(newVal, rowIndex, 0);
                                    }
                                    isProgrammaticChange = false;
                                }));
                    }
                }
            }
        });

        loadDependencies();
    }

    private void addPendingDependencyRow(String name) {
        tableModel.addRow(new Object[] {true, name, 0L});
    }

    public void loadDependencies() {
        isProgrammaticChange = true;
        tableModel.setRowCount(0);
        dependencyProjectFileMap.clear();

        var project = chrome.getProject();
        var allDeps = project.getAllOnDiskDependencies();
        Set<IProject.Dependency> liveDeps = new HashSet<>(project.getLiveDependencies());

        for (var dep : allDeps) {
            String name = dep.getRelPath().getFileName().toString();
            dependencyProjectFileMap.put(name, dep);
            boolean isLive = liveDeps.stream().anyMatch(d -> d.root().equals(dep));
            tableModel.addRow(new Object[] {isLive, name, 0L});
        }
        isProgrammaticChange = false;

        // count files in background
        new FileCountingWorker().execute();
    }

    public CompletableFuture<Void> saveChangesAsync() {
        return saveChangesAsync(Map.of());
    }

    private CompletableFuture<Void> saveChangesAsync(Map<String, Boolean> overridesByName) {
        // Snapshot the desired live set on the EDT to avoid accessing Swing model off-thread
        var newLiveDependencyTopLevelDirs = new HashSet<Path>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String name = (String) tableModel.getValueAt(i, 1);
            boolean isLive = overridesByName.getOrDefault(name, Boolean.TRUE.equals(tableModel.getValueAt(i, 0)));
            if (!isLive) continue;

            var pf = dependencyProjectFileMap.get(name);
            if (pf != null) {
                var depTopLevelDir = chrome.getProject()
                        .getMasterRootPathForConfig()
                        .resolve(".brokk")
                        .resolve("dependencies")
                        .resolve(pf.getRelPath().getFileName());
                newLiveDependencyTopLevelDirs.add(depTopLevelDir);
            }
        }

        var cm = chrome.getContextManager();
        return cm.submitBackgroundTask("Save dependency configuration", () -> {
            var project = chrome.getProject();
            project.saveLiveDependencies(newLiveDependencyTopLevelDirs);

            var newFiles = project.getAllFiles();

            var addedFiles = new HashSet<>(newFiles);
            addedFiles.removeAll(initialFiles);

            var removedFiles = new HashSet<>(initialFiles);
            removedFiles.removeAll(newFiles);

            var changedFiles = new HashSet<>(addedFiles);
            changedFiles.addAll(removedFiles);

            if (!changedFiles.isEmpty()) {
                cm.getAnalyzerWrapper().updateFiles(changedFiles);
            }
        });
    }

    private class FileCountingWorker extends SwingWorker<Void, Object[]> {
        @Override
        protected Void doInBackground() {
            int rowCount = tableModel.getRowCount();
            for (int i = 0; i < rowCount; i++) {
                String name = (String) tableModel.getValueAt(i, 1);
                var pf = dependencyProjectFileMap.get(name);
                requireNonNull(pf);

                try (var pathStream = Files.walk(pf.absPath())) {
                    long fileCount = pathStream.filter(Files::isRegularFile).count();
                    publish(new Object[] {i, fileCount});
                } catch (IOException e) {
                    publish(new Object[] {i, 0L});
                }
            }
            return null;
        }

        @Override
        protected void process(List<Object[]> chunks) {
            isProgrammaticChange = true;
            try {
                for (Object[] chunk : chunks) {
                    int row = (int) chunk[0];
                    long files = (long) chunk[1];
                    tableModel.setValueAt(files, row, 2);
                }
            } finally {
                isProgrammaticChange = false;
            }
        }
    }

    private void showTablePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int viewRow = table.rowAtPoint(e.getPoint());
        if (viewRow < 0) {
            return;
        }
        int viewCol = table.columnAtPoint(e.getPoint());
        if (viewCol < 0) {
            return;
        }

        table.setRowSelectionInterval(viewRow, viewRow);
        int modelRow = table.convertRowIndexToModel(viewRow);

        boolean isLive = Boolean.TRUE.equals(tableModel.getValueAt(modelRow, 0));

        var menu = new JPopupMenu();
        var summarizeItem = new JMenuItem("Summarize All Files");
        summarizeItem.setEnabled(isLive);
        summarizeItem.addActionListener(ev -> summarizeDependencyForRow(modelRow));
        menu.add(summarizeItem);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void summarizeDependencyForRow(int modelRow) {
        Object nameObj = tableModel.getValueAt(modelRow, 1);
        if (!(nameObj instanceof String depName)) {
            return;
        }
        var pf = dependencyProjectFileMap.get(depName);
        if (pf == null) {
            return;
        }
        // Only allow summarizing for Live dependencies
        if (!Boolean.TRUE.equals(tableModel.getValueAt(modelRow, 0))) {
            return;
        }

        var project = chrome.getProject();
        var depOpt = project.getLiveDependencies().stream()
                .filter(d -> d.root().equals(pf))
                .findFirst();
        if (depOpt.isEmpty()) {
            return;
        }
        var dep = depOpt.get();

        var cm = chrome.getContextManager();
        cm.submitContextTask("Summarize files for " + depName, () -> {
            cm.addSummaries(dep.files(), Set.of());
        });
    }

    private void removeSelectedDependency() {
        int selectedRowInView = table.getSelectedRow();
        if (selectedRowInView == -1) {
            return;
        }
        int selectedRowInModel = table.convertRowIndexToModel(selectedRowInView);

        String depName = (String) tableModel.getValueAt(selectedRowInModel, 1);
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete the dependency '" + depName + "'?\nThis action cannot be undone.",
                "Confirm Deletion",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (choice == JOptionPane.YES_OPTION) {
            var pf = dependencyProjectFileMap.get(depName);
            if (pf != null) {
                try {
                    Decompiler.deleteDirectoryRecursive(pf.absPath());
                    loadDependencies();
                    // Persist changes after successful deletion and reload.
                    saveChangesAsync();
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(
                            this,
                            "Error deleting dependency '" + depName + "':\n" + ex.getMessage(),
                            "Deletion Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }
}
