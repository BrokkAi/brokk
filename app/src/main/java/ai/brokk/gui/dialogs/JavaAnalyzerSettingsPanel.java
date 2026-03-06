package ai.brokk.gui.dialogs;

import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.SourceRootScanner;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.util.Icons;
import ai.brokk.project.IProject;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class JavaAnalyzerSettingsPanel extends AnalyzerSettingsPanel {
    private final IProject project;
    private final DefaultTableModel tableModel;
    private final JTable table;

    public JavaAnalyzerSettingsPanel(Language language, IProject project, IConsoleIO io) {
        super(new BorderLayout(), language, project.getRoot(), io);
        this.project = project;

        JPanel contentPanel = new JPanel(new BorderLayout(5, 5));
        contentPanel.add(new JLabel("Source Roots (relative to project root):"), BorderLayout.NORTH);

        tableModel = new DefaultTableModel(new Object[] {"Source Root Path"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }
        };

        for (String root : project.getJavaSourceRoots()) {
            tableModel.addRow(new Object[] {root});
        }

        table = new JTable(tableModel);
        table.setName("JavaSourceRootsTable");
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setName("JavaSourceRootsScrollPane");
        // Reduce default preferred height to roughly half (e.g. 80-100px) but keep it responsive
        scrollPane.setPreferredSize(new Dimension(400, 80));
        contentPanel.add(scrollPane, BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        toolbar.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

        Dimension compactBtnSize = new Dimension(26, 26);
        Insets compactMargin = new Insets(2, 2, 2, 2);

        MaterialButton addButton = new MaterialButton();
        addButton.setName("AddSourceRootButton");
        addButton.setIcon(Icons.ADD);
        addButton.setToolTipText("Add Source Root");
        addButton.setPreferredSize(compactBtnSize);
        addButton.setMinimumSize(compactBtnSize);
        addButton.setMaximumSize(compactBtnSize);
        addButton.setMargin(compactMargin);
        addButton.addActionListener(e -> {
            tableModel.addRow(new Object[] {""});
            int row = tableModel.getRowCount() - 1;
            table.setRowSelectionInterval(row, row);
            table.editCellAt(row, 0);
        });

        MaterialButton removeButton = new MaterialButton();
        removeButton.setName("RemoveSourceRootButton");
        removeButton.setIcon(Icons.REMOVE);
        removeButton.setToolTipText("Remove Selected");
        removeButton.setPreferredSize(compactBtnSize);
        removeButton.setMinimumSize(compactBtnSize);
        removeButton.setMaximumSize(compactBtnSize);
        removeButton.setMargin(compactMargin);
        removeButton.addActionListener(e -> {
            int[] selectedRows = table.getSelectedRows();
            for (int i = selectedRows.length - 1; i >= 0; i--) {
                tableModel.removeRow(selectedRows[i]);
            }
        });

        MaterialButton resetButton = new MaterialButton("Reset");
        resetButton.setName("ResetSourceRootsButton");
        resetButton.setToolTipText("Reset to detected source roots");
        resetButton.addActionListener(e -> {
            List<String> detected = SourceRootScanner.scan(project, language);
            tableModel.setRowCount(0);
            for (String path : detected) {
                tableModel.addRow(new Object[] {path});
            }
        });

        toolbar.add(addButton);
        toolbar.add(removeButton);
        toolbar.add(resetButton);

        contentPanel.add(toolbar, BorderLayout.SOUTH);
        add(contentPanel, BorderLayout.CENTER);
    }

    @Override
    public void saveSettings() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }

        int rowCount = tableModel.getRowCount();
        java.util.LinkedHashSet<String> canonicalRoots = new java.util.LinkedHashSet<>();
        List<String> invalidRoots = new ArrayList<>();

        java.nio.file.Path masterRoot = project.getMasterRootPathForConfig();

        for (int i = 0; i < rowCount; i++) {
            String val = (String) tableModel.getValueAt(i, 0);
            if (val == null || val.isBlank()) {
                continue;
            }

            String canonical = ai.brokk.util.PathNormalizer.canonicalizeForProject(val, masterRoot);
            if (canonical.isEmpty()) {
                continue;
            }

            // A path is invalid if it escapes the project root (contains ".." at the start or elsewhere
            // after canonicalization that PathNormalizer couldn't resolve within the root).
            if (canonical.startsWith("../") || canonical.equals("..")) {
                invalidRoots.add(val.trim());
            } else {
                canonicalRoots.add(canonical);
            }
        }

        if (!invalidRoots.isEmpty()) {
            String msg = "The following source roots are invalid or outside the project root:\n"
                    + String.join("\n", invalidRoots);
            io.toolError(msg, "Invalid Source Roots");
            return;
        }

        List<String> newRoots = new ArrayList<>(canonicalRoots);
        List<String> existingRoots = project.getJavaSourceRoots();

        if (!newRoots.equals(existingRoots)) {
            project.setJavaSourceRoots(newRoots);
            logger.debug(
                    "Saved {} Java source roots for project {}",
                    newRoots.size(),
                    project.getRoot().getFileName());

            // Update table to show canonicalized versions
            tableModel.setRowCount(0);
            for (String root : newRoots) {
                tableModel.addRow(new Object[] {root});
            }
        }
    }
}
