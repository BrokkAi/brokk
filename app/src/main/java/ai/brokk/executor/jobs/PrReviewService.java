package ai.brokk.executor.jobs;

import ai.brokk.git.GitRepo;
import ai.brokk.util.Json;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestReviewComment;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.HttpException;

/**
 * Helper class for GitHub PR operations used by the executor.
 *
 * <p>All methods are blocking and perform network I/O or git operations.
 */
public final class PrReviewService {
    private static final Logger logger = LogManager.getLogger(PrReviewService.class);
    private static final Pattern HUNK_PATTERN = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    private PrReviewService() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** PR metadata extracted from GitHub API. */
    public record PrDetails(String baseBranch, String headSha, String headRef) {}

    /** Structured PR review response from LLM. */
    public record PrReviewResponse(String summaryMarkdown, List<InlineComment> comments) {
        public PrReviewResponse(String summaryMarkdown, @Nullable List<InlineComment> comments) {
            this.summaryMarkdown = summaryMarkdown;
            this.comments = comments == null ? List.of() : List.copyOf(comments);
        }

        public PrReviewResponse(String summaryMarkdown) {
            this(summaryMarkdown, List.of());
        }
    }

    public enum Severity {
        CRITICAL(0),
        HIGH(1),
        MEDIUM(2),
        LOW(3);

        private final int rank;

        Severity(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }

