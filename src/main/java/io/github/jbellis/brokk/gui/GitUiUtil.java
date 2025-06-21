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
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.google.common.base.Splitter;
import org.jetbrains.annotations.Nullable;

/**
 * Static utilities for showing diffs, capturing diffs, or editing files
 * in the Git UI, removing duplicated code across multiple panels.
 */
public final class GitUiUtil
{
    private static final Logger logger = LogManager.getLogger(GitUiUtil.class);

    private GitUiUtil() {}

    /**
     * Shortens a commit ID to 7 characters for display purposes.
     * @param commitId The full commit ID, may be null
     * @return The shortened commit ID, or the original if null or shorter than 7 characters
     */
    public static String shortenCommitId(String commitId) {
        return commitId != null && commitId.length() >= 7 ? commitId.substring(0, 7) : commitId;
    }

    /**
     * Capture uncommitted diffs for the specified files, adding the result to the context.
     */
    public static void captureUncommittedDiff
    (
            ContextManager contextManager,
            Chrome chrome,
            List<ProjectFile> selectedFiles
    ) {
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
                var syntaxStyle = selectedFiles.isEmpty() ? SyntaxConstants.SYNTAX_STYLE_NONE :
                                 SyntaxDetector.fromExtension(selectedFiles.getFirst().extension());
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
    public static void editFile
    (
            ContextManager contextManager,
            String filePath
    ) {
        var file = contextManager.toFile(filePath);
        editFiles(contextManager, List.of(file)); // Call the new list-based method
    }

    /**
     * Open multiple files in the project's editor.
     */
    public static void editFiles
    (
            ContextManager contextManager,
            List<ProjectFile> files
    ) {
        if (!files.isEmpty()) {
            contextManager.editFiles(files);
        }
    }

    /**
     * Capture a single file’s historical changes into the context (HEAD vs commitId).
     */
    public static void addFileChangeToContext
    (
            ContextManager contextManager,
            Chrome chrome,
            String commitId,
            ProjectFile file
    ) {
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
    public static void showFileHistoryDiff(ContextManager cm,
                                           Chrome chrome, // Pass Chrome for theme access
                                           String commitId,
                                           ProjectFile file)
    {
        var repo = cm.getProject().getRepo();

        var shortCommitId = shortenCommitId(commitId);
        var dialogTitle = "Diff: " + file.getFileName() + " (" + shortCommitId + ")";
        var parentCommitId = commitId + "^";

        cm.submitBackgroundTask("Loading history diff for " + file.getFileName(), () -> {
            try {
                var parentObjectId = repo.resolve(parentCommitId);
                var parentContent = parentObjectId == null ? "" : repo.getFileContent(parentCommitId, file);
                var commitContent = repo.getFileContent(commitId, file);

                SwingUtilities.invokeLater(() -> {
                    var brokkDiffPanel = new BrokkDiffPanel.Builder(chrome.themeManager, cm)
                            .leftSource(new BufferSource.StringSource(parentContent, parentCommitId, file.toString()))
                            .rightSource(new BufferSource.StringSource(commitContent, commitId, file.toString()))
                            .build();
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
    public static void viewFileAtRevision(ContextManager cm,
                                          Chrome chrome,
                                          String commitId,
                                          String filePath)
    {
        var repo = cm.getProject().getRepo();

        cm.submitUserTask("Viewing file at revision", () -> {
            var file = new ProjectFile(cm.getRoot(), filePath);
            try {
                final String content = repo.getFileContent(commitId, file);
                if (content == null) {
                    chrome.systemOutput("File not found in this revision or is empty.");
                    return;
                }
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
     * Add the combined diff of multiple commits to context (from first selected to last).
     */
    public static void addCommitRangeToContext
    (
            ContextManager contextManager,
            Chrome chrome,
            int[] selectedRows,
            javax.swing.table.TableModel tableModel,
            int commitInfoColumnIndex // Add index for ICommitInfo
    ) {
        contextManager.submitContextTask("Adding commit range to context", () -> {
            try {
                if (selectedRows.length == 0 || tableModel.getRowCount() == 0) {
                    chrome.systemOutput("No commits selected or commits table is empty");
                    return;
                }
                var sorted = selectedRows.clone();
                java.util.Arrays.sort(sorted);
                if (sorted[0] < 0 || sorted[sorted.length - 1] >= tableModel.getRowCount()) {
                    chrome.systemOutput("Invalid commit selection");
                    return;
                }
                // Retrieve ICommitInfo objects using the provided index
                var firstCommitInfo = (io.github.jbellis.brokk.git.ICommitInfo) tableModel.getValueAt(sorted[0], commitInfoColumnIndex);
                var lastCommitInfo  = (io.github.jbellis.brokk.git.ICommitInfo) tableModel.getValueAt(sorted[sorted.length - 1], commitInfoColumnIndex);
                var firstCommitId = firstCommitInfo.id();
                var lastCommitId = lastCommitInfo.id();

                var repo = contextManager.getProject().getRepo();

                // Fetch diff using the correct parent syntax for range
                var diff = repo.showDiff(firstCommitId, lastCommitId + "^");
                if (diff.isEmpty()) {
                    chrome.systemOutput("No changes found in the selected commit range");
                    return;
                }

                // Use the correct method to list files between the two commits
                var changedFiles = repo.listFilesChangedBetweenCommits(firstCommitId, lastCommitId);
                var fileNames = changedFiles.stream()
                        .map(ProjectFile::getFileName)
                        .collect(Collectors.toList());
                var filesTxt  = String.join(", ", fileNames);

                var firstShort = shortenCommitId(firstCommitId);
                var lastShort  = shortenCommitId(lastCommitId);
                var hashTxt    = firstCommitId.equals(lastCommitId)
                        ? firstShort
                        : firstShort + ".." + lastShort;
                var description = "Diff of %s [%s]".formatted(filesTxt, hashTxt);

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
     * Add file changes (a subset of the commits range) to the context.
     */
    public static void addFilesChangeToContext
    (
            ContextManager contextManager,
            Chrome chrome,
            String firstCommitId,
            String lastCommitId,
            List<ProjectFile> files
    ) {
        contextManager.submitContextTask("Adding file changes from range to context", () -> {
            try {
                if (files.isEmpty()) {
                    chrome.systemOutput("No files provided to capture diff");
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
                    chrome.systemOutput("No changes found for the selected files in the commit range");
                    return;
                }
                var firstShort = shortenCommitId(firstCommitId);
                var lastShort = shortenCommitId(lastCommitId);
                var shortHash = firstCommitId.equals(lastCommitId)
                                ? firstShort
                                : "%s..%s".formatted(firstShort, lastShort);

                var filesTxt = files.stream()
                        .map(ProjectFile::getFileName)
                        .collect(Collectors.joining(", "));
                var description = "Diff of %s [%s]".formatted(filesTxt, shortHash);

                var syntaxStyle = files.isEmpty() ? SyntaxConstants.SYNTAX_STYLE_NONE :
                                  SyntaxDetector.fromExtension(files.getFirst().extension());
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
    public static void showDiffVsLocal(ContextManager cm,
                                       Chrome chrome, // Pass Chrome for theme access
                                       String commitId,
                                       String filePath,
                                       boolean useParent)
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
                    var parentObjectId = repo.resolve(commitId + "^");
                    if (parentObjectId != null) {
                        baseCommitId = commitId + "^";
                        baseCommitTitle = commitId + "^";
                        baseCommitShort = shortenCommitId(commitId) + "^";
                    } else {
                        baseCommitId = null; // Indicates no parent, so old content will be empty
                        baseCommitTitle = "[No Parent]";
                        baseCommitShort = "[No Parent]";
                    }
                }

                // 3) Read old content from the base commit (if it exists)
                var oldContent = "";
                if (baseCommitId != null) {
                    oldContent = repo.getFileContent(baseCommitId, file);
                }

                // 4) Create panel on Swing thread
                String finalOldContent = oldContent; // effectively final for lambda
                String finalBaseCommitTitle = baseCommitTitle;
                String finalDialogTitle = "Diff: %s [Local vs %s]".formatted(file.getFileName(), baseCommitShort);

                SwingUtilities.invokeLater(() -> {
                    var brokkDiffPanel = new BrokkDiffPanel.Builder(chrome.themeManager, cm)
                            .leftSource(new BufferSource.StringSource(finalOldContent, finalBaseCommitTitle, file.toString()))
                            .rightSource(new BufferSource.FileSource(file.absPath().toFile(), file.toString()))
                            .build();
                    brokkDiffPanel.showInFrame(finalDialogTitle);
                });
            } catch (Exception ex) {
                cm.getIo().toolError("Error loading compare-with-local diff: " + ex.getMessage());
            }
            return null;
        });
    }

    /**
     * Holds a parsed "owner" and "repo" from a Git remote URL.
     */
    public record OwnerRepo(String owner, String repo) { }

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
        String cleaned = remoteUrl.endsWith(".git")
                         ? remoteUrl.substring(0, remoteUrl.length() - 4)
                         : remoteUrl;

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
    public static void openCommitDiffPanel(
            ContextManager cm,
            Chrome chrome,
            io.github.jbellis.brokk.git.ICommitInfo commitInfo
    ) {
        var repo = cm.getProject().getRepo();

        cm.submitUserTask("Opening diff for commit " + shortenCommitId(commitInfo.id()), () -> {
            try {
                var files = commitInfo.changedFiles();
                if (files == null || files.isEmpty()) {
                    chrome.systemOutput("No files changed in this commit.");
                    return;
                }

                var builder = new BrokkDiffPanel.Builder(chrome.themeManager, cm);
                var parentId = commitInfo.id() + "^";

                for (var file : files) {
                    var oldContent = getFileContentOrEmpty(repo, parentId, file);
                    var newContent = getFileContentOrEmpty(repo, commitInfo.id(), file);

                    builder.addComparison(
                        new BufferSource.StringSource(oldContent, parentId, file.getFileName()),
                        new BufferSource.StringSource(newContent, commitInfo.id(), file.getFileName())
                    );
                }

                var title = "Commit Diff: %s (%s)".formatted(
                        commitInfo.message().lines().findFirst().orElse(""),
                        shortenCommitId(commitInfo.id())
                );
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

    public static void captureDiffBetweenBranches
    (
            ContextManager cm,
            Chrome chrome,
            String baseBranchName,
            String compareBranchName
    ) {
        var repo = cm.getProject().getRepo();

        cm.submitContextTask("Capturing diff between " + compareBranchName + " and " + baseBranchName, () -> {
            try {
                var diff = repo.showDiff(compareBranchName, baseBranchName);
                if (diff.isEmpty()) {
                    chrome.systemOutput(String.format("No differences found between %s and %s",
                                                      compareBranchName, baseBranchName));
                    return;
                }
                var description = "Diff of %s vs %s".formatted(compareBranchName, baseBranchName);
                var fragment = new ContextFragment.StringFragment(cm, diff, description, SyntaxConstants.SYNTAX_STYLE_NONE);
                cm.addVirtualFragment(fragment);
                chrome.systemOutput(String.format("Added diff of %s vs %s to context",
                                                  compareBranchName, baseBranchName));
            } catch (Exception ex) {
                logger.warn("Error capturing diff between branches {} and {}: {}",
                            compareBranchName, baseBranchName, ex.getMessage(), ex);
                chrome.toolError(String.format("Error capturing diff between %s and %s: %s",
                                               compareBranchName, baseBranchName, ex.getMessage()));
            }
        });
    }

    /**
     * Rollback selected files to their state at a specific commit.
     * This will overwrite the current working directory versions of these files.
     */
    public static void rollbackFilesToCommit
    (
            ContextManager contextManager,
            Chrome chrome,
            String commitId,
            List<ProjectFile> files
    ) {
        if (files == null || files.isEmpty()) {
            chrome.systemOutput("No files selected for rollback");
            return;
        }

        var shortCommitId = shortenCommitId(commitId);

        var repo = (io.github.jbellis.brokk.git.GitRepo) contextManager.getProject().getRepo();

        contextManager.submitUserTask("Rolling back files to commit " + shortCommitId, () -> {
            try {
                repo.checkoutFilesFromCommit(commitId, files);
                SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput(String.format(
                            "Successfully rolled back %d file(s) to commit %s",
                            files.size(), shortCommitId
                    ));
                    // Refresh Git panels to show the changed files
                    var gitPanel = chrome.getGitPanel();
                    if (gitPanel != null) {
                        gitPanel.updateCommitPanel();
                    }
                });
            } catch (Exception e) {
                logger.error("Error rolling back files", e);
                SwingUtilities.invokeLater(() ->
                    chrome.toolError("Error rolling back files: " + e.getMessage())
                );
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
        if (files == null || files.isEmpty()) {
            return "no files";
        }

        return files.size() <= 3
               ? files.stream().map(ProjectFile::getFileName).collect(Collectors.joining(", "))
               : files.size() + " files";
    }
}

