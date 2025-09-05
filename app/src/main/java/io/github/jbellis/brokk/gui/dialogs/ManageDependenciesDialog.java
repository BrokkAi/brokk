package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.dependencies.IExternalDependency;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.Constants;
import io.github.jbellis.brokk.util.Decompiler;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;

public class ManageDependenciesDialog extends JDialog {

    public interface DependencyLifecycleListener {
        void dependencyImportStarted(String name);

        void dependencyImportFinished(String name);
    }

    private final Chrome chrome;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final Map<String, ProjectFile> dependencyProjectFileMap = new HashMap<>();
    private final Map<String, IExternalDependency> dependencyCandidateMap = new HashMap<>();
    private final JLabel totalFilesLabel;
    private final JLabel totalLocLabel;
    private final Set<ProjectFile> initialFiles;
    private boolean isUpdatingTotals = false;

    // Track candidates that are currently being imported to prevent duplicate actions
    private final Set<String> dependenciesBeingImported = new HashSet<>();

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

    public ManageDependenciesDialog(Chrome chrome) {
        super(chrome.getFrame(), "Manage Dependencies", true);
        this.chrome = chrome;
        this.initialFiles = chrome.getProject().getAllFiles();

        var contentPanel = new JPanel(new BorderLayout());

        Object[] columnNames = {"Enabled", "Name", "Source", "Files", "LoC"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Boolean.class;
                if (columnIndex == 3 || columnIndex == 4) return Long.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                if (column != 0) return false;
                Object nameObj = getValueAt(row, 1);
                String name = (nameObj instanceof String s) ? s : null;
                // Disable editing while this dependency is being imported
                return name == null || !dependenciesBeingImported.contains(name);
            }
        };

