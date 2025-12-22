package ai.brokk.util;

import static java.util.Objects.requireNonNull;

import ai.brokk.Brokk;
import ai.brokk.SettingsChangeListener;
import ai.brokk.annotation.EdtSafe;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.dependencies.DependencyUpdateHelper;
import ai.brokk.project.MainProject;
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
 *
 * <p>This scheduler is owned by {@link MainProject} to ensure a single scheduler instance
 * per project, preventing race conditions when multiple worktrees share the same dependencies.
 */
public class DependencyUpdateScheduler implements SettingsChangeListener {
    private static final Logger logger = LogManager.getLogger(DependencyUpdateScheduler.class);

    private static final long LOCAL_CHECK_INTERVAL_MINUTES = 5;
    private static final long GIT_CHECK_INTERVAL_MINUTES = 30;

    private final MainProject project;
    private final Object schedulerLock = new Object();

    private @Nullable ScheduledExecutorService scheduler;
    private @Nullable ScheduledFuture<?> localTask;
    private @Nullable ScheduledFuture<?> gitTask;

    public DependencyUpdateScheduler(MainProject project) {
        this.project = project;
        MainProject.addSettingsChangeListener(this);

        // Check initial state and start tasks if enabled
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

    /**
     * Called when a Chrome instance's analyzer becomes ready.
     * Triggers an immediate dependency check if tasks are scheduled.
     */
    public void onAnalyzerReady() {
        logger.debug(
                "Analyzer ready, running initial dependency checks for {}",
                project.getRoot().getFileName());
        synchronized (schedulerLock) {
            if (scheduler != null && !scheduler.isShutdown()) {
                if (localTask != null) {
                    scheduler.execute(this::runLocalUpdate);
                }
                if (gitTask != null) {
                    scheduler.execute(this::runGitUpdate);
                }
            }
        }
    }

    @Override
    public void autoUpdateLocalDependenciesChanged() {
        synchronized (schedulerLock) {
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
            if (project.getAutoUpdateGitDependencies()) {
                ensureSchedulerRunning();
                startGitTask();
            } else {
                stopGitTask();
                maybeStopScheduler();
            }
        }
    }

    @EdtSafe
    private void ensureSchedulerRunning() {
        synchronized (schedulerLock) {
            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    var t = new Thread(
                            r, "DependencyUpdate-" + project.getRoot().getFileName());
                    t.setDaemon(true);
                    return t;
                });
                logger.debug(
                        "Started dependency update scheduler for {}",
                        project.getRoot().getFileName());
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

            var sched = requireNonNull(scheduler);
            // Run immediate check, then every 5 minutes
            sched.execute(this::runLocalUpdate);
            localTask = sched.scheduleAtFixedRate(
                    this::runLocalUpdate, LOCAL_CHECK_INTERVAL_MINUTES, LOCAL_CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES);

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

            var sched = requireNonNull(scheduler);
            // Run immediate check, then every 30 minutes
            sched.execute(this::runGitUpdate);
            gitTask = sched.scheduleAtFixedRate(
                    this::runGitUpdate, GIT_CHECK_INTERVAL_MINUTES, GIT_CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES);

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
     * Finds a Chrome instance for this project to use for background task submission.
     * Checks both the main project window and worktree windows.
     */
    private @Nullable Chrome findChrome() {
        // Try main project window first
        var chrome = Brokk.findOpenProjectWindow(project.getRoot());
        if (chrome != null) {
            return chrome;
        }
        // Try worktree windows
        var worktreeChromes = Brokk.getWorktreeChromes(project);
        return worktreeChromes.isEmpty() ? null : worktreeChromes.get(0);
    }

    private void runLocalUpdate() {
        var chrome = findChrome();
        if (chrome == null) {
            logger.debug("No Chrome available for local dependency update, skipping");
            return;
        }
        DependencyUpdateHelper.autoUpdateLocalDependencies(chrome);
    }

    private void runGitUpdate() {
        var chrome = findChrome();
        if (chrome == null) {
            logger.debug("No Chrome available for Git dependency update, skipping");
            return;
        }
        DependencyUpdateHelper.autoUpdateGitDependencies(chrome);
    }

    /**
     * Shuts down the scheduler and unregisters from settings changes.
     * Call this when the project is closing.
     */
    public void close() {
        MainProject.removeSettingsChangeListener(this);
        synchronized (schedulerLock) {
            stopLocalTask();
            stopGitTask();
            if (scheduler != null) {
                scheduler.shutdown();
                scheduler = null;
            }
        }
        logger.info(
                "Closed dependency update scheduler for {}", project.getRoot().getFileName());
    }
}
