package ai.brokk.gui.util;

import com.google.common.base.Splitter;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Static utilities for validating, parsing, and normalizing GitHub repository identifiers
 * (owner/repo pairs, URLs, hostnames).
 */
public interface GitRepoIdUtil {
    Logger logger = LogManager.getLogger(GitRepoIdUtil.class);

    /**
     * Error message returned by validation methods when owner/repo format is invalid.
     */
    String INVALID_REPO_FORMAT_MSG =
            "Repository must be in the form 'owner/repo'. Check Settings → Project → Issues → GitHub or your git remote.";

    /**
     * Pattern for valid GitHub owner names: alphanumeric and hyphens only, 1-39 characters,
     * no leading/trailing hyphen, no consecutive hyphens.
     */
    Pattern GITHUB_OWNER_PATTERN = Pattern.compile("^(?!.*--)[A-Za-z0-9]([A-Za-z0-9-]{0,37}[A-Za-z0-9])?$");

    /**
     * Pattern for valid GitHub repository names: alphanumeric, hyphens, underscores, dots,
     * 1-100 characters, no leading/trailing dot.
     */
    Pattern GITHUB_REPO_PATTERN = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,98}[A-Za-z0-9_]$|^[A-Za-z0-9_]$");

    /**
     * Pattern for valid hostname labels: alphanumeric and hyphens, no leading/trailing hyphen.
     */
    Pattern HOSTNAME_LABEL_PATTERN = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");

    /**
     * Holds a parsed "owner" and "repo" from a Git remote URL.
     */
    record OwnerRepo(String owner, String repo) {}

    /**
     * Validates a GitHub owner name format.
     *
     * @param owner The owner name to validate (will be trimmed)
     * @return Optional.empty() if valid, or Optional.of(errorMessage) if invalid
     */
    static Optional<String> validateOwnerFormat(@Nullable String owner) {
        String trimmedOwner = (owner == null) ? "" : owner.trim();

        if (trimmedOwner.isEmpty()) {
            return Optional.of("Owner name cannot be empty.");
        }

        if (trimmedOwner.length() > 39) {
            return Optional.of("Owner name is too long (max 39 characters).");
        }

        if (!GITHUB_OWNER_PATTERN.matcher(trimmedOwner).matches()) {
            return Optional.of(
                    "Owner name must be alphanumeric and hyphens only, with no leading/trailing hyphens or consecutive hyphens.");
        }

        return Optional.empty();
    }

    /**
     * Validates a GitHub repository name format.
     *
     * @param repo The repository name to validate (will be trimmed)
     * @return Optional.empty() if valid, or Optional.of(errorMessage) if invalid
     */
    static Optional<String> validateRepoFormat(@Nullable String repo) {
        String trimmedRepo = (repo == null) ? "" : repo.trim();

        if (trimmedRepo.isEmpty()) {
            return Optional.of("Repository name cannot be empty.");
        }

        if (trimmedRepo.length() > 100) {
            return Optional.of("Repository name is too long (max 100 characters).");
        }

        if (trimmedRepo.equals(".") || trimmedRepo.equals("..")) {
            return Optional.of("Repository name cannot be '.' or '..'.");
        }

        if (!GITHUB_REPO_PATTERN.matcher(trimmedRepo).matches()) {
            return Optional.of(
                    "Repository name must be alphanumeric, underscores, dots, and hyphens only, with no leading/trailing dots.");
        }

        return Optional.empty();
    }

    /**
     * Validates a separate owner and repo string pair using stricter GitHub-like constraints.
     *
     * @param owner The repository owner (will be trimmed)
     * @param repo The repository name (will be trimmed)
     * @return Optional.empty() if valid, or Optional.of(errorMessage) if invalid
     */
    static Optional<String> validateOwnerRepo(@Nullable String owner, @Nullable String repo) {
        // Trim inputs
        String trimmedOwner = (owner == null) ? "" : owner.trim();
        String trimmedRepo = (repo == null) ? "" : repo.trim();

        // Check for empty segments
        if (trimmedOwner.isEmpty() || trimmedRepo.isEmpty()) {
            return Optional.of(INVALID_REPO_FORMAT_MSG);
        }

        // Check for slashes within either segment
        if (trimmedOwner.contains("/") || trimmedRepo.contains("/")) {
            return Optional.of(INVALID_REPO_FORMAT_MSG);
        }

        // Check for .git suffix in owner (invalid)
        if (trimmedOwner.endsWith(".git")) {
            return Optional.of(INVALID_REPO_FORMAT_MSG);
        }

        // Delegate to specific validators for detailed error messages
        var ownerError = validateOwnerFormat(trimmedOwner);
        if (ownerError.isPresent()) {
            return ownerError;
        }

        var repoError = validateRepoFormat(trimmedRepo);
        if (repoError.isPresent()) {
            return repoError;
        }

        return Optional.empty();
    }

    /**
     * Normalizes and validates a separate owner and repo string pair.
     * Trims inputs, strips .git from repo, validates with stricter rules, and returns normalized parts.
     *
     * @param owner The repository owner
     * @param repo The repository name
     * @return A normalized OwnerRepo record
     * @throws IllegalArgumentException if validation fails
     */
    static OwnerRepo normalizeOwnerRepo(@Nullable String owner, @Nullable String repo) {
        // Trim inputs
        String trimmedOwner = (owner == null) ? "" : owner.trim();
        String trimmedRepo = (repo == null) ? "" : repo.trim();

        // Strip trailing .git from repo only
        if (trimmedRepo.endsWith(".git")) {
            trimmedRepo = trimmedRepo.substring(0, trimmedRepo.length() - 4).trim();
        }

        // Validate using stricter rules
        var validationError = validateOwnerRepo(trimmedOwner, trimmedRepo);
        if (validationError.isPresent()) {
            throw new IllegalArgumentException(validationError.get());
        }

        return new OwnerRepo(trimmedOwner, trimmedRepo);
    }

    /**
     * Builds a repository slug (owner/repo) using normalized and validated parts.
     *
     * @param owner The repository owner
     * @param repo The repository name
     * @return A string in the format "owner/repo"
     * @throws IllegalArgumentException if validation fails
     */
    static String buildRepoSlug(@Nullable String owner, @Nullable String repo) {
        var normalized = normalizeOwnerRepo(owner, repo);
        return normalized.owner() + "/" + normalized.repo();
    }

    /**
     * Validates a combined "owner/repo" string using stricter GitHub-like constraints.
     *
     * @param full The full repository identifier in "owner/repo" format
     * @return Optional.empty() if valid, or Optional.of(errorMessage) if invalid
     */
    static Optional<String> validateFullRepoName(@Nullable String full) {
        if (full == null || full.isBlank()) {
            return Optional.of(INVALID_REPO_FORMAT_MSG);
        }

        String trimmed = full.trim();

        // Strip trailing .git if present
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - 4).trim();
        }

        // Reject consecutive slashes (invalid format)
        if (trimmed.contains("//")) {
            return Optional.of(INVALID_REPO_FORMAT_MSG);
        }

        // Split by '/' to get owner and repo
        var parts = Splitter.on('/').omitEmptyStrings().splitToList(trimmed);

        // Must have exactly 2 parts (owner and repo)
        if (parts.size() != 2) {
            return Optional.of(INVALID_REPO_FORMAT_MSG);
        }

        String owner = parts.get(0);
        String repo = parts.get(1);

        // Delegate to validateOwnerRepo for consistency
        return validateOwnerRepo(owner, repo);
    }

    /**
     * Parse a Git remote URL of various forms:
     * - https://github.com/OWNER/REPO.git
     * - git@github.com:OWNER/REPO.git
     * - ssh://github.com/OWNER/REPO
     * - github.com/OWNER/REPO
     *
     * Extracts the last two path segments as "owner" and "repo", normalizes them, and returns them.
     * Returns null if parsing fails or normalization fails.
     *
     * @param remoteUrl The Git remote URL to parse
     * @return A normalized OwnerRepo, or null if parsing/normalization fails
     */
    static @Nullable OwnerRepo parseOwnerRepoFromUrl(String remoteUrl) {
        if (remoteUrl.isBlank()) {
            logger.warn("Remote URL is blank for parsing owner/repo.");
            return null;
        }

        String cleaned = remoteUrl.trim();

        // Strip trailing ".git" if present
        if (cleaned.endsWith(".git")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }

        cleaned = cleaned.replace('\\', '/'); // Normalize path separators

        // Remove protocol part (e.g., "https://", "ssh://")
        int protocolIndex = cleaned.indexOf("://");
        if (protocolIndex >= 0) {
            cleaned = cleaned.substring(protocolIndex + 3);
        }

        // Remove user@ part (e.g., "git@")
        int atIndex = cleaned.indexOf('@');
        if (atIndex >= 0) {
            cleaned = cleaned.substring(atIndex + 1);
        }

        // Split by '/' or ':' treating multiple delimiters as one
        var segments = Splitter.on(Pattern.compile("[/:]+"))
                .omitEmptyStrings() // Important to handle cases like "host:/path" or "host//path"
                .splitToList(cleaned);

        if (segments.size() < 2) {
            logger.warn("Unable to parse owner/repo from cleaned remote URL: {} (original: {})", cleaned, remoteUrl);
            return null;
        }

        // The repository name is the last segment
        String repo = segments.getLast();
        // The owner is the second to last segment
        String owner = segments.get(segments.size() - 2);

        if (owner.isBlank() || repo.isBlank()) {
            logger.warn(
                    "Parsed blank owner or repo from remote URL: {} (owner: '{}', repo: '{}')", remoteUrl, owner, repo);
            return null;
        }

        try {
            var normalized = normalizeOwnerRepo(owner, repo);
            logger.debug(
                    "Parsed and normalized owner '{}' and repo '{}' from URL '{}'",
                    normalized.owner(),
                    normalized.repo(),
                    remoteUrl);
            return normalized;
        } catch (IllegalArgumentException e) {
            logger.warn(
                    "Parsed owner/repo from URL but normalization failed: owner='{}', repo='{}'. Error: {}",
                    owner,
                    repo,
                    e.getMessage());
            return null;
        }
    }

    /**
     * Parses a flexible input that can be either a full Git remote URL or an owner/repo slug.
     * Accepts formats like:
     * - https://github.com/O/R.git
     * - git@github.com:O/R.git
     * - ssh://github.com/O/R/
     * - github.com/O/R
     * - O/R
     *
     * Returns normalized parts if parsing and validation succeed, or Optional.empty() if they fail.
     *
     * @param input The input string (URL or slug)
     * @return Optional containing normalized OwnerRepo, or Optional.empty() if parsing/validation fails
     */
    static Optional<OwnerRepo> parseOwnerRepoFlexible(@Nullable String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        String trimmed = input.trim();

        // Check for consecutive slashes (invalid in raw slugs)
        if (trimmed.contains("//") && !trimmed.contains("://")) {
            // Has consecutive slashes but no protocol - invalid
            return Optional.empty();
        }

        // Check if this looks like a raw slug (exactly one forward slash)
        int slashCount = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == '/') {
                slashCount++;
            }
        }

        if (slashCount == 1) {
            // Raw slug format: owner/repo (no colons)
            int colonCount = 0;
            for (int i = 0; i < trimmed.length(); i++) {
                if (trimmed.charAt(i) == ':') {
                    colonCount++;
                }
            }

            if (colonCount == 0) {
                // Pure slug format: owner/repo
                String[] parts = trimmed.split("/", 2);
                String owner = parts[0].trim();
                String repo = parts[1].trim();

                // Strip .git from repo if present
                if (repo.endsWith(".git")) {
                    repo = repo.substring(0, repo.length() - 4).trim();
                }

                try {
                    var normalized = normalizeOwnerRepo(owner, repo);
                    return Optional.of(normalized);
                } catch (IllegalArgumentException e) {
                    logger.debug("Flexible parsing failed for raw slug '{}': {}", trimmed, e.getMessage());
                    return Optional.empty();
                }
            } else {
                // Likely a URL (e.g., git@github.com:owner/repo)
                var result = parseOwnerRepoFromUrl(trimmed);
                return Optional.ofNullable(result);
            }
        } else {
            // URL format or single segment (no slash) - try to parse as URL
            var result = parseOwnerRepoFromUrl(trimmed);
            return Optional.ofNullable(result);
        }
    }

    /**
     * Normalizes a GitHub host by trimming, removing protocol prefix, and stripping trailing slash.
     * Returns empty Optional if host is null or blank after normalization.
     *
     * @param host The host string to normalize (may include protocol and trailing slash)
     * @return Optional containing normalized host, or Optional.empty() if null/blank
     */
    static Optional<String> normalizeGitHubHost(@Nullable String host) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }

        String normalized = host.trim();

        // Remove protocol prefix (https:// or http://)
        normalized = normalized.replaceFirst("^https?://", "");

        // Remove trailing slash
        normalized = normalized.replaceFirst("/$", "");

        if (normalized.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(normalized);
    }

    /**
     * Validates a GitHub host for use as an enterprise endpoint.
     * Checks hostname labels, optional port, and overall length.
     *
     * @param host The host string to validate (should be normalized first)
     * @return Optional.empty() if valid, or Optional.of(errorMessage) if invalid
     */
    static Optional<String> validateGitHubHost(@Nullable String host) {
        if (host == null || host.isBlank()) {
            return Optional.of("GitHub host cannot be blank.");
        }

        // Check for any remaining protocol markers or paths
        if (host.contains("://") || host.contains("/")) {
            return Optional.of("GitHub host must not contain protocol or path components.");
        }

        // Check total length (DNS limit is 253)
        if (host.length() > 253) {
            return Optional.of("GitHub host is too long (max 253 characters).");
        }

        // Split by ':' to separate host and optional port
        String[] hostAndPort = host.split(":", 2);
        String hostPart = hostAndPort[0];
        String portPart = hostAndPort.length > 1 ? hostAndPort[1] : null;

        // Validate port if present
        if (portPart != null) {
            if (!portPart.matches("^\\d{1,5}$")) {
                return Optional.of("GitHub host port must be 1-5 digits.");
            }
            int portNum = Integer.parseInt(portPart);
            if (portNum < 1 || portNum > 65535) {
                return Optional.of("GitHub host port must be between 1 and 65535.");
            }
        }

        // Validate hostname labels
        String[] labels = hostPart.split("\\.", -1); // Include empty strings for consecutive dots
        if (labels.length == 0) {
            return Optional.of("GitHub host must contain at least one label.");
        }

        for (String label : labels) {
            if (label.isEmpty()) {
                return Optional.of("GitHub host labels cannot be empty (no consecutive dots).");
            }
            if (label.length() > 63) {
                return Optional.of("GitHub host label is too long (max 63 characters).");
            }
            if (!HOSTNAME_LABEL_PATTERN.matcher(label).matches()) {
                return Optional.of(
                        "GitHub host label '" + label + "' contains invalid characters or leading/trailing hyphens.");
            }
        }

        return Optional.empty();
    }
}
