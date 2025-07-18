package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.Constants;
import io.github.jbellis.brokk.util.Decompiler;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public class ManageDependenciesDialog extends JDialog {

    public interface DependencyLifecycleListener {
        void dependencyImportStarted(String name);
        void dependencyImportFinished(String name);
    }

    private final Chrome chrome;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final Map<String, ProjectFile> dependencyProjectFileMap = new HashMap<>();
    private final JLabel totalFilesLabel;
    private final JLabel totalLocLabel;
    private final Set<ProjectFile> initialFiles;
    private boolean isUpdatingTotals = false;

    private static class NumberRenderer extends DefaultTableCellRenderer {
        public NumberRenderer() {
            setHorizontalAlignment(RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            if (value instanceof Number) {
                value = String.format("%,d", (Number) value);
            }
            return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        }
    }

    public ManageDependenciesDialog(Chrome chrome) {
        super(chrome.getFrame(), "Manage Dependencies", true);
        this.chrome = chrome;
        this.initialFiles = chrome.getProject().getAllFiles();

        var contentPanel = new JPanel(new BorderLayout());

        Object[] columnNames = {"Enabled", "Name", "Files", "LoC"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Boolean.class;
                if (columnIndex >= 2) return Long.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }
        };

        table = new JTable(tableModel);
        var sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);
        var sortKeys = new ArrayList<RowSorter.SortKey>();
        sortKeys.add(new RowSorter.SortKey(0, SortOrder.DESCENDING)); // Enabled first
        sortKeys.add(new RowSorter.SortKey(1, SortOrder.ASCENDING));  // Then by name
        sorter.setSortKeys(sortKeys);


        table.setDefaultRenderer(Long.class, new NumberRenderer());

        TableColumnModel columnModel = table.getColumnModel();
        columnModel.getColumn(0).setPreferredWidth(60);
        columnModel.getColumn(0).setMaxWidth(80);
        columnModel.getColumn(1).setPreferredWidth(300);
        columnModel.getColumn(2).setPreferredWidth(80);
        columnModel.getColumn(3).setPreferredWidth(100);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);

        var scrollPane = new JScrollPane(table);
        scrollPane.setBorder(new EmptyBorder(Constants.V_GAP, Constants.H_GAP, Constants.V_GAP, Constants.H_GAP));
        contentPanel.add(scrollPane, BorderLayout.CENTER);

        // --- Totals Panel ---
        var totalsPanel = new JPanel();
        totalsPanel.setLayout(new BoxLayout(totalsPanel, BoxLayout.PAGE_AXIS));
        totalsPanel.setBorder(new EmptyBorder(0, Constants.H_GAP, 0, Constants.H_GAP));
        totalFilesLabel = new JLabel("Files in Code Intelligence: 0");
        totalLocLabel = new JLabel("LoC in Code Intelligence: 0");
        totalFilesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        totalLocLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        totalsPanel.add(totalFilesLabel);
        totalsPanel.add(totalLocLabel);

        // --- South Panel: Totals and Buttons ---
        var southContainerPanel = new JPanel(new BorderLayout());
        southContainerPanel.add(totalsPanel, BorderLayout.NORTH);

        var buttonPanel = new JPanel(new BorderLayout());

        var okCancelPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new JButton("OK");
        var cancelButton = new JButton("Cancel");
        okCancelPanel.add(cancelButton);
        okCancelPanel.add(okButton);
        buttonPanel.add(okCancelPanel, BorderLayout.EAST);

        var addRemovePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var addButton = new JButton("+");
        var removeButton = new JButton("-");
        addRemovePanel.add(addButton);
        addRemovePanel.add(removeButton);
        buttonPanel.add(addRemovePanel, BorderLayout.WEST);

        southContainerPanel.add(buttonPanel, BorderLayout.CENTER);
        contentPanel.add(southContainerPanel, BorderLayout.SOUTH);

        contentPanel.setPreferredSize(new Dimension(600, 400));
        add(contentPanel);

        // --- Action Listeners ---
        okButton.addActionListener(e -> {
            saveChanges();
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());

        addButton.addActionListener(e -> {
            var listener = new DependencyLifecycleListener() {
                @Override
                public void dependencyImportStarted(String name) {
                    addPendingDependencyRow(name);
                }

                @Override
                public void dependencyImportFinished(String name) {
                    loadDependencies();
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

        // Re-compute totals whenever data changes or check-boxes toggle
        tableModel.addTableModelListener(e -> {
            if (e.getType() == -1) return; // ignore table structure changes, only care about cell updates
            updateTotals();
        });

        loadDependencies();

        pack();
        setLocationRelativeTo(chrome.getFrame());
    }

    private void addPendingDependencyRow(String name) {
        if (isUpdatingTotals) return; // a bit of a hack to avoid flicker
        tableModel.addRow(new Object[]{true, name, 0L, 0L});
        updateTotals();
    }

    private void loadDependencies() {
        tableModel.setRowCount(0);
        dependencyProjectFileMap.clear();

        var project = chrome.getProject();
        var allDeps = project.getAllOnDiskDependencies();
        Set<ProjectFile> liveDeps = new HashSet<>(project.getLiveDependencies());

        for (var dep : allDeps) {
            String name = dep.getRelPath().getFileName().toString();
            dependencyProjectFileMap.put(name, dep);
            boolean isLive = liveDeps.contains(dep);
            tableModel.addRow(new Object[]{isLive, name, 0L, 0L});
        }
        updateTotals(); // Initial totals calculation

        // count lines in background
        new LineCountingWorker().execute();
    }

    private void saveChanges() {
        var newLiveDependencyTopLevelDirs = new HashSet<Path>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, 0))) {
                String name = (String) tableModel.getValueAt(i, 1);
                var pf = dependencyProjectFileMap.get(name);
                if (pf != null) {
                    // We need the top-level directory of the dependency, not its full relative path
                    // which is like .brokk/dependencies/dep-name. We want just the dep-name part as Path.
                    var depTopLevelDir = chrome.getProject().getMasterRootPathForConfig()
                                               .resolve(".brokk").resolve("dependencies").resolve(pf.getRelPath().getFileName());
                    newLiveDependencyTopLevelDirs.add(depTopLevelDir);
                }
            }
        }
        chrome.getProject().saveLiveDependencies(newLiveDependencyTopLevelDirs);

        var newFiles = chrome.getProject().getAllFiles();

        var addedFiles = new HashSet<>(newFiles);
        addedFiles.removeAll(initialFiles);

        var removedFiles = new HashSet<>(initialFiles);
        removedFiles.removeAll(newFiles);

        var changedFiles = new HashSet<>(addedFiles);
        changedFiles.addAll(removedFiles);

        if (!changedFiles.isEmpty()) {
            chrome.getContextManager().getAnalyzerWrapper().updateFiles(changedFiles);
        }
    }

    /**
     * Recalculate totals for enabled dependencies and update the total labels.
     */
    private void updateTotals() {
        if (isUpdatingTotals) return;
        isUpdatingTotals = true;
        try {
            long totalFiles = 0;
            long totalLoc = 0;

            for (int i = 0; i < tableModel.getRowCount(); i++) {
                if (!Boolean.TRUE.equals(tableModel.getValueAt(i, 0))) continue;

                totalFiles += (Long) tableModel.getValueAt(i, 2);
                totalLoc += (Long) tableModel.getValueAt(i, 3);
            }

            totalFilesLabel.setText(String.format("Files in Code Intelligence: %,d", totalFiles));
            totalLocLabel.setText(String.format("LoC in Code Intelligence: %,d", totalLoc));
        } finally {
            isUpdatingTotals = false;
        }
    }

    private class LineCountingWorker extends SwingWorker<Void, Object[]> {
        @Override
        protected Void doInBackground() {
            int rowCount = tableModel.getRowCount();
            for (int i = 0; i < rowCount; i++) {
                String name = (String) tableModel.getValueAt(i, 1);
                var pf = dependencyProjectFileMap.get(name);
                requireNonNull(pf);

                try (var pathStream = Files.walk(pf.absPath())) {
                    // Collect all regular files first
                    List<Path> files = pathStream.filter(Files::isRegularFile).toList();

                    // count lines in parallel
                    long fileCount = files.size();
                    long lineCount = files.parallelStream().mapToLong(p -> {
                        try (var lines = Files.lines(p)) {
                            return lines.count();
                        } catch (IOException | UncheckedIOException e) {
                            // Ignore unreadable/non-text files
                            return 0;
                        }
                    }).sum();
                    publish(new Object[]{i, fileCount, lineCount});
                } catch (IOException e) {
                    // Could not walk the directory
                    publish(new Object[]{i, 0L, 0L});
                }
            }
            return null;
        }

        @Override
        protected void process(List<Object[]> chunks) {
            for (Object[] chunk : chunks) {
                int row = (int) chunk[0];
                long files = (long) chunk[1];
                long loc = (long) chunk[2];
                tableModel.setValueAt(files, row, 2);
                tableModel.setValueAt(loc, row, 3);
            }
            // Totals will be updated by the TableModelListener
        }
    }

    private void removeSelectedDependency() {
        int selectedRowInView = table.getSelectedRow();
        if (selectedRowInView == -1) {
            return;
        }
        int selectedRowInModel = table.convertRowIndexToModel(selectedRowInView);

        String depName = (String) tableModel.getValueAt(selectedRowInModel, 1);
        int choice = JOptionPane.showConfirmDialog(this,
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
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this,
                                                  "Error deleting dependency '" + depName + "':\n" + ex.getMessage(),
                                                  "Deletion Error",
                                                  JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    public static void show(Chrome chrome) {
        var dialog = new ManageDependenciesDialog(chrome);
        dialog.setVisible(true);
    }
}
