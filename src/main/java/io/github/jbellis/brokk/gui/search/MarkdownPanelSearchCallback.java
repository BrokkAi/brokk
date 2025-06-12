package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.mop.stream.HtmlCustomizer;
import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import io.github.jbellis.brokk.gui.mop.stream.TextNodeMarkerCustomizer;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SearchCallback implementation for searching within multiple MarkdownOutputPanels.
 */
public class MarkdownPanelSearchCallback implements SearchCallback {
    private static final Logger logger = LogManager.getLogger(MarkdownPanelSearchCallback.class);
    
    // Constants for configuration
    private static final double SCROLL_POSITION_RATIO = 0.25; // Position marker 1/4 from top of viewport
    private static final boolean REQUIRE_WHOLE_WORD = false; // Don't require whole word matching for better search experience
    
    private final List<MarkdownOutputPanel> panels;
    private String currentSearchTerm = "";
    private boolean currentCaseSensitive = false;
    private final List<Integer> allMarkerIds = new ArrayList<>();
    private int currentMarkerIndex = -1;
    private SearchBarPanel searchBarPanel;
    private Integer previousHighlightedMarkerId = null;
    
    public MarkdownPanelSearchCallback(List<MarkdownOutputPanel> panels) {
        this.panels = panels;
    }
    
    public void setSearchBarPanel(SearchBarPanel panel) {
        assert SwingUtilities.isEventDispatchThread();
        this.searchBarPanel = panel;
    }
    
    @Override
    public SearchResults performSearch(SearchCommand command) {
        assert SwingUtilities.isEventDispatchThread();
        String searchTerm = command.searchText();
        boolean caseSensitive = command.isCaseSensitive();
        
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            stopSearch();
            return SearchResults.noMatches();
        }
        
        final String finalSearchTerm = searchTerm.trim();
        
        // If only case sensitivity changed, we need to ensure old highlights are cleared
        boolean onlyCaseChanged = isOnlyCaseChange(finalSearchTerm, caseSensitive);
        
        this.currentSearchTerm = finalSearchTerm;
        this.currentCaseSensitive = caseSensitive;
        this.previousHighlightedMarkerId = null;
        
        // Create search customizer with CSS classes instead of inline styles
        HtmlCustomizer searchCustomizer = new TextNodeMarkerCustomizer(
            finalSearchTerm,
            caseSensitive,
            REQUIRE_WHOLE_WORD,
            "<span class=\"" + SearchConstants.SEARCH_HIGHLIGHT_CLASS + "\">",
            "</span>"
        );
        
        // Apply search highlighting to all panels and collect marker IDs
        allMarkerIds.clear();
        
        // Track how many panels need to be processed
        var panelCount = panels.size();
        var remainingOperations = new AtomicInteger(panelCount);
        
        // Apply customizer to all panels
        for (MarkdownOutputPanel panel : panels) {
            Runnable processSearchResults = () -> {
                // This runs after each panel's customizer is applied
                if (remainingOperations.decrementAndGet() == 0) {
                    handleSearchComplete();
                }
            };
            
            applySearchToPanel(panel, searchCustomizer, processSearchResults, onlyCaseChanged);
        }
        
