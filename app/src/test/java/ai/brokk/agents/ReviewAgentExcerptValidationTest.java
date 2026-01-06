package ai.brokk.agents;

import ai.brokk.ICodeReview;
import ai.brokk.ICodeReview.CodeExcerpt;
import ai.brokk.Llm;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ReviewAgent excerpt validation and retry loops.
 */
class ReviewAgentExcerptValidationTest {

    @TempDir
    Path tempDir;

    private TestConsoleIO consoleIO;
    private TestContextManager cm;
    private ReviewAgent agent;

    @BeforeEach
    void setUp() throws IOException {
        // Create test files
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Existing.java"), "public class Existing {}");
        Files.writeString(tempDir.resolve("src/Another.java"), "public class Another {}");

        consoleIO = new TestConsoleIO();
        var project = new TestProject(tempDir);
        cm = new TestContextManager(project, consoleIO, java.util.Set.of(), new ai.brokk.testutil.TestAnalyzer());
        
        String diff = """
            diff --git a/src/Existing.java b/src/Existing.java
            --- a/src/Existing.java
            +++ b/src/Existing.java
            @@ -1 +1,3 @@
             public class Existing {
            +    void newMethod() {}
             }
            """;
        agent = new ReviewAgent(diff, cm, consoleIO);
    }

    /**
     * Test 1: File-not-found retry loop identifies missing files and merges corrected excerpts.
     */
    @Test
    void testFileNotFoundRetryMergesCorrections() throws Exception {
        // Initial excerpts with one missing file
        Map<Integer, CodeExcerpt> initialExcerpts = new HashMap<>();
        initialExcerpts.put(0, new CodeExcerpt("src/Existing.java", 1, ICodeReview.DiffSide.NEW, "existing code"));
        initialExcerpts.put(1, new CodeExcerpt("src/Missing.java", 0, ICodeReview.DiffSide.NEW, "missing code")); // This file doesn't exist

        // Track retry count
        AtomicInteger retryCount = new AtomicInteger(0);

        // Mock LLM that returns corrected excerpt on first retry
        StreamingChatModel mockModel = createMockModel(messages -> {
            retryCount.incrementAndGet();
            // Return corrected excerpt pointing to existing file
            return """
                BRK_EXCERPT_1
                src/Another.java @10
                ```java
                corrected code
                ```
                """;
        });

        Llm llm = new Llm(mockModel, "test", cm, false, false, false, false);
        llm.setOutput(consoleIO);

        // Create initial turn1 messages and result
        List<ChatMessage> turn1Messages = List.of(new UserMessage("test"));
        Llm.StreamingResult turn1Result = createMockResult("initial response");

        // Call the retry method
        Map<Integer, CodeExcerpt> result = agent.retryFileNotFound(llm, turn1Messages, turn1Result, initialExcerpts);

        // Verify: should have merged the corrected excerpt
        assertEquals(2, result.size());
        assertEquals("src/Existing.java", result.get(0).file()); // Original kept
        assertEquals("src/Another.java", result.get(1).file()); // Corrected
        assertEquals("corrected code", result.get(1).excerpt().trim());
        assertEquals(1, retryCount.get()); // Only 1 retry needed since we corrected it
    }

    /**
     * Test 2: Excerpt-not-found retry loop identifies unmatched content and merges corrections.
     */
    @Test
    void testExcerptNotFoundRetryMergesCorrections() throws Exception {
        String diff = """
            diff --git a/src/Existing.java b/src/Existing.java
            +    void newMethod() {}
            """;
        agent = new ReviewAgent(diff, cm, consoleIO);

        // Initial excerpts with one that doesn't match the diff
        Map<Integer, CodeExcerpt> initialExcerpts = new HashMap<>();
        initialExcerpts.put(0, new CodeExcerpt("src/Existing.java", 1, ICodeReview.DiffSide.NEW, "void newMethod() {}")); // Matches
        initialExcerpts.put(1, new CodeExcerpt("src/Existing.java", 0, ICodeReview.DiffSide.NEW, "wrongContent")); // Doesn't match

        AtomicInteger retryCount = new AtomicInteger(0);

        // Mock LLM that returns corrected excerpt
        StreamingChatModel mockModel = createMockModel(messages -> {
            retryCount.incrementAndGet();
            return """
                BRK_EXCERPT_1
                src/Existing.java @1
                ```java
                void newMethod() {}
                ```
                """;
        });

        Llm llm = new Llm(mockModel, "test", cm, false, false, false, false);
        llm.setOutput(consoleIO);

        List<ChatMessage> turn1Messages = List.of(new UserMessage("test"));
        Llm.StreamingResult turn1Result = createMockResult("initial response");

        Map<Integer, CodeExcerpt> result = agent.retryExcerptNotFound(llm, turn1Messages, turn1Result, initialExcerpts);

        // Verify corrected excerpt was merged
        assertEquals(2, result.size());
        assertTrue(diff.contains(result.get(1).excerpt().trim())); // Now matches diff
        assertEquals(1, retryCount.get());
    }

