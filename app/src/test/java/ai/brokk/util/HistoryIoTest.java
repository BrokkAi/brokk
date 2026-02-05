package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.ContextHistory;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HistoryIoTest {
    @TempDir
    Path tempDir;

    private IContextManager contextManager;

    @BeforeEach
    void setup() throws IOException {
        contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
    }

    @Test
    void testCountAiResponses_v4ZipWithMixedContexts() throws Exception {
        // Create history with 3 AI responses and 2 non-AI contexts
        var initialContext = new Context(contextManager);
        var history = new ContextHistory(initialContext);

        // Add 3 contexts with AI responses (have parsedOutput)
        for (int i = 0; i < 3; i++) {
            var msgs = List.<ChatMessage>of(UserMessage.from("Query " + i), AiMessage.from("Response " + i));
            var taskFragment = new ContextFragments.TaskFragment(contextManager, msgs, "Task " + i);
            Context context = new Context(contextManager);
            var ctx = context.addHistoryEntry(new TaskEntry(i + 1, taskFragment, null), taskFragment);
            history.pushContext(ctx);
        }

        // Add 2 contexts without AI responses (no parsedOutput - just fragments)
        var sf1 = new ContextFragments.StringFragment(contextManager, "content1", "desc1", null);
        var ctx1 = new Context(contextManager).addFragments(sf1);
        history.pushContext(ctx1);

        var sf2 = new ContextFragments.StringFragment(contextManager, "content2", "desc2", null);
        var ctx2 = new Context(contextManager).addFragments(sf2);
        history.pushContext(ctx2);

        Path zipFile = tempDir.resolve("mixed_contexts.zip");
        HistoryIo.writeZip(history, zipFile);

        int count = HistoryIo.countAiResponses(zipFile);
        assertEquals(3, count, "Should count only contexts with parsedOutputId");
    }

    @Test
    void testCountAiResponses_nonExistentFile() throws Exception {
        Path nonExistent = tempDir.resolve("does_not_exist.zip");
        int count = HistoryIo.countAiResponses(nonExistent);
        assertEquals(0, count, "Non-existent file should return 0");
    }

    @Test
    void testCountIncompleteTasks_zipWithNoTaskList() throws Exception {
        Path zipFile = tempDir.resolve("no_tasklist.zip");

        String fragmentId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();

        // A StringFragment that is NOT a task list (different description)
        String fragmentsJson =
                """
            {
              "version": 4,
              "referenced": {},
              "virtual": {
                "%s": {
                  "type": "ai.brokk.context.FragmentDtos$StringFragmentDto",
                  "id": "%s",
                  "contentId": "%s",
                  "description": "Some Other Fragment",
                  "syntaxStyle": "text"
                }
              },
              "task": {}
            }
            """
                        .formatted(fragmentId, fragmentId, contentId);

        String contextsJsonl =
                """
            {"id":"%s","editable":[],"readonly":[],"virtuals":["%s"],"pinned":[],"tasks":[],"parsedOutputId":null}
            """
                        .formatted(UUID.randomUUID().toString(), fragmentId);

        String content = "Some text content";

        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("fragments-v4.json"));
            zos.write(fragmentsJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("contexts.jsonl"));
            zos.write(contextsJsonl.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("content/" + contentId + ".txt"));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        var counts = HistoryIo.countIncompleteTasks(zipFile);
        assertEquals(new HistoryIo.TaskCounts(0, 0), counts, "ZIP with no task list fragment should return (0, 0)");
    }

    @Test
    void testCountIncompleteTasks_zipWithEmptyTaskList() throws Exception {
        Path zipFile = tempDir.resolve("empty_tasklist.zip");

        String fragmentId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();

        String fragmentsJson =
                """
            {
              "version": 4,
              "referenced": {},
              "virtual": {
                "%s": {
                  "type": "ai.brokk.context.FragmentDtos$StringFragmentDto",
                  "id": "%s",
                  "contentId": "%s",
                  "description": "Task List",
                  "syntaxStyle": "json"
                }
              },
              "task": {}
            }
            """
                        .formatted(fragmentId, fragmentId, contentId);

        String contextsJsonl =
                """
            {"id":"%s","editable":[],"readonly":[],"virtuals":["%s"],"pinned":[],"tasks":[],"parsedOutputId":null}
            """
                        .formatted(UUID.randomUUID().toString(), fragmentId);

        String taskListContent = """
            {"tasks":[]}
            """;

        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("fragments-v4.json"));
            zos.write(fragmentsJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("contexts.jsonl"));
            zos.write(contextsJsonl.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("content/" + contentId + ".txt"));
            zos.write(taskListContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        var counts = HistoryIo.countIncompleteTasks(zipFile);
        assertEquals(new HistoryIo.TaskCounts(0, 0), counts, "ZIP with empty task list should return (0, 0)");
    }

    @Test
    void testCountIncompleteTasks_zipWithMixedTasks() throws Exception {
        Path zipFile = tempDir.resolve("mixed_tasks.zip");

        String fragmentId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();

        String fragmentsJson =
                """
            {
              "version": 4,
              "referenced": {},
              "virtual": {
                "%s": {
                  "type": "ai.brokk.context.FragmentDtos$StringFragmentDto",
                  "id": "%s",
                  "contentId": "%s",
                  "description": "Task List",
                  "syntaxStyle": "json"
                }
              },
              "task": {}
            }
            """
                        .formatted(fragmentId, fragmentId, contentId);

        String contextsJsonl =
                """
            {"id":"%s","editable":[],"readonly":[],"virtuals":["%s"],"pinned":[],"tasks":[],"parsedOutputId":null}
            """
                        .formatted(UUID.randomUUID().toString(), fragmentId);

        // 3 tasks: 1 done, 2 incomplete
        String taskListContent =
                """
            {
              "tasks": [
                {"id": "%s", "title": "Task 1", "text": "First task", "done": false},
                {"id": "%s", "title": "Task 2", "text": "Second task", "done": true},
                {"id": "%s", "title": "Task 3", "text": "Third task", "done": false}
              ]
            }
            """
                        .formatted(
                                UUID.randomUUID().toString(),
                                UUID.randomUUID().toString(),
                                UUID.randomUUID().toString());

        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("fragments-v4.json"));
            zos.write(fragmentsJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("contexts.jsonl"));
            zos.write(contextsJsonl.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("content/" + contentId + ".txt"));
            zos.write(taskListContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        var counts = HistoryIo.countIncompleteTasks(zipFile);
        assertEquals(new HistoryIo.TaskCounts(3, 2), counts, "Should return (3 total, 2 incomplete)");
    }

    @Test
    void testCountIncompleteTasks_legacyTasklistJsonOnly() throws Exception {
        Path zipFile = tempDir.resolve("legacy_tasklist.zip");

        // 2 tasks: 1 done, 1 incomplete
        String legacyTaskListContent =
                """
            {
              "tasks": [
                {"id": "%s", "title": "Legacy Task 1", "text": "First legacy task", "done": false},
                {"id": "%s", "title": "Legacy Task 2", "text": "Second legacy task", "done": true}
              ]
            }
            """
                        .formatted(
                                UUID.randomUUID().toString(), UUID.randomUUID().toString());

        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("tasklist.json"));
            zos.write(legacyTaskListContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        var counts = HistoryIo.countIncompleteTasks(zipFile);
        assertEquals(new HistoryIo.TaskCounts(2, 1), counts, "Legacy format should return (2 total, 1 incomplete)");
    }

    @Test
    void testCountIncompleteTasks_emptyNewFormatWithLegacyFallback() throws Exception {
        // This test verifies that the new format takes precedence over legacy.
        // When a task list fragment IS found (even with empty tasks), the legacy format
        // should NOT be used as fallback.
        Path zipFile = tempDir.resolve("new_format_precedence.zip");

        String fragmentId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();

        // New format with empty task list
        String fragmentsJson =
                """
            {
              "version": 4,
              "referenced": {},
              "virtual": {
                "%s": {
                  "type": "ai.brokk.context.FragmentDtos$StringFragmentDto",
                  "id": "%s",
                  "contentId": "%s",
                  "description": "Task List",
                  "syntaxStyle": "json"
                }
              },
              "task": {}
            }
            """
                        .formatted(fragmentId, fragmentId, contentId);

        String contextsJsonl =
                """
            {"id":"%s","editable":[],"readonly":[],"virtuals":["%s"],"pinned":[],"tasks":[],"parsedOutputId":null}
            """
                        .formatted(UUID.randomUUID().toString(), fragmentId);

        String emptyTaskListContent = """
            {"tasks":[]}
            """;

        // Legacy format with actual tasks
        String legacyTaskListContent =
                """
            {
              "tasks": [
                {"id": "%s", "title": "Legacy Task 1", "text": "First legacy task", "done": false},
                {"id": "%s", "title": "Legacy Task 2", "text": "Second legacy task", "done": true}
              ]
            }
            """
                        .formatted(
                                UUID.randomUUID().toString(), UUID.randomUUID().toString());

        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("fragments-v4.json"));
            zos.write(fragmentsJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("contexts.jsonl"));
            zos.write(contextsJsonl.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("content/" + contentId + ".txt"));
            zos.write(emptyTaskListContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("tasklist.json"));
            zos.write(legacyTaskListContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        var counts = HistoryIo.countIncompleteTasks(zipFile);
        // New format takes precedence - should return (0, 0), NOT legacy counts (2, 1)
        assertEquals(
                new HistoryIo.TaskCounts(0, 0),
                counts,
                "New format should take precedence; empty new-format task list should NOT fall back to legacy");
    }

    @Test
    void testCountAiResponses_blankLinesAndMalformedJson() throws Exception {
        // Create a zip with manually crafted contexts.jsonl
        Path zipFile = tempDir.resolve("malformed_contexts.zip");
        String contextsContent =
                """
            {"id":"1","editable":[],"readonly":[],"virtuals":[],"tasks":[],"parsedOutputId":"task1","action":"a","groupId":null,"groupLabel":null}

            {"id":"2","editable":[],"readonly":[],"virtuals":[],"tasks":[],"parsedOutputId":null,"action":"b","groupId":null,"groupLabel":null}
            not valid json at all
            {"id":"3","editable":[],"readonly":[],"virtuals":[],"tasks":[],"parsedOutputId":"task2","action":"c","groupId":null,"groupLabel":null}
               \s
            {"malformed json missing closing brace
            {"id":"4","editable":[],"readonly":[],"virtuals":[],"tasks":[],"parsedOutputId":"task3","action":"d","groupId":null,"groupLabel":null}
            """;

        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            var entry = new ZipEntry("contexts.jsonl");
            zos.putNextEntry(entry);
            zos.write(contextsContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        int count = HistoryIo.countAiResponses(zipFile);
        assertEquals(3, count, "Should count valid AI entries (ids 1, 3, 4), ignoring blank lines and malformed JSON");
    }
}
