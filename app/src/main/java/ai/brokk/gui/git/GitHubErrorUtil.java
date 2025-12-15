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
     * @param cause the root exception to inspect (may be null)
     * @param baseBranch the base branch name (may be null or blank)
     * @param headBranch the head branch name (may be null or blank)
     * @return true if a matching HttpException is found in the cause chain
     */
    public static boolean isNoCommitsBetweenError(
            @Nullable Throwable cause, @Nullable String baseBranch, @Nullable String headBranch) {
        while (cause != null) {
            if (cause instanceof HttpException httpEx) {
                if (httpEx.getResponseCode() == 422) {
                    var msg = httpEx.getMessage();
                    if (msg != null) {
                        var msgLower = msg.toLowerCase(Locale.ROOT);
                        if (msgLower.contains("no commits between")) {
                            boolean hasBase = baseBranch != null && !baseBranch.isBlank();
                            boolean hasHead = headBranch != null && !headBranch.isBlank();
                            if (!hasBase || !hasHead) {
                                return true;
                            }
                            var baseLower = baseBranch.toLowerCase(Locale.ROOT);
                            var headLower = headBranch.toLowerCase(Locale.ROOT);
                            if (msgLower.contains(baseLower) && msgLower.contains(headLower)) {
                                return true;
                            }
                        }
                    }
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Convenience overload that checks for a "no commits between" error without branch matching.
     */
    public static boolean isNoCommitsBetweenError(@Nullable Throwable cause) {
        return isNoCommitsBetweenError(cause, null, null);
    }

    /**
     * Formats a user-friendly message for a "no commits between" pull request validation error.
     */
    public static String formatNoCommitsBetweenError(String baseBranch, String headBranch) {
        return "No commits to include in pull request between '"
                + headBranch
                + "' and '"
                + baseBranch
                + "'. Make new commits on '"
                + headBranch
                + "' or select a different target branch.";
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
