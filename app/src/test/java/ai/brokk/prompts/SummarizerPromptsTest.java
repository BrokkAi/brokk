package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class SummarizerPromptsTest {

    @Test
    void testCollectPrTitleAndDescriptionMessagesWithContext_noContext() {
        String diff = "some diff content";
        List<ChatMessage> messages =
                SummarizerPrompts.instance.collectPrTitleAndDescriptionMessagesWithContext(diff, null);

        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof SystemMessage);
        assertTrue(messages.get(1) instanceof UserMessage);

        String systemText = ((SystemMessage) messages.get(0)).text();
        String userText = ((UserMessage) messages.get(1)).singleText();

        assertFalse(systemText.contains("WHY the changes were made"));
        assertTrue(userText.contains("<diff>\nsome diff content\n</diff>"));
        assertFalse(userText.contains("<context>"));
    }

    @Test
    void testCollectPrTitleAndDescriptionMessagesWithContext_withContext() {
        String diff = "some diff content";
        String context = "intent: fix bug";
        List<ChatMessage> messages =
                SummarizerPrompts.instance.collectPrTitleAndDescriptionMessagesWithContext(diff, context);

        assertEquals(2, messages.size());
        String systemText = ((SystemMessage) messages.get(0)).text();
        String userText = ((UserMessage) messages.get(1)).singleText();

        assertTrue(systemText.contains("Use the session context to understand the intent behind the changes"));
        assertTrue(systemText.contains("Focus on WHY the changes were made, not just WHAT changed"));

        assertTrue(userText.contains("<diff>\nsome diff content\n</diff>"));
        assertTrue(userText.contains("<context>\nintent: fix bug\n</context>"));
    }

    @Test
    void testCollectPrTitleAndDescriptionFromCommitMsgsWithContext_withContext() {
        List<String> commits = List.of("feat: add foo", "fix: bar");
        String context = "intent: cleanup";
        List<ChatMessage> messages =
                SummarizerPrompts.instance.collectPrTitleAndDescriptionFromCommitMsgsWithContext(commits, context);

        assertEquals(2, messages.size());
        String systemText = ((SystemMessage) messages.get(0)).text();
        String userText = ((UserMessage) messages.get(1)).singleText();

        assertTrue(systemText.contains("WHY the changes were made"));
        assertTrue(userText.contains("<commits>\nfeat: add foo\n\nfix: bar\n</commits>"));
        assertTrue(userText.contains("<context>\nintent: cleanup\n</context>"));
    }
}
