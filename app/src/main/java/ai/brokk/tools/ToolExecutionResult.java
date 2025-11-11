package ai.brokk.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the result of executing a tool, providing more context than the standard ToolExecutionResultMessage. It
 * includes the execution status, a classification of the action type, the textual result (or error message), and the
 * original request.
 */
public record ToolExecutionResult(
        ToolExecutionRequest request,
        Status status,
        String resultText // Contains the primary output on SUCCESS, or error message on FAILURE.
        ) {

    /** Overall status of the tool execution. */
    public enum Status {
        /** The tool executed successfully and produced its intended outcome. */
        SUCCESS,
        /** The tool call was flawed */
        REQUEST_ERROR,
        /** internal error that should never happen */
        INTERNAL_ERROR,
        /** an external resource is unavailable, requiring immediate termination of the agent */
        FATAL_RESOURCE
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
    public static ToolExecutionResult requestError(ToolExecutionRequest request, @Nullable String errorMessage) {
        String finalError = (errorMessage == null || errorMessage.isBlank()) ? "Unknown error" : errorMessage;
        // Store the error message in the resultText field for simplicity
        return new ToolExecutionResult(request, Status.REQUEST_ERROR, finalError);
    }

    public static ToolExecutionResult internalError(ToolExecutionRequest request, String errorMessage) {
        return new ToolExecutionResult(request, Status.INTERNAL_ERROR, errorMessage);
    }

    /**
     * Creates a fatal error result.
     *
     * @param request The original request.
     * @param errorMessage The error message describing the fatal error.
     * @return A new ToolExecutionResult instance.
     */
    public static ToolExecutionResult fatal(ToolExecutionRequest request, String errorMessage) {
        return new ToolExecutionResult(request, Status.FATAL_RESOURCE, errorMessage);
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
     * Converts this extended result into the standard LangChain4j ToolExecutionResultMessage suitable for sending back
     * to the LLM.
     *
     * @return A ToolExecutionResultMessage.
     */
    public ToolExecutionResultMessage toExecutionResultMessage() {
        String text =
                switch (status) {
                    case SUCCESS -> resultText; // Already handled null/blank in factory
                    case REQUEST_ERROR -> "Error: " + resultText;
                    case INTERNAL_ERROR -> "Internal error: " + resultText;
                    case FATAL_RESOURCE -> "Fatal error: " + resultText;
                };
        return new ToolExecutionResultMessage(toolId(), toolName(), text);
    }
}
