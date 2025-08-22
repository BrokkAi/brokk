package io.github.jbellis.brokk.gui.mop.webview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.gui.mop.SymbolLookupService;
import java.io.UncheckedIOException;
import java.util.ArrayList;
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
import netscape.javascript.JSException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class MOPBridge {
    private static final Logger logger = LogManager.getLogger(MOPBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record SearchState(int totalMatches, int currentDisplayIndex) {}

    private final List<Consumer<SearchState>> searchListeners = new CopyOnWriteArrayList<>();
    private final WebEngine engine;
    private final ScheduledExecutorService xmit;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicInteger epoch = new AtomicInteger();
    private final Map<Integer, CompletableFuture<Void>> awaiting = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<BrokkEvent> eventQueue = new LinkedBlockingQueue<>();
    private volatile @Nullable IContextManager contextManager;
    private volatile @Nullable io.github.jbellis.brokk.gui.Chrome chrome;
    private volatile @Nullable java.awt.Component hostComponent;
    private final MOPWebViewHost webViewHost;

    public MOPBridge(WebEngine engine, MOPWebViewHost webViewHost) {
        this.engine = engine;
        this.webViewHost = webViewHost;
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

    /** Enhanced executeScript with comprehensive JSException prevention */
    private void safeExecuteScript(String script, String methodName) {
        if (engine == null) {
            logger.debug("{} failed: WebEngine is null", methodName);
            return;
        }

        Platform.runLater(() -> {
            try {
                engine.executeScript(script);
            } catch (JSException js) {
                if (js.getMessage() != null && js.getMessage().contains("undefined is not an object")) {
                    logger.debug("{} failed: JavaScript context not ready - {}", methodName, js.getMessage());
                } else {
                    logger.warn("{} failed with JSException: {}", methodName, js.getMessage());
                }
            } catch (IllegalStateException ise) {
                logger.debug("{} failed: WebView not initialized - {}", methodName, ise.getMessage());
            } catch (Exception e) {
                logger.warn("{} failed with unexpected exception: {}", methodName, e.getMessage(), e);
            }
        });
    }

    /** Enhanced executeScript for methods that return values */
    private CompletableFuture<Object> safeExecuteScriptWithResult(String script, String methodName) {
        var future = new CompletableFuture<Object>();

        if (engine == null) {
            logger.debug("{} failed: WebEngine is null", methodName);
            future.complete(null);
            return future;
        }

        Platform.runLater(() -> {
            try {
                Object result = engine.executeScript(script);
                future.complete(result);
            } catch (JSException js) {
                if (js.getMessage() != null && js.getMessage().contains("undefined is not an object")) {
                    logger.debug("{} failed: JavaScript context not ready - {}", methodName, js.getMessage());
                } else {
                    logger.warn("{} failed with JSException: {}", methodName, js.getMessage());
                }
                future.complete(null);
            } catch (IllegalStateException ise) {
                logger.debug("{} failed: WebView not initialized - {}", methodName, ise.getMessage());
                future.complete(null);
            } catch (Exception e) {
                logger.warn("{} failed with unexpected exception: {}", methodName, e.getMessage(), e);
                future.complete(null);
            }
        });

        return future;
    }

    public void setSearch(String query, boolean caseSensitive) {
        var js = "if (window.brokk && window.brokk.setSearch) { window.brokk.setSearch(" + toJson(query) + ", "
                + caseSensitive + "); }";
        safeExecuteScript(js, "setSearch");
    }

    public void clearSearch() {
        safeExecuteScript(
                "if (window.brokk && window.brokk.clearSearch) { window.brokk.clearSearch(); }", "clearSearch");
    }

    public void nextMatch() {
        safeExecuteScript("if (window.brokk && window.brokk.nextMatch) { window.brokk.nextMatch(); }", "nextMatch");
    }

    public void prevMatch() {
        safeExecuteScript("if (window.brokk && window.brokk.prevMatch) { window.brokk.prevMatch(); }", "prevMatch");
    }

    public void scrollToCurrent() {
        safeExecuteScript(
                "if (window.brokk && window.brokk.scrollToCurrent) { window.brokk.scrollToCurrent(); }",
                "scrollToCurrent");
    }

    public void append(String text, boolean isNew, ChatMessageType msgType, boolean streaming, boolean reasoning) {
        if (text.isEmpty()) {
            return;
        }
        // Epoch is assigned later, just queue the content
        eventQueue.add(new BrokkEvent.Chunk(text, isNew, msgType, -1, streaming, reasoning));
        scheduleSend();
    }

    public void setTheme(boolean isDark, boolean isDevMode) {
        var js = "if (window.brokk && window.brokk.setTheme) { window.brokk.setTheme(" + isDark + ", " + isDevMode
                + "); }";
        safeExecuteScript(js, "setTheme");
    }

    public void showSpinner(String message) {
        var jsonMessage = toJson(message);
        var js = "if (window.brokk && window.brokk.showSpinner) { window.brokk.showSpinner(" + jsonMessage + "); }";
        safeExecuteScript(js, "showSpinner");
    }

    public void hideSpinner() {
        safeExecuteScript(
                "if (window.brokk && window.brokk.hideSpinner) { window.brokk.hideSpinner(); }", "hideSpinner");
    }

    public void clear() {
        safeExecuteScript("if (window.brokk && window.brokk.clear) { window.brokk.clear(); }", "clear");
    }

    public void refreshSymbolLookup() {
        safeExecuteScript(
                "if (window.brokk && window.brokk.refreshSymbolLookup) { window.brokk.refreshSymbolLookup(); }",
                "refreshSymbolLookup");
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
                        sendChunk(
                                currentText.toString(),
                                firstChunk.isNew(),
                                firstChunk.msgType(),
                                firstChunk.streaming(),
                                firstChunk.reasoning());
                        currentText.setLength(0);
                        firstChunk = chunk;
                    }
                    currentText.append(chunk.text());
                }
            }

            if (firstChunk != null) {
                sendChunk(
                        currentText.toString(),
                        firstChunk.isNew(),
                        firstChunk.msgType(),
                        firstChunk.streaming(),
                        firstChunk.reasoning());
            }
        } finally {
            pending.set(false);
            if (!eventQueue.isEmpty()) {
                scheduleSend();
            }
        }
    }

    private void sendChunk(String text, boolean isNew, ChatMessageType msgType, boolean streaming, boolean reasoning) {
        var e = epoch.incrementAndGet();
        var event = new BrokkEvent.Chunk(text, isNew, msgType, e, streaming, reasoning);
        sendEvent(event);
    }

    private void sendEvent(BrokkEvent event) {
        var e = event.getEpoch();
        if (e != null) {
            awaiting.put(e, new CompletableFuture<>());
        }
        var json = toJson(event);
        var js = "if (window.brokk && window.brokk.onEvent) { window.brokk.onEvent(" + json + "); }";
        safeExecuteScript(js, "sendEvent");
    }

    public void onAck(int e) {
        var p = awaiting.remove(e);
        if (p != null) {
            p.complete(null);
        }
    }

    public CompletableFuture<String> getSelection() {
        var script = "window.brokk && window.brokk.getSelection ? window.brokk.getSelection() : ''";
        return safeExecuteScriptWithResult(script, "getSelection")
                .thenApply(result -> result != null ? result.toString() : "");
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
            case "INFO" -> {
                logger.info("JS: {}", message);
                if (message.contains("CODE-LOGGER")) {
                    System.out.println("////// " + message);
                }
            }
            case "DEBUG" -> logger.debug("JS: {}", message);
            default -> {
                if (message.contains("[WORKER-ERROR]") || message.contains("[WORKER-REJECTION]")) {
                    logger.error("JS: {}", message);
                } else if (message.contains("CODE-LOGGER")) {
                    System.out.println("////// " + message);
                } else if (message.contains("ERROR") || message.contains("Error") || message.contains("error")) {
                    logger.warn("JS: {}", message);
                } else {
                    logger.debug("JS: {}", message);
                }
            }
        }
    }

    public void setProject(IProject project) {
        // Deprecated: use setContextManager instead
    }

    public void setContextManager(@Nullable IContextManager contextManager) {
        this.contextManager = contextManager;
    }

    public void setSymbolRightClickHandler(@Nullable io.github.jbellis.brokk.gui.Chrome chrome) {
        this.chrome = chrome;
    }

    public void setHostComponent(@Nullable java.awt.Component hostComponent) {
        this.hostComponent = hostComponent;
    }

    public void debugLog(String message) {
        System.out.println(" ------- [JS-DEBUG] " + message);
    }

    public String lookupSymbols(String symbolNamesJson) {
        logger.debug("Optimized symbol lookup requested with JSON: {}", symbolNamesJson);
        logger.debug("ContextManager available: {}", contextManager != null);

        if (contextManager != null) {
            var project = contextManager.getProject();
            logger.debug("Project: {}", project != null ? project.getRoot() : "null");
        }

        try {
            var symbolNames = MAPPER.readValue(symbolNamesJson, new TypeReference<Set<String>>() {});
            logger.debug("Parsed {} symbol names for lookup", symbolNames.size());

            var results = SymbolLookupService.lookupSymbolsOptimized(symbolNames, contextManager);
            var jsonResult = MAPPER.writeValueAsString(results);

            logger.debug(
                    "Optimized symbol lookup completed, returning {} found symbols out of {} requested",
                    results.size(),
                    symbolNames.size());
            return jsonResult;
        } catch (Exception e) {
            logger.warn("Error in symbol lookup", e);
            return "{}";
        }
    }

    public void onSymbolRightClick(String symbolName, boolean symbolExists, @Nullable String fqn, int x, int y) {
        logger.debug("Symbol right-clicked: {}, exists: {}, fqn: {} at ({}, {})", symbolName, symbolExists, fqn, x, y);

        SwingUtilities.invokeLater(() -> {
            var component = hostComponent != null
                    ? hostComponent
                    : java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                            .getFocusOwner();

            if (component != null && contextManager != null) {
                if (chrome != null) {
                    io.github.jbellis.brokk.gui.menu.ContextMenuBuilder.forSymbol(
                                    symbolName, symbolExists, fqn, chrome, (io.github.jbellis.brokk.ContextManager)
                                            contextManager)
                            .show(component, x, y);
                } else {
                    logger.warn("Symbol right-click handler not set, ignoring right-click on symbol: {}", symbolName);
                }
            }
        });
    }

    public void onProcessorStateChanged(String state) {
        logger.debug("Received processor state change notification: {}", state);
        webViewHost.onProcessorStateChanged(state);
    }

    public String getContextCacheId() {
        if (contextManager == null) {
            return "no-context";
        }

        var sb = new StringBuilder();

        // Project identity
        var project = contextManager.getProject();
        if (project != null) {
            sb.append("project:").append(project.getRoot().toString());
        } else {
            sb.append("project:null");
        }

        // Context manager identity
        sb.append("|cm:").append(System.identityHashCode(contextManager));
        sb.append("|cm-class:").append(contextManager.getClass().getSimpleName());

        // Analyzer identity
        try {
            var analyzer = contextManager.getAnalyzerUninterrupted();
            sb.append("|analyzer:").append(System.identityHashCode(analyzer));
            sb.append("|analyzer-class:").append(analyzer.getClass().getSimpleName());
        } catch (Exception e) {
            sb.append("|analyzer:unavailable");
        }

        // Git state (if available)
        try {
            if (project != null) {
                var repo = project.getRepo();
                sb.append("|branch:").append(repo.getCurrentBranch());
                sb.append("|commit:")
                        .append(repo.getCurrentCommitId()
                                .substring(
                                        0, Math.min(8, repo.getCurrentCommitId().length())));
                var modifiedFiles = repo.getModifiedFiles();
                sb.append("|dirty:").append(!modifiedFiles.isEmpty());
            }
        } catch (Exception e) {
            sb.append("|git:unavailable");
        }

        // Hash the context string for compact ID
        String contextString = sb.toString();
        return Integer.toHexString(contextString.hashCode());
    }

    private static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void shutdown() {
        xmit.shutdownNow();
    }
}
