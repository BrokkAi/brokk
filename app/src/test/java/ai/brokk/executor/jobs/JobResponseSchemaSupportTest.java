package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class JobResponseSchemaSupportTest {
    @Test
    void coerceOutputConvertsArrayToStringForStringField() throws Exception {
        var schema = schema(
                """
                {
                  "type": "object",
                  "properties": {
                    "findings": { "type": "string" }
                  },
                  "required": ["findings"],
                  "additionalProperties": false
                }
                """);

        var coerced = JobResponseSchemaSupport.coerceOutput(schema, "{\"findings\":[\"one\",\"two\"]}")
                .orElseThrow();

        assertEquals(
                "one\ntwo",
                Json.getMapper().readTree(coerced.json()).get("findings").textValue());
        assertEquals("response.findings array -> string", coerced.changes().getFirst());
        assertTrue(
                JobResponseSchemaSupport.validateOutput(schema, coerced.json()).isEmpty());
    }

    @Test
    void coerceOutputLeavesStringArraysAsArrays() throws Exception {
        var schema = schema(
                """
                {
                  "type": "object",
                  "properties": {
                    "observations": {
                      "type": "array",
                      "items": { "type": "string" }
                    }
                  },
                  "required": ["observations"],
                  "additionalProperties": false
                }
                """);

        var coerced = JobResponseSchemaSupport.coerceOutput(schema, "{\"observations\":[\"one\",\"two\"]}");

        assertTrue(coerced.isEmpty());
        assertTrue(JobResponseSchemaSupport.validateOutput(schema, "{\"observations\":[\"one\",\"two\"]}")
                .isEmpty());
    }

    @Test
    void coerceOutputConvertsScalarValuesToStringForStringField() throws Exception {
        var schema = schema(
                """
                {
                  "type": "object",
                  "properties": {
                    "count": { "type": "string" },
                    "enabled": { "type": "string" }
                  },
                  "required": ["count", "enabled"],
                  "additionalProperties": false
                }
                """);

        var coerced = JobResponseSchemaSupport.coerceOutput(schema, "{\"count\":7,\"enabled\":true}")
                .orElseThrow();
        var output = Json.getMapper().readTree(coerced.json());

        assertEquals("7", output.get("count").textValue());
        assertEquals("true", output.get("enabled").textValue());
        assertTrue(
                JobResponseSchemaSupport.validateOutput(schema, coerced.json()).isEmpty());
    }

    @Test
    void coerceOutputDoesNotCoerceEnumFields() throws Exception {
        var schema = schema(
                """
                {
                  "type": "object",
                  "properties": {
                    "confidence": { "type": "string", "enum": ["low", "medium", "high"] }
                  },
                  "required": ["confidence"],
                  "additionalProperties": false
                }
                """);

        assertTrue(JobResponseSchemaSupport.coerceOutput(schema, "{\"confidence\":1}")
                .isEmpty());
        assertTrue(JobResponseSchemaSupport.coerceOutput(schema, "{\"confidence\":[\"high\"]}")
                .isEmpty());
        assertEquals(
                "response.confidence expected string, got integer",
                JobResponseSchemaSupport.validateOutput(schema, "{\"confidence\":1}")
                        .orElseThrow());
        assertEquals(
                "response.confidence expected string, got array",
                JobResponseSchemaSupport.validateOutput(schema, "{\"confidence\":[\"high\"]}")
                        .orElseThrow());
        assertEquals(
                "response.confidence has value outside enum",
                JobResponseSchemaSupport.validateOutput(schema, "{\"confidence\":\"certain\"}")
                        .orElseThrow());
    }

    @Test
    void coerceOutputDoesNotCoerceObjectOrNullToString() throws Exception {
        var schema = schema(
                """
                {
                  "type": "object",
                  "properties": {
                    "findings": { "type": "string" }
                  },
                  "required": ["findings"],
                  "additionalProperties": false
                }
                """);

        assertTrue(JobResponseSchemaSupport.coerceOutput(schema, "{\"findings\":{\"text\":\"one\"}}")
                .isEmpty());
        assertTrue(JobResponseSchemaSupport.coerceOutput(schema, "{\"findings\":null}")
                .isEmpty());
        assertTrue(JobResponseSchemaSupport.coerceOutput(schema, "{\"findings\":[{\"text\":\"one\"}]}")
                .isEmpty());
        assertTrue(JobResponseSchemaSupport.coerceOutput(schema, "{\"findings\":[null]}")
                .isEmpty());
        assertEquals(
                "response.findings expected string, got object",
                JobResponseSchemaSupport.validateOutput(schema, "{\"findings\":{\"text\":\"one\"}}")
                        .orElseThrow());
        assertEquals(
                "response.findings is required",
                JobResponseSchemaSupport.validateOutput(schema, "{\"findings\":null}")
                        .orElseThrow());
        assertEquals(
                "response.findings expected string, got array",
                JobResponseSchemaSupport.validateOutput(schema, "{\"findings\":[{\"text\":\"one\"}]}")
                        .orElseThrow());
        assertEquals(
                "response.findings expected string, got array",
                JobResponseSchemaSupport.validateOutput(schema, "{\"findings\":[null]}")
                        .orElseThrow());
    }

    private static JobSpec.ResponseSchema schema(String json) throws Exception {
        JsonNode root = Json.getMapper().readTree(json);
        return new JobSpec.ResponseSchema("TestSchema", root);
    }
}
