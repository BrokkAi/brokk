package ai.brokk.tools;

import ai.brokk.ContextManager;
import ai.brokk.IProject;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.util.GitUiUtil;
import ai.brokk.issues.Comment;
import ai.brokk.issues.FilterOptions;
import ai.brokk.issues.GitHubFilterOptions;
import ai.brokk.issues.IssueCaptureBuilder;
import ai.brokk.issues.IssueDetails;
import ai.brokk.issues.IssueHeader;
import ai.brokk.issues.IssueService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GitIssueToolsTest {

    private static class FakeProject implements IProject {
        private final Path root;
        FakeProject(Path root) { this.root = root; }
        @Override public Path getRoot() { return root; }
        @Override public boolean isGitHubRepo() { return true; }
    }

    private static class TestContextManager extends ContextManager {
        private final List<ContextFragment> added = new ArrayList<>();
        public TestContextManager(IProject project) {
            super(project);
        }
        public List<ContextFragment> getAdded() {
            return added;
        }
        @Override public void addVirtualFragment(ContextFragment.VirtualFragment fragment) {
            added.add(fragment);
        }
        @Override public void addVirtualFragments(java.util.Collection<? extends ContextFragment.VirtualFragment> fragments) {
            added.addAll(fragments);
        }
    }

    private static class FakeIssueService implements IssueService {
        private final List<IssueHeader> headers;
        private final IssueDetails details;
        FakeIssueService(List<IssueHeader> headers, IssueDetails details) {
            this.headers = headers;
            this.details = details;
        }
        @Override public List<IssueHeader> listIssues(FilterOptions filterOptions) {
            return headers;
        }
        @Override public IssueDetails loadDetails(String issueId) {
            return details;
        }
        @Override public OkHttpClient httpClient() { return new OkHttpClient(); }
        @Override public List<String> listAvailableStatuses() { return List.of(); }
    }

    @Test
    void testAddAllGithubIssuesAsFragment() {
        var repoName = "repo";
        var project = new FakeProject(Path.of("/tmp/" + repoName));
        var cm = new TestContextManager(project);

        var h1 = new IssueHeader("1", "First", "alice", new Date(), List.of(), List.of(), "Open", URI.create("https://example.com/1"));
        var h2 = new IssueHeader("2", "Second", "bob", new Date(), List.of("bug"), List.of("carol"), "Closed", URI.create("https://example.com/2"));
        var headers = List.of(h1, h2);
        var fakeDetails = new IssueDetails(h1, "ignored", List.of(), List.of());
        var service = new FakeIssueService(headers, fakeDetails);

        var tools = new GitIssueTools(cm);
        tools.setIssueServiceForTests(service);

        String result = tools.addAllGithubIssuesAsFragment("https://github.com/owner/" + repoName);

        assertTrue(result.contains("Added summary for 2 issues"));
        var added = cm.getAdded();
        assertEquals(1, added.size());
        var frag = added.getFirst();
        assertTrue(frag instanceof ContextFragment.StringFragment);
        var expected = IssueCaptureBuilder.buildCompactListMarkdown(headers, "owner", repoName);
        assertEquals(expected, frag.text());
        assertTrue(frag.description().contains("GitHub Issues: owner/" + repoName));
        assertEquals("md", frag.syntaxStyle());
    }

    @Test
    void testAddGithubIssueAsFragment_withComments() {
        var repoName = "repo";
        var project = new FakeProject(Path.of("/tmp/" + repoName));
        var cm = new TestContextManager(project);

        var header = new IssueHeader("123", "Fix bug", "alice", new Date(), List.of("bug"), List.of("bob"), "Open", URI.create("https://github.com/owner/" + repoName + "/issues/123"));
        var comments = List.of(
                new Comment("bob", "Looks good", new Date()),
                new Comment("carol", "   ", new Date()), // skipped
                new Comment("dave", "Ship it", new Date())
        );
        var details = new IssueDetails(header, "Main body", comments, List.of());
        var service = new FakeIssueService(List.of(header), details);

        var tools = new GitIssueTools(cm);
        tools.setIssueServiceForTests(service);

        String result = tools.addGithubIssueAsFragment("https://github.com/owner/" + repoName, "123");

        assertTrue(result.contains("Captured issue 123 (Fix bug) with 3 comment(s)."));

        var added = cm.getAdded();
        // Expect: one TaskFragment for issue body + one TaskFragment for non-empty comments
        assertEquals(2, added.size());
        assertTrue(added.get(0) instanceof ContextFragment.TaskFragment);
        assertTrue(added.get(1) instanceof ContextFragment.TaskFragment);

        var issueFrag = (ContextFragment.TaskFragment) added.get(0);
        var commentsFrag = (ContextFragment.TaskFragment) added.get(1);

        assertEquals("Issue 123: Fix bug", issueFrag.description());
        assertEquals("Issue 123: Comments", commentsFrag.description());

        // Issue text messages: single UserMessage
        List<ChatMessage> issueMsgs = issueFrag.messages();
        assertEquals(1, issueMsgs.size());
        var um = (UserMessage) issueMsgs.getFirst();
        assertEquals("alice", um.name());
        assertTrue(um.toString().contains("# Issue #123: Fix bug"));
        assertTrue(um.toString().contains("Main body"));

        // Comments messages: two non-empty
        List<ChatMessage> commentMsgs = commentsFrag.messages();
        assertEquals(2, commentMsgs.size());
        var c0 = (UserMessage) commentMsgs.get(0);
        var c1 = (UserMessage) commentMsgs.get(1);
        assertEquals("bob", c0.name());
        assertTrue(c0.toString().contains("Looks good"));
        assertEquals("dave", c1.name());
        assertTrue(c1.toString().contains("Ship it"));
    }
}
