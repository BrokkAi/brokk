package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.difftool.ui.JMHighlightPainter;
import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.mop.stream.HtmlCustomizer;
import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import io.github.jbellis.brokk.gui.mop.stream.TextNodeMarkerCustomizer;
import io.github.jbellis.brokk.gui.mop.util.ComponentUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * SearchableComponent adapter for MarkdownOutputPanel(s).
 * This bridges the SearchableComponent interface with MarkdownPanelSearchCallback functionality,
 * supporting search in both Markdown text and code blocks (RSyntaxTextArea).
 */
public class MarkdownSearchableComponent extends BaseSearchableComponent {
    private static final Logger logger = LogManager.getLogger(MarkdownSearchableComponent.class);

    // Debug flag - set to true to enable detailed search debugging
    // When enabled, outputs debug logs (at DEBUG level) showing marker collection, navigation steps, and HTML contexts
    private static final boolean DEBUG_SEARCH_COLLECTION = false;

    // Constants for configuration
    private static final boolean REQUIRE_WHOLE_WORD = false; // Don't require whole word matching for better search experience

    private final List<MarkdownOutputPanel> panels;
    private final MarkdownSearchDebugger debugger;

    private final List<SearchMatch> allMatches = new ArrayList<>();
    private int currentMatchIndex = -1;
    @Nullable
    private SearchMatch previousMatch = null;
    private final List<RTextAreaSearchableComponent> codeSearchComponents = new ArrayList<>();


    public MarkdownSearchableComponent(List<MarkdownOutputPanel> panels) {
        this.panels = panels;
        this.debugger = new MarkdownSearchDebugger(DEBUG_SEARCH_COLLECTION);
    }

    /**
     * Creates an adapter for a single MarkdownOutputPanel.
     */
    public static MarkdownSearchableComponent wrap(MarkdownOutputPanel panel) {
        return new MarkdownSearchableComponent(List.of(panel));
    }

    @Override
    public String getText() {
        String result = panels.stream()
                .map(p -> p.getText())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n\n" + b);
        return result;
    }