        // Return "searching" state since the actual search happens asynchronously
        // The UI should show something like "Searching..." rather than "1 of 1"
        return SearchResults.noMatches();
    }
    
    @Override
    public void goToPreviousResult() {
        assert SwingUtilities.isEventDispatchThread();
        navigateToResult(-1, "goToPreviousResult");
    }
    
    @Override
    public void goToNextResult() {
        assert SwingUtilities.isEventDispatchThread();
        navigateToResult(1, "goToNextResult");
    }
    
    /**
     * Unified navigation method for moving between search results.
     * 
     * @param direction The direction to navigate: 1 for next, -1 for previous
     * @param methodName The name of the calling method for logging
     */
    private void navigateToResult(int direction, String methodName) {
        if (!canNavigate()) {
            return;
        }
        
        int previousIndex = currentMarkerIndex;
        // Move to next/previous match with proper wrap-around
        currentMarkerIndex = Math.floorMod(currentMarkerIndex + direction, allMarkerIds.size());
        
        int currentMarkerId = allMarkerIds.get(currentMarkerIndex);
        logger.trace("{}: Moving from index {} to {} (marker ID: {})", 
                    methodName, previousIndex, currentMarkerIndex, currentMarkerId);
        
        updateCurrentMatchHighlighting();
        scrollToCurrentMarker();
        notifySearchBarPanel();
    }
    
    private void updateCurrentMatchHighlighting() {
        if (allMarkerIds.isEmpty() || currentMarkerIndex < 0 || currentMarkerIndex >= allMarkerIds.size()) {
            return;
        }
        
        int currentMarkerId = allMarkerIds.get(currentMarkerIndex);
        logger.trace("updateCurrentMatchHighlighting: Setting current marker ID to {}", currentMarkerId);
        
        // First, clear previous highlighting if it exists
        if (previousHighlightedMarkerId != null) {
            logger.trace("updateCurrentMatchHighlighting: Clearing previous highlight for marker ID {}", previousHighlightedMarkerId);
            updateMarkerStyleInAllPanels(previousHighlightedMarkerId, false);
        }
        
        // Then highlight the current match
        updateMarkerStyleInAllPanels(currentMarkerId, true);
        previousHighlightedMarkerId = currentMarkerId;
    }
    
    private void updateMarkerStyleInAllPanels(int markerId, boolean isCurrent) {
        // Collect all marker update operations to batch them into a single EDT call
        List<Runnable> updateOperations = new ArrayList<>();
        
        for (MarkdownOutputPanel panel : panels) {
            panel.renderers().forEach(renderer -> {
                updateOperations.add(() -> renderer.updateMarkerStyle(markerId, isCurrent));
            });
        }
        
        // Execute all updates in a single EDT operation for better performance
        SwingUtilities.invokeLater(() -> {
            updateOperations.forEach(Runnable::run);
        });
    }
    
    private void scrollToCurrentMarker() {
        if (currentMarkerIndex < 0 || currentMarkerIndex >= allMarkerIds.size()) {
            return;
        }
        
        int markerId = allMarkerIds.get(currentMarkerIndex);
        logger.trace("Scrolling to marker ID {} (index {} of {})", markerId, currentMarkerIndex + 1, allMarkerIds.size());
        
        // Find which renderer contains this marker and scroll to it
        for (MarkdownOutputPanel panel : panels) {
            // Check all renderers in this panel using the direct API
            Optional<JComponent> foundComponent = panel.renderers()
                .map(renderer -> renderer.findByMarkerId(markerId))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
                
            if (foundComponent.isPresent()) {
                logger.trace("Found component for marker ID {}: {}", markerId, foundComponent.get().getClass().getSimpleName());
                // Scroll the component into view
                SwingUtilities.invokeLater(() -> {
                    JComponent comp = foundComponent.get();
                    
                    // Find the scroll pane and scroll to the component
                    Container parent = comp.getParent();
                    while (parent != null) {
                        if (parent instanceof JScrollPane scrollPane) {
                            Rectangle bounds = comp.getBounds();
                            if (comp.getParent() != scrollPane.getViewport().getView()) {
                                // Convert bounds to viewport coordinates if needed
                                bounds = SwingUtilities.convertRectangle(comp.getParent(), bounds, scrollPane.getViewport().getView());
                            }
                            
                            // Position the found marker near the top of the viewport for better context
                            JViewport viewport = scrollPane.getViewport();
                            Rectangle viewRect = viewport.getViewRect();
                            
                            // Calculate desired position: put the marker at configured position from the top
                            int desiredY = Math.max(0, bounds.y - (int)(viewRect.height * SCROLL_POSITION_RATIO));
                            
                            // Ensure we don't scroll past the end of the content
                            Component view = viewport.getView();
                            int maxY = Math.max(0, view.getHeight() - viewRect.height);
                            desiredY = Math.min(desiredY, maxY);
                            
                            // Set the viewport position directly for precise control
                            viewport.setViewPosition(new Point(viewRect.x, desiredY));
                            
                            logger.trace("Scrolled to position: y={} (marker bounds: {}, viewport height: {})", 
                                       desiredY, bounds, viewRect.height);
                            break;
                        }
                        parent = parent.getParent();
                    }
                });
                return; // Found and scrolled to the marker
            }
        }
    }
    
    @Override
    public void stopSearch() {
        assert SwingUtilities.isEventDispatchThread();
        // Clear search highlighting from all panels
        for (MarkdownOutputPanel panel : panels) {
            panel.setHtmlCustomizer(HtmlCustomizer.DEFAULT);
        }
        currentSearchTerm = "";
        allMarkerIds.clear();
        currentMarkerIndex = -1;
        previousHighlightedMarkerId = null;
        
        // Scroll to top after clearing search
        scrollToTop();
    }
    
    /**
     * Scrolls all panels to the top.
     */
    private void scrollToTop() {
        SwingUtilities.invokeLater(() -> {
            for (MarkdownOutputPanel panel : panels) {
                // Find scroll pane containing this panel
                Container parent = panel.getParent();
                while (parent != null) {
                    if (parent instanceof JScrollPane scrollPane) {
                        scrollPane.getViewport().setViewPosition(new Point(0, 0));
                        break;
                    }
                    parent = parent.getParent();
                }
            }
        });
    }
    
    /**
     * Checks if a marker with the given ID contains text that matches the current search term.
     * This is used to filter out stale markers from previous searches.
     */
    private boolean markerContainsCurrentSearchTerm(IncrementalBlockRenderer renderer, int markerId) {
        if (currentSearchTerm.isEmpty()) {
            return false;
        }
        
        // Find the component containing this marker
        Optional<JComponent> componentOpt = renderer.findByMarkerId(markerId);
        if (componentOpt.isEmpty()) {
            return false;
        }
        
        // Extract text content from the component
        String componentText = extractTextFromComponent(componentOpt.get());
        if (componentText == null || componentText.isEmpty()) {
            return false;
        }
        
        // Check if the component text contains the current search term (respecting case sensitivity)
        if (currentCaseSensitive) {
            return componentText.contains(currentSearchTerm);
        } else {
            return componentText.toLowerCase(Locale.ROOT).contains(currentSearchTerm.toLowerCase(Locale.ROOT));
        }
    }
    
    /**
     * Extracts plain text content from a JComponent (JEditorPane or JLabel).
     */
    private String extractTextFromComponent(JComponent component) {
        if (component instanceof JEditorPane editorPane) {
            // For JEditorPane, get the plain text content
            try {
                return editorPane.getDocument().getText(0, editorPane.getDocument().getLength());
            } catch (Exception e) {
                logger.trace("Failed to extract text from JEditorPane: {}", e.getMessage());
                return "";
            }
        } else if (component instanceof JLabel label) {
            // For JLabel, extract text from HTML
            String html = label.getText();
            if (html != null && html.startsWith("<html>")) {
                // Simple HTML to text conversion - remove all HTML tags
                return html.replaceAll("<[^>]+>", "");
            }
            return html;
        }
        return "";
    }
    
    /**
     * Collects marker IDs from all panels and renderers in visual order (top to bottom).
     * This ensures that search navigation follows the natural reading order of the document.
     */
    private void collectMarkerIdsInVisualOrder() {
        // Helper class to track marker position context
        record MarkerContext(int markerId, int panelIndex, int rendererIndex) {}
        
        List<MarkerContext> markerContexts = new ArrayList<>();
        
        // Collect markers with their position context
        for (int panelIndex = 0; panelIndex < panels.size(); panelIndex++) {
            MarkdownOutputPanel panel = panels.get(panelIndex);
            List<IncrementalBlockRenderer> rendererList = panel.renderers().toList();
            
            for (int rendererIndex = 0; rendererIndex < rendererList.size(); rendererIndex++) {
                IncrementalBlockRenderer renderer = rendererList.get(rendererIndex);
                var markerIds = renderer.getIndexedMarkerIds();
                
                // Marker IDs logged only when needed for debugging
                
                // Convert marker IDs to contexts for sorting, but only include markers that contain the current search term
                for (int markerId : markerIds) {
                    if (markerContainsCurrentSearchTerm(renderer, markerId)) {
                        markerContexts.add(new MarkerContext(markerId, panelIndex, rendererIndex));
                    } else {
                        logger.trace("Filtering out stale marker ID {} that doesn't match current search term '{}'", 
                                   markerId, currentSearchTerm);
                    }
                }
            }
        }
        
        // Sort by visual position: panel index first, then renderer index, then marker ID
        // The marker ID acts as a tiebreaker for markers within the same renderer,
        // maintaining the natural document order since IDs are generated sequentially
        markerContexts.sort((a, b) -> {
            if (a.panelIndex != b.panelIndex) {
                return Integer.compare(a.panelIndex, b.panelIndex);
            }
            if (a.rendererIndex != b.rendererIndex) {
                return Integer.compare(a.rendererIndex, b.rendererIndex);
            }
            return Integer.compare(a.markerId, b.markerId);
        });
        
        // Extract the sorted marker IDs
        allMarkerIds.clear();
        for (MarkerContext context : markerContexts) {
            allMarkerIds.add(context.markerId);
        }
        
    }
    
    public String getCurrentSearchTerm() {
        assert SwingUtilities.isEventDispatchThread();
        return currentSearchTerm;
    }
    
    public SearchResults getCurrentResults() {
        assert SwingUtilities.isEventDispatchThread();
        if (allMarkerIds.isEmpty()) {
            return SearchResults.noMatches();
        }
        return SearchResults.withMatches(allMarkerIds.size(), currentMarkerIndex + 1);
    }
    
    /**
     * Checks if the search change is only a case sensitivity change
     * (same search term, different case sensitivity setting).
     */
    private boolean isOnlyCaseChange(String newTerm, boolean newCaseSensitive) {
        return newTerm.equals(this.currentSearchTerm) && 
               newCaseSensitive != this.currentCaseSensitive;
    }
    
    /**
     * Checks if navigation to search results is possible.
     */
    private boolean canNavigate() {
        return !allMarkerIds.isEmpty() && currentMarkerIndex >= 0;
    }
    
    /**
     * Applies search customizer to a panel, handling case-only changes appropriately.
     */
    private void applySearchToPanel(MarkdownOutputPanel panel, HtmlCustomizer searchCustomizer, 
                                   Runnable onComplete, boolean onlyCaseChanged) {
        if (onlyCaseChanged) {
            // For case-only changes, chain the operations to ensure proper sequencing
            panel.setHtmlCustomizerWithCallback(HtmlCustomizer.DEFAULT, () -> {
                // After clearing is complete, apply the search customizer
                panel.setHtmlCustomizerWithCallback(searchCustomizer, onComplete);
            });
        } else {
            // Normal search - just apply the search customizer
            panel.setHtmlCustomizerWithCallback(searchCustomizer, onComplete);
        }
    }
    
    /**
     * Handles the completion of search processing across all panels.
     */
    private void handleSearchComplete() {
        // All panels processed, now collect marker IDs in visual order
        allMarkerIds.clear();
        collectMarkerIdsInVisualOrder();
        
        
        // Reset current position
        currentMarkerIndex = allMarkerIds.isEmpty() ? -1 : 0;
        
        if (!allMarkerIds.isEmpty()) {
            handleSearchResults();
        } else {
            handleNoSearchResults();
        }
        
        notifySearchBarPanel();
    }
    
    /**
     * Handles the case when search results are found.
     */
    private void handleSearchResults() {
        // Highlight the first match as current
        updateCurrentMatchHighlighting();
        // Scroll to the first match
        scrollToCurrentMarker();
    }
    
    /**
     * Handles the case when no search results are found.
     */
    private void handleNoSearchResults() {
        // No matches found - clear search state and highlighting, then scroll to top
        
        // Clear search state first
        currentSearchTerm = "";
        allMarkerIds.clear();
        currentMarkerIndex = -1;
        previousHighlightedMarkerId = null;
        
        // Clear highlighting from all panels
        for (MarkdownOutputPanel p : panels) {
            p.setHtmlCustomizer(HtmlCustomizer.DEFAULT);
        }
        
        // Scroll to top when no matches found
        scrollToTop();
    }
    
    /**
     * Notifies the search bar panel of current results.
     */
    private void notifySearchBarPanel() {
        if (searchBarPanel != null) {
            SwingUtilities.invokeLater(() -> {
                searchBarPanel.updateSearchResults(getCurrentResults());
            });
        }
    }
}
