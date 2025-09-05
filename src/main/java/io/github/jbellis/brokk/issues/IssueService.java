package io.github.jbellis.brokk.issues;

import java.io.IOException;
import java.util.List;
import okhttp3.OkHttpClient;

public interface IssueService {
    List<IssueHeader> listIssues(FilterOptions filterOptions) throws IOException;

    IssueDetails loadDetails(String issueId) throws IOException;

    OkHttpClient httpClient() throws IOException; // For reusing authenticated client for attachments

    default List<String> listAvailableStatuses() throws IOException {
        return List.of();
    }
}
