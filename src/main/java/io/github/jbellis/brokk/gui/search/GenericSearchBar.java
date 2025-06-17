package io.github.jbellis.brokk.gui.search;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;

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

    // Performance optimization: debouncing
    private Timer searchTimer;
    private static final int SEARCH_DELAY_MS = 300; // 300ms delay for debouncing

    // Case sensitive listeners
    private final List<Consumer<Boolean>> caseSensitiveListeners = new ArrayList<>();

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
        // Initialize debouncing timer
        searchTimer = new Timer(SEARCH_DELAY_MS, e -> updateSearchHighlights());
        searchTimer.setRepeats(false);

        // Real-time search as user types with debouncing
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleSearch();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleSearch();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleSearch();
            }

            private void scheduleSearch() {
                // Restart the timer for each keystroke (debouncing)
                searchTimer.restart();

                // Provide immediate feedback for empty search
                var query = searchField.getText();
                if (query == null || query.trim().isEmpty()) {
                    updateMatchCount(0, 0);
                }
            }
        });

        // Enter key triggers next match
        searchField.addActionListener(e -> findNext());

        // Button actions
        nextButton.addActionListener(e -> findNext());
        previousButton.addActionListener(e -> findPrevious());
        caseSensitiveButton.addActionListener(e -> {
            updateTooltip();
            updateSearchHighlights();
            fireCaseSensitiveChanged(caseSensitiveButton.isSelected());
        });
    }

    private void setupKeyboardShortcuts() {
        // Down/Up arrow navigation shortcuts
        KeyboardShortcutUtil.registerSearchNavigationShortcuts(searchField, this::findNext, this::findPrevious);
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
        // Standard search shortcuts: Ctrl/Cmd+F to focus, Esc to clear
        KeyboardShortcutUtil.registerStandardSearchShortcuts(
            parentComponent,
            searchField,
            this::focusSearchField,
            () -> {
                clearHighlights();
                targetComponent.requestFocusInWindow();
            }
        );
    }

    /**
     * Gets the search field component for external keyboard shortcut registration.
     */
    public JTextField getSearchField() {
        return searchField;
    }

    /**
     * Focuses the search field and optionally populates it with selected text.
     */
    public void focusSearchField() {
        var selected = targetComponent.getSelectedText();
        if (selected != null && !selected.isEmpty()) {
            searchField.setText(selected);
        }
        searchField.selectAll();
        searchField.requestFocusInWindow();

        // If there's text in the search field, re-highlight matches
        var query = searchField.getText();
        if (query != null && !query.trim().isEmpty()) {
            int originalCaretPosition = targetComponent.getCaretPosition();
            updateSearchHighlights();
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
     */
    private void updateSearchHighlights() {
        var query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            clearHighlights();
            updateMatchCount(0, 0);
            return;
        }

        // Highlight all occurrences (this will trigger async callback)
        targetComponent.highlightAll(query, caseSensitiveButton.isSelected());

        // For async components, the scrolling to first match will happen in the callback
        // when the search completes (see onSearchComplete and MarkdownSearchableComponent.handleSearchComplete)
    }

    /**
     * Callback method for async search completion.
     * This will be called by async SearchableComponent implementations when search is complete.
     */
    private void onSearchComplete(int totalMatches, int currentMatchIndex) {
        SwingUtilities.invokeLater(() -> {
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
        var query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            return;
        }

        var found = targetComponent.findNext(query, caseSensitiveButton.isSelected(), forward);
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

    /**
     * Adds a listener that will be notified when the case-sensitive button is toggled.
     */
    public void addCaseSensitiveListener(Consumer<Boolean> listener) {
        caseSensitiveListeners.add(listener);
    }

    /**
     * Removes a case-sensitive listener.
     */
    public void removeCaseSensitiveListener(Consumer<Boolean> listener) {
        caseSensitiveListeners.remove(listener);
    }

    /**
     * Notifies all case-sensitive listeners of a change.
     */
    private void fireCaseSensitiveChanged(boolean isSelected) {
        for (var listener : caseSensitiveListeners) {
            listener.accept(isSelected);
        }
    }
}
