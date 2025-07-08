package io.github.jbellis.brokk.issues;

import org.jetbrains.annotations.Nullable;

public record GitHubFilterOptions(
        @Nullable String status,      // e.g., "Open", "Closed", or null for "All"
        @Nullable String author,      // Author's login/username or null for any
        @Nullable String label,       // Label name or null for any
        @Nullable String assignee,    // Assignee's login/username or null for any
        @Nullable String query        // Text search query, null if not used
) implements FilterOptions {
}
