package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.tasks.TaskList;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that tasklist.json life cycle follows session ZIP operations: - copySession: copied zip contains the same
 * tasklist.json - deleteSession: deleting zip removes tasklist.json - moveSessionToUnreadable: moved zip still contains
 * tasklist.json
 */
class SessionManagerTaskListLifecycleTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void cleanSessionsDir() throws IOException {
        Path sessionsDir = tempDir.resolve(".brokk").resolve("sessions");
        if (Files.exists(sessionsDir)) {
            try (var walk = Files.walk(sessionsDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    @Test
    void deleteMovesAndCopy() throws Exception {
        MainProject project = new MainProject(tempDir);
        SessionManager sm = project.getSessionManager();

        // Prepare a canonical task list payload with explicit titles
        TaskList.TaskListData data = new TaskList.TaskListData(List.of(
                new TaskList.TaskItem("Task A Title", "do A", false),
                new TaskList.TaskItem("Task B Title", "do B", true)));

        // 1) Create original session and write a tasklist.json inside its zip
        SessionManager.SessionInfo s1 = sm.newSession("Origin");
        UUID s1Id = s1.id();
        sm.writeTaskList(s1Id, data).get(5, TimeUnit.SECONDS);

        // Sanity: can read it back from the SessionManager API
        TaskList.TaskListData readS1 = sm.readTaskList(s1Id).get(5, TimeUnit.SECONDS);
        assertEquals(data.tasks().size(), readS1.tasks().size());
        assertEquals(data.tasks().get(0).title(), readS1.tasks().get(0).title());
        assertEquals(data.tasks().get(0).text(), readS1.tasks().get(0).text());
        assertEquals(data.tasks().get(1).title(), readS1.tasks().get(1).title());
        assertEquals(data.tasks().get(1).done(), readS1.tasks().get(1).done());

        Path sessionsDir = tempDir.resolve(".brokk").resolve("sessions");
        Path s1Zip = sessionsDir.resolve(s1Id + ".zip");
        assertTrue(Files.exists(s1Zip), "Original session zip should exist");

        // 2) copySession: new zip should contain identical tasklist.json
        SessionManager.SessionInfo s2 = sm.copySession(s1Id, "Copy");
        UUID s2Id = s2.id();
        Path s2Zip = sessionsDir.resolve(s2Id + ".zip");

        // Wait until the new zip appears
        assertEventually(() -> assertTrue(Files.exists(s2Zip), "Copied session zip should exist"));

        // Read via API and verify content equals including titles
        TaskList.TaskListData readS2 = sm.readTaskList(s2Id).get(5, TimeUnit.SECONDS);
        assertEquals(data.tasks().size(), readS2.tasks().size());
        for (int i = 0; i < data.tasks().size(); i++) {
            assertEquals(data.tasks().get(i).title(), readS2.tasks().get(i).title());
            assertEquals(data.tasks().get(i).text(), readS2.tasks().get(i).text());
            assertEquals(data.tasks().get(i).done(), readS2.tasks().get(i).done());
        }

        // 3) deleteSession: deleting the zip removes tasklist.json (implicit)
        sm.deleteSession(s1Id);

        // Deletion happens on executor; wait until zip is gone
        assertEventually(() -> assertFalse(Files.exists(s1Zip), "Original session zip should be deleted"));
        // API should now return empty task list since the zip is gone
        TaskList.TaskListData afterDelete = sm.readTaskList(s1Id).get(5, TimeUnit.SECONDS);
        assertTrue(afterDelete.tasks().isEmpty(), "Reading deleted session should return empty task list");

        // 4) moveSessionToUnreadable: move the copied session and verify tasklist.json moved intact
        sm.moveSessionToUnreadable(s2Id); // synchronous (waits on future internally)
        Path unreadableDir = sessionsDir.resolve("unreadable");
        Path movedZip = unreadableDir.resolve(s2Id + ".zip");
        assertTrue(Files.exists(unreadableDir), "unreadable/ dir must exist");
        assertTrue(Files.exists(movedZip), "Moved session zip should exist under unreadable/");

        // Original location should no longer have the zip
        assertFalse(Files.exists(s2Zip), "Copied session zip should be moved out of sessions root");

        // Manually open moved zip and verify tasklist.json content equals original "data" including titles
        TaskList.TaskListData movedData = readTaskListDirect(movedZip);
        assertNotNull(movedData, "Moved task list should be readable");
        assertEquals(data.tasks().size(), movedData.tasks().size());
        for (int i = 0; i < data.tasks().size(); i++) {
            assertEquals(data.tasks().get(i).title(), movedData.tasks().get(i).title());
            assertEquals(data.tasks().get(i).text(), movedData.tasks().get(i).text());
            assertEquals(data.tasks().get(i).done(), movedData.tasks().get(i).done());
        }

        project.close();
    }

    private static void assertEventually(Runnable assertion) throws InterruptedException {
        long timeoutMs = 5_000;
        long intervalMs = 100;
        long start = System.currentTimeMillis();
        AssertionError last = null;
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                last = e;
                Thread.sleep(intervalMs);
            }
        }
        if (last != null) throw last;
        throw new AssertionError("Condition not met within timeout");
    }

    /** Low-level helper: read tasklist.json directly from a given session zip path. */
    private static TaskList.TaskListData readTaskListDirect(Path zipPath) throws IOException {
        try (var fs = FileSystems.newFileSystem(zipPath, Map.of())) {
            Path tasklist = fs.getPath("tasklist.json");
            if (!Files.exists(tasklist)) {
                return new TaskList.TaskListData(List.of());
            }
            String json = Files.readString(tasklist);
            return AbstractProject.objectMapper.readValue(json, TaskList.TaskListData.class);
        }
    }

    @Test
    void legacyMissingTitlesAreDefaulted() throws Exception {
        MainProject project = new MainProject(tempDir);
        SessionManager sm = project.getSessionManager();

        // Create a new session
        SessionManager.SessionInfo sessionInfo = sm.newSession("Legacy");
        UUID sessionId = sessionInfo.id();

        Path sessionsDir = tempDir.resolve(".brokk").resolve("sessions");
        Path sessionZip = sessionsDir.resolve(sessionId + ".zip");

        // Wait for session zip to be created
        assertEventually(() -> assertTrue(Files.exists(sessionZip), "Session zip should exist"));

        // Write legacy tasklist.json (without title field) directly into the zip
        String legacyJson =
                """
                {
                  "tasks": [
                    { "text": "legacy task A", "done": false },
                    { "text": "legacy task B", "done": true }
                  ]
                }
                """;

        try (var fs = FileSystems.newFileSystem(sessionZip, Map.of())) {
            Path tasklistPath = fs.getPath("tasklist.json");
            Files.writeString(
                    tasklistPath, legacyJson, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        // Read the task list via SessionManager
        TaskList.TaskListData data = sm.readTaskList(sessionId).get(5, TimeUnit.SECONDS);

        // Assert data was read correctly
        assertNotNull(data, "TaskListData should not be null");
        assertEquals(2, data.tasks().size(), "Should have 2 tasks");

        // Verify first task
        TaskList.TaskItem task0 = data.tasks().get(0);
        assertEquals("legacy task A", task0.text(), "First task text should match");
        assertFalse(task0.done(), "First task should not be done");
        assertNotNull(task0.title(), "First task title should not be null");
        assertEquals("", task0.title(), "First task title should be empty string (defaulted)");

        // Verify second task
        TaskList.TaskItem task1 = data.tasks().get(1);
        assertEquals("legacy task B", task1.text(), "Second task text should match");
        assertTrue(task1.done(), "Second task should be done");
        assertNotNull(task1.title(), "Second task title should not be null");
        assertEquals("", task1.title(), "Second task title should be empty string (defaulted)");

        project.close();
    }
}
