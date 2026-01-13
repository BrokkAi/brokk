package ai.brokk.gui.mop.webview;

import ai.brokk.ContextManager;
import ai.brokk.DependencyException;
import ai.brokk.TaskEntry;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.project.MainProject;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.Nullable;

/**
 * JCEF-based WebView host using jcefmaven.
 * Uses jcefmaven library which automatically downloads and manages JCEF binaries.
 *
 */
public final class JCEFWebViewHost extends JPanel implements IWebViewHost {
    private static final Logger logger = LogManager.getLogger(JCEFWebViewHost.class);

    // CEF singleton - shared across all instances
    private static final AtomicReference<CefApp> cefAppRef = new AtomicReference<>();
    private static final Object cefInitLock = new Object();

    private @Nullable CefClient client;
    private @Nullable CefBrowser browser;
    private @Nullable JCEFBridge bridge;
    private final List<HostCommand> pendingCommands = new CopyOnWriteArrayList<>();
    private volatile boolean bridgeReady = false;

    // Minimal command interface for buffering
    private sealed interface HostCommand {
        record Append(String text, boolean isNew, ChatMessageType msgType, boolean streaming, boolean reasoning)
                implements HostCommand {}

        record Clear() implements HostCommand {}

        record Theme(String themeName, boolean isDevMode, boolean wrapMode) implements HostCommand {}

        record HistoryReset() implements HostCommand {}

        record HistoryTask(TaskEntry entry) implements HostCommand {}

        record LiveSummary(int taskSequence, boolean compressed, String summary) implements HostCommand {}

        record ShowSpinner(String message) implements HostCommand {}

        record HideSpinner() implements HostCommand {}

        record ShowTransientMessage(String message) implements HostCommand {}

        record HideTransientMessage() implements HostCommand {}

        record SetTaskInProgress(boolean inProgress) implements HostCommand {}
    }

    public JCEFWebViewHost() {
        super(new BorderLayout());

        // Set initial background color to avoid white flash while loading
        String themeName = MainProject.getTheme();
        boolean isDark = !GuiTheme.THEME_LIGHT.equals(themeName) && !"BrokkLightPlus".equals(themeName);
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
        try {
            // Get the shared, fully-initialized CefApp
            CefApp cefApp = getOrCreateCefApp();

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
                    logger.error("Load error: {} - {} for URL: {}", errorCode, errorText, failedUrl);
                }
            });

            // Get ClasspathHttpServer port and create browser
            int port = ClasspathHttpServer.ensureStarted();
            String themeName = MainProject.getTheme();
            String url = "http://127.0.0.1:" + port + "/index.html?theme=" + themeName;
            System.out.println("*** JCEF (JBR): Creating browser for URL: " + url + " ***");
            logger.info("Creating JCEF browser for URL: {}", url);

            // Create browser (windowed mode) - use deprecated API like working sample
            @SuppressWarnings("deprecation")
            var createdBrowser = client.createBrowser(url, false, false);
            browser = createdBrowser;
            System.out.println("*** JCEF (JBR): Browser created: " + browser + " ***");

            // Get the UI component and add it
            var uiComponent = browser.getUIComponent();
            System.out.println(
                    "*** JCEF (JBR): UI component: " + uiComponent.getClass().getName() + " ***");

            // Add browser directly - CEF requires component in displayable hierarchy to load
            // Brief white flash is unavoidable JCEF limitation (native window ignores Swing overlays)
            add(uiComponent, BorderLayout.CENTER);
            revalidate();
            repaint();

            System.out.println("*** JCEF (JBR): Browser added to panel ***");
            logger.info("JCEF browser created successfully");

            // Initial theme - queue until bridge ready
            boolean isDevMode = Boolean.parseBoolean(System.getProperty("brokk.devmode", "false"));
            setInitialTheme(themeName, isDevMode, MainProject.getCodeBlockWrapMode());

        } catch (Exception e) {
            logger.error("Failed to initialize JCEF via jcefmaven", e);
            System.err.println("*** JCEF initialization failed: " + e.getMessage() + " ***");
            e.printStackTrace(System.err);
            throw wrapWithDependencyHint(e);
        }
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

    private static CefApp getOrCreateCefApp() {
        CefApp existing = cefAppRef.get();
        if (existing != null) {
            return existing;
        }

        synchronized (cefInitLock) {
            existing = cefAppRef.get();
            if (existing != null) {
                return existing;
            }

            System.out.println("*** JCEF: Initializing CEF with jcefmaven ***");
            logger.info("Initializing JCEF with jcefmaven");

            var builder = JCefSetup.builder();

            String themeName = MainProject.getTheme();
            boolean isDark = !GuiTheme.THEME_LIGHT.equals(themeName) && !"BrokkLightPlus".equals(themeName);
            var bgColor = ThemeColors.getColor(isDark, ThemeColors.CHAT_BACKGROUND);

            var settings = builder.getCefSettings();
            settings.windowless_rendering_enabled = false;
            settings.background_color = settings.new ColorType(0xFF, bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue());

            builder.setAppHandler(new MavenCefAppHandlerAdapter() {
                @Override
                public void stateHasChanged(CefApp.CefAppState state) {
                    logger.info("CefApp state changed: {}", state);
                    System.out.println("*** JCEF: State changed to " + state + " ***");
                }
            });

            CefApp app;
            try {
                app = builder.build();
            } catch (Exception e) {
                throw wrapWithDependencyHint(e);
            }
            cefAppRef.set(app);

            System.out.println("*** JCEF: CefApp created (state: " + app.getState() + ") ***");
            logger.info("JCEF CefApp created");
            return app;
        }
    }

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
        } catch (IOException e) {
            logger.error("Failed to load JCEF bridge script", e);
        }
    }

    // Called by JCEFBridge when JS signals bridge is ready
    void onBridgeReady() {
        System.out.println("*** JCEFWebViewHost.onBridgeReady() called, setting bridgeReady=true ***");
        logger.info("Bridge ready, flushing {} pending commands", pendingCommands.size());
        bridgeReady = true;
        flushPendingCommands();
    }

    private void flushPendingCommands() {
        if (!bridgeReady || browser == null || bridge == null) {
            return;
        }
        logger.info("Flushing {} pending commands", pendingCommands.size());
        for (var cmd : pendingCommands) {
            switch (cmd) {
                case HostCommand.Append a ->
                    bridge.sendAppend(browser, a.text(), a.isNew(), a.msgType(), a.streaming(), a.reasoning());
                case HostCommand.Clear ignored -> bridge.sendClear(browser);
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
            }
        }
        pendingCommands.clear();
    }

    // Public API methods (minimal for PoC)

    @Override
    public void append(String text, boolean isNew, ChatMessageType msgType, boolean streaming, boolean reasoning) {
        if (bridgeReady && browser != null && bridge != null) {
            bridge.sendAppend(browser, text, isNew, msgType, streaming, reasoning);
        } else {
            pendingCommands.add(new HostCommand.Append(text, isNew, msgType, streaming, reasoning));
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

    // Stub methods for API compatibility (not implemented in PoC)

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

    @Override
    public CompletableFuture<Void> flushAsync() {
        return CompletableFuture.completedFuture(null);
    }

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
