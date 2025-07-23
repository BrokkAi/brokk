package io.github.jbellis.brokk.gui.search;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SearchMatch.compareTo() method ordering behavior.
 */
public class SearchMatchCompareToTest {

    // Mock component for testing
    private static final JComponent MOCK_COMPONENT = new JLabel();

    @Test
    void testOrderingByPanelIndex() {
        var match1 = createMarkdownMatch(0, 0, 0, 0, 1);
        var match2 = createMarkdownMatch(1, 0, 0, 0, 2);

        assertTrue(match1.compareTo(match2) < 0, "Panel 0 should come before panel 1");
        assertTrue(match2.compareTo(match1) > 0, "Panel 1 should come after panel 0");
    }

    @Test
    void testOrderingByRendererIndex() {
        var match1 = createMarkdownMatch(0, 0, 0, 0, 1);
        var match2 = createMarkdownMatch(0, 1, 0, 0, 2);

        assertTrue(match1.compareTo(match2) < 0, "Renderer 0 should come before renderer 1");
        assertTrue(match2.compareTo(match1) > 0, "Renderer 1 should come after renderer 0");
    }

    @Test
    void testOrderingByComponentVisualOrder() {
        var match1 = createMarkdownMatch(0, 0, 0, 0, 1);
        var match2 = createMarkdownMatch(0, 0, 1, 0, 2);

        assertTrue(match1.compareTo(match2) < 0, "Component order 0 should come before order 1");
        assertTrue(match2.compareTo(match1) > 0, "Component order 1 should come after order 0");
    }

    @Test
    void testOrderingBySubComponentIndex() {
        var match1 = createCodeMatch(0, 10, 0, 0, 0, 0);
        var match2 = createCodeMatch(20, 30, 0, 0, 0, 1);

        assertTrue(match1.compareTo(match2) < 0, "Sub-component 0 should come before sub-component 1");
        assertTrue(match2.compareTo(match1) > 0, "Sub-component 1 should come after sub-component 0");
    }

    @Test
    void testOrderingByMarkdownMarkerId() {
        var match1 = createMarkdownMatch(1, 0, 0, 0, 0);
        var match2 = createMarkdownMatch(2, 0, 0, 0, 0);

        assertTrue(match1.compareTo(match2) < 0, "Marker ID 1 should come before marker ID 2");
        assertTrue(match2.compareTo(match1) > 0, "Marker ID 2 should come after marker ID 1");
    }

    @Test
    void testOrderingByCodeStartOffset() {
        var match1 = createCodeMatch(10, 15, 0, 0, 0, 0);
        var match2 = createCodeMatch(20, 25, 0, 0, 0, 0);

        assertTrue(match1.compareTo(match2) < 0, "Start offset 10 should come before offset 20");
        assertTrue(match2.compareTo(match1) > 0, "Start offset 20 should come after offset 10");
    }

    @Test
    void testMixedTypeOrderingAtSamePosition() {
        var markdownMatch = createMarkdownMatch(1, 0, 0, 0, 0);
        var codeMatch = createCodeMatch(10, 15, 0, 0, 0, 0);

        // When types differ at same position, they should be considered equal
        assertEquals(0, markdownMatch.compareTo(codeMatch), "Different types at same position should be equal");
        assertEquals(0, codeMatch.compareTo(markdownMatch), "Different types at same position should be equal");
    }

    @Test
    void testEqualityAndConsistency() {
        var match1 = createMarkdownMatch(1, 0, 0, 0, 0);
        var match2 = createMarkdownMatch(1, 0, 0, 0, 0);

        assertEquals(0, match1.compareTo(match2), "Identical matches should be equal");
        assertEquals(0, match2.compareTo(match1), "Comparison should be symmetric");
    }

    @Test
    void testTransitivityOfOrdering() {
        var match1 = createMarkdownMatch(1, 0, 0, 0, 0);
        var match2 = createMarkdownMatch(2, 0, 0, 0, 0);
        var match3 = createMarkdownMatch(3, 0, 0, 0, 0);

        assertTrue(match1.compareTo(match2) < 0, "match1 < match2");
        assertTrue(match2.compareTo(match3) < 0, "match2 < match3");
        assertTrue(match1.compareTo(match3) < 0, "match1 < match3 (transitivity)");
    }

