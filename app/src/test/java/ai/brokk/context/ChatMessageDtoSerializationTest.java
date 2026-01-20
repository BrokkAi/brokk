package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.FragmentDtos.ChatMessageDto;
import ai.brokk.context.FragmentDtos.ToolExecutionRequestDto;
import ai.brokk.context.FragmentDtos.ToolExecutionResultDto;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;
import java.util.Map;
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
        ChatMessageDto original = new ChatMessageDto("ai", "content-123", "reasoning-456", null, null, null);

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
        assertNull(deserialized.attributes());
    }

    @Test
    void testSerializeAndDeserializeWithAttributes() throws Exception {
        // Use Boolean value since attributes is now Map<String, Object>
        ChatMessageDto original =
                new ChatMessageDto("custom", "content-123", null, null, null, Map.of("terminal", true));

        String json = objectMapper.writeValueAsString(original);
        ChatMessageDto deserialized = objectMapper.readValue(json, ChatMessageDto.class);

        assertEquals("custom", deserialized.role());
        assertEquals("content-123", deserialized.contentId());
        assertNull(deserialized.reasoningContentId());
        assertEquals(Map.of("terminal", true), deserialized.attributes());
    }

    @Test
    void testDeserializeLegacyJsonWithoutAttributesField() throws Exception {
        String legacyJson = "{\"role\":\"custom\",\"contentId\":\"content-old\",\"reasoningContentId\":null}";

        ChatMessageDto deserialized = objectMapper.readValue(legacyJson, ChatMessageDto.class);

        assertEquals("custom", deserialized.role());
        assertEquals("content-old", deserialized.contentId());
        assertNull(deserialized.reasoningContentId());
        assertNull(deserialized.attributes());
    }

    @Test
    void testSerializeWithNullReasoningContentId() throws Exception {
        ChatMessageDto original = new ChatMessageDto("ai", "content-111");

        String json = objectMapper.writeValueAsString(original);
        ChatMessageDto deserialized = objectMapper.readValue(json, ChatMessageDto.class);

        assertEquals("ai", deserialized.role());
        assertEquals("content-111", deserialized.contentId());
        assertNull(deserialized.reasoningContentId());
    }

    @Test
    void testBackwardCompatibleConstructor() {
        ChatMessageDto dto = new ChatMessageDto("custom", "content-abc");

        assertEquals("custom", dto.role());
        assertEquals("content-abc", dto.contentId());
        assertNull(dto.reasoningContentId());
        assertNull(dto.attributes());
        assertNull(dto.toolExecutionRequests());
    }

    @Test
    void testSerializeAndDeserializeWithToolExecutionRequests() throws Exception {
        var toolRequests = List.of(
                new ToolExecutionRequestDto("id-1", "searchSymbols", "{\"query\":\"foo\"}"),
                new ToolExecutionRequestDto(null, "getFileContents", "{\"files\":[\"bar.java\"]}"));
        ChatMessageDto original = new ChatMessageDto("ai", "content-123", "reasoning-456", toolRequests, null, null);

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
        ChatMessageDto dto = new ChatMessageDto("ai", "content-xyz", "reasoning-xyz", null, null, null);

        assertEquals("ai", dto.role());
        assertEquals("content-xyz", dto.contentId());
        assertEquals("reasoning-xyz", dto.reasoningContentId());
        assertNull(dto.toolExecutionRequests());
    }

    @Test
    void testEmptyToolExecutionRequestsNormalizedToNull() {
        // Empty list should be normalized to null for cleaner JSON
        ChatMessageDto dto = new ChatMessageDto("ai", "content-123", null, List.of(), null, null);

        assertNull(dto.toolExecutionRequests());
    }

    @Test
    void testSerializeAndDeserializeToolExecutionResult() throws Exception {
        var toolResult = new ToolExecutionResultDto("call-123", "searchSymbols", "Found 5 matches");
        ChatMessageDto original =
                new ChatMessageDto("tool_execution_result", "content-abc", null, null, toolResult, null);

        String json = objectMapper.writeValueAsString(original);
        ChatMessageDto deserialized = objectMapper.readValue(json, ChatMessageDto.class);

        assertEquals("tool_execution_result", deserialized.role());
        assertNotNull(deserialized.toolExecutionResult());
        assertEquals("call-123", deserialized.toolExecutionResult().id());
        assertEquals("searchSymbols", deserialized.toolExecutionResult().toolName());
        assertEquals("Found 5 matches", deserialized.toolExecutionResult().text());
    }

    @Test
    void testDeserializeLegacyToolExecutionResultWithoutDto() throws Exception {
        // Legacy JSON without toolExecutionResult field
        String legacyJson = "{\"role\":\"tool_execution_result\",\"contentId\":\"content-old\"}";

        ChatMessageDto deserialized = objectMapper.readValue(legacyJson, ChatMessageDto.class);

        assertEquals("tool_execution_result", deserialized.role());
        assertNull(deserialized.toolExecutionResult());
    }
}
