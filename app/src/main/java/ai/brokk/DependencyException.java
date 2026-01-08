package ai.brokk;

/**
 * Exception for missing dependencies with actionable installation instructions.
 */
public class DependencyException extends RuntimeException {
    public DependencyException(String message) {
        super(message);
    }

    public DependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
