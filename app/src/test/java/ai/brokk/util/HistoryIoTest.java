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
import java.util.concurrent.CompletableFuture;
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
        ContextFragments.setMinimumId(1);
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
            var ctx = new Context(contextManager)
                    .addHistoryEntry(
                            new TaskEntry(i + 1, taskFragment, null),
                            taskFragment,
                            CompletableFuture.completedFuture("action" + i));
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
