package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferSource;
import io.github.jbellis.brokk.git.ICommitInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import io.github.jbellis.brokk.util.SyntaxDetector;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.base.Splitter;
import org.jetbrains.annotations.Nullable;

/**
 * Static utilities for showing diffs, capturing diffs, or editing files
 * in the Git UI, removing duplicated code across multiple panels.
 */
public final class GitUiUtil {
    private static final Logger logger = LogManager.getLogger(GitUiUtil.class);

    private GitUiUtil() {
    }

    /**
     * Shortens a commit ID to 7 characters for display purposes.
     *
     * @param commitId The full commit ID, may be null
     * @return The shortened commit ID, or the original if null or shorter than 7 characters
     */
    public static String shortenCommitId(String commitId) {
        return commitId.length() >= 7 ? commitId.substring(0, 7) : commitId;
    }

    /**
     * Capture uncommitted diffs for the specified files, adding the result to the context.
     */
    public static void captureUncommittedDiff(ContextManager contextManager, Chrome chrome, List<ProjectFile> selectedFiles)
    {
        if (selectedFiles.isEmpty()) {
            chrome.systemOutput("No files selected to capture diff");
            return;
        }
        var repo = contextManager.getProject().getRepo();

        contextManager.submitContextTask("Capturing uncommitted diff", () -> {
            try {
                var diff = repo.diffFiles(selectedFiles);
                if (diff.isEmpty()) {
                    chrome.systemOutput("No uncommitted changes found for selected files");
                    return;
                }
                var description = "Diff of %s".formatted(formatFileList(selectedFiles));
                var syntaxStyle = selectedFiles.isEmpty() ? SyntaxConstants.SYNTAX_STYLE_NONE : SyntaxDetector.fromExtension(selectedFiles.getFirst().extension());
                var fragment = new ContextFragment.StringFragment(contextManager, diff, description, syntaxStyle);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added uncommitted diff for " + selectedFiles.size() + " file(s) to context");
            } catch (Exception ex) {
                chrome.toolError("Error capturing uncommitted diff: " + ex.getMessage());
            }
        });
    }

    /**
     * Open a file in the project’s editor.
     */
    public static void editFile(ContextManager contextManager, String filePath) {
        contextManager.submitContextTask("Adding file to context", () -> {
            var file = contextManager.toFile(filePath);
            contextManager.editFiles(List.of(file));
        });
    }

    /**
     * Capture a single file’s historical changes into the context (HEAD vs commitId).
     */
    public static void addFileChangeToContext(ContextManager contextManager, Chrome chrome, String commitId, ProjectFile file)
    {
        var repo = contextManager.getProject().getRepo();

        contextManager.submitContextTask("Adding file change to context", () -> {
            try {
                var diff = repo.showFileDiff(commitId + "^", commitId, file);
                if (diff.isEmpty()) {
                    chrome.systemOutput("No changes found for " + file.getFileName());
                    return;
                }
                var shortHash = shortenCommitId(commitId);
                var description = "Diff of %s [%s]".formatted(file.getFileName(), shortHash);
                var syntaxStyle = SyntaxDetector.fromExtension(file.extension());
                var fragment = new ContextFragment.StringFragment(contextManager, diff, description, syntaxStyle);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added changes for " + file.getFileName() + " to context");
            } catch (Exception e) {
                chrome.toolError("Error adding file change to context: " + e.getMessage());
            }
        });
    }

