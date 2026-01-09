package ai.brokk;

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
