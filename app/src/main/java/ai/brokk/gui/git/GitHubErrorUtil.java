package ai.brokk.gui.git;

import java.util.Locale;
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
                        var msgLower = msg.toLowerCase(Locale.ROOT);
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
     * Checks if the exception chain represents the GitHub PR validation error
     * "No commits between &lt;base&gt; and &lt;head&gt;" (HTTP 422).
     *
     * <p>This is a best-effort heuristic based on substring matching. GitHub's API
     * does not expose structured error codes for this condition.
     */
    public static boolean isNoCommitsBetweenError(@Nullable Throwable cause) {
        while (cause != null) {
            if (cause instanceof HttpException httpEx) {
                if (httpEx.getResponseCode() == 422) {
                    var msg = httpEx.getMessage();
                    if (msg != null && msg.toLowerCase(Locale.ROOT).contains("no commits between")) {
                        return true;
                    }
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Formats a user-friendly message for a "no commits between" pull request validation error.
     */
    public static String formatNoCommitsBetweenError(String baseBranch, String headBranch) {
        return "GitHub reports no commits to include in a pull request between '"
                + headBranch
                + "' and '"
                + baseBranch
                + "'. Make new commits on '"
                + headBranch
                + "', push them to GitHub, or select a different target branch.";
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
