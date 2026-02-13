package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AbstractService;
import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.util.Messages;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodePromptsTest {
    private static final String ELIDED_BLOCK_PLACEHOLDER = "[elided SEARCH/REPLACE block]";

    // --- Redaction (S/R Block) Tests ---

    private String createMinimalMessage(String filename, String search, String replace) {
        return """
               ```
               %s
               <<<<<<< SEARCH
               %s
               =======
               %s
               >>>>>>> REPLACE
               ```
               """
                .formatted(filename, search, replace);
    }

    private String createMarkdownMessage(String filename, String search, String replace) {
        return """
               ```java
               %s
               <<<<<<< SEARCH
               %s
               =======
               %s
               >>>>>>> REPLACE
               ```
               """
                .formatted(filename, search, replace);
    }

    private void assertRedaction(String aiText, String expectedText) {
        AiMessage originalMessage = new AiMessage(aiText);
        Optional<AiMessage> redactedResult = CodePrompts.redactEditBlocks(originalMessage);

        assertTrue(redactedResult.isPresent(), "Message should be present after redaction.");
        assertEquals(expectedText, redactedResult.get().text(), "Redaction mismatch.");
    }

    @Test
    void removesBlockOnlyMessages() {
        String minimal = createMinimalMessage("file.txt", "old code", "new code");
        assertRedaction(minimal, ELIDED_BLOCK_PLACEHOLDER);

        String markdown = createMarkdownMessage("file.txt", "old code", "new code");
        assertRedaction(markdown, ELIDED_BLOCK_PLACEHOLDER);
    }

    @Test
    void insertsPlaceholderIntoMixedMessage() {
        String prefix = "Here is the patch:\n\n";
        String suffix = "\n\nHope that helps!";

        String minimal = prefix + createMinimalMessage("foo.txt", "old", "new") + suffix;
        assertRedaction(minimal, prefix + ELIDED_BLOCK_PLACEHOLDER + suffix);

        String markdown = prefix + createMarkdownMessage("foo.txt", "old", "new") + suffix;
        assertRedaction(markdown, prefix + ELIDED_BLOCK_PLACEHOLDER + suffix);
    }

    @Test
    void leavesPlainMessageUntouched() {
        String aiText = "This is a plain message with no S/R blocks.";
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactEditBlocks(originalMessage);

        assertTrue(redactedResult.isPresent(), "Plain message should be present.");
        assertEquals(originalMessage.text(), redactedResult.get().text(), "Plain message text should be unchanged.");
    }

    @Test
    void handlesEmptyMessage() {
        String aiText = "";
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactEditBlocks(originalMessage);
        assertTrue(redactedResult.isEmpty(), "Empty message should result in empty optional.");
    }

    @Test
    void handlesBlankMessage() {
        String aiText = "   \n\t   ";
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactEditBlocks(originalMessage);
        assertTrue(redactedResult.isEmpty(), "Blank message should result in empty optional after redaction.");
    }

    @Test
    void handlesMultipleBlocksAndTextSegments() {
        String text1 = "First part of the message.\n";
        String text2 = "\nSome intermediate text.\n";
        String text3 = "\nFinal part.";
        String expected = text1 + ELIDED_BLOCK_PLACEHOLDER + text2 + ELIDED_BLOCK_PLACEHOLDER + text3;

        String minimal = text1
                + createMinimalMessage("file1.txt", "s1", "r1")
                + text2
                + createMinimalMessage("file2.java", "s2", "r2")
                + text3;
        assertRedaction(minimal, expected);

        String markdown = text1
                + createMarkdownMessage("file1.txt", "s1", "r1")
                + text2
                + createMarkdownMessage("file2.java", "s2", "r2")
                + text3;
        assertRedaction(markdown, expected);
    }

    @Test
    void handlesMessageWithOnlyMultipleBlocks() {
        String expected = ELIDED_BLOCK_PLACEHOLDER + "\n" + ELIDED_BLOCK_PLACEHOLDER;

        String minimal =
                createMinimalMessage("file1.txt", "s1", "r1") + "\n" + createMinimalMessage("file2.txt", "s2", "r2");
        assertRedaction(minimal, expected);

        String markdown =
                createMarkdownMessage("file1.txt", "s1", "r1") + "\n" + createMarkdownMessage("file2.txt", "s2", "r2");
        assertRedaction(markdown, expected);
    }

    @Test
    void handlesMessageEndingWithBlock() {
        String text = "Text before block\n";

        assertRedaction(text + createMinimalMessage("file.end", "s", "r"), text + ELIDED_BLOCK_PLACEHOLDER);
        assertRedaction(text + createMarkdownMessage("file.end", "s", "r"), text + ELIDED_BLOCK_PLACEHOLDER);
    }

    @Test
    void handlesMessageStartingWithBlock() {
        String text = "\nText after block";

        assertRedaction(createMinimalMessage("file.start", "s", "r") + text, ELIDED_BLOCK_PLACEHOLDER + text);
        assertRedaction(createMarkdownMessage("file.start", "s", "r") + text, ELIDED_BLOCK_PLACEHOLDER + text);
    }

    @Test
    void silentModeRemovesBlocksEntirely() {
        String prefix = "Here is the patch:\n\n";
        String suffix = "\n\nHope that helps!";

        String minimal = prefix + createMinimalMessage("foo.txt", "old", "new") + suffix;
        AiMessage originalMessage = new AiMessage(minimal);

        Optional<AiMessage> silentResult = CodePrompts.redactEditBlocks(originalMessage, false);

        assertTrue(silentResult.isPresent(), "Message should be present after silent redaction.");
        String silentText = silentResult.get().text();
        assertFalse(silentText.contains(ELIDED_BLOCK_PLACEHOLDER), "Silent mode should not include placeholder");
        assertTrue(silentText.contains("Here is the patch:"), "Silent mode should preserve prefix text");
        assertTrue(silentText.contains("Hope that helps!"), "Silent mode should preserve suffix text");
    }

    @Test
    void silentModeWithOnlyBlocksReturnsEmpty() {
        String minimal = createMinimalMessage("file.txt", "old code", "new code");
        AiMessage originalMessage = new AiMessage(minimal);

        Optional<AiMessage> silentResult = CodePrompts.redactEditBlocks(originalMessage, false);

        assertTrue(silentResult.isEmpty(), "Silent redaction of block-only message should return empty");
    }

    @Test
    void silentModeWithMultipleBlocksPreservesIntermediateText() {
        String text1 = "First part.\n";
        String text2 = "\nMiddle part.\n";
        String text3 = "\nFinal part.";

        String message = text1
                + createMinimalMessage("file1.txt", "s1", "r1")
                + text2
                + createMinimalMessage("file2.java", "s2", "r2")
                + text3;
        AiMessage originalMessage = new AiMessage(message);

        Optional<AiMessage> silentResult = CodePrompts.redactEditBlocks(originalMessage, false);

        assertTrue(silentResult.isPresent());
        String silentText = silentResult.get().text();
        assertFalse(silentText.contains(ELIDED_BLOCK_PLACEHOLDER));
        assertTrue(silentText.contains("First part."));
        assertTrue(silentText.contains("Middle part."));
        assertTrue(silentText.contains("Final part."));
    }

    // --- Tool Redaction Logic Tests ---

    @Test
    void shouldRedactFalse_preservesToolRequestAndResultMessages() {
        var toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("searchFiles")
                .arguments("{\"pattern\": \"*.java\"}")
                .build();
        var aiWithTools = new AiMessage(List.of(toolRequest));
        var toolResult = new ToolExecutionResultMessage("call-1", "searchFiles", "Found 5 files");
        var userMessage = new UserMessage("Find Java files");

        var messages = List.<ChatMessage>of(userMessage, aiWithTools, toolResult);

        var result = CodePrompts.redactHistoryMessages(messages, false);

        assertEquals(3, result.size());
        assertSame(userMessage, result.get(0));
        assertInstanceOf(AiMessage.class, result.get(1));
        var resultAi = (AiMessage) result.get(1);
        assertTrue(resultAi.hasToolExecutionRequests());
        assertEquals(1, resultAi.toolExecutionRequests().size());
        assertInstanceOf(ToolExecutionResultMessage.class, result.get(2));
    }

    @Test
    void shouldRedactTrue_dropsToolExecutionResultMessage() {
        var toolResult = new ToolExecutionResultMessage("call-1", "searchFiles", "Found 5 files");
        var userMessage = new UserMessage("Find Java files");

        var messages = List.<ChatMessage>of(userMessage, toolResult);

        var result = CodePrompts.redactHistoryMessages(messages, true);

        assertEquals(1, result.size());
        assertSame(userMessage, result.get(0));
    }

    @Test
    void shouldRedactTrue_rewritesToolRequestAiMessageIntoPassiveText() {
        var toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("addFilesToWorkspace")
                .arguments("{\"paths\": [\"src/Main.java\"]}")
                .build();
        var aiWithTools = new AiMessage(List.of(toolRequest));

        var messages = List.<ChatMessage>of(aiWithTools);

        var result = CodePrompts.redactHistoryMessages(messages, true);

        assertEquals(1, result.size());
        assertInstanceOf(AiMessage.class, result.get(0));
        var resultAi = (AiMessage) result.get(0);
        assertFalse(resultAi.hasToolExecutionRequests());

        var text = resultAi.text();
        assertTrue(text.contains("[Historical tool usage by a different model]"));
        assertTrue(text.contains("Tool `addFilesToWorkspace` was invoked with {\"paths\": [\"src/Main.java\"]}"));
    }

    @Test
    void shouldRedactTrue_preservesOriginalAiMessageTextBeforeToolList() {
        var toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("searchFiles")
                .arguments("{}")
                .build();
        var aiWithTextAndTools = new AiMessage("Let me search for relevant files.", List.of(toolRequest));

        var messages = List.<ChatMessage>of(aiWithTextAndTools);

        var result = CodePrompts.redactHistoryMessages(messages, true);

        assertEquals(1, result.size());
        var resultAi = (AiMessage) result.get(0);
        var text = resultAi.text();

        assertTrue(text.startsWith("Let me search for relevant files."));
        assertTrue(text.contains("[Historical tool usage by a different model]"));
        assertTrue(text.contains("Tool `searchFiles` was invoked with {}"));
    }

    @Test
    void shouldRedactTrue_handlesMultipleToolRequests() {
        var request1 = ToolExecutionRequest.builder()
                .id("call-1")
                .name("searchFiles")
                .arguments("{\"pattern\": \"*.java\"}")
                .build();
        var request2 = ToolExecutionRequest.builder()
                .id("call-2")
                .name("addFilesToWorkspace")
                .arguments("{\"paths\": [\"a.java\", \"b.java\"]}")
                .build();
        var aiWithMultipleTools = new AiMessage(List.of(request1, request2));

        var messages = List.<ChatMessage>of(aiWithMultipleTools);

        var result = CodePrompts.redactHistoryMessages(messages, true);

        assertEquals(1, result.size());
        var text = ((AiMessage) result.get(0)).text();

        assertTrue(text.contains("[Historical tool usage by a different model]"));
        assertTrue(text.contains("Tool `searchFiles` was invoked with {\"pattern\": \"*.java\"}"));
        assertTrue(text.contains("Tool `addFilesToWorkspace` was invoked with {\"paths\": [\"a.java\", \"b.java\"]}"));
    }

    @Test
    void shouldRedactFalse_stillAppliesSRBlockRedactionToAiMessages() {
        var aiWithSRBlock = new AiMessage(
                """
                Here is the fix:
                ```java
                foo.java
                <<<<<<< SEARCH
                old code
                =======
                new code
                >>>>>>> REPLACE
                ```
                """);

        var messages = List.<ChatMessage>of(aiWithSRBlock);

        var result = CodePrompts.redactHistoryMessages(messages, false);

        assertEquals(1, result.size());
        var text = ((AiMessage) result.get(0)).text();
        assertTrue(text.contains("[elided SEARCH/REPLACE block]"));
        assertFalse(text.contains("<<<<<<< SEARCH"));
    }

    @Test
    void shouldRedactTrue_aiMessageWithoutToolRequests_appliesSRBlockRedaction() {
        var aiWithSRBlock = new AiMessage(
                """
                file.txt
                <<<<<<< SEARCH
                old
                =======
                new
                >>>>>>> REPLACE
                """);

        var messages = List.<ChatMessage>of(aiWithSRBlock);

        var result = CodePrompts.redactHistoryMessages(messages, true);

        assertEquals(1, result.size());
        var text = ((AiMessage) result.get(0)).text();
        assertTrue(text.contains("[elided SEARCH/REPLACE block]"));
    }

    @Test
    void preservesOtherMessageTypes() {
        var user = new UserMessage("Hello");
        var ai = new AiMessage("Hi there");

        var messages = List.<ChatMessage>of(user, ai);

        var resultFalse = CodePrompts.redactHistoryMessages(messages, false);
        var resultTrue = CodePrompts.redactHistoryMessages(messages, true);

        assertEquals(2, resultFalse.size());
        assertEquals(2, resultTrue.size());
        assertSame(user, resultFalse.get(0));
        assertSame(user, resultTrue.get(0));
    }

    @Test
    void handlesEmptyMessageList() {
        var result = CodePrompts.redactHistoryMessages(List.of(), true);
        assertTrue(result.isEmpty());
    }

    // --- History Fetching (getHistoryMessages) Tests ---

    @Test
    void getHistoryMessages_sameModel_preservesToolExecutionRequests() {
        IContextManager cm = new IContextManager() {};
        Context ctx = new Context(cm);

        var toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("searchFiles")
                .arguments("{\"pattern\": \"*.java\"}")
                .build();
        var aiWithTools = new AiMessage(List.of(toolRequest));
        var toolResult = new ToolExecutionResultMessage("call-1", "searchFiles", "Found 5 files");
        var finalAi = new AiMessage("Done.");
        var user = new UserMessage("Find Java files");

        var entryMessages = List.<ChatMessage>of(user, aiWithTools, toolResult, finalAi);

        var entryMeta = new TaskResult.TaskMeta(TaskResult.Type.CODE, new AbstractService.ModelConfig("model-A"));
        var entry = new TaskEntry(
                0,
                new ContextFragments.TaskFragment(cm, entryMessages, "test task"),
                new ContextFragments.TaskFragment(cm, entryMessages, "test task"),
                null,
                entryMeta);

        ctx = ctx.withHistory(List.of(entry));

        var currentMeta = new TaskResult.TaskMeta(TaskResult.Type.CODE, new AbstractService.ModelConfig("model-A"));
        var history = WorkspacePrompts.getHistoryMessages(ctx, currentMeta);

        var toolAiMessages = history.stream()
                .filter(m -> m instanceof AiMessage ai && ai.hasToolExecutionRequests())
                .map(m -> (AiMessage) m)
                .toList();

        assertEquals(1, toolAiMessages.size());
        assertSame(aiWithTools, toolAiMessages.getFirst());
        assertEquals(1, toolAiMessages.getFirst().toolExecutionRequests().size());

        assertTrue(history.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage));

        assertFalse(history.stream()
                .filter(m -> m instanceof AiMessage)
                .map(m -> ((AiMessage) m).text())
                .filter(Objects::nonNull)
                .anyMatch(t -> t.contains("[Historical tool usage by a different model]")));
    }

    @Test
    void getHistoryMessages_differentModel_dropsToolExecutionResults_andRedactsToolRequests() {
        IContextManager cm = new IContextManager() {};
        Context ctx = new Context(cm);

        var toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("searchFiles")
                .arguments("{\"pattern\": \"*.java\"}")
                .build();
        var aiWithTools = new AiMessage(List.of(toolRequest));
        var toolResult = new ToolExecutionResultMessage("call-1", "searchFiles", "Found 5 files");
        var finalAi = new AiMessage("Done.");
        var user = new UserMessage("Find Java files");

        var entryMessages = List.<ChatMessage>of(user, aiWithTools, toolResult, finalAi);

        var entryMeta = new TaskResult.TaskMeta(TaskResult.Type.CODE, new AbstractService.ModelConfig("model-A"));
        var entry = new TaskEntry(
                0,
                new ContextFragments.TaskFragment(cm, entryMessages, "test task"),
                new ContextFragments.TaskFragment(cm, entryMessages, "test task"),
                null,
                entryMeta);

        ctx = ctx.withHistory(List.of(entry));

        var currentMeta = new TaskResult.TaskMeta(TaskResult.Type.CODE, new AbstractService.ModelConfig("model-B"));
        var history = WorkspacePrompts.getHistoryMessages(ctx, currentMeta);

        assertFalse(history.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage));

        assertTrue(history.stream()
                .filter(m -> m instanceof UserMessage)
                .map(Messages::getText)
                .anyMatch("Find Java files"::equals));

        assertFalse(history.stream().anyMatch(m -> m instanceof AiMessage ai && ai.hasToolExecutionRequests()));

        var aiTexts = history.stream()
                .filter(m -> m instanceof AiMessage)
                .map(m -> ((AiMessage) m).text())
                .filter(Objects::nonNull)
                .toList();

        assertTrue(aiTexts.stream().anyMatch(t -> t.contains("[Historical tool usage by a different model]")));
        assertTrue(aiTexts.stream().anyMatch(t -> t.contains("searchFiles")));
    }
}
