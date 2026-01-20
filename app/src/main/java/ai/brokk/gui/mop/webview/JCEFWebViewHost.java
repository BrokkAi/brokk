package ai.brokk.gui.mop.webview;

import ai.brokk.ContextManager;
import ai.brokk.DependencyException;
import ai.brokk.TaskEntry;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.mop.ChunkMeta;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.mop.webview.cef.CefAppProvider;
import ai.brokk.gui.mop.webview.cef.CefAppProviderFactory;
import ai.brokk.gui.mop.webview.cef.MavenCefProvider;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.project.MainProject;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.handler.CefAppHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.Nullable;

/**
 * JCEF-based WebView host.
 *
 * <p>Automatically selects the appropriate CEF provider:
 * <ul>
 *   <li>JBR provider in production jDeploy builds</li>
 *   <li>jcefmaven provider in development</li>
 * </ul>
 */
public final class JCEFWebViewHost extends JPanel implements IWebViewHost {
    private static final Logger logger = LogManager.getLogger(JCEFWebViewHost.class);

    // CEF singleton - shared across all instances
    private static final AtomicReference<CefApp> cefAppRef = new AtomicReference<>();
    private static final Object cefInitLock = new Object();

    private @Nullable CefClient client;
    private @Nullable CefBrowser browser;
    private @Nullable JCEFBridge bridge;
    private final Queue<HostCommand> pendingCommands = new ConcurrentLinkedQueue<>();
    private volatile boolean bridgeReady = false;

    // Minimal command interface for buffering
    private sealed interface HostCommand {
        record Append(String text, ChatMessageType msgType, boolean streaming, ChunkMeta chunkMeta)
                implements HostCommand {}

        record Clear() implements HostCommand {}

        record StaticDocument(@Nullable String markdown) implements HostCommand {}

        record Theme(String themeName, boolean isDevMode, boolean wrapMode) implements HostCommand {}

        record HistoryReset() implements HostCommand {}

        record HistoryTask(TaskEntry entry) implements HostCommand {}

        record LiveSummary(int taskSequence, boolean compressed, String summary) implements HostCommand {}

        record ShowSpinner(String message) implements HostCommand {}

        record HideSpinner() implements HostCommand {}

        record ShowTransientMessage(String message) implements HostCommand {}

        record HideTransientMessage() implements HostCommand {}

        record SetTaskInProgress(boolean inProgress) implements HostCommand {}

        record ShowEmptyState(boolean show) implements HostCommand {}
    }

