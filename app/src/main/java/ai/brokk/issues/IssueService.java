package ai.brokk.issues;

import ai.brokk.gui.util.StreamingPaginationHelper;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import okhttp3.OkHttpClient;

public interface IssueService {
    /** Page size for paginated issue fetching. References StreamingPaginationHelper.DEFAULT_PAGE_SIZE. */
    int DEFAULT_PAGE_SIZE = StreamingPaginationHelper.DEFAULT_PAGE_SIZE;

    /** Maximum number of issues to fetch across all pages. References StreamingPaginationHelper.DEFAULT_MAX_ITEMS. */
    int MAX_ISSUES_LIMIT = StreamingPaginationHelper.DEFAULT_MAX_ITEMS;

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
