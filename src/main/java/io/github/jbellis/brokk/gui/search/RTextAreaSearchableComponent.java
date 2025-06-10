package io.github.jbellis.brokk.gui.search;

import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

import javax.swing.*;
import java.awt.*;

/**
 * Implementation of SearchableComponent for RTextArea components.
 * This adapter allows RTextArea (including RSyntaxTextArea) to work
 * with the generic search bar while leveraging RTextArea's built-in
 * search capabilities.
 */
public class RTextAreaSearchableComponent implements SearchableComponent {
    private final RTextArea textArea;
    
    public RTextAreaSearchableComponent(RTextArea textArea) {
        this.textArea = textArea;
    }
    
    @Override
    public String getText() {
        return textArea.getText();
    }
    
    @Override
    public String getSelectedText() {
        return textArea.getSelectedText();
    }
    
    @Override
    public int getCaretPosition() {
        return textArea.getCaretPosition();
    }
    
    @Override
    public void setCaretPosition(int position) {
        textArea.setCaretPosition(position);
    }
    
    @Override
    public void requestFocusInWindow() {
        textArea.requestFocusInWindow();
    }
    
    @Override
    public void highlightAll(String searchText, boolean caseSensitive) {
        if (searchText == null || searchText.trim().isEmpty()) {
            clearHighlights();
            return;
        }
        
        SearchContext context = new SearchContext(searchText);
        context.setMatchCase(caseSensitive);
        context.setMarkAll(true);
        context.setWholeWord(false);
        context.setRegularExpression(false);
        context.setSearchForward(true);
        context.setSearchWrap(true);
        
        SearchEngine.markAll(textArea, context);
    }
    
    @Override
    public void clearHighlights() {
        SearchContext context = new SearchContext();
        context.setMarkAll(false);
        SearchEngine.markAll(textArea, context);
        // Clear the current selection/highlight as well
        textArea.setCaretPosition(textArea.getCaretPosition());
    }
    
    @Override
    public boolean findNext(String searchText, boolean caseSensitive, boolean forward) {
        if (searchText == null || searchText.trim().isEmpty()) {
            return false;
        }
        
        SearchContext context = new SearchContext(searchText);
        context.setMatchCase(caseSensitive);
        context.setMarkAll(true);
        context.setWholeWord(false);
        context.setRegularExpression(false);
        context.setSearchForward(forward);
        context.setSearchWrap(true);
        
        SearchResult result = SearchEngine.find(textArea, context);
        return result.wasFound();
    }
    
    @Override
    public void centerCaretInView() {
        try {
            Rectangle matchRect = textArea.modelToView(textArea.getCaretPosition());
            JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, textArea);
            if (viewport != null && matchRect != null) {
                // Calculate the target Y position (1/3 from the top)
                int viewportHeight = viewport.getHeight();
                int targetY = Math.max(0, (int) (matchRect.y - viewportHeight * 0.33));
                
                // Create a new point for scrolling
                Rectangle viewRect = viewport.getViewRect();
                viewRect.y = targetY;
                textArea.scrollRectToVisible(viewRect);
            }
        } catch (Exception ex) {
            // Silently ignore any view transformation errors
        }
    }
    
    @Override
    public JComponent getComponent() {
        return textArea;
    }
    
    /**
     * Factory method to create a SearchableComponent from an RTextArea.
     * 
     * @param textArea the RTextArea to wrap
     * @return a SearchableComponent that can be used with GenericSearchBar
     */
    public static SearchableComponent wrap(RTextArea textArea) {
        return new RTextAreaSearchableComponent(textArea);
    }
}