package dev.langchain4j.data.message;

import org.jetbrains.annotations.Nullable;

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
     * <p>Can be used to cast the content to the correct type.
     *
     * @return The type of content.
     */
    ContentType type();

    /**
     * Returns the cache control directive for this content.
     *
     * @return The cache control directive, or null if not set.
     */
    @Nullable
    default String cacheControl() {
        return null;
    }
}
