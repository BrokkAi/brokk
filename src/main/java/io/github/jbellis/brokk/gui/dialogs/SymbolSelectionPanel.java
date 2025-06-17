package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.gui.AutoCompleteUtil;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.CompletionProvider;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.autocomplete.ShorthandCompletion;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale; // Added import
import java.util.Set;

/**
 * A reusable panel for selecting Java symbols (classes and members) with autocomplete.
 */
public class SymbolSelectionPanel extends JPanel {

    private final JTextField symbolInput;
    private final AutoCompletion autoCompletion;
    private final Set<CodeUnitType> typeFilter;

    public SymbolSelectionPanel(IAnalyzer analyzer, Set<CodeUnitType> typeFilter) {
        super(new BorderLayout(8, 8));
        this.typeFilter = typeFilter;

        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Build text input with autocomplete at the top
        symbolInput = new JTextField(30);
        var provider = createSymbolCompletionProvider(analyzer);
        autoCompletion = new AutoCompletion(provider);
        // Trigger with Ctrl+Space (Always. On Mac cmd-space is Spotlight)
        autoCompletion.setAutoActivationEnabled(false);
        autoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));
        autoCompletion.install(symbolInput);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inputPanel.add(symbolInput, BorderLayout.CENTER);
        String autocompleteText;
        if (typeFilter.equals(CodeUnitType.ALL)) {
            autocompleteText = "Ctrl-space to autocomplete class and member names";
        } else {
            assert typeFilter.size() == 1 : "Expected exactly one type filter";
            var type = typeFilter.iterator().next();
            autocompleteText = "Ctrl-space to autocomplete " + type.toString().toLowerCase(Locale.ROOT) + " names";
        }
        inputPanel.add(new JLabel(autocompleteText), BorderLayout.SOUTH);
        add(inputPanel, BorderLayout.CENTER);

        // Set tooltip
        symbolInput.setToolTipText("Enter a class or member name");

        // Bind Ctrl+Enter for autocomplete/submit
        AutoCompleteUtil.bindCtrlEnter(autoCompletion, symbolInput);
    }

    /**
     * Create the symbol completion provider using Completions.completeSymbols
     */
    private SymbolCompletionProvider createSymbolCompletionProvider(IAnalyzer analyzer) {
        return new SymbolCompletionProvider(analyzer, typeFilter);
    }

    /**
     * Get the text from the input field
     */
    public String getSymbolText() {
        return symbolInput.getText().trim();
    }

    /**
     * Get the text field component for setting default button actions
     */
    public JTextField getInputField() {
        return symbolInput;
    }

    public void addSymbolSelectionListener(Runnable onChange) {
        symbolInput.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                onChange.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                onChange.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                onChange.run();
            }
        });
    }

    /**
     * A completion provider for Java classes and members using Completions.completeClassesAndMembers
     */
    public class SymbolCompletionProvider extends DefaultCompletionProvider {

        private final IAnalyzer analyzer;
        private final Set<CodeUnitType> typeFilter;

        public SymbolCompletionProvider(IAnalyzer analyzer, Set<CodeUnitType> typeFilter) {
            this.analyzer = analyzer;
            this.typeFilter = typeFilter;
        }

        @Override
        public List<Completion> getCompletions(JTextComponent comp) {
            String text = comp.getText();
            int caretPosition = comp.getCaretPosition();

            // If the caret is not at the end, adjust the text
            if (caretPosition < text.length()) {
                text = text.substring(0, caretPosition);
            }

            // Get completions using the brokk Completions utility
            var completions = Completions.completeSymbols(text, analyzer);

            // Convert to RSTA completions, filtering by the requested types
            var L = completions.stream()
                    .filter(c -> typeFilter.contains(c.kind()))
                    .map(c -> new ShorthandCompletion(this, c.shortName(), c.fqName()))
                    .toList();

            // Dynamically size the popup windows
            AutoCompleteUtil.sizePopupWindows(autoCompletion, symbolInput, L);

            return L.stream().map(c -> (Completion) c).toList();
        }
    }
}
