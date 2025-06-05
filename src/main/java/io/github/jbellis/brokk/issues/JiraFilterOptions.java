package io.github.jbellis.brokk.issues;

public record JiraFilterOptions(
        String status,      // e.g., "Open", "In Progress", or null for "All"
        String resolution,  // e.g., "Resolved", "Unresolved", or null
        String author,      // Author's login/username or null for any (typically client-filtered for Jira)
        String label,       // Label name or null for any (typically client-filtered for Jira)
        String assignee,    // Assignee's login/username or null for any (typically client-filtered for Jira)
        String query        // Text search query, null if not used
) implements FilterOptions {}
