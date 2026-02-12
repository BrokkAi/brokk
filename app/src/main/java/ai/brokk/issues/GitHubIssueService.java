package ai.brokk.issues;

import ai.brokk.GitHubAuth;
import ai.brokk.gui.GfmRenderer;
import ai.brokk.gui.util.StreamingPaginationHelper;
import ai.brokk.project.IProject;
import ai.brokk.util.HtmlUtil;
import ai.brokk.util.MarkupImageParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.PagedIterator;

public class GitHubIssueService implements IssueService {
    private static final Logger logger = LogManager.getLogger(GitHubIssueService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final IProject project;
    private final GfmRenderer gfmRenderer;
    private final @Nullable GitHubAuth injectedAuth;

    public GitHubIssueService(IProject project) {
        this(project, null);
    }

    public GitHubIssueService(IProject project, @Nullable GitHubAuth auth) {
        this.project = project;
        this.gfmRenderer = new GfmRenderer();
        this.injectedAuth = auth;
    }

    protected GitHubAuth getAuth() throws IOException {
        GitHubAuth auth = injectedAuth;
        if (auth != null) {
            return auth;
        }
        return GitHubAuth.getOrCreateInstance(this.project);
    }

    @Override
    public OkHttpClient httpClient() throws IOException {
        return getAuth().authenticatedClient();
    }

    @Override
    public IssueHeader createIssue(String title, String bodyMarkdown) throws IOException {
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }

        var repo = getAuth().getGhRepository();
        var builder = repo.createIssue(title.strip());

        if (!bodyMarkdown.isBlank()) {
            builder.body(bodyMarkdown);
        }

        GHIssue created = builder.create();

        // Inform operators that an issue was successfully created, including repo and issue number/url.
        logger.info(
                "GitHubIssueService created issue #{} in {} (url={})",
                created.getNumber(),
                repo.getFullName(),
                created.getHtmlUrl());

        IssueHeader header = mapToIssueHeader(created);
        if (header == null) {
            throw new IOException(
                    "Failed to map created GitHub issue to IssueHeader (issue #" + created.getNumber() + ")");
        }
        return header;
    }

