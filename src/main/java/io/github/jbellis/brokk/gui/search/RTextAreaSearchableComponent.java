package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.difftool.ui.JMHighlightPainter;
import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import java.awt.*;

/**
 * Implementation of SearchableComponent for RTextArea components.
 */
public class RTextAreaSearchableComponent implements SearchableComponent {
    private final RTextArea textArea;
    private SearchCompleteCallback searchCompleteCallback;
    private final List<Object> highlightTags = new ArrayList<>();
    private boolean shouldJumpToFirstMatch = true;

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
    public SearchCompleteCallback getSearchCompleteCallback() {
        return searchCompleteCallback;
    }

    @Override
    public void highlightAll(String searchText, boolean caseSensitive) {
        if (searchText.trim().isEmpty()) {
            clearHighlights();
            // Notify callback with 0 matches
            var callback = getSearchCompleteCallback();
            if (callback != null) {
                callback.onSearchComplete(0, 0);
            }
            return;
        }

        // Provide immediate feedback that search is starting
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
            
            // Manually add highlights using the highlighter
            var highlighter = textArea.getHighlighter();
            if (highlighter != null) {
                var pattern = Pattern.compile(
                    Pattern.quote(searchText),
                    caseSensitive ? 0 : Pattern.CASE_INSENSITIVE
                );
                var matcher = pattern.matcher(textArea.getText());
                
                while (matcher.find()) {
                    try {
                        var tag = highlighter.addHighlight(
                            matcher.start(),
                            matcher.end(),
                            JMHighlightPainter.SEARCH
                        );
                        highlightTags.add(tag);
                    } catch (BadLocationException e) {
                        // Skip this match if location is invalid
                    }
                }
            }

            // Scroll to first match if any (only if enabled)
            if (shouldJumpToFirstMatch && countMatches(searchText, caseSensitive) > 0) {
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
            var callback = getSearchCompleteCallback();
            if (callback != null) {
                int totalMatches = countMatches(searchText, caseSensitive);
                int currentMatch = getCurrentMatchIndex(searchText, caseSensitive);
                callback.onSearchComplete(totalMatches, currentMatch);
            }
        } catch (Exception e) {
            var callback = getSearchCompleteCallback();
            if (callback != null) {
                callback.onSearchError("Search highlighting failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void clearHighlights() {
        // Remove all manually added highlights
        var highlighter = textArea.getHighlighter();
        if (highlighter != null) {
            for (Object tag : highlightTags) {
                highlighter.removeHighlight(tag);
            }
        }
        highlightTags.clear();
        
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
            var callback = getSearchCompleteCallback();
            if (callback != null) {
                int totalMatches = countMatches(searchText, caseSensitive);
                int currentMatch = getCurrentMatchIndex(searchText, caseSensitive);
                callback.onSearchComplete(totalMatches, currentMatch);
            }
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
                var viewportHeight = viewport.getHeight();
                var targetY = Math.max(0, (int) (matchRect.y - viewportHeight * 0.33));

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

    private int countMatches(String searchText, boolean caseSensitive) {
        if (searchText.trim().isEmpty()) {
            return 0;
        }

        String text = getText();
        if (text.isEmpty()) {
            return 0;
        }

        // Use regex to count matches
        var pattern = Pattern.compile(
            Pattern.quote(searchText),
            caseSensitive ? 0 : Pattern.CASE_INSENSITIVE
        );
        var matcher = pattern.matcher(text);

        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int getCurrentMatchIndex(String searchText, boolean caseSensitive) {
        if (searchText.trim().isEmpty()) {
            return 0;
        }

        String text = getText();
        if (text.isEmpty()) {
            return 0;
        }

        int caretPos = getCaretPosition();

        // Use regex to find all matches and determine current position
        var pattern = Pattern.compile(
            Pattern.quote(searchText),
            caseSensitive ? 0 : Pattern.CASE_INSENSITIVE
        );
        var matcher = pattern.matcher(text);

        int index = 0;
        while (matcher.find()) {
            index++;
            if (caretPos >= matcher.start() && caretPos <= matcher.end()) {
                return index;
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
