package io.github.jbellis.brokk.issues;

public record GitHubFilterOptions(
        String status,      // e.g., "Open", "Closed", or null for "All"
        String author,      // Author's login/username or null for any
        String label,       // Label name or null for any
        String assignee,    // Assignee's login/username or null for any
        String query        // Text search query, null if not used
) implements FilterOptions {
    // Constructor with null for query for backward compatibility or specific use cases if needed
    public GitHubFilterOptions(String status, String author, String label, String assignee) {
        this(status, author, label, assignee, null);
    }
}
