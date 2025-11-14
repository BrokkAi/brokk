package ai.brokk.tasks;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.ContextManager;
import ai.brokk.MainProject;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TaskListTest {

    @TempDir
    private Path projectRoot;

    private ContextManager cm;

    @BeforeEach
    public void setUp() throws Exception {
        cm = new ContextManager(new MainProject(projectRoot));
        cm.createHeadless();
    }

    @AfterEach
    public void tearDown() throws Exception {
        cm.close();
    }

    @Test
    public void testAppendAndPersist() throws Exception {
        UUID sessionId = cm.getCurrentSessionId();

        var tasksToAdd = List.of("first task to do", "second task to do");
        cm.appendTasksToTaskList(tasksToAdd);

        var data = cm.getTaskList();
        assertNotNull(data, "TaskListData should not be null after append");
        assertEquals(2, data.tasks().size(), "Two tasks should be present");
        var texts = data.tasks().stream().map(TaskList.TaskItem::text).collect(Collectors.toList());
        assertTrue(texts.get(0).contains("first task"), "First task text should be present");
        assertTrue(texts.get(1).contains("second task"), "Second task text should be present");

        // Verify titles are present (may be provisional or generated)
        var titles = data.tasks().stream().map(TaskList.TaskItem::title).collect(Collectors.toList());
        assertNotNull(titles.get(0), "First task title should not be null");
        assertNotNull(titles.get(1), "Second task title should not be null");

        // Close and reopen a new ContextManager on the same project to verify persistence
        cm.close();
        cm = new ContextManager(new MainProject(projectRoot));
        cm.createHeadless();

        cm.switchSessionAsync(sessionId).get();

        var persisted = cm.getTaskList();
        assertNotNull(persisted, "Persisted TaskListData should not be null");
        assertEquals(2, persisted.tasks().size(), "Persisted task count should match");
        var persistedTexts =
                persisted.tasks().stream().map(TaskList.TaskItem::text).collect(Collectors.toList());
        assertTrue(persistedTexts.get(0).contains("first task"), "Persisted first task should match");
        assertTrue(persistedTexts.get(1).contains("second task"), "Persisted second task should match");

        // Verify titles are persisted
        var persistedTitles =
                persisted.tasks().stream().map(TaskList.TaskItem::title).collect(Collectors.toList());
        assertNotNull(persistedTitles.get(0), "Persisted first task title should not be null");
        assertNotNull(persistedTitles.get(1), "Persisted second task title should not be null");
    }

    @Test
    public void testSessionSwitching() throws Exception {
        // Create session 1 and add a task
        cm.createSessionAsync("session 1").get();
        UUID session1Id = cm.getCurrentSessionId();
        cm.appendTasksToTaskList(List.of("task only in session 1"));

        // Create session 2 and add a different task
        cm.createSessionAsync("session 2").get();
        UUID session2Id = cm.getCurrentSessionId();
        cm.appendTasksToTaskList(List.of("task only in session 2"));

        // Switch back to session 1 and verify
        cm.switchSessionAsync(session1Id).get();
        var session1Tasks = cm.getTaskList();
        assertNotNull(session1Tasks, "Session 1 task list should not be null");
        assertEquals(1, session1Tasks.tasks().size(), "Session 1 should have exactly one task");
        var s1Task = session1Tasks.tasks().getFirst();
        assertTrue(s1Task.text().contains("task only in session 1"), "Session 1 task text should match");
        assertNotNull(s1Task.title(), "Session 1 task title should not be null");

        // Switch to session 2 and verify
        cm.switchSessionAsync(session2Id).get();
        var session2Tasks = cm.getTaskList();
        assertNotNull(session2Tasks, "Session 2 task list should not be null");
        assertEquals(1, session2Tasks.tasks().size(), "Session 2 should have exactly one task");
        var s2Task = session2Tasks.tasks().getFirst();
        assertTrue(s2Task.text().contains("task only in session 2"), "Session 2 task text should match");
        assertNotNull(s2Task.title(), "Session 2 task title should not be null");
    }
}
