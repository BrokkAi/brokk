package ai.brokk.gui.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

public class TestRunnerPanelTest {

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

    private static void awaitSave(TestRunnerPanel panel) {
        waitForEdt();
        panel.awaitPersistenceCompletion().join();
    }

    @Test
    void snapshotTruncatesOutputWithEllipsis() {
        var panel = new TestRunnerPanel(new InMemoryTestRunsStore());

        String runId = panel.beginRun(1, "cmd", Instant.now());
        waitForEdt();

        int requested = 210_000;
        String prefix = "BEGIN-";
        StringBuilder sb = new StringBuilder(requested + prefix.length());
        sb.append(prefix);
        for (int i = 0; i < requested; i++) {
            sb.append('x');
        }
        String longOutput = sb.toString();

        panel.appendToRun(runId, longOutput);
        waitForEdt();

        List<RunRecord> snapshot = panel.snapshotRuns(10);
        assertEquals(1, snapshot.size(), "Expected a single run in snapshot");

        String stored = snapshot.get(0).output();
        assertEquals(200_000, stored.length(), "Stored output should be capped at 200_000 chars");
        assertTrue(stored.endsWith("..."), "Stored output should end with ellipsis");
        assertTrue(stored.startsWith(prefix), "Stored output should preserve the beginning of the text when truncated");
        assertNotEquals(longOutput, stored, "Stored output should be truncated and not equal to the original");
    }

    @Test
    void persistenceRestoresCorrectRunsAndState() throws Exception {
        InMemoryTestRunsStore store = new InMemoryTestRunsStore();
        int maxRuns = 5;

        TestRunnerPanel panel1 = new TestRunnerPanel(store);
        panel1.setMaxRuns(maxRuns);
        awaitSave(panel1);

        List<String> runIds = new ArrayList<>();
        for (int i = 0; i < maxRuns + 2; i++) {
            String id = panel1.beginRun(1, "cmd " + i, Instant.now());
            runIds.add(id);
            awaitSave(panel1);

            if (i == maxRuns + 1) {
                panel1.appendToRun(id, "Output for run " + i + "\n");
                awaitSave(panel1);
            }
        }

        String newestRunId = runIds.get(runIds.size() - 1);
        panel1.completeRun(newestRunId, 0, Instant.now());
        awaitSave(panel1);

        TestRunnerPanel panel2 = new TestRunnerPanel(store);
        waitForEdt();

        DefaultListModel<?> model2 = getField(panel2, "runListModel", DefaultListModel.class);
        assertEquals(maxRuns, model2.getSize(), "Should restore only maxRuns");

        @SuppressWarnings("unchecked")
        Map<String, Object> runsById2 = getField(panel2, "runsById", Map.class);
        assertEquals(maxRuns, runsById2.size(), "runsById should have maxRuns entries");

        for (int i = 0; i < runIds.size() - maxRuns; i++) {
            assertFalse(runsById2.containsKey(runIds.get(i)), "Oldest run should not be restored");
        }
        for (int i = runIds.size() - maxRuns; i < runIds.size(); i++) {
            assertTrue(runsById2.containsKey(runIds.get(i)), "Recent run should be restored");
        }

        JList<?> runList2 = getField(panel2, "runList", JList.class);
        assertEquals(0, runList2.getSelectedIndex(), "Newest run should be selected after restore");

        JTextArea outputArea2 = getField(panel2, "outputArea", JTextArea.class);
        String expectedOutput = "Output for run " + (runIds.size() - 1) + "\n";
        assertTrue(outputArea2.getText().contains(expectedOutput), "Output area should display newest run's output");

        Object restoredNewestRunEntry = model2.getElementAt(0);
        Field completedAtField = restoredNewestRunEntry.getClass().getDeclaredField("completedAt");
        completedAtField.setAccessible(true);
        assertNotNull(completedAtField.get(restoredNewestRunEntry), "Restored newest run should have completedAt set");

        Field exitCodeField = restoredNewestRunEntry.getClass().getDeclaredField("exitCode");
        exitCodeField.setAccessible(true);
        assertEquals(0, exitCodeField.get(restoredNewestRunEntry), "Restored newest run should have correct exitCode");
    }

