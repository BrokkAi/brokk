package io.github.jbellis.brokk.issues;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import io.github.jbellis.brokk.IProject;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

public class JiraIssueService implements IssueService {

    private static final Logger logger = LogManager.getLogger(JiraIssueService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter JIRA_PRIMARY_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final DateTimeFormatter JIRA_SECONDARY_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MMM/yy HH:mm", java.util.Locale.ROOT);

    private final JiraAuth jiraAuth;
    private final IProject project; // May be needed for project-specific Jira settings
    private List<String> availableStatusesCache;

    public JiraIssueService(IProject project) {
        this.project = project;
        this.jiraAuth = new JiraAuth(project);
    }

    @Nullable
    private Date parseJiraDateTime(@Nullable String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isBlank()) {
            return null;
        }

        // Attempt 1: Primary ISO formatter (OffsetDateTime)
        try {
            return Date.from(OffsetDateTime.parse(dateTimeStr, JIRA_PRIMARY_DATE_FORMATTER).toInstant());
        } catch (DateTimeParseException e1) {
            //logger.trace("Primary date parse failed for '{}': {}", dateTimeStr, e1.getMessage());
            // Fall through to try the secondary formatter
        }

        // Attempt 2: Secondary formatter for "dd/MMM/yy HH:mm" (LocalDateTime then assume system zone)
        try {
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(dateTimeStr, JIRA_SECONDARY_DATE_FORMATTER);
            // Assume system default timezone for dates without explicit offset from renderedFields.
            return Date.from(ldt.atZone(java.time.ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException e2) {
            logger.warn("Could not parse date string from Jira: '{}'. Tried primary ISO format and secondary 'dd/MMM/yy HH:mm' format. Error from last attempt: {}", dateTimeStr, e2.getMessage());
            throw e2; // Propagate the exception if all attempts fail
        }
    }

    @Override
    public ImmutableList<IssueHeader> listIssues(FilterOptions rawFilterOptions) throws IOException {
        if (!(rawFilterOptions instanceof JiraFilterOptions filterOptions)) {
            throw new IllegalArgumentException("JiraIssueService requires JiraFilterOptions, got " + rawFilterOptions.getClass().getName());
        }
        logger.debug("Attempting to list Jira issues with filter options: {}", filterOptions);

        io.github.jbellis.brokk.IssueProvider provider = project.getIssuesProvider();
        if (provider.type() != IssueProviderType.JIRA || !(provider.config() instanceof IssuesProviderConfig.JiraConfig jiraConfig)) {
            String errorMessage = "JiraIssueService called with non-Jira or misconfigured provider. Type: " + provider.type();
            logger.error(errorMessage);
            throw new IOException(errorMessage);
        }

        String baseUrl = jiraConfig.baseUrl();
        OkHttpClient client = this.httpClient(); // httpClient already uses the provider

        String projectKey = jiraConfig.projectKey();
        if (projectKey.isBlank()) {
            logger.warn("Jira project key not set in JiraConfig for project {}, defaulting to 'CASSANDRA' for JQL query.", project.getRoot().getFileName());
            projectKey = "CASSANDRA"; // Fallback for testing as per goal
        }

        String statusClause = "";
        String statusFilter = filterOptions.status();
        if (statusFilter != null && !statusFilter.isBlank()) {
            // Escape double quotes in status value for JQL compatibility, though typically status names don't have them.
            String escapedStatus = statusFilter.replace("\"", "\\\"");
            statusClause = String.format(" AND status = \"%s\"", escapedStatus);
        }

        String resolutionClause = "";
        String resolutionFilter = filterOptions.resolution();
        if (resolutionFilter != null && !resolutionFilter.isBlank()) {
            if ("Resolved".equalsIgnoreCase(resolutionFilter)) {
                resolutionClause = " AND resolution IS NOT EMPTY";
            } else if ("Unresolved".equalsIgnoreCase(resolutionFilter)) {
                resolutionClause = " AND resolution IS EMPTY";
            }
            // If filterOptions.resolution() is something else, it's ignored for now.
        }

        StringBuilder jqlBuilder = new StringBuilder(String.format("project = %s%s%s", projectKey, statusClause, resolutionClause));

        String queryFilter = filterOptions.query();
        if (queryFilter != null && !queryFilter.isBlank()) {
            String escapedQuery = queryFilter.replace("\\", "\\\\").replace("\"", "\\\"");
            jqlBuilder.append(String.format(" AND text ~ \"%s\"", escapedQuery));
        }

        jqlBuilder.append(" ORDER BY updated DESC");
        String jql = jqlBuilder.toString();
        logger.debug("Executing Jira JQL query: {}", jql);

        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "/rest/api/2/search").newBuilder();
        urlBuilder.addQueryParameter("jql", jql);
        urlBuilder.addQueryParameter("fields", "key,summary,updated,status,reporter,assignee,labels");
        urlBuilder.addQueryParameter("maxResults", "50");

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json")
                .get()
                .build();

        List<IssueHeader> issueHeaders = new ArrayList<>();
        String responseBodyString = null;

        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                logger.error("Received null response body from Jira for listIssues query: {}", request.url());
                return ImmutableList.of();
            }
            responseBodyString = responseBody.string(); // Read body ONCE

            if (!response.isSuccessful()) {
                logger.error("Failed to fetch Jira issues. URL: {}. HTTP Status: {}. Message: {}. Body: {}",
                             request.url(), response.code(), response.message(), responseBodyString);
                return ImmutableList.of();
            }

            String contentType = response.header("Content-Type");
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
                logger.error("Expected JSON response from Jira for listIssues but received Content-Type: '{}'. URL: {}. Body follows:\n{}",
                             contentType, request.url(), responseBodyString);
                return ImmutableList.of();
            }

