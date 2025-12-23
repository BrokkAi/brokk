package ai.brokk.gui.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

public class TestEntryTimestampTest {

    @Test
    void timestampsAreSetAndStartIsNotOverwritten() {

        var panel = new TestRunnerPanel(new InMemoryTestRunsStore());
        var entry = new TestEntry("example/path/MyTest.java", "MyTest");

        // Before any status updates
        assertNull(entry.getStartedAt(), "startedAt should be null before RUNNING");
        assertNull(entry.getCompletedAt(), "completedAt should be null before RUNNING");

        // Mark as running
        panel.updateTestStatus(entry, TestEntry.Status.RUNNING);
        Instant startedAt = entry.getStartedAt();
        assertNotNull(startedAt, "startedAt should be set when status becomes RUNNING");
        assertNull(entry.getCompletedAt(), "completedAt should still be null while RUNNING");

        // Transition to PASSED
        panel.updateTestStatus(entry, TestEntry.Status.PASSED);
        Instant completedAt = entry.getCompletedAt();
        assertNotNull(completedAt, "completedAt should be set when status becomes PASSED");

        // Ensure startedAt was not overwritten
        assertEquals(startedAt, entry.getStartedAt(), "startedAt should not be overwritten by later status updates");

        // Sanity: completedAt should not be before startedAt (may be equal in rare cases)
        assertFalse(completedAt.isBefore(startedAt), "completedAt must not be before startedAt");
    }
}
