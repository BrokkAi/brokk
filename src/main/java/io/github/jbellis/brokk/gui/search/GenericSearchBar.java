package io.github.jbellis.brokk.gui.search;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * A completely generic search bar component that works with any component
 * implementing the SearchableComponent interface. This provides
 * reusability across different types of text components.
 */
public class GenericSearchBar extends JPanel {
    private final JTextField searchField;
    private final JToggleButton caseSensitiveButton;
    private final JButton nextButton;
    private final JButton previousButton;
    private final JLabel matchCountLabel;
    private final SearchableComponent targetComponent;
    private int currentMatchIndex = 0;
    private int totalMatches = 0;

    public GenericSearchBar(SearchableComponent targetComponent) {
        super(new FlowLayout(FlowLayout.LEFT, 8, 0));
        this.targetComponent = targetComponent;

        // Initialize components
        searchField = new JTextField(20);
        caseSensitiveButton = new JToggleButton("Cc");
        caseSensitiveButton.setToolTipText("Case sensitive search");
        caseSensitiveButton.setMargin(new Insets(2, 4, 2, 4));
        nextButton = new JButton();
        nextButton.setIcon(UIManager.getIcon("Table.descendingSortIcon"));
        nextButton.setEnabled(false);
        previousButton = new JButton();
        previousButton.setIcon(UIManager.getIcon("Table.ascendingSortIcon"));
        previousButton.setEnabled(false);
        matchCountLabel = new JLabel("");
        matchCountLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        // Build UI
        add(new JLabel("Search:"));
        add(searchField);
        add(caseSensitiveButton);
        add(previousButton);
        add(nextButton);
        add(matchCountLabel);

        // Setup event handlers
        setupEventHandlers();
        setupKeyboardShortcuts();

        // Initialize tooltip
        updateTooltip();
        
        // Setup async search callback
        targetComponent.setSearchCompleteCallback(this::onSearchComplete);
    }

    private void setupEventHandlers() {
        // Real-time search as user types
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateSearchHighlights(true);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateSearchHighlights(true);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateSearchHighlights(true);
            }
        });

        // Enter key triggers next match
        searchField.addActionListener(e -> findNext());

        // Button actions
        nextButton.addActionListener(e -> findNext());
        previousButton.addActionListener(e -> findPrevious());
        caseSensitiveButton.addActionListener(e -> {
            updateTooltip();
            updateSearchHighlights(false);
        });
    }

    private void setupKeyboardShortcuts() {
        var inputMap = searchField.getInputMap(JComponent.WHEN_FOCUSED);
        var actionMap = searchField.getActionMap();

        // Down arrow for next match
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "findNext");
        actionMap.put("findNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findNext();
            }
        });

        // Up arrow for previous match
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "findPrevious");
        actionMap.put("findPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findPrevious();
            }
        });
    }

    private void updateTooltip() {
        if (caseSensitiveButton.isSelected()) {
            caseSensitiveButton.setToolTipText("Case sensitive search ON");
        } else {
            caseSensitiveButton.setToolTipText("Case sensitive search OFF");
        }
    }

    /**
     * Registers global keyboard shortcuts for the search bar.
     */
    public void registerGlobalShortcuts(JComponent parentComponent) {
        // Cmd/Ctrl+F focuses the search field
        var ctrlF = KeyStroke.getKeyStroke(KeyEvent.VK_F,
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        parentComponent.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ctrlF, "focusSearch");
        parentComponent.getActionMap().put("focusSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                focusSearchField();
            }
        });

        // ESC key clears search highlights when search field has focus
        var escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        searchField.getInputMap(JComponent.WHEN_FOCUSED).put(escapeKeyStroke, "clearHighlights");
        searchField.getActionMap().put("clearHighlights", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                clearHighlights();
                targetComponent.requestFocusInWindow();
            }
        });
    }

    /**
     * Focuses the search field and optionally populates it with selected text.
     */
    public void focusSearchField() {
        String selected = targetComponent.getSelectedText();
        if (selected != null && !selected.isEmpty()) {
            searchField.setText(selected);
        }
        searchField.selectAll();
        searchField.requestFocusInWindow();

        // If there's text in the search field, re-highlight matches
        String query = searchField.getText();
        if (query != null && !query.trim().isEmpty()) {
            int originalCaretPosition = targetComponent.getCaretPosition();
            updateSearchHighlights(false);
            targetComponent.setCaretPosition(originalCaretPosition);
        }
    }

    /**
     * Clears all search highlights but keeps the search text.
     */
    public void clearHighlights() {
        targetComponent.clearHighlights();
    }

    /**
     * Updates search highlights in the target component.
     *
     * @param jumpToFirst If true, jump to the first occurrence; if false, maintain current position
     */
    private void updateSearchHighlights(boolean jumpToFirst) {
        String query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            clearHighlights();
            updateMatchCount(0, 0);
            return;
        }

        // Highlight all occurrences (this will trigger async callback)
        targetComponent.highlightAll(query, caseSensitiveButton.isSelected());

        if (jumpToFirst) {
            // Jump to the first occurrence as the user types (once async search completes)
            // The callback will handle updating the match count
            int originalCaretPosition = targetComponent.getCaretPosition();
            targetComponent.setCaretPosition(0); // Start search from beginning
            boolean found = targetComponent.findNext(query, caseSensitiveButton.isSelected(), true);
            if (!found && originalCaretPosition > 0) {
                // If not found from beginning, restore caret position
                targetComponent.setCaretPosition(originalCaretPosition);
            } else if (found) {
                // Center the match in the viewport
                targetComponent.centerCaretInView();
            }
        }
    }
    
    /**
     * Callback method for async search completion.
     * This will be called by async SearchableComponent implementations when search is complete.
     */
    private void onSearchComplete(int totalMatches, int currentMatchIndex) {
        SwingUtilities.invokeLater(() -> {
            this.totalMatches = totalMatches;
            this.currentMatchIndex = currentMatchIndex;
            updateMatchCount(currentMatchIndex, totalMatches);
        });
    }

    private void findNext() {
        findMatch(true);
    }

    private void findPrevious() {
        findMatch(false);
    }

    /**
     * Finds the next or previous match relative to the current caret position.
     *
     * @param forward true = next match; false = previous match
     */
    private void findMatch(boolean forward) {
        String query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            return;
        }

        boolean found = targetComponent.findNext(query, caseSensitiveButton.isSelected(), forward);
        if (found) {
            targetComponent.centerCaretInView();
            // The callback will handle updating the match count
        }
    }

    /**
     * Updates the match count display.
     *
     * @param currentMatch the current match index (1-based)
     * @param totalMatches the total number of matches
     */
    private void updateMatchCount(int currentMatch, int totalMatches) {
        if (totalMatches == 0) {
            matchCountLabel.setText("");
            nextButton.setEnabled(false);
            previousButton.setEnabled(false);
        } else {
            matchCountLabel.setText(currentMatch + "/" + totalMatches);
            nextButton.setEnabled(true);
            previousButton.setEnabled(true);
        }
    }

    public String getSearchText() {
        return searchField.getText();
    }

    public void setSearchText(String text) {
        searchField.setText(text);
    }

    public void clearSearch() {
        searchField.setText("");
        clearHighlights();
        updateMatchCount(0, 0);
    }

    public boolean isCaseSensitive() {
        return caseSensitiveButton.isSelected();
    }

    public void setCaseSensitive(boolean caseSensitive) {
        caseSensitiveButton.setSelected(caseSensitive);
        updateTooltip();
    }
}
