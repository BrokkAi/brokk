package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobEventSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void roundTripWithMapData() throws Exception {
        var original = new JobEvent(1, 1000L, "NOTIFICATION", Map.of("message", "hello", "level", "INFO"));
        var json = MAPPER.writeValueAsString(original);
        var deserialized = MAPPER.readValue(json, JobEvent.class);

        assertEquals(original.seq(), deserialized.seq());
        assertEquals(original.timestamp(), deserialized.timestamp());
        assertEquals(original.type(), deserialized.type());
        assertTrue(deserialized.data() instanceof Map);
        assertEquals("hello", ((Map<?, ?>) deserialized.data()).get("message"));
        assertEquals("INFO", ((Map<?, ?>) deserialized.data()).get("level"));
    }

    @Test
    void roundTripWithStringData() throws Exception {
        var original = new JobEvent(2, 2000L, "NOTIFICATION", "raw string payload");
        var json = MAPPER.writeValueAsString(original);
        var deserialized = MAPPER.readValue(json, JobEvent.class);

        assertEquals(original.seq(), deserialized.seq());
        assertEquals(original.type(), deserialized.type());
        assertEquals("raw string payload", deserialized.data());
    }

    @Test
    void roundTripWithNullData() throws Exception {
        var original = new JobEvent(3, 3000L, "STATE_CHANGE", null);
        var json = MAPPER.writeValueAsString(original);
        var deserialized = MAPPER.readValue(json, JobEvent.class);

        assertEquals(original.seq(), deserialized.seq());
        assertEquals(original.type(), deserialized.type());
        assertNull(deserialized.data());
    }

    @Test
    void deserializeLegacyStringPayload() throws Exception {
        var json =
                """
                {"seq":1,"timestamp":1000,"type":"NOTIFICATION","data":"Brokk Context Engine: analyzing"}""";
        var event = MAPPER.readValue(json, JobEvent.class);

        assertNotNull(event.data());
        assertEquals("Brokk Context Engine: analyzing", event.data());
    }

    @Test
    void deserializeMapPayload() throws Exception {
        var json =
                """
                {"seq":2,"timestamp":2000,"type":"NOTIFICATION","data":{"message":"test","level":"WARNING"}}""";
        var event = MAPPER.readValue(json, JobEvent.class);

        assertNotNull(event.data());
        assertTrue(event.data() instanceof Map);
        assertEquals("test", ((Map<?, ?>) event.data()).get("message"));
        assertEquals("WARNING", ((Map<?, ?>) event.data()).get("level"));
    }
}
