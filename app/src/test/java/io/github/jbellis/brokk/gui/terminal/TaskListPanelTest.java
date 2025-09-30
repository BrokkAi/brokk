package io.github.jbellis.brokk.gui.terminal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TaskListPanelTest {

    @Test
    void skipSearch_whenFirstTaskAndHasEditableFragments() {
        assertTrue(TaskListPanel.shouldSkipSearchForTask(0, true));
    }

    @Test
    void doNotSkipSearch_whenFirstTaskAndNoEditableFragments() {
        assertFalse(TaskListPanel.shouldSkipSearchForTask(0, false));
    }

    @Test
    void doNotSkipSearch_whenNotFirstTask_evenIfHasEditableFragments() {
        assertFalse(TaskListPanel.shouldSkipSearchForTask(1, true));
        assertFalse(TaskListPanel.shouldSkipSearchForTask(2, false));
    }
}
