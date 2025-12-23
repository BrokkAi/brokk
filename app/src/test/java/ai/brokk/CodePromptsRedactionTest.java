package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.prompts.CodePrompts;
import dev.langchain4j.data.message.AiMessage;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodePromptsRedactionTest {
    private static final String ELIDED_BLOCK_PLACEHOLDER = "[elided SEARCH/REPLACE block]";

    private String createMinimalMessage(String filename, String search, String replace) {
        return """
               %s
               <<<<<<< SEARCH
               %s
               =======
               %s
               >>>>>>> REPLACE
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
        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage);

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

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage);

        assertTrue(redactedResult.isPresent(), "Plain message should be present.");
        assertEquals(originalMessage.text(), redactedResult.get().text(), "Plain message text should be unchanged.");
    }

    @Test
    void handlesEmptyMessage() {
        String aiText = "";
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage);
        assertTrue(redactedResult.isEmpty(), "Empty message should result in empty optional.");
    }

    @Test
    void handlesBlankMessage() {
        String aiText = "   \n\t   ";
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage);
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

        Optional<AiMessage> silentResult = CodePrompts.redactAiMessage(originalMessage, true);

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

        Optional<AiMessage> silentResult = CodePrompts.redactAiMessage(originalMessage, true);

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

        Optional<AiMessage> silentResult = CodePrompts.redactAiMessage(originalMessage, true);

        assertTrue(silentResult.isPresent());
        String silentText = silentResult.get().text();
        assertFalse(silentText.contains(ELIDED_BLOCK_PLACEHOLDER));
        assertTrue(silentText.contains("First part."));
        assertTrue(silentText.contains("Middle part."));
        assertTrue(silentText.contains("Final part."));
    }
}
