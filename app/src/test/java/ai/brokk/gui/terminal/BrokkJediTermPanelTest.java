package ai.brokk.gui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Point;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BrokkJediTermPanelTest {

    private StubBufferAccessor buffer;

    @BeforeEach
    void setUp() {
        buffer = new StubBufferAccessor();
    }

    @Test
    void getFullBufferText_returnsEmptyStringWhenBufferIsEmpty() {
        buffer.setHistoryLinesCount(0);
        buffer.setHeight(0);

        String result = BrokkJediTermPanel.computeFullBufferText(buffer);

        assertEquals("", result);
    }

    @Test
    void getFullBufferText_returnsCombinedTextFromHistoryAndActiveBuffer() {
        buffer.setHistoryLinesCount(1);
        buffer.setHeight(2);

        buffer.setLine(-1, "History");
        buffer.setLine(0, "Active 1  ");
        buffer.setLine(1, "Active 2");

        String result = BrokkJediTermPanel.computeFullBufferText(buffer);

        // Trailing whitespace is preserved
        assertEquals("History\nActive 1  \nActive 2", result);
    }

    @Test
    void getSelectionText_returnsNullForNullPoints() {
        assertNull(BrokkJediTermPanel.computeSelectionText(buffer, null, new Point(0, 0)));
        assertNull(BrokkJediTermPanel.computeSelectionText(buffer, new Point(0, 0), null));
    }

    @Test
    void getSelectionText_extractsTextCorrectlyForNormalSelection() {
        Point start = new Point(1, 0);
        Point end = new Point(2, 0);

        buffer.setHistoryLinesCount(0);
        buffer.setHeight(1);

        buffer.setLine(0, "Hello");

        String result = BrokkJediTermPanel.computeSelectionText(buffer, start, end);

        // 'e' (index 1) to 'l' (index 2). End is inclusive.
        assertEquals("el", result);
    }

    @Test
    void getSelectionText_handlesReverseSelectionCoordinates() {
        Point start = new Point(2, 0);
        Point end = new Point(1, 0);

        buffer.setHistoryLinesCount(0);
        buffer.setHeight(1);
        buffer.setLine(0, "Hello");

        String result = BrokkJediTermPanel.computeSelectionText(buffer, start, end);

        assertEquals("el", result);
    }

    @Test
    void getSelectionText_handlesMultiLineSelection() {
        Point start = new Point(4, 0);
        Point end = new Point(0, 1);

        buffer.setHistoryLinesCount(0);
        buffer.setHeight(2);

        buffer.setLine(0, "Line1");
        buffer.setLine(1, "Line2");

        String result = BrokkJediTermPanel.computeSelectionText(buffer, start, end);

        // Line 0: "1" (index 4 to 5)
        // Line 1: "L" (index 0 to 1)
        assertEquals("1\nL", result);
    }

    @Test
    void getSelectionText_handlesWrappedLines() {
        Point start = new Point(4, 0);
        Point end = new Point(0, 1);

        buffer.setHistoryLinesCount(0);
        buffer.setHeight(2);

        buffer.setLine(0, "Line1", true);
        buffer.setLine(1, "Line2");

        String result = BrokkJediTermPanel.computeSelectionText(buffer, start, end);

        assertEquals("1L", result);
    }

    @Test
    void getSelectionText_clampsCoordinates() {
        Point start = new Point(-5, 0);
        Point end = new Point(100, 0);

        buffer.setHistoryLinesCount(0);
        buffer.setHeight(1);
        buffer.setLine(0, "Text");

        String result = BrokkJediTermPanel.computeSelectionText(buffer, start, end);

        assertEquals("Text", result);
    }

    @Test
    void getSelectionText_handlesHistoryLines() {
        Point start = new Point(0, -1);
        Point end = new Point(0, 0);

        buffer.setHistoryLinesCount(1);
        buffer.setHeight(1);

        buffer.setLine(-1, "H");
        buffer.setLine(0, "A");

        String result = BrokkJediTermPanel.computeSelectionText(buffer, start, end);

        assertEquals("H\nA", result);
    }

    static class StubBufferAccessor implements BrokkJediTermPanel.BufferAccessor {
        private final Map<Integer, LineData> lines = new HashMap<>();
        private int historyLineCount = 0;
        private int height = 0;

        static class LineData {
            final String text;
            final boolean wrapped;

            LineData(String text, boolean wrapped) {
                this.text = text;
                this.wrapped = wrapped;
            }
        }

        @Override
        public void lock() {}

        @Override
        public void unlock() {}

        @Override
        public int getHistoryLinesCount() {
            return historyLineCount;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public String getLineText(int y) {
            LineData data = lines.get(y);
            return data != null ? data.text : "";
        }

        @Override
        public boolean isLineWrapped(int y) {
            LineData data = lines.get(y);
            return data != null && data.wrapped;
        }

        public void setLine(int index, String text) {
            setLine(index, text, false);
        }

        public void setLine(int index, String text, boolean wrapped) {
            lines.put(index, new LineData(text, wrapped));
        }

        public void setHistoryLinesCount(int count) {
            this.historyLineCount = count;
        }

        public void setHeight(int height) {
            this.height = height;
        }
    }
}
