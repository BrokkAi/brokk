package ai.brokk.gui.mop.webview;

import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.menu.ContextMenuBuilder;
import ai.brokk.gui.mop.ChunkMeta;
import ai.brokk.gui.mop.FilePathLookupService;
import ai.brokk.gui.mop.SymbolLookupService;
import ai.brokk.project.MainProject;
import ai.brokk.util.Messages;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.jetbrains.annotations.Nullable;

/**
 * Handles JS→Java callbacks via CefMessageRouter.
 */
public final class JCEFBridge extends CefMessageRouterHandlerAdapter {
    private static final Logger logger = LogManager.getLogger(JCEFBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double MIN_ZOOM = 0.5;
    private static final double MAX_ZOOM = 2.0;

    private final JCEFWebViewHost host;
    private final AtomicInteger epoch = new AtomicInteger();
    private final List<Consumer<IWebViewHost.SearchState>> searchListeners = new CopyOnWriteArrayList<>();

    // Context for symbol right-click handling
    private @Nullable Chrome chrome;
    private @Nullable ContextManager contextManager;
    private @Nullable Component hostComponent;
    private boolean showEmptyState = false;

    public JCEFBridge(JCEFWebViewHost host) {
        this.host = host;
    }

    public void setChrome(@Nullable Chrome chrome) {
        this.chrome = chrome;
    }

    public void setContextManager(@Nullable ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    public @Nullable ContextManager getContextManager() {
        return contextManager;
    }

    public void setHostComponent(@Nullable Component hostComponent) {
        this.hostComponent = hostComponent;
    }

    public void setShowEmptyState(boolean show) {
        this.showEmptyState = show;
    }

    public void addSearchStateListener(Consumer<IWebViewHost.SearchState> l) {
        searchListeners.add(l);
    }

    public void removeSearchStateListener(Consumer<IWebViewHost.SearchState> l) {
        searchListeners.remove(l);
    }

    @Override
    public boolean onQuery(
            CefBrowser browser,
            CefFrame frame,
            long queryId,
            String request,
            boolean persistent,
            CefQueryCallback callback) {
        try {
            JsonNode call = MAPPER.readTree(request);
            String method = call.get("method").asText();
            JsonNode args = call.get("args");

            logger.trace("Bridge call: {} with args: {}", method, args);

            switch (method) {
                case "onAck" -> onAck(args.get(0).asInt());
                case "jsLog" -> jsLog(args.get(0).asText(), args.get(1).asText());
                case "onBridgeReady" -> onBridgeReady();
                case "openExternalLink" -> openExternalLink(args.get(0).asText());
                case "onSymbolClick" ->
                    onSymbolClick(
                            args.get(0).asText(),
                            args.get(1).asBoolean(),
                            args.get(2).isNull() ? null : args.get(2).asText(),
                            args.get(3).asInt(),
                            args.get(4).asInt());
                case "lookupSymbolsAsync" ->
                    lookupSymbolsAsync(
                            browser,
                            args.get(0).asText(),
                            args.get(1).asInt(),
                            args.get(2).asText());
                case "searchStateChanged" ->
                    searchStateChanged(args.get(0).asInt(), args.get(1).asInt());
                case "onFilePathClick" ->
                    onFilePathClick(
                            args.get(0).asText(),
                            args.get(1).asBoolean(),
                            args.get(2).asText(),
                            args.get(3).asInt(),
                            args.get(4).asInt());
                case "lookupFilePathsAsync" ->
                    lookupFilePathsAsync(
                            browser,
                            args.get(0).asText(),
                            args.get(1).asInt(),
                            args.get(2).asText());
                case "captureText" -> captureText(args.get(0).asText());
                case "onZoomChanged" -> onZoomChanged(args.get(0).asDouble());
                case "deleteHistoryTask" -> deleteHistoryTask(args.get(0).asInt());
                default -> logger.warn("Unknown bridge method: {}", method);
            }

            callback.success("");
            return true;

        } catch (Exception e) {
            logger.error("Bridge message handling failed for request: {}", request, e);
            callback.failure(-1, e.getMessage());
            return true;
        }
    }

    @Override
    public void onQueryCanceled(CefBrowser browser, CefFrame frame, long queryId) {
        logger.debug("Query cancelled: {}", queryId);
    }

    // JS→Java callback handlers

    private void onAck(int e) {
        logger.trace("onAck: epoch={}", e);
        // In full implementation, this would complete awaiting futures
    }

    private void jsLog(String level, String message) {
        switch (level.toUpperCase(Locale.ROOT)) {
            case "ERROR" -> logger.error("JS: {}", message);
            case "WARN" -> logger.warn("JS: {}", message);
            case "INFO" -> logger.info("JS: {}", message);
            case "DEBUG" -> logger.debug("JS: {}", message);
            default -> logger.trace("JS: {}", message);
        }
    }

    private void onBridgeReady() {
        logger.info("JCEF bridge ready - frontend initialized");
        host.onBridgeReady();
    }

    private void openExternalLink(String url) {
        logger.info("Opening external link: {}", url);
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            logger.error("Failed to open URL: {}", url, e);
        }
    }

    private void onSymbolClick(String symbolName, boolean symbolExists, @Nullable String fqn, int x, int y) {
        logger.debug("Symbol clicked: {}, exists: {}, fqn: {} at ({}, {})", symbolName, symbolExists, fqn, x, y);

        SwingUtilities.invokeLater(() -> {
            // Use browser UI component for correct coordinate mapping (x,y are relative to browser viewport)
            var browserComponent = host.getBrowserUIComponent();
            var component = browserComponent != null
                    ? browserComponent
                    : (hostComponent != null
                            ? hostComponent
                            : KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                    .getFocusOwner());

            if (component != null && contextManager != null) {
                if (chrome != null) {
                    try {
                        ContextMenuBuilder.forSymbol(symbolName, symbolExists, fqn, chrome, contextManager)
                                .show(component, x, y);
                    } catch (Exception e) {
                        logger.error("Failed to show context menu", e);
                    }
                } else {
                    logger.warn("Symbol right-click handler not set, ignoring right-click on symbol: {}", symbolName);
                }
            } else {
                logger.warn(
                        "Cannot show context menu - missing dependencies (component={}, contextManager={})",
                        component != null,
                        contextManager != null);
            }
        });
    }

    private void searchStateChanged(int total, int current) {
        logger.debug("searchStateChanged: total={}, current={}", total, current);
        var state = new IWebViewHost.SearchState(total, current);
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

    private void onFilePathClick(String filePath, boolean exists, String matchesJson, int x, int y) {
        logger.debug("File path clicked: {}, exists: {} at ({}, {})", filePath, exists, x, y);
        SwingUtilities.invokeLater(() -> {
            var browserComponent = host.getBrowserUIComponent();
            var component = browserComponent != null
                    ? browserComponent
                    : (hostComponent != null
                            ? hostComponent
                            : KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                    .getFocusOwner());

            if (component != null && contextManager != null) {
                if (chrome != null) {
                    try {
                        var matches = MAPPER.readValue(matchesJson, new TypeReference<List<Map<String, Object>>>() {});
                        var projectFiles = new ArrayList<ProjectFile>();

                        var project = contextManager.getProject();
                        for (var match : matches) {
                            var relativePath = (String) match.get("relativePath");
                            if (relativePath != null) {
                                projectFiles.add(new ProjectFile(project.getRoot(), relativePath));
                            }
                        }

                        if (!projectFiles.isEmpty()) {
                            ContextMenuBuilder.forFilePathMatches(projectFiles, chrome, contextManager)
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

    private void captureText(String text) {
        var cm = contextManager;
        if (cm != null) {
            cm.addPastedTextFragment(text);
        }
    }

    private void onZoomChanged(double zoom) {
        double clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        logger.debug("onZoomChanged from JS: {} (clamped: {})", zoom, clamped);
        MainProject.setMopZoom(clamped);
    }

    private void deleteHistoryTask(int sequence) {
        var cm = contextManager;
        if (cm == null) {
            logger.warn("Cannot delete history entry {} - no context manager", sequence);
            return;
        }
        cm.submitExclusiveAction(() -> cm.dropHistoryEntryBySequence(sequence));
    }

    private void lookupSymbolsAsync(CefBrowser browser, String symbolNamesJson, int seq, String contextId) {
        logger.debug("lookupSymbolsAsync: seq={}, contextId={}, symbols={}", seq, contextId, symbolNamesJson);

        // Parse symbol names
        Set<String> symbolNames;
        try {
            symbolNames = MAPPER.readValue(symbolNamesJson, new TypeReference<Set<String>>() {});
        } catch (Exception e) {
            logger.warn("Failed to parse symbol names JSON: {}", symbolNamesJson, e);
            sendEmptySymbolResponse(browser, seq, contextId);
            return;
        }

        if (symbolNames.isEmpty()) {
            sendEmptySymbolResponse(browser, seq, contextId);
            return;
        }

        if (contextManager == null) {
            logger.warn("No context manager available for symbol lookup");
            sendEmptySymbolResponse(browser, seq, contextId);
            return;
        }

        // Run lookup in background to avoid blocking CEF message handler
        contextManager.submitBackgroundTask("Symbol lookup for " + symbolNames.size() + " symbols", () -> {
            try {
                logger.trace(
                        "Starting streaming symbol lookup for {} symbols in context {}", symbolNames.size(), contextId);

                SymbolLookupService.lookupSymbols(
                        symbolNames,
                        contextManager,
                        // Result callback - called for each individual symbol result
                        (symbolName, symbolResult) -> {
                            try {
                                var singleResult = Map.of(symbolName, symbolResult);
                                var resultsJson = toJson(singleResult);

                                var js = "if (window.brokk && window.brokk.onSymbolLookupResponse) { "
                                        + "window.brokk.onSymbolLookupResponse(" + resultsJson + ", " + seq + ", "
                                        + toJson(contextId) + "); }";
                                browser.executeJavaScript(js, browser.getURL(), 0);
                            } catch (Exception e) {
                                logger.warn("Failed to send streaming symbol lookup result for '{}'", symbolName, e);
                            }
                        },
                        // Completion callback
                        () -> logger.trace(
                                "Streaming symbol lookup completed for {} symbols in context {}",
                                symbolNames.size(),
                                contextId));

            } catch (Exception e) {
                logger.warn("Symbol lookup failed for seq={}, contextId={}", seq, contextId, e);
                sendEmptySymbolResponse(browser, seq, contextId);
            }
            return null;
        });
    }

    private void sendEmptySymbolResponse(CefBrowser browser, int seq, String contextId) {
        try {
            var js = "if (window.brokk && window.brokk.onSymbolLookupResponse) { "
                    + "window.brokk.onSymbolLookupResponse({}, " + seq + ", " + toJson(contextId) + "); }";
            browser.executeJavaScript(js, browser.getURL(), 0);
        } catch (Exception e) {
            logger.warn("Failed to send empty symbol lookup response", e);
        }
    }

    private void lookupFilePathsAsync(CefBrowser browser, String filePathsJson, int seq, String contextId) {
        logger.debug("lookupFilePathsAsync: seq={}, contextId={}, paths={}", seq, contextId, filePathsJson);

        Set<String> filePaths;
        try {
            filePaths = MAPPER.readValue(filePathsJson, new TypeReference<Set<String>>() {});
        } catch (Exception e) {
            logger.warn("Failed to parse file paths JSON: {}", filePathsJson, e);
            sendEmptyFilePathResponse(browser, seq, contextId);
            return;
        }

        if (filePaths.isEmpty()) {
            sendEmptyFilePathResponse(browser, seq, contextId);
            return;
        }

        if (contextManager == null) {
            logger.warn("No context manager available for file path lookup");
            sendEmptyFilePathResponse(browser, seq, contextId);
            return;
        }

        contextManager.submitBackgroundTask("File path lookup for " + filePaths.size() + " paths", () -> {
            try {
                logger.trace("Starting file path lookup for {} paths in context {}", filePaths.size(), contextId);

                FilePathLookupService.lookupFilePaths(
                        filePaths,
                        contextManager,
                        (filePath, filePathResult) -> {
                            try {
                                var singleResult = Map.of(filePath, filePathResult);
                                var resultsJson = toJson(singleResult);

                                var js = "if (window.brokk && window.brokk.onFilePathLookupResponse) { "
                                        + "window.brokk.onFilePathLookupResponse(" + resultsJson + ", " + seq + ", "
                                        + toJson(contextId) + "); }";
                                browser.executeJavaScript(js, browser.getURL(), 0);
                            } catch (Exception e) {
                                logger.warn("Failed to send file path lookup result for '{}'", filePath, e);
                            }
                        },
                        () -> logger.trace(
                                "File path lookup completed for {} paths in context {}", filePaths.size(), contextId));

            } catch (Exception e) {
                logger.warn("File path lookup failed for seq={}, contextId={}", seq, contextId, e);
                sendEmptyFilePathResponse(browser, seq, contextId);
            }
            return null;
        });
    }

    private void sendEmptyFilePathResponse(CefBrowser browser, int seq, String contextId) {
        try {
            var js = "if (window.brokk && window.brokk.onFilePathLookupResponse) { "
                    + "window.brokk.onFilePathLookupResponse({}, " + seq + ", " + toJson(contextId) + "); }";
            browser.executeJavaScript(js, browser.getURL(), 0);
        } catch (Exception e) {
            logger.warn("Failed to send empty file path lookup response", e);
        }
    }

    // Java→JS methods

    /**
     * Send an append event to the frontend.
     */
    public void sendAppend(
            CefBrowser browser, String text, ChatMessageType msgType, boolean streaming, ChunkMeta chunkMeta) {
        int e = epoch.incrementAndGet();
        var event = new BrokkEvent.Chunk(text, msgType, e, streaming, chunkMeta);
        sendEvent(browser, event);
    }

    /**
     * Send a clear event to the frontend.
     */
    public void sendClear(CefBrowser browser) {
        int e = epoch.incrementAndGet();
        var event = new BrokkEvent.Clear(e);
        sendEvent(browser, event);
    }

    /** Send a static document event to the frontend. */
    public void sendStaticDocument(CefBrowser browser, @org.jetbrains.annotations.Nullable String markdown) {
        int e = epoch.incrementAndGet();
        var event = new BrokkEvent.StaticDocument(e, markdown);
        sendEvent(browser, event);
    }

    /**
     * Send a theme event to the frontend.
     */
    public void sendTheme(CefBrowser browser, String themeName, boolean isDevMode, boolean wrapMode) {
        int e = epoch.incrementAndGet();
        var event = new BrokkEvent.Theme(e, themeName, isDevMode, wrapMode);
        logger.info("Sending theme event: {}", event);
        sendEvent(browser, event);
    }

    /**
     * Send a history reset event to the frontend.
     */
    public void sendHistoryReset(CefBrowser browser) {
        int e = epoch.incrementAndGet();
        var event = new BrokkEvent.HistoryReset(e);
        sendEvent(browser, event);
    }

    /**
     * Send a history task event to the frontend.
     */
    public void sendHistoryTask(CefBrowser browser, TaskEntry entry) {
        int e = epoch.incrementAndGet();

        // Convert messages from log when available
        List<BrokkEvent.HistoryTask.Message> messages = new ArrayList<>();
        var taskFragment = entry.log();

        if (taskFragment != null) {
            var msgs = taskFragment.messages();
            for (var message : msgs) {
                var text = Messages.getText(message);
                messages.add(new BrokkEvent.HistoryTask.Message(
                        text, message.type(), Messages.isReasoningMessage(message), false));
            }
        }

        // Build event: compressed flag is true when summary is present (AI uses summary)
        // Include both summary and messages when available
        var summary = entry.isCompressed() ? entry.summary() : null;
        var compressed = entry.isCompressed();
        var messagesList = messages.isEmpty() ? null : messages;

        var event = new BrokkEvent.HistoryTask(e, entry.sequence(), compressed, summary, messagesList);
        sendEvent(browser, event);
    }

    /**
     * Send a live summary event to the frontend.
     */
    public void sendLiveSummary(CefBrowser browser, int taskSequence, boolean compressed, String summary) {
        int e = epoch.incrementAndGet();
        var event = new BrokkEvent.LiveSummary(e, taskSequence, compressed, summary);
        sendEvent(browser, event);
    }

    /**
     * Show spinner with message in the frontend.
     */
    public void showSpinner(CefBrowser browser, String message) {
        var jsonMessage = toJson(message);
        var js = "if (window.brokk && window.brokk.showSpinner) { window.brokk.showSpinner(" + jsonMessage + "); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Hide spinner in the frontend.
     */
    public void hideSpinner(CefBrowser browser) {
        var js = "if (window.brokk && window.brokk.hideSpinner) { window.brokk.hideSpinner(); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Show transient message in the frontend.
     */
    public void showTransientMessage(CefBrowser browser, String message) {
        var jsonMessage = toJson(message);
        var js = "if (window.brokk && window.brokk.showTransientMessage) { window.brokk.showTransientMessage("
                + jsonMessage + "); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Hide transient message in the frontend.
     */
    public void hideTransientMessage(CefBrowser browser) {
        var js = "if (window.brokk && window.brokk.hideTransientMessage) { window.brokk.hideTransientMessage(); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Set task in progress state in the frontend.
     */
    public void setTaskInProgress(CefBrowser browser, boolean inProgress) {
        var js = "if (window.brokk && window.brokk.setTaskInProgress) { window.brokk.setTaskInProgress(" + inProgress
                + "); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    public void setShowEmptyState(CefBrowser browser, boolean show) {
        var js =
                "if (window.brokk && window.brokk.setShowEmptyState) { window.brokk.setShowEmptyState(" + show + "); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Set zoom level in the frontend.
     */
    public void setZoom(CefBrowser browser, double zoom) {
        double clamped = Math.max(0.5, Math.min(2.0, zoom)); // MIN_ZOOM=0.5, MAX_ZOOM=2.0
        var js = "if (window.brokk && window.brokk.setZoom) { window.brokk.setZoom(" + clamped + "); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Zoom in the frontend.
     */
    public void zoomIn(CefBrowser browser) {
        var js = "if (window.brokk && window.brokk.zoomIn) { window.brokk.zoomIn(); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Zoom out the frontend.
     */
    public void zoomOut(CefBrowser browser) {
        var js = "if (window.brokk && window.brokk.zoomOut) { window.brokk.zoomOut(); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Reset zoom to default in the frontend.
     */
    public void resetZoom(CefBrowser browser) {
        var js = "if (window.brokk && window.brokk.resetZoom) { window.brokk.resetZoom(); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Set search query in the frontend.
     */
    public void setSearch(CefBrowser browser, String query, boolean caseSensitive) {
        var js = "if (window.brokk && window.brokk.setSearch) { window.brokk.setSearch(" + toJson(query) + ", "
                + caseSensitive + "); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Clear search in the frontend.
     */
    public void clearSearch(CefBrowser browser) {
        var js = "if (window.brokk && window.brokk.clearSearch) { window.brokk.clearSearch(); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Navigate to next search match in the frontend.
     */
    public void nextMatch(CefBrowser browser) {
        var js = "if (window.brokk && window.brokk.nextMatch) { window.brokk.nextMatch(); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Navigate to previous search match in the frontend.
     */
    public void prevMatch(CefBrowser browser) {
        var js = "if (window.brokk && window.brokk.prevMatch) { window.brokk.prevMatch(); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Scroll to current search match in the frontend.
     */
    public void scrollToCurrent(CefBrowser browser) {
        var js = "if (window.brokk && window.brokk.scrollToCurrent) { window.brokk.scrollToCurrent(); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Notify frontend that analyzer is ready for a context.
     */
    public void onAnalyzerReadyResponse(CefBrowser browser, String contextId) {
        logger.debug("Notifying frontend that analyzer is ready for context: {}", contextId);
        var js = "if (window.brokk && window.brokk.refreshSymbolLookup) { window.brokk.refreshSymbolLookup("
                + toJson(contextId) + "); }";
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    /**
     * Send environment information to the frontend.
     */
    public void sendEnvironmentInfo(CefBrowser browser, boolean analyzerReady) {
        if (contextManager == null) {
            logger.warn("sendEnvironmentInfo called without a ContextManager");
            return;
        }

        try {
            var project = contextManager.getProject();

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
            payload.put("showEmptyState", showEmptyState);
            if (!analyzerLanguagesInfo.isEmpty()) {
                payload.put("analyzerLanguages", analyzerLanguagesInfo);
            }

            var json = toJson(payload);
            var js = "if (window.brokk && window.brokk.setEnvironmentInfo) { window.brokk.setEnvironmentInfo(" + json
                    + "); }";

            browser.executeJavaScript(js, browser.getURL(), 0);
        } catch (Exception ex) {
            logger.warn("Failed to send environment info", ex);
        }
    }

    private void sendEvent(CefBrowser browser, BrokkEvent event) {
        String json = toJson(event);
        String js = "if (window.brokk && window.brokk.onEvent) { window.brokk.onEvent(" + json + "); }";
        logger.debug("Executing JS: {}", js);
        browser.executeJavaScript(js, browser.getURL(), 0);
    }

    private static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
