package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.FragmentDtos.ChatMessageDto;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
    }
}
