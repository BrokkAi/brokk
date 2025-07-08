package io.github.jbellis.brokk.issues;

import org.jetbrains.annotations.Nullable;

public record JiraFilterOptions(
        @Nullable String status,      // e.g., "Open", "In Progress", or null for "All"
        @Nullable String resolution,  // e.g., "Resolved", "Unresolved", or null
        @Nullable String author,      // Author's login/username or null for any (typically client-filtered for Jira)
        @Nullable String label,       // Label name or null for any (typically client-filtered for Jira)
        @Nullable String assignee,    // Assignee's login/username or null for any (typically client-filtered for Jira)
        @Nullable String query        // Text search query, null if not used
) implements FilterOptions {}
