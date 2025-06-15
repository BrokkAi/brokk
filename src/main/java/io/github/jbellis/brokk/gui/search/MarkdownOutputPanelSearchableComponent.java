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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SearchableComponent adapter for MarkdownOutputPanel(s).
 * This bridges the SearchableComponent interface with MarkdownPanelSearchCallback functionality,
 * supporting search in both Markdown text and code blocks (RSyntaxTextArea).
 */
public class MarkdownOutputPanelSearchableComponent implements SearchableComponent {
    private static final Logger logger = LogManager.getLogger(MarkdownOutputPanelSearchableComponent.class);

    // Constants for configuration
    private static final double SCROLL_POSITION_RATIO = 0.25; // Position marker 1/4 from top of viewport
    private static final boolean REQUIRE_WHOLE_WORD = false; // Don't require whole word matching for better search experience

    private final List<MarkdownOutputPanel> panels;
    private String currentSearchTerm = "";
    private boolean currentCaseSensitive = false;
    private SearchCompleteCallback searchCompleteCallback = null;

    private final List<Match> allMatches = new ArrayList<>();
    private int currentMatchIndex = -1;
    private Match previousMatch = null;
    private final List<RTextAreaSearchableComponent> codeSearchComponents = new ArrayList<>();

    private record Match(
        Type type,
        Component actualUiComponent, // The JEditorPane or RSyntaxTextArea
        int panelIndex,
        int rendererIndex,
        int componentVisualOrderInRenderer, // Index of actualUiComponent in renderer.getRoot().getComponents()
        int subComponentIndex, // For distinguishing multiple code blocks at same visual position
        // For MARKDOWN:
        int markerId,
        // For CODE:
        RTextAreaSearchableComponent codeSearchable,
        int startOffset,
        int endOffset
    ) implements Comparable<Match> {
        enum Type { MARKDOWN, CODE }

        // Markdown Match constructor
        Match(int markerId, Component actualUiComponent, int panelIndex, int rendererIndex, int componentVisualOrderInRenderer) {
            this(Type.MARKDOWN, actualUiComponent, panelIndex, rendererIndex, componentVisualOrderInRenderer, 0,
                 markerId, null, -1, -1);
        }

        // Code Match constructor
        Match(RTextAreaSearchableComponent codeSearchable, int startOffset, int endOffset, Component actualUiComponent, int panelIndex, int rendererIndex, int componentVisualOrderInRenderer, int subComponentIndex) {
            this(Type.CODE, actualUiComponent, panelIndex, rendererIndex, componentVisualOrderInRenderer, subComponentIndex,
                 -1, codeSearchable, startOffset, endOffset);
        }

        @Override
        public int compareTo(MarkdownOutputPanelSearchableComponent.Match other) {
            // First compare by panel
            int panelCmp = Integer.compare(this.panelIndex, other.panelIndex);
            if (panelCmp != 0) return panelCmp;

            // Then by renderer
            int rendererCmp = Integer.compare(this.rendererIndex, other.rendererIndex);
            if (rendererCmp != 0) return rendererCmp;

            // Then by component position in renderer
            int componentOrderCmp = Integer.compare(this.componentVisualOrderInRenderer, other.componentVisualOrderInRenderer);
            if (componentOrderCmp != 0) return componentOrderCmp;
            
            // Then by sub-component index (for multiple code blocks at same position)
            int subComponentCmp = Integer.compare(this.subComponentIndex, other.subComponentIndex);
            if (subComponentCmp != 0) return subComponentCmp;

            // Finally, within the same exact position, order by content position
            if (this.type == Type.MARKDOWN && other.type == Type.MARKDOWN) {
                return Integer.compare(this.markerId, other.markerId);
            } else if (this.type == Type.CODE && other.type == Type.CODE) {
                return Integer.compare(this.startOffset, other.startOffset);
            } else {
                // When types differ at same position, use type ordering as final tiebreaker
                return this.type.compareTo(other.type);
            }
        }
    }


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

