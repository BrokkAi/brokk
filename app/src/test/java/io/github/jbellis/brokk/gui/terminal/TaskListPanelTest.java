package io.github.jbellis.brokk.gui.terminal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TaskListPanelTest {

    private static final boolean HAS_EDITABLE_FRAGMENTS = true;
    private static final boolean NO_EDITABLE_FRAGMENTS = false;

    @Test
    void skipSearch_whenFirstTaskAndHasEditableFragments() {
        assertTrue(TaskListPanel.shouldSkipSearchForTask(0, HAS_EDITABLE_FRAGMENTS));
    }

    @Test
    void doNotSkipSearch_whenFirstTaskAndNoEditableFragments() {
        assertFalse(TaskListPanel.shouldSkipSearchForTask(0, NO_EDITABLE_FRAGMENTS));
    }

    @Test
    void doNotSkipSearch_whenNotFirstTask_evenIfHasEditableFragments() {
        assertFalse(TaskListPanel.shouldSkipSearchForTask(1, HAS_EDITABLE_FRAGMENTS));
        assertFalse(TaskListPanel.shouldSkipSearchForTask(2, NO_EDITABLE_FRAGMENTS));
    }
}
