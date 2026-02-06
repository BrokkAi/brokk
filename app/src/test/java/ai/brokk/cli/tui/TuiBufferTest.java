package ai.brokk.cli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TuiBufferTest {
    @Test
    void wrapsLongLines() {
        var buf = new TuiBuffer(100);
        buf.append("abcdef");
        assertEquals(java.util.List.of("abc", "def"), buf.snapshotWrapped(3, true));
    }

    @Test
    void preservesLinesWhenWrapDisabled() {
        var buf = new TuiBuffer(100);
        buf.append("abcdef");
        assertEquals(java.util.List.of("abcdef"), buf.snapshotWrapped(3, false));
    }

    @Test
    void splitsOnNewlines() {
        var buf = new TuiBuffer(100);
        buf.append("a\nb\n");
        assertEquals(java.util.List.of("a", "b", ""), buf.snapshotWrapped(80, true));
    }

    @Test
    void respectsMaxLines() {
        var buf = new TuiBuffer(2);
        buf.append("1\n2\n3\n4");
        assertEquals(java.util.List.of("3", "4"), buf.snapshotWrapped(80, true));
    }
}
