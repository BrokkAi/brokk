package ai.brokk.gui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Insets;
import org.junit.jupiter.api.Test;

class TaskListPanelRendererTest {

    @Test
    void padding_whenContentSmallerThanMin_isSplitEvenly() {
        int contentHeight = 18;
        int minHeight = 48;

        Insets insets = TaskListPanel.verticalPaddingForCell(contentHeight, minHeight);

        int extra = minHeight - contentHeight; // 30
        assertEquals(extra, insets.top + insets.bottom, "Top+bottom padding should equal the extra space");
        assertTrue(Math.abs(insets.top - insets.bottom) <= 1, "Top/bottom padding should be balanced");
        assertTrue(insets.top >= 0 && insets.bottom >= 0, "Padding should be non-negative");
    }

    @Test
    void padding_whenContentEqualsMin_isZero() {
        int contentHeight = 48;
        int minHeight = 48;

        Insets insets = TaskListPanel.verticalPaddingForCell(contentHeight, minHeight);

        assertEquals(0, insets.top, "Top padding should be zero when heights are equal");
        assertEquals(0, insets.bottom, "Bottom padding should be zero when heights are equal");
    }

    @Test
    void padding_whenContentLargerThanMin_isZero() {
        int contentHeight = 60;
        int minHeight = 48;

        Insets insets = TaskListPanel.verticalPaddingForCell(contentHeight, minHeight);

        assertEquals(0, insets.top, "Top padding should be zero when content exceeds min");
        assertEquals(0, insets.bottom, "Bottom padding should be zero when content exceeds min");
    }
}
