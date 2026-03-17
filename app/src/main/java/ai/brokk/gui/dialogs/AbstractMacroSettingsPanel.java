package ai.brokk.gui.dialogs;

import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.macro.MacroPolicy;
import ai.brokk.analyzer.macro.MacroPolicy.AIExpandConfig;
import ai.brokk.analyzer.macro.MacroPolicy.BuiltinConfig;
import ai.brokk.analyzer.macro.MacroPolicy.BypassConfig;
import ai.brokk.analyzer.macro.MacroPolicy.MacroConfig;
import ai.brokk.analyzer.macro.MacroPolicy.MacroMatch;
import ai.brokk.analyzer.macro.MacroPolicy.MacroScope;
import ai.brokk.analyzer.macro.MacroPolicy.MacroStrategy;
import ai.brokk.analyzer.macro.MacroPolicy.TemplateConfig;
import static java.util.Objects.requireNonNull;

import ai.brokk.gui.components.MaterialButton;
import ai.brokk.project.IProject;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

public abstract class AbstractMacroSettingsPanel extends AnalyzerSettingsPanel {

    protected final JTable macroTable;
    protected final MacroTableModel tableModel;
    protected final List<MacroMatch> macroList;

    protected AbstractMacroSettingsPanel(Language language, IProject project, IConsoleIO io) {
        super(new BorderLayout(), language, project.getRoot(), project, io);

        MacroPolicy policy = project.getMacroPolicies().get(language);
        this.macroList = new ArrayList<>(policy != null ? policy.macros() : List.of());
        this.tableModel = new MacroTableModel();
        this.macroTable = new JTable(tableModel);

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
            macroList.add(new MacroMatch("new_macro", null, MacroStrategy.BYPASS, new MacroPolicy.BypassConfig()));
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

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.NORTHWEST;

        JTextField nameField = new JTextField(match.name());
        JComboBox<MacroScope> scopeCombo = new JComboBox<>(MacroScope.values());
        scopeCombo.setSelectedItem(match.scope());
        JComboBox<MacroStrategy> strategyCombo = new JComboBox<>(MacroStrategy.values());
        strategyCombo.setSelectedItem(match.strategy());

        // Options sub-panel with CardLayout
        CardLayout cardLayout = new CardLayout();
        JPanel optionsCards = new JPanel(cardLayout);

        // BYPASS card
        optionsCards.add(new JPanel(), MacroStrategy.BYPASS.name());

        // BUILTIN card
        optionsCards.add(new JPanel(), MacroStrategy.BUILTIN.name());

        // TEMPLATE card
        JTextArea templateArea = new JTextArea(5, 30);
        templateArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        if (match.options() instanceof TemplateConfig tc) {
            templateArea.setText(tc.template());
        }
        JPanel templatePanel = new JPanel(new BorderLayout());
        templatePanel.add(new JLabel("Template:"), BorderLayout.NORTH);
        templatePanel.add(new JScrollPane(templateArea), BorderLayout.CENTER);
        optionsCards.add(templatePanel, MacroStrategy.TEMPLATE.name());

        // AI_EXPAND card
        JTextField maxTokensField = new JTextField();
        JTextField promptHintField = new JTextField();
        if (match.options() instanceof AIExpandConfig ac) {
            maxTokensField.setText(ac.max_tokens() != null ? ac.max_tokens().toString() : "");
            promptHintField.setText(ac.prompt_hint() != null ? ac.prompt_hint() : "");
        }
        JPanel aiExpandPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        aiExpandPanel.add(new JLabel("Max Tokens:"));
        aiExpandPanel.add(maxTokensField);
        aiExpandPanel.add(new JLabel("Prompt Hint:"));
        aiExpandPanel.add(promptHintField);
        optionsCards.add(aiExpandPanel, MacroStrategy.AI_EXPAND.name());

        strategyCombo.addActionListener(
                e -> cardLayout.show(optionsCards, strategyCombo.getSelectedItem().toString()));
        cardLayout.show(optionsCards, match.strategy().name());

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Name:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(nameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        panel.add(new JLabel("Scope:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(scopeCombo, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        panel.add(new JLabel("Strategy:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(strategyCombo, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(optionsCards, gbc);

        int result = JOptionPane.showConfirmDialog(
                this, panel, "Edit Macro Entry", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String newName = nameField.getText().trim();
            MacroScope newScope = (MacroScope) scopeCombo.getSelectedItem();
            MacroStrategy newStrategy = (MacroStrategy) strategyCombo.getSelectedItem();
            MacroConfig newOptions =
                    switch (newStrategy) {
                        case BYPASS -> new BypassConfig();
                        case BUILTIN -> new BuiltinConfig();
                        case TEMPLATE -> new TemplateConfig(templateArea.getText());
                        case AI_EXPAND -> {
                            Integer maxTokens = null;
                            try {
                                String txt = maxTokensField.getText().trim();
                                if (!txt.isEmpty()) maxTokens = Integer.parseInt(txt);
                            } catch (NumberFormatException ex) {
                                logger.warn("Invalid max tokens value: {}", maxTokensField.getText(), ex);
                            }
                            String hint = promptHintField.getText().trim();
                            yield new AIExpandConfig(maxTokens, hint.isEmpty() ? null : hint);
                        }
                    };

            MacroMatch updated = new MacroMatch(newName, requireNonNull(newScope), newStrategy, newOptions);
            macroList.set(row, updated);
            tableModel.fireTableRowsUpdated(row, row);
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
