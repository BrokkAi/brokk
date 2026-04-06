package ai.brokk;

import static java.util.Objects.requireNonNull;

import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.DisabledAnalyzer;
import ai.brokk.analyzer.FrameworkTemplate;
import ai.brokk.analyzer.FrameworkTemplates;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.MultiAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TemplateAnalysisResult;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.concurrent.LoggingExecutorService;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.IProject;
import ai.brokk.project.WorktreeProject;
import ai.brokk.watchservice.AbstractWatchService;
import ai.brokk.watchservice.AbstractWatchService.EventBatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AnalyzerWrapper implements AbstractWatchService.Listener, IAnalyzerWrapper {
    private final Logger logger = LogManager.getLogger(AnalyzerWrapper.class);

    private final AnalyzerListener listener; // can be null if no one is listening

    private final Path root;

    private final IProject project;
    private final AbstractWatchService watchService;

    private volatile @Nullable IAnalyzer currentAnalyzer = null;

    // Flags related to external rebuild requests and readiness
    private volatile boolean externalRebuildRequested = false;
    private final AtomicLong idlePollTriggeredRebuilds = new AtomicLong(0);

    // Dedicated single-threaded executor for analyzer refresh tasks
    private final LoggingExecutorService analyzerExecutor;
    private volatile @Nullable Thread analyzerExecutorThread;
    private boolean readyForWatcherEvents = false;
    private final List<EventBatch> queuedWatcherEvents = new ArrayList<>();

    /**
     * Creates an AnalyzerWrapper with an injected watch service.
     * This is the preferred constructor - it allows the caller to create and manage the IWatchService,
     * then inject it into AnalyzerWrapper. AnalyzerWrapper will register itself as a listener if needed.
     *
     * @param project The project to analyze
     * @param analyzerListener Listener for analyzer lifecycle events (can be null for headless mode)
     * @param watchService The watch service to use (can be null for headless mode or testing)
     */
    public AnalyzerWrapper(
            IProject project, AnalyzerListener analyzerListener, @NotNull AbstractWatchService watchService) {
        this.project = project;
        this.root = project.getRoot();
        this.listener = analyzerListener;

        // Use provided watch service or create stub for headless mode
        this.watchService = watchService;
        watchService.addListener(this);

        // Initialize executor and analyzer
        this.analyzerExecutor = createAnalyzerExecutor();
        submitInitialAnalyzerBuild();
    }

    /**
     * Creates the single-threaded executor for analyzer refresh tasks.
     */
    private LoggingExecutorService createAnalyzerExecutor() {
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
        return new LoggingExecutorService(delegateExecutor, exceptionHandler);
    }

    /**
     * Submits the initial analyzer build task.
     */
    private void submitInitialAnalyzerBuild() {
        analyzerExecutor.submit(() -> {
            if (project instanceof WorktreeProject wp) {
                wp.warmStartAnalyzerCachesFromParent();
            }

            migrateAnalyzerState();

            long start = System.currentTimeMillis();
            IAnalyzer analyzer = loadOrCreateAnalyzer();
            long durationMs = System.currentTimeMillis() - start;

            // Update currentAnalyzer and mark readiness in the same task to avoid races
            currentAnalyzer = analyzer;
            readyForWatcherEvents = true;
            processQueuedWatcherEvents();

            // debug logging
            final var metrics = analyzer.getMetrics();
            logger.debug(
                    "Initial analyzer has {} declarations across {} files and took {} ms",
                    metrics.numberOfDeclarations(),
                    metrics.numberOfCodeUnits(),
                    durationMs);

            return analyzer;
        });
    }

    private void processQueuedWatcherEvents() {
        logger.debug("Processing {} queued watcher event batches", queuedWatcherEvents.size());
        List<EventBatch> localQueuedEvents;
        synchronized (queuedWatcherEvents) {
            localQueuedEvents = new ArrayList<>(queuedWatcherEvents);
            queuedWatcherEvents.clear();
        }

        for (var batch : localQueuedEvents) {
            onFilesChanged(batch);
        }
    }

    @Override
    public void onFilesChanged(EventBatch batch) {
        logger.trace("AnalyzerWrapper received events batch: {}", batch);
        if (!readyForWatcherEvents) {
            synchronized (queuedWatcherEvents) {
                if (!readyForWatcherEvents) {
                    logger.debug("AnalyzerWrapper not ready for watcher events yet, queuing batch");
                    queuedWatcherEvents.add(batch);
                    return;
                }
            }
        }

        logger.trace(
                "onFilesChanged fired: files={}, overflowed={}, untrackedGitignoreChanged={}",
                batch.getFiles().size(),
                batch.isOverflowed(),
                batch.isUntrackedGitignoreChanged());

        // 1) Handle overflow - trigger full analyzer rebuild
        if (batch.isOverflowed()) {
            logger.debug("Event batch overflowed, triggering full analyzer rebuild");
            project.getRepo().invalidateCaches();
            project.invalidateAllFiles();
            refresh(prev -> {
                long startTime = System.currentTimeMillis();
                IAnalyzer result = prev.update();
                long duration = System.currentTimeMillis() - startTime;
                logger.info("{} overflow analyzer refresh completed in {}ms", getLanguageDescription(), duration);
                return result;
            });
            return; // No need to process individual files after full rebuild
        }

        // 2) Determine if cache invalidation is necessary before filtering
        final var trackedFilesBefore = project.getRepo().getTrackedFiles();
        boolean untrackedGitignoreChanged = batch.isUntrackedGitignoreChanged();
        boolean hasUntrackedFiles = batch.getFiles().stream().anyMatch(f -> !trackedFilesBefore.contains(f));

        final Set<ProjectFile> finalTrackedFiles;
        if (untrackedGitignoreChanged || hasUntrackedFiles) {
            logger.debug(
                    "Refreshing project caches (untrackedGitignoreChanged={}, hasUntrackedFiles={})",
                    untrackedGitignoreChanged,
                    hasUntrackedFiles);

            project.getRepo().invalidateCaches();
            var trackedFilesAfter = project.getRepo().getTrackedFiles();

            // If gitignore changed or the set of tracked files actually changed after repo refresh,
            // we must invalidate the project's file-system-view cache.
            if (untrackedGitignoreChanged || !trackedFilesAfter.equals(trackedFilesBefore)) {
                project.invalidateAllFiles();
            }
            finalTrackedFiles = trackedFilesAfter;
        } else {
            finalTrackedFiles = trackedFilesBefore;
        }

        // 3) Filter for analyzer-relevant files.
        var projectLanguages = requireNonNull(currentAnalyzer).languages();

        // Only consider tracked files that match our analyzer's language extensions
        var relevantFiles = batch.getFiles().stream()
                .filter(finalTrackedFiles::contains) // Must be tracked by git
                .filter(pf -> projectLanguages.stream()
                        .anyMatch(L -> L.getExtensions().contains(pf.extension())))
                .collect(Collectors.toSet());

        // 3) Update analyzer for relevant files
        if (!relevantFiles.isEmpty()) {
            logger.debug(
                    "Rebuilding analyzer due to changes in {} files relevant to configured languages: {}",
                    relevantFiles.size(),
                    relevantFiles.stream()
                            .limit(10) // Log first 10 files to avoid excessive logging
                            .map(ProjectFile::toString)
                            .collect(Collectors.joining(", ")));

            updateFiles(relevantFiles);
        } else {
            logger.trace(
                    "No analyzer-relevant files changed (batch contained {} files); skipping analyzer rebuild",
                    batch.getFiles().size());
        }
    }

    @Override
    public CompletableFuture<IAnalyzer> updateFiles(Set<ProjectFile> relevantFiles) {
        return refresh(prev -> {
            long startTime = System.currentTimeMillis();
            IAnalyzer result = prev.update(relevantFiles);
            long duration = System.currentTimeMillis() - startTime;
            logger.info(
                    "{} analyzer update processed {} files in {}ms",
                    getLanguageDescription(),
                    relevantFiles.size(),
                    duration);
            return result;
        });
    }

    @Override
    public void onNoFilesChangedDuringPollInterval() {
        processExternalRebuildRequest();
    }

    private synchronized void processExternalRebuildRequest() {
        if (!externalRebuildRequested) {
            return;
        }

        long count = idlePollTriggeredRebuilds.incrementAndGet();
        logger.debug("Triggering external rebuild #{}", count);

        IAnalyzer.ProgressListener progressListener = listener::onProgress;

        refresh(prev -> {
            Set<Language> currentLangs = prev.languages();
            Set<Language> projectLangs = project.getAnalyzerLanguages().stream()
                    .filter(l -> l != Languages.NONE)
                    .collect(Collectors.toSet());

            if (currentLangs.equals(projectLangs)) {
                logger.info(
                        "External rebuild requested but languages match; clearing persisted state and performing update.");
                deletePersistedAnalyzerStateFiles(projectLangs);
                return prev.update();
            }

            logger.info("External rebuild requested: language set changed from {} to {}.", currentLangs, projectLangs);

            // 1. Identify and delete state for removed languages
            Set<Language> removedLangs = new HashSet<>(currentLangs);
            removedLangs.removeAll(projectLangs);
            deletePersistedAnalyzerStateFiles(removedLangs);

            // 2. Compose the next analyzer
            Map<Language, IAnalyzer> nextDelegates = new HashMap<>();
            for (Language lang : projectLangs) {
                Optional<IAnalyzer> existingSub = prev.subAnalyzer(lang);
                if (existingSub.isPresent()) {
                    // Language was already present, perform incremental update
                    nextDelegates.put(lang, existingSub.get().update());
                } else {
                    // New language added, try to load from disk or create fresh
                    try {
                        nextDelegates.put(lang, lang.loadAnalyzer(project, progressListener));
                    } catch (Throwable t) {
                        logger.debug("Failed to load cached analyzer for new language {}, creating fresh", lang, t);
                        nextDelegates.put(lang, lang.createAnalyzer(project, progressListener));
                    }
                }
            }

            var templates = FrameworkTemplates.discoverTemplateAnalyzers(project, projectLangs);
            if (!templates.isEmpty()) {
                logger.info(
                        "Discovered {} template analyzers: {}",
                        templates.size(),
                        templates.stream().map(it -> it.name()).collect(Collectors.joining(", ")));
            } else {
                logger.debug("No template analyzers discovered for this project.");
            }

            if (nextDelegates.isEmpty()) {
                return new DisabledAnalyzer(project);
            } else if (nextDelegates.size() == 1 && templates.isEmpty()) {
                return nextDelegates.values().iterator().next();
            } else {
                return new MultiAnalyzer(nextDelegates, templates);
            }
        });
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
    private IAnalyzer loadOrCreateAnalyzer() {
        /* ── 0.  Decide which languages we are dealing with ─────────────────────────── */
        Set<Language> projectLangsSet = project.getAnalyzerLanguages();
        Language langHandle = Languages.aggregate(projectLangsSet);

        var templates = FrameworkTemplates.discoverTemplateAnalyzers(project, projectLangsSet);
        if (!templates.isEmpty()) {
            logger.info(
                    "Discovered {} template analyzers: {}",
                    templates.size(),
                    templates.stream().map(it -> it.name()).collect(Collectors.joining(", ")));
        } else {
            logger.debug("No template analyzers discovered for this project.");
        }

        if (langHandle == Languages.NONE) {
            logger.info("No languages configured, using disabled analyzer for: {}", project.getRoot());
            logger.debug("Analyzer became ready (Disabled), notifying listeners");
            listener.onAnalyzerReady();
            listener.afterEachBuild(false);
            return new DisabledAnalyzer(project);
        }

        logger.info("Setting up analyzer for languages: {} in directory: {}", langHandle.name(), project.getRoot());
        logger.debug("Loading/creating analyzer for languages: {}", langHandle);

        /* ── 1.  Pre‑flight notifications & build details ───────────────────────────── */
        listener.beforeEachBuild();

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
            Set<ProjectFile> tracked = project.getAnalyzableFiles(lang);
            if (isStale(lang, storagePath, tracked)) needsRebuild = true; // cache older than sources
        }

        /* ── 3.  Load or build the analyzer delegates ───────────────────────────────── */
        // Create progress listener to pass through construction
        IAnalyzer.ProgressListener progressListener = listener::onProgress;

        Set<Language> projectLangs = project.getAnalyzerLanguages().stream()
                .filter(l -> l != Languages.NONE)
                .collect(Collectors.toSet());

        Map<Language, IAnalyzer> nextDelegates = new HashMap<>();
        for (Language lang : projectLangs) {
            try {
                IAnalyzer delegate;
                try {
                    logger.debug("Attempting to load existing analyzer for {}", lang.name());
                    delegate = lang.loadAnalyzer(project, progressListener);
                } catch (Throwable th) {
                    logger.warn("Failed to load cached analyzer for {}, creating fresh", lang.name(), th);
                    try {
                        delegate = lang.createAnalyzer(project, progressListener);
                    } catch (Throwable t2) {
                        logger.error(
                                "Critical failure creating analyzer for language {}: {}",
                                lang.name(),
                                t2.toString(),
                                t2);
                        continue;
                    }
                    needsRebuild = true;
                }
                nextDelegates.put(lang, delegate);

                // Restore template analyzer state
                for (var ta : templates) {
                    List<TemplateAnalysisResult> restored = List.of();
                    // 1. Try to restore from host's TreeSitter state if available
                    if (delegate instanceof TreeSitterAnalyzer ts) {
                        restored = ts.snapshotState().templateResults().values().stream()
                                .flatMap(List::stream)
                                .filter(r -> r.analyzerName().equals(ta.internalName()))
                                .toList();
                    }
                    // 2. If host had no state for this template, try loading from its own dedicated cache
                    if (restored.isEmpty() && ta instanceof FrameworkTemplate ft) {
                        restored = FrameworkTemplates.loadTemplateAnalyzerState(ft, project);
                    }
                    if (!restored.isEmpty()) {
                        ta.restoreState(restored);
                    }
                }
            } catch (Throwable th) {
                logger.error(
                        "Unexpected failure during analyzer setup for language {}: {}", lang.name(), th.toString(), th);
            }
        }

        IAnalyzer analyzer = nextDelegates.isEmpty()
                ? new DisabledAnalyzer(project)
                : (nextDelegates.size() == 1 && templates.isEmpty())
                        ? nextDelegates.values().iterator().next()
                        : new MultiAnalyzer(nextDelegates, templates);

        // Validate the loaded analyzer's state against project files
        Optional<StateMismatch> mismatch = stateMismatch(analyzer);
        if (mismatch.isPresent()) {
            StateMismatch delta = mismatch.get();
            logger.warn(
                    "Loaded analyzer state appears corrupt (file mismatch). Attempting targeted repair of {} files.",
                    delta.all().size());

            analyzer = analyzer.update(delta.all());

            // Check if we need to prune or add delegates after update
            Set<Language> analyzerLangs = analyzer.languages();
            if (!analyzerLangs.equals(projectLangs)) {
                Map<Language, IAnalyzer> finalDelegates = new HashMap<>();
                for (Language lang : projectLangs) {
                    analyzer.subAnalyzer(lang).ifPresent(sub -> finalDelegates.put(lang, sub));
                }

                analyzer = finalDelegates.isEmpty()
                        ? new DisabledAnalyzer(project)
                        : (finalDelegates.size() == 1 && templates.isEmpty())
                                ? finalDelegates.values().iterator().next()
                                : new MultiAnalyzer(finalDelegates, templates);
            }

            if (stateMismatch(analyzer).isPresent()) {
                logger.error("Analyzer state remains corrupt after targeted repair attempt. Triggering full rebuild.");
                Map<Language, IAnalyzer> finalDelegates = new HashMap<>();
                for (Language lang : projectLangs) {
                    finalDelegates.put(lang, lang.createAnalyzer(project, progressListener));
                }
                analyzer = finalDelegates.isEmpty()
                        ? new DisabledAnalyzer(project)
                        : (finalDelegates.size() == 1 && templates.isEmpty())
                                ? finalDelegates.values().iterator().next()
                                : new MultiAnalyzer(finalDelegates, templates);
            }
            // Persist the repaired state
            persistAnalyzerState(analyzer);
        }

        logger.debug("Analyzer became ready, notifying listeners");
        listener.onAnalyzerReady();
        listener.afterEachBuild(false);

        /* ── 5.  If we used stale caches, schedule a background rebuild ─────────────── */
        if (needsRebuild && !externalRebuildRequested) {
            logger.debug("Scheduling background refresh");
            refresh(IAnalyzer::update);
        }

        logger.debug("Analyzer load complete!");
        return analyzer;
    }

    /**
     * Represents a mismatch between the files expected by the project and those actually present in the analyzer.
     */
    private record StateMismatch(Set<ProjectFile> missing, Set<ProjectFile> unexpected) {

        /**
         * @return a combination of missing and unexpected files.
         */
        Set<ProjectFile> all() {
            Set<ProjectFile> deltaFiles = new HashSet<>(missing);
            deltaFiles.addAll(unexpected);
            return Set.copyOf(deltaFiles);
        }
    }

    /**
     * Checks if the analyzer's tracked file set, languages, and template analyzers match the project's configuration.
     * Returns an Optional containing the mismatch details if corruption is detected.
     */
    private Optional<StateMismatch> stateMismatch(IAnalyzer analyzer) {
        Set<Language> projectLangs = project.getAnalyzerLanguages().stream()
                .filter(l -> l != Languages.NONE)
                .collect(Collectors.toSet());
        Set<Language> analyzerLangs = analyzer.languages();

        boolean langMismatch = !projectLangs.equals(analyzerLangs);
        if (langMismatch) {
            logger.info("Analyzer language mismatch detected. Project: {}, Analyzer: {}", projectLangs, analyzerLangs);
        }

        // Check for template analyzer mismatch
        boolean templateMismatch = false;
        var expectedTemplates = FrameworkTemplates.discoverTemplateAnalyzers(project, projectLangs).stream()
                .map(it -> it.internalName())
                .collect(Collectors.toSet());
        var actualTemplates = (analyzer instanceof MultiAnalyzer ma)
                ? ma.getTemplateAnalyzers().stream()
                        .map(it -> it.internalName())
                        .collect(Collectors.toSet())
                : Set.of();

        if (!expectedTemplates.equals(actualTemplates)) {
            templateMismatch = true;
            logger.info(
                    "Template analyzer mismatch detected. Expected: {}, Actual: {}",
                    expectedTemplates,
                    actualTemplates);
        }

        Set<ProjectFile> expectedFiles = projectLangs.stream()
                .flatMap(l -> project.getAnalyzableFiles(l).stream())
                .collect(Collectors.toSet());

        Set<ProjectFile> actualFiles = analyzer.getAnalyzedFiles();

        if (langMismatch || templateMismatch || !actualFiles.equals(expectedFiles)) {
            Set<ProjectFile> missing = new HashSet<>(expectedFiles);
            missing.removeAll(actualFiles);
            Set<ProjectFile> unexpected = new HashSet<>(actualFiles);
            unexpected.removeAll(expectedFiles);

            if (!actualFiles.equals(expectedFiles)) {
                logger.debug(
                        "Analyzer file mismatch detected. Missing ({}): {}, Unexpected ({}): {}",
                        missing.size(),
                        missing,
                        unexpected.size(),
                        unexpected);
            }
            return Optional.of(new StateMismatch(missing, unexpected));
        }
        return Optional.empty();
    }

    /** Get a human-readable description of the analyzer languages for logging. */
    private String getLanguageDescription() {
        return currentAnalyzer == null
                ? "Uninitialized"
                : currentAnalyzer.languages().stream().map(Language::name).collect(Collectors.joining("/"));
    }

    /**
     * Determine whether the cached analyzer for the given language is stale relative to its tracked source files and
     * any user-requested rebuilds.
     *
     * <p>
     *
     * <p>The caller guarantees that {@code analyzerPath} is non-null and exists.
     */
    private boolean isStale(Language lang, Path analyzerPath, Set<ProjectFile> trackedFiles) {
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
     * (non-null) as its argument and must return the new analyzer to become current.
     *
     * <p>Returns the Future representing the scheduled task.
     */
    private CompletableFuture<IAnalyzer> refresh(Function<IAnalyzer, IAnalyzer> fn) {
        logger.trace("Scheduling analyzer refresh task");
        return analyzerExecutor.submit(() -> {
            requireNonNull(currentAnalyzer);
            listener.beforeEachBuild();

            // Capture the flag state before the function (which might reset it) executes
            boolean isExternal = externalRebuildRequested;

            // The function is supplied the current analyzer.
            currentAnalyzer = fn.apply(currentAnalyzer);
            // Persist analyzer snapshots by language (best-effort), unless this was an external rebuild
            // where the user specifically wanted to clear persisted state.
            if (!isExternal) {
                persistAnalyzerState(currentAnalyzer);
            }
            logger.debug("Analyzer refresh completed.");

            listener.afterEachBuild(isExternal);
            if (isExternal) {
                externalRebuildRequested = false;
            }

            return currentAnalyzer;
        });
    }

    /** Get the analyzer, showing a spinner UI while waiting if requested. */
    @Override
    @Blocking
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
        listener.onBlocked();

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
        processExternalRebuildRequest();
    }

    /** Pause the file watching service. */
    @Override
    public void pause() {
        watchService.pause();
    }

    /** Resume the file watching service. */
    @Override
    public void resume() {
        watchService.resume();
    }

    @Override
    public boolean isPause() {
        return watchService.isPaused();
    }

    @Override
    public AbstractWatchService getWatchService() {
        return watchService;
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

    /**
     * Delete persisted analyzer state files for the project's languages.
     *
     * <p>This removes both current-format LZ4 snapshots and legacy gzip-based snapshots so that an
     * explicit "refresh code intelligence" operation starts from a clean slate.
     *
     * <p>NOTE: This operation is intentionally conservative and best-effort: IO failures are logged
     * but do not abort the rebuild. Deletions are performed synchronously on the analyzer executor
     * as part of the explicit rebuild path to avoid races with concurrent readers.
     */
    @Override
    public void deletePersistedAnalyzerStateFiles() {
        deletePersistedAnalyzerStateFiles(project.getAnalyzerLanguages());
    }

    /**
     * Delete persisted analyzer state files for the specified languages.
     *
     * @param languages The set of languages to clean up.
     */
    private void deletePersistedAnalyzerStateFiles(Set<Language> languages) {
        try {
            for (var lang : languages) {
                deleteStateForLanguage(lang);
            }

            // Delete persisted framework template analyzer state (per framework, by internal name)
            var langs = project.getAnalyzerLanguages().stream()
                    .filter(l -> l != Languages.NONE)
                    .collect(Collectors.toSet());
            for (var template : FrameworkTemplates.discoverTemplates(project, langs)) {
                try {
                    Files.deleteIfExists(template.getStoragePath(project.getRoot()));
                } catch (IOException e) {
                    logger.debug(
                            "Failed to delete framework template state file {}: {}",
                            template.getStoragePath(project.getRoot()),
                            e.getMessage());
                }
            }
        } catch (Throwable t) {
            logger.debug("Unexpected error in deletePersistedAnalyzerStateFiles(): {}", t.toString());
        }
    }

    private void deleteStateForLanguage(Language lang) {
        if (lang == Languages.NONE) {
            return;
        }
        try {
            Path storage = lang.getStoragePath(project);

            // Primary (current) file
            try {
                if (Files.deleteIfExists(storage)) {
                    logger.info("Deleted persisted analyzer state: {}", storage);
                }
            } catch (IOException e) {
                logger.debug("Failed to delete analyzer state file {}: {}", storage, e.getMessage());
            }
        } catch (Throwable t) {
            logger.debug(
                    "Unexpected error while attempting to delete persisted state for language {}: {}",
                    lang,
                    t.toString());
        }
    }

    /**
     * Migrates analyzer state files from .brokk/ to .brokk/code_intelligence/.
     */
    private void migrateAnalyzerState() {
        Path brokkDir = project.getRoot().resolve(AbstractProject.BROKK_DIR);
        if (!Files.exists(brokkDir)) {
            return;
        }

        Path targetDir = brokkDir.resolve(AbstractProject.CODE_INTELLIGENCE_DIR);

        for (Language lang : project.getAnalyzerLanguages()) {
            if (lang == Languages.NONE) continue;

            String fileName = lang.internalName().toLowerCase(Locale.ROOT) + Language.ANALYZER_STATE_SUFFIX;
            Path oldPath = brokkDir.resolve(fileName);

            if (Files.exists(oldPath)) {
                try {
                    Files.createDirectories(targetDir);
                    Path newPath = targetDir.resolve(fileName);
                    if (Files.exists(newPath)) {
                        logger.info(
                                "Analyzer state for {} already exists at {}; deleting legacy file {}",
                                lang.name(),
                                newPath,
                                oldPath);
                        Files.delete(oldPath);
                    } else {
                        logger.info("Migrating analyzer state for {} from {} to {}", lang.name(), oldPath, newPath);
                        Files.move(oldPath, newPath);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to migrate analyzer state for {}: {}", lang.name(), e.getMessage());
                }
            }
        }
    }

    /**
     * Persist per-language analyzer snapshots if the sub-analyzers are TreeSitter-backed.
     */
    private void persistAnalyzerState(IAnalyzer analyzer) {
        var langs = analyzer.languages();
        if (langs.isEmpty()) {
            logger.trace(
                    "No languages to persist for analyzer: {}",
                    analyzer.getClass().getSimpleName());
            return;
        }

        // Persist each language in parallel since saveAnalyzer is I/O bound
        var futures = langs.stream()
                .map(lang -> LoggingFuture.runAsync(() -> {
                    try {
                        var sub = analyzer.subAnalyzer(lang).orElse(analyzer);
                        lang.saveAnalyzer(sub, project);
                    } catch (Throwable t) {
                        // Not the end of the world, but worth reporting as it possibly hurts performance later
                        logger.debug("Failed persisting analyzer state for {}: {}", lang.name(), t.toString());
                    }
                }))
                .toArray(CompletableFuture[]::new);

        LoggingFuture.allOf(futures).join();

        if (analyzer instanceof MultiAnalyzer multi) {
            // Materialize template analysis (Angular, etc.) into template analyzers before persisting;
            // host-only TreeSitter saves do not populate FrameworkTemplate snapshot maps.
            multi.snapshotState();
            for (var ta : multi.getTemplateAnalyzers()) {
                if (ta instanceof FrameworkTemplate ft) {
                    try {
                        FrameworkTemplates.saveTemplateAnalyzerState(ft, project, ta.snapshotState());
                    } catch (Throwable t) {
                        logger.debug("Failed persisting template analyzer state for {}: {}", ft.name(), t.toString());
                    }
                }
            }
        }
    }
}