            try {
                panel.setHtmlCustomizerWithCallback(searchCustomizer, processMarkdownSearchResults);
            } catch (Exception e) {
                logger.error("Error applying search customizer to panel for Markdown", e);
                var callback = getSearchCompleteCallback();
                if (callback != null) {
                    callback.onSearchError("Search failed during Markdown highlighting: " + e.getMessage());
                }
                // Even if one panel fails, try to complete with what we have
                if (remainingMarkdownOperations.decrementAndGet() == 0) {
                    handleSearchComplete();
                }
            }
        }
    }

    @Override
    public void clearHighlights() {
        // Clear Markdown highlights
        for (MarkdownOutputPanel panel : panels) {
            panel.setHtmlCustomizer(HtmlCustomizer.DEFAULT);
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
        if (!searchText.equals(currentSearchTerm) || caseSensitive != currentCaseSensitive) {
            highlightAll(searchText, caseSensitive);
            return false; // Search is async, navigation will occur after handleSearchComplete
        }

        if (!canNavigate()) {
            return false;
        }

        var direction = forward ? 1 : -1;
        currentMatchIndex = Math.floorMod(currentMatchIndex + direction, allMatches.size());

        updateCurrentMatchHighlighting();
        // Ensure scroll happens after highlighting is complete
        SwingUtilities.invokeLater(() -> scrollToCurrentMatch());

        var callback = getSearchCompleteCallback();
        if (callback != null) {
            callback.onSearchComplete(allMatches.size(), currentMatchIndex + 1);
        }
        return true;
    }

    @Override
    public void centerCaretInView() {
        scrollToCurrentMatch();
    }

    @Override
    public JComponent getComponent() {
        return panels.isEmpty() ? new JPanel() : panels.get(0);
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
                Match firstMatch = allMatches.get(0);
                System.out.println("INITIAL SCROLL: First match is " + firstMatch.type() + " at [P:" + firstMatch.panelIndex() + ",R:" + firstMatch.rendererIndex() + ",C:" + firstMatch.componentVisualOrderInRenderer() + ",S:" + firstMatch.subComponentIndex() + "] bounds=" + firstMatch.actualUiComponent().getBounds());
                scrollToCurrentMatch();
            });
        }

        var callback = getSearchCompleteCallback();
        if (callback != null) {
            int total = allMatches.size();
            int currentIdxDisplay = total == 0 ? 0 : currentMatchIndex + 1;
            callback.onSearchComplete(total, currentIdxDisplay);
        }
    }

    private void updateMarkdownMarkerStyle(int markerId, boolean isCurrent) {
        SwingUtilities.invokeLater(() -> { // Ensure UI updates on EDT
            for (MarkdownOutputPanel panel : panels) {
                panel.renderers().forEach(renderer -> {
                    if (renderer.findByMarkerId(markerId).isPresent()) {
                        renderer.updateMarkerStyle(markerId, isCurrent);
                    }
                });
            }
        });
    }

    private void updateCurrentMatchHighlighting() {
        if (previousMatch != null) {
            if (previousMatch.type() == Match.Type.MARKDOWN) {
                updateMarkdownMarkerStyle(previousMatch.markerId(), false);
            } else if (previousMatch.type() == Match.Type.CODE && previousMatch.codeSearchable() != null) {
                if (previousMatch.actualUiComponent() instanceof RSyntaxTextArea ta) {
                    // Change the previous current match back to regular highlight
                    var highlighter = ta.getHighlighter();
                    if (highlighter != null) {
                        // Find and update the CURRENT_SEARCH highlight back to SEARCH
                        for (var highlight : highlighter.getHighlights()) {
                            if (highlight.getPainter() == JMHighlightPainter.CURRENT_SEARCH &&
                                highlight.getStartOffset() == previousMatch.startOffset() &&
                                highlight.getEndOffset() == previousMatch.endOffset()) {
                                highlighter.removeHighlight(highlight);
                                try {
                                    highlighter.addHighlight(previousMatch.startOffset(), 
                                                           previousMatch.endOffset(), 
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

        if (allMatches.isEmpty() || currentMatchIndex < 0 || currentMatchIndex >= allMatches.size()) {
            previousMatch = null;
            return;
        }

        Match currentMatch = allMatches.get(currentMatchIndex);
        if (currentMatch.type() == Match.Type.MARKDOWN) {
            updateMarkdownMarkerStyle(currentMatch.markerId(), true);
        } else if (currentMatch.type() == Match.Type.CODE && currentMatch.codeSearchable() != null) {
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
                    for (int i = 0; i < ranges.size(); i++) {
                        var range = ranges.get(i);
                        try {
                            // Use CURRENT_SEARCH for the current match, SEARCH for others
                            var painter = (range[0] == currentMatch.startOffset() && range[1] == currentMatch.endOffset()) 
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
                    ta.select(currentMatch.startOffset(), currentMatch.endOffset());
                }
            }
        }
        previousMatch = currentMatch;
    }


    private void scrollToCurrentMatch() {
        if (currentMatchIndex < 0 || currentMatchIndex >= allMatches.size()) {
            return;
        }
        Match match = allMatches.get(currentMatchIndex);
        if (match.actualUiComponent() instanceof JComponent jc) {
            scrollToComponent(jc);
        } else {
            logger.warn("Cannot scroll to match, actualUiComponent is not a JComponent: {}", match.actualUiComponent());
        }
    }
    

    private void scrollToComponent(JComponent compToScroll) {
        if (compToScroll == null) {
            logger.trace("scrollToComponent called with null component.");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            Container parent = compToScroll.getParent();
            JScrollPane scrollPane = null;
            while (parent != null) {
                if (parent instanceof JScrollPane) {
                    scrollPane = (JScrollPane) parent;
                    break;
                }
                if (parent instanceof JViewport && parent.getParent() instanceof JScrollPane) {
                    scrollPane = (JScrollPane) parent.getParent();
                    break;
                }
                parent = parent.getParent();
            }

            if (scrollPane == null) {
                 // Fallback: Try to find containing JScrollPane of the panel itself if comp is deep
                if (!panels.isEmpty()) {
                    parent = panels.get(0).getParent(); // Assuming all panels in similar scroll setup
                     while (parent != null) {
                        if (parent instanceof JScrollPane) {
                            scrollPane = (JScrollPane) parent;
                            break;
                        }
                        if (parent instanceof JViewport && parent.getParent() instanceof JScrollPane) {
                             scrollPane = (JScrollPane) parent.getParent();
                             break;
                        }
                        parent = parent.getParent();
                    }
                }
            }
            
            if (scrollPane == null) {
                logger.trace("Could not find JScrollPane for component: {}", compToScroll.getClass().getSimpleName());
                // Try to scroll the component itself if it's in a viewport
                if (compToScroll.getParent() instanceof JViewport) {
                    Rectangle bounds = compToScroll.getBounds();
                    JViewport viewport = (JViewport) compToScroll.getParent();
                     Rectangle viewRect = viewport.getViewRect();
                     int desiredY = Math.max(0, bounds.y - (int)(viewRect.height * SCROLL_POSITION_RATIO));
                     viewport.setViewPosition(new Point(viewRect.x, desiredY));
                } else {
                    compToScroll.scrollRectToVisible(new Rectangle(0,0, compToScroll.getWidth(), compToScroll.getHeight()));
                }
                return;
            }


            Rectangle bounds = SwingUtilities.convertRectangle(compToScroll.getParent(), compToScroll.getBounds(), scrollPane.getViewport().getView());
            JViewport viewport = scrollPane.getViewport();
            Rectangle viewRect = viewport.getViewRect();

            // Scroll to put the component at the top of the viewport
            int desiredY = Math.max(0, bounds.y);
            Component view = viewport.getView();
            int maxY = Math.max(0, view.getHeight() - viewRect.height);
            desiredY = Math.min(desiredY, maxY);

            viewport.setViewPosition(new Point(viewRect.x, desiredY));
            logger.trace("Scrolled to component {} at y={}", compToScroll.getClass().getSimpleName(), desiredY);
        });
    }


    private void scrollToTop() {
        SwingUtilities.invokeLater(() -> {
            if (panels.isEmpty() || panels.get(0).getParent() == null) return;

            Container parent = panels.get(0).getParent();
            while (parent != null) {
                if (parent instanceof JViewport && parent.getParent() instanceof JScrollPane) {
                    JScrollPane scrollPane = (JScrollPane) parent.getParent();
                    scrollPane.getViewport().setViewPosition(new Point(0, 0));
                    return;
                }
                if (parent instanceof JScrollPane) { // If panel is directly in JScrollPane (less common for MOP)
                     ((JScrollPane)parent).getViewport().setViewPosition(new Point(0,0));
                     return;
                }
                parent = parent.getParent();
            }
        });
    }




    private List<int[]> countMatchesInTextArea(RSyntaxTextArea textArea, String searchText, boolean caseSensitive) {
        List<int[]> ranges = new ArrayList<>();
        if (searchText.trim().isEmpty()) {
            return ranges;
        }
        String textContent;
        try {
            textContent = textArea.getText();
        } catch (NullPointerException e) { // getText can throw NPE if document is null
            logger.warn("RSyntaxTextArea document was null, cannot search.", e);
            return ranges;
        }
        if (textContent.isEmpty()) {
            return ranges;
        }

        Pattern pattern = Pattern.compile(
            Pattern.quote(searchText),
            caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
        );
        Matcher matcher = pattern.matcher(textContent);
        while (matcher.find()) {
            ranges.add(new int[]{matcher.start(), matcher.end()});
        }
        return ranges;
    }

    private void collectMatchesInVisualOrder() {
        allMatches.clear();
        List<Match> tempMatches = new ArrayList<>();

        for (int panelIdx = 0; panelIdx < panels.size(); panelIdx++) {
            MarkdownOutputPanel panel = panels.get(panelIdx);
            List<IncrementalBlockRenderer> renderers = panel.renderers().toList();

            for (int rendererIdx = 0; rendererIdx < renderers.size(); rendererIdx++) {
                IncrementalBlockRenderer renderer = renderers.get(rendererIdx);
                JComponent rendererRoot = renderer.getRoot();
                Component[] componentsInRenderer = rendererRoot.getComponents();
                
                // First, collect markdown matches from direct children
                for (int compVisOrder = 0; compVisOrder < componentsInRenderer.length; compVisOrder++) {
                    Component actualUiComp = componentsInRenderer[compVisOrder];

                    // Markdown Matches
                    if (actualUiComp instanceof JEditorPane) {
                        for (int markerId : renderer.getIndexedMarkerIds()) {
                            if (renderer.findByMarkerId(markerId).orElse(null) == actualUiComp) {
                                tempMatches.add(new Match(markerId, actualUiComp, panelIdx, rendererIdx, compVisOrder));
                            }
                        }
                    } else if (actualUiComp instanceof JLabel) { // Components that can host markers
                        for (int markerId : renderer.getIndexedMarkerIds()) {
                            if (renderer.findByMarkerId(markerId).orElse(null) == actualUiComp) {
                                tempMatches.add(new Match(markerId, actualUiComp, panelIdx, rendererIdx, compVisOrder));
                            }
                        }
                    } else if (actualUiComp instanceof Container container) {
                        // Check for nested JEditorPane components inside containers
                        List<JEditorPane> nestedEditors = ComponentUtils.findComponentsOfType(container, JEditorPane.class);
                        for (JEditorPane nestedEditor : nestedEditors) {
                            for (int markerId : renderer.getIndexedMarkerIds()) {
                                Component foundComponent = renderer.findByMarkerId(markerId).orElse(null);
                                if (foundComponent == nestedEditor) {
                                    // For nested markdown, calculate both the component position and sub-position
                                    int correctComponentOrder = findCorrectComponentOrder(nestedEditor, componentsInRenderer);
                                    int visualSubPosition = calculateVisualPosition(nestedEditor, rendererRoot);
                                    tempMatches.add(new Match(Match.Type.MARKDOWN, nestedEditor, panelIdx, rendererIdx, correctComponentOrder, visualSubPosition, markerId, null, -1, -1));
                                }
                            }
                        }
                    }
                }

                // Then, find all RSyntaxTextArea components (which may be nested)
                List<RSyntaxTextArea> textAreas = ComponentUtils.findComponentsOfType(rendererRoot, RSyntaxTextArea.class);
                
                // Group text areas by their parent component position
                Map<Integer, List<RSyntaxTextArea>> textAreasByPosition = new HashMap<>();
                for (RSyntaxTextArea textArea : textAreas) {
                    int compVisOrder = findComponentVisualOrder(textArea, componentsInRenderer);
                    textAreasByPosition.computeIfAbsent(compVisOrder, k -> new ArrayList<>()).add(textArea);
                }
                
                // Process text areas in order, assigning unique sub-component indices
                for (Map.Entry<Integer, List<RSyntaxTextArea>> entry : textAreasByPosition.entrySet()) {
                    int compVisOrder = entry.getKey();
                    List<RSyntaxTextArea> textAreasAtPosition = entry.getValue();
                    
                    for (int subIdx = 0; subIdx < textAreasAtPosition.size(); subIdx++) {
                        RSyntaxTextArea textArea = textAreasAtPosition.get(subIdx);
                        
                        // Find matching RTextAreaSearchableComponent
                        RTextAreaSearchableComponent rsc = codeSearchComponents.stream()
                            .filter(cs -> cs.getComponent() == textArea)
                            .findFirst().orElse(null);

                        if (rsc != null) {
                            List<int[]> ranges = countMatchesInTextArea(textArea, currentSearchTerm, currentCaseSensitive);
                            for (int[] range : ranges) { // ranges are sorted by start offset from countMatchesInTextArea
                                tempMatches.add(new Match(rsc, range[0], range[1], textArea, panelIdx, rendererIdx, compVisOrder, subIdx));
                            }
                        }
                    }
                }
            }
        }
        Collections.sort(tempMatches); // Sort using Match.compareTo
        allMatches.addAll(tempMatches);
    }


    private boolean canNavigate() {
        return !allMatches.isEmpty() && currentMatchIndex >= 0;
    }
    
    private int findComponentVisualOrder(RSyntaxTextArea textArea, Component[] componentsInRenderer) {
        // Try to find which direct child component contains this text area
        for (int i = 0; i < componentsInRenderer.length; i++) {
            Component directChild = componentsInRenderer[i];
            if (isComponentContainedIn(textArea, directChild)) {
                return i;
            }
        }
        // If not found in any direct child, place at the end
        return componentsInRenderer.length;
    }
    
    private boolean isComponentContainedIn(Component target, Component container) {
        if (target == container) {
            return true;
        }
        if (container instanceof Container cont) {
            for (Component child : cont.getComponents()) {
                if (isComponentContainedIn(target, child)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private int findCorrectComponentOrder(JEditorPane editorPane, Component[] componentsInRenderer) {
        // Find which top-level component contains this editor pane
        for (int i = 0; i < componentsInRenderer.length; i++) {
            Component topLevelComponent = componentsInRenderer[i];
            if (isComponentContainedIn(editorPane, topLevelComponent)) {
                return i;
            }
        }
        // If not found, return a position at the end
        return componentsInRenderer.length;
    }
    
    private int calculateVisualPosition(JEditorPane editorPane, JComponent rendererRoot) {
        try {
            // Get all RSyntaxTextArea components in the renderer to compare positions
            List<RSyntaxTextArea> allTextAreas = ComponentUtils.findComponentsOfType(rendererRoot, RSyntaxTextArea.class);
            
            // Get the Y position of the editor pane
            Rectangle editorBounds = SwingUtilities.convertRectangle(editorPane.getParent(), editorPane.getBounds(), rendererRoot);
            int editorY = editorBounds.y;
            
            // Count how many text areas have Y positions before this editor pane
            int precedingTextAreas = 0;
            for (RSyntaxTextArea textArea : allTextAreas) {
                Rectangle textAreaBounds = SwingUtilities.convertRectangle(textArea.getParent(), textArea.getBounds(), rendererRoot);
                if (textAreaBounds.y < editorY) {
                    precedingTextAreas++;
                }
            }
            
            return precedingTextAreas;
        } catch (Exception e) {
            // If coordinate conversion fails, fallback to position 0
            return 0;
        }
    }

    @Override
    public void setSearchCompleteCallback(SearchCompleteCallback callback) {
        this.searchCompleteCallback = callback;
    }

    @Override
    public SearchCompleteCallback getSearchCompleteCallback() {
        return searchCompleteCallback;
    }
    
    private void highlightCodeComponents() {
        // Clear any existing code search components
        codeSearchComponents.clear();
        
        // Find and highlight code components after markdown rendering is complete
        for (MarkdownOutputPanel panel : panels) {
            panel.renderers().forEach(renderer -> {
                List<RSyntaxTextArea> textAreas = ComponentUtils.findComponentsOfType(renderer.getRoot(), RSyntaxTextArea.class);
                for (RSyntaxTextArea textArea : textAreas) {
                    RTextAreaSearchableComponent rsc = RTextAreaSearchableComponent.wrapWithoutJumping(textArea);
                    codeSearchComponents.add(rsc);
                    // Temporarily set a null callback to prevent RTextAreaSearchableComponent from calling back to GenericSearchBar
                    // as we will consolidate results in handleSearchComplete.
                    SearchableComponent.SearchCompleteCallback originalCallback = rsc.getSearchCompleteCallback();
                    rsc.setSearchCompleteCallback(null);
                    rsc.highlightAll(currentSearchTerm, currentCaseSensitive);
                    rsc.setSearchCompleteCallback(originalCallback); // Restore original if any
                }
            });
        }
    }

    private void printSearchResults() {
        System.out.println("\n=== Search Results for '" + currentSearchTerm + "' ===");
        System.out.println("Total matches: " + allMatches.size());
        if (currentMatchIndex >= 0 && currentMatchIndex < allMatches.size()) {
            System.out.println("Current match: " + (currentMatchIndex + 1) + " of " + allMatches.size());
        }
        
        // First, print all blocks in the document
        printAllBlocks();
        
        // Then print blocks with matches
        System.out.println("\n--- Blocks with matches ---");
        
        // Group matches by block (including sub-component index for code blocks)
        int currentPanelIdx = -1;
        int currentRendererIdx = -1;
        int currentComponentIdx = -1;
        int currentSubComponentIdx = -1;
        int blockMatchCount = 0;
        int blockFirstMatchIdx = -1;
        String blockType = "";
        String blockPreview = "";
        int totalBlocksWithMatches = 0;
        
        for (int i = 0; i < allMatches.size(); i++) {
            Match match = allMatches.get(i);
            
            // Check if we've moved to a new block (including sub-component for code)
            boolean newBlock = match.panelIndex() != currentPanelIdx ||
                             match.rendererIndex() != currentRendererIdx ||
                             match.componentVisualOrderInRenderer() != currentComponentIdx ||
                             (match.type() == Match.Type.CODE && match.subComponentIndex() != currentSubComponentIdx);
            
            if (newBlock && i > 0) {
                // Print previous block info
                boolean isCurrentBlock = currentMatchIndex >= blockFirstMatchIdx && 
                                       currentMatchIndex < blockFirstMatchIdx + blockMatchCount;
                String marker = isCurrentBlock ? " <<<< CURRENT" : "";
                String blockId = currentSubComponentIdx >= 0 && blockType.equals("CODE") 
                    ? String.format("[P:%d,R:%d,C:%d,S:%d]", currentPanelIdx, currentRendererIdx, currentComponentIdx, currentSubComponentIdx)
                    : String.format("[P:%d,R:%d,C:%d]", currentPanelIdx, currentRendererIdx, currentComponentIdx);
                System.out.println(String.format("Block %s (%s): %d hit(s) - %s%s",
                    blockId, blockType, blockMatchCount, blockPreview, marker));
                blockMatchCount = 0;
            }
            
            if (newBlock) {
                currentPanelIdx = match.panelIndex();
                currentRendererIdx = match.rendererIndex();
                currentComponentIdx = match.componentVisualOrderInRenderer();
                currentSubComponentIdx = match.type() == Match.Type.CODE ? match.subComponentIndex() : -1;
                blockType = match.type().toString();
                blockFirstMatchIdx = i;
                totalBlocksWithMatches++;
                
                // Get block preview
                if (match.type() == Match.Type.MARKDOWN && match.actualUiComponent() instanceof JEditorPane editor) {
                    String text = editor.getText();
                    // Strip HTML tags for preview
                    text = text.replaceAll("<[^>]+>", "").trim();
                    blockPreview = text.substring(0, Math.min(50, text.length())) + (text.length() > 50 ? "..." : "");
                } else if (match.type() == Match.Type.CODE && match.actualUiComponent() instanceof RSyntaxTextArea textArea) {
                    String text = textArea.getText();
                    blockPreview = text.substring(0, Math.min(50, text.length())).replace("\n", " ") + (text.length() > 50 ? "..." : "");
                }
            }
            
            blockMatchCount++;
        }
        
        // Don't forget the last block
        if (!allMatches.isEmpty()) {
            boolean isCurrentBlock = currentMatchIndex >= blockFirstMatchIdx && 
                                   currentMatchIndex < blockFirstMatchIdx + blockMatchCount;
            String marker = isCurrentBlock ? " <<<< CURRENT" : "";
            String blockId = currentSubComponentIdx >= 0 && blockType.equals("CODE") 
                ? String.format("[P:%d,R:%d,C:%d,S:%d]", currentPanelIdx, currentRendererIdx, currentComponentIdx, currentSubComponentIdx)
                : String.format("[P:%d,R:%d,C:%d]", currentPanelIdx, currentRendererIdx, currentComponentIdx);
            System.out.println(String.format("Block %s (%s): %d hit(s) - %s%s",
                blockId, blockType, blockMatchCount, blockPreview, marker));
        }
        
        System.out.println("\nTotal blocks with matches: " + totalBlocksWithMatches);
        System.out.println("=== End Search Results ===\n");
    }

    private void printAllBlocks() {
        System.out.println("\n--- All blocks in document ---");
        int totalBlocks = 0;
        
        for (int panelIdx = 0; panelIdx < panels.size(); panelIdx++) {
            MarkdownOutputPanel panel = panels.get(panelIdx);
            List<IncrementalBlockRenderer> renderers = panel.renderers().toList();
            
            for (int rendererIdx = 0; rendererIdx < renderers.size(); rendererIdx++) {
                IncrementalBlockRenderer renderer = renderers.get(rendererIdx);
                JComponent rendererRoot = renderer.getRoot();
                Component[] componentsInRenderer = rendererRoot.getComponents();
                
                // Check each direct component
                for (int compIdx = 0; compIdx < componentsInRenderer.length; compIdx++) {
                    Component comp = componentsInRenderer[compIdx];
                    String blockType = "";
                    String blockPreview = "";
                    
                    if (comp instanceof JEditorPane editor) {
                        blockType = "MARKDOWN";
                        String text = editor.getText();
                        text = text.replaceAll("<[^>]+>", "").trim();
                        blockPreview = text.substring(0, Math.min(50, text.length())) + (text.length() > 50 ? "..." : "");
                        totalBlocks++;
                        System.out.println(String.format("Block %d [P:%d,R:%d,C:%d] (%s): %s",
                            totalBlocks, panelIdx, rendererIdx, compIdx, blockType, blockPreview));
                    } else if (comp instanceof JLabel jLabel) {
                        // JLabel might also contain markdown
                        blockType = "MARKDOWN(Label)";
                        String text = jLabel.getText();
                        text = text.replaceAll("<[^>]+>", "").trim();
                        if (!text.isEmpty()) {
                            blockPreview = text.substring(0, Math.min(50, text.length())) + (text.length() > 50 ? "..." : "");
                            totalBlocks++;
                            System.out.println(String.format("Block %d [P:%d,R:%d,C:%d] (%s): %s",
                                totalBlocks, panelIdx, rendererIdx, compIdx, blockType, blockPreview));
                        }
                    } else if (comp instanceof Container container) {
                        // Check for nested RSyntaxTextArea components
                        List<RSyntaxTextArea> textAreas = ComponentUtils.findComponentsOfType(container, RSyntaxTextArea.class);
                        if (!textAreas.isEmpty()) {
                            for (int subIdx = 0; subIdx < textAreas.size(); subIdx++) {
                                RSyntaxTextArea textArea = textAreas.get(subIdx);
                                blockType = "CODE";
                                String text = textArea.getText();
                                blockPreview = text.substring(0, Math.min(50, text.length())).replace("\n", " ") + 
                                             (text.length() > 50 ? "..." : "");
                                totalBlocks++;
                                System.out.println(String.format("Block %d [P:%d,R:%d,C:%d,S:%d] (%s): %s",
                                    totalBlocks, panelIdx, rendererIdx, compIdx, subIdx, blockType, blockPreview));
                            }
                        } else {
                            // Could be a container for other components
                            blockType = "CONTAINER";
                            totalBlocks++;
                            System.out.println(String.format("Block %d [P:%d,R:%d,C:%d] (%s): [Container component]",
                                totalBlocks, panelIdx, rendererIdx, compIdx, blockType));
                        }
                    } else {
                        // Other component type
                        blockType = comp.getClass().getSimpleName();
                        totalBlocks++;
                        System.out.println(String.format("Block %d [P:%d,R:%d,C:%d] (%s): [%s component]",
                            totalBlocks, panelIdx, rendererIdx, compIdx, blockType, blockType));
                    }
                }
            }
        }
        
        System.out.println("Total blocks in document: " + totalBlocks);
    }

    /**
     * Public method to print all blocks in the document.
     * Useful for debugging document structure.
     */
    public void printDocumentStructure() {
        printAllBlocks();
    }

    /**
     * Finds the currently focused JTextComponent within any of the panels.
     */
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
