package io.github.jbellis.brokk.gui.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

public class TestRunnerPanelRunPersistenceTest {

    // Reusing helper methods from TestRunnerPanelRunRetentionTest/TestRunnerPanelRunSelectionAfterDropTest
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
    void persistenceRestoresCorrectRunsAndState() throws Exception {
        InMemoryTestRunsStore store = new InMemoryTestRunsStore();
        int maxRuns = 5;

        // --- Phase 1: Create runs and save state ---
        TestRunnerPanel panel1 = new TestRunnerPanel(store);
        panel1.setMaxRuns(maxRuns);
        awaitSave(panel1); // Ensure triggered save is complete

        List<String> runIds = new ArrayList<>();
        // Create more runs than maxRuns to test retention during initial creation and saving
        for (int i = 0; i < maxRuns + 2; i++) { // e.g., 7 runs for maxRuns = 5
            String id = panel1.beginRun(1, "cmd " + i, Instant.now());
            runIds.add(id);
            awaitSave(panel1); // Ensure triggered save is complete

            if (i == maxRuns + 1) {
                // Append output to the newest run for validation
                panel1.appendToRun(id, "Output for run " + i + "\n");
                awaitSave(panel1); // Ensure triggered save is complete
            }
        }

        // Complete the newest run to ensure its state is saved correctly
        String newestRunId = runIds.get(runIds.size() - 1);
        panel1.completeRun(newestRunId, 0, Instant.now());
        awaitSave(panel1); // Ensure triggered save is complete

        // --- Phase 2: Construct a new panel and validate restored state ---
        TestRunnerPanel panel2 = new TestRunnerPanel(store);
        waitForEdt(); // Ensure runs are loaded from the store into panel2

        // Assert only maxRuns are restored
        DefaultListModel<?> model2 = getField(panel2, "runListModel", DefaultListModel.class);
        assertEquals(maxRuns, model2.getSize(), "Should restore only maxRuns");

        // Assert the correct runs (the most recent ones) are restored
        @SuppressWarnings("unchecked")
        Map<String, Object> runsById2 = getField(panel2, "runsById", Map.class);
        assertEquals(maxRuns, runsById2.size(), "runsById should have maxRuns entries");

        for (int i = 0; i < runIds.size() - maxRuns; i++) {
            assertFalse(runsById2.containsKey(runIds.get(i)), "Oldest run should not be restored");
        }
        for (int i = runIds.size() - maxRuns; i < runIds.size(); i++) {
            assertTrue(runsById2.containsKey(runIds.get(i)), "Recent run should be restored");
        }

        // Assert the newest run is selected in panel2
        JList<?> runList2 = getField(panel2, "runList", JList.class);
        assertEquals(0, runList2.getSelectedIndex(), "Newest run should be selected after restore");

        // Assert the output area displays the newest run's output
        JTextArea outputArea2 = getField(panel2, "outputArea", JTextArea.class);
        String expectedOutput = "Output for run " + (runIds.size() - 1) + "\n";
        assertTrue(outputArea2.getText().contains(expectedOutput), "Output area should display newest run's output");

        // Validate completion status (exitCode and completedAt) is restored
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
        // Don't add any runs to the store

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
        int initialRuns = 10; // More than default maxRuns (50) or a custom set maxRuns
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
        // Reverse to be newest-to-oldest, to match snapshotRuns order
        Collections.reverse(recordsToSave);
        store.save(recordsToSave);

        int customMaxRuns = 7; // Set a custom maxRuns
        TestRunnerPanel panel = new TestRunnerPanel(store);
        panel.setMaxRuns(customMaxRuns);
        awaitSave(panel);

        DefaultListModel<?> model = getField(panel, "runListModel", DefaultListModel.class);
        assertEquals(customMaxRuns, model.getSize(), "Restore should respect custom maxRuns cap");

        // Verify the restored runs are the most recent ones
        for (int i = 0; i < customMaxRuns; i++) {
            Object runEntry = model.getElementAt(i);
            Field idField = runEntry.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            String restoredId = (String) idField.get(runEntry);
            // The list is newest-to-oldest, so we expect id-(initialRuns - 1 - i)
            assertEquals("id-" + (initialRuns - 1 - i), restoredId);
        }

        JList<?> runList = getField(panel, "runList", JList.class);
        assertEquals(0, runList.getSelectedIndex(), "Newest run should be selected after restore with custom maxRuns");

        JTextArea outputArea = getField(panel, "outputArea", JTextArea.class);
        // Check output of the newest restored run
        String expectedOutput = "Output " + (initialRuns - 1);
        assertTrue(
                outputArea.getText().contains(expectedOutput),
                "Output area should display newest restored run's output");
    }

    @Test
    void clearAllRunsPersistsClearedState() throws Exception {
        InMemoryTestRunsStore store = new InMemoryTestRunsStore();
        TestRunnerPanel panel1 = new TestRunnerPanel(store);
        waitForEdt();

        String id = panel1.beginRun(1, "run1", Instant.now());
        awaitSave(panel1);
        panel1.completeRun(id, 0, Instant.now());
        awaitSave(panel1); // Ensure run is saved

        DefaultListModel<?> model1 = getField(panel1, "runListModel", DefaultListModel.class);
        assertEquals(1, model1.getSize(), "Panel1 should have 1 run");

        panel1.clearAllRuns();
        awaitSave(panel1); // Ensure cleared state is saved

        DefaultListModel<?> clearedModel = getField(panel1, "runListModel", DefaultListModel.class);
        assertEquals(0, clearedModel.getSize(), "Panel1 should have 0 runs after clear");

        // Load into a new panel
        TestRunnerPanel panel2 = new TestRunnerPanel(store);
        waitForEdt();

        DefaultListModel<?> model2 = getField(panel2, "runListModel", DefaultListModel.class);
        assertEquals(0, model2.getSize(), "Panel2 should have 0 runs after restoring cleared state");

        JTextArea outputArea2 = getField(panel2, "outputArea", JTextArea.class);
        assertEquals("", outputArea2.getText(), "Output area should be empty after restoring cleared state");
    }
}
