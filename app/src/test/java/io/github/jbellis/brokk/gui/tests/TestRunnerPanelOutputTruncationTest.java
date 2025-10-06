package io.github.jbellis.brokk.gui.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

public class TestRunnerPanelOutputTruncationTest {

    private static void waitForEdt() {
        try {
            SwingUtilities.invokeAndWait(() -> {});
        } catch (Exception e) {
            fail("Failed to wait for EDT: " + e);
        }
    }

    @Test
    void snapshotTruncatesOutputWithEllipsis() {
        var panel = new TestRunnerPanel(new InMemoryTestRunsStore());

        String runId = panel.beginRun(1, "cmd", Instant.now());
        waitForEdt();

        // Construct output longer than the cap (use ~210k)
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
        // Expect max length 200_000 and ending with "..."
        assertEquals(200_000, stored.length(), "Stored output should be capped at 200_000 chars");
        assertTrue(stored.endsWith("..."), "Stored output should end with ellipsis");
        assertTrue(stored.startsWith(prefix), "Stored output should preserve the beginning of the text when truncated");

        // Should differ from original due to truncation
        assertNotEquals(longOutput, stored, "Stored output should be truncated and not equal to the original");
    }
}
