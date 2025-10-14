package io.github.jbellis.brokk.gui.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

public class TestRunnerPanelRunSelectionAfterDropTest {

    private static <T> T getField(Object target, String fieldName, Class<T> type) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        Object value = f.get(target);
        return type.cast(value);
    }

    private static void waitForEdt() {
        try {
            SwingUtilities.invokeAndWait(() -> {});
        } catch (Exception e) {
            fail("Failed to wait for EDT: " + e);
        }
    }

    @Test
    void selectionUpdatesToNewest_whenPreviouslySelectedRunIsDropped() throws Exception {
        var panel = new TestRunnerPanel(new InMemoryTestRunsStore());
        panel.setMaxRuns(5);

        // Seed with 5 runs, completing them so none are "active".
        for (int i = 0; i < 5; i++) {
            String id = panel.beginRun(1, "init " + i, Instant.now());
            panel.completeRun(id, 0, Instant.now());
        }
        waitForEdt();

        // Access internals
        DefaultListModel<?> model = getField(panel, "runListModel", DefaultListModel.class);
        JList<?> runList = getField(panel, "runList", JList.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> runsById = getField(panel, "runsById", Map.class);

        assertEquals(5, model.getSize(), "Expected 5 initial runs");

        // Select an older run (index 1)
        Object selectedEntry = model.getElementAt(1);
        Field idField = selectedEntry.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        String oldSelectedId = (String) idField.get(selectedEntry);

        runList.setSelectedIndex(1);
        waitForEdt();

        // Push new runs until the previously selected run is dropped by retention.
        // Complete them so the selection logic is predictable.
        String newestId = null;
        for (int i = 5; i < 10; i++) {
            newestId = panel.beginRun(1, "push " + i, Instant.now());
            panel.completeRun(newestId, 0, Instant.now());
            waitForEdt();
        }

        assertNotNull(newestId, "Newest run id should be captured");

        // The old selected run should be evicted from both the list and the map
        assertFalse(runsById.containsKey(oldSelectedId), "Previously selected run should be evicted from runsById");

        Set<String> idsInList = new HashSet<>();
        for (int i = 0; i < model.getSize(); i++) {
            Object runEntry = model.getElementAt(i);
            Field f = runEntry.getClass().getDeclaredField("id");
            f.setAccessible(true);
            idsInList.add((String) f.get(runEntry));
        }
        assertFalse(idsInList.contains(oldSelectedId), "Previously selected run should be evicted from the list model");

        // Selection should be updated to the newest run (top of the list)
        assertEquals(0, runList.getSelectedIndex(), "Selection should point to the newest run");

        // Appending to the newest run should reflect in the output area
        String appended = "Newest run output\n";
        panel.appendToRun(newestId, appended);
        waitForEdt();

        JTextArea outputArea = getField(panel, "outputArea", JTextArea.class);
        String text = outputArea.getText();
        assertTrue(text.contains(appended), "Output area should display appended text for the newest selected run");
    }
}
