package dev.langchain4j.data.message;

import static dev.langchain4j.data.message.ChatMessageType.SYSTEM;
import static dev.langchain4j.internal.Utils.quoted;
import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a system message, typically defined by a developer. This type of message usually provides instructions
 * regarding the AI's actions, such as its behavior or response style.
 */
public class SystemMessage implements ChatMessage {

    private final String text;

    @Nullable
    private final String cacheControl;

    /**
     * Creates a new system message.
     *
     * @param text the message text.
     */
    public SystemMessage(String text) {
        this(text, null);
    }

    /**
     * Creates a new system message with cache control.
     *
     * @param text         the message text.
     * @param cacheControl the cache control.
     */
    public SystemMessage(String text, @Nullable String cacheControl) {
        this.text = ensureNotBlank(text, "text");
        this.cacheControl = cacheControl;
    }

    /**
     * Returns the message text.
     *
     * @return the message text.
     */
    public String text() {
        return text;
    }

    /**
     * Returns the cache control.
     *
     * @return the cache control.
     */
    @Nullable
    public String cacheControl() {
        return cacheControl;
    }

    @Override
    public ChatMessageType type() {
        return SYSTEM;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SystemMessage that = (SystemMessage) o;
        return Objects.equals(this.text, that.text) && Objects.equals(this.cacheControl, that.cacheControl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, cacheControl);
    }

    @Override
    public String toString() {
        return "SystemMessage {" + " text = " + quoted(text) + ", cacheControl = " + quoted(cacheControl) + " }";
    }

    /**
     * Creates a new system message.
     *
     * @param text the message text.
     * @return the system message.
     */
    public static SystemMessage from(String text) {
        return new SystemMessage(text);
    }

    /**
     * Creates a new system message with cache control.
     *
     * @param text         the message text.
     * @param cacheControl the cache control.
     * @return the system message.
     */
    public static SystemMessage withCacheControl(String text, String cacheControl) {
        return new SystemMessage(text, cacheControl);
    }

    /**
     * Creates a new system message from an existing one with cache control.
     *
     * @param sm           the system message.
     * @param cacheControl the cache control.
     * @return the system message.
     */
    public static SystemMessage withCacheControl(SystemMessage sm, String cacheControl) {
        return new SystemMessage(sm.text(), cacheControl);
    }

    /**
     * Creates a new system message.
     *
     * @param text the message text.
     * @return the system message.
     */
    public static SystemMessage systemMessage(String text) {
        return from(text);
    }
}
