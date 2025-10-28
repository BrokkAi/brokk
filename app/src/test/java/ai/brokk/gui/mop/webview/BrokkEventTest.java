package ai.brokk.gui.mop.webview;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessageType;
import java.util.List;
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
}
