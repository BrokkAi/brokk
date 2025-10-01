package io.github.jbellis.brokk.sessions;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.sessions.TaskListStore.TaskEntryDto;
import io.github.jbellis.brokk.sessions.TaskListStore.TaskListData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskListStoreTest {

    @TempDir Path tempDir;

    @Test
    void readWriteRoundTrip() throws Exception {
        var sessionDir = tempDir.resolve("sessions").resolve("abc123");
        var taskFile = sessionDir.resolve("tasklist.json");

        // Non-existent file -> empty list
        var empty = TaskListStore.read(taskFile);
        assertNotNull(empty);
        assertTrue(empty.tasks().isEmpty(), "Expected empty tasks for non-existent file");

        var original = new TaskListData(List.of(
                new TaskEntryDto("first task", false),
                new TaskEntryDto("second task", true)));

        TaskListStore.write(taskFile, original);
        assertTrue(Files.exists(taskFile), "tasklist.json should be created");

        var loaded = TaskListStore.read(taskFile);
        assertEquals(original.tasks().size(), loaded.tasks().size());
        assertEquals(original.tasks().get(0).text(), loaded.tasks().get(0).text());
        assertEquals(original.tasks().get(0).done(), loaded.tasks().get(0).done());
        assertEquals(original.tasks().get(1).text(), loaded.tasks().get(1).text());
        assertEquals(original.tasks().get(1).done(), loaded.tasks().get(1).done());
    }
}
