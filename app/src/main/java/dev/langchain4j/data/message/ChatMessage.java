package dev.langchain4j.data.message;

/**
 * Minimal interface representing a chat message.
 *
 * This is a deliberately small interface to allow the rest of the codebase
 * (which defines many concrete message classes implementing ChatMessage) to compile.
 * If the project has a canonical ChatMessage interface/class, replace this with
 * the full implementation.
 */
public interface ChatMessage {

    /**
     * Returns the logical message type/role.
     */
    ChatMessageType type();
}
