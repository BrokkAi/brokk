package io.github.jbellis.brokk;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.IWatchService.EventBatch;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.analyzer.DisabledAnalyzer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class AnalyzerWrapper implements AutoCloseable, IWatchService.Listener {
    private final Logger logger = LogManager.getLogger(AnalyzerWrapper.class);

    public static final String ANALYZER_BUSY_MESSAGE =
            "Code Intelligence is still being built. Please wait until completion.";
    public static final String ANALYZER_BUSY_TITLE = "Analyzer Busy";

    @Nullable
    private final AnalyzerListener listener; // can be null if no one is listening

    private final Path root;

    @Nullable
    private final Path gitRepoRoot;

    private final ContextManager.TaskRunner runner;
    private final IProject project;
    private final IWatchService watchService;

    private volatile Future<IAnalyzer> future;
    private volatile @Nullable IAnalyzer currentAnalyzer = null;
    private volatile boolean rebuildInProgress = false;
    private volatile boolean externalRebuildRequested =
            false; // TODO allow requesting either incremental or full rebuild
    private volatile boolean rebuildPending = false;
    private volatile boolean wasReady = false;

    public AnalyzerWrapper(
            IProject project, ContextManager.TaskRunner runner, @Nullable AnalyzerListener listener, IConsoleIO io) {
        this.project = project;
        this.root = project.getRoot();
        gitRepoRoot = project.hasGit() ? project.getRepo().getGitTopLevel() : null;
        this.runner = runner;
        this.listener = listener;
        if (listener == null) {
            this.watchService = new IWatchService() {};
        } else {
            this.watchService = new ProjectWatchService(root, gitRepoRoot, this);
        }

        // build the initial Analyzer
        future = runner.submit("Initializing code intelligence", () -> {
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
                    "Initial analyzer has {} declarations across {} files",
                    metrics.numberOfDeclarations(),
                    metrics.numberOfCodeUnits());

            // configure auto-refresh based on how long the first build took
            if (project.getAnalyzerRefresh() == IProject.AnalyzerRefresh.UNSET) {
                handleFirstBuildRefreshSettings(
                        metrics.numberOfCodeUnits(), durationMs, project.getAnalyzerLanguages());
            }

            return currentAnalyzer;
        });
    }

    @Override
    public void onFilesChanged(EventBatch batch) {
        logger.trace("Events batch: {}", batch);

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
            refresh(() -> {
                final var analyzer = requireNonNull(currentAnalyzer);
                return analyzer.as(IncrementalUpdateProvider.class)
                        .map(IncrementalUpdateProvider::update)
                        .orElse(analyzer);
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
            // update the analyzer if we're configured to do so
            if (project.getAnalyzerRefresh() != IProject.AnalyzerRefresh.AUTO) {
                return;
            }

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
            refresh(() -> {
                final var analyzer = requireNonNull(currentAnalyzer);
                return analyzer.as(IncrementalUpdateProvider.class)
                        .map(incAnalyzer -> incAnalyzer.update(relevantFiles))
                        .orElse(analyzer);
            });
        } else {
            logger.trace(
                    "No tracked files changed for any of the configured analyzer languages; skipping analyzer rebuild");
        }
    }

    @Override
    public void onNoFilesChangedDuringPollInterval() {
        if (externalRebuildRequested && !rebuildInProgress) {
            logger.debug("External rebuild requested");
            refresh(() -> getLanguageHandle().createAnalyzer(project));
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
        // ACHTUNG!
        // Do not call into the listener directly in this method, since if the listener asks for the analyzer
        // object via get() it can cause a deadlock.

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
            runner.submit("Prep Code Intelligence", () -> {
                listener.beforeEachBuild();
                return null;
            });
        }

        logger.debug("Waiting for build details");
        BuildAgent.BuildDetails buildDetails = project.awaitBuildDetails();
        if (buildDetails.equals(BuildAgent.BuildDetails.EMPTY))
            logger.warn("Build details are empty or null. Analyzer functionality may be limited.");

        /* ── 2.  Determine if any cached storage is stale ───────────────────────────────── */
        logger.debug("Scanning for modified project files");
        boolean needsRebuild = externalRebuildRequested; // explicit user request wins
        if (project.getAnalyzerRefresh() != IProject.AnalyzerRefresh.MANUAL) {
            for (Language lang : project.getAnalyzerLanguages()) {
                Path storagePath = lang.getStoragePath(project);
                if (!Files.exists(storagePath)) { // no cache → rebuild
                    needsRebuild = true;
                    continue;
                }
                // Filter tracked files relevant to this language
                List<ProjectFile> tracked = project.getAllFiles().stream()
                        .filter(pf -> lang.getExtensions()
                                .contains(com.google.common.io.Files.getFileExtension(
                                        pf.absPath().toString())))
                        .toList();
                if (isStale(lang, storagePath, tracked)) // cache older than sources
                needsRebuild = true;
            }
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
            // Check if analyzer became ready BEFORE submitting task to avoid race condition
            boolean isNowReady = (analyzer != null);
            logger.debug("Checking analyzer ready transition: wasReady={}, isNowReady={}", wasReady, isNowReady);

            // always refresh workspace in case there was a race and we shut down
            // after saving a new analyzer but before refreshing the workspace
            runner.submit("Refreshing Workspace", () -> {
                if (!wasReady && isNowReady) {
                    logger.debug("Analyzer became ready during loadOrCreateAnalyzer, notifying listeners");
                    listener.onAnalyzerReady();
                } else {
                    logger.debug("No analyzer ready transition detected");
                }
                listener.afterEachBuild(false);
                wasReady = isNowReady;
                return null;
            });
        } else {
            logger.debug("AnalyzerWrapper has no listener - skipping notification");
        }

        /* ── 5.  If we used stale caches, schedule a background rebuild ─────────────── */
        if (needsRebuild
                && project.getAnalyzerRefresh() != IProject.AnalyzerRefresh.MANUAL
                && !externalRebuildRequested) {
            logger.debug("Scheduling background refresh");
            IAnalyzer finalAnalyzer = analyzer;
            runner.submit("Refreshing Code Intelligence", () -> {
                finalAnalyzer
                        .as(IncrementalUpdateProvider.class)
                        .ifPresent(incAnalyzer -> refresh(incAnalyzer::update));
                return null;
            });
        }

        logger.debug("Analyzer load complete!");
        return analyzer;
    }

    private void handleFirstBuildRefreshSettings(int totalDeclarations, long durationMs, Set<Language> languages) {
        var isEmpty = totalDeclarations == 0;
        String langNames = languages.stream().map(Language::name).collect(Collectors.joining("/"));
        String langExtensions = languages.stream()
                .flatMap(l -> l.getExtensions().stream())
                .distinct()
                .collect(Collectors.joining(", "));

        if (listener == null) { // Should not happen in normal flow, but good for safety
            logger.warn(
                    "AnalyzerListener is null during handleFirstBuildRefreshSettings, cannot call afterFirstBuild.");
            // Set a default refresh policy if listener is unexpectedly null
            if (isEmpty || durationMs > 3 * 6000) {
                project.setAnalyzerRefresh(IProject.AnalyzerRefresh.MANUAL);
            } else if (durationMs > 5000) {
                project.setAnalyzerRefresh(IProject.AnalyzerRefresh.ON_RESTART);
            } else {
                project.setAnalyzerRefresh(IProject.AnalyzerRefresh.AUTO);
            }
            return;
        }

        if (isEmpty) {
            logger.info("Empty {} analyzer", langNames);
            listener.afterFirstBuild("");
        } else if (durationMs > 3 * 6000) {
            project.setAnalyzerRefresh(IProject.AnalyzerRefresh.MANUAL);
            var msg =
                    """
                            Code Intelligence for %s found %d declarations in %,d ms.
                            Since this was slow, code intelligence will only refresh when explicitly requested via the Context menu.
                            You can change this in the Settings -> Project dialog.
                            """
                            .stripIndent()
                            .formatted(langNames, totalDeclarations, durationMs);
            listener.afterFirstBuild(msg);
            logger.info(msg);
        } else if (durationMs > 5000) {
            project.setAnalyzerRefresh(IProject.AnalyzerRefresh.ON_RESTART);
            var msg =
                    """
                            Code Intelligence for %s found %d declarations in %,d ms.
                            Since this was slow, code intelligence will only refresh on restart, or when explicitly requested via the Context menu.
                            You can change this in the Settings -> Project dialog.
                            """
                            .stripIndent()
                            .formatted(langNames, totalDeclarations, durationMs);
            listener.afterFirstBuild(msg);
            logger.info(msg);
        } else {
            project.setAnalyzerRefresh(IProject.AnalyzerRefresh.AUTO);
            var msg =
                    """
                            Code Intelligence for %s found %d declarations in %,d ms.
                            If this is fewer than expected, it's probably because Brokk only looks for %s files.
                            If this is not a useful subset of your project, you can change it in the Settings -> Project
                            dialog, or disable Code Intelligence by setting the language(s) to NONE.
                            """
                            .stripIndent()
                            .formatted(langNames, totalDeclarations, durationMs, langExtensions, Languages.NONE.name());
            listener.afterFirstBuild(msg);
            logger.info(msg);
        }
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
     * Refreshes the analyzer by scheduling a job on the analyzerExecutor. Avoids concurrent rebuilds by setting a flag,
     * but if a change is detected during the rebuild, a new rebuild will be scheduled immediately afterwards. This
     * refreshes the entire analyzer setup (single or multi). The supplier controls whether a new analyzer is created,
     * or an optimistic or pessimistic incremental rebuild.
     */
    private synchronized void refresh(Supplier<IAnalyzer> supplier) {
        if (rebuildInProgress) {
            rebuildPending = true;
            return;
        }

        rebuildInProgress = true;
        logger.trace("Refreshing analyzer (full)");
        future = runner.submit("Refreshing code intelligence", () -> {
            try {
                if (listener != null) {
                    listener.beforeEachBuild();
                }
                // This will reconstruct the analyzer (potentially MultiAnalyzer) based on current settings.
                currentAnalyzer = supplier.get();
                logger.debug("Analyzer refresh completed.");
                if (listener != null) {
                    // Check readiness after analyzer assignment to avoid race condition
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
            } finally {
                synchronized (AnalyzerWrapper.this) {
                    rebuildInProgress = false;
                    if (rebuildPending) {
                        rebuildPending = false;
                        logger.trace("Refreshing immediately after pending request");
                        refresh(() -> getLanguageHandle().createAnalyzer(project));
                    } else {
                        externalRebuildRequested = false;
                    }
                }
            }
        });
    }

    /** Get the analyzer, showing a spinner UI while waiting if requested. */
    public IAnalyzer get() throws InterruptedException {
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

        // Otherwise, this must be the very first build (or a failed one).
        if (listener != null) {
            listener.onBlocked();
        }
        try {
            // Block until the future analyzer finishes building
            return future.get();
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to create analyzer", e);
        }
    }

    /** @return null if analyzer is not ready yet */
    @Nullable
    public IAnalyzer getNonBlocking() {
        if (currentAnalyzer != null) {
            return currentAnalyzer;
        }

        try {
            // Try to get with zero timeout - returns null if not done
            return future.get(0, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Not done yet
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while checking analyzer", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to create analyzer", e);
        }
    }

    /** @return true if the analyzer is ready for use, false if still building */
    public boolean isReady() {
        return getNonBlocking() != null;
    }

    public void requestRebuild() {
        externalRebuildRequested = true;
    }

    public void updateFiles(Set<ProjectFile> changedFiles) {
        try {
            final var analyzer = future.get();
            currentAnalyzer = analyzer.as(IncrementalUpdateProvider.class)
                    .map(incAnalyzer -> incAnalyzer.update(changedFiles))
                    .orElse(analyzer);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /** Pause the file watching service. */
    public synchronized void pause() {
        watchService.pause();
    }

    /** Resume the file watching service. */
    public synchronized void resume() {
        watchService.resume();
    }

    @Override
    public void close() {
        watchService.close();
    }
}
