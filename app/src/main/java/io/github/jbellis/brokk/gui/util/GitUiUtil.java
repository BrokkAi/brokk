package io.github.jbellis.brokk.gui.util;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferSource;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.ICommitInfo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.DiffWindowManager;
import io.github.jbellis.brokk.gui.PrTitleFormatter;
import io.github.jbellis.brokk.util.SyntaxDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.JOptionPane;
import javax.swing.border.TitledBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
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
                var diff = repo.showFileDiff(commitId + "^", commitId, file);
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
                String diff = repo.showDiff(newestCommitId, oldestCommitId + "^");
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
                    changedFiles = repo.listFilesChangedBetweenCommits(newestCommitId, oldestCommitId + "^");
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
            String firstCommitId,
            String lastCommitId,
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
                                return repo.showFileDiff(firstCommitId, lastCommitId + "^", file);
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
                var firstShort = ((GitRepo) repo).shortHash(firstCommitId);
                var lastShort = ((GitRepo) repo).shortHash(lastCommitId);
                var shortHash =
                        firstCommitId.equals(lastCommitId) ? firstShort : "%s..%s".formatted(firstShort, lastShort);

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
            var commitDate =
                    commitInstant.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
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

    /** Holds a parsed "owner" and "repo" from a Git remote URL. */
    public record OwnerRepo(String owner, String repo) {}

    /**
     * Parse a Git remote URL of form: - https://github.com/OWNER/REPO.git - git@github.com:OWNER/REPO.git -
     * ssh://github.com/OWNER/REPO - or any variant that ends with OWNER/REPO(.git) This attempts to extract the last
     * two path segments as "owner" and "repo". Returns null if it cannot.
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
        logger.debug("Parsed owner '{}' and repo '{}' from URL '{}'", owner, repo, remoteUrl);
        return new OwnerRepo(owner, repo);
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
    public static void openCommitDiffPanel(
            ContextManager cm, Chrome chrome, io.github.jbellis.brokk.git.ICommitInfo commitInfo) {
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
            ContextManager cm,
            Chrome chrome,
            io.github.jbellis.brokk.git.ICommitInfo commitInfo,
            String targetFileName) {
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

    private static String getFileContentOrEmpty(
            io.github.jbellis.brokk.git.IGitRepo repo, String commitId, ProjectFile file) {
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
                var diff = repo.showDiff(compareBranchName, baseBranchName);
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
                .filter(io.github.jbellis.brokk.analyzer.BrokkFile::isText)
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

                String diff = repo.showDiff(prHeadSha, effectiveBaseSha);
                if (diff.isEmpty()) {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            String.format(
                                    "No differences found for PR #%d (head: %s, effective base: %s)",
                                    prNumber, repo.shortHash(prHeadSha), repo.shortHash(effectiveBaseSha)));
                    return;
                }

                List<ProjectFile> changedFiles = repo.listFilesChangedBetweenCommits(prHeadSha, effectiveBaseSha);
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

                    if ("deleted".equals(status)) {
                        // Deleted: left side has content from base, right side is empty (but still track head SHA for
                        // context)
                        leftSource = new BufferSource.StringSource(
                                repo.getFileContent(prBaseSha, projectFile),
                                prBaseSha,
                                projectFile.toString(),
                                prBaseSha);
                        rightSource = new BufferSource.StringSource(
                                "", prHeadSha + " (Deleted)", projectFile.toString(), prHeadSha);
                    } else if ("new".equals(status)) {
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

    /** Helper method to check if a commit is locally available. */
    private static boolean isCommitLocallyAvailable(GitRepo repo, String sha) {
        ObjectId objectId = null;
        try {
            objectId = repo.resolve(sha);
            // Try to parse the commit to ensure its data is present
            try (RevWalk revWalk = new RevWalk(repo.getGit().getRepository())) {
                revWalk.parseCommit(objectId);
                return true; // Resolvable and parsable
            }
        } catch (MissingObjectException e) {
            logger.debug(
                    "Commit object for SHA {} (resolved to {}) is missing locally.",
                    repo.shortHash(sha),
                    objectId.name());
            return false;
        } catch (Exception e) {
            logger.debug("Cannot resolve or parse SHA {}: {}", repo.shortHash(sha), e.getMessage());
            return false;
        }
    }

    /** Helper method to ensure a SHA is available locally by fetching if needed. */
    private static boolean ensureShaIsLocal(GitRepo repo, String sha, String refSpec, String remoteName) {
        if (isCommitLocallyAvailable(repo, sha)) {
            return true;
        }

        // If not available or missing, try to fetch
        logger.debug(
                "SHA {} not fully available locally - fetching {} from {}", repo.shortHash(sha), refSpec, remoteName);
        try {
            var fetchCommand = repo.getGit().fetch().setRemote(remoteName).setRefSpecs(new RefSpec(refSpec));
            repo.applyGitHubAuthentication(fetchCommand, repo.remote().getUrl(remoteName));
            fetchCommand.call();
            // After fetch, verify again
            if (isCommitLocallyAvailable(repo, sha)) {
                logger.debug("Successfully fetched and verified SHA {}", repo.shortHash(sha));
                return true;
            } else {
                logger.warn(
                        "Failed to make SHA {} fully available locally even after fetching {} from {}",
                        repo.shortHash(sha),
                        refSpec,
                        remoteName);
                return false;
            }
        } catch (Exception e) {
            // Includes GitAPIException, IOException, etc.
            logger.warn(
                    "Error during fetch operation in ensureShaIsLocal for SHA {}: {}",
                    repo.shortHash(sha),
                    e.getMessage(),
                    e);
            return false;
        }
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
