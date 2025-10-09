package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.components.MaterialToggleButton;
import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * A completely generic search bar component that works with any component implementing the SearchableComponent
 * interface. This provides reusability across different types of text components.
 */
public class GenericSearchBar extends JPanel {
    private final JTextField searchField;
    private final JButton clearButton;
    private final JPanel searchFieldPanel;
    private final JToggleButton caseSensitiveButton;
    private final JButton nextButton;
    private final JButton previousButton;
    private final JLabel matchCountLabel;
    private final SearchableComponent targetComponent;
    private final JPanel controlsPanel; // Panel for navigation controls

    // Performance optimization: debouncing
    private Timer searchTimer;
    private static final int SEARCH_DELAY_MS = 300; // 300ms delay for debouncing

    // Layout thresholds for responsive behavior
    private static final int VERY_NARROW_WIDTH = 200; // Hide all controls
    private static final int MEDIUM_NARROW_WIDTH = 350; // Stack controls vertically

    // Case sensitive listeners
    private final List<Consumer<Boolean>> caseSensitiveListeners = new ArrayList<>();

    public GenericSearchBar(SearchableComponent targetComponent) {
        super(new GridBagLayout());
        this.targetComponent = targetComponent;

        // Initialize components
        searchField = new JTextField();
        searchField.setBorder(new EmptyBorder(4, 4, 4, 4));
        searchField.setColumns(15); // Preferred width but can shrink

        // Create clear button as a small floating button
        clearButton = new JButton("×");
        clearButton.setMargin(new Insets(0, 4, 0, 4));
        clearButton.setToolTipText("Clear search");
        clearButton.setVisible(false); // Initially hidden
        clearButton.setFocusable(false); // Prevent focus stealing
        clearButton.setBorderPainted(false);
        clearButton.setContentAreaFilled(false);
        clearButton.setOpaque(false);
        clearButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        clearButton.setFont(clearButton.getFont().deriveFont(Font.PLAIN, 14f)); // Make × symbol larger
        clearButton.setForeground(Color.GRAY); // Make it visible

        // Create a panel to hold the search field with the overlaid clear button
        searchFieldPanel = new JPanel(null) {
            @Override
            public void doLayout() {
                super.doLayout();
                // Position the search field to fill the panel
                searchField.setBounds(0, 0, getWidth(), getHeight());

                // Only position the clear button if it's visible
                if (clearButton.isVisible()) {
                    // Position the clear button inside the text field on the right
                    Insets insets = searchField.getInsets();

                    int buttonWidth = 20; // Fixed width for consistency
                    int buttonHeight = 20; // Fixed height for consistency
                    int fieldHeight = searchField.getHeight();
                    int buttonY = (fieldHeight - buttonHeight) / 2; // For vertical centering
                    // Use a small fixed padding from the right inner border of the search field
                    int horizontalPadding = 2; // pixels
                    int buttonX = searchField.getWidth() - buttonWidth - insets.right - horizontalPadding;
                    clearButton.setBounds(buttonX, buttonY, buttonWidth, buttonHeight);
                }
            }
        };
        searchFieldPanel.setOpaque(false);

        // Add components to the panel (order matters for overlaying)
        searchFieldPanel.add(clearButton); // Add button first so it appears on top
        searchFieldPanel.add(searchField);

        // Adjust text field right margin to make room for the clear button
        searchField.setMargin(new Insets(2, 2, 2, 20));
        caseSensitiveButton = new MaterialToggleButton("Cc");
        caseSensitiveButton.setToolTipText("Case sensitive search");
        caseSensitiveButton.setMargin(new Insets(1, 3, 1, 3));
        nextButton = new JButton();
        nextButton.setIcon(UIManager.getIcon("Table.descendingSortIcon"));
        nextButton.setEnabled(false);
        nextButton.setMargin(new Insets(1, 2, 1, 2));
        previousButton = new JButton();
        previousButton.setIcon(UIManager.getIcon("Table.ascendingSortIcon"));
        previousButton.setEnabled(false);
        previousButton.setMargin(new Insets(1, 2, 1, 2));
        matchCountLabel = new JLabel("");
        matchCountLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        // Create a panel for navigation controls that can wrap
        controlsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        controlsPanel.add(caseSensitiveButton);
        controlsPanel.add(previousButton);
        controlsPanel.add(nextButton);
        controlsPanel.add(matchCountLabel);

        // Build UI with GridBagLayout for better responsive behavior
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 2, 0, 2);

        // Search label
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.WEST;
        add(new JLabel("Search:"), gbc);

        // Search field panel (expandable)
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1.0; // Takes remaining space
        gbc.fill = GridBagConstraints.HORIZONTAL;
        add(searchFieldPanel, gbc);

        // Controls panel (wraps to next line if needed)
        gbc.gridx = 2;
        gbc.gridy = 0;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        add(controlsPanel, gbc);

        // Set size constraints for the search field panel
        Dimension fieldSize = searchField.getPreferredSize();
        searchFieldPanel.setPreferredSize(new Dimension(200, fieldSize.height));
        searchFieldPanel.setMinimumSize(new Dimension(60, fieldSize.height));

        // Set minimum size for search field itself
        searchField.setMinimumSize(new Dimension(50, fieldSize.height));

        // Setup event handlers
        setupEventHandlers();

