package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TreeSitterStateIORangeTest {

    // Mirror TreeSitterStateIO.RangeMixIn behavior: ignore unknown properties for Range.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private abstract static class RangeMixIn {}

    @Test
    void testRangeSerializationDocumentsEmptyFieldBehavior() throws Exception {
        // Document current behavior: in this environment, Jackson treats Range.isEmpty() as a boolean property
        // and includes it as "empty" in JSON output. This can vary by Jackson version/configuration.
        ObjectMapper jsonMapper = new ObjectMapper();
        IAnalyzer.Range range = new IAnalyzer.Range(10, 20, 1, 2, 5);

        String json = jsonMapper.writeValueAsString(range);

        assertTrue(
                json.contains("\"empty\""),
                "Documented behavior: serialized Range includes 'empty' (derived from isEmpty()). json=" + json);
    }

    @Test
    void testRangeSmileDeserializationWithExtraFieldFailsWithoutMixIn() throws Exception {
        ObjectMapper smileMapper =
                new ObjectMapper(new SmileFactory()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

        byte[] payloadWithExtra = smileMapper.writeValueAsBytes(rangePayloadWithExtraField("empty", true));

        assertThrows(
                Exception.class,
                () -> smileMapper.readValue(payloadWithExtra, IAnalyzer.Range.class),
                "Without RangeMixIn, FAIL_ON_UNKNOWN_PROPERTIES should reject unknown fields like 'empty'");
    }

    @Test
    void testRangeSmileDeserializationWithExtraFieldSucceedsWithMixIn() throws Exception {
        ObjectMapper smileMapper =
                new ObjectMapper(new SmileFactory()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        smileMapper.addMixIn(IAnalyzer.Range.class, RangeMixIn.class);

        byte[] payloadWithExtra = smileMapper.writeValueAsBytes(rangePayloadWithExtraField("empty", true));

        IAnalyzer.Range range = smileMapper.readValue(payloadWithExtra, IAnalyzer.Range.class);

        assertEquals(10, range.startByte());
        assertEquals(20, range.endByte());
        assertEquals(1, range.startLine());
        assertEquals(2, range.endLine());
        assertEquals(5, range.commentStartByte());
    }

    private static Map<String, Object> rangePayloadWithExtraField(String extraFieldName, Object extraFieldValue) {
        Map<String, Object> data = new HashMap<>();
        data.put("startByte", 10);
        data.put("endByte", 20);
        data.put("startLine", 1);
        data.put("endLine", 2);
        data.put("commentStartByte", 5);
        data.put(extraFieldName, extraFieldValue);
        return data;
    }
}
