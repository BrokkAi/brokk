package ai.brokk.executor.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.executor.jobs.ChildAgentArtifact;
import ai.brokk.tools.ToolExecutionResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

class ChildAgentArtifactFactoryTest {
    private static ToolExecutionRequest request() {
        return ToolExecutionRequest.builder()
                .id("tool-call-1")
                .name("callCustomAgentWithSchema")
                .arguments("{}")
                .build();
    }

    @Test
    void successPreservesValidatedJsonObject() {
        var result = ToolExecutionResult.success(request(), "{\"summary\":\"ok\"}", 37L);

        var artifact = ChildAgentArtifactFactory.fromToolResult(
                "parent-job", "child-run", "tool-call-1", "agent", "StrictReport", "model", 37L, result);

        assertEquals(ChildAgentArtifact.STATUS_SUCCESS, artifact.status());
        assertNotNull(artifact.validatedResponse());
        assertEquals("ok", artifact.validatedResponse().get("summary").asText());
        assertNull(artifact.validationError());
        assertEquals(37L, artifact.elapsedMs());
        assertEquals("model", artifact.model());
    }

    @Test
    void schemaInvalidFailureUsesStructuredDiagnostics() {
        var failureText = "RESPONSE_SCHEMA_OUTPUT_INVALID: schema=StrictReport "
                + "originalValidation=response.summary expected string "
                + "finalValidation=response.summary is required "
                + "invalidOutputExcerpt={\"summary\":null} finishReason=complete";
        var result = ToolExecutionResult.fatal(request(), failureText, 12L);

        var artifact = ChildAgentArtifactFactory.fromToolResult(
                "parent-job", "child-run", "tool-call-1", "agent", "StrictReport", "model", 12L, result);

        assertEquals(ChildAgentArtifact.STATUS_SCHEMA_INVALID, artifact.status());
        assertEquals("response.summary is required", artifact.validationError());
        assertEquals("{\"summary\":null}", artifact.invalidOutputExcerpt());
        assertNull(artifact.errorMessage());
    }

    @Test
    void schemaInvalidFailureFallsBackToOriginalValidationAndBoundedExcerpt() {
        var failureText = "RESPONSE_SCHEMA_OUTPUT_INVALID: schema=StrictReport "
                + "originalValidation=response.summary expected string "
                + "originalOutputExcerpt={\"summary\":null}";
        var result = ToolExecutionResult.fatal(request(), failureText);

        var artifact = ChildAgentArtifactFactory.fromToolResult(
                "parent-job", "child-run", "tool-call-1", "agent", "StrictReport", "model", 0L, result);

        assertEquals(ChildAgentArtifact.STATUS_SCHEMA_INVALID, artifact.status());
        assertEquals("response.summary expected string", artifact.validationError());
        assertEquals("{\"summary\":null}", artifact.invalidOutputExcerpt());
    }

    @Test
    void genericFailuresMapStatusFromFailureText() {
        assertEquals(
                ChildAgentArtifact.STATUS_TIMEOUT,
                ChildAgentArtifactFactory.statusForNonSchemaFailure("child timed out"));
        assertEquals(
                ChildAgentArtifact.STATUS_CANCELLED,
                ChildAgentArtifactFactory.statusForNonSchemaFailure("child was cancelled"));
        assertEquals(ChildAgentArtifact.STATUS_FAILED, ChildAgentArtifactFactory.statusForNonSchemaFailure("boom"));
    }

    @Test
    void requestErrorBecomesFailedArtifact() {
        var result = ToolExecutionResult.requestError(request(), "responseSchemaName is required");

        var artifact = ChildAgentArtifactFactory.fromToolResult(
                "parent-job", "child-run", "tool-call-1", "agent", "StrictReport", "model", 0L, result);

        assertEquals(ChildAgentArtifact.STATUS_FAILED, artifact.status());
        assertNotNull(artifact.errorMessage());
        assertTrue(artifact.errorMessage().contains("responseSchemaName is required"));
    }

    @Test
    void malformedSuccessfulTextBecomesSchemaInvalidArtifact() {
        var result = ToolExecutionResult.success(request(), "not json");

        var artifact = ChildAgentArtifactFactory.fromToolResult(
                "parent-job", "child-run", "tool-call-1", "agent", "StrictReport", "model", 0L, result);

        assertEquals(ChildAgentArtifact.STATUS_SCHEMA_INVALID, artifact.status());
        assertNotNull(artifact.validationError());
        assertTrue(artifact.validationError().contains("could not be parsed as JSON"));
        assertEquals("not json", artifact.invalidOutputExcerpt());
    }
}
