package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.FragmentDtos.ChatMessageDto;
import ai.brokk.util.HistoryIo.ContentWriter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DtoMapper.toChatMessageDto, verifying that reasoning content
 * is persisted separately for AiMessage while maintaining backward compatibility.
 */
class DtoMapperToChatMessageDtoTest {

    @Test
    void testAiMessageWithTextAndReasoning() {
        String text = "This is the final answer";
        String reasoning = "Let me think through this step by step...";
        AiMessage aiMessage = new AiMessage(text, reasoning);

        ContentWriter writer = new ContentWriter();
        ChatMessageDto dto = DtoMapper.toChatMessageDto(aiMessage, writer);

        assertNotNull(dto, "ChatMessageDto should not be null");
        assertEquals("ai", dto.role(), "Role should be 'ai'");
        assertNotNull(dto.contentId(), "contentId should not be null");
        assertNotNull(dto.reasoningContentId(), "reasoningContentId should not be null for reasoning message");
        assertNotEquals(dto.contentId(), dto.reasoningContentId(), "contentId and reasoningContentId should differ");
    }

    @Test
    void testAiMessageWithTextOnly() {
        String text = "This is the final answer";
        AiMessage aiMessage = new AiMessage(text);

        ContentWriter writer = new ContentWriter();
        ChatMessageDto dto = DtoMapper.toChatMessageDto(aiMessage, writer);

        assertNotNull(dto, "ChatMessageDto should not be null");
        assertEquals("ai", dto.role(), "Role should be 'ai'");
        assertNotNull(dto.contentId(), "contentId should not be null");
        assertNull(dto.reasoningContentId(), "reasoningContentId should be null for non-reasoning message");
    }

    @Test
    void testAiMessageWithBlankReasoning() {
        String text = "This is the final answer";
        String reasoning = "   ";
        AiMessage aiMessage = new AiMessage(text, reasoning);

        ContentWriter writer = new ContentWriter();
        ChatMessageDto dto = DtoMapper.toChatMessageDto(aiMessage, writer);

        assertNotNull(dto, "ChatMessageDto should not be null");
        assertEquals("ai", dto.role(), "Role should be 'ai'");
        assertNotNull(dto.contentId(), "contentId should not be null");
        assertNull(dto.reasoningContentId(), "reasoningContentId should be null for blank reasoning");
    }

    @Test
    void testUserMessage() {
        UserMessage userMessage = new UserMessage("What is 2+2?");

        ContentWriter writer = new ContentWriter();
        ChatMessageDto dto = DtoMapper.toChatMessageDto(userMessage, writer);

        assertNotNull(dto, "ChatMessageDto should not be null");
        assertEquals("user", dto.role(), "Role should be 'user'");
        assertNotNull(dto.contentId(), "contentId should not be null");
        assertNull(dto.reasoningContentId(), "reasoningContentId should be null for UserMessage");
    }

    @Test
    void testSystemMessage() {
        SystemMessage systemMessage = new SystemMessage("You are a helpful assistant");

        ContentWriter writer = new ContentWriter();
        ChatMessageDto dto = DtoMapper.toChatMessageDto(systemMessage, writer);

        assertNotNull(dto, "ChatMessageDto should not be null");
        assertEquals("system", dto.role(), "Role should be 'system'");
        assertNotNull(dto.contentId(), "contentId should not be null");
        assertNull(dto.reasoningContentId(), "reasoningContentId should be null for SystemMessage");
    }

    @Test
    void testAiMessageWithEmptyReasoningAndText() {
        String text = "";
        String reasoning = "";
        AiMessage aiMessage = new AiMessage(text, reasoning);

        ContentWriter writer = new ContentWriter();
        ChatMessageDto dto = DtoMapper.toChatMessageDto(aiMessage, writer);

        assertNotNull(dto, "ChatMessageDto should not be null");
        assertEquals("ai", dto.role(), "Role should be 'ai'");
        assertNotNull(dto.contentId(), "contentId should not be null (even if empty)");
        assertNull(dto.reasoningContentId(), "reasoningContentId should be null for empty reasoning");
    }
}
