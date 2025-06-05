package io.github.jbellis.brokk.issues;

public enum IssueProviderType {
    NONE,      // explicitly “do not fetch issues”
    GITHUB,    // GitHub‐backed issues (owner/repo can be overridden)
    JIRA;      // Jira‐backed issues

    public String getDisplayName() {
        return switch (this) {
            case NONE -> "None";
            case GITHUB -> "GitHub";
            case JIRA -> "Jira";
        };
    }

    public static IssueProviderType fromString(String text) {
        if (text == null || text.isBlank()) {
            return NONE; // Default to NONE if not specified or blank
        }
        for (IssueProviderType type : IssueProviderType.values()) {
            if (type.name().equalsIgnoreCase(text.trim()) || 
                type.getDisplayName().equalsIgnoreCase(text.trim())) {
                return type;
            }
        }
        return NONE; // Default if not found
    }
}
