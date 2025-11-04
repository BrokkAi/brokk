package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.SessionManager.SessionInfo;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Simulates a legacy tasklist.json in a session zip (with no Task List fragment),
 * verifies round-trip via SessionManager, then applies Context.withTaskList(...) to
 * emulate the post-migration state and asserts that:
 *
 *  - a Task List StringFragment exists with equivalent JSON
 *  - getTaskListDataOrEmpty() matches the legacy data
 */
public class TaskListFragmentMigrationTest {

    @Test
    void legacyJsonRoundTrip_thenFragmentAppearsWithEquivalentJson() throws Exception {
        // Given: a temp sessions directory and a legacy tasklist.json written via SessionManager
        Path sessionsDir = Files.createTempDirectory("tasklist-migration-test");
        try (var sm = new SessionManager(sessionsDir)) {
            SessionInfo info = sm.newSession("Legacy Session");
            UUID sessionId = info.id();

            var legacyData = new TaskList.TaskListData(
                    List.of(new TaskList.TaskItem("Legacy One", false), new TaskList.TaskItem("Legacy Two", false)));

            // Write legacy tasklist.json
            sm.writeTaskList(sessionId, legacyData).get(10, TimeUnit.SECONDS);

            // Sanity: legacy read returns the same structure
            var loaded = sm.readTaskList(sessionId).get(10, TimeUnit.SECONDS);
            assertEquals(legacyData.tasks(), loaded.tasks(), "Legacy read should match written tasks");

            // When: The context initially has no Task List fragment, we "migrate" by adding it
            var cm = new IContextManager() {};
            var base = new Context(cm, (String) null);
            var migrated = base.withTaskList(loaded);

            // Then: a Task List StringFragment is present with equivalent JSON
            Optional<ContextFragment.StringFragment> fragOpt = migrated.getTaskListFragment();
            assertTrue(fragOpt.isPresent(), "Task List fragment should be present after migration step");

            var expectedJson = Json.getMapper().writeValueAsString(legacyData);
            assertEquals(expectedJson, fragOpt.get().text(), "Fragment JSON should equal legacy JSON");

            // And: Context.getTaskListDataOrEmpty() matches legacy
            var parsed = migrated.getTaskListDataOrEmpty();
            assertEquals(legacyData.tasks(), parsed.tasks(), "Migrated TaskListData must equal legacy data");
        }
    }
}
