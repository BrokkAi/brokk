package io.github.jbellis.brokk;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.IWatchService.EventBatch;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.analyzer.DisabledAnalyzer;
import io.github.jbellis.brokk.util.LoggingExecutorService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class AnalyzerWrapper implements IWatchService.Listener, IAnalyzerWrapper {
    private final Logger logger = LogManager.getLogger(AnalyzerWrapper.class);

    @Nullable
    private final AnalyzerListener listener; // can be null if no one is listening

    private final Path root;

    @Nullable
    private final Path gitRepoRoot;

    private final IProject project;
    private final IWatchService watchService;

    private volatile @Nullable IAnalyzer currentAnalyzer = null;

    // Flags related to external rebuild requests and readiness
    private volatile boolean externalRebuildRequested = false;
    private volatile boolean wasReady = false;
    private final AtomicLong idlePollTriggeredRebuilds = new AtomicLong(0);

    // Dedicated single-threaded executor for analyzer refresh tasks
    private final LoggingExecutorService analyzerExecutor;
    private volatile @Nullable Thread analyzerExecutorThread;

    public AnalyzerWrapper(IProject project, @Nullable AnalyzerListener listener, IConsoleIO io) {
        this.project = project;
        this.root = project.getRoot();
        gitRepoRoot = project.hasGit() ? project.getRepo().getGitTopLevel() : null;
        this.listener = listener;
        if (listener == null) {
            this.watchService = new IWatchService() {};
        } else {
            this.watchService = new ProjectWatchService(root, gitRepoRoot, this);
        }

        // Create a single-threaded executor for analyzer refresh tasks (wrapped with logging).
        var threadFactory = new ThreadFactory() {
            private final ThreadFactory delegate = Executors.defaultThreadFactory();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = delegate.newThread(r);
                t.setName("brokk-analyzer-exec-" + root.getFileName());
                t.setDaemon(true);
                analyzerExecutorThread = t; // Store the thread reference
                return t;
            }
        };
        var delegateExecutor = Executors.newSingleThreadExecutor(threadFactory);
        Consumer<Throwable> exceptionHandler = th -> logger.error("Uncaught exception in analyzer executor", th);
        this.analyzerExecutor = new LoggingExecutorService(delegateExecutor, exceptionHandler);

        // build the initial Analyzer
        analyzerExecutor.submit(() -> {
            // Watcher will wait for the future to complete before processing events,
            // but it will start watching files immediately in order not to miss any changes in the meantime.
            var delayNotificationsUntilCompleted = new CompletableFuture<Void>();
            watchService.start(delayNotificationsUntilCompleted);

            // Loading the analyzer with `Optional.empty` tells the analyzer to determine changed files on its own
            long start = System.currentTimeMillis();
            currentAnalyzer = loadOrCreateAnalyzer(io);
            long durationMs = System.currentTimeMillis() - start;

            delayNotificationsUntilCompleted.complete(null);

            // debug logging
            final var metrics = currentAnalyzer.getMetrics();
            logger.debug(
                    "Initial analyzer has {} declarations across {} files and took {} ms",
                    metrics.numberOfDeclarations(),
                    metrics.numberOfCodeUnits(),
                    durationMs);

            return currentAnalyzer;
        });
    }

    @Override
    public void onFilesChanged(EventBatch batch) {
        logger.trace("Events batch: {}", batch);

        // Instrumentation: log reason for callback and whether it includes .git metadata changes
        Path gitMetaRel = (gitRepoRoot != null) ? root.relativize(gitRepoRoot.resolve(".git")) : null;
        boolean dueToGitMeta = gitMetaRel != null
                && batch.files.stream().anyMatch(pf -> pf.getRelPath().startsWith(gitMetaRel));
        logger.debug(
                "onFilesChanged fired: files={}, overflowed={}, dueToGitMeta={}, gitMetaDir={}",
                batch.files.size(),
                batch.isOverflowed,
                dueToGitMeta,
                (gitMetaRel != null ? gitMetaRel : "(none)"));

        // 1) Possibly refresh Git
        if (gitRepoRoot != null) {
            Path relativeGitMetaDir = root.relativize(gitRepoRoot.resolve(".git"));
            if (batch.isOverflowed
                    || batch.files.stream().anyMatch(pf -> pf.getRelPath().startsWith(relativeGitMetaDir))) {
                logger.debug("Changes in git metadata directory ({}) detected", gitRepoRoot.resolve(".git"));
                if (listener != null) {
                    listener.onRepoChange();
                    listener.onTrackedFileChange(); // Tracked files can also change as a result, e.g. git add <files>
                }
            }
        }
        // 2) If overflowed, assume something changed
        if (batch.isOverflowed) {
            if (listener != null) listener.onTrackedFileChange();
            refresh(prev -> {
                long startTime = System.currentTimeMillis();
                IAnalyzer result = prev.update();
                long duration = System.currentTimeMillis() - startTime;
                logger.info(
                        "Library ingestion: {} analyzer refresh completed in {}ms", getLanguageDescription(), duration);
                return result;
            });
        }

        // 3) We have an exact files list to check
        var trackedFiles = project.getRepo().getTrackedFiles();
        var projectLanguages = project.getAnalyzerLanguages();

        var changedTrackedFiles =
                batch.files.stream().filter(trackedFiles::contains).collect(Collectors.toSet());

        if (!changedTrackedFiles.isEmpty()) {
            // call listener (refreshes git panel)
            logger.debug("Changes in tracked files detected");
            if (listener != null) listener.onTrackedFileChange();
        }

        var relevantFiles = changedTrackedFiles.stream()
                .filter(pf -> projectLanguages.stream()
                        .anyMatch(L -> L.getExtensions().contains(pf.extension())))
                .collect(Collectors.toSet());

        if (!relevantFiles.isEmpty()) {
            logger.debug(
                    "Rebuilding analyzer due to changes in tracked files relevant to configured languages: {}",
                    relevantFiles.stream()
                            .filter(pf -> {
                                Language lang = Languages.fromExtension(pf.extension());
                                return projectLanguages.contains(lang);
                            })
                            .distinct()
                            .map(ProjectFile::toString)
                            .collect(Collectors.joining(", ")));

            updateFiles(relevantFiles);
        } else {
            logger.trace(
                    "No tracked files changed for any of the configured analyzer languages; skipping analyzer rebuild");
        }
    }

    @Override
    public CompletableFuture<IAnalyzer> updateFiles(Set<ProjectFile> relevantFiles) {
        return refresh(prev -> {
            long startTime = System.currentTimeMillis();
            IAnalyzer result = prev.update(relevantFiles);
            long duration = System.currentTimeMillis() - startTime;
            logger.info(
                    "Library ingestion: {} analyzer processed {} files in {}ms",
                    getLanguageDescription(),
                    relevantFiles.size(),
                    duration);
            return result;
        });
    }

    @Override
    public void onNoFilesChangedDuringPollInterval() {
        if (externalRebuildRequested) {
            long count = idlePollTriggeredRebuilds.incrementAndGet();
            logger.debug("Idle-poll triggered external rebuild #{}", count);
            refresh(prev -> getLanguageHandle().createAnalyzer(project));
            externalRebuildRequested = false;
        }
    }

    /**
     * Builds or loads an {@link IAnalyzer} for the project.
     *
     * <p>All “loop over every language” work is now delegated to a single {@link Language} handle:
     *
     * <ul>
     *   <li>If the project has exactly one concrete language, that language is used directly.
     *   <li>If the project has several languages, a new {@link Language.MultiLanguage} wrapper is created to fan‑out
     *       the work behind the scenes.
     * </ul>
     *
     * <p>The <strong>cache / staleness</strong> checks that used to live in the helper method <code>
     * loadSingleCachedAnalyzerForLanguage</code> are now performed here <em>before</em> we decide whether to call
     * <code>langHandle.loadAnalyzer()</code> (use cache) or <code>langHandle.createAnalyzer()</code> (full rebuild).
     */
    private IAnalyzer loadOrCreateAnalyzer(IConsoleIO io) {
        /* ── 0.  Decide which languages we are dealing with ─────────────────────────── */
        Language langHandle = getLanguageHandle();
        var projectLangs = project.getAnalyzerLanguages().stream()
                .filter(l -> l != Languages.NONE)
                .map(Language::name)
                .collect(Collectors.toList());
        logger.info("Setting up analyzer for languages: {} in directory: {}", projectLangs, project.getRoot());
        logger.debug("Loading/creating analyzer for languages: {}", langHandle);
        if (langHandle == Languages.NONE) {
            logger.info("No languages configured, using disabled analyzer for: {}", project.getRoot());
            return new DisabledAnalyzer();
        }

        /* ── 1.  Pre‑flight notifications & build details ───────────────────────────── */
        if (listener != null) {
            listener.beforeEachBuild();
        }

        logger.debug("Waiting for build details");
        BuildAgent.BuildDetails buildDetails = project.awaitBuildDetails();
        if (buildDetails.equals(BuildAgent.BuildDetails.EMPTY))
            logger.warn("Build details are empty or null. Analyzer functionality may be limited.");

        /* ── 2.  Determine if any cached storage is stale ───────────────────────────────── */
        logger.debug("Scanning for modified project files");
        boolean needsRebuild = externalRebuildRequested; // explicit user request wins
        for (Language lang : project.getAnalyzerLanguages()) {
            Path storagePath = lang.getStoragePath(project);
            // todo: This will not exist for most analyzers right now
            if (!Files.exists(storagePath)) { // no cache → rebuild
                needsRebuild = true;
                continue;
            }
            // Filter tracked files relevant to this language
            List<ProjectFile> tracked = project.getFiles(lang).stream().toList();
            if (isStale(lang, storagePath, tracked)) needsRebuild = true; // cache older than sources
        }

        /* ── 3.  Load or build the analyzer via the Language handle ─────────────────── */
        IAnalyzer analyzer;
        try {
            logger.debug("Attempting to load existing analyzer");
            analyzer = langHandle.loadAnalyzer(project);
            logger.info(
                    "Loaded existing analyzer: {} for directory: {}",
                    analyzer.getClass().getSimpleName(),
                    project.getRoot());
            if (analyzer instanceof CanCommunicate communicativeAnalyzer) {
                communicativeAnalyzer.setIo(io);
            }
        } catch (Throwable th) {
            // cache missing or corrupt, rebuild
            logger.warn(th);
            analyzer = langHandle.createAnalyzer(project);
            logger.info(
                    "Created new analyzer: {} for directory: {}",
                    analyzer.getClass().getSimpleName(),
                    project.getRoot());
            needsRebuild = false;
        }

        /* ── 4.  Notify listeners ───────────────────────────────────────────────────── */
        if (listener != null) {
            logger.debug("AnalyzerWrapper has listener, submitting workspace refresh task");

            // always refresh workspace in case there was a race and we shut down
            // after saving a new analyzer but before refreshing the workspace
            if (wasReady) {
                logger.debug("No analyzer ready transition detected");
            } else {
                logger.debug("Analyzer became ready during loadOrCreateAnalyzer, notifying listeners");
                listener.onAnalyzerReady();
            }
            listener.afterEachBuild(false);
            wasReady = true;
        } else {
            logger.debug("AnalyzerWrapper has no listener - skipping notification");
        }

        /* ── 5.  If we used stale caches, schedule a background rebuild ─────────────── */
        if (needsRebuild && !externalRebuildRequested) {
            logger.debug("Scheduling background refresh");
            refresh(IAnalyzer::update);
        }

        logger.debug("Analyzer load complete!");
        return analyzer;
    }

    @Override
    public boolean providesInterproceduralAnalysis() {
        return project.getAnalyzerLanguages().stream().anyMatch(Language::providesInterproceduralAnalysis);
    }

    @Override
    public boolean providesSummaries() {
        return project.getAnalyzerLanguages().stream().anyMatch(Language::providesSummaries);
    }

    @Override
    public boolean providesSourceCode() {
        return project.getAnalyzerLanguages().stream().anyMatch(Language::providesSourceCode);
    }

    /** Convenience overload that infers the language set from {@link #project}. */
    private Language getLanguageHandle() {
        var projectLangs = project.getAnalyzerLanguages().stream()
                .filter(l -> l != Languages.NONE)
                .collect(Collectors.toUnmodifiableSet());
        if (projectLangs.isEmpty()) {
            return Languages.NONE;
        }
        return (projectLangs.size() == 1) ? projectLangs.iterator().next() : new Language.MultiLanguage(projectLangs);
    }

    /** Get a human-readable description of the analyzer languages for logging. */
    private String getLanguageDescription() {
        return project.getAnalyzerLanguages().stream()
                .filter(l -> l != Languages.NONE)
                .map(Language::name)
                .collect(Collectors.joining("/"));
    }

    /**
     * Determine whether the cached analyzer for the given language is stale relative to its tracked source files and
     * any user-requested rebuilds.
     *
     * <p>
     *
     * <p>The caller guarantees that {@code analyzerPath} is non-null and exists.
     */
    private boolean isStale(Language lang, Path analyzerPath, List<ProjectFile> trackedFiles) {
        // An explicit external rebuild request always wins.
        if (externalRebuildRequested) {
            return true;
        }

        long lastModifiedTime;
        try {
            lastModifiedTime = Files.getLastModifiedTime(analyzerPath).toMillis();
        } catch (IOException e) {
            logger.warn("Error reading analyzer file timestamp for {}: {}", analyzerPath, e.getMessage());
            // Unable to read the timestamp - treat the cache as stale so that we rebuild.
            return true;
        }

        for (ProjectFile rf : trackedFiles) {
            try {
                var path = rf.absPath();
                if (!Files.exists(path)) {
                    // The file was removed - that is effectively newer than the CPG.
                    return true;
                }
                long fileMTime = Files.getLastModifiedTime(path).toMillis();
                if (fileMTime > lastModifiedTime) {
                    logger.debug(
                            "Tracked file {} for language {} is newer than its CPG {} ({} > {})",
                            path,
                            lang.name(),
                            analyzerPath,
                            fileMTime,
                            lastModifiedTime);
                    return true;
                }
            } catch (IOException e) {
                logger.debug(
                        "Error reading timestamp for tracked file {} (language {}): {}",
                        rf.absPath(),
                        lang.name(),
                        e.getMessage());
                // If we cannot evaluate the timestamp, be conservative.
                return true;
            }
        }

        return false;
    }

    /**
     * Refreshes the analyzer by scheduling a job on the analyzerExecutor. The function controls whether a new analyzer
     * is created, or an optimistic or pessimistic incremental rebuild. The function receives the current analyzer
     * (possibly {@code null}) as its argument and must return the new analyzer to become current.
     *
     * <p>Returns the Future representing the scheduled task.
     *
     * <p>Synchronized to simplify reasoning about pause/resume; otherwise is inherently threadsafe.
     */
    private synchronized CompletableFuture<IAnalyzer> refresh(Function<IAnalyzer, IAnalyzer> fn) {
        logger.trace("Scheduling analyzer refresh task");
        return analyzerExecutor.submit(() -> {
            requireNonNull(currentAnalyzer);
            if (listener != null) {
                listener.beforeEachBuild();
            }
            // The function is supplied the current analyzer (may be null).
            currentAnalyzer = fn.apply(currentAnalyzer);
            logger.debug("Analyzer refresh completed.");
            if (listener != null) {
                boolean isNowReady = (currentAnalyzer != null);
                logger.debug(
                        "Checking analyzer ready transition after refresh: wasReady={}, isNowReady={}",
                        wasReady,
                        isNowReady);
                if (!wasReady && isNowReady) {
                    logger.debug("Analyzer became ready, notifying listeners");
                    listener.onAnalyzerReady();
                }
                listener.afterEachBuild(externalRebuildRequested);
                wasReady = isNowReady;
            }
            return currentAnalyzer;
        });
    }

    /** Get the analyzer, showing a spinner UI while waiting if requested. */
    @Override
    public IAnalyzer get() throws InterruptedException {
        // Prevent calling blocking get() from the EDT.
        if (SwingUtilities.isEventDispatchThread()) {
            logger.error("Never call blocking get() from EDT", new UnsupportedOperationException());
            if (Boolean.getBoolean("brokk.devmode")) {
                throw new UnsupportedOperationException("Never call blocking get() from EDT");
            }
        }

        // If we already have an analyzer, just return it.
        if (currentAnalyzer != null) {
            return currentAnalyzer;
        }

        // Prevent blocking on the analyzer's own executor thread.
        if (Thread.currentThread() == analyzerExecutorThread) {
            throw new IllegalStateException("Attempted to call blocking get() from the analyzer's own executor thread "
                    + "before the analyzer was ready. This would cause a deadlock.");
        }

        // Otherwise, this must be the very first build (or a failed one); we'll have to wait for it to be ready.
        if (listener != null) {
            listener.onBlocked();
        }
        while (currentAnalyzer == null) {
            //noinspection BusyWait
            Thread.sleep(100);
        }
        return currentAnalyzer;
    }

    /** @return null if analyzer is not ready yet */
    @Override
    public @Nullable IAnalyzer getNonBlocking() {
        return currentAnalyzer;
    }

    @Override
    public void requestRebuild() {
        externalRebuildRequested = true;
    }

    /** Pause the file watching service. */
    @Override
    public synchronized void pause() {
        watchService.pause();
    }

    /** Resume the file watching service. */
    @Override
    public synchronized void resume() {
        watchService.resume();
    }

    @Override
    public void close() {
        watchService.close();
        try {
            // Attempt a graceful shutdown of the analyzer executor; do not propagate exceptions.
            analyzerExecutor.shutdownAndAwait(5000L, "AnalyzerWrapper");
        } catch (Throwable th) {
            logger.debug("Exception while shutting down analyzerExecutor: {}", th.getMessage());
        }
    }
}
