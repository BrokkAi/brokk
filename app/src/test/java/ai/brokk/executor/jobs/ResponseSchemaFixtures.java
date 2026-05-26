package ai.brokk.executor.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

public final class ResponseSchemaFixtures {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponseSchemaFixtures() {}

    public static Map<String, Object> validResponseSchemaMap() {
        return Map.of(
                "name",
                "StrictReport",
                "schema",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("summary", Map.of("type", "string")),
                        "required",
                        List.of("summary"),
                        "additionalProperties",
                        false));
    }

    public static JobSpec.ResponseSchema validResponseSchema() {
        try {
            return new JobSpec.ResponseSchema(
                    "StrictReport", MAPPER.valueToTree(validResponseSchemaMap().get("schema")));
        } catch (IllegalArgumentException e) {
            throw new AssertionError(e);
        }
    }
}
