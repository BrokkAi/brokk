package io.github.jbellis.brokk.issues;

import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.util.MarkdownImageParser;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import com.google.common.collect.ImmutableList;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHUser;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class GitHubIssueService implements IssueService {
    private static final Logger logger = LogManager.getLogger(GitHubIssueService.class);

    private final IProject project;

    public GitHubIssueService(IProject project) {
        this.project = project;
    }

    private GitHubAuth getAuth() throws IOException {
        return GitHubAuth.getOrCreateInstance(this.project);
    }

    @Override
    public OkHttpClient httpClient() throws IOException {
        return getAuth().authenticatedClient();
    }

    @Override
    public List<IssueHeader> listIssues(FilterOptions rawFilterOptions) throws IOException {
        if (!(rawFilterOptions instanceof GitHubFilterOptions filterOptions)) {
            throw new IllegalArgumentException("GitHubIssueService requires GitHubFilterOptions, got " + rawFilterOptions.getClass().getName());
        }

        String queryText = filterOptions.query();

        if (queryText != null && !queryText.isBlank()) {
            logger.debug("Using GitHub Search API for query: '{}', options: {}", queryText, filterOptions);
            String fullQuery = buildGitHubSearchQuery(filterOptions, getAuth().getOwner(), getAuth().getRepoName());
            List<GHIssue> searchResults = new ArrayList<>();
            try {
                for (GHIssue issue : getAuth().getGitHub().searchIssues().q(fullQuery).list()) {
                    if (!issue.isPullRequest()) { // "is:issue" in query should handle this, but double-check
                        searchResults.add(issue);
                    }
                }
                logger.debug("GitHub Search API returned {} results for query: {}", searchResults.size(), fullQuery);
            } catch (IOException e) {
                logger.error("IOException during GitHub search API call for query [{}]: {}", fullQuery, e.getMessage(), e);
                throw e;
            }
            return searchResults.stream()
                    .map(this::mapToIssueHeader)
                    .filter((@Nullable var h) -> h != null)
                    .collect(Collectors.toList());
        } else {
            logger.debug("No search query. Using standard listIssues with client-side filters. Options: {}", filterOptions);
            GHIssueState apiState;
            String status = filterOptions.status();
            if (status == null || status.equalsIgnoreCase("ALL") || status.isBlank()) {
                apiState = GHIssueState.ALL;
            } else {
                apiState = switch (status.toUpperCase(Locale.ROOT)) {
                    case "OPEN" -> GHIssueState.OPEN;
                    case "CLOSED" -> GHIssueState.CLOSED;
                    default -> {
                        logger.warn("Unrecognized status filter '{}', defaulting to ALL.", status);
                        yield GHIssueState.ALL;
                    }
                };
            }

            List<GHIssue> fetchedIssues = getAuth().listIssues(apiState);

            return fetchedIssues.stream()
                    .filter(ghIssue -> !ghIssue.isPullRequest())
                    .filter(ghIssue -> matchesAuthor(ghIssue, filterOptions.author()))
                    .filter(ghIssue -> matchesLabel(ghIssue, filterOptions.label()))
                    .filter(ghIssue -> matchesAssignee(ghIssue, filterOptions.assignee()))
                    .map(this::mapToIssueHeader)
                    .filter((@Nullable var h) -> h != null)
                    .collect(Collectors.toList());
        }
    }

    private String buildGitHubSearchQuery(GitHubFilterOptions options, String owner, String repoName) {
        StringBuilder q = new StringBuilder();
        if (options.query() != null && !options.query().isBlank()) {
            q.append(options.query().trim());
        }

        q.append(" repo:").append(owner).append("/").append(repoName);
        q.append(" is:issue");

        if (options.status() != null && !options.status().equalsIgnoreCase("ALL") && !options.status().isBlank()) {
            q.append(" state:").append(options.status().trim().toLowerCase(Locale.ROOT));
        }
        if (options.author() != null && !options.author().isBlank()) {
            q.append(" author:").append(options.author().trim());
        }
        if (options.label() != null && !options.label().isBlank()) {
            q.append(" label:\"").append(options.label().trim()).append("\"");
        }
        if (options.assignee() != null && !options.assignee().isBlank()) {
            q.append(" assignee:").append(options.assignee().trim());
        }
        logger.trace("Constructed GitHub search query: [{}]", q.toString());
        return q.toString();
    }

    @Override
    public IssueDetails loadDetails(String issueId) throws IOException {
        if (issueId.isBlank()) {
            throw new IOException("Issue ID cannot be null or blank.");
        }
        String numericIdStr = issueId.startsWith("#") ? issueId.substring(1) : issueId;
        int numericId;
        try {
            numericId = Integer.parseInt(numericIdStr);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid issue ID format: " + issueId + ". Must be a number, optionally prefixed with '#'.", e);
        }

        GHIssue ghIssue = getAuth().getIssue(numericId);
        IssueHeader header = mapToIssueHeader(ghIssue);
        if (header == null) {
            // mapToIssueHeader logs specific errors, this is a general fallback.
            throw new IOException("Failed to map GitHub issue #" + numericId + " to IssueHeader.");
        }

        String markdownBody = ghIssue.getBody() == null ? "" : ghIssue.getBody();
        List<GHIssueComment> ghComments;
        try {
            ghComments = ghIssue.getComments();
        } catch (IOException e) {
            logger.error("Failed to fetch comments for issue #{}: {}", numericId, e.getMessage(), e);
            ghComments = Collections.emptyList(); // Proceed with empty comments if fetching fails
        }

        List<Comment> comments = mapToComments(ghComments);
        List<URI> attachmentUrls = extractAttachmentUrls(markdownBody, comments);

        return new IssueDetails(header, markdownBody, comments, attachmentUrls);
    }

    private @Nullable IssueHeader mapToIssueHeader(GHIssue ghIssue) {
        try {
            String id = "#" + ghIssue.getNumber();
            String title = ghIssue.getTitle();
            String author = getAuthorLogin(ghIssue.getUser()); // Handles potential IOException for getUser
            Date updated = ghIssue.getUpdatedAt(); // Can throw IOException
            List<String> labels = ghIssue.getLabels().stream().map(GHLabel::getName).collect(Collectors.toList());
            List<String> assignees = ghIssue.getAssignees().stream().map(this::getAuthorLogin).collect(Collectors.toList());
            String status = ghIssue.getState().toString();
            URI htmlUrl = ghIssue.getHtmlUrl().toURI(); // Can throw URISyntaxException

            return new IssueHeader(id, title, author, updated, labels, assignees, status, htmlUrl);
        } catch (IOException e) {
            logger.error("IOException mapping GHIssue #{} to IssueHeader: {}", ghIssue.getNumber(), e.getMessage(), e);
            return null; // Or throw a wrapped exception if IssueHeader is critical
        } catch (URISyntaxException e) {
            logger.error("URISyntaxException for GHIssue #{} URL ({}): {}", ghIssue.getNumber(), ghIssue.getHtmlUrl(), e.getMessage(), e);
            return null;
        } catch (NullPointerException e) {
            logger.error("NullPointerException during mapping of GHIssue #{}, possibly due to missing core fields like getHtmlUrl() returning null: {}", ghIssue.getNumber(), e.getMessage(), e);
            return null;
        }
    }

    private String getAuthorLogin(GHUser user) {
        try {
            // GHUser.getLogin() itself does not throw IOException.
            // The `user` object might have been obtained via a call that threw (e.g., ghIssue.getUser()).
            // That IOException would be caught where ghIssue.getUser() is called.
            String login = user.getLogin();
            return (login != null && !login.isBlank()) ? login : "N/A";
        } catch (Exception e) { // Catching generic Exception as a safeguard for unexpected issues with the user object
            logger.warn("Unexpected error retrieving login for user object (ID: {}): {}. Defaulting to 'N/A'.", user.getId(), e.getMessage());
            return "N/A";
        }
    }

    @Override
    public List<String> listAvailableStatuses() {
        // GitHub issues primarily use "Open" and "Closed" states.
        return List.of("Open", "Closed");
    }

    private ImmutableList<Comment> mapToComments(List<GHIssueComment> ghComments) {
        var builder = ImmutableList.<Comment>builder();
        for (GHIssueComment gc : ghComments) {
            try {
                String author = getAuthorLogin(gc.getUser()); // Handles potential IOException for getUser
                String body = gc.getBody() == null ? "" : gc.getBody();
                Date created = gc.getCreatedAt(); // Can throw IOException
                builder.add(new Comment(author, body, created));
            } catch (IOException e) {
                logger.warn("IOException mapping GHIssueComment ID {} to Comment DTO: {}", gc.getId(), e.getMessage(), e);
                // Skip this comment or add with default values
            }
        }
        return builder.build();
    }

    private List<URI> extractAttachmentUrls(String issueBodyMarkdown, List<Comment> dtoComments) {
        Set<String> allImageUrls = new LinkedHashSet<>();
        if (!issueBodyMarkdown.isBlank()) {
            allImageUrls.addAll(MarkdownImageParser.extractImageUrls(issueBodyMarkdown));
        }
        for (Comment comment : dtoComments) {
            if (!comment.markdownBody().isBlank()) {
                allImageUrls.addAll(MarkdownImageParser.extractImageUrls(comment.markdownBody()));
            }
        }

        return allImageUrls.stream()
                .map(urlString -> {
                    try {
                        return new URI(urlString);
                    } catch (URISyntaxException e) {
                        logger.warn("Invalid URI syntax for attachment URL: '{}'. Skipping. Error: {}", urlString, e.getMessage());
                        return null;
                    }
                })
                .filter((@Nullable var u) -> u != null)
                .collect(Collectors.toList());
    }

    private boolean matchesAuthor(GHIssue issue, @Nullable String authorFilter) {
        if (authorFilter == null || authorFilter.isBlank()) {
            return true;
        }
        try {
            return authorFilter.equals(getAuthorLogin(issue.getUser()));
        } catch (Exception e) { // Includes IOException from issue.getUser() via getAuthorLogin's path
            logger.warn("Failed to get author for issue #{} during filter, treating as no match: {}", issue.getNumber(), e.getMessage());
            return false;
        }
    }

    private boolean matchesLabel(GHIssue issue, @Nullable String labelFilter) {
        if (labelFilter == null || labelFilter.isBlank()) {
            return true;
        }
        // GHLabel.getName() and issue.getLabels() do not typically throw IOException once the GHIssue is fetched.
        return issue.getLabels().stream().anyMatch(label -> labelFilter.equals(label.getName()));
    }

    private boolean matchesAssignee(GHIssue issue, @Nullable String assigneeFilter) {
        if (assigneeFilter == null || assigneeFilter.isBlank()) {
            return true;
        }
        // GHUser.getLogin() for assignees.
        return issue.getAssignees().stream().anyMatch(assignee -> {
            try {
                return assigneeFilter.equals(getAuthorLogin(assignee));
            } catch (Exception e) { // Includes IOException if getAuthorLogin path for assignee throws
                 logger.warn("Failed to get assignee login for issue #{} during filter, treating as no match for this assignee: {}", issue.getNumber(), e.getMessage());
                return false;
            }
        });
    }
}
