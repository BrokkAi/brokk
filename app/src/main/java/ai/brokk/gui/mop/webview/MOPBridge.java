package ai.brokk.gui.mop.webview;

import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.menu.ContextMenuBuilder;
import ai.brokk.gui.mop.FilePathLookupService;
import ai.brokk.gui.mop.SymbolLookupService;
import ai.brokk.project.MainProject;
import ai.brokk.util.Environment;
import ai.brokk.util.Messages;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class MOPBridge {
    private static final Logger logger = LogManager.getLogger(MOPBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double MIN_ZOOM = 0.5;
    private static final double MAX_ZOOM = 2.0;

    public record SearchState(int totalMatches, int currentDisplayIndex) {}

    private final List<Consumer<SearchState>> searchListeners = new CopyOnWriteArrayList<>();
    private final WebEngine engine;
    private final ScheduledExecutorService xmit;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicInteger epoch = new AtomicInteger();
    private final Map<Integer, CompletableFuture<Void>> awaiting = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<BrokkEvent> eventQueue = new LinkedBlockingQueue<>();
    private volatile @Nullable ContextManager contextManager;
    private volatile @Nullable Chrome chrome;
    private volatile @Nullable Component hostComponent;

    public MOPBridge(WebEngine engine) {
        this.engine = engine;
        this.xmit = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "MOPBridge-" + this.hashCode());
            t.setDaemon(true);
            return t;
        });
    }

    public void addSearchStateListener(Consumer<SearchState> l) {
        searchListeners.add(l);
    }

    public void removeSearchStateListener(Consumer<SearchState> l) {
        searchListeners.remove(l);
    }

    public void searchStateChanged(int total, int current) {
        logger.debug("searchStateChanged: total={}, current={}", total, current);
        var state = new SearchState(total, current);
        SwingUtilities.invokeLater(() -> {
            for (var l : searchListeners) {
                try {
                    l.accept(state);
                } catch (Exception ex) {
                    logger.warn("search listener failed", ex);
                }
            }
        });
    }

    public void setSearch(String query, boolean caseSensitive) {
        var js = "if (window.brokk && window.brokk.setSearch) { window.brokk.setSearch(" + toJson(query) + ", "
                + caseSensitive + "); }";
        Platform.runLater(() -> engine.executeScript(js));
    }

    public void clearSearch() {
        Platform.runLater(() ->
                engine.executeScript("if (window.brokk && window.brokk.clearSearch) { window.brokk.clearSearch(); }"));
    }

    public void nextMatch() {
        Platform.runLater(() ->
                engine.executeScript("if (window.brokk && window.brokk.nextMatch) { window.brokk.nextMatch(); }"));
    }

    public void prevMatch() {
        Platform.runLater(() ->
                engine.executeScript("if (window.brokk && window.brokk.prevMatch) { window.brokk.prevMatch(); }"));
    }

    public void scrollToCurrent() {
        Platform.runLater(() -> engine.executeScript(
                "if (window.brokk && window.brokk.scrollToCurrent) { window.brokk.scrollToCurrent(); }"));
    }

    public void zoomIn() {
        Platform.runLater(
                () -> engine.executeScript("if (window.brokk && window.brokk.zoomIn) { window.brokk.zoomIn(); }"));
    }

    public void zoomOut() {
        Platform.runLater(
                () -> engine.executeScript("if (window.brokk && window.brokk.zoomOut) { window.brokk.zoomOut(); }"));
    }

    public void resetZoom() {
        Platform.runLater(() ->
                engine.executeScript("if (window.brokk && window.brokk.resetZoom) { window.brokk.resetZoom(); }"));
    }

    public void onZoomChanged(double zoom) {
        double clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        logger.debug("onZoomChanged from JS: {} (clamped: {})", zoom, clamped);
        MainProject.setMopZoom(clamped);
    }

    public void setZoom(double zoom) {
        double clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        var js = "if (window.brokk && window.brokk.setZoom) { window.brokk.setZoom(" + clamped + "); }";
        Platform.runLater(() -> engine.executeScript(js));
    }

    public void onAnalyzerReadyResponse(String contextId) {
        logger.debug("Notifying frontend that analyzer is ready for context: {}", contextId);
        var js = "if (window.brokk && window.brokk.refreshSymbolLookup) { window.brokk.refreshSymbolLookup("
                + toJson(contextId) + "); }";
        Platform.runLater(() -> engine.executeScript(js));
    }

    public void append(String text, boolean isNew, ChatMessageType msgType, boolean streaming, boolean reasoning) {
        if (text.isEmpty()) {
            return;
        }

        eventQueue.add(new BrokkEvent.Chunk(text, isNew, msgType, -1, streaming, reasoning));
        scheduleSend();
    }

    public void setTheme(String themeName, boolean isDevMode, boolean wrapMode, double zoom) {
        double clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        var js = "if (window.brokk && window.brokk.setTheme) { window.brokk.setTheme(" + toJson(themeName) + ", "
                + isDevMode + ", " + wrapMode + ", " + clamped
                + "); } else { console.error('setTheme buffered - bridge not ready yet'); }";
        Platform.runLater(() -> engine.executeScript(js));
    }

    public void showSpinner(String message) {
        var jsonMessage = toJson(message);
        var js = "if (window.brokk && window.brokk.showSpinner) { window.brokk.showSpinner(" + jsonMessage
                + "); } else { console.error('showSpinner called - bridge not ready yet'); }";
        Platform.runLater(() -> engine.executeScript(js));
    }

    public void hideSpinner() {
        Platform.runLater(
                () -> engine.executeScript(
                        "if (window.brokk && window.brokk.hideSpinner) { window.brokk.hideSpinner(); } else { console.error('hideSpinner called - bridge not ready yet'); }"));
    }

    public void showTransientMessage(String message) {
        var jsonMessage = toJson(message);
        var js = "if (window.brokk && window.brokk.showTransientMessage) { window.brokk.showTransientMessage("
                + jsonMessage + "); } else { console.error('showTransientMessage called - bridge not ready yet'); }";
        Platform.runLater(() -> engine.executeScript(js));
    }

    public void hideTransientMessage() {
        Platform.runLater(
                () -> engine.executeScript(
                        "if (window.brokk && window.brokk.hideTransientMessage) { window.brokk.hideTransientMessage(); } else { console.error('hideTransientMessage called - bridge not ready yet'); }"));
    }

    public void setTaskInProgress(boolean inProgress) {
        var js = "if (window.brokk && window.brokk.setTaskInProgress) { window.brokk.setTaskInProgress(" + inProgress
                + "); } else { console.error('setTaskInProgress called - bridge not ready yet'); }";
        Platform.runLater(() -> engine.executeScript(js));
    }

    public void clear() {
        var e = epoch.incrementAndGet();
        eventQueue.add(new BrokkEvent.Clear(e));
        scheduleSend();
    }

    /** Enqueue a history reset event for the WebView to clear its stored history. */
    public void sendHistoryReset() {
        var e = epoch.incrementAndGet();
        eventQueue.add(new BrokkEvent.HistoryReset(e));
        scheduleSend();
    }

    /** Enqueue a single task from the conversation history to the WebView. */
    public void sendHistoryTask(TaskEntry entry) {
        var e = epoch.incrementAndGet();

        // Convert messages from log when available
        List<BrokkEvent.HistoryTask.Message> messages = new ArrayList<>();
        var taskFragment = entry.log();

        if (taskFragment != null) {
            var msgs = taskFragment.messages();
            for (var message : msgs) {
                var text = Messages.getText(message);
                messages.add(
                        new BrokkEvent.HistoryTask.Message(text, message.type(), Messages.isReasoningMessage(message)));
            }
        }

        // Build event: compressed flag is true when summary is present (AI uses summary)
        // Include both summary and messages when available
        var summary = entry.isCompressed() ? entry.summary() : null;
        var compressed = entry.isCompressed();
        var messagesList = messages.isEmpty() ? null : messages;

        var event = new BrokkEvent.HistoryTask(e, entry.sequence(), compressed, summary, messagesList);
        eventQueue.add(event);
        scheduleSend();
    }

    /**
     * Sends a live summary event to the frontend for the current in-progress task.
     * This allows the frontend to display a summary toggle in the live area.
     *
     * @param taskSequence The backend TaskEntry sequence number
     * @param compressed Whether the task is compressed (AI uses summary)
     * @param summary The summary text
     */
    public void sendLiveSummary(int taskSequence, boolean compressed, String summary) {
        var e = epoch.incrementAndGet();
        eventQueue.add(new BrokkEvent.LiveSummary(e, taskSequence, compressed, summary));
        scheduleSend();
    }

    private void scheduleSend() {
        if (pending.compareAndSet(false, true)) {
            xmit.schedule(this::processQueue, 20, TimeUnit.MILLISECONDS);
        }
    }

    private void processQueue() {
        try {
            var events = new ArrayList<BrokkEvent>();
            eventQueue.drainTo(events);
            if (events.isEmpty()) {
                return;
            }

            var currentText = new StringBuilder();
            BrokkEvent.Chunk firstChunk = null;

            for (var event : events) {
                if (event instanceof BrokkEvent.Chunk chunk) {
                    if (firstChunk == null) {
                        firstChunk = chunk;
                    } else if (chunk.isNew()
                            || chunk.msgType() != firstChunk.msgType()
                            || chunk.reasoning() != firstChunk.reasoning()) {
                        // A new bubble is starting, so send the previously buffered one
                        flushCurrentChunk(firstChunk, currentText);
                        firstChunk = chunk;
                    }
                    currentText.append(chunk.text());
                } else {
                    // Any non-chunk event (clear, history-reset, history-task, etc.)
                    // must flush any pending chunk first and then be forwarded immediately.
                    flushCurrentChunk(firstChunk, currentText);
                    firstChunk = null;
                    sendEvent(event);
                }
            }

            // After the loop, send any remaining buffered text
            flushCurrentChunk(firstChunk, currentText);
        } finally {
            pending.set(false);
            if (!eventQueue.isEmpty()) {
                scheduleSend();
            }
        }
    }

    private void flushCurrentChunk(@Nullable BrokkEvent.Chunk firstChunk, StringBuilder currentText) {
        if (firstChunk != null) {
            sendChunk(
                    currentText.toString(),
                    firstChunk.isNew(),
                    firstChunk.msgType(),
                    firstChunk.streaming(),
                    firstChunk.reasoning());
            currentText.setLength(0);
        }
    }

    private void sendChunk(String text, boolean isNew, ChatMessageType msgType, boolean streaming, boolean reasoning) {
        var e = epoch.incrementAndGet();
        var event = new BrokkEvent.Chunk(text, isNew, msgType, e, streaming, reasoning);
        sendEvent(event);
    }

    private void sendEvent(BrokkEvent event) {
        var e = event.getEpoch();
        awaiting.put(e, new CompletableFuture<>());
        var json = toJson(event);
        Platform.runLater(() -> engine.executeScript("if (window.brokk && window.brokk.onEvent) { window.brokk.onEvent("
                + json + "); } else { console.error('onEvent called - bridge not ready yet'); }"));
    }

    public void onAck(int e) {
        var p = awaiting.remove(e);
        if (p != null) {
            p.complete(null);
        }
    }

    public CompletableFuture<String> getSelection() {
        var future = new CompletableFuture<String>();
        Platform.runLater(() -> {
            try {
                Object result = engine.executeScript(
                        "(window.brokk && window.brokk.getSelection) ? window.brokk.getSelection() : ''");
                future.complete(result != null ? result.toString() : "");
            } catch (Exception ex) {
                logger.error("Failed to get selection from WebView", ex);
                future.complete("");
            }
        });
        return future;
    }

    public CompletableFuture<Void> flushAsync() {
        var future = new CompletableFuture<Void>();
        xmit.submit(() -> {
            processQueue();
            var lastEpoch = epoch.get();
            var lastFuture = awaiting.getOrDefault(lastEpoch, CompletableFuture.completedFuture(null));
            lastFuture.whenComplete((res, err) -> {
                if (err != null) {
                    future.completeExceptionally(err);
                } else {
                    future.complete(null);
                }
            });
        });
        return future;
    }

    public void jsLog(String level, String message) {
        switch (level.toUpperCase(Locale.ROOT)) {
            case "ERROR" -> logger.error("JS: {}", message);
            case "WARN" -> logger.warn("JS: {}", message);
            case "INFO" -> logger.info("JS: {}", message);
            case "DEBUG" -> logger.debug("JS: {}", message);
            default -> logger.trace("JS: {}", message);
        }
    }

    public void setContextManager(@Nullable ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    public void setChrome(@Nullable Chrome chrome) {
        this.chrome = chrome;
    }

    public void setHostComponent(@Nullable Component hostComponent) {
        this.hostComponent = hostComponent;
    }

    public void lookupSymbolsAsync(String symbolNamesJson, int seq, String contextId) {
        // Assert we're not blocking the EDT with this call
        assert !SwingUtilities.isEventDispatchThread() : "Symbol lookup should not be called on EDT";

        // Parse symbol names (keep existing parsing logic)
        Set<String> symbolNames;
        try {
            symbolNames = MAPPER.readValue(symbolNamesJson, new TypeReference<Set<String>>() {});
        } catch (Exception e) {
            logger.warn("Failed to parse symbol names JSON: {}", symbolNamesJson, e);
            sendEmptyResponse(seq, contextId);
            return;
        }

        if (symbolNames.isEmpty()) {
            sendEmptyResponse(seq, contextId);
            return;
        }

        if (contextManager == null) {
            logger.warn("No context manager available for symbol lookup");
            sendEmptyResponse(seq, contextId);
            return;
        }

        // Use Chrome's background task system instead of raw CompletableFuture.supplyAsync()
        contextManager.submitBackgroundTask("Symbol lookup for " + symbolNames.size() + " symbols", () -> {
            // Assert background task is not running on EDT
            assert !SwingUtilities.isEventDispatchThread() : "Background task running on EDT";

            try {
                logger.trace(
                        "Starting streaming symbol lookup for {} symbols in context {}", symbolNames.size(), contextId);

                // Use streaming lookup to send results as they become available
                SymbolLookupService.lookupSymbols(
                        symbolNames,
                        contextManager,
                        // Result callback - called for each individual symbol result
                        (symbolName, symbolResult) -> {
                            // Send individual result immediately on UI thread
                            Platform.runLater(() -> {
                                try {
                                    var singleResult = Map.of(symbolName, symbolResult);
                                    var resultsJson = toJson(singleResult);

                                    var js = "if (window.brokk && window.brokk.onSymbolLookupResponse) { "
                                            + "window.brokk.onSymbolLookupResponse(" + resultsJson + ", " + seq + ", "
                                            + toJson(contextId) + "); }";
                                    engine.executeScript(js);
                                } catch (Exception e) {
                                    logger.warn(
                                            "Failed to send streaming symbol lookup result for '{}'", symbolName, e);
                                }
                            });
                        },
                        // Completion callback - called when all symbols are processed
                        () -> {
                            logger.trace(
                                    "Streaming symbol lookup completed for {} symbols in context {}",
                                    symbolNames.size(),
                                    contextId);
                        });

            } catch (Exception e) {
                logger.warn("Symbol lookup failed for seq={}, contextId={}", seq, contextId, e);
                Platform.runLater(() -> {
                    sendEmptyResponse(seq, contextId);
                });
            }
            return null;
        });
    }

    private void sendEmptyResponse(int seq, String contextId) {
        try {
            var js = "if (window.brokk && window.brokk.onSymbolLookupResponse) { "
                    + "window.brokk.onSymbolLookupResponse({}, " + seq + ", " + toJson(contextId) + "); }";
            engine.executeScript(js);
        } catch (Exception e) {
            logger.warn("Failed to send empty symbol lookup response", e);
        }
    }

    public void lookupFilePathsAsync(String filePathsJson, int seq, String contextId) {
        // Assert we're not blocking the EDT with this call
        assert !SwingUtilities.isEventDispatchThread() : "File path lookup should not be called on EDT";

        // Parse file paths
        Set<String> filePaths;
        try {
            filePaths = MAPPER.readValue(filePathsJson, new TypeReference<Set<String>>() {});
        } catch (Exception e) {
            logger.warn("Failed to parse file paths JSON: {}", filePathsJson, e);
            sendEmptyFilePathResponse(seq, contextId);
            return;
        }

        if (filePaths.isEmpty()) {
            sendEmptyFilePathResponse(seq, contextId);
            return;
        }

        if (contextManager == null) {
            logger.warn("No context manager available for file path lookup");
            sendEmptyFilePathResponse(seq, contextId);
            return;
        }

        // Use Chrome's background task system for file path lookup
        contextManager.submitBackgroundTask("File path lookup for " + filePaths.size() + " paths", () -> {
            // Assert background task is not running on EDT
            assert !SwingUtilities.isEventDispatchThread() : "Background task running on EDT";

            try {
                logger.trace("Starting file path lookup for {} paths in context {}", filePaths.size(), contextId);

                // Use file path lookup service
                FilePathLookupService.lookupFilePaths(
                        filePaths,
                        contextManager,
                        // Result callback - called for each individual file path result
                        (filePath, filePathResult) -> {
                            // Send individual result immediately on UI thread
                            Platform.runLater(() -> {
                                try {
                                    var singleResult = Map.of(filePath, filePathResult);
                                    var resultsJson = toJson(singleResult);

                                    var js = "if (window.brokk && window.brokk.onFilePathLookupResponse) { "
                                            + "window.brokk.onFilePathLookupResponse(" + resultsJson + ", " + seq + ", "
                                            + toJson(contextId) + "); }";
                                    engine.executeScript(js);
                                } catch (Exception e) {
                                    logger.warn("Failed to send file path lookup result for '{}'", filePath, e);
                                }
                            });
                        },
                        // Completion callback - called when all file paths are processed
                        () -> {
                            // No-op completion callback
                        });

            } catch (Exception e) {
                logger.warn("File path lookup failed for seq={}, contextId={}", seq, contextId, e);
                Platform.runLater(() -> {
                    sendEmptyFilePathResponse(seq, contextId);
                });
            }
            return null;
        });
    }

    private void sendEmptyFilePathResponse(int seq, String contextId) {
        try {
            var js = "if (window.brokk && window.brokk.onFilePathLookupResponse) { "
                    + "window.brokk.onFilePathLookupResponse({}, " + seq + ", " + toJson(contextId) + "); }";
            engine.executeScript(js);
        } catch (Exception e) {
            logger.warn("Failed to send empty file path lookup response", e);
        }
    }

    public void onFilePathClick(String filePath, boolean exists, String matchesJson, int x, int y) {
        SwingUtilities.invokeLater(() -> {
            var component = hostComponent != null
                    ? hostComponent
                    : KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();

            if (component != null && contextManager != null) {
                if (chrome != null) {
                    try {
                        // Parse the matches JSON to get ProjectFile list
                        var matches = MAPPER.readValue(matchesJson, new TypeReference<List<Map<String, Object>>>() {});
                        var projectFiles = new ArrayList<ProjectFile>();

                        var project = contextManager.getProject();
                        for (var match : matches) {
                            String relativePath = (String) match.get("relativePath");
                            if (relativePath != null) {
                                projectFiles.add(new ProjectFile(project.getRoot(), relativePath));
                            }
                        }

                        if (!projectFiles.isEmpty()) {
                            ContextMenuBuilder.forFilePathMatches(projectFiles, chrome, (ContextManager) contextManager)
                                    .show(component, x, y);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to show file path context menu", e);
                    }
                } else {
                    logger.warn("File path click handler not set, ignoring click on file path: {}", filePath);
                }
            } else {
                logger.warn("Cannot show file path context menu - missing dependencies");
            }
        });
    }

    public void onSymbolClick(String symbolName, boolean symbolExists, @Nullable String fqn, int x, int y) {
        logger.debug("Symbol clicked: {}, exists: {}, fqn: {} at ({}, {})", symbolName, symbolExists, fqn, x, y);

        SwingUtilities.invokeLater(() -> {
            var component = hostComponent != null
                    ? hostComponent
                    : KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();

            if (component != null && contextManager != null) {
                if (chrome != null) {
                    try {
                        ContextMenuBuilder.forSymbol(
                                        symbolName, symbolExists, fqn, chrome, (ContextManager) contextManager)
                                .show(component, x, y);
                    } catch (Exception e) {
                        logger.error("Failed to show context menu", e);
                    }
                } else {
                    logger.warn("Symbol right-click handler not set, ignoring right-click on symbol: {}", symbolName);
                }
            } else {
                logger.warn("Cannot show context menu - missing dependencies");
            }
        });
    }

    public void captureText(String text) {
        var cm = contextManager;
        if (cm != null) {
            cm.addPastedTextFragment(text);
        }
    }

    public void deleteHistoryTask(int sequence) {
        var cm = contextManager;
        if (cm == null) {
            logger.warn("Cannot delete history entry {} - no context manager", sequence);
            return;
        }
        cm.submitExclusiveAction(() -> cm.dropHistoryEntryBySequence(sequence));
    }

    public String getContextCacheId() {
        return "main-context";
    }

    private static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void openExternalLink(String url) {
        logger.info("Opening external link in browser: {}", url);
        SwingUtilities.invokeLater(() -> {
            if (hostComponent != null) {
                Environment.openInBrowser(url, SwingUtilities.getWindowAncestor(hostComponent));
            } else {
                Environment.openInBrowser(url, null);
            }
        });
    }

    public void onBridgeReady() {
        // Send initial environment snapshot; reflect current analyzer state and languages
        boolean ready = contextManager != null && contextManager.isAnalyzerReady();
        sendEnvironmentInfo(ready);

        // If analyzer is already ready when bridge initializes, trigger symbol lookup for existing content
        if (ready) {
            onAnalyzerReadyResponse(getContextCacheId());
        }

        if (hostComponent instanceof MOPWebViewHost host) {
            host.onBridgeReady();
        }
    }

    /**
     * Send a snapshot of environment information to the frontend. The frontend is expected to expose
     * window.brokk.setEnvironmentInfo(info).
     *
     * @param analyzerReady whether the analyzer is ready
     */
    public void sendEnvironmentInfo(boolean analyzerReady) {
        var cm = contextManager;
        if (cm == null) {
            logger.warn("sendEnvironmentInfo called without a ContextManager");
            return;
        }

        try {
            var project = cm.getProject();

            String version = BuildInfo.version;
            String projectName = project.getRoot().getFileName().toString();
            int totalFileCount = project.getAllFiles().size();

            List<Map<String, Object>> analyzerLanguagesInfo = new ArrayList<>();
            try {
                // Get analyzer languages from the project
                Object langs = project.getAnalyzerLanguages();
                List<String> languageNames = new ArrayList<>();

                if (langs instanceof String s) {
                    if (!s.isBlank()) {
                        languageNames.add(s.trim());
                    }
                } else if (langs instanceof Collection<?> c) {
                    languageNames = c.stream()
                            .map(String::valueOf)
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .distinct()
                            .toList();
                } else if (langs.getClass().isArray()) {
                    var arr = (Object[]) langs;
                    languageNames = Arrays.stream(arr)
                            .map(String::valueOf)
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .distinct()
                            .toList();
                } else {
                    var s = String.valueOf(langs).trim();
                    if (!s.isEmpty()) {
                        languageNames.add(s);
                    }
                }

                // Get all live dependencies once
                var liveDeps = project.getLiveDependencies();

                // For each language, get file and dependency counts
                for (String langName : languageNames) {
                    try {
                        // Convert language name to Language object
                        var language = ai.brokk.analyzer.Languages.valueOf(langName);

                        // Get analyzable files for this language
                        var analyzableFiles = project.getAnalyzableFiles(language);
                        int fileCount = analyzableFiles.size();

                        // Count files from dependencies matching this language
                        int depCount = 0;
                        for (var dep : liveDeps) {
                            if (dep.language() == language) {
                                depCount += dep.files().size();
                            }
                        }

                        var langInfo = new LinkedHashMap<String, Object>();
                        langInfo.put("name", language.name());
                        langInfo.put("fileCount", fileCount);
                        langInfo.put("depCount", depCount);
                        analyzerLanguagesInfo.add(langInfo);
                    } catch (IllegalArgumentException e) {
                        logger.trace("Language not found or invalid: {}", langName, e);
                    } catch (Exception e) {
                        logger.trace("Failed to get file counts for language: {}", langName, e);
                    }
                }
            } catch (Throwable t) {
                logger.trace("Analyzer languages unavailable from project", t);
            }

            var payload = new LinkedHashMap<String, Object>();
            payload.put("version", version);
            payload.put("projectName", projectName);
            payload.put("totalFileCount", totalFileCount);
            payload.put("analyzerReady", analyzerReady);
            if (!analyzerLanguagesInfo.isEmpty()) {
                payload.put("analyzerLanguages", analyzerLanguagesInfo);
            }

            var json = toJson(payload);
            var js = "if (window.brokk && window.brokk.setEnvironmentInfo) { " + "window.brokk.setEnvironmentInfo("
                    + json + "); }";

            Platform.runLater(() -> {
                try {
                    engine.executeScript(js);
                } catch (Exception ex) {
                    logger.warn("Failed to dispatch environment info to WebView", ex);
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to gather or send environment info", e);
        }
    }

    public void shutdown() {
        xmit.shutdownNow();
    }
}
