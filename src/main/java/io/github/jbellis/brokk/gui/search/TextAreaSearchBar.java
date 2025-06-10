package io.github.jbellis.brokk.gui.search;

import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * A lightweight search bar component specifically designed for RTextArea components,
 * with built-in support for RSyntaxTextArea highlighting.
 */
public class TextAreaSearchBar extends JPanel {
    private final JTextField searchField;
    private final JButton nextButton;
    private final JButton previousButton;
    private final RTextArea targetTextComponent;

    public TextAreaSearchBar(RTextArea targetTextComponent) {
        super(new FlowLayout(FlowLayout.LEFT, 8, 0));
        this.targetTextComponent = targetTextComponent;
        
        // Initialize components
        searchField = new JTextField(20);
        nextButton = new JButton();
        nextButton.setIcon(UIManager.getIcon("Table.descendingSortIcon"));
        previousButton = new JButton();
        previousButton.setIcon(UIManager.getIcon("Table.ascendingSortIcon"));
        
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
                targetTextComponent.requestFocusInWindow();
            }
        });
    }
    
    /**
     * Focuses the search field and optionally populates it with selected text.
     */
    public void focusSearchField() {
        String selected = targetTextComponent.getSelectedText();
        if (selected != null && !selected.isEmpty()) {
            searchField.setText(selected);
        }
        searchField.selectAll();
        searchField.requestFocusInWindow();
        
        // If there's text in the search field, re-highlight matches
        String query = searchField.getText();
        if (query != null && !query.trim().isEmpty()) {
            int originalCaretPosition = targetTextComponent.getCaretPosition();
            updateSearchHighlights(false);
            targetTextComponent.setCaretPosition(originalCaretPosition);
        }
    }
    
    /**
     * Clears all search highlights but keeps the search text.
     */
    public void clearHighlights() {
        SearchContext context = new SearchContext();
        context.setMarkAll(false);
        SearchEngine.markAll(targetTextComponent, context);
        targetTextComponent.setCaretPosition(targetTextComponent.getCaretPosition());
    }
    
    /**
     * Updates search highlights in the text component.
     * 
     * @param jumpToFirst If true, jump to the first occurrence; if false, maintain current position
     */
    private void updateSearchHighlights(boolean jumpToFirst) {
        String query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            clearHighlights();
            return;
        }
        
        SearchContext context = new SearchContext(query);
        context.setMatchCase(false);
        context.setMarkAll(true);
        context.setWholeWord(false);
        context.setRegularExpression(false);
        context.setSearchForward(true);
        context.setSearchWrap(true);
        
        // Mark all occurrences
        SearchEngine.markAll(targetTextComponent, context);
        
        if (jumpToFirst) {
            // Jump to the first occurrence as the user types
            int originalCaretPosition = targetTextComponent.getCaretPosition();
            targetTextComponent.setCaretPosition(0); // Start search from beginning
            SearchResult result = SearchEngine.find(targetTextComponent, context);
            if (!result.wasFound() && originalCaretPosition > 0) {
                // If not found from beginning, restore caret position
                targetTextComponent.setCaretPosition(originalCaretPosition);
            } else if (result.wasFound()) {
                // Center the match in the viewport
                centerCurrentMatchInView();
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
        
        SearchContext context = new SearchContext(query);
        context.setMatchCase(false);
        context.setMarkAll(true);
        context.setWholeWord(false);
        context.setRegularExpression(false);
        context.setSearchForward(forward);
        context.setSearchWrap(true);
        
        SearchResult result = SearchEngine.find(targetTextComponent, context);
        if (result.wasFound()) {
            centerCurrentMatchInView();
        }
    }
    
    /**
     * Centers the current match in the viewport (approximately 1/3 from the top).
     */
    private void centerCurrentMatchInView() {
        try {
            Rectangle matchRect = targetTextComponent.modelToView(targetTextComponent.getCaretPosition());
            JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, targetTextComponent);
            if (viewport != null && matchRect != null) {
                // Calculate the target Y position (1/3 from the top)
                int viewportHeight = viewport.getHeight();
                int targetY = Math.max(0, (int) (matchRect.y - viewportHeight * 0.33));
                
                // Create a new point for scrolling
                Rectangle viewRect = viewport.getViewRect();
                viewRect.y = targetY;
                targetTextComponent.scrollRectToVisible(viewRect);
            }
        } catch (Exception ex) {
            // Silently ignore any view transformation errors
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