        table = new JTable(tableModel);
        var sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);
        var sortKeys = new ArrayList<RowSorter.SortKey>();
        sortKeys.add(new RowSorter.SortKey(0, SortOrder.DESCENDING)); // Enabled first
        sortKeys.add(new RowSorter.SortKey(1, SortOrder.ASCENDING)); // Then by name
        sorter.setSortKeys(sortKeys);

        table.setDefaultRenderer(Long.class, new NumberRenderer());

        TableColumnModel columnModel = table.getColumnModel();
        columnModel.getColumn(0).setPreferredWidth(60);
        columnModel.getColumn(0).setMaxWidth(80);
        columnModel.getColumn(1).setPreferredWidth(300);
        columnModel.getColumn(2).setPreferredWidth(160); // Source
        columnModel.getColumn(3).setPreferredWidth(80); // Files
        columnModel.getColumn(4).setPreferredWidth(100); // LoC
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

        // Honor platform-specific button order
        var order = UIManager.getString("OptionPane.buttonOrder");
        if (order == null) order = "OC"; // sensible fallback

        for (int i = 0; i < order.length(); i++) {
            char ch = order.charAt(i);
            switch (ch) {
                case 'O' -> okCancelPanel.add(okButton);
                case 'C' -> okCancelPanel.add(cancelButton);
            }
        }
        // Ensure both buttons are present even if the LAF string missed one
        if (okButton.getParent() == null) okCancelPanel.add(okButton);
        if (cancelButton.getParent() == null) okCancelPanel.add(cancelButton);

        // Make OK the default button for Enter key activation
        getRootPane().setDefaultButton(okButton);

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

            // Trigger import when a candidate is enabled
            if (e.getColumn() == 0 && e.getFirstRow() >= 0) {
                int row = e.getFirstRow();
                Object val = tableModel.getValueAt(row, 0);
                if (Boolean.TRUE.equals(val)) {
                    String name = (String) tableModel.getValueAt(row, 1);
                    IExternalDependency candidate = dependencyCandidateMap.get(name);
                    if (candidate != null && dependenciesBeingImported.add(name)) {
                        // Disable checkbox while importing
                        tableModel.fireTableRowsUpdated(row, row);

                        if (candidate.importStrategy() == IExternalDependency.ImportStrategy.DECOMPILE) {
                            Decompiler.decompileJar(
                                    chrome,
                                    candidate.sourcePath(),
                                    chrome.getContextManager()::submitBackgroundTask,
                                    () -> SwingUtilities.invokeLater(this::loadDependencies));
                        } else {
                            // Other strategies (e.g., COPY_DIRECTORY) can be implemented later
                        }
                    }
                }
            }

            updateTotals();
        });

        loadDependencies();

        // Discover external dependency candidates asynchronously
        new CandidateDiscoveryWorker().execute();

        pack();
        setLocationRelativeTo(chrome.getFrame());
    }

    private void addPendingDependencyRow(String name) {
        if (isUpdatingTotals) return; // a bit of a hack to avoid flicker
        tableModel.addRow(new Object[] {true, name, "Imported", 0L, 0L});
        updateTotals();
    }

    private void loadDependencies() {
        // Reset importing state when reloading dependencies
        dependenciesBeingImported.clear();

        tableModel.setRowCount(0);
        dependencyProjectFileMap.clear();

        var project = chrome.getProject();
        var allDeps = project.getAllOnDiskDependencies();
        Set<ProjectFile> liveDeps = new HashSet<>(project.getLiveDependencies());

        // Track imported dependency top-level directory names for filtering candidates
        var importedNames = new HashSet<String>();
        for (var dep : allDeps) {
            String name = dep.getRelPath().getFileName().toString();
            importedNames.add(name);
            dependencyProjectFileMap.put(name, dep);
            boolean isLive = liveDeps.contains(dep);
            tableModel.addRow(new Object[] {isLive, name, "Imported", 0L, 0L});
        }

        // Re-add any auto-discovered candidates that are not yet imported
        for (var entry : dependencyCandidateMap.entrySet()) {
            String displayName = entry.getKey();
            IExternalDependency candidate = entry.getValue();
            // Skip candidates whose sanitized import name is already present on disk
            if (importedNames.contains(candidate.sanitizedImportName())) {
                continue;
            }
            tableModel.addRow(new Object[] {false, displayName, candidate.sourceSystem(), 0L, 0L});
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
                    var depTopLevelDir = chrome.getProject()
                            .getMasterRootPathForConfig()
                            .resolve(".brokk")
                            .resolve("dependencies")
                            .resolve(pf.getRelPath().getFileName());
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

    /** Recalculate totals for enabled dependencies and update the total labels. */
    private void updateTotals() {
        if (isUpdatingTotals) return;
        isUpdatingTotals = true;
        try {
            long totalFiles = 0;
            long totalLoc = 0;

            for (int i = 0; i < tableModel.getRowCount(); i++) {
                if (!Boolean.TRUE.equals(tableModel.getValueAt(i, 0))) continue;

                totalFiles += (Long) tableModel.getValueAt(i, 3);
                totalLoc += (Long) tableModel.getValueAt(i, 4);
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
                if (pf == null) {
                    // Candidate (not imported): skip line counting
                    continue;
                }

                try (var pathStream = Files.walk(pf.absPath())) {
                    // Collect all regular files first
                    List<Path> files = pathStream.filter(Files::isRegularFile).toList();

                    // count lines in parallel
                    long fileCount = files.size();
                    long lineCount = files.parallelStream()
                            .mapToLong(p -> {
                                try (var lines = Files.lines(p)) {
                                    return lines.count();
                                } catch (IOException | UncheckedIOException e) {
                                    // Ignore unreadable/non-text files
                                    return 0;
                                }
                            })
                            .sum();
                    publish(new Object[] {i, fileCount, lineCount});
                } catch (IOException e) {
                    // Could not walk the directory
                    publish(new Object[] {i, 0L, 0L});
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
                tableModel.setValueAt(files, row, 3);
                tableModel.setValueAt(loc, row, 4);
            }
            // Totals will be updated by the TableModelListener
        }
    }

    private class CandidateDiscoveryWorker extends SwingWorker<List<IExternalDependency>, Void> {
        @Override
        protected List<IExternalDependency> doInBackground() {
            try {
                return chrome.getProject().getExternalDependencyCandidates();
            } catch (Exception e) {
                return java.util.List.of();
            }
        }

        @Override
        protected void done() {
            try {
                List<IExternalDependency> candidates = get();
                for (IExternalDependency dep : candidates) {
                    String displayName = dep.displayName();
                    // Avoid duplicate entries by name
                    if (dependencyProjectFileMap.containsKey(displayName)
                            || dependencyCandidateMap.containsKey(displayName)) {
                        continue;
                    }
                    dependencyCandidateMap.put(displayName, dep);
                    tableModel.addRow(new Object[] {false, displayName, dep.sourceSystem(), 0L, 0L});
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private void removeSelectedDependency() {
        int selectedRowInView = table.getSelectedRow();
        if (selectedRowInView == -1) {
            return;
        }
        int selectedRowInModel = table.convertRowIndexToModel(selectedRowInView);

        String depName = (String) tableModel.getValueAt(selectedRowInModel, 1);
        if (dependencyCandidateMap.containsKey(depName)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Auto-discovered candidates cannot be deleted.\nYou can ignore them or import them.",
                    "Not Deletable",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
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

    public static void show(Chrome chrome) {
        var dialog = new ManageDependenciesDialog(chrome);
        dialog.setVisible(true);
    }
}
