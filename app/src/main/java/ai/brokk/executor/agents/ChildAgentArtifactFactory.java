package ai.brokk.executor.agents;

import ai.brokk.executor.jobs.ChildAgentArtifact;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

final class ChildAgentArtifactFactory {
    private static final int EXCERPT_MAX_CHARS = 2_000;

    private ChildAgentArtifactFactory() {}

    static ChildAgentArtifact fromToolResult(
            String parentJobId,
            String childRunId,
            @Nullable String toolCallId,
            String agentName,
            String responseSchemaName,
            String modelName,
            long elapsedMs,
            ToolExecutionResult result) {
        var resultText = result.resultText();
        if (result.status() == ToolExecutionResult.Status.SUCCESS) {
            return successFromText(
                    parentJobId,
                    childRunId,
                    toolCallId,
                    agentName,
                    responseSchemaName,
                    modelName,
                    elapsedMs,
                    resultText);
        }
        if (result.status() == ToolExecutionResult.Status.FATAL
                && resultText.contains("RESPONSE_SCHEMA_OUTPUT_INVALID")) {
            return schemaInvalidFromFailureText(
                    parentJobId,
                    childRunId,
                    toolCallId,
                    agentName,
                    responseSchemaName,
                    modelName,
                    elapsedMs,
                    resultText);
        }
        return genericFailure(
                parentJobId,
                childRunId,
                toolCallId,
                agentName,
                responseSchemaName,
                modelName,
                elapsedMs,
                statusForNonSchemaFailure(resultText),
                resultText);
    }

    static ChildAgentArtifact successFromText(
            String parentJobId,
            String childRunId,
            @Nullable String toolCallId,
            String agentName,
            String responseSchemaName,
            String modelName,
            long elapsedMs,
            String text) {
        try {
            var parsed = Json.getMapper().readTree(text);
            if (parsed.isObject()) {
                return artifact(
                        parentJobId,
                        childRunId,
                        toolCallId,
                        agentName,
                        responseSchemaName,
                        ChildAgentArtifact.STATUS_SUCCESS,
                        parsed,
                        null,
                        null,
                        null,
                        elapsedMs,
                        modelName);
            }
            return schemaInvalid(
                    parentJobId,
                    childRunId,
                    toolCallId,
                    agentName,
                    responseSchemaName,
                    modelName,
                    elapsedMs,
                    "validated child response was not a JSON object",
                    text);
        } catch (Exception e) {
            return schemaInvalid(
                    parentJobId,
                    childRunId,
                    toolCallId,
                    agentName,
                    responseSchemaName,
                    modelName,
                    elapsedMs,
                    "validated child response could not be parsed as JSON: " + e.getMessage(),
                    text);
        }
    }

    static ChildAgentArtifact schemaInvalidFromFailureText(
            String parentJobId,
            String childRunId,
            @Nullable String toolCallId,
            String agentName,
            String responseSchemaName,
            String modelName,
            long elapsedMs,
            String failureText) {
        var validationError = SchemaFailureDiagnostics.validationError(failureText);
        if (validationError == null) {
            validationError = failureText.lines().findFirst().orElse("RESPONSE_SCHEMA_OUTPUT_INVALID");
        }
        var invalidOutputExcerpt = SchemaFailureDiagnostics.invalidOutputExcerpt(failureText);
        if (invalidOutputExcerpt == null) {
            invalidOutputExcerpt = SchemaFailureDiagnostics.originalOutputExcerpt(failureText);
        }
        if (invalidOutputExcerpt == null) {
            invalidOutputExcerpt = excerpt(failureText);
        }
        return artifact(
                parentJobId,
                childRunId,
                toolCallId,
                agentName,
                responseSchemaName,
                ChildAgentArtifact.STATUS_SCHEMA_INVALID,
                null,
                validationError,
                invalidOutputExcerpt,
                null,
                elapsedMs,
                modelName);
    }

    static ChildAgentArtifact genericFailure(
            String parentJobId,
            String childRunId,
            @Nullable String toolCallId,
            String agentName,
            String responseSchemaName,
            String modelName,
            long elapsedMs,
            String status,
            String errorText) {
        return artifact(
                parentJobId,
                childRunId,
                toolCallId,
                agentName,
                responseSchemaName,
                status,
                null,
                null,
                null,
                excerpt(errorText),
                elapsedMs,
                modelName);
    }

    private static ChildAgentArtifact schemaInvalid(
            String parentJobId,
            String childRunId,
            @Nullable String toolCallId,
            String agentName,
            String responseSchemaName,
            String modelName,
            long elapsedMs,
            String validationError,
            String invalidOutput) {
        return artifact(
                parentJobId,
                childRunId,
                toolCallId,
                agentName,
                responseSchemaName,
                ChildAgentArtifact.STATUS_SCHEMA_INVALID,
                null,
                validationError,
                excerpt(invalidOutput),
                null,
                elapsedMs,
                modelName);
    }

    private static ChildAgentArtifact artifact(
            String parentJobId,
            String childRunId,
            @Nullable String toolCallId,
            String agentName,
            String responseSchemaName,
            String status,
            @Nullable JsonNode validatedResponse,
            @Nullable String validationError,
            @Nullable String invalidOutputExcerpt,
            @Nullable String errorMessage,
            long elapsedMs,
            String modelName) {
        return new ChildAgentArtifact(
                parentJobId,
                childRunId,
                toolCallId,
                agentName,
                responseSchemaName,
                status,
                validatedResponse,
                validationError,
                invalidOutputExcerpt,
                errorMessage,
                elapsedMs,
                modelName,
                null,
                null,
                null);
    }

    static String statusForNonSchemaFailure(String resultText) {
        var lower = resultText.toLowerCase(Locale.ROOT);
        if (lower.contains("cancel")) {
            return ChildAgentArtifact.STATUS_CANCELLED;
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return ChildAgentArtifact.STATUS_TIMEOUT;
        }
        return ChildAgentArtifact.STATUS_FAILED;
    }

    private static String excerpt(String text) {
        var normalized = text.strip();
        if (normalized.length() <= EXCERPT_MAX_CHARS) {
            return normalized;
        }
        return normalized.substring(0, EXCERPT_MAX_CHARS)
                + "...[truncated "
                + (normalized.length() - EXCERPT_MAX_CHARS)
                + " chars]";
    }
}
