package ai.brokk.issues;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.GitHubAuth;
import ai.brokk.IssueProvider;
import ai.brokk.project.IProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.Queue;
import okhttp3.*;
import okio.Timeout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GitHubIssueServiceTest {

    private TestableGitHubIssueService service;
    private MockOkHttpClient mockHttpClient;

    @BeforeEach
    void setUp() {
        IProject mockProject = new IProject() {
            @Override
            public IssueProvider getIssuesProvider() {
                return null;
            }

            @Override
            public Path getRoot() {
                return Path.of("mock-root");
            }
        };
        mockHttpClient = new MockOkHttpClient();
        service = new TestableGitHubIssueService(mockProject, mockHttpClient);
    }

    @Test
    void testLoadDetailsPagination() throws IOException {
        // Setup responses
        String response1 = createGraphQLResponse(true, "cursor1", "Comment 1", "author1");
        String response2 = createGraphQLResponse(false, "cursor2", "Comment 2", "author2");

        mockHttpClient.addResponse(response1);
        mockHttpClient.addResponse(response2);

        // Act
        IssueDetails details = service.loadDetails("123");

        // Assert
        assertNotNull(details);
        assertEquals("#123", details.header().id());
        assertEquals("Test Issue Title", details.header().title());
        assertEquals(2, details.comments().size());

        assertEquals("Comment 1", details.comments().get(0).markdownBody());
        assertEquals("author1", details.comments().get(0).author());

        assertEquals("Comment 2", details.comments().get(1).markdownBody());
        assertEquals("author2", details.comments().get(1).author());
    }

    @Test
    void testLoadDetailsFiltersMinimizedComments() throws IOException {
        String response1 = createGraphQLResponseWithMinimizedComment();
        mockHttpClient.addResponse(response1);

        IssueDetails details = service.loadDetails("456");

        assertNotNull(details);
        assertEquals("#456", details.header().id());
        assertEquals(1, details.comments().size());
        assertEquals("Visible comment", details.comments().get(0).markdownBody());
        assertEquals("visible-author", details.comments().get(0).author());
    }

    private String createGraphQLResponse(boolean hasNextPage, String endCursor, String commentBody, String author) {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode data = root.putObject("data");
        ObjectNode repository = data.putObject("repository");
        ObjectNode issue = repository.putObject("issue");

        issue.put("number", 123);
        issue.put("title", "Test Issue Title");
        issue.put("url", "https://github.com/test-owner/test-repo/issues/123");
        issue.put("state", "OPEN");
        issue.put("body", "Issue Body");
        issue.put("bodyHTML", "<p>Issue Body</p>");
        issue.put("createdAt", "2023-01-01T00:00:00Z");
        issue.put("updatedAt", "2023-01-01T00:00:00Z");

        ObjectNode issueAuthor = issue.putObject("author");
        issueAuthor.put("login", "issue-author");

        issue.putObject("assignees").putArray("nodes");
        issue.putObject("labels").putArray("nodes");

        ObjectNode comments = issue.putObject("comments");
        ObjectNode pageInfo = comments.putObject("pageInfo");
        pageInfo.put("hasNextPage", hasNextPage);
        if (endCursor != null) {
            pageInfo.put("endCursor", endCursor);
        }

        ArrayNode nodes = comments.putArray("nodes");
        ObjectNode comment = nodes.addObject();
        comment.put("body", commentBody);
        comment.put("bodyHTML", "<p>" + commentBody + "</p>");
        comment.put("createdAt", "2023-01-02T00:00:00Z");
        comment.put("isMinimized", false);
        ObjectNode commentAuthor = comment.putObject("author");
        commentAuthor.put("login", author);

        return root.toString();
    }

    private String createGraphQLResponseWithMinimizedComment() {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode data = root.putObject("data");
        ObjectNode repository = data.putObject("repository");
        ObjectNode issue = repository.putObject("issue");

        issue.put("number", 456);
        issue.put("title", "Test Issue With Minimized Comments");
        issue.put("url", "https://github.com/test-owner/test-repo/issues/456");
        issue.put("state", "OPEN");
        issue.put("body", "Issue Body");
        issue.put("bodyHTML", "<p>Issue Body</p>");
        issue.put("createdAt", "2023-01-01T00:00:00Z");
        issue.put("updatedAt", "2023-01-01T00:00:00Z");

        ObjectNode issueAuthor = issue.putObject("author");
        issueAuthor.put("login", "issue-author");

        issue.putObject("assignees").putArray("nodes");
        issue.putObject("labels").putArray("nodes");

        ObjectNode comments = issue.putObject("comments");
        ObjectNode pageInfo = comments.putObject("pageInfo");
        pageInfo.put("hasNextPage", false);

        ArrayNode nodes = comments.putArray("nodes");

        ObjectNode visibleComment = nodes.addObject();
        visibleComment.put("body", "Visible comment");
        visibleComment.put("bodyHTML", "<p>Visible comment</p>");
        visibleComment.put("createdAt", "2023-01-02T00:00:00Z");
        visibleComment.put("isMinimized", false);
        ObjectNode visibleAuthor = visibleComment.putObject("author");
        visibleAuthor.put("login", "visible-author");

        ObjectNode minimizedComment = nodes.addObject();
        minimizedComment.put("body", "Spam comment");
        minimizedComment.put("bodyHTML", "<p>Spam comment</p>");
        minimizedComment.put("createdAt", "2023-01-03T00:00:00Z");
        minimizedComment.put("isMinimized", true);
        ObjectNode minimizedAuthor = minimizedComment.putObject("author");
        minimizedAuthor.put("login", "spammer");

        return root.toString();
    }

    // --- Manual Mocks ---

    static class TestableGitHubIssueService extends GitHubIssueService {
        private final MockOkHttpClient mockClient;

        public TestableGitHubIssueService(IProject project, MockOkHttpClient mockClient) {
            super(project, new GitHubAuth("test-owner", "test-repo", null));
            this.mockClient = mockClient;
        }

        @Override
        public OkHttpClient httpClient() {
            return mockClient;
        }

        @Override
        protected boolean isTokenPresent() {
            return true; // Force GraphQL path
        }
    }

    static class MockOkHttpClient extends OkHttpClient {
        private final Queue<String> responses = new LinkedList<>();

        void addResponse(String jsonBody) {
            responses.add(jsonBody);
        }

        @Override
        public Call newCall(Request request) {
            String body = responses.poll();
            if (body == null) {
                throw new IllegalStateException("No more mock responses configured!");
            }
            return new MockCall(request, body);
        }
    }

    static class MockCall implements Call {
        private final Request request;
        private final String responseBody;

        MockCall(Request request, String responseBody) {
            this.request = request;
            this.responseBody = responseBody;
        }

        @Override
        public Response execute() {
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(responseBody, MediaType.get("application/json")))
                    .build();
        }

        @Override
        public void enqueue(Callback responseCallback) {}

        @Override
        public void cancel() {}

        @Override
        public boolean isExecuted() {
            return true;
        }

        @Override
        public boolean isCanceled() {
            return false;
        }

        @Override
        public Timeout timeout() {
            return Timeout.NONE;
        }

        @Override
        public Call clone() {
            return this;
        }

        @Override
        public Request request() {
            return request;
        }
    }
}