    public JCEFWebViewHost() {
        super(new BorderLayout());

        // Set initial background color to avoid white flash while loading
        String themeName = MainProject.getTheme();
        boolean isDark = !GuiTheme.isLightThemeName(themeName);
        var bgColor = ThemeColors.getColor(isDark, "chat_background");
        setOpaque(true);
        setBackground(bgColor);

        // Initialize directly - macOS with -XstartOnFirstThread needs main thread for AWT
        initializeCef();
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    /**
     * Returns the browser's UI component for coordinate-relative operations.
     * Used by JCEFBridge to show popup menus at correct positions.
     */
    @Nullable
    Component getBrowserUIComponent() {
        return browser != null ? browser.getUIComponent() : null;
    }

    private void initializeCef() {
        // Use async initialization to avoid blocking AWT thread while waiting for CefApp INITIALIZED state
        // This allows state callbacks (delivered on AWT) to fire properly
        getOrCreateCefAppAsync(this::createBrowserWithCefApp);
    }

    private void createBrowserWithCefApp(CefApp cefApp) {
        SwingUtilities.invokeLater(() -> {
            try {
                logger.info("CefApp ready (state: {}), creating browser", cefApp.getState());

                // Create per-instance client and bridge
                client = cefApp.createClient();
                bridge = new JCEFBridge(this);

                // Set up message router for JS→Java callbacks
                CefMessageRouter.CefMessageRouterConfig routerConfig = new CefMessageRouter.CefMessageRouterConfig();
                routerConfig.jsQueryFunction = "cefQuery";
                routerConfig.jsCancelFunction = "cefQueryCancel";
                CefMessageRouter messageRouter = CefMessageRouter.create(routerConfig);
                messageRouter.addHandler(bridge, true);
                client.addMessageRouter(messageRouter);

                // Add load handler to inject bridge script after page loads
                client.addLoadHandler(new CefLoadHandlerAdapter() {
                    @Override
                    public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                        if (frame.isMain()) {
                            logger.info("Page loaded with status {}, injecting bridge script", httpStatusCode);
                            injectBridgeScript(browser);
                        }
                    }

                    @Override
                    public void onLoadError(
                            CefBrowser browser,
                            CefFrame frame,
                            CefLoadHandler.ErrorCode errorCode,
                            String errorText,
                            String failedUrl) {
                        // ERR_ABORTED is common during navigation changes and usually not fatal
                        if (errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) {
                            logger.debug("Load aborted (non-fatal): {} for URL: {}", errorText, failedUrl);
                            return;
                        }
                        logger.error("Load error: {} - {} for URL: {}", errorCode, errorText, failedUrl);

                        // Retry once for connection errors (server may not be ready)
                        if (frame.isMain()
                                && (errorCode == CefLoadHandler.ErrorCode.ERR_CONNECTION_REFUSED
                                        || errorCode == CefLoadHandler.ErrorCode.ERR_CONNECTION_RESET)) {
                            logger.info("Retrying load after connection error...");
                            // Retry after a short delay
                            new Thread(() -> {
                                        try {
                                            Thread.sleep(500);
                                            browser.reload();
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                    })
                                    .start();
                        }
                    }
                });

                // Get ClasspathHttpServer port and create browser
                int port = ClasspathHttpServer.ensureStarted();
                String themeName = MainProject.getTheme();
                String url = "http://127.0.0.1:" + port + "/index.html?theme=" + themeName;
                logger.info("Creating JCEF browser for URL: {}", url);

                // Create browser directly with URL (like working jcef branch)
                @SuppressWarnings("deprecation")
                var createdBrowser = client.createBrowser(url, false, false);
                browser = createdBrowser;
                logger.debug("Browser created: {}", browser);

                // Get the UI component and add it
                var uiComponent = browser.getUIComponent();
                logger.debug("UI component: {}", uiComponent.getClass().getName());

                // Add browser directly - CEF requires component in displayable hierarchy to load
                // Brief white flash is unavoidable JCEF limitation (native window ignores Swing overlays)
                add(uiComponent, BorderLayout.CENTER);
                revalidate();
                repaint();

                logger.info("JCEF browser created successfully");

                // Initial theme - queue until bridge ready
                boolean isDevMode = Boolean.parseBoolean(System.getProperty("brokk.devmode", "false"));
                setInitialTheme(themeName, isDevMode, MainProject.getCodeBlockWrapMode());

            } catch (Exception e) {
                logger.error("Failed to initialize JCEF via JBR", e);
                System.err.println("*** JCEF initialization failed: " + e.getMessage() + " ***");
                e.printStackTrace(System.err);
                // Note: can't rethrow from invokeLater, just log
            }
        });
    }

    private static RuntimeException wrapWithDependencyHint(Throwable e) {
        // Check for missing dependencies across all platforms
        Throwable cause = e;
        while (cause != null) {
            var msg = cause.getMessage();
            if (msg == null) {
                cause = cause.getCause();
                continue;
            }

            // Check for missing system libraries (all platforms)
            if (cause instanceof UnsatisfiedLinkError) {
                // Linux: .so files
                if (msg.contains(".so:")) {
                    var libName = extractMissingLibrary(msg, ".so:");
                    return new DependencyException(linuxInstructions(libName), cause);
                }
                // macOS: .dylib files or jcef_helper
                if (msg.contains(".dylib") || msg.contains("jcef_helper")) {
                    return new DependencyException(macOsInstructions(msg), cause);
                }
                // Windows: .dll files
                if (msg.contains(".dll")) {
                    var libName = extractMissingLibrary(msg, ".dll");
                    return new DependencyException(windowsInstructions(libName), cause);
                }
            }

            // Check for missing JCEF binaries from JCefSetup
            if (cause instanceof IllegalStateException) {
                if (msg.contains("jcef Helper.app not found")) {
                    return new DependencyException(macOsBinaryMissing(), cause);
                }
                if (msg.contains("jcef.dll not found")) {
                    return new DependencyException(windowsBinaryMissing(), cause);
                }
                if (msg.contains("libjcef.so not found")) {
                    return new DependencyException(linuxBinaryMissing(), cause);
                }
            }

            cause = cause.getCause();
        }
        return new RuntimeException("JCEF initialization failed", e);
    }

    private static String linuxInstructions(String libName) {
        return """
                JCEF requires system libraries that are not installed.
                Missing: %s

                Install with:
                  Debian/Ubuntu: sudo apt install libnss3 libatk-bridge2.0-0 libgtk-3-0 libgbm1
                  Fedora/RHEL:   sudo dnf install nss atk gtk3 mesa-libgbm
                  Arch:          sudo pacman -S nss atk gtk3 mesa
                """
                .formatted(libName);
    }

    private static String macOsInstructions(String errorMsg) {
        return """
                JCEF requires system frameworks that are not available.
                Error: %s

                This may indicate:
                - Missing or corrupted Chromium framework files
                - Insufficient permissions to load frameworks
                - Incomplete JCEF installation

                Try reinstalling Brokk or check file permissions.
                """
                .formatted(errorMsg);
    }

    private static String windowsInstructions(String libName) {
        return """
                JCEF requires DLL files that are not installed.
                Missing: %s

                Install Visual C++ Redistributable:
                https://aka.ms/vs/17/release/vc_redist.x64.exe

                Or reinstall Brokk.
                """
                .formatted(libName);
    }

    private static String linuxBinaryMissing() {
        return """
                JCEF binary (libjcef.so) not found in expected location.

                This indicates an incomplete installation.
                Please reinstall Brokk or check the installation directory.
                """;
    }

    private static String macOsBinaryMissing() {
        return """
                JCEF Helper.app not found in expected location.

                This indicates an incomplete installation.
                Please reinstall Brokk or check the Frameworks directory.
                """;
    }

    private static String windowsBinaryMissing() {
        return """
                JCEF binary (jcef.dll) not found in expected location.

                This indicates an incomplete installation.
                Please reinstall Brokk or check the installation directory.
                """;
    }

    /**
     * Callback interface for deferred browser creation after CefApp is initialized.
     */
    private interface CefAppReadyCallback {
        void onCefAppReady(CefApp app);
    }

    /**
     * Gets or creates the shared CefApp instance, calling the callback when it's ready.
     * Uses CompletableFuture for race-free callback handling:
     * - If already complete, callback runs immediately
     * - If not complete, callback is queued and guaranteed to fire
     */
    private static void getOrCreateCefAppAsync(CefAppReadyCallback callback) {
        cefAppFuture.thenAccept(callback::onCefAppReady);
        ensureCefAppCreated();
    }

    private static void ensureCefAppCreated() {
        if (cefAppRef.get() != null) {
            return;
        }

        synchronized (cefInitLock) {
            if (cefAppRef.get() != null) {
                return;
            }

            CefAppProvider provider = CefAppProviderFactory.getProvider();
            logger.info("Initializing JCEF with {}", provider.getClass().getSimpleName());

            CefApp app;
            try {
                app = provider.createCefApp(new CefAppHandlerAdapter(null) {
                    @Override
                    public void stateHasChanged(CefApp.CefAppState state) {
                        logger.info("CefApp state changed: {}", state);
                        if (state == CefApp.CefAppState.INITIALIZED) {
                            var cefApp = cefAppRef.get();
                            if (cefApp != null) {
                                var version = cefApp.getVersion();
                                if (version != null) {
                                    logger.info("CEF version: {} (Chromium {})",
                                                version.getCefVersion(), version.getChromeVersion());
                                }
                                cefAppFuture.complete(cefApp);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                cefAppFuture.completeExceptionally(e);
                throw wrapWithDependencyHint(e);
            }
            cefAppRef.set(app);

            var currentState = app.getState();
            logger.debug("JCEF CefApp created (state: {})", currentState);

            // Complete future immediately for already-initialized or MavenCefProvider
            // (MavenCefProvider's build() blocks until ready internally)
            if (currentState == CefApp.CefAppState.INITIALIZED) {
                logger.info("CefApp already INITIALIZED, completing future");
                cefAppFuture.complete(app);
            } else if (provider instanceof MavenCefProvider) {
                logger.info("MavenCefProvider used, CefApp ready after build(), completing future");
                cefAppFuture.complete(app);
            } else {
                logger.info("CefApp not yet INITIALIZED, future will complete on state callback");
            }
        }
    }

    // CompletableFuture for race-free callback handling
    private static final CompletableFuture<CefApp> cefAppFuture = new CompletableFuture<>();

    private static String extractMissingLibrary(String errorMessage, String extension) {
        // Parse library name from error message (e.g., "libnss3.so: cannot open shared object file")
        int idx = errorMessage.lastIndexOf(extension);
        if (idx > 0) {
            int start = errorMessage.lastIndexOf(' ', idx);
            if (start < 0) start = 0;
            return errorMessage.substring(start, idx + extension.length()).trim();
        }
        return "unknown library";
    }

    /**
     * Loads a JavaScript resource file from the classpath.
     */
    private static String loadJavaScriptResource(String resourcePath) throws IOException {
        try (InputStream is = JCEFWebViewHost.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("JavaScript resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void injectBridgeScript(CefBrowser browser) {
        try {
            String bridgeScript = loadJavaScriptResource("mop-webview-scripts/jcef-bridge.js");
            browser.executeJavaScript(bridgeScript, browser.getURL(), 0);

            // Inject console interceptor to redirect console.* to jsLog (prevents JCEF stdout noise)
            String consoleInterceptor = loadJavaScriptResource("mop-webview-scripts/jcef-console-interceptor.js");
            browser.executeJavaScript(consoleInterceptor, browser.getURL(), 0);
        } catch (IOException e) {
            logger.error("Failed to load JCEF bridge script", e);
        }
    }

    // Called by JCEFBridge when JS signals bridge is ready
    void onBridgeReady() {
        logger.info("Bridge ready, flushing {} pending commands", pendingCommands.size());
        bridgeReady = true;
        flushPendingCommands();
    }

    private void flushPendingCommands() {
        if (!bridgeReady || browser == null || bridge == null) {
            return;
        }
        int count = 0;
        HostCommand cmd;
        while ((cmd = pendingCommands.poll()) != null) {
            count++;
            switch (cmd) {
                case HostCommand.Append a ->
                    bridge.sendAppend(browser, a.text(), a.msgType(), a.streaming(), a.chunkMeta());
                case HostCommand.Clear ignored -> bridge.sendClear(browser);
                case HostCommand.StaticDocument sd -> bridge.sendStaticDocument(browser, sd.markdown());
                case HostCommand.Theme t -> bridge.sendTheme(browser, t.themeName(), t.isDevMode(), t.wrapMode());
                case HostCommand.HistoryReset ignored -> bridge.sendHistoryReset(browser);
                case HostCommand.HistoryTask ht -> bridge.sendHistoryTask(browser, ht.entry());
                case HostCommand.LiveSummary ls ->
                    bridge.sendLiveSummary(browser, ls.taskSequence(), ls.compressed(), ls.summary());
                case HostCommand.ShowSpinner ss -> bridge.showSpinner(browser, ss.message());
                case HostCommand.HideSpinner ignored -> bridge.hideSpinner(browser);
                case HostCommand.ShowTransientMessage stm -> bridge.showTransientMessage(browser, stm.message());
                case HostCommand.HideTransientMessage ignored -> bridge.hideTransientMessage(browser);
                case HostCommand.SetTaskInProgress stp -> bridge.setTaskInProgress(browser, stp.inProgress());
                case HostCommand.ShowEmptyState ses -> bridge.setShowEmptyState(browser, ses.show());
            }
        }
        logger.info("Flushed {} pending commands", count);
    }

    @Override
    public void append(String text, ChatMessageType msgType, boolean streaming, ChunkMeta chunkMeta) {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.sendAppend(browser, text, msgType, streaming, chunkMeta);
        } else {
            pendingCommands.add(new HostCommand.Append(text, msgType, streaming, chunkMeta));
        }
    }

    @Override
    public void clear() {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.sendClear(browser);
        } else {
            pendingCommands.add(new HostCommand.Clear());
        }
    }

    @Override
    public void sendStaticDocument(@Nullable String markdown) {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.sendStaticDocument(browser, markdown);
        } else {
            pendingCommands.add(new HostCommand.StaticDocument(markdown));
        }
    }

    @Override
    public void setShowEmptyState(boolean show) {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.setShowEmptyState(browser, show);
        } else {
            pendingCommands.add(new HostCommand.ShowEmptyState(show));
        }
    }

    @Override
    public void dispose() {
        logger.info("Disposing JCEF browser");
        if (browser != null) {
            browser.close(true);
            browser = null;
        }
        if (client != null) {
            client.dispose();
            client = null;
        }
        bridge = null;
        // Note: CefApp is shared singleton, don't dispose it here
    }

    @Override
    public void historyReset() {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.sendHistoryReset(browser);
        } else {
            pendingCommands.add(new HostCommand.HistoryReset());
        }
    }

    @Override
    public void historyTask(TaskEntry entry) {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.sendHistoryTask(browser, entry);
        } else {
            pendingCommands.add(new HostCommand.HistoryTask(entry));
        }
    }

    @Override
    public void showSpinner(String message) {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.showSpinner(browser, message);
        } else {
            pendingCommands.add(new HostCommand.ShowSpinner(message));
        }
    }

    @Override
    public void hideSpinner() {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.hideSpinner(browser);
        } else {
            pendingCommands.add(new HostCommand.HideSpinner());
        }
    }

