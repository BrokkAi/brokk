package dev.langchain4j.exception;

public class OverthinkingException extends NonRetriableException {
    public OverthinkingException(String message) {
        super(message);
    }

    public OverthinkingException(Throwable cause) {
        super(cause);
    }
}
