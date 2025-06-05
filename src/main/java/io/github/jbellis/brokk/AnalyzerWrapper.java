package io.github.jbellis.brokk;

import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.KeyboardFocusManager; // Keep specific AWT imports if used elsewhere for UI
import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class AnalyzerWrapper implements AutoCloseable {
    private final Logger logger = LogManager.getLogger(AnalyzerWrapper.class);

    private static final long DEBOUNCE_DELAY_MS = 500;
    private static final long POLL_TIMEOUT_FOCUSED_MS = 100;
    private static final long POLL_TIMEOUT_UNFOCUSED_MS = 1000;

    private final AnalyzerListener listener; // can be null if no one is listening
    private final Path root;
    private final ContextManager.TaskRunner runner;
    private final IProject project;

    private volatile boolean running = true;
    private volatile boolean paused = false;

    private volatile Future<IAnalyzer> future;
    private volatile IAnalyzer currentAnalyzer = null;
    private volatile boolean rebuildInProgress = false;
    private volatile boolean externalRebuildRequested = false;
    private volatile boolean rebuildPending = false;

    public AnalyzerWrapper(IProject project, ContextManager.TaskRunner runner, AnalyzerListener listener) {
        this.project = project;
        this.root = project.getRoot();
        this.runner = runner;
        this.listener = listener;

        // build the initial Analyzer
        future = runner.submit("Initializing code intelligence", () -> {
            var an = loadOrCreateAnalyzer();
            var codeUnits = an.getAllDeclarations();
            var codeFiles = codeUnits.stream().map(CodeUnit::source).distinct().count();
            logger.debug("Initial analyzer has {} declarations across {} files", codeUnits.size(), codeFiles);
            return an;
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
        listener.onRepoChange();
        listener.onTrackedFileChange();

        logger.debug("Setting up WatchService for {}", root);
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            // Recursively register all directories except .brokk
            registerAllDirectories(root, watchService);

            // Watche for events, debounces them, and handles them
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
                        rebuild();
                    }
                    continue;
                }

                // We got an event, collect it and any others within the debounce window
                var batch = new HashSet<FileChangeEvent>();
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
        }
        catch (IOException e) {
            logger.error("Error setting up watch service", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("FileWatchService thread interrupted; shutting down");
        }
    }

    /**
     * Check if changes in this batch of events require a .git refresh and/or analyzer rebuild.
     */
    private void handleBatch(Set<FileChangeEvent> batch) {
        logger.trace("Events batch: {}", batch);

        // 1) Possibly refresh Git
        boolean needsGitRefresh = batch.stream().anyMatch(event -> {
            Path gitDir = root.resolve(".git");
            return event.path.startsWith(gitDir)
                    && (event.type == EventType.CREATE
                    || event.type == EventType.DELETE
                    || event.type == EventType.MODIFY);
        });
        if (needsGitRefresh) {
            logger.debug("Changes in .git directory detected");
            if (listener != null) {
                listener.onRepoChange();
                listener.onTrackedFileChange(); // not 100% sure this is necessary
            }
        }

        // 2) Check if any *tracked* files changed
        Set<Path> trackedPaths = project.getRepo().getTrackedFiles().stream()
                .map(ProjectFile::absPath)
                .collect(Collectors.toSet());
        boolean trackedPathsChanged = batch.stream()
                .anyMatch(event -> trackedPaths.contains(event.path));

        if (trackedPathsChanged) {
            // call listener (refreshes git panel)
            logger.debug("Changes in tracked files detected");
            if (listener != null) {
                listener.onTrackedFileChange();
            }

            // update the analyzer if we're configured to do so
            // Only rebuild if the changed files are of a type relevant to the project's configured languages
            Set<Language> projectLanguages = project.getAnalyzerLanguages();
            boolean relevantFileChanged = batch.stream().anyMatch(event -> {
                if (!trackedPaths.contains(event.path)) return false;
                String extension = com.google.common.io.Files.getFileExtension(event.path.toString());
                Language langOfFile = Language.fromExtension(extension);
                return langOfFile != Language.NONE && projectLanguages.contains(langOfFile);
            });

            if (relevantFileChanged) {
                if (project.getAnalyzerRefresh() == IProject.CpgRefresh.AUTO) {
                    logger.debug("Rebuilding analyzer due to changes in tracked files relevant to configured languages: {}",
                                 batch.stream()
                                         .filter(e -> trackedPaths.contains(e.path))
                                         .filter(e -> {
                                             String ext = com.google.common.io.Files.getFileExtension(e.path.toString());
                                             Language lang = Language.fromExtension(ext);
                                             return projectLanguages.contains(lang);
                                         })
                                         .distinct()
                                         .map(e -> e.path.toString())
                                         .collect(Collectors.joining(", "))
                    );
                    rebuild();
                }
            } else {
                logger.trace("No tracked files relevant to configured languages changed; skipping analyzer rebuild");
            }
        } else {
            logger.trace("No tracked files changed (overall); skipping analyzer rebuild");
        }
    }

    /**
     * Synchronously load or create an Analyzer:
     *   1) If the cpg file is up to date, reuse it;
     *   2) Otherwise, rebuild a fresh Analyzer.
     * This method is called for the initial load and for full rebuilds.
     */
    private IAnalyzer loadOrCreateAnalyzer() {
        return loadOrCreateAnalyzerInternal(true);
    }

    /**
     * Internal version of loadOrCreateAnalyzer.
     * @param isInitialLoad true if this is the very first load (triggers UNSET logic and watcher start).
     */
    private IAnalyzer loadOrCreateAnalyzerInternal(boolean isInitialLoad) {
        Set<Language> projectLangs = project.getAnalyzerLanguages();
        logger.debug("Loading/creating analyzer for languages: {}", projectLangs.stream().map(Language::name).collect(Collectors.joining(", ")));

        if (projectLangs.isEmpty() || (projectLangs.size() == 1 && projectLangs.contains(Language.NONE))) {
            currentAnalyzer = new DisabledAnalyzer();
            if (isInitialLoad) startWatcher(); // Watcher for git, etc.
            return currentAnalyzer;
        }

        BuildAgent.BuildDetails fetchedBuildDetails = project.awaitBuildDetails();
        if (fetchedBuildDetails.equals(BuildAgent.BuildDetails.EMPTY)) {
            logger.warn("Build details are empty or null. Analyzer functionality may be limited.");
        }

        IAnalyzer resultAnalyzer;
        long totalCreationTimeMs;
        int totalDeclarations = 0;
        boolean allEmpty = true;

        if (projectLangs.size() == 1) {
            Language lang = projectLangs.iterator().next();
            assert lang != Language.NONE;

            Path cpgPath = lang.isCpg() ? lang.getCpgPath(project) : null;
            long startTime = System.currentTimeMillis();

            if (isInitialLoad && project.getAnalyzerRefresh() == IProject.CpgRefresh.UNSET) {
                logger.debug("First startup for language {}: timing Analyzer creation", lang.name());
                resultAnalyzer = lang.createAnalyzer(project);
            } else {
                resultAnalyzer = loadSingleCachedAnalyzerForLanguage(lang, cpgPath);
                if (resultAnalyzer == null) {
                    logger.debug("Creating {} analyzer for {}", lang.name(), project.getRoot());
                    resultAnalyzer = lang.createAnalyzer(project);
                }
            }
            totalCreationTimeMs = System.currentTimeMillis() - startTime;
            if (!resultAnalyzer.isEmpty()) {
                allEmpty = false;
                try { totalDeclarations = resultAnalyzer.getAllDeclarations().size(); }
                catch (UnsupportedOperationException e) { /* some analyzers might not support it */ }
            }
        } else { // Multi-language
            java.util.Map<Language, IAnalyzer> delegateAnalyzers = new java.util.HashMap<>();
            long longestLangCreationTimeMs = 0;

            for (Language lang : projectLangs) {
                if (lang == Language.NONE) continue;
                Path cpgPath = lang.isCpg() ? lang.getCpgPath(project) : null;
                IAnalyzer delegate;
                long langStartTime = System.currentTimeMillis();

                if (isInitialLoad && project.getAnalyzerRefresh() == IProject.CpgRefresh.UNSET) {
                     delegate = lang.createAnalyzer(project);
                } else {
                    delegate = loadSingleCachedAnalyzerForLanguage(lang, cpgPath);
                    if (delegate == null) {
                        logger.debug("Creating {} analyzer for {}", lang.name(), project.getRoot());
                        delegate = lang.createAnalyzer(project);
                    }
                }
                long langCreationTime = System.currentTimeMillis() - langStartTime;
                longestLangCreationTimeMs = Math.max(longestLangCreationTimeMs, langCreationTime);
                delegateAnalyzers.put(lang, delegate);

                if (!delegate.isEmpty()) {
                    allEmpty = false;
                    try { totalDeclarations += delegate.getAllDeclarations().size(); }
                    catch (UnsupportedOperationException e) { /* ignore */ }
                }
            }
            resultAnalyzer = new MultiAnalyzer(delegateAnalyzers);
            totalCreationTimeMs = longestLangCreationTimeMs; // Use longest for multi-analyzer setup time heuristic
        }
        currentAnalyzer = resultAnalyzer;
        logger.debug("Analyzer (re)build completed for languages: {}", projectLangs.stream().map(Language::name).collect(Collectors.joining(", ")));

        // Notify listener after each build, once currentAnalyzer is set
        if (listener != null) {
            listener.afterEachBuild();
        }

        if (isInitialLoad && project.getAnalyzerRefresh() == IProject.CpgRefresh.UNSET) {
            handleFirstBuildRefreshSettings(totalDeclarations, totalCreationTimeMs, allEmpty, projectLangs);
            startWatcher();
        } else if (isInitialLoad) { // Not UNSET, but still initial load
            startWatcher();
        }
        return resultAnalyzer;
    }

    private void handleFirstBuildRefreshSettings(int totalDeclarations, long durationMs, boolean isEmpty, Set<Language> languages) {
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
        if (currentAnalyzer == null) return false;
        return currentAnalyzer.isCpg();
    }

    /** Load a cached analyzer for a single language if it is up to date; otherwise, or on any loading error, return null. */
    private IAnalyzer loadSingleCachedAnalyzerForLanguage(Language lang, Path analyzerPath) {
        if (analyzerPath == null || !Files.exists(analyzerPath)) {
            return null;
        }

        // In MANUAL mode, always use cached data if it exists
        if (project.getAnalyzerRefresh() == IProject.CpgRefresh.MANUAL) {
            logger.debug("MANUAL refresh mode for {} - using cached analyzer from {}", lang.name(), analyzerPath);
            try {
                return lang.loadAnalyzer(project);
            } catch (Throwable th) {
                logger.info("Error loading {} analyzer from {}: {}", lang.name(), analyzerPath, th.getMessage());
                return null;
            }
        }

        var trackedFiles = project.getAllFiles().stream() // Filter for files relevant to this language
            .filter(pf -> {
                String ext = com.google.common.io.Files.getFileExtension(pf.absPath().toString());
                return lang.getExtensions().contains(ext);
            })
            .toList();

        if (trackedFiles.isEmpty() && lang.isCpg()) { // No files for this CPG language, cache might be irrelevant or stale
             logger.debug("No tracked files for language {}, considering cache {} stale.", lang.name(), analyzerPath);
             return null;
        }

        long cpgMTime;
        try {
            cpgMTime = Files.getLastModifiedTime(analyzerPath).toMillis();
        } catch (IOException e) {
            logger.warn("Error reading analyzer file timestamp for {}: {}", analyzerPath, e.getMessage());
            return null; // Cannot determine if cache is fresh
        }

        for (ProjectFile rf : trackedFiles) {
            try {
                if (!Files.exists(rf.absPath())) continue; // File might have been deleted
                long fileMTime = Files.getLastModifiedTime(rf.absPath()).toMillis();
                if (fileMTime > cpgMTime) {
                    logger.debug("Tracked file {} for language {} is newer than its CPG {} ({} > {})",
                                 rf.absPath(), lang.name(), analyzerPath, fileMTime, cpgMTime);
                    return null; // Cache is stale
                }
            } catch (IOException e) {
                logger.debug("Error reading timestamp for tracked file {} (language {}): {}", rf.absPath(), lang.name(), e.getMessage());
                // If we can't check a file, assume cache might be stale to be safe
                return null;
            }
        }

        // Saved analyzer is up to date for this language
        try {
            logger.debug("Using up-to-date cached analyzer for {} from {}", lang.name(), analyzerPath);
            return lang.loadAnalyzer(project);
        } catch (Throwable th) {
            logger.warn("Error loading cached {} analyzer from {}; falling back to full rebuild for this language: {}", lang.name(), analyzerPath, th.getMessage());
            return null;
        }
    }

    /**
     * Force a fresh rebuild of the analyzer by scheduling a job on the analyzerExecutor.
     * Avoids concurrent rebuilds by setting a flag, but if a change is detected during
     * the rebuild, a new rebuild will be scheduled immediately afterwards.
     * This rebuilds the entire analyzer setup (single or multi) based on current project languages.
     */
    private synchronized void rebuild() {
        if (rebuildInProgress) {
            rebuildPending = true;
            return;
        }

        rebuildInProgress = true;
        logger.trace("Rebuilding analyzer (full)");
        future = runner.submit("Rebuilding code intelligence", () -> {
            try {
                // This will reconstruct the analyzer (potentially MultiAnalyzer) based on current settings.
                IAnalyzer newAnalyzer = loadOrCreateAnalyzerInternal(false);
                currentAnalyzer = newAnalyzer;
                logger.debug("Analyzer (full rebuild) completed.");
                return newAnalyzer;
            } finally {
                synchronized (AnalyzerWrapper.this) {
                    rebuildInProgress = false;
                    if (rebuildPending) {
                        rebuildPending = false;
                        logger.trace("Rebuilding immediately after pending request");
                        rebuild();
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

    public void requestRebuild() {
        externalRebuildRequested = true;
    }

    /**
     * Checks if any window in the application currently has focus
     * @return true if any application window has focus, false otherwise
     */
    private boolean isApplicationFocused() {
        var focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        return focusedWindow != null;
    }

    private void startWatcher() {
        Thread watcherThread = new Thread(() -> beginWatching(root), "DirectoryWatcher");
        watcherThread.start();
    }
    
    private void collectEventsFromKey(WatchKey key, WatchService watchService, Set<FileChangeEvent> batch)
    {
        for (WatchEvent<?> event : key.pollEvents()) {
            @SuppressWarnings("unchecked")
            WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
            Path relativePath = pathEvent.context();
            Path parentDir = (Path) key.watchable();
            Path absolutePath;
            try {
                absolutePath = parentDir.resolve(relativePath);
            } catch (NullPointerException e) {
                logger.warn(e);
                continue;
            }

            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                logger.debug("Overflow event: {}", absolutePath);
                continue;
            }

            // Skip .brokk or log file paths
            String pathStr = absolutePath.toString();
            if (pathStr.contains("${sys:logfile.path}") ||
                absolutePath.startsWith(root.resolve(".brokk"))) {
                continue;
            }

            WatchEvent.Kind<Path> kind = pathEvent.kind();
            EventType eventType = switch (kind.name()) {
                case "ENTRY_CREATE" -> EventType.CREATE;
                case "ENTRY_DELETE" -> EventType.DELETE;
                case "ENTRY_MODIFY" -> EventType.MODIFY;
                default -> EventType.OVERFLOW;
            };

            logger.trace("Directory event: {} on {}", eventType, absolutePath);
            batch.add(new FileChangeEvent(eventType, absolutePath));

            // If it's a directory creation, register it so we can watch its children
            if (eventType == EventType.CREATE && Files.isDirectory(absolutePath)) {
                try {
                    registerAllDirectories(absolutePath, watchService);
                } catch (IOException ex) {
                    logger.warn("Failed to register new directory for watching: {}", absolutePath, ex);
                }
            }
        }

        // If the key is no longer valid, we can't watch this path anymore
        if (!key.reset()) {
            logger.debug("Watch key no longer valid: {}", key.watchable());
        }
    }
    
    private void registerAllDirectories(Path start, WatchService watchService) throws IOException
    {
        if (!Files.isDirectory(start)) return;

        // Skip .brokk itself
        if (start.getFileName() != null && start.getFileName().toString().equals(".brokk")) {
            return;
        }

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (var walker = Files.walk(start)) {
                walker.filter(Files::isDirectory)
                        .filter(dir -> !dir.toString().contains(".brokk"))
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

    /** Pause the file watching service. */
    public synchronized void pause() {
        logger.debug("Pausing file watcher");
        paused = true;
    }

    /** Resume the file watching service. */
    public synchronized void resume() {
        logger.debug("Resuming file watcher");
        paused = false;
    }

    @Override
    public void close() {
        running = false;
        resume(); // Ensure any waiting thread is woken up to exit
    }

    public record CodeWithSource(String code, Set<CodeUnit> sources) {
    }
    
    // Internal event representation to replace DirectoryChangeEvent
    private enum EventType {
        CREATE, MODIFY, DELETE, OVERFLOW
    }
    
    private record FileChangeEvent(EventType type, Path path) {
    }
}
