package ai.brokk.executor.jobs;

/**
 * Exception thrown when an ISSUE mode job fails due to validation,
 * metadata, or execution errors (e.g., build verification failure).
 */
public class IssueExecutionException extends RuntimeException {
    public IssueExecutionException(String message) {
        super(message);
    }

    public IssueExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