        public static Severity normalize(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return LOW;
            }
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "CRITICAL" -> CRITICAL;
                case "HIGH" -> HIGH;
                case "MEDIUM" -> MEDIUM;
                case "LOW" -> LOW;
                default -> LOW;
            };
        }

        public boolean isAtLeast(Severity threshold) {
            return this.rank <= threshold.rank;
        }
    }

    /** Inline comment for a specific file and line. */
    public record InlineComment(
            String path,
            int line,
            @Nullable Integer startLine,
            @Nullable Integer endLine,
            String bodyMarkdown,
            Severity severity,
            boolean lineExplicit) {

        /**
         * Jackson-friendly creator that accepts either legacy 'line' or optional 'startLine'/'endLine'.
         *
         * Resolution rules for the primitive 'line' component:
         * - If JSON 'line' is present (non-null) use that value and mark lineExplicit=true.
         * - Else if 'startLine' present use startLine and mark lineExplicit=false.
         * - Else if 'endLine' present use endLine and mark lineExplicit=false.
         * - Else throw IllegalArgumentException.
         */
        @JsonCreator
        public InlineComment(
                @JsonProperty("path") String path,
                @JsonProperty("line") @Nullable Integer lineJson,
                @JsonProperty("startLine") @Nullable Integer startLine,
                @JsonProperty("endLine") @Nullable Integer endLine,
                @JsonProperty("bodyMarkdown") String bodyMarkdown,
                @JsonProperty("severity") @Nullable String severity) {
            this(
                    path,
                    resolveLineNumber(lineJson, startLine, endLine),
                    startLine,
                    endLine,
                    bodyMarkdown,
                    Severity.normalize(severity),
                    lineJson != null);
        }

        private static int resolveLineNumber(
                @Nullable Integer lineJson, @Nullable Integer startLine, @Nullable Integer endLine) {
            if (lineJson != null) {
                return lineJson;
            }
            if (startLine != null) {
                return startLine;
            }
            if (endLine != null) {
                return endLine;
            }
            throw new IllegalArgumentException(
                    "InlineComment requires at least one of 'line', 'startLine', or 'endLine'");
        }

        /** Canonical constructor: ensure severity is non-null. */
        public InlineComment {
            severity = Objects.requireNonNullElse(severity, Severity.LOW);
        }

        /** Auxiliary convenience constructor used in many tests and callers. */
        public InlineComment(String path, int line, String bodyMarkdown, Severity severity) {
            this(path, line, /* startLine= */ null, /* endLine= */ null, bodyMarkdown, severity, true);
        }
    }

    public static List<InlineComment> filterInlineComments(
            List<InlineComment> comments, Severity threshold, int maxComments) {
        if (maxComments < 0) {
            throw new IllegalArgumentException("maxComments must be >= 0");
        }

        record InlineCommentKey(String path, int line, String bodyMarkdown) {}

        Map<InlineCommentKey, InlineComment> deduped = comments.stream()
                .filter(c ->
                        Objects.requireNonNullElse(c.severity(), Severity.LOW).isAtLeast(threshold))
                .collect(Collectors.toMap(
                        c -> new InlineCommentKey(c.path(), c.line(), c.bodyMarkdown()), c -> c, (a, b) -> {
                            Severity aSeverity = Objects.requireNonNullElse(a.severity(), Severity.LOW);
                            Severity bSeverity = Objects.requireNonNullElse(b.severity(), Severity.LOW);
                            return aSeverity.rank() <= bSeverity.rank() ? a : b;
                        }));

        Comparator<InlineComment> comparator = Comparator.<InlineComment>comparingInt(c ->
                        Objects.requireNonNullElse(c.severity(), Severity.LOW).rank())
                .thenComparing(InlineComment::path)
                .thenComparingInt(InlineComment::line)
                .thenComparing(InlineComment::bodyMarkdown);

        return deduped.values().stream().sorted(comparator).limit(maxComments).toList();
    }

    /**
     * Fetches PR details from GitHub.
     *
     * @param repo the GitHub repository
     * @param prNumber the pull request number
     * @return PR details including base branch, head SHA, and head ref
     * @throws IOException if the GitHub API call fails
     */
    @Blocking
    public static PrDetails fetchPrDetails(GHRepository repo, int prNumber) throws IOException {
        GHPullRequest pr = repo.getPullRequest(prNumber);
        String baseBranch = pr.getBase().getRef();
        String headSha = pr.getHead().getSha();
        String headRef = pr.getHead().getRef();
        return new PrDetails(baseBranch, headSha, headRef);
    }

    /**
     * Computes the diff between the base branch and the PR head ref.
     *
     * @param repo the git repository
     * @param baseBranch the base branch to diff against (e.g., "main", "origin/main")
     * @param headRef the PR head ref or SHA to diff from
     * @return the annotated diff string with file headers
     * @throws IllegalStateException if no merge-base exists between branches
     * @throws GitAPIException if git operations fail
     */
    @Blocking
    public static String computePrDiff(GitRepo repo, String baseBranch, String headRef) throws GitAPIException {
        String mergeBase = repo.getMergeBase(baseBranch, headRef);
        if (mergeBase == null) {
            throw new IllegalStateException(
                    "No merge-base found between base branch '" + baseBranch + "' and head ref '" + headRef + "'");
        }
        return repo.getDiff(mergeBase, headRef);
    }

    /**
     * Posts a review comment on the pull request.
     *
     * @param pr the GitHub pull request
     * @param body the comment body in markdown
     * @throws IOException if the GitHub API call fails
     */
    @Blocking
    public static void postReviewComment(GHPullRequest pr, String body) throws IOException {
        pr.comment(body);
    }

    /**
     * Format a fallback PR comment body when inline (or ranged) review comment creation fails.
     *
     * The formatted markdown includes the file path, a human-friendly line or range description,
     * and the original body text.
     *
     * Note: package-private so unit tests can validate contents.
     */
    static String formatFallbackInlineCommentBody(
            String path, @Nullable Integer startLine, @Nullable Integer endLine, String body) {
        StringBuilder sb = new StringBuilder();
        if (startLine != null && endLine != null) {
            sb.append(String.format("**Comment on `%s` lines %d-%d:**\n\n", path, startLine, endLine));
        } else if (startLine != null) {
            sb.append(String.format("**Comment on `%s` line %d:**\n\n", path, startLine));
        } else if (endLine != null) {
            sb.append(String.format("**Comment on `%s` line %d:**\n\n", path, endLine));
        } else {
            sb.append(String.format("**Comment on `%s`:**\n\n", path));
        }
        sb.append(body);
        return sb.toString();
    }

    /**
     * Posts an inline review comment described by an InlineComment record.
     *
     * <p>If the inline comment fails with HTTP 422 (e.g., line not part of the diff), falls back
     * to posting a regular PR comment with file and line/range context.
     *
     * @param pr the GitHub pull request
     * @param comment the structured inline comment (may contain start/end for ranges)
     * @param commitId the commit SHA to comment on
     * @throws IOException if the GitHub API call fails (other than HTTP 422)
     */
    @Blocking
    public static void postLineComment(GHPullRequest pr, InlineComment comment, String commitId) throws IOException {
        String path = Objects.requireNonNullElse(comment.path(), "");
        @Nullable Integer start = comment.startLine();
        @Nullable Integer end = comment.endLine();
        int line = comment.line();

        // Validate obvious invalid line numbers early and fallback to a regular PR comment.
        if (line <= 0) {
            logger.warn(
                    "Invalid line number {} for path '{}' in PR #{}; falling back to regular PR comment",
                    line,
                    path,
                    pr.getNumber());
            String fallbackBody = formatFallbackInlineCommentBody(
                    path, start, end, Objects.requireNonNullElse(comment.bodyMarkdown(), ""));
            pr.comment(fallbackBody);
            logger.info("Posted fallback comment for {} (invalid line {}) in PR #{}", path, line, pr.getNumber());
            return;
        }

        try {
            // Use range mode only when:
            // 1. Both startLine and endLine are present, AND
            // 2. The JSON did not explicitly provide a 'line' field (i.e., line was derived), AND
            // 3. startLine <= endLine (valid range)
            if (start != null && end != null && !comment.lineExplicit() && start <= end) {
                // The underlying GitHub API client does not provide a builder method for ranged inline
                // comments in all versions. As a best-effort attempt, post the comment on the end line
                // of the range (the GitHub API will accept a single-line inline comment). We still log
                // the original start/end for clarity.
                pr.createReviewComment()
                        .body(Objects.requireNonNullElse(comment.bodyMarkdown(), ""))
                        .commitId(commitId)
                        .path(path)
                        .line(end)
                        .create();
                logger.info(
                        "Posted inline ranged comment on {}:{}-{} in PR #{} (derived from start/end)",
                        path,
                        start,
                        end,
                        pr.getNumber());
            } else {
                pr.createReviewComment()
                        .body(Objects.requireNonNullElse(comment.bodyMarkdown(), ""))
                        .commitId(commitId)
                        .path(path)
                        .line(line)
                        .create();
                if (start != null && end != null && comment.lineExplicit()) {
                    logger.info(
                            "Posted inline comment on {}:{} in PR #{} (explicit 'line' provided; start/end present but ignored)",
                            path,
                            line,
                            pr.getNumber());
                } else {
                    logger.info("Posted inline comment on {}:{} in PR #{}", path, line, pr.getNumber());
                }
            }
        } catch (HttpException e) {
            if (e.getResponseCode() == 422) {
                if (start != null && end != null) {
                    logger.warn(
                            "Failed to post ranged inline comment on {}:{}-{} (HTTP 422), falling back to regular comment",
                            path,
                            start,
                            end);
                } else {
                    logger.warn(
                            "Failed to post inline comment on {}:{} (HTTP 422), falling back to regular comment",
                            path,
                            line);
                }
                String fallbackBody = formatFallbackInlineCommentBody(
                        path, start, end, Objects.requireNonNullElse(comment.bodyMarkdown(), ""));
                pr.comment(fallbackBody);
                if (start != null && end != null) {
                    logger.info("Posted fallback comment for {}:{}-{} in PR #{}", path, start, end, pr.getNumber());
                } else {
                    logger.info("Posted fallback comment for {}:{} in PR #{}", path, line, pr.getNumber());
                }
            } else {
                throw e;
            }
        }
    }

    /**
     * Posts an inline review comment on a specific line of a file in the pull request.
     *
     * <p>Legacy overload kept for existing callers; delegates to {@link #postLineComment(GHPullRequest, InlineComment, String)}.
     *
     * @param pr the GitHub pull request
     * @param path the file path relative to repository root
     * @param line the line number in the file (1-indexed)
     * @param body the comment body in markdown
     * @param commitId the commit SHA to comment on
     * @throws IOException if the GitHub API call fails (other than HTTP 422)
     */
    @Blocking
    public static void postLineComment(GHPullRequest pr, String path, int line, String body, String commitId)
            throws IOException {
        postLineComment(pr, new InlineComment(path, line, body, Severity.LOW), commitId);
    }

    /**
     * Checks if an existing review comment already exists on the specified line of a file.
     *
     * @param pr the GitHub pull request
     * @param path the file path relative to repository root
     * @param line the line number in the file (1-indexed)
     * @return true if a comment already exists on that line, false otherwise
     * @throws IOException if the GitHub API call fails
     */
    @Blocking
    public static boolean hasExistingLineComment(GHPullRequest pr, String path, int line) throws IOException {
        for (GHPullRequestReviewComment comment : pr.listReviewComments()) {
            if (path.equals(comment.getPath()) && line == comment.getLine()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses raw LLM output text into a structured PrReviewResponse.
     *
     * <p>This parser is robust to LLM output variations:
     * <ul>
     *   <li>Attempts to parse the entire text as JSON</li>
     *   <li>If that fails, extracts JSON between first '{' and last '}' and retries</li>
     *   <li>Handles missing or null 'comments' field by defaulting to empty list</li>
     *   <li>Returns null if JSON is malformed or missing required fields</li>
     * </ul>
     *
     * Note: Jackson will bind comment objects into {@link InlineComment} using the provided
     * {@code @JsonCreator}, which supports legacy single 'line' integers as well as optional
     * 'startLine' and 'endLine' integers for ranged comments.
     *
     * @param rawText the raw LLM output string
     * @return parsed PrReviewResponse, or null if parsing fails
     */
    public static @Nullable PrReviewResponse parsePrReviewResponse(@Nullable String rawText) {
        if (rawText == null || rawText.isBlank()) {
            logger.warn("parsePrReviewResponse: empty or null input");
            return null;
        }

        logger.trace("parsePrReviewResponse: rawText length={}", rawText.length());

        JsonNode root;
        try {
            root = Json.getMapper().readTree(rawText);
        } catch (Exception initialParseError) {
            logger.trace("parsePrReviewResponse: direct parse failed, attempting extraction");
            int firstBrace = rawText.indexOf('{');
            int lastBrace = rawText.lastIndexOf('}');

            if (firstBrace == -1 || lastBrace == -1 || lastBrace < firstBrace) {
                logger.warn("parsePrReviewResponse: no JSON braces found in response", initialParseError);
                return null;
            }

            String extractedJson = rawText.substring(firstBrace, lastBrace + 1);
            try {
                root = Json.getMapper().readTree(extractedJson);
                logger.trace("parsePrReviewResponse: extraction succeeded");
            } catch (Exception extractionParseError) {
                logger.warn("parsePrReviewResponse: extracted JSON is malformed", extractionParseError);
                return null;
            }
        }

        if (!root.has("summaryMarkdown") || !root.get("summaryMarkdown").isTextual()) {
            logger.warn("parsePrReviewResponse: missing or invalid 'summaryMarkdown' field");
            return null;
        }

        String summaryMarkdown = root.get("summaryMarkdown").asText();

        List<InlineComment> comments;
        if (!root.has("comments") || root.get("comments").isNull()) {
            comments = List.of();
        } else if (!root.get("comments").isArray()) {
            logger.warn("parsePrReviewResponse: 'comments' field is not an array");
            return null;
        } else {
            JsonNode commentsNode = root.get("comments");
            try {
                comments = Json.getMapper()
                        .readValue(
                                commentsNode.toString(),
                                Json.getMapper()
                                        .getTypeFactory()
                                        .constructCollectionType(List.class, InlineComment.class));
            } catch (Exception e) {
                logger.warn("parsePrReviewResponse: failed to deserialize 'comments' array", e);
                return null;
            }
        }

        return new PrReviewResponse(summaryMarkdown, comments);
    }

    /**
     * Annotates a unified diff with explicit line numbers for LLM review.
     *
     * <p>Transforms standard unified diff format into an annotated format where each content line
     * is prefixed with [OLD:N NEW:N] markers showing the exact line numbers:
     *
     * <ul>
     *   <li>Context lines: [OLD:N NEW:M] (both line numbers)</li>
     *   <li>Added lines: [OLD:- NEW:N] (only new line number)</li>
     *   <li>Removed lines: [OLD:N NEW:-] (only old line number)</li>
     * </ul>
     *
     * <p>File headers (diff --git, ---, +++, index) are preserved as-is without annotation.
     *
     * @param unifiedDiff the standard unified diff string
     * @return the annotated diff with line number prefixes
     */
    public static String annotateDiffWithLineNumbers(String unifiedDiff) {
        if (unifiedDiff.isEmpty()) {
            return "";
        }

        String[] lines = unifiedDiff.split("\n", -1);
        StringBuilder result = new StringBuilder();
        int oldLine = 0;
        int newLine = 0;

        for (String line : lines) {
            if (line.startsWith("diff --git")
                    || line.startsWith("---")
                    || line.startsWith("+++")
                    || line.startsWith("index ")) {
                result.append(line).append('\n');
            } else if (line.startsWith("@@")) {
                Matcher matcher = HUNK_PATTERN.matcher(line);
                if (matcher.find()) {
                    oldLine = Integer.parseInt(matcher.group(1));
                    newLine = Integer.parseInt(matcher.group(2));
                }
                result.append(line).append('\n');
            } else if (line.startsWith(" ")) {
                result.append("[OLD:")
                        .append(oldLine)
                        .append(" NEW:")
                        .append(newLine)
                        .append("] ")
                        .append(line)
                        .append('\n');
                oldLine++;
                newLine++;
            } else if (line.startsWith("+")) {
                result.append("[OLD:- NEW:")
                        .append(newLine)
                        .append("] ")
                        .append(line)
                        .append('\n');
                newLine++;
            } else if (line.startsWith("-")) {
                result.append("[OLD:")
                        .append(oldLine)
                        .append(" NEW:-] ")
                        .append(line)
                        .append('\n');
                oldLine++;
            } else {
                result.append(line).append('\n');
            }
        }

        if (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
            result.setLength(result.length() - 1);
        }

        return result.toString();
    }
}