    @Test
    void noRunsRestoredWhenStoreIsEmpty() throws Exception {
        InMemoryTestRunsStore store = new InMemoryTestRunsStore();
        TestRunnerPanel panel = new TestRunnerPanel(store);
        waitForEdt();

        DefaultListModel<?> model = getField(panel, "runListModel", DefaultListModel.class);
        assertEquals(0, model.getSize(), "No runs should be restored when the store is empty");

        JTextArea outputArea = getField(panel, "outputArea", JTextArea.class);
        assertEquals("", outputArea.getText(), "Output area should be empty when no runs are restored");
    }

    @Test
    void maxRunsCapEnforcedDuringRestore() throws Exception {
        InMemoryTestRunsStore store = new InMemoryTestRunsStore();
        List<RunRecord> recordsToSave = new ArrayList<>();
        int initialRuns = 10;
        for (int i = 0; i < initialRuns; i++) {
            recordsToSave.add(new RunRecord(
                    "id-" + i,
                    1,
                    "cmd " + i,
                    Instant.now().minusSeconds(initialRuns - i),
                    Instant.now().minusSeconds(initialRuns - i - 1),
                    0,
                    "Output " + i));
        }
        Collections.reverse(recordsToSave);
        store.save(recordsToSave);

        int customMaxRuns = 7;
        TestRunnerPanel panel = new TestRunnerPanel(store);
        panel.setMaxRuns(customMaxRuns);
        awaitSave(panel);

        DefaultListModel<?> model = getField(panel, "runListModel", DefaultListModel.class);
        assertEquals(customMaxRuns, model.getSize(), "Restore should respect custom maxRuns cap");

        for (int i = 0; i < customMaxRuns; i++) {
            Object runEntry = model.getElementAt(i);
            Field idField = runEntry.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            String restoredId = (String) idField.get(runEntry);
            assertEquals("id-" + (initialRuns - 1 - i), restoredId);
        }

        JList<?> runList = getField(panel, "runList", JList.class);
        assertEquals(0, runList.getSelectedIndex(), "Newest run should be selected after restore with custom maxRuns");

        JTextArea outputArea = getField(panel, "outputArea", JTextArea.class);
        String expectedOutput = "Output " + (initialRuns - 1);
        assertTrue(outputArea.getText().contains(expectedOutput), "Output area should display newest restored run's output");
    }

    @Test
    void clearAllRunsPersistsClearedState() throws Exception {
        InMemoryTestRunsStore store = new InMemoryTestRunsStore();
        TestRunnerPanel panel1 = new TestRunnerPanel(store);
        waitForEdt();

        String id = panel1.beginRun(1, "run1", Instant.now());
        awaitSave(panel1);
        panel1.completeRun(id, 0, Instant.now());
        awaitSave(panel1);

        DefaultListModel<?> model1 = getField(panel1, "runListModel", DefaultListModel.class);
        assertEquals(1, model1.getSize(), "Panel1 should have 1 run");

        panel1.clearAllRuns();
        awaitSave(panel1);

        DefaultListModel<?> clearedModel = getField(panel1, "runListModel", DefaultListModel.class);
        assertEquals(0, clearedModel.getSize(), "Panel1 should have 0 runs after clear");

        TestRunnerPanel panel2 = new TestRunnerPanel(store);
        waitForEdt();

        DefaultListModel<?> model2 = getField(panel2, "runListModel", DefaultListModel.class);
        assertEquals(0, model2.getSize(), "Panel2 should have 0 runs after restoring cleared state");

        JTextArea outputArea2 = getField(panel2, "outputArea", JTextArea.class);
        assertEquals("", outputArea2.getText(), "Output area should be empty after restoring cleared state");
    }

