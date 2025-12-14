package dev.langchain4j.exception;

public class PaymentRequiredException extends NonRetriableException {
    public PaymentRequiredException(Throwable th) {
        super(th);
    }
}