    /**
     * Show the diff for a single file at a specific commit.
     */
    public static void showFileHistoryDiff(ContextManager cm, Chrome chrome, // Pass Chrome for theme access
                                           String commitId, ProjectFile file)
    {
        var repo = cm.getProject().getRepo();

        var shortCommitId = shortenCommitId(commitId);
        var dialogTitle = "Diff: " + file.getFileName() + " (" + shortCommitId + ")";
        var parentCommitId = commitId + "^";

        cm.submitBackgroundTask("Loading history diff for " + file.getFileName(), () -> {
            try {
                var parentContent = repo.getFileContent(parentCommitId, file);
                var commitContent = repo.getFileContent(commitId, file);

                SwingUtilities.invokeLater(() -> {
                    var brokkDiffPanel = new BrokkDiffPanel.Builder(chrome.themeManager, cm).leftSource(new BufferSource.StringSource(parentContent, parentCommitId, file.toString())).rightSource(new BufferSource.StringSource(commitContent, commitId, file.toString())).build();
                    brokkDiffPanel.showInFrame(dialogTitle);
                });
            } catch (Exception ex) {
                cm.getIo().toolError("Error loading history diff: " + ex.getMessage());
            }
            return null;
        });
    }

    /**
     * View the file content at a specific commit (opens it in a preview window).
     */
    public static void viewFileAtRevision(ContextManager cm, Chrome chrome, String commitId, String filePath)
    {
        var repo = cm.getProject().getRepo();

        cm.submitUserTask("Viewing file at revision", () -> {
            var file = new ProjectFile(cm.getRoot(), filePath);
            try {
                final String content = repo.getFileContent(commitId, file);
                SwingUtilities.invokeLater(() -> {
                    var fragment = new ContextFragment.GitFileFragment(file, commitId, content);
                    chrome.openFragmentPreview(fragment);
                });
            } catch (GitAPIException e) {
                logger.warn(e);
                chrome.systemOutput("Error retrieving file content: " + e.getMessage());
            }
        });
    }

    /**
     * Captures the diff for a range of commits, defined by the chronologically newest and oldest
     * ICommitInfo objects in the selection, and adds it to the context.
     * The diff is calculated from the parent of the oldest commit in the range up to the newest commit.
     *
     * @param contextManager      The ContextManager instance.
     * @param chrome              The Chrome instance for UI feedback.
     * @param newestCommitInSelection The ICommitInfo for the newest commit in the selected range.
     * @param oldestCommitInSelection The ICommitInfo for the oldest commit in the selected range.
     */
    public static void addCommitRangeToContext
    (
            ContextManager contextManager,
            Chrome chrome,
            ICommitInfo newestCommitInSelection,
            ICommitInfo oldestCommitInSelection
    ) {
        String taskDescription = String.format("Capturing diff from %s to %s",
                                               shortenCommitId(oldestCommitInSelection.id()),
                                               shortenCommitId(newestCommitInSelection.id()));
        contextManager.submitContextTask(taskDescription, () -> {
            try {
                var repo = contextManager.getProject().getRepo();
                var newestCommitId = newestCommitInSelection.id();
                var oldestCommitId = oldestCommitInSelection.id();

                // Diff is from oldestCommit's parent up to newestCommit.
                String diff = repo.showDiff(newestCommitId, oldestCommitId + "^");
                if (diff.isEmpty()) {
                    chrome.systemOutput("No changes found in the selected commit range");
                    return;
                }

                List<ProjectFile> changedFiles;
                if (newestCommitId.equals(oldestCommitId)) { // Single commit selected
                    changedFiles = newestCommitInSelection.changedFiles();
                } else {
                    // Files changed between oldest selected commit's parent and newest selected commit
                    changedFiles = repo.listFilesChangedBetweenCommits(newestCommitId, oldestCommitId + "^");
                }

                var fileNamesSummary = formatFileList(changedFiles);

                var newestShort = shortenCommitId(newestCommitId);
                var oldestShort = shortenCommitId(oldestCommitId);
                var hashTxt = newestCommitId.equals(oldestCommitId)
                              ? newestShort
                              : oldestShort + ".." + newestShort;

                var description = "Diff of %s [%s]".formatted(fileNamesSummary, hashTxt);

                var syntaxStyle = changedFiles.isEmpty() ? SyntaxConstants.SYNTAX_STYLE_NONE :
                                  SyntaxDetector.fromExtension(changedFiles.getFirst().extension());
                var fragment = new ContextFragment.StringFragment(contextManager, diff, description, syntaxStyle);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added changes for commit range to context");
            } catch (Exception ex) {
                chrome.toolError("Error adding commit range to context: " + ex.getMessage());
            }
        });
    }

