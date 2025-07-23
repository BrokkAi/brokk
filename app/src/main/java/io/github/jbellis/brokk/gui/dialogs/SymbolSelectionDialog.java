package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Set;

/**
 * A dialog for selecting Java symbols (classes and members) with autocomplete.
 */
public class SymbolSelectionDialog extends JDialog {

    private final SymbolSelectionPanel selectionPanel;
    private final JButton okButton;
    private final JButton cancelButton;

    // The selected symbol
    private @Nullable String selectedSymbol = null;

    // Indicates if the user confirmed the selection
    private boolean confirmed = false;

    public SymbolSelectionDialog(Frame parent, IAnalyzer analyzer, String title, Set<CodeUnitType> typeFilter) {
        super(parent, title, true); // modal dialog

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Create the symbol selection panel
        selectionPanel = new SymbolSelectionPanel(analyzer, typeFilter);
        mainPanel.add(selectionPanel, BorderLayout.NORTH);

        // Buttons at the bottom
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        okButton = new JButton("OK");
        okButton.addActionListener(e -> doOk());
        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            confirmed = false;
            dispose();
        });
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Handle escape key to close dialog
        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        getRootPane().registerKeyboardAction(e -> {
            confirmed = false;
            selectedSymbol = null;
            dispose();
        }, escapeKeyStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Set OK as the default button (responds to Enter key)
        getRootPane().setDefaultButton(okButton);

        // Add a tooltip to indicate Enter key functionality
        selectionPanel.getInputField().setToolTipText("Enter a class or member name and press Enter to confirm");

        setContentPane(mainPanel);
        pack();
        setLocationRelativeTo(parent);
    }

    /**
     * When OK is pressed, get the symbol from the text input.
     */
    private void doOk() {
        confirmed = true;
        selectedSymbol = null;

        String typed = selectionPanel.getSymbolText();
        if (!typed.isEmpty()) {
            selectedSymbol = typed;
        }
        dispose();
    }

    /**
     * Return true if user confirmed the selection.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Return the selected symbol or null if none.
     */
    public @Nullable String getSelectedSymbol() {
        return selectedSymbol;
    }
}
