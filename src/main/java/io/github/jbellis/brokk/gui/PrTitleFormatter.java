package io.github.jbellis.brokk.gui;

import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHPullRequest;

/**
 * Utility class for standardizing PR title formatting across the application. Provides consistent formatting patterns
 * for different PR-related UI elements.
 */
public class PrTitleFormatter {

    /** Formats a title for PR diff windows. Format: "PR #123 Diff: Title of the PR" */
    public static String formatDiffTitle(GHPullRequest pr) {
        return "PR #" + pr.getNumber() + " Diff: " + pr.getTitle();
    }

    /** Formats a simple PR identifier for file tree root nodes. Format: "PR #123" */
    public static String formatPrRoot(GHPullRequest pr) {
        return "PR #" + pr.getNumber();
    }

    /** Formats a session name for PR review sessions. Format: "Review PR #123" */
    public static String formatReviewSessionName(GHPullRequest pr) {
        return "Review PR #" + pr.getNumber();
    }

    /**
     * Formats a review prompt with PR information and review guide. Format: "Review PR #123: Title of the
     * PR\n\n{reviewGuide}"
     */
    public static String formatReviewPrompt(GHPullRequest pr, String reviewGuide) {
        return String.format("Review PR #%d: %s\n\n%s", pr.getNumber(), pr.getTitle(), reviewGuide);
    }

    /** Formats a description fragment title for PR descriptions. Format: "PR #123 Description" */
    public static String formatDescriptionTitle(int prNumber) {
        return "PR #" + prNumber + " Description";
    }

    /**
     * Formats an error message for PR capture failures. Format: "Unable to capture diff for PR #123: {errorMessage}"
     */
    public static String formatCaptureError(int prNumber, @Nullable String errorMessage) {
        return "Unable to capture diff for PR #" + prNumber + ": "
                + (errorMessage != null ? errorMessage : "Unknown error");
    }

    /** Formats a notification message for when no changes are found in a PR. Format: "No changes found in PR #123" */
    public static String formatNoChangesMessage(int prNumber) {
        return "No changes found in PR #" + prNumber;
    }

    /**
     * Formats an error message for PR diff opening failures. Format: "Unable to open diff for PR #123: {errorMessage}"
     */
    public static String formatDiffError(int prNumber, @Nullable String errorMessage) {
        return "Unable to open diff for PR #" + prNumber + ": "
                + (errorMessage != null ? errorMessage : "Unknown error");
    }
}
