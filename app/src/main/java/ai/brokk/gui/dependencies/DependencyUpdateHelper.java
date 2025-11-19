package ai.brokk.gui.dependencies;

import ai.brokk.AbstractProject;
import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.Chrome;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Helper for updating imported dependencies and refreshing the analyzer.
 *
 * <p>This class currently provides support for GitHub-backed and local path dependencies.
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
        return runUpdate(
                chrome,
                dependencyRoot,
                metadata,
                "GitHub",
                abstractProject -> {
                    try {
                        return abstractProject.updateGitDependencyOnDisk(dependencyRoot, metadata);
                    } catch (IOException e) {
                        throw new RuntimeException(
                                "I/O error while updating GitHub dependency on disk: " + dependencyRoot, e);
                    }
                });
    }

    /**
     * Updates a single local-path-backed dependency and refreshes the analyzer.
     *
     * <p>This method mirrors {@link #updateGitDependency(Chrome, ProjectFile, AbstractProject.DependencyMetadata)}
     * but delegates to
     * {@link AbstractProject#updateLocalPathDependencyOnDisk(ProjectFile, AbstractProject.DependencyMetadata)}
     * for the on-disk update.
     *
     * @param chrome current Chrome instance
     * @param dependencyRoot top-level dependency directory as a {@link ProjectFile}
     * @param metadata parsed dependency metadata (must be of type LOCAL_PATH)
     * @return future completing with the set of changed/added/removed files, or an empty set on failure
     */
    public static CompletableFuture<Set<ProjectFile>> updateLocalPathDependency(
            Chrome chrome, ProjectFile dependencyRoot, AbstractProject.DependencyMetadata metadata) {
        return runUpdate(
                chrome,
                dependencyRoot,
                metadata,
                "local path",
                abstractProject -> {
                    try {
                        return abstractProject.updateLocalPathDependencyOnDisk(dependencyRoot, metadata);
                    } catch (IOException e) {
                        throw new RuntimeException(
                                "I/O error while updating local path dependency on disk: " + dependencyRoot, e);
                    }
                });
    }

    private static CompletableFuture<Set<ProjectFile>> runUpdate(
            Chrome chrome,
            ProjectFile dependencyRoot,
            AbstractProject.DependencyMetadata metadata,
            String dependencyKindLabel,
            Function<AbstractProject, Set<ProjectFile>> updateOperation) {

        var project = chrome.getProject();
        if (!(project instanceof AbstractProject abstractProject)) {
            logger.warn(
                    "Project implementation {} does not extend AbstractProject; cannot update {} dependency {}",
                    project.getClass().getName(),
                    dependencyKindLabel,
                    dependencyRoot);
            return CompletableFuture.completedFuture(Collections.emptySet());
        }

        String depName = dependencyRoot.getRelPath().getFileName().toString();
        var cm = chrome.getContextManager();

        chrome.showNotification(
                IConsoleIO.NotificationRole.INFO,
                "Updating " + dependencyKindLabel + " dependency '" + depName + "' in the background...");

        var future = cm.submitBackgroundTask(
                "Update " + dependencyKindLabel + " dependency " + depName,
                () -> {
                    var analyzer = cm.getAnalyzerWrapper();
                    analyzer.pause();
                    try {
                        Set<ProjectFile> changedFiles = updateOperation.apply(abstractProject);

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
                logger.error(
                        "Error updating {} dependency {}: {}",
                        dependencyKindLabel,
                        depName,
                        ex.getMessage(),
                        ex);
                chrome.toolError(
                        "Failed to update " + dependencyKindLabel + " dependency '" + depName + "': " + ex.getMessage(),
                        "Dependency Update Error");
            } else {
                Set<ProjectFile> nonNullChanged = changedFiles != null ? changedFiles : Collections.emptySet();
                if (nonNullChanged.isEmpty()) {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            capitalize(dependencyKindLabel)
                                    + " dependency '"
                                    + depName
                                    + "' is already up to date.");
                } else {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "Updated "
                                    + dependencyKindLabel
                                    + " dependency '"
                                    + depName
                                    + "' ("
                                    + nonNullChanged.size()
                                    + " files changed).");
                }
            }
        }));

        return future;
    }

    private static String capitalize(String label) {
        if (label.isEmpty()) {
            return label;
        }
        if (label.length() == 1) {
            return label.toUpperCase();
        }
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }
}
