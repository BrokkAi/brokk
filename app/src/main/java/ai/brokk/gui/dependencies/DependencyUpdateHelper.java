package ai.brokk.gui.dependencies;

import ai.brokk.IConsoleIO;
import ai.brokk.IProject;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.gui.Chrome;
import ai.brokk.util.DependencyUpdater;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
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
            Chrome chrome, ProjectFile dependencyRoot, DependencyUpdater.DependencyMetadata metadata) {
        // Check if update is needed by comparing commit hashes
        var storedHash = metadata.commitHash();
        var repoUrl = metadata.repoUrl();
        var ref = metadata.ref();

        if (storedHash != null && repoUrl != null && ref != null) {
            var remoteHash = GitRepoFactory.getRemoteRefCommit(repoUrl, ref);
            if (remoteHash != null && storedHash.equals(remoteHash)) {
                logger.debug("Git dependency {} is already up to date (commit {})",
                             dependencyRoot.getRelPath().getFileName(),
                             storedHash.substring(0, Math.min(8, storedHash.length())));
                return CompletableFuture.completedFuture(Collections.emptySet());
            }
        }

        return runUpdate(chrome, dependencyRoot, "GitHub", project -> {
            try {
                return DependencyUpdater.updateGitDependencyOnDisk(project, dependencyRoot, metadata);
            } catch (IOException e) {
                throw new RuntimeException("I/O error while updating GitHub dependency on disk: " + dependencyRoot, e);
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
            Chrome chrome, ProjectFile dependencyRoot, DependencyUpdater.DependencyMetadata metadata) {
        // Check if update is needed by comparing timestamps
        var sourcePath = metadata.sourcePath();
        if (sourcePath != null) {
            var source = Path.of(sourcePath);
            if (Files.exists(source) && Files.isDirectory(source)) {
                long newestTimestamp = getNewestFileTimestamp(source);
                if (newestTimestamp <= metadata.lastUpdatedMillis()) {
                    logger.debug("Local dependency {} is already up to date (no newer files)",
                                 dependencyRoot.getRelPath().getFileName());
                    return CompletableFuture.completedFuture(Collections.emptySet());
                }
            }
        }

        return runUpdate(chrome, dependencyRoot, "local path", project -> {
            try {
                return DependencyUpdater.updateLocalPathDependencyOnDisk(project, dependencyRoot, metadata);
            } catch (IOException e) {
                throw new RuntimeException(
                        "I/O error while updating local path dependency on disk: " + dependencyRoot, e);
            }
        });
    }

    /**
     * Returns the newest file modification timestamp in a directory (recursive).
     * Returns 0 if directory is empty or cannot be read.
     */
    private static long getNewestFileTimestamp(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .max()
                    .orElse(0L);
        } catch (IOException e) {
            logger.debug("Failed to scan directory for timestamps: {}", dir, e);
            return 0L;
        }
    }

    private static CompletableFuture<Set<ProjectFile>> runUpdate(
            Chrome chrome,
            ProjectFile dependencyRoot,
            String dependencyKindLabel,
            Function<IProject, Set<ProjectFile>> updateOperation) {

        var project = chrome.getProject();

        String depName = dependencyRoot.getRelPath().getFileName().toString();
        var cm = chrome.getContextManager();

        chrome.showNotification(
                IConsoleIO.NotificationRole.INFO,
                "Updating " + dependencyKindLabel + " dependency '" + depName + "' in the background...");

        var future = cm.submitBackgroundTask("Update " + dependencyKindLabel + " dependency " + depName, () -> {
            var analyzer = cm.getAnalyzerWrapper();
            analyzer.pause();
            try {
                Set<ProjectFile> changedFiles = updateOperation.apply(project);

                if (!changedFiles.isEmpty()) {
                    try {
                        analyzer.updateFiles(new HashSet<>(changedFiles)).get();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while updating analyzer for dependency " + depName, ie);
                    } catch (ExecutionException ee) {
                        throw new RuntimeException("Analyzer update failed for dependency " + depName, ee.getCause());
                    }
                }

                return changedFiles;
            } finally {
                analyzer.resume();
            }
        });

        future.whenComplete((changedFiles, ex) -> SwingUtilities.invokeLater(() -> {
            if (ex != null) {
                logger.error("Error updating {} dependency {}: {}", dependencyKindLabel, depName, ex.getMessage(), ex);
                chrome.toolError(
                        "Failed to update " + dependencyKindLabel + " dependency '" + depName + "': " + ex.getMessage(),
                        "Dependency Update Error");
            } else {
                assert changedFiles != null;
                if (changedFiles.isEmpty()) {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            capitalize(dependencyKindLabel) + " dependency '" + depName + "' is already up to date.");
                } else {
                    logger.info("Updated dependency {}: {} files changed", depName, changedFiles.size());
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "Updated "
                                    + dependencyKindLabel
                                    + " dependency '"
                                    + depName
                                    + "' ("
                                    + changedFiles.size()
                                    + " files changed).");
                }
            }
        }));

        return future;
    }

    /**
     * Performs a single automatic update pass across all imported dependencies for the current
     * project, honoring the project's auto-update flags.
     *
     * <p>This helper is intended for "run once on startup" style behavior. It delegates the
     * on-disk work to {@link AbstractProject#autoUpdateDependenciesOnce(boolean, boolean)} and
     * applies the resulting file changes to the analyzer in a single
     * {@code AnalyzerWrapper.updateFiles(...)} call.
     *
     * @param chrome current Chrome instance
     * @return future completing with the aggregate result of the auto-update pass
     */
    public static CompletableFuture<DependencyUpdater.DependencyAutoUpdateResult> autoUpdateEligibleDependencies(
            Chrome chrome) {
        var project = chrome.getProject();

        boolean includeLocal = project.getAutoUpdateLocalDependencies();
        boolean includeGit = project.getAutoUpdateGitDependencies();

        if (!includeLocal && !includeGit) {
            logger.debug("Automatic dependency update skipped: both auto-update flags are disabled.");
            return CompletableFuture.completedFuture(
                    new DependencyUpdater.DependencyAutoUpdateResult(Collections.emptySet(), 0));
        }

        var cm = chrome.getContextManager();

        chrome.showNotification(
                IConsoleIO.NotificationRole.INFO, "Checking imported dependencies for updates in the background...");

        var future = cm.submitBackgroundTask("Auto-update imported dependencies", () -> {
            var analyzer = cm.getAnalyzerWrapper();
            analyzer.pause();
            try {
                DependencyUpdater.DependencyAutoUpdateResult result =
                        DependencyUpdater.autoUpdateDependenciesOnce(project, includeLocal, includeGit);

                if (!result.changedFiles().isEmpty()) {
                    try {
                        analyzer.updateFiles(new HashSet<>(result.changedFiles()))
                                .get();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while applying dependency auto-updates", ie);
                    } catch (ExecutionException ee) {
                        throw new RuntimeException(
                                "Analyzer update failed after dependency auto-update", ee.getCause());
                    }
                }

                return result;
            } finally {
                analyzer.resume();
            }
        });

        future.whenComplete((result, ex) -> SwingUtilities.invokeLater(() -> {
            if (ex != null) {
                logger.error("Error during automatic dependency update: {}", ex.getMessage(), ex);
                chrome.toolError(
                        "Automatic dependency update failed: " + ex.getMessage(), "Dependency Auto-Update Error");
                return;
            }

            assert result != null;
            int depsUpdated = result.updatedDependencies();
            int filesChanged = result.changedFiles().size();

            if (depsUpdated == 0) {
                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Checked imported dependencies; everything is already up to date.");
            } else {
                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Updated "
                                + depsUpdated
                                + " imported dependenc"
                                + (depsUpdated == 1 ? "y" : "ies")
                                + " ("
                                + filesChanged
                                + " files changed).");
            }
        }));

        return future;
    }

    private static String capitalize(String label) {
        if (label.isEmpty()) {
            return label;
        }
        if (label.length() == 1) {
            return label.toUpperCase(Locale.ROOT);
        }
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }
}
