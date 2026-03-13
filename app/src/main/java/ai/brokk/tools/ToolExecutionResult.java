package ai.brokk.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the result of executing a tool, providing more context than the standard ToolExecutionResultMessage. It
 * includes the execution status, a classification of the action type, the textual result (or error message), and the
 * original request.
 */
public final class ToolExecutionResult {
    private final ToolExecutionRequest request;
    private final Status status;
    private final ToolOutput result;
    private final long elapsedMs;

    private ToolExecutionResult(ToolExecutionRequest request, Status status, ToolOutput result, long elapsedMs) {
        this.request = request;
        this.status = status;
        this.result = result;
        this.elapsedMs = elapsedMs;
    }

    /** Overall status of the tool execution. */
    public enum Status {
        /** The tool executed successfully and produced its intended outcome. */
        SUCCESS,
        /** The tool call was flawed */
        REQUEST_ERROR,
        /** internal error that should never happen */
        INTERNAL_ERROR,
        /** something went so badly wrong that the agent should stop processing. Currently only used for sub-agent failures. */
        FATAL
    }

    // --- Factory Methods ---

    public static ToolExecutionResult create(ToolExecutionRequest request, Status status, @Nullable String resultText) {
        return create(request, status, resultText, 0L);
    }

    public static ToolExecutionResult create(
            ToolExecutionRequest request, Status status, @Nullable String resultText, long elapsedMs) {
        String finalText = (resultText == null || resultText.isBlank()) ? status.toString() : resultText;
        return create(request, status, new ToolOutput.TextOutput(finalText), elapsedMs);
    }

    public static ToolExecutionResult create(ToolExecutionRequest request, Status status, ToolOutput result) {
        return create(request, status, result, 0L);
    }

    public static ToolExecutionResult create(
            ToolExecutionRequest request, Status status, ToolOutput result, long elapsedMs) {
        return new ToolExecutionResult(request, status, result, elapsedMs);
    }

    public static ToolExecutionResult success(ToolExecutionRequest request, @Nullable String resultText) {
        return create(request, Status.SUCCESS, resultText);
    }

    public static ToolExecutionResult success(
            ToolExecutionRequest request, @Nullable String resultText, long elapsedMs) {
        return create(request, Status.SUCCESS, resultText, elapsedMs);
    }

    public static ToolExecutionResult success(ToolExecutionRequest request, ToolOutput output) {
        return create(request, Status.SUCCESS, output);
    }

    public static ToolExecutionResult success(ToolExecutionRequest request, ToolOutput output, long elapsedMs) {
        return create(request, Status.SUCCESS, output, elapsedMs);
    }

    public static ToolExecutionResult requestError(ToolExecutionRequest request, String errorMessage) {
        return create(request, Status.REQUEST_ERROR, errorMessage);
    }

    public static ToolExecutionResult requestError(ToolExecutionRequest request, String errorMessage, long elapsedMs) {
        return create(request, Status.REQUEST_ERROR, errorMessage, elapsedMs);
    }

    public static ToolExecutionResult internalError(ToolExecutionRequest request, String errorMessage) {
        return create(request, Status.INTERNAL_ERROR, errorMessage);
    }

    public static ToolExecutionResult internalError(ToolExecutionRequest request, String errorMessage, long elapsedMs) {
        return create(request, Status.INTERNAL_ERROR, errorMessage, elapsedMs);
    }

    public static ToolExecutionResult fatal(ToolExecutionRequest request, String errorMessage) {
        return create(request, Status.FATAL, errorMessage);
    }

    public static ToolExecutionResult fatal(ToolExecutionRequest request, String errorMessage, long elapsedMs) {
        return create(request, Status.FATAL, errorMessage, elapsedMs);
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
    public ToolExecutionResultMessage toMessage() {
        String text =
                switch (status) {
                    case SUCCESS -> result.llmText();
                    case REQUEST_ERROR -> "Request error: " + result.llmText();
                    case INTERNAL_ERROR -> "Internal error: " + result.llmText();
                    case FATAL -> "Fatal error: " + result.llmText();
                };
        return new ToolExecutionResultMessage(toolId(), toolName(), text);
    }

    public ToolExecutionRequest request() {
        return request;
    }

    public Status status() {
        return status;
    }

    public String resultText() {
        return result.llmText();
    }

    public ToolOutput result() {
        return result;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (ToolExecutionResult) obj;
        return Objects.equals(this.request, that.request)
                && Objects.equals(this.status, that.status)
                && Objects.equals(this.result, that.result)
                && this.elapsedMs == that.elapsedMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(request, status, result, elapsedMs);
    }

    @Override
    public String toString() {
        return "ToolExecutionResult[" + "request="
                + request + ", " + "status="
                + status + ", " + "result="
                + result + ", " + "elapsedMs="
                + elapsedMs + ']';
    }
}
