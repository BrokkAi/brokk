package io.github.jbellis.brokk.issues;

import org.jetbrains.annotations.Nullable;

public record GitHubFilterOptions(
        @Nullable String status,      // e.g., "Open", "Closed", or null for "All"
        @Nullable String author,      // Author's login/username or null for any
        @Nullable String label,       // Label name or null for any
        @Nullable String assignee,    // Assignee's login/username or null for any
        @Nullable String query        // Text search query, null if not used
) implements FilterOptions {
    // Constructor with null for query for backward compatibility or specific use cases if needed
    public GitHubFilterOptions(@Nullable String status, @Nullable String author, @Nullable String label, @Nullable String assignee) {
        this(status, author, label, assignee, null);
    }
}
