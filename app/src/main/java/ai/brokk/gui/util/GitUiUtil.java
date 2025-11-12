package ai.brokk.gui.util;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IProject;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.difftool.ui.BrokkDiffPanel;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.git.GitRepo;
import ai.brokk.git.ICommitInfo;
import ai.brokk.git.IGitRepo;
import ai.brokk.git.IGitRepo.ModificationType;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.DiffWindowManager;
import ai.brokk.gui.PrTitleFormatter;
import ai.brokk.gui.components.RoundedLineBorder;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.util.SyntaxDetector;
import com.google.common.base.Splitter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.JOptionPane;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHPullRequest;

/**
 * Static utilities for showing diffs, capturing diffs, or editing files in the Git UI, removing duplicated code across
 * multiple panels.
 */
public final class GitUiUtil {
    private static final Logger logger = LogManager.getLogger(GitUiUtil.class);

    private GitUiUtil() {}

    /**
     * Creates a real-time validation listener for a JTextField that provides visual feedback
     * on keystroke as the user types.
     *
     * <p>The listener validates input with a 200ms debounce using javax.swing.Timer. On validation
     * failure, the text field border is set to a red RoundedLineBorder (2px thickness, 3px arc)
     * and a tooltip is set with the error message. On validation success, a transparent placeholder
     * border is applied and the tooltip is cleared.
     *
     * <p>This design prevents layout shifts by maintaining constant border dimensions regardless
     * of validation state.
     *
     * @param textField The JTextField to attach validation feedback to
     * @param validator A function that validates the text field's content and returns
     *     Optional.empty() if valid, or Optional.of(errorMessage) if invalid
     * @return A DocumentListener that can be attached to the text field's document
     */
    public static DocumentListener createRealtimeValidationListener(
            JTextField textField, Function<String, Optional<String>> validator) {
        // Store the original border to restore when validation passes
        var defaultBorder = textField.getBorder();
        if (defaultBorder == null) {
            defaultBorder = UIManager.getBorder("TextField.border");
        }
        var normalBorder = defaultBorder;

        // Error border
        var errorBorder = new RoundedLineBorder(ThemeColors.getColor(ThemeColors.NOTIF_ERROR_BORDER), 2, 3);

        // Use array to store debounce timer (allows modification in lambda)
        Timer[] debounceTimer = {null};

        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleValidation();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleValidation();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleValidation();
            }