    /**
     * Test 3: File-not-found retry respects the 2-attempt limit.
     */
    @Test
    void testFileNotFoundRetryLimit() throws Exception {
        // Excerpt with permanently missing file
        Map<Integer, CodeExcerpt> initialExcerpts = new HashMap<>();
        initialExcerpts.put(0, new CodeExcerpt("src/Missing.java", 0, ICodeReview.DiffSide.NEW, "code"));

        AtomicInteger retryCount = new AtomicInteger(0);

        // Mock LLM that always returns the same missing file
        StreamingChatModel mockModel = createMockModel(messages -> {
            retryCount.incrementAndGet();
            return """
                BRK_EXCERPT_0
                src/StillMissing.java @1
                ```java
                still missing
                ```
                """;
        });

        Llm llm = new Llm(mockModel, "test", cm, false, false, false, false);
        llm.setOutput(consoleIO);

        List<ChatMessage> turn1Messages = List.of(new UserMessage("test"));
        Llm.StreamingResult turn1Result = createMockResult("initial");

        Map<Integer, CodeExcerpt> result = agent.retryFileNotFound(llm, turn1Messages, turn1Result, initialExcerpts);

        // Should have retried exactly 2 times then stopped
        assertEquals(2, retryCount.get());
        // The excerpt should still be invalid (pointing to missing file)
        assertNotNull(result.get(0));
    }

    /**
     * Test 4: Excerpt-not-found retry respects the 2-attempt limit.
     */
    @Test
    void testExcerptNotFoundRetryLimit() throws Exception {
        String diff = "actual diff content";
        agent = new ReviewAgent(diff, cm, consoleIO);

        Map<Integer, CodeExcerpt> initialExcerpts = new HashMap<>();
        initialExcerpts.put(0, new CodeExcerpt("src/Existing.java", 0, ICodeReview.DiffSide.NEW, "wrong content"));

        AtomicInteger retryCount = new AtomicInteger(0);

        // Mock LLM that always returns non-matching content
        StreamingChatModel mockModel = createMockModel(messages -> {
            retryCount.incrementAndGet();
            return """
                BRK_EXCERPT_0
                src/Existing.java @1
                ```java
                still wrong content
                ```
                """;
        });

        Llm llm = new Llm(mockModel, "test", cm, false, false, false, false);
        llm.setOutput(consoleIO);

        List<ChatMessage> turn1Messages = List.of(new UserMessage("test"));
        Llm.StreamingResult turn1Result = createMockResult("initial");

        Map<Integer, CodeExcerpt> result = agent.retryExcerptNotFound(llm, turn1Messages, turn1Result, initialExcerpts);

        // Should have retried exactly 2 times then stopped
        assertEquals(2, retryCount.get());
    }

    /**
     * Test 5: No retries when all files exist.
     */
    @Test
    void testNoRetryWhenAllFilesExist() throws Exception {
        Map<Integer, CodeExcerpt> initialExcerpts = new HashMap<>();
        initialExcerpts.put(0, new CodeExcerpt("src/Existing.java", 0, ICodeReview.DiffSide.NEW, "code"));
        initialExcerpts.put(1, new CodeExcerpt("src/Another.java", 0, ICodeReview.DiffSide.NEW, "more code"));

        AtomicInteger retryCount = new AtomicInteger(0);

        StreamingChatModel mockModel = createMockModel(messages -> {
            retryCount.incrementAndGet();
            return "should not be called";
        });

        Llm llm = new Llm(mockModel, "test", cm, false, false, false, false);
        llm.setOutput(consoleIO);

        List<ChatMessage> turn1Messages = List.of(new UserMessage("test"));
        Llm.StreamingResult turn1Result = createMockResult("initial");

        Map<Integer, CodeExcerpt> result = agent.retryFileNotFound(llm, turn1Messages, turn1Result, initialExcerpts);

        // No retries should happen
        assertEquals(0, retryCount.get());
        assertEquals(2, result.size());
    }

    /**
     * Test 6: No retries when all excerpts match diff.
     */
    @Test
    void testNoRetryWhenAllExcerptsMatchDiff() throws Exception {
        String diff = "matching content here";
        agent = new ReviewAgent(diff, cm, consoleIO);

        Map<Integer, CodeExcerpt> initialExcerpts = new HashMap<>();
        initialExcerpts.put(0, new CodeExcerpt("src/Existing.java", 0, ICodeReview.DiffSide.NEW, "matching content"));

        AtomicInteger retryCount = new AtomicInteger(0);

        StreamingChatModel mockModel = createMockModel(messages -> {
            retryCount.incrementAndGet();
            return "should not be called";
        });

        Llm llm = new Llm(mockModel, "test", cm, false, false, false, false);
        llm.setOutput(consoleIO);

        List<ChatMessage> turn1Messages = List.of(new UserMessage("test"));
        Llm.StreamingResult turn1Result = createMockResult("initial");

        Map<Integer, CodeExcerpt> result = agent.retryExcerptNotFound(llm, turn1Messages, turn1Result, initialExcerpts);

        assertEquals(0, retryCount.get());
        assertEquals(1, result.size());
    }

    // Helper to create a mock StreamingChatModel
    private StreamingChatModel createMockModel(java.util.function.Function<List<ChatMessage>, String> responseGenerator) {
        return new StreamingChatModel() {
            @Override
            public void chat(dev.langchain4j.model.chat.request.ChatRequest request, 
                           StreamingChatResponseHandler handler) {
                String response = responseGenerator.apply(request.messages());
                handler.onPartialResponse(response);
                handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(new AiMessage(response))
                    .build());
            }
        };
    }

    // Helper to create a mock StreamingResult
    private Llm.StreamingResult createMockResult(String text) {
        return Llm.StreamingResult.fromResponse(
            ChatResponse.builder()
                .aiMessage(new AiMessage(text, ""))
                .build(),
            null
        );
    }
}