    @Test
    void testComplexSortingScenario() {
        List<SearchMatch> matches = new ArrayList<>();

        // Different panels
        matches.add(createMarkdownMatch(1, 1, 0, 0, 0));
        matches.add(createMarkdownMatch(1, 0, 0, 0, 0));

        // Same panel, different renderers
        matches.add(createMarkdownMatch(1, 0, 1, 0, 0));
        matches.add(createMarkdownMatch(2, 0, 0, 0, 0));

        // Same renderer, different component order
        matches.add(createMarkdownMatch(1, 0, 0, 1, 0));

        // Same component, different marker IDs
        matches.add(createMarkdownMatch(3, 0, 0, 0, 0));

        // Code matches at same level
        matches.add(createCodeMatch(5, 10, 0, 0, 0, 0));
        matches.add(createCodeMatch(15, 20, 0, 0, 0, 0));

        Collections.sort(matches);

        // Verify sorted order
        assertEquals(0, matches.get(0).panelIndex(), "First match should be panel 0");
        assertEquals(0, matches.get(1).panelIndex(), "Second match should be panel 0");
        assertEquals(1, matches.get(matches.size() - 1).panelIndex(), "Last match should be panel 1");

        // Within panel 0, verify ordering
        var panel0Matches = matches.stream()
                .filter(m -> m.panelIndex() == 0)
                .toList();

        for (int i = 1; i < panel0Matches.size(); i++) {
            assertTrue(panel0Matches.get(i - 1).compareTo(panel0Matches.get(i)) <= 0,
                    "Matches should be in sorted order");
        }
    }

    @Test
    void testCodeMatchSubComponentOrdering() {
        var match1 = createCodeMatch(10, 15, 0, 0, 0, 0);
        var match2 = createCodeMatch(20, 25, 0, 0, 0, 1);
        var match3 = createCodeMatch(5, 8, 0, 0, 0, 2);

        List<SearchMatch> matches = List.of(match3, match1, match2);
        var sorted = new ArrayList<>(matches);
        Collections.sort(sorted);

        assertEquals(match1, sorted.get(0), "Sub-component 0 should be first");
        assertEquals(match2, sorted.get(1), "Sub-component 1 should be second");
        assertEquals(match3, sorted.get(2), "Sub-component 2 should be third");
    }

    @Test
    void testHierarchicalOrdering() {
        // Create matches that test the full hierarchy: panel -> renderer -> component -> sub-component -> content
        var match1 = createMarkdownMatch(1, 0, 0, 0, 0); // Panel 0, renderer 0, component 0, marker 1
        var match2 = createMarkdownMatch(2, 0, 0, 0, 0); // Panel 0, renderer 0, component 0, marker 2
        var match3 = createMarkdownMatch(1, 0, 0, 1, 0); // Panel 0, renderer 0, component 1, marker 1
        var match4 = createMarkdownMatch(1, 0, 1, 0, 0); // Panel 0, renderer 1, component 0, marker 1
        var match5 = createMarkdownMatch(1, 1, 0, 0, 0); // Panel 1, renderer 0, component 0, marker 1

        List<SearchMatch> matches = new ArrayList<>(List.of(match5, match3, match1, match4, match2));
        Collections.sort(matches);

        assertEquals(match1, matches.get(0), "Should sort by marker ID within same position");
        assertEquals(match2, matches.get(1), "Should sort by marker ID within same position");
        assertEquals(match3, matches.get(2), "Should sort by component order");
        assertEquals(match4, matches.get(3), "Should sort by renderer index");
        assertEquals(match5, matches.get(4), "Should sort by panel index");
    }

    private MarkdownSearchMatch createMarkdownMatch(int markerId, int panelIndex, int rendererIndex,
                                                   int componentVisualOrder, int subComponentIndex) {
        return new MarkdownSearchMatch(markerId, MOCK_COMPONENT, panelIndex, rendererIndex,
                                     componentVisualOrder, subComponentIndex);
    }

    private CodeSearchMatch createCodeMatch(int startOffset, int endOffset, int panelIndex, int rendererIndex,
                                          int componentVisualOrder, int subComponentIndex) {
        return new CodeSearchMatch(null, startOffset, endOffset, MOCK_COMPONENT, panelIndex, rendererIndex,
                                 componentVisualOrder, subComponentIndex);
    }
}