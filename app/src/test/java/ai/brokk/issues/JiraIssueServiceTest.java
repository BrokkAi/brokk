package ai.brokk.issues;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.brokk.IProject;
import ai.brokk.IssueProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
            // Use empty string for unauthenticated tests instead of null
            // or hardcode your Jira API token here for authenticated tests
            jiraApiToken = "";
        }
    }

    private IProject createMockProject(final String projectKeyFromTest) {
        // Return an anonymous implementation of IProject
        return new IProject() {
            @Override
            public IssueProvider getIssuesProvider() {
                var jiraConfig = new IssuesProviderConfig.JiraConfig(jiraBaseUrl, jiraApiToken, projectKeyFromTest);
                return new IssueProvider(IssueProviderType.JIRA, jiraConfig);
            }

            @Override
            public Path getRoot() {
                return Path.of("mock-project-root");
            }

            // Other methods will use default implementations from IProject (often throwing
            // UnsupportedOperationException)
        };
    }

    @Test
    void testListIssuesCassandraProject() throws Exception {
        // This test targets a Jira instance (defaulting to issues.apache.org/jira).
        // If JIRA_TEST_USER_EMAIL and JIRA_TEST_API_TOKEN are set,
        // it will attempt authenticated access. Otherwise, it will attempt unauthenticated access.
        IProject mockProject = createMockProject("CASSANDRA");
        JiraIssueService service = new JiraIssueService(mockProject);

        // Default: null status (all), "Unresolved" resolution, others null for API call, query null
        JiraFilterOptions filterOptions = new JiraFilterOptions(null, "Unresolved", null, null, null, null);

        List<IssueHeader> issues = service.listIssues(filterOptions);

        assertNotNull(issues, "The returned list of issues should not be null.");

        // Skip test if empty list is returned. JiraIssueService returns empty list on IOException
        // (network errors), but also on auth failures or legitimately empty results.
        // This is acceptable for integration tests - we prefer skipping over flaky failures.
        // Note: This means auth/API issues may also be silently skipped rather than caught.
        if (issues.isEmpty()) {
            assumeTrue(
                    false, "Skipping test - empty result (network unavailable, auth failure, or no matching issues)");
            return;
        }

        // If we reach here, we got results - validate them
        for (IssueHeader issue : issues) {
            assertNotNull(issue.id(), "Issue ID should not be null.");
            assertTrue(
                    issue.id().startsWith("CASSANDRA-"),
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
        IProject mockProject = createMockProject("CASSANDRA");
        JiraIssueService service = new JiraIssueService(mockProject);

        String issueIdToLoad = "CASSANDRA-18464";

        IssueDetails details;
        try {
            details = service.loadDetails(issueIdToLoad);
        } catch (IOException e) {
            // JiraIssueService.loadDetails() throws IOException on network errors.
            // Skip test instead of failing to avoid flaky test failures.
            // Note: This will also skip on auth failures or API errors, which is acceptable
            // for integration tests that depend on external services.
            assumeTrue(false, "Skipping test due to IOException (network unavailable or API error): " + e.getMessage());
            return;
        }

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
            Comment firstComment = details.comments().getFirst();
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
        System.out.println("Body (first 100 chars): "
                + (details.markdownBody().length() > 100
                        ? details.markdownBody().substring(0, 100) + "..."
                        : details.markdownBody()));
        System.out.println("Number of comments: " + details.comments().size());
        System.out.println(
                "Number of attachment URLs: " + details.attachmentUrls().size());
    }

    @Test
    void testListIssuesWithStatusFilter() throws Exception {
        // This test targets a Jira instance and filters by a specific status.
        IProject mockProject = createMockProject("CASSANDRA");
        JiraIssueService service = new JiraIssueService(mockProject);

        String targetStatus = "Open"; // Or "Resolved", "Closed" if "Open" yields no results for CASSANDRA
        // Filter by targetStatus, "Unresolved" resolution, others null, query null
        JiraFilterOptions filterOptions = new JiraFilterOptions(targetStatus, "Unresolved", null, null, null, null);

        List<IssueHeader> issues = service.listIssues(filterOptions);

        assertNotNull(issues, "The returned list of issues should not be null.");

        // Skip test if empty list is returned. JiraIssueService returns empty list on IOException
        // (network errors), but also on auth failures or legitimately empty results.
        // This is acceptable for integration tests - we prefer skipping over flaky failures.
        // Note: This means auth/API issues may also be silently skipped rather than caught.
        if (issues.isEmpty()) {
            assumeTrue(
                    false, "Skipping test - empty result (network unavailable, auth failure, or no matching issues)");
            return;
        }

        // If we reach here, we got results - validate them
        for (IssueHeader issue : issues) {
            assertNotNull(issue.status(), "Issue status should not be null for ID: " + issue.id());
            assertTrue(
                    targetStatus.equalsIgnoreCase(issue.status()),
                    "Issue " + issue.id() + " has status '" + issue.status() + "' but expected '" + targetStatus
                            + "'.");
            // Also verify core fields for each issue found
            assertNotNull(issue.id(), "Issue ID should not be null.");
            assertTrue(
                    issue.id().startsWith("CASSANDRA-"),
                    "Issue ID '" + issue.id() + "' should start with 'CASSANDRA-'.");
            assertNotNull(issue.title(), "Issue title should not be null for ID: " + issue.id());
            assertNotNull(issue.htmlUrl(), "Issue HTML URL should not be null for ID: " + issue.id());
        }

        System.out.println("Successfully fetched " + issues.size() + " issues with status '" + targetStatus
                + "' for CASSANDRA project.");
    }
}