    @Override
    public void showTransientMessage(String message) {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.showTransientMessage(browser, message);
        } else {
            pendingCommands.add(new HostCommand.ShowTransientMessage(message));
        }
    }

    @Override
    public void hideTransientMessage() {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.hideTransientMessage(browser);
        } else {
            pendingCommands.add(new HostCommand.HideTransientMessage());
        }
    }

    @Override
    public void setTaskInProgress(boolean inProgress) {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.setTaskInProgress(browser, inProgress);
        } else {
            pendingCommands.add(new HostCommand.SetTaskInProgress(inProgress));
        }
    }

    @Override
    public void sendEnvironmentInfo(boolean analyzerReady) {
        if (browser != null && bridge != null) {
            bridge.sendEnvironmentInfo(browser, analyzerReady);
        } else {
            logger.debug("sendEnvironmentInfo ignored; bridge not ready");
        }
    }

    @Override
    public void sendLiveSummary(int taskSequence, boolean compressed, String summary) {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.sendLiveSummary(browser, taskSequence, compressed, summary);
        } else {
            pendingCommands.add(new HostCommand.LiveSummary(taskSequence, compressed, summary));
        }
    }

    @Override
    public void setInitialTheme(String themeName, boolean isDevMode, boolean wrapMode) {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.sendTheme(browser, themeName, isDevMode, wrapMode);
        } else {
            pendingCommands.add(new HostCommand.Theme(themeName, isDevMode, wrapMode));
        }
    }

    @Override
    public void setRuntimeTheme(String themeName, boolean isDevMode, boolean wrapMode) {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.sendTheme(browser, themeName, isDevMode, wrapMode);
        } else {
            pendingCommands.add(new HostCommand.Theme(themeName, isDevMode, wrapMode));
        }
    }

    /**
     * @deprecated Not implemented in JCEF backend; kept for API compatibility.
     */
    @Deprecated
    @Override
    public CompletableFuture<Void> flushAsync() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * @deprecated Not implemented in JCEF backend; kept for API compatibility.
     */
    @Deprecated
    @Override
    public CompletableFuture<String> getSelectedText() {
        return CompletableFuture.completedFuture("");
    }

    @Override
    public void setZoom(double zoom) {
        if (browser != null && bridge != null) {
            bridge.setZoom(browser, zoom);
        } else {
            logger.debug("setZoom ignored; bridge not ready");
        }
    }

    @Override
    public void zoomIn() {
        if (browser != null && bridge != null) {
            bridge.zoomIn(browser);
        } else {
            logger.debug("zoomIn ignored; bridge not ready");
        }
    }

    @Override
    public void zoomOut() {
        if (browser != null && bridge != null) {
            bridge.zoomOut(browser);
        } else {
            logger.debug("zoomOut ignored; bridge not ready");
        }
    }

    @Override
    public void resetZoom() {
        if (browser != null && bridge != null) {
            bridge.resetZoom(browser);
        } else {
            logger.debug("resetZoom ignored; bridge not ready");
        }
    }

    @Override
    public void setSearch(String query, boolean caseSensitive) {
        if (browser != null && bridge != null) {
            bridge.setSearch(browser, query, caseSensitive);
        } else {
            logger.debug("setSearch ignored; bridge not ready");
        }
    }

    @Override
    public void clearSearch() {
        if (browser != null && bridge != null) {
            bridge.clearSearch(browser);
        } else {
            logger.debug("clearSearch ignored; bridge not ready");
        }
    }

    @Override
    public void nextMatch() {
        if (browser != null && bridge != null) {
            bridge.nextMatch(browser);
        } else {
            logger.debug("nextMatch ignored; bridge not ready");
        }
    }

    @Override
    public void prevMatch() {
        if (browser != null && bridge != null) {
            bridge.prevMatch(browser);
        } else {
            logger.debug("prevMatch ignored; bridge not ready");
        }
    }

    @Override
    public void scrollToCurrent() {
        if (browser != null && bridge != null) {
            bridge.scrollToCurrent(browser);
        } else {
            logger.debug("scrollToCurrent ignored; bridge not ready");
        }
    }

    @Override
    public void onAnalyzerReadyResponse(String contextId) {
        if (browser != null && bridge != null) {
            bridge.onAnalyzerReadyResponse(browser, contextId);
        } else {
            logger.debug("onAnalyzerReadyResponse ignored; bridge not ready for context: {}", contextId);
        }
    }

    @Override
    public String getContextCacheId() {
        return "jcef-jbr";
    }

    @Override
    public void addSearchStateListener(Consumer<IWebViewHost.SearchState> listener) {
        if (bridge != null) {
            bridge.addSearchStateListener(listener);
        }
    }

    @Override
    public void removeSearchStateListener(Consumer<IWebViewHost.SearchState> listener) {
        if (bridge != null) {
            bridge.removeSearchStateListener(listener);
        }
    }

    @Override
    public void setContextManager(@Nullable ContextManager contextManager) {
        if (bridge != null) {
            bridge.setContextManager(contextManager);
        }
    }

    @Override
    public void setSymbolRightClickHandler(@Nullable Chrome chrome) {
        if (bridge != null) {
            bridge.setChrome(chrome);
        }
    }

    @Override
    public void setHostComponent(@Nullable Component hostComponent) {
        if (bridge != null) {
            bridge.setHostComponent(hostComponent);
        }
    }
}
