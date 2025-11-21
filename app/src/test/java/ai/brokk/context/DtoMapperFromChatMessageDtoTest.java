package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.FragmentDtos.ChatMessageDto;
import ai.brokk.util.HistoryIo.ContentReader;
import ai.brokk.util.HistoryIo.ContentWriter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DtoMapper.fromChatMessageDto, verifying that reasoning content
 * is correctly reconstructed from ChatMessageDto and that legacy format fallback parsing works.
 */
class DtoMapperFromChatMessageDtoTest {

    @Test
    void testRoundTripAiMessageWithTextAndReasoning() {
        // Serialize an AiMessage with both text and reasoning
        String text = "This is the final answer";
        String reasoning = "Let me think through this step by step...";
        AiMessage original = new AiMessage(text, reasoning);

        ContentWriter writer = new ContentWriter();
        ChatMessageDto dto = DtoMapper.toChatMessageDto(original, writer);

        // Verify the DTO has both content IDs
        assertNotNull(dto.contentId(), "contentId should not be null");
        assertNotNull(dto.reasoningContentId(), "reasoningContentId should not be null");

        // Reconstruct the message from the DTO
        ContentReader reader = createReaderFromWriter(writer);
        dev.langchain4j.data.message.ChatMessage reconstructed = fromChatMessageDtoPublic(dto, reader);

        // Verify round-trip
        assertInstanceOf(AiMessage.class, reconstructed);
        AiMessage reconstructedAi = (AiMessage) reconstructed;
        assertEquals(text, reconstructedAi.text(), "Text should match after round-trip");
        assertEquals(reasoning, reconstructedAi.reasoningContent(), "Reasoning should match after round-trip");
    }

    @Test
    void testRoundTripAiMessageWithTextOnly() {
        String text = "This is the final answer";
        AiMessage original = new AiMessage(text);

        ContentWriter writer = new ContentWriter();
        ChatMessageDto dto = DtoMapper.toChatMessageDto(original, writer);

        assertNotNull(dto.contentId(), "contentId should not be null");
        assertNull(dto.reasoningContentId(), "reasoningContentId should be null for text-only message");

        ContentReader reader = createReaderFromWriter(writer);
        dev.langchain4j.data.message.ChatMessage reconstructed = fromChatMessageDtoPublic(dto, reader);

        assertInstanceOf(AiMessage.class, reconstructed);
        AiMessage reconstructedAi = (AiMessage) reconstructed;
        assertEquals(text, reconstructedAi.text(), "Text should match after round-trip");
    }

    @Test
    void testLegacyAiReprWithBothReasoningAndText() {
        // Simulate a legacy format with both reasoning and text
        String legacyRepr = "Reasoning:\nLet me think step by step\nText:\nThe final answer";

        ContentWriter writer = new ContentWriter();
        String contentId = writer.writeContent(legacyRepr, null);

        // Create a legacy ChatMessageDto without reasoningContentId
        ChatMessageDto dto = new ChatMessageDto("ai", contentId);

        ContentReader reader = createReaderFromWriter(writer);
        dev.langchain4j.data.message.ChatMessage reconstructed = fromChatMessageDtoPublic(dto, reader);

        assertInstanceOf(AiMessage.class, reconstructed);
        AiMessage reconstructedAi = (AiMessage) reconstructed;
        assertEquals("The final answer", reconstructedAi.text(), "Text should be extracted from legacy repr");
        assertEquals(
                "Let me think step by step",
                reconstructedAi.reasoningContent(),
                "Reasoning should be extracted from legacy repr");
    }

    @Test
    void testLegacyAiReprWithReasoningOnly() {
        // Simulate a legacy format with only reasoning
        String legacyRepr = "Reasoning:\nLet me think through this carefully";

        ContentWriter writer = new ContentWriter();
        String contentId = writer.writeContent(legacyRepr, null);

        ChatMessageDto dto = new ChatMessageDto("ai", contentId);

        ContentReader reader = createReaderFromWriter(writer);
        dev.langchain4j.data.message.ChatMessage reconstructed = fromChatMessageDtoPublic(dto, reader);

        assertInstanceOf(AiMessage.class, reconstructed);
        AiMessage reconstructedAi = (AiMessage) reconstructed;
        assertNull(reconstructedAi.text(), "Text should be null when not present in legacy repr");
        assertEquals(
                "Let me think through this carefully",
                reconstructedAi.reasoningContent(),
                "Reasoning should be extracted from legacy repr");
    }

    @Test
    void testLegacyAiReprWithTextOnly() {
        // Simulate a legacy format with only text (no Reasoning: marker)
        String legacyRepr = "The final answer without any reasoning";

        ContentWriter writer = new ContentWriter();
        String contentId = writer.writeContent(legacyRepr, null);

        ChatMessageDto dto = new ChatMessageDto("ai", contentId);

        ContentReader reader = createReaderFromWriter(writer);
        dev.langchain4j.data.message.ChatMessage reconstructed = fromChatMessageDtoPublic(dto, reader);

        assertInstanceOf(AiMessage.class, reconstructed);
        AiMessage reconstructedAi = (AiMessage) reconstructed;
        assertEquals("The final answer without any reasoning", reconstructedAi.text(), "Text should be the entire repr");
        assertNull(reconstructedAi.reasoningContent(), "Reasoning should be null");
    }

