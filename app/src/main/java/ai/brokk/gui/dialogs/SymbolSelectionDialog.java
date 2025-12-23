package ai.brokk.gui.dialogs;

import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Set;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/** A dialog for selecting Java symbols (classes and members) with autocomplete. */
public class SymbolSelectionDialog extends BaseThemedDialog {

    private final SymbolSelectionPanel selectionPanel;
    private final JButton okButton;
    private final JButton cancelButton;
    // Checkbox to include usages in test files
    private final JCheckBox includeTestFilesCheckBox;

    // The selected symbol and options
    public record SymbolSelection(@Nullable String symbol, boolean includeTestFiles) {}

    private @Nullable SymbolSelectionDialog.SymbolSelection symbolSelection = null;

    // Indicates if the user confirmed the selection
    private boolean confirmed = false;

    public SymbolSelectionDialog(Frame parent, IAnalyzer analyzer, String title, Set<CodeUnitType> typeFilter) {
        super(parent, title);

        // Create the symbol selection panel
        selectionPanel = new SymbolSelectionPanel(analyzer, typeFilter);

        // Include test files checkbox
        includeTestFilesCheckBox = new JCheckBox("Include usages in test files");
        includeTestFilesCheckBox.setSelected(false);

        // Buttons at the bottom
        okButton = new JButton("OK");
        okButton.addActionListener(e -> doOk());
        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            confirmed = false;
            dispose();
        });

        // Build layout in the content root
        JPanel root = getContentRoot();
        root.setLayout(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        root.add(selectionPanel, BorderLayout.NORTH);
        root.add(includeTestFilesCheckBox, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        root.add(buttonPanel, BorderLayout.SOUTH);

        // Handle escape key to close dialog
        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        getRootPane()
                .registerKeyboardAction(
                        e -> {
                            confirmed = false;
                            symbolSelection = null;
                            dispose();
                        },
                        escapeKeyStroke,
                        JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Set OK as the default button (responds to Enter key)
        getRootPane().setDefaultButton(okButton);

        // Add a tooltip to indicate Enter key functionality
        selectionPanel.getInputField().setToolTipText("Enter a class or member name and press Enter to confirm");

        pack();
        setLocationRelativeTo(parent);
    }

    /** When OK is pressed, get the symbol from the text input. */
    private void doOk() {
        confirmed = true;
        symbolSelection = null;

        String typed = selectionPanel.getSymbolText();
        if (!typed.isEmpty()) {
            symbolSelection = new SymbolSelection(typed, includeTestFilesCheckBox.isSelected());
        }
        dispose();
    }

    /** Return true if user confirmed the selection. */
    public boolean isConfirmed() {
        return confirmed;
    }

    /** Returns the full selection, including the 'include test files' flag. */
    public @Nullable SymbolSelectionDialog.SymbolSelection getSelection() {
        return symbolSelection;
    }
}
