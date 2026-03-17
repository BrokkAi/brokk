package ai.brokk.gui.dialogs;

import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.macro.MacroPolicy;
import ai.brokk.analyzer.macro.MacroPolicy.MacroMatch;
import ai.brokk.analyzer.macro.MacroPolicy.MacroStrategy;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.project.IProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

public abstract class AbstractMacroSettingsPanel extends AnalyzerSettingsPanel {

    protected final JTextArea yamlEditor;
    protected final JTable macroTable;
    protected final MacroTableModel tableModel;
    protected final List<MacroMatch> macroList;
    protected static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    protected AbstractMacroSettingsPanel(Language language, IProject project, IConsoleIO io) {
        super(new BorderLayout(), language, project.getRoot(), project, io);

        MacroPolicy policy = project.getMacroPolicies().get(language);
        this.macroList = new ArrayList<>(policy != null ? policy.macros() : List.of());
        this.tableModel = new MacroTableModel();
        this.macroTable = new JTable(tableModel);

        this.yamlEditor = new JTextArea();
        this.yamlEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        this.yamlEditor.setTabSize(2);

        // Intercept Tab to insert 2 spaces
        this.yamlEditor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_TAB) {
                    e.consume();
                    yamlEditor.replaceSelection("  ");
                }
            }
        });

        initComponents();
    }

    private void initComponents() {
        // Main: Table and buttons
        JPanel topPanel = new JPanel(new BorderLayout());
        macroTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        macroTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = macroTable.rowAtPoint(e.getPoint());
                    if (row != -1) {
                        editRow(row);
                    }
                }
            }
        });

        topPanel.add(new JScrollPane(macroTable), BorderLayout.CENTER);

        JPanel tableButtons = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 5, 2, 0);

        MaterialButton addBtn = new MaterialButton("+");
        addBtn.addActionListener(e -> {
            macroList.add(new MacroMatch("new_macro", null, MacroStrategy.BYPASS, null));
            tableModel.fireTableDataChanged();
            int newRow = macroList.size() - 1;
            macroTable.setRowSelectionInterval(newRow, newRow);
            editRow(newRow);
        });
        tableButtons.add(addBtn, gbc);

        MaterialButton removeBtn = new MaterialButton("-");
        removeBtn.addActionListener(e -> {
            int row = macroTable.getSelectedRow();
            if (row != -1) {
                macroList.remove(row);
                tableModel.fireTableDataChanged();
            }
        });
        gbc.gridy++;
        tableButtons.add(removeBtn, gbc);

        MaterialButton upBtn = new MaterialButton("▲");
        upBtn.addActionListener(e -> moveRow(-1));
        gbc.gridy++;
        tableButtons.add(upBtn, gbc);

        MaterialButton downBtn = new MaterialButton("▼");
        downBtn.addActionListener(e -> moveRow(1));
        gbc.gridy++;
        tableButtons.add(downBtn, gbc);

        topPanel.add(tableButtons, BorderLayout.EAST);
        add(topPanel, BorderLayout.CENTER);
    }

    private void editRow(int row) {
        MacroMatch match = macroList.get(row);
        try {
            yamlEditor.setText(YAML_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(match));
            yamlEditor.setCaretPosition(0);

            JScrollPane scrollPane = new JScrollPane(yamlEditor);
            scrollPane.setPreferredSize(new Dimension(500, 400));

            while (true) {
                int result = JOptionPane.showConfirmDialog(
                        this, scrollPane, "Edit Macro Entry", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

                if (result != JOptionPane.OK_OPTION) {
                    break;
                }

                try {
                    MacroMatch updated = YAML_MAPPER.readValue(yamlEditor.getText(), MacroMatch.class);
                    macroList.set(row, updated);
                    tableModel.fireTableRowsUpdated(row, row);
                    break;
                } catch (Exception ex) {
                    String msg = ex.getMessage();
                    if (ex instanceof com.fasterxml.jackson.core.JsonProcessingException jpe) {
                        msg = jpe.getOriginalMessage();
                    } else if (ex.getCause() instanceof com.fasterxml.jackson.core.JsonProcessingException jpe) {
                        msg = jpe.getOriginalMessage();
                    }
                    io.toolError("Failed to parse macro entry: " + msg);
                    // Loop continues, re-showing the dialog with current yamlEditor content
                }
            }
        } catch (Exception ex) {
            io.toolError("Failed to serialize macro entry: " + ex.getMessage());
        }
    }

    private void moveRow(int delta) {
        int row = macroTable.getSelectedRow();
        if (row == -1) return;
        int next = row + delta;
        if (next >= 0 && next < macroList.size()) {
            Collections.swap(macroList, row, next);
            tableModel.fireTableDataChanged();
            macroTable.setRowSelectionInterval(next, next);
        }
    }

    @Override
    public void saveSettings() {
        List<MacroMatch> validMacros =
                macroList.stream().filter(m -> !m.name().isBlank()).toList();

        if (validMacros.isEmpty()) {
            project.setMacroPolicy(language, null);
        } else {
            MacroPolicy policy = new MacroPolicy(
                    "1.0", language.internalName().toLowerCase(Locale.ROOT), new ArrayList<>(validMacros));
            project.setMacroPolicy(language, policy);
        }
    }

    protected class MacroTableModel extends AbstractTableModel {
        private final String[] columns = {"Name", "Strategy"};

        @Override
        public int getRowCount() {
            return macroList.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MacroMatch match = macroList.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> match.name();
                case 1 -> match.strategy();
                default -> "";
            };
        }
    }
}
