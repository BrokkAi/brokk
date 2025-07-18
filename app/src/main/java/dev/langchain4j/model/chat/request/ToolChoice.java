package dev.langchain4j.model.chat.request;

/**
 * Specifies how chat model should use tools.
 */
public enum ToolChoice {

    /**
     * The chat model can choose whether to use tools, which ones to use, and how many.
     */
    AUTO,

    /**
     * The chat model is required to use one or more tools.
     */
    REQUIRED
}
