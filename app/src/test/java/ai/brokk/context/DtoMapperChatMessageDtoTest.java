//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.brokk.util.HistoryIo;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.*;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DtoMapperChatMessageDtoTest {
    static Stream<Arguments> aiRoundTripCases() {
        return Stream.of(
                Arguments.of(new Object[] {"This is the final answer", "Let me think through this step by step..."}),
                Arguments.of(new Object[] {"This is the final answer", null}),
                Arguments.of(new Object[] {"", "This is the reasoning content"}),
                Arguments.of(new Object[] {"", "   "}));
    }

    static Stream<Arguments> nonAiCases() {
        return Stream.of(
                Arguments.of(new Object[] {new UserMessage("What is 2+2?"), "user"}),
                Arguments.of(new Object[] {new SystemMessage("You are a helpful assistant"), "system"}));
    }

    @ParameterizedTest
    @MethodSource({"aiRoundTripCases"})
    void testAiMessage_ToDto_And_FromDto_RoundTrip(String text, String reasoning) {
        AiMessage original = reasoning != null ? new AiMessage(text, reasoning) : new AiMessage(text);
        HistoryIo.ContentWriter writer = new HistoryIo.ContentWriter();
        FragmentDtos.ChatMessageDto dto = DtoMapper.toChatMessageDto(original, writer);
        Assertions.assertEquals("ai", dto.role());
        Assertions.assertNotNull(dto.contentId(), "contentId should not be null");
        boolean hasStructuredReasoning = reasoning != null && !reasoning.isBlank();
        if (hasStructuredReasoning) {
            Assertions.assertNotNull(
                    dto.reasoningContentId(), "reasoningContentId should be set for non-blank reasoning");
        } else {
            Assertions.assertNull(
                    dto.reasoningContentId(), "reasoningContentId should be null when reasoning is null/blank");
        }

        HistoryIo.ContentReader reader = this.createReaderFromWriter(writer);
        ChatMessage reconstructed = DtoMapper.fromChatMessageDto(dto, reader);
        Assertions.assertInstanceOf(AiMessage.class, reconstructed);
        AiMessage ai = (AiMessage) reconstructed;
        Assertions.assertEquals(text, ai.text());
        String expectedReasoning = hasStructuredReasoning ? reasoning : null;
        Assertions.assertEquals(expectedReasoning, ai.reasoningContent());
    }

    @ParameterizedTest
    @MethodSource({"nonAiCases"})
    void testNonAiMessages_ToDto_And_FromDto(ChatMessage message, String expectedRole) {
        HistoryIo.ContentWriter writer = new HistoryIo.ContentWriter();
        FragmentDtos.ChatMessageDto dto = DtoMapper.toChatMessageDto(message, writer);
        Assertions.assertNotNull(dto);
        Assertions.assertEquals(expectedRole, dto.role());
        Assertions.assertNotNull(dto.contentId());
        Assertions.assertNull(dto.reasoningContentId(), "reasoningContentId should be null for non-AI messages");
        assertNull(dto.attributes(), "attributes should be null for non-custom messages");
        HistoryIo.ContentReader reader = this.createReaderFromWriter(writer);
        ChatMessage reconstructed = DtoMapper.fromChatMessageDto(dto, reader);
        Assertions.assertEquals(message.type(), reconstructed.type());
        Assertions.assertEquals(Messages.getRepr(message), Messages.getRepr(reconstructed));
    }

    @Test
    void testCustomMessage_ToDto_And_FromDto_RoundTrip_PreservesAttributes() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("customFlag", true); // Boolean, not String
        attrs.put("text", "Hello from custom"); // Use "text" key - this is extracted to contentId
        CustomMessage original = new CustomMessage(attrs);

        HistoryIo.ContentWriter writer = new HistoryIo.ContentWriter();
        FragmentDtos.ChatMessageDto dto = DtoMapper.toChatMessageDto(original, writer);

        assertEquals("custom", dto.role());
        assertNotNull(dto.contentId());
        assertNull(dto.reasoningContentId());
        // "text" should NOT be in attributes - it's stored via contentId
        assertEquals(Map.of("customFlag", true), dto.attributes());

        HistoryIo.ContentReader reader = createReaderFromWriter(writer);
        ChatMessage reconstructed = DtoMapper.fromChatMessageDto(dto, reader);

        assertInstanceOf(CustomMessage.class, reconstructed);
        CustomMessage custom = (CustomMessage) reconstructed;

        // Verify content was stored correctly
        assertEquals("Hello from custom", reader.readContent(dto.contentId()));

        // Verify "text" is restored into the reconstructed CustomMessage
        assertEquals("Hello from custom", custom.attributes().get("text"));
        assertEquals(true, custom.attributes().get("customFlag"));
    }

    private HistoryIo.ContentReader createReaderFromWriter(HistoryIo.ContentWriter writer) {
        Map<String, byte[]> copy = new HashMap();
        writer.getContentBytes().forEach(copy::put);
        HistoryIo.ContentReader reader = new HistoryIo.ContentReader(copy);
        reader.setContentMetadata(writer.getContentMetadata());
        return reader;
    }
}
