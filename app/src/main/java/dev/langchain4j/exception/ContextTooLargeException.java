package dev.langchain4j.exception;

public class ContextTooLargeException extends NonRetriableException {
    public ContextTooLargeException(String message) {
        super(message);
    }

    public ContextTooLargeException(Throwable cause) {
        super(cause);
    }
}
