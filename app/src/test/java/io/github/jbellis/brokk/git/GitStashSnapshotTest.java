package io.github.jbellis.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.testutil.NoOpConsoleIO;
import io.github.jbellis.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GitStashSnapshotTest {

    @TempDir
    Path tempDir;

    @Test
    void noopSnapshotDoesNotBreakHistory() {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());
        Context initialLive = cm.liveContext();

        // Initialize history with current live context
        var history = new ContextHistory(initialLive);
        int sizeBefore = history.getHistory().size();

        // Simulate pre-stash snapshot (with no changes)
        history.addFrozenContextAndClearRedo(cm.liveContext());
        int sizeAfter = history.getHistory().size();

        assertEquals(sizeBefore + 1, sizeAfter, "History should add a snapshot entry");
        assertNotNull(history.topContext(), "Top context should not be null after snapshot");
    }
}
