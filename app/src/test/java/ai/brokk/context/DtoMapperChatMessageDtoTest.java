package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.FragmentDtos.ChatMessageDto;
import ai.brokk.util.HistoryIo.ContentReader;
import ai.brokk.util.HistoryIo.ContentWriter;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Consolidated parameterized tests for DtoMapper <-> ChatMessageDto both directions,
 * including structured and legacy formats, to reduce duplication.
 */
class DtoMapperChatMessageDtoTest {

    // ===== Parameter sources =====

    static Stream<Arguments> aiRoundTripCases() {
        return Stream.of(
                Arguments.of("This is the final answer", "Let me think through this step by step..."),
                Arguments.of("This is the final answer", null),
                Arguments.of("", "This is the reasoning content"),
                Arguments.of("", "   ") // blank reasoning -> treated as null
                );
    }

    static Stream<Arguments> nonAiCases() {
        return Stream.of(
                Arguments.of(new UserMessage("What is 2+2?"), "user"),
                Arguments.of(new SystemMessage("You are a helpful assistant"), "system"));
    }

    static Stream<Arguments> legacyCases() {
        return Stream.of(
                Arguments.of(
                        "Reasoning:\nLet me think step by step\nText:\nThe final answer",
                        "The final answer",
                        "Let me think step by step"),
                Arguments.of(
                        "Reasoning:\nLet me think through this carefully", null, "Let me think through this carefully"),
                Arguments.of("The final answer without any reasoning", "The final answer without any reasoning", null),
                Arguments.of(
                        "Reasoning:\n  Some reasoning with spaces  \nText:\n  Some text with spaces  ",
                        "Some text with spaces",
                        "Some reasoning with spaces"),
                Arguments.of("Reasoning:\n\nText:\n\n", null, null));
    }

    // ===== Tests =====

    @ParameterizedTest
    @MethodSource("aiRoundTripCases")
    void testAiMessage_ToDto_And_FromDto_RoundTrip(String text, String reasoning) {
        // Build original message
        AiMessage original = reasoning != null ? new AiMessage(text, reasoning) : new AiMessage(text);

        // Serialize to DTO
        ContentWriter writer = new ContentWriter();
        ChatMessageDto dto = DtoMapper.toChatMessageDto(original, writer);

        assertEquals("ai", dto.role());
        assertNotNull(dto.contentId(), "contentId should not be null");

        // reasoningContentId is only set when non-blank reasoning is present
        boolean hasStructuredReasoning = reasoning != null && !reasoning.isBlank();
        if (hasStructuredReasoning) {
            assertNotNull(dto.reasoningContentId(), "reasoningContentId should be set for non-blank reasoning");
        } else {
            assertNull(dto.reasoningContentId(), "reasoningContentId should be null when reasoning is null/blank");
        }

        // Round-trip
        ContentReader reader = createReaderFromWriter(writer);
        ChatMessage reconstructed = DtoMapper.fromChatMessageDto(dto, reader);
        assertInstanceOf(AiMessage.class, reconstructed);

        AiMessage ai = (AiMessage) reconstructed;
        assertEquals(text, ai.text());

        String expectedReasoning = hasStructuredReasoning ? reasoning : null;
        assertEquals(expectedReasoning, ai.reasoningContent());
    }

    @ParameterizedTest
    @MethodSource("nonAiCases")
    void testNonAiMessages_ToDto_And_FromDto(ChatMessage message, String expectedRole) {
        ContentWriter writer = new ContentWriter();
        ChatMessageDto dto = DtoMapper.toChatMessageDto(message, writer);

        assertNotNull(dto);
        assertEquals(expectedRole, dto.role());
        assertNotNull(dto.contentId());
        assertNull(dto.reasoningContentId(), "reasoningContentId should be null for non-AI messages");

        // Reconstruct
        ContentReader reader = createReaderFromWriter(writer);
        ChatMessage reconstructed = DtoMapper.fromChatMessageDto(dto, reader);

        assertEquals(message.type(), reconstructed.type());
        assertEquals(Messages.getRepr(message), Messages.getRepr(reconstructed));
    }

    @ParameterizedTest
    @MethodSource("legacyCases")
    void testLegacyAiReprParsing_FromDtoOnly(String legacyRepr, String expectedText, String expectedReasoning) {
        // Prepare legacy DTO without reasoningContentId
        ContentWriter writer = new ContentWriter();
        String contentId = writer.writeContent(legacyRepr, null);
        ChatMessageDto legacyDto = new ChatMessageDto("ai", contentId);

        ContentReader reader = createReaderFromWriter(writer);
        ChatMessage reconstructed = DtoMapper.fromChatMessageDto(legacyDto, reader);

        assertInstanceOf(AiMessage.class, reconstructed);
        AiMessage ai = (AiMessage) reconstructed;

        if (expectedText == null) {
            assertNull(ai.text(), "Expected null text");
        } else {
            assertEquals(expectedText, ai.text());
        }

        if (expectedReasoning == null) {
            assertNull(ai.reasoningContent(), "Expected null reasoning");
        } else {
            assertEquals(expectedReasoning, ai.reasoningContent());
        }
    }

    // ===== Helper Methods =====

    private ContentReader createReaderFromWriter(ContentWriter writer) {
        Map<String, byte[]> copy = new HashMap<>();
        writer.getContentBytes().forEach(copy::put);
        ContentReader reader = new ContentReader(copy);
        reader.setContentMetadata(writer.getContentMetadata());
        return reader;
    }
}
