package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.difftool.ui.JMHighlightPainter;
import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

import javax.swing.*;
import java.awt.*;

/**
 * Implementation of SearchableComponent for RTextArea components.
 */
public class RTextAreaSearchableComponent extends BaseSearchableComponent {
    private final RTextArea textArea;
    private final HighlightManager highlightManager;
    private boolean shouldJumpToFirstMatch = true;

    public RTextAreaSearchableComponent(RTextArea textArea) {
        this.textArea = textArea;
        this.highlightManager = new HighlightManager(textArea);
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
        if (searchText.trim().isEmpty()) {
            clearHighlights();
            notifySearchComplete(0, 0);
            return;
        }

        updateSearchState(searchText, caseSensitive);
        notifySearchStart(searchText);

        var context = new SearchContext(searchText);
        context.setMatchCase(caseSensitive);
        context.setMarkAll(true);
        context.setWholeWord(false);
        context.setRegularExpression(false);
        context.setSearchForward(true);
        context.setSearchWrap(true);

        try {
            // Clear existing highlights first
            clearHighlights();
            
            // Find all matches and highlight them
            var matches = SearchPatternUtils.findAllMatches(textArea.getText(), searchText, caseSensitive);
            highlightManager.highlightAllMatches(matches, -1); // -1 means no current match

            // Scroll to first match if any (only if enabled)
            if (shouldJumpToFirstMatch && !matches.isEmpty()) {
                // Save current position
                var originalPosition = textArea.getCaretPosition();
                textArea.setCaretPosition(0);

                // Find and jump to first match
                var findContext = new SearchContext(searchText);
                findContext.setMatchCase(caseSensitive);
                findContext.setSearchForward(true);
                var result = SearchEngine.find(textArea, findContext);

                if (!result.wasFound() && originalPosition > 0) {
                    // Restore position if no match found
                    textArea.setCaretPosition(originalPosition);
                } else if (result.wasFound()) {
                    // Center the first match
                    centerCaretInView();
                }
            }

            // For sync implementation, immediately notify callback with results
            int totalMatches = matches.size();
            int currentMatch = getCurrentMatchIndex(searchText, caseSensitive);
            notifySearchComplete(totalMatches, currentMatch);
        } catch (Exception e) {
            notifySearchError("Search highlighting failed: " + e.getMessage());
        }
    }

    @Override
    public void clearHighlights() {
        highlightManager.clearHighlights();
        
        // Clear the current selection/highlight as well
        textArea.setCaretPosition(textArea.getCaretPosition());
    }

    @Override
    public boolean findNext(String searchText, boolean caseSensitive, boolean forward) {
        if (searchText.trim().isEmpty()) {
            return false;
        }

        var context = new SearchContext(searchText);
        context.setMatchCase(caseSensitive);
        context.setMarkAll(true);
        context.setWholeWord(false);
        context.setRegularExpression(false);
        context.setSearchForward(forward);
        context.setSearchWrap(true);

        var result = SearchEngine.find(textArea, context);
        boolean found = result.wasFound();

        // Notify callback with updated match index
        if (found) {
            int totalMatches = SearchPatternUtils.countMatches(textArea.getText(), searchText, caseSensitive);
            int currentMatch = getCurrentMatchIndex(searchText, caseSensitive);
            notifySearchComplete(totalMatches, currentMatch);
        }

        return found;
    }

    @Override
    public void centerCaretInView() {
        try {
            var matchRect = textArea.modelToView2D(textArea.getCaretPosition()).getBounds();
            var viewport = ScrollingUtils.findParentViewport(textArea);
            if (viewport != null && matchRect != null) {
                ScrollingUtils.centerRectInViewport(viewport, matchRect, 0.33);
            }
        } catch (Exception ex) {
            // Silently ignore any view transformation errors
        }
    }

    @Override
    public JComponent getComponent() {
        return textArea;
    }


    private int getCurrentMatchIndex(String searchText, boolean caseSensitive) {
        int caretPos = getCaretPosition();
        var matches = SearchPatternUtils.findAllMatches(getText(), searchText, caseSensitive);
        
        for (int i = 0; i < matches.size(); i++) {
            int[] match = matches.get(i);
            if (caretPos >= match[0] && caretPos <= match[1]) {
                return i + 1; // 1-based index
            }
        }
        
        return 0; // No current match
    }

    public void setShouldJumpToFirstMatch(boolean shouldJump) {
        this.shouldJumpToFirstMatch = shouldJump;
    }
    
    public static SearchableComponent wrap(RTextArea textArea) {
        return new RTextAreaSearchableComponent(textArea);
    }
    
    public static RTextAreaSearchableComponent wrapWithoutJumping(RTextArea textArea) {
        var component = new RTextAreaSearchableComponent(textArea);
        component.setShouldJumpToFirstMatch(false);
        return component;
    }
}
