package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.FragmentDtos.ChatMessageDto;
import ai.brokk.context.FragmentDtos.ToolExecutionRequestDto;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ChatMessageDto serialization and deserialization,
 * verifying backward compatibility and proper handling of the reasoningContentId field.
 */
class ChatMessageDtoSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .configure(SerializationFeature.CLOSE_CLOSEABLE, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    void testSerializeAndDeserializeWithReasoningContentId() throws Exception {
        ChatMessageDto original = new ChatMessageDto("ai", "content-123", "reasoning-456");

        String json = objectMapper.writeValueAsString(original);
        ChatMessageDto deserialized = objectMapper.readValue(json, ChatMessageDto.class);

        assertEquals("ai", deserialized.role());
        assertEquals("content-123", deserialized.contentId());
        assertEquals("reasoning-456", deserialized.reasoningContentId());
    }

    @Test
    void testSerializeAndDeserializeWithoutReasoningContentId() throws Exception {
        ChatMessageDto original = new ChatMessageDto("user", "content-789");

        String json = objectMapper.writeValueAsString(original);
        ChatMessageDto deserialized = objectMapper.readValue(json, ChatMessageDto.class);

        assertEquals("user", deserialized.role());
        assertEquals("content-789", deserialized.contentId());
        assertNull(deserialized.reasoningContentId());
    }

    @Test
    void testDeserializeWithoutReasoningContentIdField() throws Exception {
        // Simulate legacy JSON that doesn't have the reasoningContentId field
        String legacyJson = "{\"role\":\"system\",\"contentId\":\"content-old\"}";

        ChatMessageDto deserialized = objectMapper.readValue(legacyJson, ChatMessageDto.class);

        assertEquals("system", deserialized.role());
        assertEquals("content-old", deserialized.contentId());
        assertNull(deserialized.reasoningContentId());
    }

    @Test
    void testSerializeWithNullReasoningContentId() throws Exception {
        ChatMessageDto original = new ChatMessageDto("ai", "content-111", null);

        String json = objectMapper.writeValueAsString(original);
        ChatMessageDto deserialized = objectMapper.readValue(json, ChatMessageDto.class);

        assertEquals("ai", deserialized.role());
        assertEquals("content-111", deserialized.contentId());
        assertNull(deserialized.reasoningContentId());
    }

    @Test
    void testBackwardCompatibleConstructor() {
        // Verify the backward-compatible 2-arg constructor still works
        ChatMessageDto dto = new ChatMessageDto("custom", "content-abc");

        assertEquals("custom", dto.role());
        assertEquals("content-abc", dto.contentId());
        assertNull(dto.reasoningContentId());
        assertNull(dto.toolExecutionRequests());
    }

    @Test
    void testSerializeAndDeserializeWithToolExecutionRequests() throws Exception {
        var toolRequests = List.of(
                new ToolExecutionRequestDto("id-1", "searchSymbols", "{\"query\":\"foo\"}"),
                new ToolExecutionRequestDto(null, "getFileContents", "{\"files\":[\"bar.java\"]}"));
        ChatMessageDto original = new ChatMessageDto("ai", "content-123", "reasoning-456", toolRequests);

        String json = objectMapper.writeValueAsString(original);
        ChatMessageDto deserialized = objectMapper.readValue(json, ChatMessageDto.class);

        assertEquals("ai", deserialized.role());
        assertEquals("content-123", deserialized.contentId());
        assertEquals("reasoning-456", deserialized.reasoningContentId());
        assertNotNull(deserialized.toolExecutionRequests());
        assertEquals(2, deserialized.toolExecutionRequests().size());

        var firstTool = deserialized.toolExecutionRequests().get(0);
        assertEquals("id-1", firstTool.id());
        assertEquals("searchSymbols", firstTool.name());
        assertEquals("{\"query\":\"foo\"}", firstTool.arguments());

        var secondTool = deserialized.toolExecutionRequests().get(1);
        assertNull(secondTool.id());
        assertEquals("getFileContents", secondTool.name());
    }

    @Test
    void testDeserializeWithoutToolExecutionRequestsField() throws Exception {
        // Simulate legacy JSON that doesn't have the toolExecutionRequests field
        String legacyJson = "{\"role\":\"ai\",\"contentId\":\"content-old\",\"reasoningContentId\":\"reason-old\"}";

        ChatMessageDto deserialized = objectMapper.readValue(legacyJson, ChatMessageDto.class);

        assertEquals("ai", deserialized.role());
        assertEquals("content-old", deserialized.contentId());
        assertEquals("reason-old", deserialized.reasoningContentId());
        assertNull(deserialized.toolExecutionRequests());
    }

    @Test
    void testThreeArgConstructorBackwardCompatibility() {
        // Verify the 3-arg constructor still works (no tool requests)
        ChatMessageDto dto = new ChatMessageDto("ai", "content-xyz", "reasoning-xyz");

        assertEquals("ai", dto.role());
        assertEquals("content-xyz", dto.contentId());
        assertEquals("reasoning-xyz", dto.reasoningContentId());
        assertNull(dto.toolExecutionRequests());
    }

    @Test
    void testEmptyToolExecutionRequestsNormalizedToNull() {
        // Empty list should be normalized to null for cleaner JSON
        ChatMessageDto dto = new ChatMessageDto("ai", "content-123", null, List.of());

        assertNull(dto.toolExecutionRequests());
    }
}
