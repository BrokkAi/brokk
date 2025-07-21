package io.github.jbellis.brokk;

import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.KeyboardFocusManager;
import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public class AnalyzerWrapper implements AutoCloseable {
    private final Logger logger = LogManager.getLogger(AnalyzerWrapper.class);

    public static final String ANALYZER_BUSY_MESSAGE = "Code Intelligence is still being built. Please wait until completion.";
    public static final String ANALYZER_BUSY_TITLE = "Analyzer Busy";

    private static final long DEBOUNCE_DELAY_MS = 500;
    private static final long POLL_TIMEOUT_FOCUSED_MS = 100;
    private static final long POLL_TIMEOUT_UNFOCUSED_MS = 1000;

    @Nullable
    private final AnalyzerListener listener; // can be null if no one is listening
    private final Path root;
    @Nullable
    private final Path gitRepoRoot;
    private final ContextManager.TaskRunner runner;
    private final IProject project;

    private volatile boolean running = true;
    private volatile boolean paused = false;

    private volatile Future<IAnalyzer> future;
    private volatile @Nullable IAnalyzer currentAnalyzer = null;
    private volatile boolean rebuildInProgress = false;
    private volatile boolean externalRebuildRequested = false; // TODO allow requesting either incremental or full rebuild
    private volatile boolean rebuildPending = false;

    public AnalyzerWrapper(IProject project, ContextManager.TaskRunner runner, @Nullable AnalyzerListener listener) {
        this.project = project;
        this.root = project.getRoot();
        gitRepoRoot = project.hasGit() ? project.getRepo().getGitTopLevel() : null;
        this.runner = runner;
        this.listener = listener;

        // build the initial Analyzer
        future = runner.submit("Initializing code intelligence", () -> {
            // Loading the analyzer with `Optional.empty` tells the analyzer to determine changed files on its own
            long start =  System.currentTimeMillis();
            currentAnalyzer = loadOrCreateAnalyzer();
            long durationMs = System.currentTimeMillis() - start;

            // Watcher assumes that currentAnalyzer has been initialized so start it after we have one
            startWatcher();
            
            // debug logging
            var codeUnits = currentAnalyzer.getAllDeclarations();
            var codeFiles = codeUnits.stream().map(CodeUnit::source).distinct().count();
            logger.debug("Initial analyzer has {} declarations across {} files", codeUnits.size(), codeFiles);

            // configure auto-refresh based on how long the first build took
            if (project.getAnalyzerRefresh() == IProject.CpgRefresh.UNSET) {
                handleFirstBuildRefreshSettings(codeUnits.size(), durationMs, project.getAnalyzerLanguages());
            }

            return currentAnalyzer;
        });
    }

    private void beginWatching(Path root) {
        try {
            // Wait for the initial analyzer to build
            future.get();
        } catch (InterruptedException | ExecutionException e) {
            // everything expects that get() will work, this is fatal
            logger.debug("Error building initial analyzer", e);
            throw new RuntimeException(e);
        }

        logger.debug("Signaling repo + tracked files change to catch any events that we missed during initial analyzer build");
        if (listener != null) {
            listener.onRepoChange();
            listener.onTrackedFileChange();
        }

        logger.debug("Setting up WatchService for {}", root);
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            // Recursively register all directories under project root except .brokk
            registerAllDirectories(root, watchService);

            // If the actual .git directory's parent (gitRepoRoot) is different from the project root
            // (common in worktrees), explicitly watch the .git directory within gitRepoRoot.
            // The registerAllDirectories method's .brokk exclusion (relative to this.root)
            // will not interfere with watching contents of .git.
            if (gitRepoRoot != null && !gitRepoRoot.equals(this.root)) {
                Path actualGitMetaDir = gitRepoRoot.resolve(".git");
                assert Files.isDirectory(actualGitMetaDir);
                logger.debug("Additionally watching git metadata directory for changes: {}", actualGitMetaDir);
                registerAllDirectories(actualGitMetaDir, watchService);
            }

            // Watch for events, debounce them, and handle them
            while (running) {
                // Wait if paused
                while (paused) {
                    Thread.onSpinWait();
                }

                // Choose a short or long poll depending on focus
                long pollTimeout = isApplicationFocused() ? POLL_TIMEOUT_FOCUSED_MS : POLL_TIMEOUT_UNFOCUSED_MS;
                WatchKey key = watchService.poll(pollTimeout, TimeUnit.MILLISECONDS);

                // If no event arrived within the poll window, check for external rebuild requests
                if (key == null) {
                    if (externalRebuildRequested && !rebuildInProgress) {
                        logger.debug("External rebuild requested");
                        refresh(() -> getLanguageHandle().createAnalyzer(project));
                    }
                    continue;
                }

                // We got an event, collect it and any others within the debounce window
                var batch = new EventBatch();
                collectEventsFromKey(key, watchService, batch);

                long deadline = System.currentTimeMillis() + DEBOUNCE_DELAY_MS;
                while (true) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    WatchKey nextKey = watchService.poll(remaining, TimeUnit.MILLISECONDS);
                    if (nextKey == null) break;
                    collectEventsFromKey(nextKey, watchService, batch);
                }

                // Process the batch
                handleBatch(batch);
            }
        } catch (IOException e) {
            logger.error("Error setting up watch service", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("FileWatchService thread interrupted; shutting down");
        }
    }

    /** mutable since we will collect events until they stop arriving */
    static class EventBatch {
        boolean isOverflowed;
        Set<ProjectFile> files = new HashSet<>();
    }

    /**
     * Check if changes in this batch of events require a .git refresh and/or analyzer rebuild.
     */
    private void handleBatch(EventBatch batch) {
        logger.trace("Events batch: {}", batch);

        // 1) Possibly refresh Git
        if (gitRepoRoot != null) {
            Path gitMetaDir = gitRepoRoot.resolve(".git"); // gitRepoRoot is checked non-null
            if (batch.isOverflowed || batch.files.stream().anyMatch(pf -> pf.getRelPath().startsWith(gitMetaDir))) {
                logger.debug("Changes in git metadata directory ({}) detected", requireNonNull(gitRepoRoot).resolve(".git"));
                if (listener != null) {
                    listener.onRepoChange();
                }
            }
        }
        // 2) If overflowed, assume something changed
        if (batch.isOverflowed) {
            if (listener != null) listener.onTrackedFileChange();
            refresh(() -> currentAnalyzer.update());
        }

        // 3) We have an exact files list to check
        var trackedFiles = project.getRepo().getTrackedFiles();
        var projectLanguages = project.getAnalyzerLanguages();
        var relevantFiles = batch.files.stream()
                .filter(pf -> {
                    if (!trackedFiles.contains(pf)) {
                        return false;
                    }
                    return projectLanguages.stream().anyMatch(L -> L.getExtensions().contains(pf.extension()));
                })
                .collect(Collectors.toSet());

        if (!relevantFiles.isEmpty()) {
            // call listener (refreshes git panel)
            logger.debug("Changes in tracked files detected");
            if (listener != null) listener.onTrackedFileChange();

            // update the analyzer if we're configured to do so
            if (project.getAnalyzerRefresh() != IProject.CpgRefresh.AUTO) {
                return;
            }

            logger.debug("Rebuilding analyzer due to changes in tracked files relevant to configured languages: {}",
                         relevantFiles.stream()
                                 .filter(pf -> {
                                     Language lang = Language.fromExtension(pf.extension());
                                     return projectLanguages.contains(lang);
                                 })
                                 .distinct()
                                 .map(ProjectFile::toString)
                                 .collect(Collectors.joining(", "))
            );
            refresh(() -> currentAnalyzer.update(relevantFiles));
        } else {
            logger.trace("No tracked files changed (overall); skipping analyzer rebuild");
        }
    }

    /**
     * Builds or loads an {@link IAnalyzer} for the project.
     *
     * <p>All “loop over every language” work is now delegated to a single
     * {@link Language} handle:
     * <ul>
     *   <li>If the project has exactly one concrete language, that language is used
     *       directly.</li>
     *   <li>If the project has several languages, a new
     *       {@link Language.MultiLanguage} wrapper is created to fan‑out the work
     *       behind the scenes.</li>
     * </ul>
     *
     * <p>The <strong>cache / staleness</strong> checks that used to live in the
     * helper method <code>loadSingleCachedAnalyzerForLanguage</code> are now
     * performed here <em>before</em> we decide whether to call
     * <code>langHandle.loadAnalyzer()</code> (use cache) or
     * <code>langHandle.createAnalyzer()</code> (full rebuild).</p>
     */
    private IAnalyzer loadOrCreateAnalyzer() {
        // ACHTUNG!
        // Do not call into the listener directly in this method, since if the listener asks for the analyzer
        // object via get() it can cause a deadlock.
        
        /* ── 0.  Decide which languages we are dealing with ─────────────────────────── */
        Language langHandle = getLanguageHandle();
        logger.debug("Loading/creating analyzer for languages: {}", langHandle);
        if (langHandle == Language.NONE) {
            return new DisabledAnalyzer();
        }

        /* ── 1.  Pre‑flight notifications & build details ───────────────────────────── */
        if (listener != null) {
            runner.submit("Prep Code Intelligence", () -> {
                listener.beforeEachBuild();
                return null;
            });
        }

        BuildAgent.BuildDetails buildDetails = project.awaitBuildDetails();
        if (buildDetails.equals(BuildAgent.BuildDetails.EMPTY))
            logger.warn("Build details are empty or null. Analyzer functionality may be limited.");

        /* ── 2.  Determine if any cached CPG is stale ───────────────────────────────── */
        boolean needsRebuild = externalRebuildRequested;            // explicit user request wins
        if (project.getAnalyzerRefresh() != IProject.CpgRefresh.MANUAL) {
            for (Language lang : project.getAnalyzerLanguages()) {
                if (!lang.isCpg()) continue;                       // non‑CPG langs are rebuilt ad‑hoc
                Path cpgPath = lang.getCpgPath(project);
                if (!Files.exists(cpgPath)) {                      // no cache → rebuild
                    needsRebuild = true;
                    continue;
                }
                // Filter tracked files relevant to this language
                List<ProjectFile> tracked = project.getAllFiles().stream()
                        .filter(pf -> lang.getExtensions()
                                .contains(com.google.common.io.Files.getFileExtension(pf.absPath().toString())))
                        .toList();
                if (isStale(lang, cpgPath, tracked))               // cache older than sources
                    needsRebuild = true;
            }
        }

        /* ── 3.  Load or build the analyzer via the Language handle ─────────────────── */
        IAnalyzer analyzer;
        try {
            analyzer = langHandle.loadAnalyzer(project);
        } catch (Throwable th) {
            // cache missing or corrupt, rebuild
            logger.warn(th);
            analyzer = langHandle.createAnalyzer(project);
            needsRebuild = false;
        }

        /* ── 4.  Notify listeners ───────────────────────────────────────────────────── */
        if (listener != null) {
            runner.submit("Refreshing Workspace", () -> {
                listener.afterEachBuild(true, externalRebuildRequested);
                return null;
            });
        }

        /* ── 5.  If we used stale caches, schedule a background rebuild ─────────────── */
        if (needsRebuild
            && project.getAnalyzerRefresh() != IProject.CpgRefresh.MANUAL
            && !externalRebuildRequested) 
        {
            logger.debug("Scheduling background refresh");
            IAnalyzer finalAnalyzer = analyzer;
            runner.submit("Refreshing Code Intelligence", () -> {
                refresh(finalAnalyzer::update);
                return null;
            });
        }

        return analyzer;
    }

    private void handleFirstBuildRefreshSettings(int totalDeclarations, long durationMs, Set<Language> languages) {
        var isEmpty = totalDeclarations > 0;
        String langNames = languages.stream().map(Language::name).collect(Collectors.joining("/"));
        String langExtensions = languages.stream()
                .flatMap(l -> l.getExtensions().stream())
                .distinct()
                .collect(Collectors.joining(", "));

        if (listener == null) { // Should not happen in normal flow, but good for safety
            logger.warn("AnalyzerListener is null during handleFirstBuildRefreshSettings, cannot call afterFirstBuild.");
            // Set a default refresh policy if listener is unexpectedly null
            if (isEmpty || durationMs > 3 * 6000) {
                project.setAnalyzerRefresh(IProject.CpgRefresh.MANUAL);
            } else if (durationMs > 5000) {
                project.setAnalyzerRefresh(IProject.CpgRefresh.ON_RESTART);
            } else {
                project.setAnalyzerRefresh(IProject.CpgRefresh.AUTO);
            }
            return;
        }

        if (isEmpty) {
            logger.info("Empty {} analyzer", langNames);
            listener.afterFirstBuild("");
        } else if (durationMs > 3 * 6000) {
            project.setAnalyzerRefresh(IProject.CpgRefresh.MANUAL);
            var msg = """
                    Code Intelligence for %s found %d declarations in %,d ms.
                    Since this was slow, code intelligence will only refresh when explicitly requested via the Context menu.
                    You can change this in the Settings -> Project dialog.
                    """.stripIndent().formatted(langNames, totalDeclarations, durationMs);
            listener.afterFirstBuild(msg);
            logger.info(msg);
        } else if (durationMs > 5000) {
            project.setAnalyzerRefresh(IProject.CpgRefresh.ON_RESTART);
            var msg = """
                    Code Intelligence for %s found %d declarations in %,d ms.
                    Since this was slow, code intelligence will only refresh on restart, or when explicitly requested via the Context menu.
                    You can change this in the Settings -> Project dialog.
                    """.stripIndent().formatted(langNames, totalDeclarations, durationMs);
            listener.afterFirstBuild(msg);
            logger.info(msg);
        } else {
            project.setAnalyzerRefresh(IProject.CpgRefresh.AUTO);
            var msg = """
                    Code Intelligence for %s found %d declarations in %,d ms.
                    If this is fewer than expected, it's probably because Brokk only looks for %s files.
                    If this is not a useful subset of your project, you can change it in the Settings -> Project
                    dialog, or disable Code Intelligence by setting the language(s) to NONE.
                    """.stripIndent().formatted(langNames, totalDeclarations, durationMs, langExtensions, Language.NONE.name());
            listener.afterFirstBuild(msg);
            logger.info(msg);
        }
    }


    public boolean isCpg() {
        return project.getAnalyzerLanguages().stream().anyMatch(Language::isCpg);
    }

    /**
     * Convenience overload that infers the language set from {@link #project}.
     */
    private Language getLanguageHandle() {
        var projectLangs = project.getAnalyzerLanguages()
                                  .stream()
                                  .filter(l -> l != Language.NONE)
                                  .collect(Collectors.toUnmodifiableSet());
        if (projectLangs.isEmpty()) {
            return Language.NONE;
        }
        return (projectLangs.size() == 1)
               ? projectLangs.iterator().next()
               : new Language.MultiLanguage(projectLangs);
    }
    
    /**
     * Determine whether the cached analyzer for the given language is stale relative to
     * its tracked source files and any user-requested rebuilds.
     *
     * The caller guarantees that {@code analyzerPath} is non-null and exists.
     */
    private boolean isStale(Language lang, Path analyzerPath, List<ProjectFile> trackedFiles)
    {
        // An explicit external rebuild request always wins.
        if (externalRebuildRequested) {
            return true;
        }

        long cpgMTime;
        try {
            cpgMTime = Files.getLastModifiedTime(analyzerPath).toMillis();
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
                if (fileMTime > cpgMTime) {
                    logger.debug("Tracked file {} for language {} is newer than its CPG {} ({} > {})",
                                 path, lang.name(), analyzerPath, fileMTime, cpgMTime);
                    return true;
                }
            } catch (IOException e) {
                logger.debug("Error reading timestamp for tracked file {} (language {}): {}", rf.absPath(), lang.name(), e.getMessage());
                // If we cannot evaluate the timestamp, be conservative.
                return true;
            }
        }

        return false;
    }

    /**
     * Refreshes the analyzer by scheduling a job on the analyzerExecutor.
     * Avoids concurrent rebuilds by setting a flag, but if a change is detected during
     * the rebuild, a new rebuild will be scheduled immediately afterwards.
     * This refreshes the entire analyzer setup (single or multi). The supplier
     * controls whether a new analyzer is created, or an optimistic or pessimistic incremental rebuild.
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
                // This will reconstruct the analyzer (potentially MultiAnalyzer) based on current settings.
                currentAnalyzer = supplier.get();
                logger.debug("Analyzer refresh completed.");
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

    /**
     * Get the analyzer, showing a spinner UI while waiting if requested.
     */
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

    /**
     * @return null if analyzer is not ready yet
     */
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

    /**
     * @return true if the analyzer is ready for use, false if still building
     */
    public boolean isReady() {
        return getNonBlocking() != null;
    }

    public void requestRebuild() {
        externalRebuildRequested = true;
    }

    /**
     * Checks if any window in the application currently has focus
     *
     * @return true if any application window has focus, false otherwise
     */
    private boolean isApplicationFocused() {
        var focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        return focusedWindow != null;
    }

    private void startWatcher() {
        Thread watcherThread = new Thread(() -> beginWatching(root), "DirectoryWatcher@" + Long.toHexString(Thread.currentThread().threadId()));
        watcherThread.start();
    }

    private void collectEventsFromKey(WatchKey key, WatchService watchService, EventBatch batch) {
        Path watchPath = (Path) key.watchable();

        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                batch.isOverflowed = true;
                continue;
            }

            // Guard: context might be null (OVERFLOW) or not a Path
            if (!(event.context() instanceof Path ctx)) {
                logger.warn("Event is not overflow but has no path: {}", event);
                continue;
            }

            // convert to ProjectFile
            Path eventPath = watchPath.resolve(ctx);
            batch.files.add(new ProjectFile(root, root.relativize(eventPath)));

            // If it's a directory creation, register it so we can watch its children
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(eventPath)) {
                try {
                    registerAllDirectories(eventPath, watchService);
                } catch (IOException ex) {
                    logger.warn("Failed to register new directory for watching: {}", eventPath, ex);
                }
            }
        }

        // If the key is no longer valid, we can't watch this path anymore
        if (!key.reset()) {
            logger.warn("Watch key no longer valid: {}", key.watchable());
        }
    }

    /**
     * @param start can be either the root project directory, or a newly created directory we want to add to the watch
     */
    private void registerAllDirectories(Path start, WatchService watchService) throws IOException {
        if (!Files.isDirectory(start)) return;

        var brokkPrivate = root.resolve(".brokk");
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (var walker = Files.walk(start)) {
                walker.filter(Files::isDirectory)
                        .filter(dir -> !dir.startsWith(brokkPrivate))
                        .forEach(dir -> {
                            try {
                                dir.register(watchService,
                                        StandardWatchEventKinds.ENTRY_CREATE,
                                        StandardWatchEventKinds.ENTRY_DELETE,
                                        StandardWatchEventKinds.ENTRY_MODIFY);
                            } catch (IOException e) {
                                logger.warn("Failed to register directory for watching: {}", dir, e);
                            }
                        });
                // Success: If the walk completes without exception, break the retry loop.
                return;
            } catch (IOException | java.io.UncheckedIOException e) {
                // Determine the root cause, handling the case where the UncheckedIOException wraps another exception.
                Throwable cause = (e instanceof java.io.UncheckedIOException uioe) ? uioe.getCause() : e;

                // Retry only if it's a NoSuchFileException and we have attempts left.
                if (cause instanceof java.nio.file.NoSuchFileException && attempt < 3) {
                    logger.warn("Attempt {} failed to walk directory {} due to NoSuchFileException. Retrying in 10ms...", attempt, start, cause);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(e);
                    }
                }
            }
        } // End of retry loop
        logger.debug("Failed to (completely) register directory `{}` for watching", start);
    }

    public void updateFiles(Set<ProjectFile> changedFiles) {
        try {
            currentAnalyzer = future.get().update(changedFiles);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Pause the file watching service.
     */
    public synchronized void pause() {
        logger.debug("Pausing file watcher");
        paused = true;
    }

    /**
     * Resume the file watching service.
     */
    public synchronized void resume() {
        logger.debug("Resuming file watcher");
        paused = false;
    }

    @Override
    public void close() {
        running = false;
        resume(); // Ensure any waiting thread is woken up to exit
    }
    
    private record CachedAnalyzerResult(@Nullable IAnalyzer analyzer, boolean needsRebuild) {
    }
}