    /**
     * Groups contiguous integers from a sorted array into sub-lists.
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

    /**
     * Add file changes (a subset of the commits range) to the context.
     */
    public static void addFilesChangeToContext(ContextManager contextManager, Chrome chrome, String firstCommitId, String lastCommitId, List<ProjectFile> files)
    {
        contextManager.submitContextTask("Adding file changes from range to context", () -> {
            try {
                if (files.isEmpty()) {
                    chrome.systemOutput("No files provided to capture diff");
                    return;
                }
                var repo = contextManager.getProject().getRepo();

                var diffs = files.stream().map(file -> {
                    try {
                        return repo.showFileDiff(firstCommitId, lastCommitId + "^", file);
                    } catch (GitAPIException e) {
                        logger.warn(e);
                        return "";
                    }
                }).filter(s -> !s.isEmpty()).collect(Collectors.joining("\n\n"));
                if (diffs.isEmpty()) {
                    chrome.systemOutput("No changes found for the selected files in the commit range");
                    return;
                }
                var firstShort = shortenCommitId(firstCommitId);
                var lastShort = shortenCommitId(lastCommitId);
                var shortHash = firstCommitId.equals(lastCommitId) ? firstShort : "%s..%s".formatted(firstShort, lastShort);

                var filesTxt = files.stream().map(ProjectFile::getFileName).collect(Collectors.joining(", "));
                var description = "Diff of %s [%s]".formatted(filesTxt, shortHash);

                var syntaxStyle = files.isEmpty() ? SyntaxConstants.SYNTAX_STYLE_NONE : SyntaxDetector.fromExtension(files.getFirst().extension());
                var fragment = new ContextFragment.StringFragment(contextManager, diffs, description, syntaxStyle);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added changes for selected files in commit range to context");
            } catch (Exception ex) {
                chrome.toolError("Error adding file changes from range to context: " + ex.getMessage());
            }
        });
    }

    /**
     * Compare a single file from a specific commit to the local (working directory) version.
     * If useParent=true, compares the file's parent commit to local.
     */
    public static void showDiffVsLocal(ContextManager cm, Chrome chrome, // Pass Chrome for theme access
                                       String commitId, String filePath, boolean useParent)
    {
        var repo = cm.getProject().getRepo();
        var file = new ProjectFile(cm.getRoot(), filePath);

        cm.submitBackgroundTask("Loading compare-with-local for " + file.getFileName(), () -> {
            try {
                // 2) Figure out the base commit ID and title components
                String baseCommitId = commitId;
                String baseCommitTitle = commitId;
                String baseCommitShort = shortenCommitId(commitId);

                if (useParent) {
                    baseCommitId = commitId + "^";
                    baseCommitTitle = commitId + "^";
                    baseCommitShort = shortenCommitId(commitId) + "^";
                }

                // 3) Read old content from the base commit
                var oldContent = repo.getFileContent(baseCommitId, file);

                // 4) Create panel on Swing thread
                String finalOldContent = oldContent; // effectively final for lambda
                String finalBaseCommitTitle = baseCommitTitle;
                String finalDialogTitle = "Diff: %s [Local vs %s]".formatted(file.getFileName(), baseCommitShort);

                SwingUtilities.invokeLater(() -> {
                    var brokkDiffPanel = new BrokkDiffPanel.Builder(chrome.themeManager, cm).leftSource(new BufferSource.StringSource(finalOldContent, finalBaseCommitTitle, file.toString())).rightSource(new BufferSource.FileSource(file.absPath().toFile(), file.toString())).build();
                    brokkDiffPanel.showInFrame(finalDialogTitle);
                });
            } catch (Exception ex) {
                cm.getIo().toolError("Error loading compare-with-local diff: " + ex.getMessage());
            }
            return null;
        });
    }

