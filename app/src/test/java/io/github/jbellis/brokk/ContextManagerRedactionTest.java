package io.github.jbellis.brokk;

import dev.langchain4j.data.message.AiMessage;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextManagerRedactionTest {

    private final EditBlockParser parser = EditBlockParser.instance;

    private static final String ELIDED_BLOCK_PLACEHOLDER = "[elided SEARCH/REPLACE block]";

    private String createSingleBlockMessage(String filename, String search, String replace) {
        return """
               ```
               %s
               <<<<<<< SEARCH
               %s
               =======
               %s
               >>>>>>> REPLACE
               ```
               """.formatted(filename, search, replace);
    }

    @Test
    void removesBlockOnlyMessages() {
        String aiText = createSingleBlockMessage("file.txt", "old code", "new code");
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedMessage = CodePrompts.redactAiMessage(originalMessage, parser);

        assertTrue(redactedMessage.isPresent(), "Message with only S/R block should NOT be removed.");
        assertEquals(ELIDED_BLOCK_PLACEHOLDER, redactedMessage.get().text(), "Message content should be the placeholder.");
    }

    @Test
    void insertsPlaceholderIntoMixedMessage() {
        String prefix = "Here is the patch:\n\n";
        String suffix = "\n\nHope that helps!";
        String block = createSingleBlockMessage("foo.txt", "old", "new");
        String aiText = prefix + block + suffix;
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage, parser);

        assertTrue(redactedResult.isPresent(), "Message should be present after redaction.");
        AiMessage redactedMessage = redactedResult.get();
        String expectedText = prefix + ELIDED_BLOCK_PLACEHOLDER + suffix;
        assertEquals(expectedText, redactedMessage.text(), "S/R block should be replaced by placeholder.");
    }

    @Test
    void leavesPlainMessageUntouched() {
        String aiText = "This is a plain message with no S/R blocks.";
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage, parser);

        assertTrue(redactedResult.isPresent(), "Plain message should be present.");
        assertEquals(originalMessage.text(), redactedResult.get().text(), "Plain message text should be unchanged.");
    }
    
    @Test
    void handlesEmptyMessage() {
        String aiText = "";
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage, parser);
        assertTrue(redactedResult.isEmpty(), "Empty message should result in empty optional.");
    }

    @Test
    void handlesBlankMessage() {
        String aiText = "   \n\t   ";
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage, parser);
        assertTrue(redactedResult.isEmpty(), "Blank message should result in empty optional after redaction.");
    }

    @Test
    void handlesMultipleBlocksAndTextSegments() {
        String text1 = "First part of the message.\n";
        String block1 = createSingleBlockMessage("file1.txt", "search1", "replace1");
        String text2 = "\nSome intermediate text.\n";
        String block2 = createSingleBlockMessage("file2.java", "search2", "replace2");
        String text3 = "\nFinal part.";

        String aiText = text1 + block1 + text2 + block2 + text3;
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage, parser);

        assertTrue(redactedResult.isPresent(), "Message should be present after redaction.");
        String expectedText = text1 + ELIDED_BLOCK_PLACEHOLDER + text2 + ELIDED_BLOCK_PLACEHOLDER + text3;
        assertEquals(expectedText, redactedResult.get().text(), "All S/R blocks should be replaced by placeholders.");
    }

    @Test
    void handlesMessageWithOnlyMultipleBlocks() {
        String block1 = createSingleBlockMessage("file1.txt", "s1", "r1");
        String block2 = createSingleBlockMessage("file2.txt", "s2", "r2");
        // Note: EditBlockParser adds newlines between blocks implicitly if they are not there,
        // so the redaction will join placeholders.
        String aiText = block1 + "\n" + block2; // Explicit newline for clarity in test
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage, parser);
        
        assertTrue(redactedResult.isPresent(), "Message composed of only S/R blocks but resulting in non-blank placeholder text should be present.");
        String expectedText = ELIDED_BLOCK_PLACEHOLDER + "\n" + ELIDED_BLOCK_PLACEHOLDER;
        assertEquals(expectedText, redactedResult.get().text());
    }

    @Test
    void handlesMessageEndingWithBlock() {
        String text = "Text before block\n";
        String block = createSingleBlockMessage("file.end", "end_s", "end_r");
        String aiText = text + block;
        AiMessage originalMessage = new AiMessage(aiText);
        
        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage, parser);
        
        assertTrue(redactedResult.isPresent());
        assertEquals(text + ELIDED_BLOCK_PLACEHOLDER, redactedResult.get().text());
    }
    
    @Test
    void handlesMessageStartingWithBlock() {
        String block = createSingleBlockMessage("file.start", "start_s", "start_r");
        String text = "\nText after block";
        String aiText = block + text;
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage, parser);

        assertTrue(redactedResult.isPresent());
        assertEquals(ELIDED_BLOCK_PLACEHOLDER + text, redactedResult.get().text());
    }
}
