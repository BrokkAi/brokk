package ai.brokk.util;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.MainProject;
import ai.brokk.SettingsChangeListener;
import ai.brokk.gui.Chrome;
import java.util.HashSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages periodic background checking and updating of dependencies.
 * Local dependencies are checked every 5 minutes (sequentially).
 * Git dependencies are checked every 30 minutes (in parallel).
 */
public class DependencyUpdateScheduler implements SettingsChangeListener, IContextManager.AnalyzerCallback {
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
        chrome.getContextManager().addAnalyzerCallback(this);

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
    public void onAnalyzerReady() {
        // Run initial check when analyzer is ready
        logger.debug("Analyzer ready, running initial dependency checks");
        synchronized (schedulerLock) {
            if (localScheduler != null && !localScheduler.isShutdown()) {
                localScheduler.execute(this::checkLocalDependencies);
            }
            if (gitScheduler != null && !gitScheduler.isShutdown()) {
                gitScheduler.execute(this::checkGitDependencies);
            }
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

            // First run triggered by onAnalyzerReady, then every 5 minutes
            localScheduler.scheduleAtFixedRate(
                    this::checkLocalDependencies,
                    LOCAL_CHECK_INTERVAL_MINUTES,
                    LOCAL_CHECK_INTERVAL_MINUTES,
                    TimeUnit.MINUTES);

            logger.info(
                    "Started local dependency update scheduler (interval: {} minutes)", LOCAL_CHECK_INTERVAL_MINUTES);
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

            // First run triggered by onAnalyzerReady, then every 30 minutes
            gitScheduler.scheduleAtFixedRate(
                    this::checkGitDependencies,
                    GIT_CHECK_INTERVAL_MINUTES,
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

            if (analyzer == null) {
                logger.debug("Analyzer not initialized yet, skipping local dependency check");
                return;
            }

            analyzer.pause();
            try {
                var result = DependencyUpdater.autoUpdateDependenciesOnce(project, true, false);

                if (result.updatedDependencies() > 0) {
                    // Update analyzer index for changed files
                    if (!result.changedFiles().isEmpty()) {
                        try {
                            analyzer.updateFiles(new HashSet<>(result.changedFiles()))
                                    .get();
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
                                String.format(
                                        "Updated %d local %s (%d files changed)",
                                        depsUpdated, depsUpdated == 1 ? "dependency" : "dependencies", filesChanged));
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

            if (analyzer == null) {
                logger.debug("Analyzer not initialized yet, skipping Git dependency check");
                return;
            }

            analyzer.pause();
            try {
                var result = DependencyUpdater.autoUpdateDependenciesOnce(project, false, true);

                if (result.updatedDependencies() > 0) {
                    // Update analyzer index for changed files
                    if (!result.changedFiles().isEmpty()) {
                        try {
                            analyzer.updateFiles(new HashSet<>(result.changedFiles()))
                                    .get();
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
                                String.format(
                                        "Updated %d Git %s (%d files changed)",
                                        depsUpdated, depsUpdated == 1 ? "dependency" : "dependencies", filesChanged));
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
        chrome.getContextManager().removeAnalyzerCallback(this);
        synchronized (schedulerLock) {
            stopLocalScheduler();
            stopGitScheduler();
        }
        logger.info("Closed dependency update scheduler");
    }
}
