package ai.brokk;

/**
 * Metadata flags associated with streamed LLM output.
 *
 * <p>The canonical record constructor is public only because Java records require it. Callers should prefer the
 * provided factory-like entry points ({@link #DEFAULT}, {@link #newMessage()}, {@link #reasoning()},
 * {@link #terminal()}) and the fluent {@code withX} methods to construct and evolve instances, rather than calling
 * {@code new LlmOutputMeta(...)} directly.
 */
public record LlmOutputMeta(boolean isNewMessage, boolean isReasoning, boolean isTerminal) {
    public static final LlmOutputMeta DEFAULT = new LlmOutputMeta(false, false, false);

    public static LlmOutputMeta newMessage() {
        return new LlmOutputMeta(true, false, false);
    }

    public static LlmOutputMeta reasoning() {
        return new LlmOutputMeta(false, true, false);
    }

    public static LlmOutputMeta terminal() {
        return new LlmOutputMeta(false, false, true);
    }

    public LlmOutputMeta withNewMessage(boolean isNewMessage) {
        return new LlmOutputMeta(isNewMessage, isReasoning, isTerminal);
    }

    public LlmOutputMeta withReasoning(boolean isReasoning) {
        return new LlmOutputMeta(isNewMessage, isReasoning, isTerminal);
    }

    public LlmOutputMeta withTerminal(boolean isTerminal) {
        return new LlmOutputMeta(isNewMessage, isReasoning, isTerminal);
    }
}
