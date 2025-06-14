package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.mop.stream.HtmlCustomizer;
import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import io.github.jbellis.brokk.gui.mop.stream.TextNodeMarkerCustomizer;
import io.github.jbellis.brokk.gui.mop.util.ComponentUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
            this(Type.MARKDOWN, actualUiComponent, panelIndex, rendererIndex, componentVisualOrderInRenderer,
                 markerId, null, -1, -1);
        }

        // Code Match constructor
        Match(RTextAreaSearchableComponent codeSearchable, int startOffset, int endOffset, Component actualUiComponent, int panelIndex, int rendererIndex, int componentVisualOrderInRenderer) {
            this(Type.CODE, actualUiComponent, panelIndex, rendererIndex, componentVisualOrderInRenderer,
                 -1, codeSearchable, startOffset, endOffset);
        }

        @Override
        public int compareTo(MarkdownOutputPanelSearchableComponent.Match other) {
            int panelCmp = Integer.compare(this.panelIndex, other.panelIndex);
            if (panelCmp != 0) return panelCmp;

            int rendererCmp = Integer.compare(this.rendererIndex, other.rendererIndex);
            if (rendererCmp != 0) return rendererCmp;

            int componentOrderCmp = Integer.compare(this.componentVisualOrderInRenderer, other.componentVisualOrderInRenderer);
            if (componentOrderCmp != 0) return componentOrderCmp;

            if (this.type == Type.MARKDOWN && other.type == Type.MARKDOWN) {
                return Integer.compare(this.markerId, other.markerId);
            } else if (this.type == Type.CODE && other.type == Type.CODE) {
                return Integer.compare(this.startOffset, other.startOffset);
            } else {
                return this.type.compareTo(other.type); // MD before Code if types differ for same component (unlikely)
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

        // Provide immediate feedback that search is starting
        notifySearchStart(finalSearchTerm);
        this.previousMatch = null;
        this.allMatches.clear();
        this.codeSearchComponents.clear();

        // Highlight in code components first
        for (MarkdownOutputPanel panel : panels) {
            panel.renderers().forEach(renderer -> {
                List<RSyntaxTextArea> textAreas = ComponentUtils.findComponentsOfType(renderer.getRoot(), RSyntaxTextArea.class);
                for (RSyntaxTextArea textArea : textAreas) {
                    RTextAreaSearchableComponent rsc = (RTextAreaSearchableComponent) RTextAreaSearchableComponent.wrap(textArea);
                    codeSearchComponents.add(rsc);
                    // Temporarily set a null callback to prevent RTextAreaSearchableComponent from calling back to GenericSearchBar
                    // as we will consolidate results in handleSearchComplete.
                    SearchableComponent.SearchCompleteCallback originalCallback = rsc.getSearchCompleteCallback();
                    rsc.setSearchCompleteCallback(null);
                    rsc.highlightAll(finalSearchTerm, caseSensitive);
                    rsc.setSearchCompleteCallback(originalCallback); // Restore original if any
                }
            });
        }

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
        scrollToCurrentMatch();

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
        collectMatchesInVisualOrder(); // Populates and sorts allMatches

        currentMatchIndex = allMatches.isEmpty() ? -1 : 0;
        previousMatch = null; // Reset previous match before new highlighting sequence

        if (!allMatches.isEmpty()) {
            updateCurrentMatchHighlighting();
            scrollToCurrentMatch();
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
                // RTextAreaSearchableComponent's clearHighlights would remove all marks.
                // Here, we just want to "de-select" it if it was the current one.
                // The actual highlights (marks) are managed by its highlightAll/clearHighlights.
                // For "current" in code, we rely on selection. So, clearing selection is key.
                if (previousMatch.actualUiComponent() instanceof RSyntaxTextArea ta) {
                    ta.setSelectionStart(ta.getSelectionStart()); // Collapse selection
                    ta.setSelectionEnd(ta.getSelectionStart());
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
                SwingUtilities.invokeLater(() -> { // Ensure focus and selection on EDT
                    ta.requestFocusInWindow();
                    ta.select(currentMatch.startOffset(), currentMatch.endOffset());
                    // RTextAreaSearchableComponent's internal findNext usually calls centerCaretInView.
                    // We might need to manually ensure it's centered if we are not calling its findNext.
                    // scrollToCurrentMatch() which calls scrollToComponent() will handle overall scroll.
                });
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

            int desiredY = Math.max(0, bounds.y - (int) (viewRect.height * SCROLL_POSITION_RATIO));
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

    private boolean markerContainsCurrentSearchTerm(IncrementalBlockRenderer renderer, int markerId) {
        if (currentSearchTerm.isEmpty()) {
            return false;
        }
        Optional<JComponent> componentOpt = renderer.findByMarkerId(markerId);
        if (componentOpt.isEmpty()) {
            return false;
        }
        String componentText = extractTextFromComponent(componentOpt.get());
        if (componentText == null || componentText.isEmpty()) {
            return false;
        }
        if (currentCaseSensitive) {
            return componentText.contains(currentSearchTerm);
        } else {
            return componentText.toLowerCase(Locale.ROOT).contains(currentSearchTerm.toLowerCase(Locale.ROOT));
        }
    }

    private String extractTextFromComponent(JComponent component) {
        if (component instanceof JEditorPane editorPane) {
            try {
                return editorPane.getDocument().getText(0, editorPane.getDocument().getLength());
            } catch (Exception e) {
                logger.trace("Failed to extract text from JEditorPane: {}", e.getMessage());
                return "";
            }
        } else if (component instanceof JLabel label) {
            var html = label.getText();
            if (html != null && html.toLowerCase(Locale.ROOT).startsWith("<html>")) {
                return html.replaceAll("<[^>]+>", ""); // Basic HTML to text
            }
            return html;
        }
        // RSyntaxTextArea text is handled by countMatchesInTextArea directly
        return "";
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

                for (int compVisOrder = 0; compVisOrder < componentsInRenderer.length; compVisOrder++) {
                    Component actualUiComp = componentsInRenderer[compVisOrder];

                    // Markdown Matches
                    if (actualUiComp instanceof JEditorPane || actualUiComp instanceof JLabel) { // Components that can host markers
                        for (int markerId : renderer.getIndexedMarkerIds()) {
                            if (renderer.findByMarkerId(markerId).orElse(null) == actualUiComp &&
                                markerContainsCurrentSearchTerm(renderer, markerId)) {
                                tempMatches.add(new Match(markerId, actualUiComp, panelIdx, rendererIdx, compVisOrder));
                            }
                        }
                    }

                    // Code Matches
                    if (actualUiComp instanceof RSyntaxTextArea textArea) {
                        RTextAreaSearchableComponent rsc = codeSearchComponents.stream()
                            .filter(cs -> cs.getComponent() == textArea)
                            .findFirst().orElse(null);

                        if (rsc != null) {
                            List<int[]> ranges = countMatchesInTextArea(textArea, currentSearchTerm, currentCaseSensitive);
                            for (int[] range : ranges) { // ranges are sorted by start offset from countMatchesInTextArea
                                tempMatches.add(new Match(rsc, range[0], range[1], actualUiComp, panelIdx, rendererIdx, compVisOrder));
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

    @Override
    public void setSearchCompleteCallback(SearchCompleteCallback callback) {
        this.searchCompleteCallback = callback;
    }

    @Override
    public SearchCompleteCallback getSearchCompleteCallback() {
        return searchCompleteCallback;
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
