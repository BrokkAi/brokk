package ai.brokk.gui.dependencies;

import ai.brokk.AbstractProject;
import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.Chrome;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Helper for updating imported dependencies and refreshing the analyzer.
 *
 * <p>This class currently provides support for GitHub-backed dependencies; future dependency
 * types (e.g., local paths) can be added alongside.
 */
public final class DependencyUpdateHelper {
    private static final Logger logger = LogManager.getLogger(DependencyUpdateHelper.class);

    private DependencyUpdateHelper() {
        // utility class
    }

    /**
     * Updates a single GitHub-backed dependency and refreshes the analyzer.
     *
     * <p>This method submits a background task via the project's ContextManager. It performs the
     * on-disk update by delegating to
     * {@link AbstractProject#updateGitDependencyOnDisk(ProjectFile, AbstractProject.DependencyMetadata)},
     * then calls {@code AnalyzerWrapper.updateFiles(...)} with the resulting changed files.
     *
     * <p>Progress and errors are surfaced via {@link Chrome#showNotification(IConsoleIO.NotificationRole, String)}
     * and {@link Chrome#toolError(String, String)}. Failures for this dependency do not throw on
     * the caller thread; instead they are reported through the returned {@link CompletableFuture}
     * and via UI notifications.
     *
     * @param chrome current Chrome instance
     * @param dependencyRoot top-level dependency directory as a {@link ProjectFile}
     * @param metadata parsed dependency metadata (must be of type GITHUB)
     * @return future completing with the set of changed/added/removed files, or an empty set on failure
     */
    public static CompletableFuture<Set<ProjectFile>> updateGitDependency(
            Chrome chrome, ProjectFile dependencyRoot, AbstractProject.DependencyMetadata metadata) {

        var project = chrome.getProject();
        if (!(project instanceof AbstractProject abstractProject)) {
            logger.warn(
                    "Project implementation {} does not extend AbstractProject; cannot update GitHub dependency {}",
                    project.getClass().getName(),
                    dependencyRoot);
            return CompletableFuture.completedFuture(Collections.emptySet());
        }

        String depName = dependencyRoot.getRelPath().getFileName().toString();
        var cm = chrome.getContextManager();

        chrome.showNotification(
                IConsoleIO.NotificationRole.INFO,
                "Updating GitHub dependency '" + depName + "' in the background...");

        var future = cm.submitBackgroundTask(
                "Update GitHub dependency " + depName,
                () -> {
                    var analyzer = cm.getAnalyzerWrapper();
                    analyzer.pause();
                    try {
                        Set<ProjectFile> changedFiles =
                                abstractProject.updateGitDependencyOnDisk(dependencyRoot, metadata);

                        if (!changedFiles.isEmpty()) {
                            try {
                                analyzer.updateFiles(new HashSet<>(changedFiles)).get();
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(
                                        "Interrupted while updating analyzer for dependency " + depName, ie);
                            } catch (ExecutionException ee) {
                                throw new RuntimeException(
                                        "Analyzer update failed for dependency " + depName, ee.getCause());
                            }
                        }

                        return changedFiles;
                    } finally {
                        analyzer.resume();
                    }
                });

        future.whenComplete((changedFiles, ex) -> SwingUtilities.invokeLater(() -> {
            if (ex != null) {
                logger.error("Error updating GitHub dependency {}: {}", depName, ex.getMessage(), ex);
                chrome.toolError(
                        "Failed to update GitHub dependency '" + depName + "': " + ex.getMessage(),
                        "Dependency Update Error");
            } else {
                Set<ProjectFile> nonNullChanged = changedFiles != null ? changedFiles : Collections.emptySet();
                if (nonNullChanged.isEmpty()) {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "GitHub dependency '" + depName + "' is already up to date.");
                } else {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "Updated GitHub dependency '"
                                    + depName
                                    + "' ("
                                    + nonNullChanged.size()
                                    + " files changed).");
                }
            }
        }));

        return future;
    }
}
