package io.github.jbellis.brokk.issues;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.IssueProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JiraIssueServiceTest {

    private String jiraBaseUrl;
    private String jiraApiToken;

    @BeforeEach
    void setUp() {
        jiraBaseUrl = System.getenv("JIRA_TEST_BASE_URL");
        if (jiraBaseUrl == null) {
            // Default to Apache Jira if no specific test URL is provided via env var
            jiraBaseUrl = "https://issues.apache.org/jira";
        }
        jiraApiToken = System.getenv("JIRA_TEST_API_TOKEN");
        if (jiraApiToken == null) {
            // hardcode token here for tests
        }
    }

    private IProject createMockProject(final String projectKeyFromTest) {
        // Return an anonymous implementation of IProject
        return new IProject() {
            @Override
            public io.github.jbellis.brokk.IssueProvider getIssuesProvider() {
                var jiraConfig = new IssuesProviderConfig.JiraConfig(jiraBaseUrl, jiraApiToken, projectKeyFromTest);
                return new io.github.jbellis.brokk.IssueProvider(IssueProviderType.JIRA, jiraConfig);
            }

            @Override
            public Path getRoot() {
                return Path.of("mock-project-root");
            }

            // Other methods will use default implementations from IProject (often throwing UnsupportedOperationException)
        };
    }

    @Test
    void testListIssuesCassandraProject() throws Exception {
        // This test targets a Jira instance (defaulting to issues.apache.org/jira).
        // If JIRA_TEST_USER_EMAIL and JIRA_TEST_API_TOKEN are set,
        // it will attempt authenticated access. Otherwise, it will attempt unauthenticated access.
        // The Apache Jira API requires authentication even for publicly viewable projects.
        // If authentication is not provided or fails, the service is expected to return
        // an empty list of issues, and the assertion `assertFalse(issues.isEmpty())` will fail.
        IProject mockProject = createMockProject("CASSANDRA");
        JiraIssueService service = new JiraIssueService(mockProject);

        // Default: null status (all), "Unresolved" resolution, others null for API call, query null
        JiraFilterOptions filterOptions = new JiraFilterOptions(null, "Unresolved", null, null, null, null);

        List<IssueHeader> issues = service.listIssues(filterOptions);

        assertNotNull(issues, "The returned list of issues should not be null.");
        // This assertion will fail if authentication is not successful, as an empty list will be returned.
        // This is an expected failure mode when running without proper credentials.
        assertFalse(issues.isEmpty(), "Expected to find issues for the CASSANDRA project. This will fail if unauthenticated or if the project has no issues.");

        for (IssueHeader issue : issues) {
            assertNotNull(issue.id(), "Issue ID should not be null.");
            assertTrue(issue.id().startsWith("CASSANDRA-"),
                    "Issue ID '" + issue.id() + "' should start with 'CASSANDRA-'.");
            assertNotNull(issue.title(), "Issue title should not be null for ID: " + issue.id());
            assertNotNull(issue.status(), "Issue status should not be null for ID: " + issue.id());
            assertNotNull(issue.htmlUrl(), "Issue HTML URL should not be null for ID: " + issue.id());
            // Author and updated date can sometimes be null or complex depending on Jira config/issue type
            // So, only basic non-null checks for core fields.
        }
        System.out.println("Successfully fetched " + issues.size() + " issues for CASSANDRA project.");
    }

    @Test
    void testLoadDetailsCassandraIssue() throws Exception {
        // This test targets a Jira instance (defaulting to issues.apache.org/jira)
        // and attempts to load details for a specific issue.
        // Authentication requirements are similar to testListIssuesCassandraProject.
        IProject mockProject = createMockProject("CASSANDRA");
        JiraIssueService service = new JiraIssueService(mockProject);

        String issueIdToLoad = "CASSANDRA-18464";

        IssueDetails details = service.loadDetails(issueIdToLoad);

        assertNotNull(details, "IssueDetails should not be null for " + issueIdToLoad);

        // Verify IssueHeader
        IssueHeader header = details.header();
        assertNotNull(header, "IssueHeader within IssueDetails should not be null.");
        assertEquals(issueIdToLoad, header.id(), "Issue ID in header does not match requested ID.");
        assertNotNull(header.title(), "Issue title should not be null.");
        assertFalse(header.title().isBlank(), "Issue title should not be blank.");
        assertNotNull(header.status(), "Issue status should not be null.");
        assertNotNull(header.htmlUrl(), "Issue HTML URL should not be null.");
        // Author and updated date can be complex; basic non-null checks are done by mapToIssueHeader implicitly.

        // Verify markdownBody (rendered description from Jira)
        // Depending on Jira's response, this might be HTML.
        assertNotNull(details.markdownBody(), "Markdown body (rendered description) should not be null.");

        // Verify comments
        assertNotNull(details.comments(), "Comments list should not be null (can be empty).");
        // Further checks on comment content could be added if a specific comment is guaranteed to exist.
        // For CASSANDRA-1, it's likely to have comments.
        if (!details.comments().isEmpty()) {
            Comment firstComment = details.comments().get(0);
            assertNotNull(firstComment.author(), "First comment's author should not be null.");
            assertNotNull(firstComment.markdownBody(), "First comment's body should not be null.");
            assertNotNull(firstComment.created(), "First comment's creation date should not be null.");
        }


        // Verify attachmentUrls
        assertNotNull(details.attachmentUrls(), "Attachment URLs list should not be null (can be empty).");
        // CASSANDRA-1 might not have image attachments directly in description/comments that are parsed as such.
        // The service specifically looks for image mime types in Jira's attachment section.

        System.out.println("Successfully loaded details for issue " + issueIdToLoad + " from CASSANDRA project.");
        System.out.println("Title: " + header.title());
        System.out.println("Body (first 100 chars): " + (details.markdownBody().length() > 100 ? details.markdownBody().substring(0, 100) + "..." : details.markdownBody()));
        System.out.println("Number of comments: " + details.comments().size());
        System.out.println("Number of attachment URLs: " + details.attachmentUrls().size());
    }

    @Test
    void testListIssuesWithStatusFilter() throws Exception {
        // This test targets a Jira instance and filters by a specific status.
        // Authentication requirements are similar to testListIssuesCassandraProject.
        IProject mockProject = createMockProject("CASSANDRA");
        JiraIssueService service = new JiraIssueService(mockProject);

        String targetStatus = "Open"; // Or "Resolved", "Closed" if "Open" yields no results for CASSANDRA
        // Filter by targetStatus, "Unresolved" resolution, others null, query null
        JiraFilterOptions filterOptions = new JiraFilterOptions(targetStatus, "Unresolved", null, null, null, null);

        List<IssueHeader> issues = service.listIssues(filterOptions);

        assertNotNull(issues, "The returned list of issues should not be null.");
        // This assertion can fail if no issues match the status, or due to authentication/project changes.
        assertFalse(issues.isEmpty(), "Expected to find issues for the CASSANDRA project with status '" + targetStatus +
                                      "'. This might fail if no such issues exist or due to authentication.");

        for (IssueHeader issue : issues) {
            assertNotNull(issue.status(), "Issue status should not be null for ID: " + issue.id());
            assertTrue(targetStatus.equalsIgnoreCase(issue.status()),
                    "Issue " + issue.id() + " has status '" + issue.status() + "' but expected '" + targetStatus + "'.");
            // Also verify core fields for each issue found
            assertNotNull(issue.id(), "Issue ID should not be null.");
            assertTrue(issue.id().startsWith("CASSANDRA-"),
                    "Issue ID '" + issue.id() + "' should start with 'CASSANDRA-'.");
            assertNotNull(issue.title(), "Issue title should not be null for ID: " + issue.id());
            assertNotNull(issue.htmlUrl(), "Issue HTML URL should not be null for ID: " + issue.id());
        }

        System.out.println("Successfully fetched " + issues.size() + " issues with status '" + targetStatus + "' for CASSANDRA project.");
    }
}