    /**
     * Format commit date to show e.g. "HH:MM:SS today" if it is today's date.
     */
    public static String formatRelativeDate(java.time.Instant commitInstant, java.time.LocalDate today) {
        try {
            var now = java.time.Instant.now();
            var duration = java.time.Duration.between(commitInstant, now);
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
            var commitDate = commitInstant.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            if (hours < 24 && commitDate.equals(today)) {
                long n = Math.max(1, hours);
                return n + " hour" + (n == 1 ? "" : "s") + " ago";
            }

            // 3) yesterday
            if (commitDate.equals(today.minusDays(1))) {
                return "Yesterday";
            }

            var zdt = commitInstant.atZone(java.time.ZoneId.systemDefault());
            if (zdt.getYear() == today.getYear()) {
                // 4) older, same year: "d MMM" (e.g., 7 Apr)
                return zdt.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()));
            }

            // 5) previous years: "MMM yy" (e.g., Apr 23)
            return zdt.format(java.time.format.DateTimeFormatter.ofPattern("MMM yy", Locale.getDefault()));
        } catch (Exception e) {
            logger.debug("Could not format date: {}", commitInstant, e);
            return commitInstant.toString();
        }
    }

    /**
     * Holds a parsed "owner" and "repo" from a Git remote URL.
     */
    public record OwnerRepo(String owner, String repo) {
    }

    /**
     * Parse a Git remote URL of form:
     * - https://github.com/OWNER/REPO.git
     * - git@github.com:OWNER/REPO.git
     * - ssh://github.com/OWNER/REPO
     * - or any variant that ends with OWNER/REPO(.git)
     * This attempts to extract the last two path segments
     * as "owner" and "repo". Returns null if it cannot.
     */
    public static @Nullable OwnerRepo parseOwnerRepoFromUrl(String remoteUrl) {
        if (remoteUrl.isBlank()) {
            logger.warn("Remote URL is blank for parsing owner/repo.");
            return null;
        }

        // Strip trailing ".git" if present
        String cleaned = remoteUrl.endsWith(".git") ? remoteUrl.substring(0, remoteUrl.length() - 4) : remoteUrl;

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
        var segments = Splitter.on(Pattern.compile("[/:]+")).omitEmptyStrings() // Important to handle cases like "host:/path" or "host//path"
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
            logger.warn("Parsed blank owner or repo from remote URL: {} (owner: '{}', repo: '{}')", remoteUrl, owner, repo);
            return null;
        }
        logger.debug("Parsed owner '{}' and repo '{}' from URL '{}'", owner, repo, remoteUrl);
        return new OwnerRepo(owner, repo);
    }

    /**
     * Capture the diff between two branches (e.g., HEAD vs. a selected feature branch)
     * and add it to the context.
     *
     * @param cm               The ContextManager instance.
     * @param chrome           The Chrome instance for UI feedback.
     * @param baseBranchName   The name of the base branch for comparison (e.g., "HEAD", or a specific branch name).
     * @param compareBranchName The name of the branch to compare against the base.
     */
    /**
     * Open a BrokkDiffPanel showing all file changes in the specified commit.
     */
    public static void openCommitDiffPanel(ContextManager cm, Chrome chrome, io.github.jbellis.brokk.git.ICommitInfo commitInfo)
    {
        var repo = cm.getProject().getRepo();

        cm.submitUserTask("Opening diff for commit " + shortenCommitId(commitInfo.id()), () -> {
            try {
                var files = commitInfo.changedFiles();
                if (files.isEmpty()) {
                    chrome.systemOutput("No files changed in this commit.");
                    return;
                }

                var builder = new BrokkDiffPanel.Builder(chrome.themeManager, cm);
                var parentId = commitInfo.id() + "^";

                for (var file : files) {
                    var oldContent = getFileContentOrEmpty(repo, parentId, file);
                    var newContent = getFileContentOrEmpty(repo, commitInfo.id(), file);

                    builder.addComparison(new BufferSource.StringSource(oldContent, parentId, file.getFileName()), new BufferSource.StringSource(newContent, commitInfo.id(), file.getFileName()));
                }

                var title = "Commit Diff: %s (%s)".formatted(commitInfo.message().lines().findFirst().orElse(""), shortenCommitId(commitInfo.id()));
                SwingUtilities.invokeLater(() -> builder.build().showInFrame(title));
            } catch (Exception ex) {
                chrome.toolError("Error opening commit diff: " + ex.getMessage());
            }
        });
    }

    private static String getFileContentOrEmpty(io.github.jbellis.brokk.git.IGitRepo repo, String commitId, ProjectFile file) {
        try {
            return repo.getFileContent(commitId, file);
        } catch (Exception e) {
            return ""; // File may be new or deleted
        }
    }

    public static void compareCommitToLocal(ContextManager contextManager, Chrome chrome, ICommitInfo commitInfo) {
        contextManager.submitUserTask("Opening multi-file diff to local", () -> {
            try {
                var changedFiles = commitInfo.changedFiles();
                if (changedFiles.isEmpty()) {
                    chrome.systemOutput("No files changed in this commit");
                    return;
                }

                var builder = new BrokkDiffPanel.Builder(chrome.themeManager, contextManager);
                var repo = contextManager.getProject().getRepo();
                var shortId = shortenCommitId(commitInfo.id());

                for (var file : changedFiles) {
                    String commitContent = getFileContentOrEmpty(repo, commitInfo.id(), file);
                    var leftSource = new BufferSource.StringSource(commitContent, shortId, file.getFileName());
                    var rightSource = new BufferSource.FileSource(file.absPath().toFile(), file.getFileName());
                    builder.addComparison(leftSource, rightSource);
                }

                SwingUtilities.invokeLater(() -> {
                    var panel = builder.build();
                    panel.showInFrame("Compare " + shortId + " to Local");
                });
            } catch (Exception ex) {
                chrome.toolError("Error opening multi-file diff: " + ex.getMessage());
            }
        });
    }

    public static void captureDiffBetweenBranches(ContextManager cm, Chrome chrome, String baseBranchName, String compareBranchName)
    {
        var repo = cm.getProject().getRepo();

        cm.submitContextTask("Capturing diff between " + compareBranchName + " and " + baseBranchName, () -> {
            try {
                var diff = repo.showDiff(compareBranchName, baseBranchName);
                if (diff.isEmpty()) {
                    chrome.systemOutput(String.format("No differences found between %s and %s", compareBranchName, baseBranchName));
                    return;
                }
                var description = "Diff of %s vs %s".formatted(compareBranchName, baseBranchName);
                var fragment = new ContextFragment.StringFragment(cm, diff, description, SyntaxConstants.SYNTAX_STYLE_NONE);
                cm.addVirtualFragment(fragment);
                chrome.systemOutput(String.format("Added diff of %s vs %s to context", compareBranchName, baseBranchName));
            } catch (Exception ex) {
                logger.warn("Error capturing diff between branches {} and {}: {}", compareBranchName, baseBranchName, ex.getMessage(), ex);
                chrome.toolError(String.format("Error capturing diff between %s and %s: %s", compareBranchName, baseBranchName, ex.getMessage()));
            }
        });
    }

    /**
     * Rollback selected files to their state at a specific commit.
     * This will overwrite the current working directory versions of these files.
     */
    public static void rollbackFilesToCommit(ContextManager contextManager, Chrome chrome, String commitId, List<ProjectFile> files)
    {
        if (files.isEmpty()) {
            chrome.systemOutput("No files selected for rollback");
            return;
        }

        var shortCommitId = shortenCommitId(commitId);

        var repo = (io.github.jbellis.brokk.git.GitRepo) contextManager.getProject().getRepo();

        contextManager.submitUserTask("Rolling back files to commit " + shortCommitId, () -> {
            try {
                repo.checkoutFilesFromCommit(commitId, files);
                SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput(String.format("Successfully rolled back %d file(s) to commit %s", files.size(), shortCommitId));
                    // Refresh Git panels to show the changed files
                    var gitPanel = chrome.getGitPanel();
                    if (gitPanel != null) {
                        gitPanel.updateCommitPanel();
                    }
                });
            } catch (Exception e) {
                logger.error("Error rolling back files", e);
                SwingUtilities.invokeLater(() -> chrome.toolError("Error rolling back files: " + e.getMessage()));
            }
        });
    }

    /**
     * Formats a list of files for display in UI messages.
     * Shows individual filenames for 3 or fewer files, otherwise shows a count.
     *
     * @param files List of ProjectFile objects
     * @return A formatted string like "file1.java, file2.java" or "5 files"
     */
    public static String formatFileList(List<ProjectFile> files) {
        if (files.isEmpty()) {
            return "no files";
        }

        return files.size() <= 3 ? files.stream().map(ProjectFile::getFileName).collect(Collectors.joining(", ")) : files.size() + " files";
    }

    /**
     * Captures the diff of a pull request (between its head and its effective base) and adds it to the context.
     *
     * @param cm        The ContextManager instance.
     * @param chrome    The Chrome instance for UI feedback.
     * @param prTitle   The title of the pull request.
     * @param prNumber  The number of the pull request.
     * @param prHeadSha The SHA of the head commit of the pull request.
     * @param prBaseSha The SHA of the base commit of the pull request (as recorded by GitHub).
     * @param repo      The GitRepo instance.
     */
    public static void capturePrDiffToContext(ContextManager cm, Chrome chrome, String prTitle, int prNumber, String prHeadSha, String prBaseSha, io.github.jbellis.brokk.git.GitRepo repo)
    {
        cm.submitContextTask("Capturing diff for PR #" + prNumber, () -> {
            try {
                String effectiveBaseSha = repo.getMergeBase(prHeadSha, prBaseSha);
                if (effectiveBaseSha == null) {
                    logger.warn("Could not determine merge base for PR #{} (head: {}, base: {}). Falling back to PR base SHA for diff.", prNumber, shortenCommitId(prHeadSha), shortenCommitId(prBaseSha));
                    effectiveBaseSha = prBaseSha;
                }

                String diff = repo.showDiff(prHeadSha, effectiveBaseSha);
                if (diff.isEmpty()) {
                    chrome.systemOutput(String.format("No differences found for PR #%d (head: %s, effective base: %s)", prNumber, shortenCommitId(prHeadSha), shortenCommitId(effectiveBaseSha)));
                    return;
                }

                List<ProjectFile> changedFiles = repo.listFilesChangedBetweenCommits(prHeadSha, effectiveBaseSha);
                String fileNamesSummary = formatFileList(changedFiles);

                String description = String.format("Diff of PR #%d (%s): %s [HEAD: %s vs Base: %s]", prNumber, prTitle, fileNamesSummary, shortenCommitId(prHeadSha), shortenCommitId(effectiveBaseSha));

                String syntaxStyle = SyntaxConstants.SYNTAX_STYLE_NONE;
                if (!changedFiles.isEmpty()) {
                    syntaxStyle = SyntaxDetector.fromExtension(changedFiles.getFirst().extension());
                }

                var fragment = new ContextFragment.StringFragment(cm, diff, description, syntaxStyle);
                cm.addVirtualFragment(fragment);
                chrome.systemOutput(String.format("Added diff for PR #%d (%s) to context", prNumber, prTitle));

            } catch (Exception ex) {
                logger.warn("Error capturing diff for PR #{}: {}", prNumber, ex.getMessage(), ex);
                chrome.toolError(String.format("Error capturing diff for PR #%d: %s", prNumber, ex.getMessage()));
            }
        });
    }
}

