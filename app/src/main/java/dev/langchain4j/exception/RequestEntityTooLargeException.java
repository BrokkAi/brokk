package dev.langchain4j.exception;

public class RequestEntityTooLargeException extends RetriableException {
    public RequestEntityTooLargeException(String message) {
        super(message);
    }

    public RequestEntityTooLargeException(Throwable cause) {
        this(cause.getMessage(), cause);
    }

    public RequestEntityTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }

}