        // Setup keyboard shortcuts - Down/Up arrow navigation
        KeyboardShortcutUtil.registerSearchNavigationShortcuts(searchField, this::findNext, this::findPrevious);

        // Initialize tooltip
        updateTooltip();

        // Setup async search callback
        targetComponent.setSearchCompleteCallback(this::onSearchComplete);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // Register resize listener after component is added to hierarchy
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                handleResize();
            }
        });
    }

    /** Handles layout adjustments when the component is resized to very narrow widths. */
    private void handleResize() {
        int width = getWidth();
        if (width <= 0) return; // Skip if not properly sized yet

        // For very narrow widths, adjust component visibility and layout
        GridBagConstraints gbc = new GridBagConstraints();
        removeAll(); // Clear current layout

        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 2, 0, 2);

        // Add search label and field (common to all layouts)
        addSearchLabelAndField(gbc);

        if (width < VERY_NARROW_WIDTH) {
            // Very narrow: hide all buttons, show only search field
            controlsPanel.setVisible(false);
        } else if (width < MEDIUM_NARROW_WIDTH) {
            // Medium narrow: stack controls on second row
            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.gridwidth = 2;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            controlsPanel.setVisible(true);
            add(controlsPanel, gbc);
        } else {
            // Normal width: horizontal layout with all controls visible
            gbc.gridx = 2;
            gbc.gridy = 0;
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            gbc.anchor = GridBagConstraints.WEST;
            controlsPanel.setVisible(true);
            add(controlsPanel, gbc);
        }

        revalidate();
        repaint();
    }

    /** Helper method to add search label and field panel (common to all layouts). */
    private void addSearchLabelAndField(GridBagConstraints gbc) {
        // Search label
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        add(new JLabel("Search:"), gbc);

        // Search field panel
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        add(searchFieldPanel, gbc);
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

                // Update clear button visibility
                updateClearButtonVisibility();
            }
        });

        // Enter key triggers next match
        searchField.addActionListener(e -> findNext());

        // Button actions
        clearButton.addActionListener(e -> {
            clearSearch();
            searchField.requestFocusInWindow();
        });
        nextButton.addActionListener(e -> findNext());
        previousButton.addActionListener(e -> findPrevious());
        caseSensitiveButton.addActionListener(e -> {
            updateTooltip();
            updateSearchHighlights();
            fireCaseSensitiveChanged(caseSensitiveButton.isSelected());
        });
    }

    private void updateTooltip() {
        if (caseSensitiveButton.isSelected()) {
            caseSensitiveButton.setToolTipText("Case sensitive search ON");
        } else {
            caseSensitiveButton.setToolTipText("Case sensitive search OFF");
        }
    }

    /** Registers global keyboard shortcuts for the search bar. */
    public void registerGlobalShortcuts(JComponent parentComponent) {
        // Standard search shortcuts: Ctrl/Cmd+F to focus, Esc to clear
        KeyboardShortcutUtil.registerStandardSearchShortcuts(
                parentComponent, searchField, this::focusSearchField, () -> {
                    clearHighlights();
                    targetComponent.requestFocusInWindow();
                });
    }

    /** Gets the search field component for external keyboard shortcut registration. */
    public JTextField getSearchField() {
        return searchField;
    }

    /** Focuses the search field and optionally populates it with selected text. */
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

    /** Clears all search highlights but keeps the search text. */
    public void clearHighlights() {
        targetComponent.clearHighlights();
    }

    /** Updates the visibility of the clear button based on search field content. */
    private void updateClearButtonVisibility() {
        var text = searchField.getText();
        boolean hasText = text != null && !text.isEmpty();
        clearButton.setVisible(hasText);

        // Adjust text field margin based on clear button visibility
        if (hasText) {
            searchField.setMargin(new Insets(2, 2, 2, 26)); // Increased margin for button space
        } else {
            searchField.setMargin(new Insets(2, 2, 2, 2));
        }

        // Force layout update and ensure button is on top
        SwingUtilities.invokeLater(() -> {
            searchFieldPanel.doLayout();
            searchFieldPanel.revalidate();
            searchFieldPanel.repaint();
            if (hasText) {
                clearButton.repaint(); // Ensure button is painted
            }
        });
    }

    /** Updates search highlights in the target component. */
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
     * Callback method for async search completion. This will be called by async SearchableComponent implementations
     * when search is complete.
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
        updateClearButtonVisibility();
    }

    public void clearSearch() {
        searchField.setText("");
        clearHighlights();
        updateMatchCount(0, 0);
        updateClearButtonVisibility();
    }

    public boolean isCaseSensitive() {
        return caseSensitiveButton.isSelected();
    }

    public void setCaseSensitive(boolean caseSensitive) {
        caseSensitiveButton.setSelected(caseSensitive);
        updateTooltip();
    }

    /** Adds a listener that will be notified when the case-sensitive button is toggled. */
    public void addCaseSensitiveListener(Consumer<Boolean> listener) {
        caseSensitiveListeners.add(listener);
    }

    /** Removes a case-sensitive listener. */
    public void removeCaseSensitiveListener(Consumer<Boolean> listener) {
        caseSensitiveListeners.remove(listener);
    }

    /** Notifies all case-sensitive listeners of a change. */
    private void fireCaseSensitiveChanged(boolean isSelected) {
        for (var listener : caseSensitiveListeners) {
            listener.accept(isSelected);
        }
    }
}
