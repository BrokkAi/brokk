package ai.brokk.gui.mop.webview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.gui.mop.ChunkMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessageType;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

public class BrokkEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testHistoryTaskSerialization() throws Exception {
        var message = new BrokkEvent.HistoryTask.Message("Hello", ChatMessageType.USER, false);
        var event = new BrokkEvent.HistoryTask(123, 456, false, null, List.of(message));

        String json = MAPPER.writeValueAsString(event);

        assertTrue(json.contains("\"type\":\"history-task\""));
        assertTrue(json.contains("\"epoch\":123"));
        assertTrue(json.contains("\"taskSequence\":456"));
        assertTrue(json.contains("\"compressed\":false"));
        assertTrue(json.contains("\"messages\":[{\"text\":\"Hello\",\"msgType\":\"USER\",\"reasoning\":false}]"));
    }

    @Test
    public void testHistoryTaskSerializationCompressed() throws Exception {
        var event = new BrokkEvent.HistoryTask(123, 456, true, "summary text", null);

        String json = MAPPER.writeValueAsString(event);

        assertTrue(json.contains("\"type\":\"history-task\""));
        assertTrue(json.contains("\"epoch\":123"));
        assertTrue(json.contains("\"taskSequence\":456"));
        assertTrue(json.contains("\"compressed\":true"));
        assertTrue(json.contains("\"summary\":\"summary text\""));
    }

    @Test
    public void testHistoryTaskSerializationWithBothSummaryAndMessages() throws Exception {
        var message = new BrokkEvent.HistoryTask.Message("Full message content", ChatMessageType.AI, false);
        var event = new BrokkEvent.HistoryTask(123, 456, true, "AI summary", List.of(message));

        String json = MAPPER.writeValueAsString(event);

        assertTrue(json.contains("\"type\":\"history-task\""));
        assertTrue(json.contains("\"epoch\":123"));
        assertTrue(json.contains("\"taskSequence\":456"));
        assertTrue(json.contains("\"compressed\":true"));
        assertTrue(json.contains("\"summary\":\"AI summary\""));
        assertTrue(json.contains(
                "\"messages\":[{\"text\":\"Full message content\",\"msgType\":\"AI\",\"reasoning\":false}]"));
    }

    @Test
    public void testHistoryTaskSerializationCompressedFlagWithSummary() throws Exception {
        // When summary is present, compressed should be true
        var event = new BrokkEvent.HistoryTask(
                123,
                456,
                true,
                "summary text",
                List.of(new BrokkEvent.HistoryTask.Message("msg", ChatMessageType.USER, false)));

        String json = MAPPER.writeValueAsString(event);

        assertTrue(json.contains("\"compressed\":true"));
        assertTrue(json.contains("\"summary\":\"summary text\""));
        assertTrue(json.contains("\"messages\":["));
    }

    @Test
    public void testHistoryTaskSerializationCompressedFlagWithoutSummary() throws Exception {
        // When only messages are present, compressed should be false
        var event = new BrokkEvent.HistoryTask(
                123, 456, false, null, List.of(new BrokkEvent.HistoryTask.Message("msg", ChatMessageType.USER, false)));

        String json = MAPPER.writeValueAsString(event);

        assertTrue(json.contains("\"compressed\":false"));
        assertTrue(!json.contains("\"summary\"")); // summary should not be in JSON when null
        assertTrue(json.contains("\"messages\":["));
    }

    @Test
    public void testChunkSerializationWithTerminal() throws Exception {
        var event = new BrokkEvent.Chunk(
                "terminal output",
                ChatMessageType.AI,
                100,
                false,
                new ChunkMeta(true, false, true));

        var node = MAPPER.readTree(MAPPER.writeValueAsString(event));

        assertEquals("chunk", node.get("type").asText());
        assertEquals("terminal output", node.get("text").asText());
        assertEquals("AI", node.get("msgType").asText());
        assertEquals(100, node.get("epoch").asInt());
        assertFalse(node.get("streaming").asBoolean());

        assertFalse(node.has("isNew"));
        assertFalse(node.has("reasoning"));
        assertFalse(node.has("terminal"));

        assertTrue(node.has("meta"));
        var meta = node.get("meta");

        var metaKeys = StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(meta.fieldNames(), 0), false)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("isNewMessage", "isReasoning", "isTerminal"), metaKeys);

        assertTrue(meta.get("isNewMessage").asBoolean());
        assertFalse(meta.get("isReasoning").asBoolean());
        assertTrue(meta.get("isTerminal").asBoolean());
    }
}