            private void scheduleValidation() {
                // Cancel any pending validation
                if (debounceTimer[0] != null) {
                    debounceTimer[0].stop();
                }

                // Schedule new validation after 200ms debounce
                debounceTimer[0] = new Timer(200, evt -> {
                    String text = textField.getText().trim();
                    Optional<String> validationError = validator.apply(text);

                    if (validationError.isPresent()) {
                        // Validation failed: apply error border and set tooltip
                        String errorMessage = validationError.get();
                        textField.setBorder(errorBorder);
                        textField.setToolTipText(errorMessage);
                    } else {
                        // Validation succeeded: restore normal border and clear tooltip
                        textField.setBorder(normalBorder);
                        textField.setToolTipText(null);
                    }
                });
                debounceTimer[0].setRepeats(false);
                debounceTimer[0].start();
            }
        };
    }

    /**
     * Capture uncommitted diffs for the specified files, adding the result to the context. `selectedFiles` must not be
     * empty.
     */
    public static void captureUncommittedDiff(
            ContextManager contextManager, Chrome chrome, List<ProjectFile> selectedFiles) {
        assert !selectedFiles.isEmpty();
        var repo = contextManager.getProject().getRepo();

        contextManager.submitContextTask(() -> {
            try {
                var diff = repo.diffFiles(selectedFiles);
                if (diff.isEmpty()) {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO, "No uncommitted changes found for selected files");
                    return;
                }
                var description = "Diff of %s".formatted(formatFileList(selectedFiles));
                var syntaxStyle = selectedFiles.isEmpty()
                        ? SyntaxConstants.SYNTAX_STYLE_NONE
                        : SyntaxDetector.fromExtension(selectedFiles.getFirst().extension());
                var fragment = new ContextFragment.StringFragment(contextManager, diff, description, syntaxStyle);
                contextManager.addVirtualFragment(fragment);
                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Added uncommitted diff for " + selectedFiles.size() + " file(s) to context");
            } catch (Exception ex) {
                chrome.toolError("Error capturing uncommitted diff: " + ex.getMessage());
            }
        });
    }

    /** Open a file in the project’s editor. */
    public static void editFile(ContextManager contextManager, String filePath) {
        contextManager.submitContextTask(() -> {
            var file = contextManager.toFile(filePath);
            contextManager.addFiles(List.of(file));
        });
    }

    /** Capture a single file’s historical changes into the context (HEAD vs commitId). */
    public static void addFileChangeToContext(
            ContextManager contextManager, Chrome chrome, String commitId, ProjectFile file) {
        var repo = contextManager.getProject().getRepo();

        contextManager.submitContextTask(() -> {
            try {
                var diff = repo.getDiff(file, commitId + "^", commitId);
                if (diff.isEmpty()) {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO, "No changes found for " + file.getFileName());
                    return;
                }
                String shortHash = ((GitRepo) repo).shortHash(commitId);
                var description = "Diff of %s [%s]".formatted(file.getFileName(), shortHash);
                var syntaxStyle = SyntaxDetector.fromExtension(file.extension());
                var fragment = new ContextFragment.StringFragment(contextManager, diff, description, syntaxStyle);
                contextManager.addVirtualFragment(fragment);
                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO, "Added changes for " + file.getFileName() + " to context");
            } catch (Exception e) {
                chrome.toolError("Error adding file change to context: " + e.getMessage());
            }
        });
    }

    /** Show the diff for a single file at a specific commit. */
    public static void showFileHistoryDiff(
            ContextManager cm,
            Chrome chrome, // Pass Chrome for theme access
            String commitId,
            ProjectFile file) {
        var repo = cm.getProject().getRepo();

        String shortCommitId = ((GitRepo) repo).shortHash(commitId);
        var dialogTitle = "Diff: " + file.getFileName() + " (" + shortCommitId + ")";
        var parentCommitId = commitId + "^";

        cm.submitBackgroundTask("Loading history diff for " + file.getFileName(), () -> {
            try {
                var parentContent = repo.getFileContent(parentCommitId, file);
                var commitContent = repo.getFileContent(commitId, file);

                SwingUtilities.invokeLater(() -> {
                    var brokkDiffPanel = new BrokkDiffPanel.Builder(chrome.getTheme(), cm)
                            .leftSource(new BufferSource.StringSource(
                                    parentContent, parentCommitId, file.toString(), parentCommitId))
                            .rightSource(
                                    new BufferSource.StringSource(commitContent, commitId, file.toString(), commitId))
                            .build();
                    brokkDiffPanel.showInFrame(dialogTitle);
                });
            } catch (Exception ex) {
                cm.getIo().toolError("Error loading history diff: " + ex.getMessage());
            }
            return null;
        });
    }

    /** View the file content at a specific commit (opens it in a preview window). */
    public static void viewFileAtRevision(ContextManager cm, Chrome chrome, String commitId, String filePath) {
        var repo = cm.getProject().getRepo();

        cm.submitBackgroundTask("View file at revision", () -> {
            var file = new ProjectFile(cm.getRoot(), filePath);
            try {
                final String content = repo.getFileContent(commitId, file);
                SwingUtilities.invokeLater(() -> {
                    var fragment =
                            new ContextFragment.GitFileFragment(file, ((GitRepo) repo).shortHash(commitId), content);
                    chrome.openFragmentPreview(fragment);
                });
            } catch (GitAPIException e) {
                logger.warn(e);
                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO, "Error retrieving file content: " + e.getMessage());
            }
        });
    }

    /**
     * Captures the diff for a range of commits, defined by the chronologically newest and oldest ICommitInfo objects in
     * the selection, and adds it to the context. The diff is calculated from the parent of the oldest commit in the
     * range up to the newest commit.
     *
     * @param contextManager The ContextManager instance.
     * @param chrome The Chrome instance for UI feedback.
     * @param newestCommitInSelection The ICommitInfo for the newest commit in the selected range.
     * @param oldestCommitInSelection The ICommitInfo for the oldest commit in the selected range.
     */
    public static void addCommitRangeToContext(
            ContextManager contextManager,
            Chrome chrome,
            ICommitInfo newestCommitInSelection,
            ICommitInfo oldestCommitInSelection) {
        contextManager.submitContextTask(() -> {
            try {
                var repo = contextManager.getProject().getRepo();
                var newestCommitId = newestCommitInSelection.id();
                var oldestCommitId = oldestCommitInSelection.id();

                // Diff is from oldestCommit's parent up to newestCommit.
                String diff = repo.getDiff(oldestCommitId + "^", newestCommitId);
                if (diff.isEmpty()) {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO, "No changes found in the selected commit range");
                    return;
                }

                List<ProjectFile> changedFiles;
                if (newestCommitId.equals(oldestCommitId)) { // Single commit selected
                    changedFiles = newestCommitInSelection.changedFiles();
                } else {
                    // Files changed between oldest selected commit's parent and newest selected commit
                    changedFiles = repo.listFilesChangedBetweenCommits(newestCommitId, oldestCommitId + "^").stream()
                            .map(IGitRepo.ModifiedFile::file)
                            .collect(Collectors.toList());
                }

                var fileNamesSummary = formatFileList(changedFiles);

                var newestShort = ((GitRepo) repo).shortHash(newestCommitId);
                var oldestShort = ((GitRepo) repo).shortHash(oldestCommitId);
                var hashTxt = newestCommitId.equals(oldestCommitId) ? newestShort : oldestShort + ".." + newestShort;

                var description = "Diff of %s [%s]".formatted(fileNamesSummary, hashTxt);

                var syntaxStyle = changedFiles.isEmpty()
                        ? SyntaxConstants.SYNTAX_STYLE_NONE
                        : SyntaxDetector.fromExtension(changedFiles.getFirst().extension());
                var fragment = new ContextFragment.StringFragment(contextManager, diff, description, syntaxStyle);
                contextManager.addVirtualFragment(fragment);
                chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Added changes for commit range to context");
            } catch (Exception ex) {
                chrome.toolError("Error adding commit range to context: " + ex.getMessage());
            }
        });
    }

    /**
     * Groups contiguous integers from a sorted array into sub-lists.
     *
     * @param sortedRows A sorted array of integers.
     * @return A list of lists, where each inner list contains a sequence of contiguous integers.
     */
    public static List<List<Integer>> groupContiguous(int[] sortedRows) {
        if (sortedRows.length == 0) return List.of();

        var groups = new ArrayList<List<Integer>>();
        var currentGroup = new ArrayList<Integer>();
        currentGroup.add(sortedRows[0]);
        groups.add(currentGroup);

        for (int i = 1; i < sortedRows.length; i++) {
            if (sortedRows[i] == sortedRows[i - 1] + 1) {
                currentGroup.add(sortedRows[i]);
            } else {
                currentGroup = new ArrayList<>();
                currentGroup.add(sortedRows[i]);
                groups.add(currentGroup);
            }
        }
        return groups;
    }

    /** Add file changes (a subset of the commits range) to the context. */
    public static void addFilesChangeToContext(
            ContextManager contextManager,
            Chrome chrome,
            String newestCommitId,
            String oldestCommitId,
            List<ProjectFile> files) {
        contextManager.submitContextTask(() -> {
            try {
                if (files.isEmpty()) {
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No files provided to capture diff");
                    return;
                }
                var repo = contextManager.getProject().getRepo();

                var diffs = files.stream()
                        .map(file -> {
                            try {
                                return repo.getDiff(file, oldestCommitId + "^", newestCommitId);
                            } catch (GitAPIException e) {
                                logger.warn(e);
                                return "";
                            }
                        })
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.joining("\n\n"));
                if (diffs.isEmpty()) {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "No changes found for the selected files in the commit range");
                    return;
                }
                var newShort = ((GitRepo) repo).shortHash(newestCommitId);
                var oldShort = ((GitRepo) repo).shortHash(oldestCommitId);
                var shortHash =
                        newestCommitId.equals(oldestCommitId) ? newShort : "%s..%s".formatted(oldShort, newShort);

                var filesTxt = files.stream().map(ProjectFile::getFileName).collect(Collectors.joining(", "));
                var description = "Diff of %s [%s]".formatted(filesTxt, shortHash);

                var syntaxStyle = files.isEmpty()
                        ? SyntaxConstants.SYNTAX_STYLE_NONE
                        : SyntaxDetector.fromExtension(files.getFirst().extension());
                var fragment = new ContextFragment.StringFragment(contextManager, diffs, description, syntaxStyle);
                contextManager.addVirtualFragment(fragment);
                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Added changes for selected files in commit range to context");
            } catch (Exception ex) {
                chrome.toolError("Error adding file changes from range to context: " + ex.getMessage());
            }
        });
    }

    /**
     * Compare a single file from a specific commit to the local (working directory) version. If useParent=true,
     * compares the file's parent commit to local.
     */
    public static void showDiffVsLocal(
            ContextManager cm,
            Chrome chrome, // Pass Chrome for theme access
            String commitId,
            String filePath,
            boolean useParent) {
        var repo = cm.getProject().getRepo();
        var file = new ProjectFile(cm.getRoot(), filePath);

        cm.submitBackgroundTask("Loading compare-with-local for " + file.getFileName(), () -> {
            try {
                // 2) Figure out the base commit ID and title components
                String baseCommitId = commitId;
                String baseCommitTitle = commitId;
                String baseCommitShort = ((GitRepo) repo).shortHash(commitId);

                if (useParent) {
                    baseCommitId = commitId + "^";
                    baseCommitTitle = commitId + "^";
                    baseCommitShort = ((GitRepo) repo).shortHash(commitId) + "^";
                }

                // 3) Read old content from the base commit
                var oldContent = repo.getFileContent(baseCommitId, file);

                // 4) Create panel on Swing thread
                String finalOldContent = oldContent; // effectively final for lambda
                String finalBaseCommitTitle = baseCommitTitle;
                String finalBaseCommitId = baseCommitId; // effectively final for lambda
                String finalDialogTitle = "Diff: %s [Local vs %s]".formatted(file.getFileName(), baseCommitShort);

                SwingUtilities.invokeLater(() -> {
                    // Check if we already have a window showing this diff
                    var leftSource = new BufferSource.StringSource(
                            finalOldContent, finalBaseCommitTitle, file.toString(), finalBaseCommitId);
                    var rightSource = new BufferSource.FileSource(file.absPath().toFile(), file.toString());

                    if (DiffWindowManager.tryRaiseExistingWindow(List.of(leftSource), List.of(rightSource))) {
                        return; // Existing window raised, don't create new one
                    }

                    // No existing window found, create new one
                    var brokkDiffPanel = new BrokkDiffPanel.Builder(chrome.getTheme(), cm)
                            .leftSource(leftSource)
                            .rightSource(rightSource)
                            .build();
                    brokkDiffPanel.showInFrame(finalDialogTitle);
                });
            } catch (Exception ex) {
                cm.getIo().toolError("Error loading compare-with-local diff: " + ex.getMessage());
            }
            return null;
        });
    }

    /** Format commit date to show e.g. "HH:MM:SS today" if it is today's date. */
    public static String formatRelativeDate(Instant commitInstant, LocalDate today) {
        try {
            var now = Instant.now();
            var duration = Duration.between(commitInstant, now);
            // 1) seconds ago
            long seconds = duration.toSeconds();
            if (seconds < 60) {
                return "seconds ago";
            }

            // 2) minutes ago
            long minutes = duration.toMinutes();
            if (minutes < 60) {
                long n = Math.max(1, minutes); // avoid "0 minutes ago"
                return n + " minute" + (n == 1 ? "" : "s") + " ago";
            }

            // 2) hours ago (same calendar day)
            long hours = duration.toHours();
            var commitDate = commitInstant.atZone(ZoneId.systemDefault()).toLocalDate();
            if (hours < 24 && commitDate.equals(today)) {
                long n = Math.max(1, hours);
                return n + " hour" + (n == 1 ? "" : "s") + " ago";
            }

            // 3) yesterday
            if (commitDate.equals(today.minusDays(1))) {
                return "Yesterday";
            }

            var zdt = commitInstant.atZone(ZoneId.systemDefault());
            if (zdt.getYear() == today.getYear()) {
                // 4) older, same year: "d MMM" (e.g., 7 Apr)
                return zdt.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()));
            }

            // 5) previous years: "MMM yy" (e.g., Apr 23)
            return zdt.format(DateTimeFormatter.ofPattern("MMM yy", Locale.getDefault()));
        } catch (Exception e) {
            logger.debug("Could not format date: {}", commitInstant, e);
            return commitInstant.toString();
        }
    }

    /**
     * Error message returned by validation methods when owner/repo format is invalid.
     */
    private static final String INVALID_REPO_FORMAT_MSG =
            "Repository must be in the form 'owner/repo'. Check Settings → Project → Issues → GitHub or your git remote.";

    /**
     * Pattern for valid GitHub owner names: alphanumeric and hyphens only, 1-39 characters,
     * no leading/trailing hyphen, no consecutive hyphens.
     */
    private static final Pattern GITHUB_OWNER_PATTERN =
            Pattern.compile("^(?!.*--)[A-Za-z0-9]([A-Za-z0-9-]{0,37}[A-Za-z0-9])?$");

    /**
     * Pattern for valid GitHub repository names: alphanumeric, hyphens, underscores, dots,
     * 1-100 characters, no leading/trailing dot.
     */
    private static final Pattern GITHUB_REPO_PATTERN =
            Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,98}[A-Za-z0-9_]$|^[A-Za-z0-9_]$");

    /**
     * Pattern for valid hostname labels: alphanumeric and hyphens, no leading/trailing hyphen.
     */
    private static final Pattern HOSTNAME_LABEL_PATTERN =
            Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");

    /**
     * Holds a parsed "owner" and "repo" from a Git remote URL.
     */
    public record OwnerRepo(String owner, String repo) {}

    /**
     * Validates a GitHub owner name format.
     *
     * @param owner The owner name to validate (will be trimmed)
     * @return Optional.empty() if valid, or Optional.of(errorMessage) if invalid
     */
    public static Optional<String> validateOwnerFormat(@Nullable String owner) {
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
    public static Optional<String> validateRepoFormat(@Nullable String repo) {
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
    public static Optional<String> validateOwnerRepo(@Nullable String owner, @Nullable String repo) {
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

        // Validate owner: length 1-39, alphanumeric and hyphens only
        if (trimmedOwner.length() > 39
                || !GITHUB_OWNER_PATTERN.matcher(trimmedOwner).matches()) {
            return Optional.of(INVALID_REPO_FORMAT_MSG);
        }

        // Validate repo: length 1-100, alphanumeric/underscore/dot/hyphen, no leading/trailing dot
        if (trimmedRepo.length() > 100
                || trimmedRepo.equals(".")
                || trimmedRepo.equals("..")
                || !GITHUB_REPO_PATTERN.matcher(trimmedRepo).matches()) {
            return Optional.of(INVALID_REPO_FORMAT_MSG);
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
    public static OwnerRepo normalizeOwnerRepo(@Nullable String owner, @Nullable String repo) {
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
    public static String buildRepoSlug(@Nullable String owner, @Nullable String repo) {
        var normalized = normalizeOwnerRepo(owner, repo);
        return normalized.owner() + "/" + normalized.repo();
    }

    /**
     * Validates a combined "owner/repo" string using stricter GitHub-like constraints.
     *
     * @param full The full repository identifier in "owner/repo" format
     * @return Optional.empty() if valid, or Optional.of(errorMessage) if invalid
     */
    public static Optional<String> validateFullRepoName(@Nullable String full) {
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
    public static @Nullable OwnerRepo parseOwnerRepoFromUrl(String remoteUrl) {
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
    public static Optional<OwnerRepo> parseOwnerRepoFlexible(@Nullable String input) {
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
    public static Optional<String> normalizeGitHubHost(@Nullable String host) {
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
    public static Optional<String> validateGitHubHost(@Nullable String host) {
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

    /**
     * Capture the diff between two branches (e.g., HEAD vs. a selected feature branch) and add it to the context.
     *
     * @param cm The ContextManager instance.
     * @param chrome The Chrome instance for UI feedback.
     * @param baseBranchName The name of the base branch for comparison (e.g., "HEAD", or a specific branch name).
     * @param compareBranchName The name of the branch to compare against the base.
     */
    /** Open a BrokkDiffPanel showing all file changes in the specified commit. */
    public static void openCommitDiffPanel(ContextManager cm, Chrome chrome, ICommitInfo commitInfo) {
        var repo = cm.getProject().getRepo();

        cm.submitBackgroundTask("Opening diff for commit " + ((GitRepo) repo).shortHash(commitInfo.id()), () -> {
            try {
                var files = commitInfo.changedFiles();
                if (files.isEmpty()) {
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No files changed in this commit.");
                    return;
                }

                var parentId = commitInfo.id() + "^";
                var leftSources = new ArrayList<BufferSource>();
                var rightSources = new ArrayList<BufferSource>();

                for (var file : files) {
                    var oldContent = getFileContentOrEmpty(repo, parentId, file);
                    var newContent = getFileContentOrEmpty(repo, commitInfo.id(), file);

                    leftSources.add(new BufferSource.StringSource(oldContent, parentId, file.toString(), parentId));
                    rightSources.add(new BufferSource.StringSource(
                            newContent, commitInfo.id(), file.toString(), commitInfo.id()));
                }

                String shortId = ((GitRepo) repo).shortHash(commitInfo.id());
                var title = "Commit Diff: %s (%s)"
                        .formatted(commitInfo.message().lines().findFirst().orElse(""), shortId);

                SwingUtilities.invokeLater(() -> {
                    // Check if we already have a window showing this diff
                    if (DiffWindowManager.tryRaiseExistingWindow(leftSources, rightSources)) {
                        return; // Existing window raised, don't create new one
                    }

                    // No existing window found, create new one
                    var builder = new BrokkDiffPanel.Builder(chrome.getTheme(), cm);
                    for (int i = 0; i < leftSources.size(); i++) {
                        builder.addComparison(leftSources.get(i), rightSources.get(i));
                    }
                    builder.build().showInFrame(title);
                });
            } catch (Exception ex) {
                chrome.toolError("Error opening commit diff: " + ex.getMessage());
            }
        });
    }

    /** Open a BrokkDiffPanel showing all file changes in the specified commit with a specific file pre-selected. */
    public static void openCommitDiffPanel(
            ContextManager cm, Chrome chrome, ICommitInfo commitInfo, String targetFileName) {
        var repo = cm.getProject().getRepo();

        cm.submitBackgroundTask("Opening diff for commit " + ((GitRepo) repo).shortHash(commitInfo.id()), () -> {
            try {
                var files = commitInfo.changedFiles();
                if (files.isEmpty()) {
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No files changed in this commit.");
                    return;
                }

                var builder = new BrokkDiffPanel.Builder(chrome.getTheme(), cm);
                var parentId = commitInfo.id() + "^";

                // Track target file index
                int targetFileIndex = -1;
                int currentIndex = 0;

                for (var file : files) {
                    var oldContent = getFileContentOrEmpty(repo, parentId, file);
                    var newContent = getFileContentOrEmpty(repo, commitInfo.id(), file);

                    // Check if this is the target file
                    if (file.toString().equals(targetFileName)) {
                        targetFileIndex = currentIndex;
                    }

                    builder.addComparison(
                            new BufferSource.StringSource(oldContent, parentId, file.toString(), parentId),
                            new BufferSource.StringSource(
                                    newContent, commitInfo.id(), file.toString(), commitInfo.id()));
                    currentIndex++;
                }

                // Set initial file index to target file if found
                if (targetFileIndex >= 0) {
                    builder.setInitialFileIndex(targetFileIndex);
                }

                String shortId = ((GitRepo) repo).shortHash(commitInfo.id());
                var title = "Commit Diff: %s (%s)"
                        .formatted(commitInfo.message().lines().findFirst().orElse(""), shortId);
                SwingUtilities.invokeLater(() -> builder.build().showInFrame(title));
            } catch (Exception ex) {
                chrome.toolError("Error opening commit diff: " + ex.getMessage());
            }
        });
    }

    private static String getFileContentOrEmpty(IGitRepo repo, String commitId, ProjectFile file) {
        try {
            return repo.getFileContent(commitId, file);
        } catch (Exception e) {
            return ""; // File may be new or deleted
        }
    }

    public static void compareCommitToLocal(ContextManager contextManager, Chrome chrome, ICommitInfo commitInfo) {
        contextManager.submitBackgroundTask("Comparing commit to local", () -> {
            try {
                var changedFiles = commitInfo.changedFiles();
                if (changedFiles.isEmpty()) {
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No files changed in this commit");
                    return;
                }

                var repo = contextManager.getProject().getRepo();
                var shortId = ((GitRepo) repo).shortHash(commitInfo.id());
                var leftSources = new ArrayList<BufferSource>();
                var rightSources = new ArrayList<BufferSource>();

                for (var file : changedFiles) {
                    String commitContent = getFileContentOrEmpty(repo, commitInfo.id(), file);
                    leftSources.add(
                            new BufferSource.StringSource(commitContent, shortId, file.toString(), commitInfo.id()));
                    rightSources.add(new BufferSource.FileSource(file.absPath().toFile(), file.toString()));
                }

                SwingUtilities.invokeLater(() -> {
                    // Check if we already have a window showing this diff
                    if (DiffWindowManager.tryRaiseExistingWindow(leftSources, rightSources)) {
                        return; // Existing window raised, don't create new one
                    }

                    // No existing window found, create new one
                    var builder = new BrokkDiffPanel.Builder(chrome.getTheme(), contextManager);
                    for (int i = 0; i < leftSources.size(); i++) {
                        builder.addComparison(leftSources.get(i), rightSources.get(i));
                    }
                    var panel = builder.build();
                    panel.showInFrame("Compare " + shortId + " to Local");
                });
            } catch (Exception ex) {
                chrome.toolError("Error opening multi-file diff: " + ex.getMessage());
            }
        });
    }

    public static void captureDiffBetweenBranches(
            ContextManager cm, Chrome chrome, String baseBranchName, String compareBranchName) {
        var repo = cm.getProject().getRepo();

        cm.submitContextTask(() -> {
            try {
                var diff = repo.getDiff(baseBranchName, compareBranchName);
                if (diff.isEmpty()) {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            String.format("No differences found between %s and %s", compareBranchName, baseBranchName));
                    return;
                }
                var description = "Diff of %s vs %s".formatted(compareBranchName, baseBranchName);
                var fragment =
                        new ContextFragment.StringFragment(cm, diff, description, SyntaxConstants.SYNTAX_STYLE_NONE);
                cm.addVirtualFragment(fragment);
                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        String.format("Added diff of %s vs %s to context", compareBranchName, baseBranchName));
            } catch (Exception ex) {
                logger.warn(
                        "Error capturing diff between branches {} and {}: {}",
                        compareBranchName,
                        baseBranchName,
                        ex.getMessage(),
                        ex);
                chrome.toolError(String.format(
                        "Error capturing diff between %s and %s: %s",
                        compareBranchName, baseBranchName, ex.getMessage()));
            }
        });
    }

    /**
     * Rollback selected files to their state at a specific commit. This will overwrite the current working directory
     * versions of these files.
     */
    public static void rollbackFilesToCommit(
            ContextManager contextManager, Chrome chrome, String commitId, List<ProjectFile> files) {
        if (files.isEmpty()) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No files selected for rollback");
            return;
        }

        var repo = (GitRepo) contextManager.getProject().getRepo();
        var shortCommitId = repo.shortHash(commitId);

        contextManager.submitExclusiveAction(() -> {
            try {
                repo.checkoutFilesFromCommit(commitId, files);
                SwingUtilities.invokeLater(() -> {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            String.format(
                                    "Successfully rolled back %d file(s) to commit %s", files.size(), shortCommitId));
                    // Refresh Git panels to show the changed files
                    chrome.updateCommitPanel();
                });
            } catch (Exception e) {
                logger.error("Error rolling back files", e);
                SwingUtilities.invokeLater(() -> chrome.toolError("Error rolling back files: " + e.getMessage()));
            }
        });
    }

    /**
     * Formats a list of files for display in UI messages. Shows individual filenames for 3 or fewer files, otherwise
     * shows a count.
     *
     * @param files List of ProjectFile objects
     * @return A formatted string like "file1.java, file2.java" or "5 files"
     */
    public static String formatFileList(List<ProjectFile> files) {
        if (files.isEmpty()) {
            return "no files";
        }

        return files.size() <= 3
                ? files.stream().map(ProjectFile::getFileName).collect(Collectors.joining(", "))
                : files.size() + " files";
    }

    /**
     * Filters a list of modified files to include only text files (excludes binary files like images, PDFs, etc.).
     *
     * @param modifiedFiles The list of modified files to filter.
     * @return A list containing only the text files.
     */
    public static List<ProjectFile> filterTextFiles(List<GitRepo.ModifiedFile> modifiedFiles) {
        return modifiedFiles.stream()
                .map(GitRepo.ModifiedFile::file)
                .filter(BrokkFile::isText)
                .collect(Collectors.toList());
    }

    /**
     * Captures the diff of a pull request (between its head and its effective base) and adds it to the context.
     *
     * @param cm The ContextManager instance.
     * @param chrome The Chrome instance for UI feedback.
     * @param prTitle The title of the pull request.
     * @param prNumber The number of the pull request.
     * @param prHeadSha The SHA of the head commit of the pull request.
     * @param prBaseSha The SHA of the base commit of the pull request (as recorded by GitHub).
     * @param repo The GitRepo instance.
     */
    public static void capturePrDiffToContext(
            ContextManager cm,
            Chrome chrome,
            String prTitle,
            int prNumber,
            String prHeadSha,
            String prBaseSha,
            GitRepo repo) {
        cm.submitContextTask(() -> {
            try {
                String effectiveBaseSha = repo.getMergeBase(prHeadSha, prBaseSha);
                if (effectiveBaseSha == null) {
                    logger.warn(
                            "Could not determine merge base for PR #{} (head: {}, base: {}). Falling back to PR base SHA for diff.",
                            prNumber,
                            repo.shortHash(prHeadSha),
                            repo.shortHash(prBaseSha));
                    effectiveBaseSha = prBaseSha;
                }

                String diff = repo.getDiff(effectiveBaseSha, prHeadSha);
                if (diff.isEmpty()) {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            String.format(
                                    "No differences found for PR #%d (head: %s, effective base: %s)",
                                    prNumber, repo.shortHash(prHeadSha), repo.shortHash(effectiveBaseSha)));
                    return;
                }

                List<ProjectFile> changedFiles =
                        repo.listFilesChangedBetweenCommits(prHeadSha, effectiveBaseSha).stream()
                                .map(IGitRepo.ModifiedFile::file)
                                .collect(Collectors.toList());
                String fileNamesSummary = formatFileList(changedFiles);

                String description = String.format(
                        "Diff of PR #%d (%s): %s [HEAD: %s vs Base: %s]",
                        prNumber,
                        prTitle,
                        fileNamesSummary,
                        repo.shortHash(prHeadSha),
                        repo.shortHash(effectiveBaseSha));

                String syntaxStyle = SyntaxConstants.SYNTAX_STYLE_NONE;
                if (!changedFiles.isEmpty()) {
                    syntaxStyle =
                            SyntaxDetector.fromExtension(changedFiles.getFirst().extension());
                }

                var fragment = new ContextFragment.StringFragment(cm, diff, description, syntaxStyle);
                cm.addVirtualFragment(fragment);
                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        String.format("Added diff for PR #%d (%s) to context", prNumber, prTitle));

            } catch (Exception ex) {
                logger.warn("Error capturing diff for PR #{}: {}", prNumber, ex.getMessage(), ex);
                chrome.toolError(String.format("Error capturing diff for PR #%d: %s", prNumber, ex.getMessage()));
            }
        });
    }

    /**
     * Gets the current branch name from a project's Git repository.
     *
     * @param project The project to get the branch name from
     * @return The current branch name, or empty string if unable to retrieve
     */
    public static String getCurrentBranchName(IProject project) {
        try {
            if (!project.hasGit()) {
                return "";
            }
            IGitRepo repo = project.getRepo();
            if (repo instanceof GitRepo gitRepo) {
                return gitRepo.getCurrentBranch();
            }
        } catch (Exception e) {
            logger.warn("Could not get current branch name", e);
        }
        return "";
    }

    /**
     * Updates a panel's titled border to include the current branch name.
     *
     * @param panel The panel to update
     * @param baseTitle The base title (e.g., "Git", "Project Files")
     * @param branchName The current branch name (may be empty)
     */
    public static void updatePanelBorderWithBranch(@Nullable JPanel panel, String baseTitle, String branchName) {
        if (panel == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            var border = panel.getBorder();
            if (border instanceof TitledBorder titledBorder) {
                String newTitle = !branchName.isBlank() ? baseTitle + " (" + branchName + ")" : baseTitle;
                titledBorder.setTitle(newTitle);
                panel.revalidate();
                panel.repaint();
            }
        });
    }

    /** Extract file path from display format "filename - full/path" to just "full/path". */
    public static String extractFilePathFromDisplay(String displayText) {
        int dashIndex = displayText.indexOf(" - ");
        if (dashIndex != -1 && dashIndex < displayText.length() - 3) {
            return displayText.substring(dashIndex + 3);
        }
        return displayText;
    }

    /** Open a BrokkDiffPanel showing all file changes in a PR with a specific file pre-selected. */
    public static void openPrDiffPanel(
            ContextManager contextManager, Chrome chrome, GHPullRequest pr, String targetFileName) {
        String targetFilePath = extractFilePathFromDisplay(targetFileName);

        contextManager.submitBackgroundTask("Opening PR diff", () -> {
            try {
                var repo = (GitRepo) contextManager.getProject().getRepo();

                String prHeadSha = pr.getHead().getSha();
                String prBaseSha = pr.getBase().getSha();
                String prHeadFetchRef = String.format(
                        "+refs/pull/%d/head:refs/remotes/origin/pr/%d/head", pr.getNumber(), pr.getNumber());
                String prBaseBranchName = pr.getBase().getRef();
                String prBaseFetchRef =
                        String.format("+refs/heads/%s:refs/remotes/origin/%s", prBaseBranchName, prBaseBranchName);

                if (!ensureShaIsLocal(repo, prHeadSha, prHeadFetchRef, "origin")) {
                    chrome.toolError(
                            "Could not make PR head commit " + repo.shortHash(prHeadSha) + " available locally.",
                            "Diff Error");
                    return null;
                }
                if (!ensureShaIsLocal(repo, prBaseSha, prBaseFetchRef, "origin")) {
                    logger.warn(
                            "PR base commit {} might not be available locally after fetching {}. Diff might be based on a different merge-base.",
                            repo.shortHash(prBaseSha),
                            prBaseFetchRef);
                }

                var modifiedFiles = repo.listFilesChangedBetweenBranches(prHeadSha, prBaseSha);

                if (modifiedFiles.isEmpty()) {
                    chrome.systemNotify(
                            PrTitleFormatter.formatNoChangesMessage(pr.getNumber()),
                            "Diff Info",
                            JOptionPane.INFORMATION_MESSAGE);
                    return null;
                }

                var builder = new BrokkDiffPanel.Builder(chrome.getTheme(), contextManager)
                        .setMultipleCommitsContext(true)
                        .setRootTitle(PrTitleFormatter.formatPrRoot(pr));

                // Add all files in natural order and track target file index
                int targetFileIndex = -1;
                int currentIndex = 0;

                for (var mf : modifiedFiles) {
                    var projectFile = mf.file();
                    var status = mf.status();

                    BufferSource leftSource, rightSource;

                    if (status == ModificationType.DELETED) {
                        // Deleted: left side has content from base, right side is empty (but still track head SHA for
                        // context)
                        leftSource = new BufferSource.StringSource(
                                repo.getFileContent(prBaseSha, projectFile),
                                prBaseSha,
                                projectFile.toString(),
                                prBaseSha);
                        rightSource = new BufferSource.StringSource(
                                "", prHeadSha + " (Deleted)", projectFile.toString(), prHeadSha);
                    } else if (status == ModificationType.NEW) {
                        // New: left side is empty (but still track base SHA for context), right side has content from
                        // head
                        leftSource = new BufferSource.StringSource(
                                "", prBaseSha + " (New)", projectFile.toString(), prBaseSha);
                        rightSource = new BufferSource.StringSource(
                                repo.getFileContent(prHeadSha, projectFile),
                                prHeadSha,
                                projectFile.toString(),
                                prHeadSha);
                    } else { // modified
                        leftSource = new BufferSource.StringSource(
                                repo.getFileContent(prBaseSha, projectFile),
                                prBaseSha,
                                projectFile.toString(),
                                prBaseSha);
                        rightSource = new BufferSource.StringSource(
                                repo.getFileContent(prHeadSha, projectFile),
                                prHeadSha,
                                projectFile.toString(),
                                prHeadSha);
                    }

                    // Check if this is the target file
                    if (projectFile.toString().equals(targetFilePath)) {
                        targetFileIndex = currentIndex;
                    }

                    builder.addComparison(leftSource, rightSource);
                    currentIndex++;
                }

                // Set initial file index to target file if found
                if (targetFileIndex >= 0) {
                    builder.setInitialFileIndex(targetFileIndex);
                }

                SwingUtilities.invokeLater(() -> {
                    var diffPanel = builder.build();
                    diffPanel.showInFrame(PrTitleFormatter.formatDiffTitle(pr));
                });

            } catch (Exception ex) {
                logger.error(
                        "Error opening PR diff viewer for PR #{} with file '{}'", pr.getNumber(), targetFileName, ex);
                chrome.toolError(PrTitleFormatter.formatDiffError(pr.getNumber(), ex.getMessage()), "Diff Error");
            }
            return null;
        });
    }

    /**
     * Checks if a commit's data is fully available and parsable in the local repository.
     *
     * @param repo The GitRepo instance.
     * @param sha The commit SHA to check.
     * @return true if the commit is resolvable and its object data is parsable, false otherwise.
     */
    public static boolean isCommitLocallyAvailable(GitRepo repo, String sha) {
        return repo.remote().isCommitLocallyAvailable(sha);
    }

    /**
     * Ensure a commit SHA is available locally and is fully parsable, fetching the specified refSpec from the remote if
     * necessary.
     *
     * @param sha The commit SHA that must be present and parsable locally.
     * @param repo GitRepo to operate on (non-null).
     * @param refSpec The refSpec to fetch if the SHA is missing or not parsable (e.g.
     *     "+refs/pull/123/head:refs/remotes/origin/pr/123/head").
     * @param remoteName Which remote to fetch from (e.g. "origin").
     * @return true if the SHA is now locally available and parsable, false otherwise.
     */
    public static boolean ensureShaIsLocal(GitRepo repo, String sha, String refSpec, String remoteName) {
        return repo.remote().ensureShaIsLocal(sha, refSpec, remoteName);
    }

    /**
     * Builds a concise commit label such as<br>
     * 'First line …' [abcdef1] <br>
     * If {@code rawTitle} is just a hash, we try to look up the commit message from {@code repo}. When the lookup fails
     * we fall back to the hash only.
     */
    public static String friendlyCommitLabel(@Nullable String rawTitle, @Nullable GitRepo repo) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return "";
        }

        // Detect a leading full hash (40 hex chars) or short hash (≥7 hex chars)
        var matcher =
                Pattern.compile("^(?<hash>[a-fA-F0-9]{7,40})\\s*(?<msg>.*)$").matcher(rawTitle.strip());
        String hash;
        String msg;
        if (matcher.matches()) {
            hash = matcher.group("hash");
            msg = matcher.group("msg");
        } else {
            // Try to find the hash at the end in brackets, e.g. "Some title (abcdef123)"
            var tailMatcher = Pattern.compile("^(?<msg>.*)\\s+\\((?<hash>[a-fA-F0-9]{7,40})\\)$")
                    .matcher(rawTitle.strip());
            if (tailMatcher.matches()) {
                hash = tailMatcher.group("hash");
                msg = tailMatcher.group("msg");
            } else {
                // No recognisable hash – just truncate the string we have
                return truncateWithEllipsis(rawTitle.trim(), 40);
            }
        }

        String shortHash = (repo != null) ? repo.shortHash(hash) : "";

        // If we still have no message, and a repo is available, try to look it up
        if ((msg == null || msg.isBlank()) && repo != null) {
            try {
                var infoOpt = repo.getLocalCommitInfo(hash);
                if (infoOpt.isPresent()) {
                    msg = infoOpt.get().message();
                }
            } catch (Exception ignore) {
                /* lookup failure is non-fatal */
            }
        }

        // Final formatting
        msg = (msg == null ? "" : truncateWithEllipsis(msg.split("\\R", 2)[0].trim(), 30));
        return msg.isBlank() ? "[%s]".formatted(shortHash) : "'%s' [%s]".formatted(msg, shortHash);
    }

    /** Truncates s to maxLen characters, appending '...' if needed. */
    private static String truncateWithEllipsis(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 2) + "...";
    }
}
