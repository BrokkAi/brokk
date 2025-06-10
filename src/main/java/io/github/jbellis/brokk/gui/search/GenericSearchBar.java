package io.github.jbellis.brokk.gui.search;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * A completely generic search bar component that works with any component
 * implementing the SearchableComponent interface. This provides maximum
 * reusability across different types of text components.
 */
public class GenericSearchBar extends JPanel {
    private final JTextField searchField;
    private final JButton nextButton;
    private final JButton previousButton;
    private final SearchableComponent targetComponent;

    public GenericSearchBar(SearchableComponent targetComponent) {
        super(new FlowLayout(FlowLayout.LEFT, 8, 0));
        this.targetComponent = targetComponent;
        
        // Initialize components
        searchField = new JTextField(20);
        nextButton = new JButton("↓");
        previousButton = new JButton("↑");
        
        // Build UI
        add(new JLabel("Search:"));
        add(searchField);
        add(previousButton);
        add(nextButton);
        
        // Setup event handlers
        setupEventHandlers();
        setupKeyboardShortcuts();
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
        searchField.addActionListener(e -> findNext(true));
        
        // Button actions
        nextButton.addActionListener(e -> findNext(true));
        previousButton.addActionListener(e -> findNext(false));
    }
    
    private void setupKeyboardShortcuts() {
        InputMap inputMap = searchField.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = searchField.getActionMap();
        
        // Down arrow for next match
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "findNext");
        actionMap.put("findNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findNext(true);
            }
        });
        
        // Up arrow for previous match
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "findPrevious");
        actionMap.put("findPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findNext(false);
            }
        });
    }
    
    /**
     * Registers global keyboard shortcuts for the search bar.
     * 
     * @param parentComponent The component to register shortcuts on (typically the parent panel)
     */
    public void registerGlobalShortcuts(JComponent parentComponent) {
        // Cmd/Ctrl+F focuses the search field
        KeyStroke ctrlF = KeyStroke.getKeyStroke(KeyEvent.VK_F, 
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        parentComponent.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ctrlF, "focusSearch");
        parentComponent.getActionMap().put("focusSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                focusSearchField();
            }
        });
        
        // ESC key clears search highlights when search field has focus
        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
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
            return;
        }
        
        // Highlight all occurrences (case-insensitive by default)
        targetComponent.highlightAll(query, false);
        
        if (jumpToFirst) {
            // Jump to the first occurrence as the user types
            int originalCaretPosition = targetComponent.getCaretPosition();
            targetComponent.setCaretPosition(0); // Start search from beginning
            boolean found = targetComponent.findNext(query, false, true);
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
     * Finds the next or previous match relative to the current caret position.
     * 
     * @param forward true = next match; false = previous match
     */
    private void findNext(boolean forward) {
        String query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        
        boolean found = targetComponent.findNext(query, false, forward);
        if (found) {
            targetComponent.centerCaretInView();
        }
    }
    
    /**
     * Gets the current search text.
     */
    public String getSearchText() {
        return searchField.getText();
    }
    
    /**
     * Sets the search text.
     */
    public void setSearchText(String text) {
        searchField.setText(text);
    }
    
    /**
     * Clears the search text and highlights.
     */
    public void clearSearch() {
        searchField.setText("");
        clearHighlights();
    }
}