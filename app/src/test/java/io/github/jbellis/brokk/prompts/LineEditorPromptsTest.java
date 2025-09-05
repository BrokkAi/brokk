package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LineEditorPromptsTest {

    @Test
    void examples_are_parseable() {
        var lep = LineEditorParser.instance;
        List<ChatMessage> examples = lep.exampleMessages();

        var aiTexts = examples.stream()
                .filter(m -> m instanceof AiMessage)
                .map(m -> ((AiMessage) m).text())
                .toList();

        assertFalse(aiTexts.isEmpty(), "Expected at least one AI example message");

        for (var text : aiTexts) {
            var parsed = lep.parse(text);
            assertNull(parsed.error(), "Example should parse cleanly without errors");
            assertTrue(
                    parsed.edits().stream().anyMatch(e -> e instanceof io.github.jbellis.brokk.LineEdit.EditFile),
                    "Example should contain at least one BRK_EDIT_EX block producing edits");
        }
    }
}
