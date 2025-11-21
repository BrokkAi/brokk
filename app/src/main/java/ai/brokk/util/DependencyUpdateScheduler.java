package ai.brokk.util;

import ai.brokk.gui.Chrome;
import ai.brokk.MainProject;
import ai.brokk.SettingsChangeListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import ai.brokk.IConsoleIO;

import javax.swing.SwingUtilities;
import java.util.HashSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages periodic background checking and updating of dependencies.
 * Local dependencies are checked every 5 minutes (sequentially).
 * Git dependencies are checked every 30 minutes (in parallel).
 */
public class DependencyUpdateScheduler implements SettingsChangeListener {
    private static final Logger logger = LogManager.getLogger(DependencyUpdateScheduler.class);

    private static final long LOCAL_CHECK_INTERVAL_MINUTES = 5;
    private static final long GIT_CHECK_INTERVAL_MINUTES = 30;

    private final Chrome chrome;
    private final Object schedulerLock = new Object();

    private ScheduledExecutorService localScheduler;
    private ScheduledExecutorService gitScheduler;

    public DependencyUpdateScheduler(Chrome chrome) {
        this.chrome = chrome;
        MainProject.addSettingsChangeListener(this);

        // Check initial state and start schedulers if enabled
        var project = chrome.getProject();
        if (project.getAutoUpdateLocalDependencies()) {
            startLocalScheduler();
        }
        if (project.getAutoUpdateGitDependencies()) {
            startGitScheduler();
        }
    }

    @Override
    public void autoUpdateLocalDependenciesChanged() {
        synchronized (schedulerLock) {
            var project = chrome.getProject();
            if (project.getAutoUpdateLocalDependencies()) {
                startLocalScheduler();
            } else {
                stopLocalScheduler();
            }
        }
    }

    @Override
    public void autoUpdateGitDependenciesChanged() {
        synchronized (schedulerLock) {
            var project = chrome.getProject();
            if (project.getAutoUpdateGitDependencies()) {
                startGitScheduler();
            } else {
                stopGitScheduler();
            }
        }
    }

    private void startLocalScheduler() {
        synchronized (schedulerLock) {
            if (localScheduler != null && !localScheduler.isShutdown()) {
                logger.debug("Local dependency scheduler already running");
                return;
            }

            localScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "DependencyUpdate-Local");
                t.setDaemon(true);
                return t;
            });

            // Run immediately, then every 5 minutes
            localScheduler.scheduleAtFixedRate(
                    this::checkLocalDependencies,
                    0,
                    LOCAL_CHECK_INTERVAL_MINUTES,
                    TimeUnit.MINUTES);

            logger.info("Started local dependency update scheduler (interval: {} minutes)", LOCAL_CHECK_INTERVAL_MINUTES);
        }
    }

    private void stopLocalScheduler() {
        synchronized (schedulerLock) {
            if (localScheduler != null) {
                localScheduler.shutdown();
                localScheduler = null;
                logger.info("Stopped local dependency update scheduler");
            }
        }
    }

    private void startGitScheduler() {
        synchronized (schedulerLock) {
            if (gitScheduler != null && !gitScheduler.isShutdown()) {
                logger.debug("Git dependency scheduler already running");
                return;
            }

            gitScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "DependencyUpdate-Git");
                t.setDaemon(true);
                return t;
            });

            // Run immediately, then every 30 minutes
            gitScheduler.scheduleAtFixedRate(
                    this::checkGitDependencies,
                    0,
                    GIT_CHECK_INTERVAL_MINUTES,
                    TimeUnit.MINUTES);

            logger.info("Started Git dependency update scheduler (interval: {} minutes)", GIT_CHECK_INTERVAL_MINUTES);
        }
    }

    private void stopGitScheduler() {
        synchronized (schedulerLock) {
            if (gitScheduler != null) {
                gitScheduler.shutdown();
                gitScheduler = null;
                logger.info("Stopped Git dependency update scheduler");
            }
        }
    }

    private void checkLocalDependencies() {
        try {
            logger.debug("Checking local dependencies for updates...");
            var project = chrome.getProject();
            var cm = chrome.getContextManager();
            var analyzer = cm.getAnalyzerWrapper();

            analyzer.pause();
            try {
                var result = DependencyUpdater.autoUpdateDependenciesOnce(project, true, false);

                if (result.updatedDependencies() > 0) {
                    // Update analyzer index for changed files
                    if (!result.changedFiles().isEmpty()) {
                        try {
                            analyzer.updateFiles(new HashSet<>(result.changedFiles())).get();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            logger.error("Interrupted while updating analyzer after local dependency update", ie);
                            return;
                        } catch (ExecutionException ee) {
                            logger.error("Analyzer update failed after local dependency update", ee.getCause());
                        }
                    }

                    // Notify user on EDT
                    int depsUpdated = result.updatedDependencies();
                    int filesChanged = result.changedFiles().size();
                    SwingUtilities.invokeLater(() -> {
                        chrome.showNotification(
                                IConsoleIO.NotificationRole.INFO,
                                String.format("Updated %d local %s (%d files changed)",
                                        depsUpdated,
                                        depsUpdated == 1 ? "dependency" : "dependencies",
                                        filesChanged));
                    });
                } else {
                    logger.debug("No local dependency updates found");
                }
            } finally {
                analyzer.resume();
            }
        } catch (Exception e) {
            logger.error("Error checking local dependencies for updates", e);
        }
    }

    private void checkGitDependencies() {
        try {
            logger.debug("Checking Git dependencies for updates...");
            var project = chrome.getProject();
            var cm = chrome.getContextManager();
            var analyzer = cm.getAnalyzerWrapper();

            analyzer.pause();
            try {
                var result = DependencyUpdater.autoUpdateDependenciesOnce(project, false, true);

                if (result.updatedDependencies() > 0) {
                    // Update analyzer index for changed files
                    if (!result.changedFiles().isEmpty()) {
                        try {
                            analyzer.updateFiles(new HashSet<>(result.changedFiles())).get();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            logger.error("Interrupted while updating analyzer after Git dependency update", ie);
                            return;
                        } catch (ExecutionException ee) {
                            logger.error("Analyzer update failed after Git dependency update", ee.getCause());
                        }
                    }

                    // Notify user on EDT
                    int depsUpdated = result.updatedDependencies();
                    int filesChanged = result.changedFiles().size();
                    SwingUtilities.invokeLater(() -> {
                        chrome.showNotification(
                                IConsoleIO.NotificationRole.INFO,
                                String.format("Updated %d Git %s (%d files changed)",
                                        depsUpdated,
                                        depsUpdated == 1 ? "dependency" : "dependencies",
                                        filesChanged));
                    });
                } else {
                    logger.debug("No Git dependency updates found");
                }
            } finally {
                analyzer.resume();
            }
        } catch (Exception e) {
            logger.error("Error checking Git dependencies for updates", e);
        }
    }

    /**
     * Shuts down all schedulers and unregisters from settings changes.
     * Call this when the project is closing.
     */
    public void close() {
        MainProject.removeSettingsChangeListener(this);
        synchronized (schedulerLock) {
            stopLocalScheduler();
            stopGitScheduler();
        }
        logger.info("Closed dependency update scheduler");
    }
}
