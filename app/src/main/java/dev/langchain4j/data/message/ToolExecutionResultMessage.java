package dev.langchain4j.data.message;

import static dev.langchain4j.data.message.ChatMessageType.TOOL_EXECUTION_RESULT;
import static dev.langchain4j.internal.Utils.quoted;
import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.Objects;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Represents the result of a tool execution in response to a {@link ToolExecutionRequest}.
 * {@link ToolExecutionRequest}s come from a previous {@link AiMessage#toolExecutionRequests()}.
 */
public class ToolExecutionResultMessage implements ChatMessage {

    private final String id;
    private final String toolName;
    private final String text;

    /**
     * Creates a {@link ToolExecutionResultMessage}.
     *
     * NB: Prefer ToolExecutionResult::toExecutionResultMessage to manual construction.
     *
     * @param id the id of the tool.
     * @param toolName the name of the tool.
     * @param text the result of the tool execution.
     */
    @VisibleForTesting
    public ToolExecutionResultMessage(String id, String toolName, String text) {
        this.id = id;
        this.toolName = toolName;
        this.text = ensureNotBlank(text, "text");
    }

    /**
     * Returns the id of the tool.
     *
     * @return the id of the tool.
     */
    public String id() {
        return id;
    }

    /**
     * Returns the name of the tool.
     *
     * @return the name of the tool.
     */
    public String toolName() {
        return toolName;
    }

    /**
     * Returns the result of the tool execution.
     *
     * @return the result of the tool execution.
     */
    public String text() {
        return text;
    }

    @Override
    public ChatMessageType type() {
        return TOOL_EXECUTION_RESULT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ToolExecutionResultMessage that = (ToolExecutionResultMessage) o;
        return Objects.equals(this.id, that.id)
                && Objects.equals(this.toolName, that.toolName)
                && Objects.equals(this.text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, toolName, text);
    }

    @Override
    public String toString() {
        return "ToolExecutionResultMessage {" + " id = "
                + quoted(id) + " toolName = "
                + quoted(toolName) + " text = "
                + quoted(text) + " }";
    }
}
