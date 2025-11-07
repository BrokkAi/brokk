package ai.brokk.gui.util;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.kohsuke.github.HttpException;

/**
 * Centralizes exception-to-user-message mapping for Git tabs (Issues and Pull Requests).
 * Provides consistent, user-facing error messages for common network and API errors.
 */
public final class GitTabExceptionMapper {
    private GitTabExceptionMapper() {}

    /**
     * Maps an exception to a user-friendly error message.
     * Handles HttpException with status code branching (401, 403, 404),
     * as well as UnknownHostException, SocketTimeoutException, ConnectException, and IOException.
     *
     * @param ex The exception to map
     * @return A user-facing error message
     */
    public static String mapExceptionToUserMessage(Exception ex) {
        if (ex instanceof HttpException httpEx) {
            int statusCode = httpEx.getResponseCode();
            return switch (statusCode) {
                case 401 -> "Authentication failed. Please check your GitHub token in Settings.";
                case 403 -> "Access forbidden. Check API rate limit or repository permissions in Settings.";
                case 404 -> "Repository not found. Verify owner/repo in Settings → Project → Issues → GitHub.";
                default -> "GitHub API error (HTTP " + statusCode + "): " + httpEx.getMessage();
            };
        } else if (ex instanceof UnknownHostException) {
            return "Network connection failed. Please check your internet connection.";
        } else if (ex instanceof SocketTimeoutException) {
            return "Request timed out. Please try again or check your network.";
        } else if (ex instanceof ConnectException) {
            return "Request timed out or connection refused. Please try again.";
        } else if (ex instanceof IOException) {
            return "I/O error: " + ex.getMessage();
        } else {
            // Fallback for unexpected exception types
            return "Error: " + ex.getMessage();
        }
    }
}
