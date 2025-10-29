package ai.brokk.issues;

import ai.brokk.util.HtmlUtil;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * UI-free helper for building issue capture content and messages.
 *
 * This class centralizes logic that was previously embedded in the GUI-only GitIssuesTab so it can
 * be reused by headless tools and other components.
 *
 * Notes:
 * - For Jira issues, HTML content is converted to Markdown via HtmlUtil.
 * - Message-producing methods return LangChain4j ChatMessage instances.
 */
public final class IssueCaptureBuilder {

    private IssueCaptureBuilder() {
        // no instances
    }

    /**
     * Builds the main issue description messages as ChatMessage list (single UserMessage).
     * Mirrors the formatting used in GitIssuesTab.buildIssueTextContentFromDetails.
     *
     * @param service the issue service (used to detect Jira vs GitHub formatting)
     * @param details the loaded issue details
     * @return a singleton list containing one UserMessage with the formatted Markdown content
     */
    public static List<ChatMessage> buildIssueTextMessages(IssueService service, IssueDetails details) {
        var header = details.header();
        String bodyForCapture = details.markdownBody(); // HTML from Jira, Markdown from GitHub
        if (service instanceof JiraIssueService) {
            bodyForCapture = HtmlUtil.convertToMarkdown(bodyForCapture);
        }
        bodyForCapture = bodyForCapture.isBlank() ? "*No description provided.*" : bodyForCapture;

        String content = String.format(
                """
                # Issue #%s: %s

                **Author:** %s
                **Status:** %s
                **URL:** %s
                **Labels:** %s
                **Assignees:** %s

                ---

                %s
                """,
                header.id(),
                header.title(),
                header.author(),
                header.status(),
                header.htmlUrl(),
                header.labels().isEmpty() ? "None" : String.join(", ", header.labels()),
                header.assignees().isEmpty() ? "None" : String.join(", ", header.assignees()),
                bodyForCapture);

        return List.of(UserMessage.from(header.author(), content));
    }

    /**
     * Builds comment messages as a list of ChatMessage (UserMessage) instances.
     * Mirrors the behavior of GitIssuesTab.buildChatMessagesFromDtoComments.
     *
     * @param service the issue service (used to detect Jira vs GitHub formatting)
     * @param comments the list of comments
     * @return a list of UserMessage, one per non-empty comment
     */
    public static List<ChatMessage> buildCommentMessages(IssueService service, List<Comment> comments) {
        var chatMessages = new ArrayList<ChatMessage>();

        for (var comment : comments) {
            var author = comment.author().isBlank() ? "unknown" : comment.author();
            String originalCommentBody = comment.markdownBody(); // HTML from Jira, Markdown from GitHub
            String commentBodyForCapture = originalCommentBody;
            if (service instanceof JiraIssueService) {
                commentBodyForCapture = HtmlUtil.convertToMarkdown(originalCommentBody);
            }

            if (!commentBodyForCapture.isBlank()) {
                chatMessages.add(UserMessage.from(author, commentBodyForCapture));
            }
        }
        return chatMessages;
    }

    /**
     * Builds a compact, single-line-per-issue Markdown list.
     * Each line includes: id, [status], title, and optional (labels: ...) and (assignees: ...) sections.
     * Empty sections are omitted to keep the output short.
     *
     * Example line:
     * - #123 [Open] Fix NPE on startup (labels: bug,core; assignees: alice,bob)
     *
     * @param headers list of issue headers
     * @param owner repository owner (not used in formatting, but kept for future extensibility)
     * @param repo repository name (not used in formatting, but kept for future extensibility)
     * @return Markdown string containing one bullet per issue, or a placeholder if empty
     */
    public static String buildCompactListMarkdown(List<IssueHeader> headers, String owner, String repo) {
        if (headers.isEmpty()) {
            return "*No issues found.*";
        }

        return headers.stream()
                .map(IssueCaptureBuilder::toCompactLine)
                .collect(Collectors.joining("\n"));
    }

    private static String toCompactLine(IssueHeader h) {
        var metaParts = new ArrayList<String>(2);
        if (!h.labels().isEmpty()) {
            metaParts.add("labels: " + String.join(",", h.labels()));
        }
        if (!h.assignees().isEmpty()) {
            metaParts.add("assignees: " + String.join(",", h.assignees()));
        }
        var meta = metaParts.isEmpty() ? "" : " (" + String.join("; ", metaParts) + ")";

        // Keep concise: id, [Status], Title, and optional meta
        return "- " + h.id() + " [" + h.status() + "] " + h.title() + meta;
    }
}
