package ai.brokk.executor.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SchemaFailureDiagnosticsTest {
    @Test
    void parsesSingleLineFinalValidationAndExcerpt() {
        var text = "RESPONSE_SCHEMA_OUTPUT_INVALID: schema=StrictReport "
                + "finalValidation=response.found is required "
                + "invalidOutputExcerpt={\"found\":null} finishReason=complete";

        assertEquals("response.found is required", SchemaFailureDiagnostics.validationError(text));
        assertEquals("{\"found\":null}", SchemaFailureDiagnostics.invalidOutputExcerpt(text));
        assertEquals("complete", SchemaFailureDiagnostics.finishReason(text));
    }

    @Test
    void parsesNewlineSeparatedDiagnostics() {
        var text =
                """
                RESPONSE_SCHEMA_OUTPUT_INVALID
                schema=StrictReport
                candidateSource=answer.explanation.text
                finalValidation=response.metadata is required
                invalidOutputExcerpt={"summary":"partial"}
                finishReason=LENGTH
                """;

        assertEquals("response.metadata is required", SchemaFailureDiagnostics.validationError(text));
        assertEquals("{\"summary\":\"partial\"}", SchemaFailureDiagnostics.invalidOutputExcerpt(text));
        assertEquals("LENGTH", SchemaFailureDiagnostics.finishReason(text));
    }

    @Test
    void fallsBackFromFinalValidationToOriginalValidationToLegacyValidation() {
        assertEquals(
                "response.summary is required",
                SchemaFailureDiagnostics.validationError(
                        "originalValidation=response.summary is required invalidOutputExcerpt={}"));
        assertEquals(
                "response.found is required",
                SchemaFailureDiagnostics.validationError(
                        "validation=response.found is required invalidOutputExcerpt={}"));
        assertEquals(
                "response.summary is required",
                SchemaFailureDiagnostics.validationError(
                        "finalValidation=null originalValidation=response.summary is required"));
    }

    @Test
    void doesNotOverrunIntoNextKeyOrMatchEmbeddedKeyName() {
        var text = "notfinalValidation=wrong finalValidation=response.summary is required "
                + "invalidOutputExcerpt={\"summary\":null}";

        assertEquals("response.summary is required", SchemaFailureDiagnostics.validationError(text));
        assertEquals("{\"summary\":null}", SchemaFailureDiagnostics.invalidOutputExcerpt(text));
    }
}
