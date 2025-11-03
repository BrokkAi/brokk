package ai.brokk.gui.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

public class TestRunnerPanelRunRetentionTest {

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
    void retainsOnlyMostRecent50Runs_andUpdatesSelectionAndOutput() throws Exception {
        var panel = new TestRunnerPanel(new InMemoryTestRunsStore());

        // Create 55 runs, completing each one so that the newest is always active and selected.
        List<String> runIds = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            String id = panel.beginRun(1, "cmd " + i, Instant.now());
            runIds.add(id);
            panel.completeRun(id, 0, Instant.now());
        }
        // Ensure all EDT actions from beginRun are processed
        waitForEdt();

        // Assert only 50 runs are retained
        DefaultListModel<?> model = getField(panel, "runListModel", DefaultListModel.class);
        assertEquals(50, model.getSize(), "Should retain only 50 most recent runs");

        // Assert the first 5 inserted runs are removed from runsById
        @SuppressWarnings("unchecked")
        Map<String, Object> runsById = getField(panel, "runsById", Map.class);
        for (int i = 0; i < 5; i++) {
            String oldId = runIds.get(i);
            assertFalse(runsById.containsKey(oldId), "Oldest run should be evicted from runsById: " + oldId);
        }

        // Collect ids present in the list model (via reflection into RunEntry.id)
        Set<String> idsInList = new HashSet<>();
        for (int i = 0; i < model.getSize(); i++) {
            Object runEntry = model.getElementAt(i);
            Field idField = runEntry.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            String id = (String) idField.get(runEntry);
            idsInList.add(id);
        }

        // Assert the first 5 inserted runs are not present in the list
        for (int i = 0; i < 5; i++) {
            String oldId = runIds.get(i);
            assertFalse(idsInList.contains(oldId), "Oldest run should be evicted from list model: " + oldId);
        }

        // Assert the newest run is selected
        JList<?> runList = getField(panel, "runList", JList.class);
        assertEquals(0, runList.getSelectedIndex(), "Newest run should be selected");

        // Append output to the newest run and verify it appears in the output area
        String newestId = runIds.get(runIds.size() - 1);
        String appended = "Output for newest run\n";
        panel.appendToRun(newestId, appended);
        waitForEdt();

        // Ensure selection is on the newest run; reselect explicitly if needed
        boolean selectedNewest = false;
        for (int i = 0; i < model.getSize(); i++) {
            Object runEntry = model.getElementAt(i);
            Field idField = runEntry.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            String id = (String) idField.get(runEntry);
            if (newestId.equals(id)) {
                if (runList.getSelectedIndex() != i) {
                    runList.setSelectedIndex(i);
                    waitForEdt();
                }
                selectedNewest = true;
                break;
            }
        }
        assertTrue(selectedNewest, "Newest run entry should exist in the list");

        JTextArea outputArea = getField(panel, "outputArea", JTextArea.class);
        String text = outputArea.getText();
        assertTrue(text.contains(appended), "Output area should display appended text for selected run");
    }
}
