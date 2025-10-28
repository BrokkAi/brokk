package ai.brokk.exception;

public class LlmException extends RuntimeException {
    public LlmException(String activity, Throwable error) {
        super("LLM error while " + activity, error);
    }
}