    @Override
    public String getSelectedText() {
        return panels.stream()
                .map(MarkdownOutputPanel::getSelectedText)
                .filter(text -> !text.isEmpty())
                .findFirst()
                .orElse("");
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
            panels.getFirst().requestFocusInWindow();
        }
    }

    @Override
    public void highlightAll(String searchText, boolean caseSensitive) {
        if (searchText.trim().isEmpty()) {
            clearHighlights();
            // Still notify callback for empty searches
            notifySearchComplete(0, 0);
            return;
        }

        final String finalSearchTerm = searchText.trim();
        updateSearchState(finalSearchTerm, caseSensitive);

        // No render listener needed for initial scroll - we'll handle it in handleSearchComplete

        // Provide immediate feedback that search is starting
        notifySearchStart(finalSearchTerm);
        this.previousMatch = null;
        this.allMatches.clear();
        this.codeSearchComponents.clear();

        // Don't highlight code components here - they will be recreated when markdown is re-rendered
        // We'll handle code highlighting in handleSearchComplete after markdown rendering is done

        // Create search customizer for Markdown content
        HtmlCustomizer searchCustomizer = new TextNodeMarkerCustomizer(
            finalSearchTerm,
            caseSensitive,
            REQUIRE_WHOLE_WORD,
            "<span class=\"" + SearchConstants.SEARCH_HIGHLIGHT_CLASS + "\">",
            "</span>"
        );

        // Track how many panels need to be processed for Markdown highlighting
        var panelCount = panels.size();
        if (panelCount == 0) {
            handleSearchComplete(); // No panels, complete immediately
            return;
        }
        var remainingMarkdownOperations = new AtomicInteger(panelCount);

        // Apply customizer to all panels for Markdown
        for (MarkdownOutputPanel panel : panels) {
            Runnable processMarkdownSearchResults = () -> {
                if (remainingMarkdownOperations.decrementAndGet() == 0) {
                    handleSearchComplete(); // All Markdown highlighting done, now consolidate
                }
            };

            // No render listener needed for initial scroll
//            try {
//                panel.setHtmlCustomizerWithCallback(searchCustomizer, processMarkdownSearchResults);
//            } catch (Exception e) {
//                logger.error("Error applying search customizer to panel for Markdown", e);
//                notifySearchError("Search failed during Markdown highlighting: " + e.getMessage());
//                // Even if one panel fails, try to complete with what we have
//                if (remainingMarkdownOperations.decrementAndGet() == 0) {
//                    handleSearchComplete();
//                }
//            }
        }
    }

    @Override
    public void clearHighlights() {
        // Clear Markdown highlights
        for (MarkdownOutputPanel panel : panels) {
            // panel.setHtmlCustomizer(HtmlCustomizer.DEFAULT);
        }
        // Clear code highlights
        for (RTextAreaSearchableComponent codeComp : codeSearchComponents) {
            codeComp.clearHighlights();
            if (codeComp.getComponent() instanceof RSyntaxTextArea rsta) {
                // Clear selection by setting selection start and end to the same position
                int caretPos = rsta.getCaretPosition();
                rsta.select(caretPos, caretPos);
            }
        }
        codeSearchComponents.clear();

        currentSearchTerm = "";
        allMatches.clear();
        currentMatchIndex = -1;
        previousMatch = null;

        scrollToTop();
    }

    @Override
    public boolean findNext(String searchText, boolean caseSensitive, boolean forward) {
        if (hasSearchChanged(searchText, caseSensitive)) {
            highlightAll(searchText, caseSensitive);
            return false; // Search is async, navigation will occur after handleSearchComplete
        }

        if (!canNavigate()) {
            return false;
        }

        var direction = forward ? 1 : -1;
        int oldIndex = currentMatchIndex;
        currentMatchIndex = Math.floorMod(currentMatchIndex + direction, allMatches.size());

        debugger.logNavigation(forward, oldIndex, currentMatchIndex, allMatches);

        updateCurrentMatchHighlighting();
        // Ensure scroll happens after highlighting is complete
        SwingUtilities.invokeLater(this::scrollToCurrentMatch);

        notifySearchComplete(allMatches.size(), currentMatchIndex + 1);
        return true;
    }

    @Override
    public void centerCaretInView() {
        scrollToCurrentMatch();
    }

    @Override
    public JComponent getComponent() {
        return panels.isEmpty() ? new JPanel() : panels.getFirst();
    }

    private void handleSearchComplete() {
        // Now that markdown rendering is complete, find and highlight code components
        highlightCodeComponents();

        collectMatchesInVisualOrder(); // Populates and sorts allMatches

        // Print detailed block and hit information
        printSearchResults();

        currentMatchIndex = allMatches.isEmpty() ? -1 : 0;
        previousMatch = null; // Reset previous match before new highlighting sequence

        if (!allMatches.isEmpty()) {
            updateCurrentMatchHighlighting();
            // Scroll to first match after a short delay to ensure rendering is complete
            SwingUtilities.invokeLater(() -> {
                if (!allMatches.isEmpty()) { // Double-check in case of race condition
                    SearchMatch firstMatch = allMatches.getFirst();
                    debugger.logInitialScroll(firstMatch);
                    scrollToCurrentMatch();
                }
            });
        }

        int total = allMatches.size();
        int currentIdxDisplay = total == 0 ? 0 : currentMatchIndex + 1;
        notifySearchComplete(total, currentIdxDisplay);
    }

    private void updateMarkdownMarkerStyle(int markerId, boolean isCurrent) {
        SwingUtilities.invokeLater(() -> { // Ensure UI updates on EDT
//            for (MarkdownOutputPanel panel : panels) {
//                panel.renderers().forEach(renderer -> {
//                    if (renderer.findByMarkerId(markerId).isPresent()) {
//                        renderer.updateMarkerStyle(markerId, isCurrent);
//                    }
//                });
//            }
        });
    }

    private void updateCurrentMatchHighlighting() {
        if (previousMatch != null) {
            switch (previousMatch) {
                case MarkdownSearchMatch markdownMatch -> {
                    updateMarkdownMarkerStyle(markdownMatch.markerId(), false);
                }
                case CodeSearchMatch codeMatch -> {
                    if (previousMatch.actualUiComponent() instanceof RSyntaxTextArea ta) {
                        // Change the previous current match back to regular highlight
                        var highlighter = ta.getHighlighter();
                        if (highlighter != null) {
                            // Find and update the CURRENT_SEARCH highlight back to SEARCH
                            for (var highlight : highlighter.getHighlights()) {
                                if (highlight.getPainter() == JMHighlightPainter.CURRENT_SEARCH &&
                                    highlight.getStartOffset() == codeMatch.startOffset() &&
                                    highlight.getEndOffset() == codeMatch.endOffset()) {
                                    highlighter.removeHighlight(highlight);
                                    try {
                                        highlighter.addHighlight(codeMatch.startOffset(),
                                                               codeMatch.endOffset(),
                                                               JMHighlightPainter.SEARCH);
                                    } catch (BadLocationException e) {
                                        // Ignore
                                    }
                                    break;
                                }
                            }
                        }
                        // Clear selection
                        ta.setSelectionStart(ta.getCaretPosition());
                        ta.setSelectionEnd(ta.getCaretPosition());
                    }
                }
            }
        }

        if (allMatches.isEmpty() || currentMatchIndex < 0 || currentMatchIndex >= allMatches.size()) {
            previousMatch = null;
            return;
        }

        SearchMatch currentMatch = allMatches.get(currentMatchIndex);
        switch (currentMatch) {
            case MarkdownSearchMatch markdownMatch -> {
                updateMarkdownMarkerStyle(markdownMatch.markerId(), true);
            }
            case CodeSearchMatch codeMatch -> {
                if (currentMatch.actualUiComponent() instanceof RSyntaxTextArea ta) {
                // For code matches, we need to highlight the current match differently
                // First, re-apply all highlights with SEARCH painter
                var highlighter = ta.getHighlighter();
                if (highlighter != null) {
                    // Remove all existing highlights
                    for (var highlight : highlighter.getHighlights()) {
                        if (highlight.getPainter() == JMHighlightPainter.SEARCH ||
                            highlight.getPainter() == JMHighlightPainter.CURRENT_SEARCH) {
                            highlighter.removeHighlight(highlight);
                        }
                    }

                    // Re-add all matches for this text area
                    var ranges = countMatchesInTextArea(ta, currentSearchTerm, currentCaseSensitive);
                    for (int[] range : ranges) {
                        try {
                            // Use CURRENT_SEARCH for the current match, SEARCH for others
                            var painter = (range[0] == codeMatch.startOffset() && range[1] == codeMatch.endOffset())
                                          ? JMHighlightPainter.CURRENT_SEARCH
                                          : JMHighlightPainter.SEARCH;
                            highlighter.addHighlight(range[0], range[1], painter);
                        } catch (BadLocationException e) {
                            // Skip invalid ranges
                        }
                    }
                }

                    // Only set selection if this component already has focus
                    // This prevents stealing focus from markdown on initial search
                    if (ta.hasFocus()) {
                        ta.select(codeMatch.startOffset(), codeMatch.endOffset());
                    }
                }
            }
        }
        previousMatch = currentMatch;
    }


    private void scrollToCurrentMatch() {
        if (currentMatchIndex < 0 || currentMatchIndex >= allMatches.size()) {
            return;
        }
        SearchMatch match = allMatches.get(currentMatchIndex);
        if (match.actualUiComponent() instanceof JComponent jc) {
            scrollToComponent(jc);
        } else {
            logger.warn("Cannot scroll to match, actualUiComponent is not a JComponent: {}", match.actualUiComponent());
        }
    }


    private void scrollToComponent(JComponent compToScroll) {
        // Scroll to put the component at the top of the viewport (positionRatio = 0.0)
        ScrollingUtils.scrollToComponent(compToScroll, 0.0);
    }


    private void scrollToTop() {
        SwingUtilities.invokeLater(() -> {
            if (panels.isEmpty() || panels.getFirst().getParent() == null) return;

            JScrollPane scrollPane = ScrollingUtils.findParentScrollPane(panels.getFirst());
            if (scrollPane != null) {
                scrollPane.getViewport().setViewPosition(new Point(0, 0));
            }
        });
    }

    private List<int[]> countMatchesInTextArea(RSyntaxTextArea textArea, String searchText, boolean caseSensitive) {
        if (searchText.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String textContent;
        try {
            textContent = textArea.getText();
        } catch (NullPointerException e) { // getText can throw NPE if document is null
            logger.warn("RSyntaxTextArea document was null, cannot search.", e);
            return new ArrayList<>();
        }
        return SearchPatternUtils.findAllMatches(textContent, searchText, caseSensitive);
    }

    private void collectMatchesInVisualOrder() {
        allMatches.clear();
        var tempMatches = new ArrayList<SearchMatch>();

//        for (int panelIdx = 0; panelIdx < panels.size(); panelIdx++) {
//            MarkdownOutputPanel panel = panels.get(panelIdx);
//            List<IncrementalBlockRenderer> renderers = panel.renderers().toList();
//
//            for (int rendererIdx = 0; rendererIdx < renderers.size(); rendererIdx++) {
//                IncrementalBlockRenderer renderer = renderers.get(rendererIdx);
//                JComponent rendererRoot = renderer.getRoot();
//                Component[] componentsInRenderer = rendererRoot.getComponents();
//
//                // Track processed components to avoid duplicates
//                var processedComponents = new IdentityHashMap<Component, Boolean>();
//
//                // Recursively collect matches from all components and their nested children
//                for (int compVisOrder = 0; compVisOrder < componentsInRenderer.length; compVisOrder++) {
//                    Component comp = componentsInRenderer[compVisOrder];
//                    var subComponentCounter = new AtomicInteger(0);
//                    collectMatchesFromComponent(comp, renderer, panelIdx, rendererIdx, compVisOrder, subComponentCounter, tempMatches, processedComponents);
//                }
//            }
//        }
        Collections.sort(tempMatches); // Sort using SearchMatch.compareTo
        allMatches.addAll(tempMatches);
    }

    private void collectMatchesFromComponent(Component comp, IncrementalBlockRenderer renderer,
                                           int panelIdx, int rendererIdx, int compVisOrder,
                                           AtomicInteger subComponentCounter, List<SearchMatch> tempMatches,
                                           IdentityHashMap<Component, Boolean> processedComponents) {

        // Skip if we've already processed this component
        if (processedComponents.containsKey(comp)) {
            return;
        }

        // Mark this component as processed
        processedComponents.put(comp, true);

        // Debug: Show all available marker IDs at the renderer level for the first component
        if (compVisOrder == 0 && comp instanceof JEditorPane) {
            debugger.logRendererDebug(panelIdx, rendererIdx, renderer);
        }

        // Check if this component itself has matches
        if (comp instanceof JEditorPane editor) {
            // Try both indexed markers and direct DOM scanning
            collectMarkdownMatches(editor, renderer, panelIdx, rendererIdx, compVisOrder, subComponentCounter, tempMatches);
        } else if (comp instanceof JLabel label) {
            // Try both indexed markers and direct DOM scanning
            collectMarkdownMatches(label, renderer, panelIdx, rendererIdx, compVisOrder, subComponentCounter, tempMatches);
        } else if (comp instanceof RSyntaxTextArea textArea) {
            // Code matches
            RTextAreaSearchableComponent rsc = codeSearchComponents.stream()
                .filter(cs -> cs.getComponent() == textArea)
                .findFirst().orElse(null);

            if (rsc != null) {
                List<int[]> ranges = countMatchesInTextArea(textArea, currentSearchTerm, currentCaseSensitive);
                if (!ranges.isEmpty()) {
                    // All ranges within this component share the same sub-component index
                    int subIdx = subComponentCounter.getAndIncrement();
                    for (int[] range : ranges) {
                        tempMatches.add(new CodeSearchMatch(rsc, range[0], range[1], textArea, panelIdx, rendererIdx, compVisOrder, subIdx));
                    }
                }
            }
        }

        // Recursively check children
        if (comp instanceof Container container) {
            Component[] children = container.getComponents();
            for (Component child : children) {
                collectMatchesFromComponent(child, renderer, panelIdx, rendererIdx, compVisOrder, subComponentCounter, tempMatches, processedComponents);
            }
        }
    }


    private boolean canNavigate() {
        return !allMatches.isEmpty() && currentMatchIndex >= 0;
    }

    private void highlightCodeComponents() {
        // Clear any existing code search components
        codeSearchComponents.clear();

        // Find and highlight code components after markdown rendering is complete
//        for (MarkdownOutputPanel panel : panels) {
//            panel.renderers().forEach(renderer -> {
//                List<RSyntaxTextArea> textAreas = ComponentUtils.findComponentsOfType(renderer.getRoot(), RSyntaxTextArea.class);
//                for (RSyntaxTextArea textArea : textAreas) {
//                    RTextAreaSearchableComponent rsc = RTextAreaSearchableComponent.wrapWithoutJumping(textArea);
//                    codeSearchComponents.add(rsc);
//                    // Temporarily set a null callback to prevent RTextAreaSearchableComponent from calling back to GenericSearchBar
//                    // as we will consolidate results in handleSearchComplete.
//                    SearchableComponent.SearchCompleteCallback originalCallback = rsc.getSearchCompleteCallback();
//                    rsc.setSearchCompleteCallback(SearchableComponent.SearchCompleteCallback.NONE);
//                    rsc.highlightAll(currentSearchTerm, currentCaseSensitive);
//                    rsc.setSearchCompleteCallback(originalCallback); // Restore original if any
//                }
//            });
//        }
    }

    private void printSearchResults() {
        debugger.printSearchResults(allMatches, currentMatchIndex, currentSearchTerm);
        debugger.printAllBlocks(panels);
        debugger.printBlocksWithMatches(allMatches, currentMatchIndex);
    }

    /**
     * Collect markdown matches for a component using both indexed markers and direct DOM scanning.
     */
    private void collectMarkdownMatches(JComponent component, IncrementalBlockRenderer renderer,
                                      int panelIdx, int rendererIdx, int compVisOrder,
                                      AtomicInteger subComponentCounter, List<SearchMatch> tempMatches) {

        // Get all markers for this component from both sources
        var indexedMarkerIds = renderer.getIndexedMarkerIds();
        var directMarkerIds = findMarkersInComponentText(component);

        // Collect all markers that belong to this component
        var componentMarkers = new ArrayList<Integer>();
        var foundIndexedMarkers = new ArrayList<Integer>();
        var foundDirectMarkers = new ArrayList<Integer>();

        // Check indexed markers
        for (int markerId : indexedMarkerIds) {
            Component foundComponent = renderer.findByMarkerId(markerId).orElse(null);
            if (foundComponent == component) {
                componentMarkers.add(markerId);
                foundIndexedMarkers.add(markerId);
            }
        }

        // Check direct markers (only add if not already found via indexing)
        for (int markerId : directMarkerIds) {
            boolean alreadyFound = indexedMarkerIds.contains(markerId) &&
                                  renderer.findByMarkerId(markerId).orElse(null) == component;
            if (!alreadyFound) {
                componentMarkers.add(markerId);
                foundDirectMarkers.add(markerId);
            }
        }

        // Sort all markers by ID to ensure correct document order
        componentMarkers.sort(Integer::compareTo);

        // Now add them to tempMatches in the correct order
        for (int markerId : componentMarkers) {
            int subIdx = subComponentCounter.getAndIncrement();
            tempMatches.add(new MarkdownSearchMatch(markerId, component, panelIdx, rendererIdx, compVisOrder, subIdx));
        }

        debugger.logMarkdownMatches(component, panelIdx, rendererIdx, compVisOrder,
                                   foundIndexedMarkers, foundDirectMarkers, componentMarkers);

        var detailedMarkers = findDetailedMarkersInComponentText(component);
        debugger.logDetailedMarkerContext(component, foundIndexedMarkers, foundDirectMarkers,
                                        componentMarkers, detailedMarkers);
    }

    /**
     * Find marker IDs by scanning the component's HTML text directly.
     */
    private Set<Integer> findMarkersInComponentText(JComponent component) {
        var markerIds = new HashSet<Integer>();

        try {
            String htmlText = "";
            if (component instanceof JEditorPane editor) {
                htmlText = editor.getText();
            } else if (component instanceof JLabel label) {
                htmlText = label.getText();
            }

            if (htmlText == null || htmlText.isEmpty()) {
                return markerIds;
            }

            // Look for data-brokk-id attributes in the HTML
            Pattern pattern = Pattern.compile("data-brokk-id=\"(\\d+)\"");
            Matcher matcher = pattern.matcher(htmlText);

            while (matcher.find()) {
                try {
                    int markerId = Integer.parseInt(matcher.group(1));
                    markerIds.add(markerId);
                } catch (NumberFormatException e) {
                    // Skip invalid marker IDs
                }
            }

        } catch (Exception e) {
            logger.warn("Error scanning component text for markers", e);
        }

        return markerIds;
    }

    /**
     * Find detailed marker information including surrounding HTML context.
     */
    private List<MarkdownSearchDebugger.MarkerInfo> findDetailedMarkersInComponentText(JComponent component) {
        var markers = new ArrayList<MarkdownSearchDebugger.MarkerInfo>();

        try {
            String htmlText = "";
            if (component instanceof JEditorPane editor) {
                htmlText = editor.getText();
            } else if (component instanceof JLabel label) {
                htmlText = label.getText();
            }

            if (htmlText == null || htmlText.isEmpty()) {
                return markers;
            }

            // Look for complete marker tags with surrounding context
            Pattern pattern = Pattern.compile(
                "(.{0,30})<[^>]*data-brokk-id=\"(\\d+)\"[^>]*>([^<]*)</[^>]*>(.{0,30})"
            );
            Matcher matcher = pattern.matcher(htmlText);

            while (matcher.find()) {
                try {
                    int markerId = Integer.parseInt(matcher.group(2));
                    String before = matcher.group(1);
                    String content = matcher.group(3);
                    String after = matcher.group(4);

                    markers.add(new MarkdownSearchDebugger.MarkerInfo(markerId, before, content, after, matcher.start()));
                } catch (NumberFormatException e) {
                    // Skip invalid marker IDs
                }
            }

            // Sort by position in text
            markers.sort((a, b) -> Integer.compare(a.position(), b.position()));

        } catch (Exception e) {
            logger.warn("Error scanning component text for detailed markers", e);
        }

        return markers;
    }


    /**
     * Finds the currently focused JTextComponent within any of the panels.
     */
    @Nullable
    private JTextComponent findFocusedTextComponent() {
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
    @Nullable
    private JTextComponent findFocusedTextComponentIn(Component comp) {
        if (comp instanceof JTextComponent tc && tc.isFocusOwner()) {
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