    @Override
    public List<IssueHeader> listIssues(FilterOptions rawFilterOptions) throws IOException {
        if (!(rawFilterOptions instanceof GitHubFilterOptions filterOptions)) {
            throw new IllegalArgumentException("GitHubIssueService requires GitHubFilterOptions, got "
                    + rawFilterOptions.getClass().getName());
        }

        String queryText = filterOptions.query();

        if (queryText != null && !queryText.isBlank()) {
            logger.debug("Using GitHub Search API for query: '{}', options: {}", queryText, filterOptions);
            String fullQuery = buildGitHubSearchQuery(
                    filterOptions, getAuth().getOwner(), getAuth().getRepoName());
            List<GHIssue> searchResults = new ArrayList<>();
            try {
                for (GHIssue issue :
                        getAuth().getGitHub().searchIssues().q(fullQuery).list()) {
                    if (!issue.isPullRequest()) { // "is:issue" in query should handle this, but double-check
                        searchResults.add(issue);
                    }
                }
                logger.debug("GitHub Search API returned {} results for query: {}", searchResults.size(), fullQuery);
            } catch (IOException e) {
                logger.error(
                        "IOException during GitHub search API call for query [{}]: {}", fullQuery, e.getMessage(), e);
                throw e;
            }
            return searchResults.stream()
                    .map(this::mapToIssueHeader)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } else {
            logger.debug(
                    "No search query. Using standard listIssues with client-side filters. Options: {}", filterOptions);
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
                    .filter(Objects::nonNull)
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

        if (options.status() != null
                && !options.status().equalsIgnoreCase("ALL")
                && !options.status().isBlank()) {
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
            throw new IOException(
                    "Invalid issue ID format: " + issueId + ". Must be a number, optionally prefixed with '#'.", e);
        }

        if (isTokenPresent()) {
            return loadDetailsFromGraphQL(numericId);
        } else {
            return loadDetailsFromRest(numericId);
        }
    }

    protected boolean isTokenPresent() {
        return GitHubAuth.tokenPresent();
    }

    private IssueDetails loadDetailsFromRest(int issueNumber) throws IOException {
        GHIssue issue = getAuth().getIssue(issueNumber);
        IssueHeader header = mapToIssueHeader(issue);
        if (header == null) {
            throw new IOException("Failed to map issue header for issue #" + issueNumber);
        }

        String body = issue.getBody();
        if (body == null) {
            body = "";
        }
        String htmlBody = HtmlUtil.sanitize(gfmRenderer.render(body));

        Set<String> allImageUrls = new LinkedHashSet<>();
        if (!body.isBlank()) {
            allImageUrls.addAll(MarkupImageParser.extractImageUrls(body));
        }

        List<Comment> comments = new ArrayList<>();
        List<GHIssueComment> ghComments = issue.getComments();
        for (GHIssueComment ghComment : ghComments) {
            String cBody = ghComment.getBody();
            if (cBody == null) {
                cBody = "";
            }

            if (!cBody.isBlank()) {
                allImageUrls.addAll(MarkupImageParser.extractImageUrls(cBody));
            }

            String cAuthor;
            try {
                cAuthor = getAuthorLogin(ghComment.getUser());
            } catch (IOException e) {
                logger.warn("Failed to get author for comment on issue #{}", issueNumber, e);
                cAuthor = "N/A";
            }

            Date createdAtDate = ghComment.getCreatedAt();
            Instant cCreated = createdAtDate != null ? createdAtDate.toInstant() : null;

            comments.add(new Comment(cAuthor, cBody, cCreated));
        }

        List<URI> attachmentUrls = mapToUri(allImageUrls);

        return new IssueDetails(header, body, htmlBody, comments, attachmentUrls);
    }

    private IssueDetails loadDetailsFromGraphQL(int issueNumber) throws IOException {
        String query =
                """
                query($owner: String!, $repo: String!, $number: Int!, $cursor: String) {
                  repository(owner: $owner, name: $repo) {
                    issue(number: $number) {
                      number
                      title
                      url
                      state
                      body
                      bodyHTML
                      createdAt
                      updatedAt
                      author { login }
                      assignees(first: 100) { nodes { login } }
                      labels(first: 100) { nodes { name } }
                      comments(first: 100, after: $cursor) {
                        pageInfo {
                          hasNextPage
                          endCursor
                        }
                        nodes {
                          body
                          bodyHTML
                          createdAt
                          author { login }
                        }
                      }
                    }
                  }
                }
                """;

        String owner = getAuth().getOwner();
        String repo = getAuth().getRepoName();
        String cursor = null;
        boolean hasNextPage = true;

        IssueHeader header = null;
        String body = "";
        String htmlBody = "";
        List<Comment> comments = new ArrayList<>();
        Set<String> allImageUrls = new LinkedHashSet<>();

        while (hasNextPage) {
            ObjectNode variables = objectMapper.createObjectNode();
            variables.put("owner", owner);
            variables.put("repo", repo);
            variables.put("number", issueNumber);
            variables.put("cursor", cursor);

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("query", query);
            requestBody.set("variables", variables);

            Request.Builder requestBuilder = new Request.Builder()
                    .url("https://api.github.com/graphql")
                    .post(RequestBody.create(
                            objectMapper.writeValueAsString(requestBody), MediaType.get("application/json")));

            try (Response response =
                    httpClient().newCall(requestBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("GraphQL query failed: " + response.code() + " " + response.message());
                }
                if (response.body() == null) {
                    throw new IOException("GraphQL query returned empty body");
                }
                JsonNode root = objectMapper.readTree(response.body().byteStream());
                if (root.has("errors")) {
                    throw new IOException(
                            "GraphQL errors: " + root.get("errors").toString());
                }

                JsonNode issueNode = root.path("data").path("repository").path("issue");
                if (issueNode.isMissingNode() || issueNode.isNull()) {
                    throw new IOException("Issue #" + issueNumber + " not found or access denied.");
                }

                if (header == null) {
                    String id = "#" + issueNode.path("number").asInt();
                    String title = issueNode.path("title").asText();
                    String urlStr = issueNode.path("url").asText();
                    String state = issueNode.path("state").asText();
                    body = issueNode.path("body").asText("");
                    htmlBody = HtmlUtil.sanitize(issueNode.path("bodyHTML").asText(""));

                    if (!htmlBody.isBlank()) {
                        allImageUrls.addAll(MarkupImageParser.extractImageUrls(htmlBody));
                    }

                    String author = issueNode.path("author").isMissingNode()
                                    || issueNode.path("author").isNull()
                            ? "N/A"
                            : issueNode.path("author").path("login").asText("N/A");

                    Instant updated = parseIsoDate(issueNode.path("updatedAt").asText(null));
                    URI htmlUrl;
                    try {
                        htmlUrl = new URI(urlStr);
                    } catch (URISyntaxException e) {
                        logger.warn("Invalid URI from GraphQL: {}", urlStr, e);
                        htmlUrl = null;
                    }

                    List<String> labels = new ArrayList<>();
                    issueNode
                            .path("labels")
                            .path("nodes")
                            .forEach(n -> labels.add(n.path("name").asText()));

                    List<String> assignees = new ArrayList<>();
                    issueNode
                            .path("assignees")
                            .path("nodes")
                            .forEach(n -> assignees.add(n.path("login").asText()));

                    header = new IssueHeader(id, title, author, updated, labels, assignees, state, htmlUrl);
                }

                JsonNode commentsSection = issueNode.path("comments");
                JsonNode commentsNode = commentsSection.path("nodes");
                if (commentsNode.isArray()) {
                    for (JsonNode node : commentsNode) {
                        String cBody = node.path("body").asText("");
                        String cHtmlBody =
                                HtmlUtil.sanitize(node.path("bodyHTML").asText(""));
                        if (!cHtmlBody.isBlank()) {
                            allImageUrls.addAll(MarkupImageParser.extractImageUrls(cHtmlBody));
                        }
                        String cAuthor = node.path("author").isMissingNode()
                                        || node.path("author").isNull()
                                ? "N/A"
                                : node.path("author").path("login").asText("N/A");
                        Instant cCreated = parseIsoDate(node.path("createdAt").asText(null));
                        comments.add(new Comment(cAuthor, cBody, cCreated));
                    }
                }

                JsonNode pageInfo = commentsSection.path("pageInfo");
                hasNextPage = pageInfo.path("hasNextPage").asBoolean(false);
                if (hasNextPage) {
                    cursor = pageInfo.path("endCursor").asText(null);
                    if (cursor == null) {
                        hasNextPage = false;
                    }
                }
            }
        }

        if (header == null) {
            throw new IOException("Failed to load issue details (header is null).");
        }

        List<URI> attachmentUrls = mapToUri(allImageUrls);
        return new IssueDetails(header, body, htmlBody, comments, attachmentUrls);
    }

    private static List<URI> mapToUri(Set<String> allImageUrls) {
        return allImageUrls.stream()
                .map(urlString -> {
                    try {
                        return new URI(urlString);
                    } catch (URISyntaxException e) {
                        logger.warn(
                                "Invalid URI syntax for attachment URL: '{}'. Skipping. Error: {}",
                                urlString,
                                e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private @Nullable Instant parseIsoDate(@Nullable String isoDate) {
        if (isoDate == null) {
            return null;
        }
        try {
            return Instant.parse(isoDate);
        } catch (DateTimeParseException e) {
            logger.warn("Failed to parse date: {}", isoDate, e);
            return null;
        }
    }

    private @Nullable IssueHeader mapToIssueHeader(GHIssue ghIssue) {
        try {
            String id = "#" + ghIssue.getNumber();
            String title = ghIssue.getTitle();
            String author = getAuthorLogin(ghIssue.getUser()); // Handles potential IOException for getUser
            Date updatedDate = ghIssue.getUpdatedAt(); // Can throw IOException
            Instant updated = updatedDate != null ? updatedDate.toInstant() : null;
            List<String> labels =
                    ghIssue.getLabels().stream().map(GHLabel::getName).collect(Collectors.toList());
            List<String> assignees =
                    ghIssue.getAssignees().stream().map(this::getAuthorLogin).collect(Collectors.toList());
            String status = ghIssue.getState().toString();
            URI htmlUrl = ghIssue.getHtmlUrl().toURI(); // Can throw URISyntaxException

            return new IssueHeader(id, title, author, updated, labels, assignees, status, htmlUrl);
        } catch (IOException e) {
            logger.error("IOException mapping GHIssue #{} to IssueHeader: {}", ghIssue.getNumber(), e.getMessage(), e);
            return null; // Or throw a wrapped exception if IssueHeader is critical
        } catch (URISyntaxException e) {
            logger.error(
                    "URISyntaxException for GHIssue #{} URL ({}): {}",
                    ghIssue.getNumber(),
                    ghIssue.getHtmlUrl(),
                    e.getMessage(),
                    e);
            return null;
        } catch (NullPointerException e) {
            logger.error(
                    "NullPointerException during mapping of GHIssue #{}, possibly due to missing core fields like getHtmlUrl() returning null: {}",
                    ghIssue.getNumber(),
                    e.getMessage(),
                    e);
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
            logger.warn(
                    "Unexpected error retrieving login for user object (ID: {}): {}. Defaulting to 'N/A'.",
                    user.getId(),
                    e.getMessage());
            return "N/A";
        }
    }

    @Override
    public List<String> listAvailableStatuses() {
        // GitHub issues primarily use "Open" and "Closed" states.
        return List.of("Open", "Closed");
    }

    @Override
    public Iterator<List<IssueHeader>> listIssuesPaginated(FilterOptions rawFilterOptions, int pageSize, int maxTotal)
            throws IOException {
        if (!(rawFilterOptions instanceof GitHubFilterOptions filterOptions)) {
            throw new IllegalArgumentException("GitHubIssueService requires GitHubFilterOptions, got "
                    + rawFilterOptions.getClass().getName());
        }

        String queryText = filterOptions.query();

        // For search queries, fall back to non-paginated (search API has different pagination)
        if (queryText != null && !queryText.isBlank()) {
            logger.debug("Search query present, falling back to non-paginated listIssues for query: '{}'", queryText);
            var all = listIssues(filterOptions);
            int searchLimit = Math.min(maxTotal, StreamingPaginationHelper.MAX_ISSUES);
            var limited = all.size() > searchLimit ? all.subList(0, searchLimit) : all;
            return List.of(limited).iterator();
        }

        // Use paginated API with server-side filtering
        GHIssueState apiState = parseIssueState(filterOptions.status());
        var pagedIterable = getAuth()
                .listIssuesPaginated(
                        apiState, filterOptions.label(), filterOptions.assignee(), filterOptions.author(), pageSize);
        PagedIterator<GHIssue> ghIterator = pagedIterable.iterator();

        return new Iterator<>() {
            private int totalFetched = 0;

            @Override
            public boolean hasNext() {
                return totalFetched < maxTotal && ghIterator.hasNext();
            }

            @Override
            public List<IssueHeader> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                List<IssueHeader> page = new ArrayList<>();
                try {
                    while (ghIterator.hasNext() && page.size() < pageSize && totalFetched < maxTotal) {
                        GHIssue ghIssue = ghIterator.next();
                        if (ghIssue.isPullRequest()) {
                            continue;
                        }
                        var header = mapToIssueHeader(ghIssue);
                        if (header != null) {
                            page.add(header);
                            totalFetched++;
                        }
                    }
                } catch (RuntimeException e) {
                    if (e.getCause() instanceof IOException) {
                        throw new UncheckedIOException((IOException) e.getCause());
                    }
                    throw e;
                }

                logger.debug("Fetched page with {} issues, total so far: {}", page.size(), totalFetched);
                return page;
            }
        };
    }

    private GHIssueState parseIssueState(@Nullable String status) {
        if (status == null || status.equalsIgnoreCase("ALL") || status.isBlank()) {
            return GHIssueState.ALL;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "OPEN" -> GHIssueState.OPEN;
            case "CLOSED" -> GHIssueState.CLOSED;
            default -> {
                logger.warn("Unrecognized status filter '{}', defaulting to ALL.", status);
                yield GHIssueState.ALL;
            }
        };
    }

    private boolean matchesAuthor(GHIssue issue, @Nullable String authorFilter) {
        if (authorFilter == null || authorFilter.isBlank()) {
            return true;
        }
        try {
            return authorFilter.equals(getAuthorLogin(issue.getUser()));
        } catch (Exception e) { // Includes IOException from issue.getUser() via getAuthorLogin's path
            logger.warn(
                    "Failed to get author for issue #{} during filter, treating as no match: {}",
                    issue.getNumber(),
                    e.getMessage());
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
                logger.warn(
                        "Failed to get assignee login for issue #{} during filter, treating as no match for this assignee: {}",
                        issue.getNumber(),
                        e.getMessage());
                return false;
            }
        });
    }
}
