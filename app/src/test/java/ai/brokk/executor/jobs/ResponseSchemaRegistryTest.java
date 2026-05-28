package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResponseSchemaRegistryTest {
    @Test
    void resolvesSchemasByStrippedName() {
        var schema = new JobSpec.ResponseSchema(
                " StrictReport ", ResponseSchemaFixtures.validResponseSchema().schema());

        var registry = ResponseSchemaRegistry.of(List.of(schema));

        assertTrue(registry.resolve("StrictReport").isPresent());
        assertTrue(registry.resolve(" StrictReport ").isPresent());
        assertEquals(List.of("StrictReport"), List.copyOf(registry.names()));
    }

    @Test
    void rejectsBlankNames() {
        var schema = new JobSpec.ResponseSchema(
                " ", ResponseSchemaFixtures.validResponseSchema().schema());

        var thrown = assertThrows(IllegalArgumentException.class, () -> ResponseSchemaRegistry.of(List.of(schema)));

        assertEquals("responseSchema.name is required", thrown.getMessage());
    }

    @Test
    void rejectsDuplicateNamesAfterStripping() {
        var schema = ResponseSchemaFixtures.validResponseSchema();
        var duplicate = new JobSpec.ResponseSchema(" StrictReport ", schema.schema());

        var thrown = assertThrows(
                IllegalArgumentException.class, () -> ResponseSchemaRegistry.of(List.of(schema, duplicate)));

        assertEquals("Duplicate response schema name: StrictReport", thrown.getMessage());
    }

    @Test
    void rejectsInvalidSchemas() {
        var schema = new JobSpec.ResponseSchema(
                "Broken", ResponseSchemaFixtures.validResponseSchema().schema().get("properties"));

        var thrown = assertThrows(IllegalArgumentException.class, () -> ResponseSchemaRegistry.of(List.of(schema)));

        assertTrue(thrown.getMessage().contains("schema type is required"));
    }

    @Test
    void emptyRegistryHasNoNames() {
        var registry = ResponseSchemaRegistry.empty();

        assertFalse(registry.resolve("StrictReport").isPresent());
        assertTrue(registry.names().isEmpty());
    }
}
