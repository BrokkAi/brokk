package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecialTextTypeTest {

    @Test
    void testDiscardedContextRoundTrip() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("file1.java", "Reason 1");
        original.put("file2.java", "Reason 2");

        String json = SpecialTextType.serializeDiscardedContext(original);
        Map<String, String> deserialized = SpecialTextType.deserializeDiscardedContext(json);

        assertEquals(original, deserialized);
        assertTrue(deserialized instanceof LinkedHashMap, "Should preserve insertion order");
    }

    @Test
    void testDeserializeMalformedJsonReturnsEmptyMap() {
        Map<String, String> result = SpecialTextType.deserializeDiscardedContext("{ illegal json }");
        assertTrue(result.isEmpty());
    }
}
