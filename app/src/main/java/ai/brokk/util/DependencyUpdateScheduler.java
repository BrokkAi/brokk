package ai.brokk.util;

import ai.brokk.IContextManager;
import ai.brokk.MainProject;
import ai.brokk.SettingsChangeListener;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.dependencies.DependencyUpdateHelper;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Manages periodic background checking and updating of dependencies.
 * Local dependencies are checked every 5 minutes.
 * Git dependencies are checked every 30 minutes.
 * Uses a single scheduler with separate tasks for each type.
 */
public class DependencyUpdateScheduler implements SettingsChangeListener, IContextManager.AnalyzerCallback {
    private static final Logger logger = LogManager.getLogger(DependencyUpdateScheduler.class);

    private static final long LOCAL_CHECK_INTERVAL_MINUTES = 5;
    private static final long GIT_CHECK_INTERVAL_MINUTES = 30;

    private final Chrome chrome;
    private final Object schedulerLock = new Object();

    private @Nullable ScheduledExecutorService scheduler;
    private @Nullable ScheduledFuture<?> localTask;
    private @Nullable ScheduledFuture<?> gitTask;

    public DependencyUpdateScheduler(Chrome chrome) {
        this.chrome = chrome;
        MainProject.addSettingsChangeListener(this);
        chrome.getContextManager().addAnalyzerCallback(this);

        // Check initial state and start tasks if enabled
        var project = chrome.getProject();
        if (project.getAutoUpdateLocalDependencies() || project.getAutoUpdateGitDependencies()) {
            ensureSchedulerRunning();
            if (project.getAutoUpdateLocalDependencies()) {
                startLocalTask();
            }
            if (project.getAutoUpdateGitDependencies()) {
                startGitTask();
            }
        }
    }

    @Override
    public void onAnalyzerReady() {
        // Run initial check when analyzer is ready
        logger.debug("Analyzer ready, running initial dependency checks");
        synchronized (schedulerLock) {
            if (scheduler != null && !scheduler.isShutdown()) {
                if (localTask != null) {
                    scheduler.execute(() -> DependencyUpdateHelper.autoUpdateLocalDependencies(chrome));
                }
                if (gitTask != null) {
                    scheduler.execute(() -> DependencyUpdateHelper.autoUpdateGitDependencies(chrome));
                }
            }
        }
    }

    @Override
    public void autoUpdateLocalDependenciesChanged() {
        synchronized (schedulerLock) {
            var project = chrome.getProject();
            if (project.getAutoUpdateLocalDependencies()) {
                ensureSchedulerRunning();
                startLocalTask();
            } else {
                stopLocalTask();
                maybeStopScheduler();
            }
        }
    }

    @Override
    public void autoUpdateGitDependenciesChanged() {
        synchronized (schedulerLock) {
            var project = chrome.getProject();
            if (project.getAutoUpdateGitDependencies()) {
                ensureSchedulerRunning();
                startGitTask();
            } else {
                stopGitTask();
                maybeStopScheduler();
            }
        }
    }

    private void ensureSchedulerRunning() {
        synchronized (schedulerLock) {
            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    var t = new Thread(r, "DependencyUpdate");
                    t.setDaemon(true);
                    return t;
                });
                logger.debug("Started dependency update scheduler");
            }
        }
    }

    private void maybeStopScheduler() {
        synchronized (schedulerLock) {
            if (localTask == null && gitTask == null && scheduler != null) {
                scheduler.shutdown();
                scheduler = null;
                logger.debug("Stopped dependency update scheduler (no active tasks)");
            }
        }
    }

    private void startLocalTask() {
        synchronized (schedulerLock) {
            if (localTask != null) {
                logger.debug("Local dependency task already scheduled");
                return;
            }

            assert scheduler != null;
            // Run immediate check, then every 5 minutes
            scheduler.execute(() -> DependencyUpdateHelper.autoUpdateLocalDependencies(chrome));
            localTask = scheduler.scheduleAtFixedRate(
                    () -> DependencyUpdateHelper.autoUpdateLocalDependencies(chrome),
                    LOCAL_CHECK_INTERVAL_MINUTES,
                    LOCAL_CHECK_INTERVAL_MINUTES,
                    TimeUnit.MINUTES);

            logger.info("Started local dependency update task (interval: {} minutes)", LOCAL_CHECK_INTERVAL_MINUTES);
        }
    }

    private void stopLocalTask() {
        synchronized (schedulerLock) {
            if (localTask != null) {
                localTask.cancel(false);
                localTask = null;
                logger.info("Stopped local dependency update task");
            }
        }
    }

    private void startGitTask() {
        synchronized (schedulerLock) {
            if (gitTask != null) {
                logger.debug("Git dependency task already scheduled");
                return;
            }

            assert scheduler != null;
            // Run immediate check, then every 30 minutes
            scheduler.execute(() -> DependencyUpdateHelper.autoUpdateGitDependencies(chrome));
            gitTask = scheduler.scheduleAtFixedRate(
                    () -> DependencyUpdateHelper.autoUpdateGitDependencies(chrome),
                    GIT_CHECK_INTERVAL_MINUTES,
                    GIT_CHECK_INTERVAL_MINUTES,
                    TimeUnit.MINUTES);

            logger.info("Started Git dependency update task (interval: {} minutes)", GIT_CHECK_INTERVAL_MINUTES);
        }
    }

    private void stopGitTask() {
        synchronized (schedulerLock) {
            if (gitTask != null) {
                gitTask.cancel(false);
                gitTask = null;
                logger.info("Stopped Git dependency update task");
            }
        }
    }

    /**
     * Shuts down the scheduler and unregisters from settings changes.
     * Call this when the project is closing.
     */
    public void close() {
        MainProject.removeSettingsChangeListener(this);
        chrome.getContextManager().removeAnalyzerCallback(this);
        synchronized (schedulerLock) {
            stopLocalTask();
            stopGitTask();
            if (scheduler != null) {
                scheduler.shutdown();
                scheduler = null;
            }
        }
        logger.info("Closed dependency update scheduler");
    }
}
