package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import io.github.jbellis.brokk.prompts.CodePrompts;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContextManagerRedactionTest {

    private static final String ELIDED_BLOCK_PLACEHOLDER = "[elided Line-Edit tag]";

    private String createSingleLineEditTag() {
        return """
               <brk_edit_file path="foo.txt" type="replace" beginline=2 endline=3>
               new line
               </brk_edit_file>""".stripIndent();
    }

    @Test
    void removesBlockOnlyMessages() {
        String aiText = createSingleLineEditTag();
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedMessage = CodePrompts.redactAiMessage(originalMessage);

        assertTrue(redactedMessage.isPresent(), "Message with only edit block should NOT be removed.");
        assertEquals(
                ELIDED_BLOCK_PLACEHOLDER, redactedMessage.get().text(), "Message content should be the placeholder.");
    }

    @Test
    void insertsPlaceholderIntoMixedMessage() {
        String prefix = "Here is the patch:\n\n";
        String suffix = "\n\nHope that helps!";
        String block = createSingleLineEditTag();
        String aiText = prefix + block + suffix;
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage);

        assertTrue(redactedResult.isPresent(), "Message should be present after redaction.");
        AiMessage redactedMessage = redactedResult.get();
        String expectedText = prefix + ELIDED_BLOCK_PLACEHOLDER + suffix;
        assertEquals(expectedText, redactedMessage.text(), "Edit block should be replaced by placeholder.");
    }

    @Test
    void leavesPlainMessageUntouched() {
        String aiText = "This is a plain message with no edit blocks.";
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
        String block1 = createSingleLineEditTag();
        String text2 = "\nSome intermediate text.\n";
        String block2 = createSingleLineEditTag();
        String text3 = "\nFinal part.";

        String aiText = text1 + block1 + text2 + block2 + text3;
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage);

        assertTrue(redactedResult.isPresent(), "Message should be present after redaction.");
        String expectedText = text1 + ELIDED_BLOCK_PLACEHOLDER + text2 + ELIDED_BLOCK_PLACEHOLDER + text3;
        assertEquals(expectedText, redactedResult.get().text(), "All edit blocks should be replaced by placeholders.");
    }

    @Test
    void handlesMessageWithOnlyMultipleBlocks() {
        String block1 = createSingleLineEditTag();
        String block2 = createSingleLineEditTag();
        // Note: EditBlockParser adds newlines between blocks implicitly if they are not there,
        // so the redaction will join placeholders.
        String aiText = block1 + "\n" + block2; // Explicit newline for clarity in test
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage);

        assertTrue(
                redactedResult.isPresent(),
                "Message composed of only S/R blocks but resulting in non-blank placeholder text should be present.");
        String expectedText = ELIDED_BLOCK_PLACEHOLDER + "\n" + ELIDED_BLOCK_PLACEHOLDER;
        assertEquals(expectedText, redactedResult.get().text());
    }

    @Test
    void handlesMessageEndingWithBlock() {
        String text = "Text before block\n";
        String block = createSingleLineEditTag();
        String aiText = text + block;
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage);

        assertTrue(redactedResult.isPresent());
        assertEquals(text + ELIDED_BLOCK_PLACEHOLDER, redactedResult.get().text());
    }

    @Test
    void handlesMessageStartingWithBlock() {
        String block = createSingleLineEditTag();
        String text = "\nText after block";
        String aiText = block + text;
        AiMessage originalMessage = new AiMessage(aiText);

        Optional<AiMessage> redactedResult = CodePrompts.redactAiMessage(originalMessage);

        assertTrue(redactedResult.isPresent());
        assertEquals(ELIDED_BLOCK_PLACEHOLDER + text, redactedResult.get().text());
    }
}
