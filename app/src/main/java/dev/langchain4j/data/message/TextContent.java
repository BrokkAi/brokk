package dev.langchain4j.data.message;

import static dev.langchain4j.data.message.ContentType.TEXT;
import static dev.langchain4j.internal.Utils.quoted;
import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** Represents a text content. */
public class TextContent implements Content {

    private final String text;

    @Nullable
    private final String cacheControl;

    /**
     * Creates a new text content.
     *
     * @param text the text.
     */
    public TextContent(String text) {
        this(text, null);
    }

    private TextContent(String text, @Nullable String cacheControl) {
        this.text = ensureNotBlank(text, "text");
        this.cacheControl = cacheControl;
    }

    /**
     * Returns the text.
     *
     * @return the text.
     */
    public String text() {
        return text;
    }

    @Override
    public ContentType type() {
        return TEXT;
    }

    @Override
    @Nullable
    public String cacheControl() {
        return cacheControl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TextContent that = (TextContent) o;
        return Objects.equals(this.text, that.text) && Objects.equals(this.cacheControl, that.cacheControl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, cacheControl);
    }

    @Override
    public String toString() {
        return "TextContent {" + " text = " + quoted(text) + " cacheControl = " + quoted(cacheControl) + " }";
    }

    /**
     * Creates a new text content.
     *
     * @param text the text.
     * @return the text content.
     */
    public static TextContent from(String text) {
        return new TextContent(text);
    }

    /**
     * Creates a new text content with cache control.
     *
     * @param text the text.
     * @param cacheControl the cache control.
     * @return the text content.
     */
    public static TextContent withCacheControl(String text, String cacheControl) {
        return new TextContent(text, cacheControl);
    }

    /**
     * Creates a new text content from an existing one with cache control.
     *
     * @param original the original text content.
     * @param cacheControl the cache control.
     * @return the text content.
     */
    public static TextContent withCacheControl(TextContent original, String cacheControl) {
        return new TextContent(original.text(), cacheControl);
    }
}
