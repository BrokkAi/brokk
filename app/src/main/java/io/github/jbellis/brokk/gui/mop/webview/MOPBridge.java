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
        var js = "window.brokk.setSearch(" + toJson(query) + ", " + caseSensitive + ")";
        Platform.runLater(() -> engine.executeScript(js));
    }

    public void clearSearch() {
        Platform.runLater(() -> engine.executeScript("window.brokk.clearSearch()"));
    }

    public void nextMatch() {
        Platform.runLater(() -> engine.executeScript("window.brokk.nextMatch()"));
    }

    public void prevMatch() {
        Platform.runLater(() -> engine.executeScript("window.brokk.prevMatch()"));
    }

    public void scrollToCurrent() {
        Platform.runLater(() -> engine.executeScript("window.brokk.scrollToCurrent()"));
    }

    public void append(String text, boolean isNew, ChatMessageType msgType, boolean streaming, boolean reasoning) {
        if (text.isEmpty()) {
            return;
        }
        // Epoch is assigned later, just queue the content
        eventQueue.add(new BrokkEvent.Chunk(text, isNew, msgType, -1, streaming, reasoning));
        scheduleSend();
    }

    public void setTheme(boolean isDark) {
        Platform.runLater(() -> engine.executeScript("window.brokk.setTheme(" + isDark + ")"));
    }

    public void showSpinner(String message) {
        var jsonMessage = toJson(message);
        Platform.runLater(() -> engine.executeScript("window.brokk.showSpinner(" + jsonMessage + ")"));
    }

    public void hideSpinner() {
        Platform.runLater(() -> engine.executeScript("window.brokk.hideSpinner()"));
    }

    public void clear() {
        Platform.runLater(() -> engine.executeScript("window.brokk.clear()"));
    }

    public void refreshSymbolLookup() {
        Platform.runLater(() -> engine.executeScript("window.brokk.refreshSymbolLookup()"));
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
        Platform.runLater(() -> engine.executeScript("window.brokk.onEvent(" + json + ")"));
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
                Object result = engine.executeScript("window.brokk.getSelection()");
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
            default -> {
                if (message.contains("[WORKER-ERROR]") || message.contains("[WORKER-REJECTION]")) {
                    logger.error("JS: {}", message);
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
        logger.debug("Symbol lookup requested with JSON: {}", symbolNamesJson);
        logger.debug("ContextManager available: {}", contextManager != null);

        if (contextManager != null) {
            var project = contextManager.getProject();
            logger.debug("Project: {}", project != null ? project.getRoot() : "null");
        }

        try {
            var symbolNames = MAPPER.readValue(symbolNamesJson, new TypeReference<Set<String>>() {});
            logger.debug("Parsed {} symbol names for lookup", symbolNames.size());

            var results = SymbolLookupService.lookupSymbols(symbolNames, contextManager);
            var jsonResult = MAPPER.writeValueAsString(results);

            logger.debug("Symbol lookup completed, returning {} results", results.size());
            return jsonResult;
        } catch (Exception e) {
            logger.warn("Error in symbol lookup", e);
            return "{}";
        }
    }

    public void onSymbolRightClick(String symbolName, boolean symbolExists, int x, int y) {
        logger.debug("Symbol right-clicked: {}, exists: {} at ({}, {})", symbolName, symbolExists, x, y);

        SwingUtilities.invokeLater(() -> {
            var component = hostComponent != null ? hostComponent
                    : java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();

            if (component != null && contextManager != null) {
                if (chrome != null) {
                    io.github.jbellis.brokk.gui.menu.ContextMenuBuilder.forSymbol(
                                    symbolName, symbolExists, chrome, (io.github.jbellis.brokk.ContextManager)
                                            contextManager)
                            .show(component, x, y);
                } else {
                    logger.warn("Symbol right-click handler not set, ignoring right-click on symbol: {}", symbolName);
                }
            }
        });
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