            JsonNode rootNode = objectMapper.readTree(responseBodyString);
            JsonNode issuesNode = rootNode.path("issues");

            if (issuesNode.isArray()) {
                for (JsonNode issueNode : issuesNode) {
                    String issueKey = issueNode.path("key").asText(null);
                    if (issueKey == null) {
                        logger.warn("Skipping issue with null key: {}", issueNode.toString());
                        continue;
                    }

                    JsonNode fieldsNode = issueNode.path("fields");
                    String title = fieldsNode.path("summary").asText("No summary");

                    String updatedStr = fieldsNode.path("updated").asText(null);
                    Date updatedAt = parseJiraDateTime(updatedStr);

                    String status = fieldsNode.path("status").path("name").asText("Unknown");
                    String author = fieldsNode.path("reporter").path("displayName").asText("Anonymous");

                    // Placeholder for labels and assignees as per requirements
                    List<String> labels = Collections.emptyList();
                    List<String> assignees = Collections.emptyList();

                    URI htmlUrl = null;
                    try {
                        htmlUrl = new URI(baseUrl + "/browse/" + issueKey);
                    } catch (URISyntaxException e) {
                        logger.warn("Could not construct htmlUrl for issue {}: {}", issueKey, baseUrl, e);
                    }

                    issueHeaders.add(new IssueHeader(issueKey, title, author, updatedAt, labels, assignees, status, htmlUrl));
                }
            } else {
                logger.warn("Jira response 'issues' field is not an array or is missing.");
            }

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            logger.error("JSON parsing error while processing Jira list issues response. URL: {}. Error: {}. Response body (if available) was:\n{}",
                         request.url(), e.getMessage(), responseBodyString, e);
            return ImmutableList.of();
        } catch (IOException e) {
            logger.error("IOException while fetching or parsing Jira issues (URL: {}): {}", request.url(), e.getMessage(), e);
            return ImmutableList.of();
        }

        logger.info("Successfully fetched {} Jira issues for project key '{}'.", issueHeaders.size(), projectKey);
        return ImmutableList.copyOf(issueHeaders);
    }

    @Override
    public IssueDetails loadDetails(String issueId) throws IOException {
        logger.debug("Attempting to load Jira issue details for issueId: {}", issueId);

        if (project == null) {
            String errorMessage = "Project is null, cannot load Jira issue details.";
            logger.error(errorMessage);
            throw new IOException(errorMessage);
        }

        io.github.jbellis.brokk.IssueProvider provider = project.getIssuesProvider();
        if (provider.type() != IssueProviderType.JIRA || !(provider.config() instanceof IssuesProviderConfig.JiraConfig jiraConfig)) {
            String errorMessage = "JiraIssueService called (loadDetails) with non-Jira or misconfigured provider. Type: " + provider.type();
            logger.error(errorMessage);
            throw new IOException(errorMessage);
        }

        String baseUrl = jiraConfig.baseUrl();
        if (baseUrl.isBlank()) {
            String errorMessage = "Jira base URL is not configured in JiraConfig. Cannot load issue details.";
            logger.error(errorMessage);
            throw new IOException(errorMessage);
        }

        OkHttpClient client = this.httpClient(); // httpClient already uses the provider

        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "/rest/api/2/issue/" + issueId).newBuilder();
        urlBuilder.addQueryParameter("fields", "summary,description,status,reporter,assignee,labels,comment,attachment,updated,created,issuetype");
        urlBuilder.addQueryParameter("expand", "renderedFields");

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json")
                .get()
                .build();
        String responseBodyString = null;

        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                logger.error("Received null response body from Jira for issue {}. URL: {}", issueId, request.url());
                throw new IOException("Received null response body from Jira for issue " + issueId);
            }
            responseBodyString = responseBody.string(); // Read body ONCE

            if (!response.isSuccessful()) {
                logger.error("Failed to fetch Jira issue details for {}. URL: {}. HTTP Status: {}. Message: {}. Body: {}",
                             issueId, request.url(), response.code(), response.message(), responseBodyString);
                throw new IOException("Failed to fetch Jira issue details for " + issueId + ": " + response.code() + " " + response.message());
            }

            String contentType = response.header("Content-Type");
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
                logger.error("Expected JSON response for Jira issue details {} but received Content-Type: '{}'. URL: {}. Body follows:\n{}",
                             issueId, contentType, request.url(), responseBodyString);
                throw new IOException("Expected JSON response for Jira issue " + issueId + " but received Content-Type: " + contentType);
            }

            JsonNode rootNode = objectMapper.readTree(responseBodyString);

            // Map to IssueHeader
            String key = rootNode.path("key").asText(null);
            if (key == null || !key.equals(issueId)) {
                logger.error("Mismatched issue key. Expected: {}, Got: {}. Full response: {}", issueId, key, rootNode.toString());
                throw new IOException("Fetched issue key (" + key + ") does not match requested issueId (" + issueId + ")");
            }

            JsonNode fieldsNode = rootNode.path("fields");
            String title = fieldsNode.path("summary").asText("No summary");
            String author = fieldsNode.path("reporter").path("displayName").asText("Anonymous");

            String updatedStr = fieldsNode.path("updated").asText(null);
            Date updatedAt = parseJiraDateTime(updatedStr);

            List<String> labels = new ArrayList<>();
            JsonNode labelsNode = fieldsNode.path("labels");
            if (labelsNode.isArray()) {
                for (JsonNode labelNode : labelsNode) {
                    labels.add(labelNode.asText()); // Jira labels are simple strings in the array
                }
            }

            List<String> assignees = new ArrayList<>();
            JsonNode assigneeNode = fieldsNode.path("assignee");
            if (assigneeNode != null && !assigneeNode.isNull() && assigneeNode.has("displayName")) {
                assignees.add(assigneeNode.path("displayName").asText());
            }

            String status = fieldsNode.path("status").path("name").asText("Unknown");

            URI htmlUrl;
            try {
                htmlUrl = new URI(baseUrl + "/browse/" + key);
            } catch (URISyntaxException e) {
                logger.error("Could not construct htmlUrl for issue {}: {}", key, baseUrl, e);
                throw new IOException("Could not construct htmlUrl for issue " + key, e);
            }

            IssueHeader header = new IssueHeader(key, title, author, updatedAt, labels, assignees, status, htmlUrl);

            // Extract renderedBody (HTML)
            String renderedDescription = rootNode.path("renderedFields").path("description").asText("");

            // Parse comments
            List<Comment> comments = new ArrayList<>();
            JsonNode rawCommentsNode = rootNode.path("fields").path("comment").path("comments");
            JsonNode htmlCommentsNode = rootNode.path("renderedFields").path("comment").path("comments");

            if (rawCommentsNode.isArray()) {
                for (int i = 0; i < rawCommentsNode.size(); i++) {
                    JsonNode rawComment = rawCommentsNode.get(i);
                    JsonNode htmlComment = (htmlCommentsNode.isArray() && i < htmlCommentsNode.size()) ? htmlCommentsNode.get(i) : null;

                    String authorName = rawComment.path("author").path("displayName").asText("Unknown Author");
                    String bodyHtml = (htmlComment != null) ? htmlComment.path("renderedBody").asText("") : "";
                    // If renderedBody is not available from htmlComment, bodyHtml will be empty.
                    // This follows the user's provided logic snippet.

                    String createdStr = rawComment.path("created").asText(null); // ISO date from raw fields
                    Date createdDate = parseJiraDateTime(createdStr);

                    comments.add(new Comment(authorName, bodyHtml, createdDate));
                }
                logger.debug("Parsed {} comments for Jira issue {}", comments.size(), issueId);
            } else {
                logger.warn("No 'comments' array found under fields.comment.comments for issue {}. Node type: {}", issueId, rawCommentsNode.getNodeType());
            }

            // Parse attachments
            List<URI> attachmentUrls = new ArrayList<>();
            JsonNode attachmentsNode = rootNode.path("fields").path("attachment");
            if (attachmentsNode.isArray()) {
                for (JsonNode attachmentNode : attachmentsNode) {
                    String mimeType = attachmentNode.path("mimeType").asText("");
                    if (mimeType.startsWith("image/")) {
                        String contentUrlStr = attachmentNode.path("content").asText(null);
                        if (contentUrlStr != null && !contentUrlStr.isBlank()) {
                            try {
                                attachmentUrls.add(new URI(contentUrlStr));
                            } catch (URISyntaxException e) {
                                logger.warn("Invalid URI syntax for attachment content URL: '{}' from issue {}. Skipping. Error: {}", contentUrlStr, issueId, e.getMessage());
                            }
                        }
                    }
                }
                logger.debug("Parsed {} image attachment URLs for Jira issue {}", attachmentUrls.size(), issueId);
            } else {
                logger.warn("No 'attachment' array found or it's not an array for issue {}.", issueId);
            }

            logger.info("Successfully loaded details (including {} comments and {} image attachments) for Jira issue: {}", comments.size(), attachmentUrls.size(), issueId);
            return new IssueDetails(header, renderedDescription, comments, attachmentUrls);

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            logger.error("JSON parsing error while processing Jira issue details for {}. URL: {}. Error: {}. Response body (if available) was:\n{}",
                         issueId, request.url(), e.getMessage(), responseBodyString, e);
            throw new IOException("Failed to parse JSON response for issue " + issueId + ": " + e.getMessage(), e);
        } catch (IOException e) {
            logger.error("IOException while fetching or parsing Jira issue details for {} (URL: {}): {}", issueId, request.url(), e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public OkHttpClient httpClient() throws IOException {
        return this.jiraAuth.buildAuthenticatedClient();
    }

    @Override
    public List<String> listAvailableStatuses() throws IOException {
        if (availableStatusesCache != null) {
            logger.debug("Returning cached Jira statuses.");
            return availableStatusesCache;
        }

        logger.debug("Fetching available statuses from Jira API.");
        io.github.jbellis.brokk.IssueProvider provider = project.getIssuesProvider();
        if (provider.type() != IssueProviderType.JIRA || !(provider.config() instanceof IssuesProviderConfig.JiraConfig jiraConfig)) {
            String errorMessage = "JiraIssueService called (listAvailableStatuses) with non-Jira or misconfigured provider. Type: " + provider.type();
            logger.error(errorMessage);
            throw new IOException(errorMessage);
        }

        String baseUrl = jiraConfig.baseUrl();
        OkHttpClient client = httpClient(); // httpClient already uses the provider
        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "/rest/api/2/status").newBuilder();
        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json")
                .get()
                .build();

        String responseBodyString = null;
        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                logger.error("Received null response body from Jira for /status endpoint: {}", request.url());
                return Collections.emptyList();
            }
            responseBodyString = responseBody.string();

            if (!response.isSuccessful()) {
                logger.error("Failed to fetch Jira statuses. URL: {}. HTTP Status: {}. Message: {}. Body: {}",
                             request.url(), response.code(), response.message(), responseBodyString);
                return Collections.emptyList();
            }

            String contentType = response.header("Content-Type");
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
                logger.error("Expected JSON response from Jira for /status but received Content-Type: '{}'. URL: {}. Body follows:\n{}",
                             contentType, request.url(), responseBodyString);
                return Collections.emptyList();
            }

            JsonNode rootNode = objectMapper.readTree(responseBodyString);
            if (rootNode.isArray()) {
                List<String> statusNames = new ArrayList<>();
                for (JsonNode statusNode : rootNode) {
                    String statusName = statusNode.path("name").asText(null);
                    if (statusName != null && !statusName.isBlank()) {
                        statusNames.add(statusName);
                    } else {
                        logger.warn("Found a status object without a 'name' or with a blank name: {}", statusNode.toString());
                    }
                }
                logger.info("Successfully fetched {} Jira statuses.", statusNames.size());
                this.availableStatusesCache = Collections.unmodifiableList(new ArrayList<>(statusNames)); // Cache a copy
                return this.availableStatusesCache;
            } else {
                logger.error("Jira /status response is not a JSON array as expected. Body: {}", responseBodyString);
                return Collections.emptyList();
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            logger.error("JSON parsing error while processing Jira /status response. URL: {}. Error: {}. Response body (if available) was:\n{}",
                         request.url(), e.getMessage(), responseBodyString, e);
            return Collections.emptyList();
        } catch (IOException e) {
            // httpClient() or client.newCall().execute() can throw IOException
            logger.error("IOException while fetching or parsing Jira statuses (URL: {}): {}", request.url(), e.getMessage(), e);
            throw e; // Re-throw general IOExceptions as per method signature
        }
    }
}
