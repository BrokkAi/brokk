package dev.langchain4j.data.message;

/**
 * Minimal enum to represent chat message roles/types.
 *
 * The constants mirror the names used throughout the codebase so static imports
 * such as `import static dev.langchain4j.data.message.ChatMessageType.AI;` work.
 *
 * Replace this stub with the project's canonical enum if available.
 */
public enum ChatMessageType {
    SYSTEM,
    USER,
    AI,
    ASSISTANT,
    REASONING,
    TOOL_EXECUTION_REQUEST,
    TOOL_EXECUTION_RESULT,
    TOOL_METADATA,
    CUSTOM
}
