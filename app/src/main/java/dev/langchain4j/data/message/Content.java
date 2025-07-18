package dev.langchain4j.data.message;

/**
 * Abstract base interface for message content.
 *
 * @see TextContent
 * @see ImageContent
 */
public interface Content {
    /**
     * Returns the type of content.
     *
     * <p>Can be used to cast the content to the correct type.</p>
     *
     * @return The type of content.
     */
    ContentType type();
}
