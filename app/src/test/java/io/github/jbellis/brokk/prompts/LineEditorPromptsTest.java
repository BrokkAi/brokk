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
            assertNull(parsed.parseError(), "Example should parse cleanly without errors");
            assertTrue(
                    parsed.parts().stream().anyMatch(p -> p instanceof LineEditorParser.OutputPart.Edit),
                    "Example should contain at least one <brk_edit_file> part");
        }
    }

    @Test
    void format_instructions_cover_key_points() {
        var lep = LineEditorParser.instance;
        var rules = lep.lineEditFormatInstructions();

        assertTrue(rules.contains("<brk_edit_file"),
                "Rules should show the <brk_edit_file> element explicitly");
        assertTrue(rules.toLowerCase().contains("beginline"),
                "Rules should mention beginline attribute");
        assertTrue(rules.toLowerCase().contains("endline"),
                "Rules should mention endline attribute");
        assertTrue(rules.contains("DO NOT wrap edits in Markdown"),
                "Rules should forbid wrapping edits in Markdown");
        assertTrue(rules.contains("Prefer SMALL, PRECISE edits"),
                "Rules should encourage small, precise edits");
    }
}