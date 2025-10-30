package ai.brokk.issues;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.net.URI;
import java.util.Date;
import java.util.List;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IssueCaptureBuilderTest {

    private static class FakeGitHubIssueService implements IssueService {
        @Override public List<IssueHeader> listIssues(FilterOptions filterOptions) { return List.of(); }
        @Override public IssueDetails loadDetails(String issueId) { throw new UnsupportedOperationException(); }
        @Override public OkHttpClient httpClient() { return new OkHttpClient(); }
        @Override public List<String> listAvailableStatuses() { return List.of(); }
    }

    // Lightweight service to trigger the Jira branch without requiring a real JiraIssueService/IProject
    private static class FakeJiraIssueService implements IssueService {
        @Override public List<IssueHeader> listIssues(FilterOptions filterOptions) { return List.of(); }
        @Override public IssueDetails loadDetails(String issueId) { throw new UnsupportedOperationException(); }
        @Override public OkHttpClient httpClient() { return new OkHttpClient(); }
        @Override public List<String> listAvailableStatuses() { return List.of(); }
    }

    @Test
    void testBuildCompactListMarkdown() {
        var h1 = new IssueHeader("1", "First issue", "alice", new Date(), List.of(), List.of(), "Open", URI.create("https://example.com/1"));
        var h2 = new IssueHeader("2", "Second issue", "bob", new Date(), List.of("bug","core"), List.of("carol","dave"), "Closed", URI.create("https://example.com/2"));
        var h3 = new IssueHeader("3", "Third issue", "eve", new Date(), List.of("enhancement"), List.of(), "Open", URI.create("https://example.com/3"));

        String md = IssueCaptureBuilder.buildCompactListMarkdown(List.of(h1, h2, h3), "owner", "repo");
        String[] lines = md.split("\\R");

        assertEquals(3, lines.length);
        assertEquals("- 1 [Open] First issue", lines[0]);
        assertEquals("- 2 [Closed] Second issue (labels: bug,core; assignees: carol,dave)", lines[1]);
        assertEquals("- 3 [Open] Third issue (labels: enhancement)", lines[2]);
    }

    @Test
    void testBuildIssueTextMessages_GitHub() {
        var header = new IssueHeader("123", "Fix bug", "alice", new Date(), List.of("bug"), List.of("bob"), "Open", URI.create("https://github.com/o/r/issues/123"));
        var details = new IssueDetails(header, "Body text here", List.of(), List.of());
        var service = new FakeGitHubIssueService();

        List<ChatMessage> messages = IssueCaptureBuilder.buildIssueTextMessages(service, details);
        assertEquals(1, messages.size());
        var um = (UserMessage) messages.getFirst();

        String content = um.toString();
        assertEquals("alice", um.name());
        assertTrue(content.contains("# Issue #123: Fix bug"));
        assertTrue(content.contains("**Author:** alice"));
        assertTrue(content.contains("**Status:** Open"));
        assertTrue(content.contains("**URL:** https://github.com/o/r/issues/123"));
        assertTrue(content.contains("**Labels:** bug"));
        assertTrue(content.contains("**Assignees:** bob"));
        assertTrue(content.contains("Body text here"));

        // Blank body -> placeholder
        var detailsBlank = new IssueDetails(header, "   ", List.of(), List.of());
        var messagesBlank = IssueCaptureBuilder.buildIssueTextMessages(service, detailsBlank);
        var contentBlank = ((UserMessage) messagesBlank.getFirst()).toString();
        assertTrue(contentBlank.contains("*No description provided.*"));
    }

    @Test
    void testBuildIssueTextMessages_Jira_convertsHtmlToMarkdown() {
        var header = new IssueHeader("7", "Jira Story", "frank", new Date(), List.of(), List.of(), "In Progress", URI.create("https://jira.example/browse/PROJ-7"));
        var htmlBody = "<strong>Bold</strong> and <em>italic</em> text";
        var details = new IssueDetails(header, htmlBody, List.of(), List.of());

        // Use a lightweight fake service that triggers the Jira branch
        var jiraService = new FakeJiraIssueService();

        List<ChatMessage> messages = IssueCaptureBuilder.buildIssueTextMessages(jiraService, details);
        var content = ((UserMessage) messages.getFirst()).toString();

        assertFalse(content.contains("<strong>"));
        assertTrue(content.contains("**Bold**"));
        assertTrue(content.contains("*italic*"));
    }

    @Test
    void testBuildCommentMessages() {
        var service = new FakeGitHubIssueService();
        var comments = List.of(
                new Comment("", "hello world", new Date()),   // blank author -> "unknown"
                new Comment("bob", "   ", new Date()),        // blank body -> skipped
                new Comment("carol", "regular markdown", new Date())
        );

        List<ChatMessage> msgs = IssueCaptureBuilder.buildCommentMessages(service, comments);
        assertEquals(2, msgs.size());
        var m0 = (UserMessage) msgs.get(0);
        var m1 = (UserMessage) msgs.get(1);
        assertEquals("unknown", m0.name());
        assertTrue(m0.toString().contains("hello world"));
        assertEquals("carol", m1.name());
        assertTrue(m1.toString().contains("regular markdown"));
    }

    @Test
    void testBuildCommentMessages_Jira_convertsHtml() {
        var jiraService = new FakeJiraIssueService();
        var comments = List.of(
                new Comment("dan", "<em>hi</em>", new Date())
        );

        List<ChatMessage> msgs = IssueCaptureBuilder.buildCommentMessages(jiraService, comments);
        assertEquals(1, msgs.size());
        var um = (UserMessage) msgs.getFirst();
        assertEquals("dan", um.name());
        assertTrue(um.toString().contains("*hi*"));
    }
}