    @Test
    void testLegacyAiReprMalformedFallback() {
        // Test malformed repr that doesn't match expected format
        String malformedRepr = "Some random text\nwith multiple\nlines but no markers";

        ContentWriter writer = new ContentWriter();
        String contentId = writer.writeContent(malformedRepr, null);

        ChatMessageDto dto = new ChatMessageDto("ai", contentId);

        ContentReader reader = createReaderFromWriter(writer);
        dev.langchain4j.data.message.ChatMessage reconstructed = fromChatMessageDtoPublic(dto, reader);

        assertInstanceOf(AiMessage.class, reconstructed);
        AiMessage reconstructedAi = (AiMessage) reconstructed;
        assertEquals(malformedRepr, reconstructedAi.text(), "Malformed repr should be treated as plain text");
        assertNull(reconstructedAi.reasoningContent(), "Reasoning should be null for malformed repr");
    }

    @Test
    void testUserMessageReconstruction() {
        String userText = "What is 2+2?";

        ContentWriter writer = new ContentWriter();
        UserMessage original = new UserMessage(userText);
        ChatMessageDto dto = DtoMapper.toChatMessageDto(original, writer);

        ContentReader reader = createReaderFromWriter(writer);
        dev.langchain4j.data.message.ChatMessage reconstructed = fromChatMessageDtoPublic(dto, reader);

        assertInstanceOf(UserMessage.class, reconstructed);
        UserMessage reconstructedUser = (UserMessage) reconstructed;
        var firstContent = reconstructedUser.contents().get(0);
        assertInstanceOf(dev.langchain4j.data.message.TextContent.class, firstContent);
        var textContent = (dev.langchain4j.data.message.TextContent) firstContent;
        assertEquals(userText, textContent.text(), "User message text should match");
    }

    @Test
    void testSystemMessageReconstruction() {
        String systemText = "You are a helpful assistant";

        ContentWriter writer = new ContentWriter();
        SystemMessage original = new SystemMessage(systemText);
        ChatMessageDto dto = DtoMapper.toChatMessageDto(original, writer);

        ContentReader reader = createReaderFromWriter(writer);
        dev.langchain4j.data.message.ChatMessage reconstructed = fromChatMessageDtoPublic(dto, reader);

        assertInstanceOf(SystemMessage.class, reconstructed);
        SystemMessage reconstructedSystem = (SystemMessage) reconstructed;
        assertEquals(systemText, reconstructedSystem.text(), "System message text should match");
    }

    @Test
    void testLegacyReprWithWhitespace() {
        // Test that extra whitespace is properly trimmed
        String legacyRepr = "Reasoning:\n  Some reasoning with spaces  \nText:\n  Some text with spaces  ";

        ContentWriter writer = new ContentWriter();
        String contentId = writer.writeContent(legacyRepr, null);

        ChatMessageDto dto = new ChatMessageDto("ai", contentId);

        ContentReader reader = createReaderFromWriter(writer);
        dev.langchain4j.data.message.ChatMessage reconstructed = fromChatMessageDtoPublic(dto, reader);

        assertInstanceOf(AiMessage.class, reconstructed);
        AiMessage reconstructedAi = (AiMessage) reconstructed;
        assertEquals("Some text with spaces", reconstructedAi.text(), "Text should be trimmed");
        assertEquals(
                "Some reasoning with spaces",
                reconstructedAi.reasoningContent(),
                "Reasoning should be trimmed");
    }

    @Test
    void testEmptyReasoningAndTextInLegacyRepr() {
        // Test legacy repr with empty sections after markers
        String legacyRepr = "Reasoning:\n\nText:\n\n";

        ContentWriter writer = new ContentWriter();
        String contentId = writer.writeContent(legacyRepr, null);

        ChatMessageDto dto = new ChatMessageDto("ai", contentId);

        ContentReader reader = createReaderFromWriter(writer);
        dev.langchain4j.data.message.ChatMessage reconstructed = fromChatMessageDtoPublic(dto, reader);

        assertInstanceOf(AiMessage.class, reconstructed);
        AiMessage reconstructedAi = (AiMessage) reconstructed;
        assertNull(reconstructedAi.text(), "Empty text should be null");
        assertNull(reconstructedAi.reasoningContent(), "Empty reasoning should be null");
    }

    // ===== Helper Methods =====

    /**
     * Create a ContentReader from a ContentWriter's data for testing.
     */
    private ContentReader createReaderFromWriter(ContentWriter writer) {
        Map<String, byte[]> contentBytes = new HashMap<>();
        for (var entry : writer.getContentBytes().entrySet()) {
            contentBytes.put(entry.getKey(), entry.getValue());
        }
        ContentReader reader = new ContentReader(contentBytes);
        reader.setContentMetadata(writer.getContentMetadata());
        return reader;
    }

    /**
     * Public wrapper for DtoMapper.fromChatMessageDto to allow testing.
     * This uses reflection to call the private method.
     */
    private dev.langchain4j.data.message.ChatMessage fromChatMessageDtoPublic(
            ChatMessageDto dto, ContentReader reader) {
        try {
            var method = DtoMapper.class.getDeclaredMethod(
                    "fromChatMessageDto", ChatMessageDto.class, ContentReader.class);
            method.setAccessible(true);
            return (dev.langchain4j.data.message.ChatMessage) method.invoke(null, dto, reader);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call fromChatMessageDto via reflection", e);
        }
    }
}
