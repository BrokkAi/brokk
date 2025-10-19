package io.github.jbellis.brokk.gui.mop.webview;

import static java.util.Objects.requireNonNull;

import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.util.Environment;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javax.swing.*;
import netscape.javascript.JSObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class MOPWebViewHost extends JPanel {
    private static final Logger logger = LogManager.getLogger(MOPWebViewHost.class);

    @Nullable
    private JFXPanel fxPanel;

    private final AtomicReference<MOPBridge> bridgeRef = new AtomicReference<>();
    private final AtomicReference<WebView> webViewRef = new AtomicReference<>();
    private final java.util.List<HostCommand> pendingCommands = new CopyOnWriteArrayList<>();
    private final List<Consumer<MOPBridge.SearchState>> searchListeners = new CopyOnWriteArrayList<>();
    private volatile @Nullable ContextManager contextManager;
    private volatile @Nullable io.github.jbellis.brokk.gui.Chrome chrome;

    // Bridge readiness tracking
    private final CompletableFuture<Void> bridgeReadyFuture = new CompletableFuture<>();
    private volatile boolean bridgeInitialized = false;

    // Theme configuration as a record for DRY principle
    private record Theme(boolean isDark, Color awtBg, javafx.scene.paint.Color fxBg, String cssColor) {
        static Theme create(boolean isDark) {
            var bgColor = io.github.jbellis.brokk.gui.mop.ThemeColors.getColor(isDark, "chat_background");
            var fxColor = javafx.scene.paint.Color.rgb(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue());
            var cssHex = String.format("#%02x%02x%02x", bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue());
            return new Theme(isDark, bgColor, fxColor, cssHex);
        }
    }

    // Represents commands to be sent to the bridge; buffered until bridge is ready
    private sealed interface HostCommand {
        record Append(String text, boolean isNew, ChatMessageType msgType, boolean streaming, boolean reasoning)
                implements HostCommand {}

        record SetTheme(String themeName, boolean isDevMode, boolean wrapMode, double zoom) implements HostCommand {}

        record SetZoom(double zoom) implements HostCommand {}

        record ShowSpinner(String message) implements HostCommand {}

        record HideSpinner() implements HostCommand {}

        record SetTaskInProgress(boolean inProgress) implements HostCommand {}

        record Clear() implements HostCommand {}

        record HistoryReset() implements HostCommand {}

        record HistoryTask(TaskEntry entry) implements HostCommand {}
    }

    public MOPWebViewHost() {
        super(new BorderLayout());

        // Defer JFXPanel creation to avoid EDT event pumping during construction
        SwingUtilities.invokeLater(this::initializeFxPanel);
    }

    /**
     * Determines the appropriate scroll speed factor based on the current platform. Different operating systems have
     * different scroll sensitivities.
     */
    private static double getPlatformScrollSpeedFactor() {
        if (Environment.isMacOs()) {
            return 0.3; // macOS trackpads are very sensitive
        } else {
            // Windows, Linux, and other platforms
            return 1.7;
        }
    }

    private void initializeFxPanel() {
        fxPanel = new JFXPanel();
        fxPanel.setVisible(false); // Start hidden to prevent flicker
        add(fxPanel, BorderLayout.CENTER);
        revalidate();
        repaint();

        Platform.runLater(() -> {
            var view = new WebView();
            view.setContextMenuEnabled(false);

            // Set unique user data directory per process to avoid DirectoryLock conflicts
            // when multiple brokk instances are running simultaneously
            // First get the default user data directory that JavaFX would use
            File defaultUserDataDir = view.getEngine().getUserDataDirectory();
            Path userDataDir;
            if (defaultUserDataDir != null) {
                // Append PID to the default directory name
                Path defaultPath = defaultUserDataDir.toPath();
                userDataDir = defaultPath.resolveSibling(defaultPath.getFileName() + "-"
                        + ProcessHandle.current().pid());
            } else {
                // Fallback: use OS-appropriate cache directory if default is null
                Path cacheDir;
                if (Environment.isMacOs()) {
                    cacheDir = Environment.getHomePath().resolve("Library/Caches");
                } else if (Environment.isWindows()) {
                    String localAppData = System.getenv("LOCALAPPDATA");
                    cacheDir = localAppData != null
                            ? Path.of(localAppData)
                            : Environment.getHomePath().resolve("AppData/Local");
                } else {
                    // Linux/Unix: follow XDG Base Directory spec
                    String xdgCache = System.getenv("XDG_CACHE_HOME");
                    cacheDir = xdgCache != null
                            ? Path.of(xdgCache)
                            : Environment.getHomePath().resolve(".cache");
                }
                userDataDir = cacheDir.resolve(
                        "brokk/webview-" + ProcessHandle.current().pid());
            }
            try {
                Files.createDirectories(userDataDir);
                view.getEngine().setUserDataDirectory(userDataDir.toFile());
                logger.debug("Set WebView user data directory to: {}", userDataDir);
            } catch (Exception e) {
                logger.warn(
                        "Failed to create WebView user data directory: {}, falling back to default", userDataDir, e);
            }

            webViewRef.set(view); // Store reference for later theme updates
            var scene = new Scene(view);
            requireNonNull(fxPanel).setScene(scene);
            var bridge = new MOPBridge(view.getEngine());
            if (contextManager != null) {
                bridge.setContextManager(contextManager);
            }
            if (chrome != null) {
                bridge.setChrome(chrome);
            }
            bridge.setHostComponent(fxPanel);
            bridgeRef.set(bridge);

            // Add JavaScript error handling
            view.getEngine().setOnError(errorEvent -> {
                logger.error("WebView JavaScript Error: {}", errorEvent.getMessage(), errorEvent.getException());
            });

            // Log page loading errors
            view.getEngine().getLoadWorker().exceptionProperty().addListener((obs, oldException, newException) -> {
                if (newException != null) {
                    logger.error("WebView Load Error: {}", newException.getMessage(), newException);
                }
            });

            // Expose Java object to JS after the page loads
            view.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                    var window = (JSObject) view.getEngine().executeScript("window");
                    window.setMember("javaBridge", bridge);

                    for (var l : searchListeners) {
                        bridge.addSearchStateListener(l);
                    }
                    // Inject JavaScript to intercept console methods and forward to Java bridge with stack traces
                    view.getEngine()
                            .executeScript(
                                    """
                    (function() {
                    var originalLog = console.log;
                    var originalError = console.error;
                    var originalWarn = console.warn;
                    var originalInfo = console.info;

                    function toStringWithStack(arg) {
                        return (arg && typeof arg === 'object' && 'stack' in arg) ? arg.stack : String(arg);
                    }

                    console.log = function() {
                        var msg = Array.from(arguments).map(toStringWithStack).join(' ');
                        if (window.javaBridge) window.javaBridge.jsLog('INFO', msg);
                        originalLog.apply(console, arguments);
                    };
                    console.error = function() {
                        var msg = Array.from(arguments).map(toStringWithStack).join(' ');
                        if (window.javaBridge) window.javaBridge.jsLog('ERROR', msg);
                        originalError.apply(console, arguments);
                    };
                    console.warn = function() {
                        var msg = Array.from(arguments).map(toStringWithStack).join(' ');
                        if (window.javaBridge) window.javaBridge.jsLog('WARN', msg);
                        originalWarn.apply(console, arguments);
                    };
                    console.info = function() {
                        var msg = Array.from(arguments).map(toStringWithStack).join(' ');
                        if (window.javaBridge) window.javaBridge.jsLog('INFO', msg);
                        originalInfo.apply(console, arguments);
                    };
                })();
                """); // Install wheel event override for platform-specific scroll speed
                    var wheelOverrideJs = String.format(
                            Locale.US,
                            """
                        (function() {
                            try {
                                // Platform-specific scroll behavior configuration
                                var scrollSpeedFactor = %f;         // Platform-specific scroll speed factor
                                var minScrollThreshold = 0.5;       // Minimum delta to process (prevents jitter)
                                var smoothingFactor = 0.8;          // Smoothing for very small movements

                                var smoothScrolls = new Map(); // Track ongoing smooth scrolls per element
                                var momentum = new Map();      // Track momentum per element

                                function findScrollable(el) {
                                    while (el && el !== document.body && el !== document.documentElement) {
                                        var style = getComputedStyle(el);
                                        var canScrollY = (style.overflowY === 'auto' || style.overflowY === 'scroll') && el.scrollHeight > el.clientHeight;
                                        var canScrollX = (style.overflowX === 'auto' || style.overflowX === 'scroll') && el.scrollWidth > el.clientWidth;
                                        if (canScrollY || canScrollX) return el;
                                        el = el.parentElement;
                                    }
                                    return document.scrollingElement || document.documentElement || document.body;
                                }

                                function smoothScroll(element, targetX, targetY, duration) {
                                    duration = duration || animationDuration;
                                    var startX = element.scrollLeft;
                                    var startY = element.scrollTop;
                                    var deltaX = targetX - startX;
                                    var deltaY = targetY - startY;
                                    var startTime = performance.now();

                                    // Cancel any existing smooth scroll for this element
                                    var existing = smoothScrolls.get(element);
                                    if (existing) {
                                        cancelAnimationFrame(existing);
                                    }

                                    function animate(currentTime) {
                                        var elapsed = currentTime - startTime;
                                        var progress = Math.min(elapsed / duration, 1);

                                        // Ease out cubic for smooth deceleration
                                        var eased = 1 - Math.pow(1 - progress, 3);

                                        element.scrollLeft = startX + deltaX * eased;
                                        element.scrollTop = startY + deltaY * eased;

                                        if (progress < 1) {
                                            var animId = requestAnimationFrame(animate);
                                            smoothScrolls.set(element, animId);
                                        } else {
                                            smoothScrolls.delete(element);
                                        }
                                    }

                                    var animId = requestAnimationFrame(animate);
                                    smoothScrolls.set(element, animId);
                                }

                                window.addEventListener('wheel', function(ev) {
                                    if (ev.ctrlKey || ev.metaKey) { return; } // let zoom gestures pass
                                    var target = findScrollable(ev.target);
                                    if (!target) return;

                                    ev.preventDefault();

                                    var dx = ev.deltaX * scrollSpeedFactor;
                                    var dy = ev.deltaY * scrollSpeedFactor;

                                    // Filter out very small deltas to prevent jitter
                                    if (Math.abs(dx) < minScrollThreshold) dx = 0;
                                    if (Math.abs(dy) < minScrollThreshold) dy = 0;

                                    // Apply scroll immediately with rounding to prevent sub-pixel issues
                                    if (dx) {
                                        var newScrollLeft = target.scrollLeft + Math.round(dx);
                                        var maxScrollLeft = target.scrollWidth - target.clientWidth;
                                        target.scrollLeft = Math.max(0, Math.min(newScrollLeft, maxScrollLeft));
                                    }
                                    if (dy) {
                                        var newScrollTop = target.scrollTop + Math.round(dy);
                                        var maxScrollTop = target.scrollHeight - target.clientHeight;
                                        target.scrollTop = Math.max(0, Math.min(newScrollTop, maxScrollTop));
                                    }
                                }, { passive: false, capture: true });
                            } catch (e) {
                                if (window.javaBridge) window.javaBridge.jsLog('ERROR', 'wheel override failed: ' + e);
                            }
                        })();
                        """,
                            getPlatformScrollSpeedFactor());
                    view.getEngine().executeScript(wheelOverrideJs);
                    // Now that the page is loaded, flush any buffered commands
                    flushBufferedCommands();
                    // Show the panel only after the page is fully loaded
                    // SwingUtilities.invokeLater(() -> requireNonNull(fxPanel).setVisible(true));
                } else if (newState == javafx.concurrent.Worker.State.FAILED) {
                    logger.error("WebView Page Load Failed");
                    // Show the panel even on failure to display any error content
                    // SwingUtilities.invokeLater(() -> requireNonNull(fxPanel).setVisible(true));
                }
            });

            var resourceUrl = getClass().getResource("/mop-web/index.html");
            if (resourceUrl == null) {
                view.getEngine()
                        .loadContent(
                                "<html><body><h1>Error: mop-web/index.html not found</h1></body></html>", "text/html");
            } else {
                int port = ClasspathHttpServer.ensureStarted();
                var url = "http://127.0.0.1:" + port + "/index.html";
                logger.info("Loading WebView content from embedded server: {}", url);
                view.getEngine().load(url);
            }
            // Initial theme — queue until bridge ready
            boolean isDevMode = Boolean.parseBoolean(System.getProperty("brokk.devmode", "false"));
            String themeName = MainProject.getTheme();
            setInitialTheme(themeName, isDevMode, MainProject.getCodeBlockWrapMode());
            SwingUtilities.invokeLater(() -> requireNonNull(fxPanel).setVisible(true));
        });
    }

    public void append(
            String text, boolean isNewMessage, ChatMessageType msgType, boolean streaming, boolean reasoning) {
        sendOrQueue(
                new HostCommand.Append(text, isNewMessage, msgType, streaming, reasoning),
                bridge -> bridge.append(text, isNewMessage, msgType, streaming, reasoning));
    }

    /**
     * Initial theme setup used while the WebView is still boot-strapping. The command is queued until the JS bridge
     * reports it is ready.
     */
    public void setInitialTheme(String themeName, boolean isDevMode, boolean wrapMode) {
        boolean isDark = !io.github.jbellis.brokk.gui.GuiTheme.THEME_LIGHT.equals(themeName);
        double zoom = MainProject.getMopZoom();
        sendOrQueue(
                new HostCommand.SetTheme(themeName, isDevMode, wrapMode, zoom),
                bridge -> bridge.setTheme(themeName, isDevMode, wrapMode, zoom));
        applyTheme(Theme.create(isDark));
    }

    /**
     * Runtime theme switch triggered from the settings panel. Executes immediately if the bridge exists; otherwise the
     * command is queued so that the frontend is updated once the bridge appears.
     */
    public void setRuntimeTheme(String themeName, boolean isDevMode, boolean wrapMode) {
        boolean isDark = !io.github.jbellis.brokk.gui.GuiTheme.THEME_LIGHT.equals(themeName);
        double zoom = MainProject.getMopZoom();
        var bridge = bridgeRef.get();
        if (bridge != null) {
            bridge.setTheme(themeName, isDevMode, wrapMode, zoom);
        } else {
            pendingCommands.add(new HostCommand.SetTheme(themeName, isDevMode, wrapMode, zoom));
        }
        applyTheme(Theme.create(isDark));
    }

    private void applyTheme(Theme theme) {
        // Update Swing component on EDT
        if (fxPanel != null) {
            SwingUtilities.invokeLater(() -> requireNonNull(fxPanel).setBackground(theme.awtBg()));
        }
        // Update JavaFX components on JavaFX thread
        var webView = webViewRef.get();
        if (webView != null) {
            Platform.runLater(() -> {
                // Update scene fill
                var scene = webView.getScene();
                if (scene != null) {
                    scene.setFill(theme.fxBg());
                }
                // Update UA stylesheet with custom property for chat background
                String css =
                        """
                    :root {
                        --chat-background: %s;
                    }
                    html, body {
                        background-color: var(--chat-background) !important;
                    }
                    """
                                .formatted(theme.cssColor());
                String encodedCss = java.net.URLEncoder.encode(css, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20");
                String dataCssUrl = "data:text/css," + encodedCss + "#t=" + System.currentTimeMillis();
                webView.getEngine().setUserStyleSheetLocation(dataCssUrl);
            });
        }
    }

    public void clear() {
        sendOrQueue(new HostCommand.Clear(), MOPBridge::clear);
    }

    public void historyReset() {
        sendOrQueue(new HostCommand.HistoryReset(), MOPBridge::sendHistoryReset);
    }

    public void historyTask(TaskEntry entry) {
        sendOrQueue(new HostCommand.HistoryTask(entry), bridge -> bridge.sendHistoryTask(entry));
    }

    public void showSpinner(String message) {
        sendOrQueue(new HostCommand.ShowSpinner(message), bridge -> bridge.showSpinner(message));
    }

    public void hideSpinner() {
        sendOrQueue(new HostCommand.HideSpinner(), bridge -> bridge.hideSpinner());
    }

    public void setTaskInProgress(boolean inProgress) {
        sendOrQueue(new HostCommand.SetTaskInProgress(inProgress), bridge -> bridge.setTaskInProgress(inProgress));
    }

    /**
     * Push a fresh snapshot of environment information to the WebView.
     *
     * @param analyzerReady whether the analyzer is ready
     */
    public void sendEnvironmentInfo(boolean analyzerReady) {
        var bridge = bridgeRef.get();
        if (bridge == null) {
            logger.debug("sendEnvironmentInfo ignored; bridge not ready");
            return;
        }
        bridge.sendEnvironmentInfo(analyzerReady);
    }

    public void addSearchStateListener(Consumer<MOPBridge.SearchState> l) {
        searchListeners.add(l);
        var bridge = bridgeRef.get();
        if (bridge != null) {
            bridge.addSearchStateListener(l);
        }
    }

    public void removeSearchStateListener(Consumer<MOPBridge.SearchState> l) {
        searchListeners.remove(l);
        var bridge = bridgeRef.get();
        if (bridge != null) {
            bridge.removeSearchStateListener(l);
        }
    }

    public void setSearch(String query, boolean caseSensitive) {
        var bridge = bridgeRef.get();
        if (bridge == null) {
            logger.debug("setSearch ignored; bridge not ready");
            return;
        }
        bridge.setSearch(query, caseSensitive);
    }

    public void clearSearch() {
        var bridge = bridgeRef.get();
        if (bridge == null) {
            logger.debug("clearSearch ignored; bridge not ready");
            return;
        }
        bridge.clearSearch();
    }

    public void nextMatch() {
        var bridge = bridgeRef.get();
        if (bridge == null) {
            logger.debug("nextMatch ignored; bridge not ready");
            return;
        }
        bridge.nextMatch();
    }

    public void prevMatch() {
        var bridge = bridgeRef.get();
        if (bridge == null) {
            logger.debug("prevMatch ignored; bridge not ready");
            return;
        }
        bridge.prevMatch();
    }

    public void scrollToCurrent() {
        var bridge = bridgeRef.get();
        if (bridge == null) {
            logger.debug("scrollToCurrent ignored; bridge not ready");
            return;
        }
        bridge.scrollToCurrent();
    }

    public void zoomIn() {
        var bridge = bridgeRef.get();
        if (bridge == null) {
            logger.debug("zoomIn ignored; bridge not ready");
            return;
        }
        bridge.zoomIn();
    }

    public void zoomOut() {
        var bridge = bridgeRef.get();
        if (bridge == null) {
            logger.debug("zoomOut ignored; bridge not ready");
            return;
        }
        bridge.zoomOut();
    }

    public void resetZoom() {
        var bridge = bridgeRef.get();
        if (bridge == null) {
            logger.debug("resetZoom ignored; bridge not ready");
            return;
        }
        bridge.resetZoom();
    }

    public void setZoom(double zoom) {
        sendOrQueue(new HostCommand.SetZoom(zoom), bridge -> bridge.setZoom(zoom));
    }

    public void onAnalyzerReadyResponse(String contextId) {
        var bridge = bridgeRef.get();
        if (bridge == null) {
            logger.debug("onAnalyzerReady ignored; bridge not ready for context: {}", contextId);
            return;
        }
        bridge.onAnalyzerReadyResponse(contextId);
    }

    public String getContextCacheId() {
        var bridge = bridgeRef.get();
        if (bridge == null) {
            logger.debug("getContextCacheId ignored; bridge not ready");
            return "no-bridge";
        }
        return bridge.getContextCacheId();
    }

    private void sendOrQueue(HostCommand command, java.util.function.Consumer<MOPBridge> action) {
        var bridge = bridgeRef.get();

        // Only SetTheme commands need to wait for bridge ready; other commands can execute once bridge exists
        boolean needsBridgeReady = command instanceof HostCommand.SetTheme;
        boolean canExecute = bridge != null && (!needsBridgeReady || bridgeInitialized);

        if (!canExecute) {
            pendingCommands.add(command);
            logger.debug("Buffered command, waiting for bridge ready: {}", command);
        } else {
            action.accept(bridge);
        }
    }

    public CompletableFuture<Void> flushAsync() {
        var bridge = bridgeRef.get();
        if (bridge != null) {
            return bridge.flushAsync();
        }
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<String> getSelectedText() {
        var bridge = bridgeRef.get();
        if (bridge != null) {
            return bridge.getSelection();
        }
        return CompletableFuture.completedFuture("");
    }

    private void flushBufferedCommands() {
        if (!pendingCommands.isEmpty()) {
            var bridge = requireNonNull(bridgeRef.get());
            logger.info("Flushing {} buffered commands", pendingCommands.size());
            pendingCommands.forEach(command -> {
                switch (command) {
                    case HostCommand.Append a ->
                        bridge.append(a.text(), a.isNew(), a.msgType(), a.streaming(), a.reasoning());
                    case HostCommand.SetTheme t ->
                        bridge.setTheme(t.themeName(), t.isDevMode(), t.wrapMode(), t.zoom());
                    case HostCommand.SetZoom z -> bridge.setZoom(z.zoom());
                    case HostCommand.ShowSpinner s -> bridge.showSpinner(s.message());
                    case HostCommand.HideSpinner ignored -> bridge.hideSpinner();
                    case HostCommand.SetTaskInProgress stp -> bridge.setTaskInProgress(stp.inProgress());
                    case HostCommand.Clear ignored -> bridge.clear();
                    case HostCommand.HistoryReset ignored -> bridge.sendHistoryReset();
                    case HostCommand.HistoryTask ht -> bridge.sendHistoryTask(ht.entry());
                }
            });
            pendingCommands.clear();
        }
    }

    public void onBridgeReady() {
        bridgeInitialized = true;
        bridgeReadyFuture.complete(null);
        flushBufferedCommands();
    }

    public void setContextManager(@Nullable ContextManager contextManager) {
        this.contextManager = contextManager;
        var bridge = bridgeRef.get();
        if (bridge != null) {
            bridge.setContextManager(contextManager);
        }
    }

    public void setSymbolRightClickHandler(@Nullable io.github.jbellis.brokk.gui.Chrome chrome) {
        this.chrome = chrome;
        var bridge = bridgeRef.get();
        if (bridge != null) {
            bridge.setChrome(chrome);
        }
    }

    public void setHostComponent(@Nullable java.awt.Component hostComponent) {
        var bridge = bridgeRef.get();
        if (bridge != null) {
            bridge.setHostComponent(hostComponent);
        }
    }

    public void dispose() {
        var bridge = bridgeRef.getAndSet(null);
        if (bridge != null) {
            bridge.shutdown();
        }
        webViewRef.set(null);
        Platform.runLater(() -> {
            if (fxPanel != null
                    && fxPanel.getScene() != null
                    && fxPanel.getScene().getRoot() instanceof WebView webView) {
                webView.getEngine().load(null); // release memory
            }
        });
        // Note: ClasspathHttpServer shutdown is handled at application level, not per WebView instance
    }
}
