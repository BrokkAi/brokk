package ai.brokk.issues;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import okhttp3.OkHttpClient;

public interface IssueService {
    /** Page size for paginated issue fetching. */
    int DEFAULT_PAGE_SIZE = 25;

    /** Maximum number of issues to fetch across all pages. */
    int MAX_ISSUES_LIMIT = 500;

    List<IssueHeader> listIssues(FilterOptions filterOptions) throws IOException;

    IssueDetails loadDetails(String issueId) throws IOException;

    OkHttpClient httpClient() throws IOException; // For reusing authenticated client for attachments

    default List<String> listAvailableStatuses() throws IOException {
        return List.of();
    }

    /**
     * Returns an iterator that fetches issues page by page.
     * Each call to next() returns the next page of issues.
     * Use this for streaming pagination to avoid loading all issues at once.
     *
     * @param filterOptions Filter options for the query
     * @param pageSize Number of issues per page
     * @param maxTotal Maximum total issues to fetch (stops after reaching this limit)
     * @return An iterator over pages of IssueHeaders
     */
    default Iterator<List<IssueHeader>> listIssuesPaginated(FilterOptions filterOptions, int pageSize, int maxTotal)
            throws IOException {
        // Default implementation falls back to loading all at once (single page)
        var all = listIssues(filterOptions);
        var limited = all.size() > maxTotal ? all.subList(0, maxTotal) : all;
        return List.of(limited).iterator();
    }
}
