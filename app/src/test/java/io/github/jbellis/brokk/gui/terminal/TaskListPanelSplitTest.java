package io.github.jbellis.brokk.gui.terminal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TaskListPanelSplitTest {

    @Test
    void normalizeSplitLines_singleLine() {
        List<String> lines = TaskListPanel.normalizeSplitLines(" Do thing ");
        assertEquals(List.of("Do thing"), lines);
    }

    @Test
    void normalizeSplitLines_multipleLines_withBlanksAndWhitespace() {
        String input = " First task \n\n  \tSecond task\nThird task  \n   \n";
        List<String> lines = TaskListPanel.normalizeSplitLines(input);
        assertEquals(List.of("First task", "Second task", "Third task"), lines);
    }

    @Test
    void normalizeSplitLines_whitespaceOnly() {
        List<String> lines = TaskListPanel.normalizeSplitLines("   \n\t \r\n ");
        assertTrue(lines.isEmpty());
    }

    @Test
    void normalizeSplitLines_nullInput() {
        List<String> lines = TaskListPanel.normalizeSplitLines(null);
        assertTrue(lines.isEmpty());
    }
}
