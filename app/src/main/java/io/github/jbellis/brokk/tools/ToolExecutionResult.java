package io.github.jbellis.brokk.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the result of executing a tool, providing more context than
 * the standard ToolExecutionResultMessage.
 * It includes the execution status, a classification of the action type,
 * the textual result (or error message), and the original request.
 */
public record ToolExecutionResult(
        ToolExecutionRequest request,
        Status status,
        String resultText // Contains the primary output on SUCCESS, or error message on FAILURE.
) {
    /**
     * Overall status of the tool execution.
     */
    public enum Status {
        /** The tool executed successfully and produced its intended outcome. */
        SUCCESS,
        /** The tool failed to execute due to an error. */
        FAILURE
    }

    // --- Constructor ---

    /**
     * Primary constructor. Ensures non-null fields.
     */
    public ToolExecutionResult {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(resultText, "resultText cannot be null"); // Store error message here on failure
    }

    // --- Factory Methods ---

    /**
     * Creates a success result.
     *
     * @param request The original request.
     * @param resultText The textual result for the LLM (can be null, will default to "Success").
     * @return A new ToolExecutionResult instance.
     */
    public static ToolExecutionResult success(ToolExecutionRequest request, @Nullable String resultText) {
        String finalText = (resultText == null || resultText.isBlank()) ? "Success" : resultText;
        return new ToolExecutionResult(request, Status.SUCCESS, finalText);
    }

    /**
     * Creates a failure result.
     *
     * @param request The original request.
     * @param errorMessage The error message describing the failure.
     * @return A new ToolExecutionResult instance.
     */
    public static ToolExecutionResult failure(ToolExecutionRequest request, @Nullable String errorMessage) {
        String finalError = (errorMessage == null || errorMessage.isBlank()) ? "Unknown error" : errorMessage;
        // Store the error message in the resultText field for simplicity
        return new ToolExecutionResult(request, Status.FAILURE, finalError);
    }

    // --- Convenience Accessors ---

    public String toolName() {
        return request.name();
    }

    public String toolId() {
        return request.id();
    }

    public String arguments() {
        return request.arguments();
    }

    // --- Conversion ---

    /**
     * Converts this extended result into the standard LangChain4j
     * ToolExecutionResultMessage suitable for sending back to the LLM.
     *
     * @return A ToolExecutionResultMessage.
     */
    public ToolExecutionResultMessage toExecutionResultMessage() {
        String text;
        if (status == Status.SUCCESS) {
            text = resultText; // Already handled null/blank in factory
        } else {
            // For failure, the resultText field holds the error message (set by the factory)
            text = "Error: " + resultText;
        }
        // Use the ID from the original request if available, otherwise default? LC4J seems okay with null ID here.
        return new ToolExecutionResultMessage(toolId(), toolName(), text);
    }
}