    @Test
    void retainsOnlyMostRecent50Runs_andUpdatesSelectionAndOutput() throws Exception {
        var panel = new TestRunnerPanel(new InMemoryTestRunsStore());

        List<String> runIds = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            String id = panel.beginRun(1, "cmd " + i, Instant.now());
            runIds.add(id);
            panel.completeRun(id, 0, Instant.now());
        }
        waitForEdt();

        DefaultListModel<?> model = getField(panel, "runListModel", DefaultListModel.class);
        assertEquals(50, model.getSize(), "Should retain only 50 most recent runs");

        @SuppressWarnings("unchecked")
        Map<String, Object> runsById = getField(panel, "runsById", Map.class);
        for (int i = 0; i < 5; i++) {
            String oldId = runIds.get(i);
            assertFalse(runsById.containsKey(oldId), "Oldest run should be evicted from runsById: " + oldId);
        }

        Set<String> idsInList = new HashSet<>();
        for (int i = 0; i < model.getSize(); i++) {
            Object runEntry = model.getElementAt(i);
            Field idField = runEntry.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            String id = (String) idField.get(runEntry);
            idsInList.add(id);
        }

        for (int i = 0; i < 5; i++) {
            String oldId = runIds.get(i);
            assertFalse(idsInList.contains(oldId), "Oldest run should be evicted from list model: " + oldId);
        }

        JList<?> runList = getField(panel, "runList", JList.class);
        assertEquals(0, runList.getSelectedIndex(), "Newest run should be selected");

        String newestId = runIds.get(runIds.size() - 1);
        String appended = "Output for newest run\n";
        panel.appendToRun(newestId, appended);
        waitForEdt();

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
        assertTrue(outputArea.getText().contains(appended), "Output area should display appended text for selected run");
    }

    @Test
    void selectionUpdatesToNewest_whenPreviouslySelectedRunIsDropped() throws Exception {
        var panel = new TestRunnerPanel(new InMemoryTestRunsStore());
        panel.setMaxRuns(5);

        for (int i = 0; i < 5; i++) {
            String id = panel.beginRun(1, "init " + i, Instant.now());
            panel.completeRun(id, 0, Instant.now());
        }
        waitForEdt();

        DefaultListModel<?> model = getField(panel, "runListModel", DefaultListModel.class);
        JList<?> runList = getField(panel, "runList", JList.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> runsById = getField(panel, "runsById", Map.class);

        assertEquals(5, model.getSize(), "Expected 5 initial runs");

        Object selectedEntry = model.getElementAt(1);
        Field idField = selectedEntry.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        String oldSelectedId = (String) idField.get(selectedEntry);

        runList.setSelectedIndex(1);
        waitForEdt();

        String newestId = null;
        for (int i = 5; i < 10; i++) {
            newestId = panel.beginRun(1, "push " + i, Instant.now());
            panel.completeRun(newestId, 0, Instant.now());
            waitForEdt();
        }

        assertNotNull(newestId, "Newest run id should be captured");
        assertFalse(runsById.containsKey(oldSelectedId), "Previously selected run should be evicted from runsById");

        Set<String> idsInList = new HashSet<>();
        for (int i = 0; i < model.getSize(); i++) {
            Object runEntry = model.getElementAt(i);
            Field f = runEntry.getClass().getDeclaredField("id");
            f.setAccessible(true);
            idsInList.add((String) f.get(runEntry));
        }
        assertFalse(idsInList.contains(oldSelectedId), "Previously selected run should be evicted from the list model");
        assertEquals(0, runList.getSelectedIndex(), "Selection should point to the newest run");

        String appended = "Newest run output\n";
        panel.appendToRun(newestId, appended);
        waitForEdt();

        JTextArea outputArea = getField(panel, "outputArea", JTextArea.class);
        assertTrue(outputArea.getText().contains(appended), "Output area should display appended text for the newest selected run");
    }
}
