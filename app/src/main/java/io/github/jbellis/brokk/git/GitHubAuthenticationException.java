package io.github.jbellis.brokk.git;

import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * Exception thrown when GitHub HTTPS authentication fails due to missing or invalid token. This allows callers to
 * distinguish authentication failures from other Git errors.
 */
public class GitHubAuthenticationException extends GitAPIException {
    public GitHubAuthenticationException(String message) {
        super(message);
    }

    public GitHubAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
