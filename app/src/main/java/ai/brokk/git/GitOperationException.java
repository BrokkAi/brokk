package ai.brokk.git;

import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * Exception thrown when a git operation fails for logical reasons (invalid branch, conflicts, etc.). This provides a
 * concrete subclass of GitAPIException to avoid anonymous subclass syntax.
 */
public class GitOperationException extends GitAPIException {
    public GitOperationException(String message) {
        super(message);
    }

    public GitOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
