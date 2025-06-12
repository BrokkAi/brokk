package io.github.jbellis.brokk.gui.search;

import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;


import java.util.regex.Pattern;
import java.util.regex.Matcher;
import javax.swing.*;
import java.awt.*;

/**
 * Implementation of SearchableComponent for RTextArea components.
 */
public class RTextAreaSearchableComponent implements SearchableComponent {
    private final RTextArea textArea;
    private SearchCompleteCallback searchCompleteCallback;

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
    public void setSearchCompleteCallback(SearchCompleteCallback callback) {
        this.searchCompleteCallback = callback;
    }

    @Override
    public void highlightAll(String searchText, boolean caseSensitive) {
        if (searchText == null || searchText.trim().isEmpty()) {
            clearHighlights();
            // Notify callback with 0 matches
            if (searchCompleteCallback != null) {
                searchCompleteCallback.onSearchComplete(0, 0);
            }
            return;
        }

        var context = new SearchContext(searchText);
        context.setMatchCase(caseSensitive);
        context.setMarkAll(true);
        context.setWholeWord(false);
        context.setRegularExpression(false);
        context.setSearchForward(true);
        context.setSearchWrap(true);

        SearchEngine.markAll(textArea, context);
        
        // For sync implementation, immediately notify callback with results
        if (searchCompleteCallback != null) {
            int totalMatches = countMatches(searchText, caseSensitive);
            int currentMatch = getCurrentMatchIndex(searchText, caseSensitive);
            searchCompleteCallback.onSearchComplete(totalMatches, currentMatch);
        }
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

        var result = SearchEngine.find(textArea, context);
        boolean found = result.wasFound();
        
        // Notify callback with updated match index
        if (found && searchCompleteCallback != null) {
            int totalMatches = countMatches(searchText, caseSensitive);
            int currentMatch = getCurrentMatchIndex(searchText, caseSensitive);
            searchCompleteCallback.onSearchComplete(totalMatches, currentMatch);
        }
        
        return found;
    }

    @Override
    public void centerCaretInView() {
        try {
            var matchRect = textArea.modelToView(textArea.getCaretPosition());
            var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, textArea);
            if (viewport != null && matchRect != null) {
                // Calculate the target Y position (1/3 from the top)
                int viewportHeight = viewport.getHeight();
                int targetY = Math.max(0, (int) (matchRect.y - viewportHeight * 0.33));

                // Create a new point for scrolling
                var viewRect = viewport.getViewRect();
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
    
    // Helper methods for internal use
    private int countMatches(String searchText, boolean caseSensitive) {
        if (searchText == null || searchText.trim().isEmpty()) {
            return 0;
        }

        String text = getText();
        if (text.isEmpty()) {
            return 0;
        }

        // Use regex to count matches
        Pattern pattern = Pattern.compile(
            Pattern.quote(searchText),
            caseSensitive ? 0 : Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(text);

        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int getCurrentMatchIndex(String searchText, boolean caseSensitive) {
        if (searchText == null || searchText.trim().isEmpty()) {
            return 0;
        }

        String text = getText();
        if (text.isEmpty()) {
            return 0;
        }

        int caretPos = getCaretPosition();

        // Use regex to find all matches and determine current position
        Pattern pattern = Pattern.compile(
            Pattern.quote(searchText),
            caseSensitive ? 0 : Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(text);

        int index = 0;
        while (matcher.find()) {
            index++;
            if (caretPos >= matcher.start() && caretPos <= matcher.end()) {
                return index;
            }
        }

        return 0; // No current match
    }

    public static SearchableComponent wrap(RTextArea textArea) {
        return new RTextAreaSearchableComponent(textArea);
    }
}
