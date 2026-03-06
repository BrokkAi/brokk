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

    private List<String> getRootsFromTable() {
        int rowCount = tableModel.getRowCount();
        List<String> roots = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            String val = (String) tableModel.getValueAt(i, 0);
            if (val != null && !val.trim().isEmpty()) {
                roots.add(val.trim());
            }
        }
        return roots;
    }

    @Override
    public void saveSettings() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        List<String> roots = getRootsFromTable();
        project.setJavaSourceRoots(roots);
        logger.debug(
                "Saved {} Java source roots for project {}",
                roots.size(),
                project.getRoot().getFileName());
    }
}
