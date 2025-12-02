package ai.brokk.gui.git;

import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.HttpException;

/** Utility methods for formatting GitHub API errors with user-friendly messages. */
public final class GitHubErrorUtil {
    private GitHubErrorUtil() {}

    /**
     * Checks if the exception represents a GitHub rate limit error.
     * Handles both 403 (with rate limit message) and 429 (Too Many Requests) status codes.
     */
    public static boolean isRateLimitError(@Nullable Throwable cause) {
        while (cause != null) {
            if (cause instanceof HttpException httpEx) {
                int code = httpEx.getResponseCode();
                if (code == 429) {
                    return true;
                }
                if (code == 403) {
                    var msg = httpEx.getMessage();
                    if (msg != null) {
                        var msgLower = msg.toLowerCase();
                        if (msgLower.contains("rate limit") || msgLower.contains("secondary rate limit")) {
                            return true;
                        }
                    }
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Formats a GitHub API error for display to users.
     * Returns a user-friendly message for rate limits, or a generic error with the exception message.
     */
    public static String formatError(Exception ex, String itemType) {
        if (isRateLimitError(ex)) {
            return "GitHub rate limit exceeded. Try again later.";
        }
        var msg = ex.getMessage();
        return "Error fetching " + itemType + ": " + (msg != null ? msg : "Unknown error");
    }
}
