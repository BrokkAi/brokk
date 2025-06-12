package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.mop.stream.HtmlCustomizer;
import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import io.github.jbellis.brokk.gui.mop.stream.TextNodeMarkerCustomizer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SearchableComponent adapter for MarkdownOutputPanel(s).
 * This bridges the SearchableComponent interface with MarkdownPanelSearchCallback functionality.
 */
public class MarkdownOutputPanelSearchableComponent implements SearchableComponent {
    private static final Logger logger = LogManager.getLogger(MarkdownOutputPanelSearchableComponent.class);
    
    // Constants for configuration
    private static final double SCROLL_POSITION_RATIO = 0.25; // Position marker 1/4 from top of viewport
    private static final boolean REQUIRE_WHOLE_WORD = false; // Don't require whole word matching for better search experience
    
    private final List<MarkdownOutputPanel> panels;
    private String currentSearchTerm = "";
    private boolean currentCaseSensitive = false;
    private final List<Integer> allMarkerIds = new ArrayList<>();
    private int currentMarkerIndex = -1;
    private Integer previousHighlightedMarkerId = null;
    private SearchCompleteCallback searchCompleteCallback = null;
    
    public MarkdownOutputPanelSearchableComponent(List<MarkdownOutputPanel> panels) {
        this.panels = panels;
    }
    
    /**
     * Creates an adapter for a single MarkdownOutputPanel.
     */
    public static MarkdownOutputPanelSearchableComponent wrap(MarkdownOutputPanel panel) {
        return new MarkdownOutputPanelSearchableComponent(List.of(panel));
    }
    
    @Override
    public String getText() {
        return panels.stream()
                .map(MarkdownOutputPanel::getText)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n\n" + b);
    }
    
    @Override
    public String getSelectedText() {
        return panels.stream()
                .map(MarkdownOutputPanel::getSelectedText)
                .filter(text -> text != null && !text.isEmpty())
                .findFirst()
                .orElse(null);
    }
    
    @Override
    public int getCaretPosition() {
        // Find the focused text component and return its caret position
        var focusedComponent = findFocusedTextComponent();
        return focusedComponent != null ? focusedComponent.getCaretPosition() : 0;
    }
    
    @Override
    public void setCaretPosition(int position) {
        // Find the focused text component and set its caret position
        var focusedComponent = findFocusedTextComponent();
        if (focusedComponent != null) {
            try {
                focusedComponent.setCaretPosition(Math.min(position, focusedComponent.getDocument().getLength()));
            } catch (Exception e) {
                logger.trace("Failed to set caret position: {}", e.getMessage());
            }
        }
    }
    
    @Override
    public void requestFocusInWindow() {
        if (!panels.isEmpty()) {
            panels.get(0).requestFocusInWindow();
        }
    }
    
    @Override
    public void highlightAll(String searchText, boolean caseSensitive) {
        if (searchText == null || searchText.trim().isEmpty()) {
            clearHighlights();
            return;
        }
        
        final String finalSearchTerm = searchText.trim();
        this.currentSearchTerm = finalSearchTerm;
        this.currentCaseSensitive = caseSensitive;
        this.previousHighlightedMarkerId = null;
        
        // Create search customizer with CSS classes
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
            
            panel.setHtmlCustomizerWithCallback(searchCustomizer, processSearchResults);
        }
    }
    
    @Override
    public void clearHighlights() {
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
    
    @Override
    public boolean findNext(String searchText, boolean caseSensitive, boolean forward) {
        // If search term or case sensitivity changed, re-trigger search
        if (!searchText.equals(currentSearchTerm) || caseSensitive != currentCaseSensitive) {
            highlightAll(searchText, caseSensitive);
            // The search is async, so we can't navigate yet
            return false;
        }
        
        if (!canNavigate()) {
            return false;
        }
        
        int direction = forward ? 1 : -1;
        int previousIndex = currentMarkerIndex;
        
        // Move to next/previous match with proper wrap-around
        currentMarkerIndex = Math.floorMod(currentMarkerIndex + direction, allMarkerIds.size());
        
        int currentMarkerId = allMarkerIds.get(currentMarkerIndex);
        logger.trace("findNext: Moving from index {} to {} (marker ID: {})", 
                    previousIndex, currentMarkerIndex, currentMarkerId);
        
        updateCurrentMatchHighlighting();
        scrollToCurrentMarker();
        
        // Update the search bar with new position
        if (searchCompleteCallback != null) {
            searchCompleteCallback.onSearchComplete(allMarkerIds.size(), currentMarkerIndex + 1);
        }
        
        return true;
    }
    
    @Override
    public void centerCaretInView() {
        // This is called after a successful search - scroll to current marker
        scrollToCurrentMarker();
    }
    
    @Override
    public JComponent getComponent() {
        // Return the first panel as the representative component
        return panels.isEmpty() ? new JPanel() : panels.get(0);
    }
    
    // Helper methods (adapted from MarkdownPanelSearchCallback)
    
    private void handleSearchComplete() {
        // All panels processed, now collect marker IDs in visual order
        allMarkerIds.clear();
        collectMarkerIdsInVisualOrder();
        
        // Reset current position
        currentMarkerIndex = allMarkerIds.isEmpty() ? -1 : 0;
        
        if (!allMarkerIds.isEmpty()) {
            // Highlight the first match as current
            updateCurrentMatchHighlighting();
        }
        
        // Notify the GenericSearchBar that search is complete
        if (searchCompleteCallback != null) {
            int matchIndex = allMarkerIds.isEmpty() ? 0 : currentMarkerIndex + 1; // 1-based
            searchCompleteCallback.onSearchComplete(allMarkerIds.size(), matchIndex);
        }
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
            return componentText.toLowerCase().contains(currentSearchTerm.toLowerCase());
        }
    }
    
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
    
    private boolean canNavigate() {
        return !allMarkerIds.isEmpty() && currentMarkerIndex >= 0;
    }
    
    @Override
    public void setSearchCompleteCallback(SearchCompleteCallback callback) {
        this.searchCompleteCallback = callback;
    }
    
    /**
     * Finds the currently focused JTextComponent within any of the panels.
     */
    private javax.swing.text.JTextComponent findFocusedTextComponent() {
        for (MarkdownOutputPanel panel : panels) {
            var focused = findFocusedTextComponentIn(panel);
            if (focused != null) {
                return focused;
            }
        }
        return null;
    }
    
    /**
     * Helper method to find a focused JTextComponent within a given component hierarchy.
     */
    private javax.swing.text.JTextComponent findFocusedTextComponentIn(Component comp) {
        if (comp instanceof javax.swing.text.JTextComponent tc && tc.isFocusOwner()) {
            return tc;
        }

        if (comp instanceof Container container) {
            for (var child : container.getComponents()) {
                var focused = findFocusedTextComponentIn(child);
                if (focused != null) {
                    return focused;
                }
            }
        }
        return null;
    }
}