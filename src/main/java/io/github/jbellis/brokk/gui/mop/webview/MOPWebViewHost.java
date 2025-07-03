package io.github.jbellis.brokk.gui.mop.webview;

import dev.langchain4j.data.message.ChatMessageType;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

public final class MOPWebViewHost extends JPanel {
    private static final Logger logger = LogManager.getLogger(MOPWebViewHost.class);
    @Nullable private JFXPanel fxPanel;
    private final AtomicReference<MOPBridge> bridgeRef = new AtomicReference<>();
    private final AtomicReference<WebView> webViewRef = new AtomicReference<>();
    private final java.util.List<HostCommand> pendingCommands = new CopyOnWriteArrayList<>();
    private volatile boolean darkTheme = true; // Default to dark theme

    // Theme configuration as a record for DRY principle
    private record Theme(boolean isDark, Color awtBg, javafx.scene.paint.Color fxBg, String cssColor) {
        static final Theme DARK = new Theme(true, Color.BLACK, javafx.scene.paint.Color.BLACK, "black");
        static final Theme LIGHT = new Theme(false, Color.WHITE, javafx.scene.paint.Color.WHITE, "white");
    }

    // Represents commands to be sent to the bridge; buffered until bridge is ready
    private sealed interface HostCommand {
        record Append(String text, boolean isNew, ChatMessageType msgType, boolean streaming) implements HostCommand {}
        record SetTheme(boolean isDark) implements HostCommand {}
        record ShowSpinner(String message) implements HostCommand {}
        record Clear() implements HostCommand {}
    }

    public MOPWebViewHost() {
        super(new BorderLayout());

        // Defer JFXPanel creation to avoid EDT event pumping during construction
        SwingUtilities.invokeLater(this::initializeFxPanel);
    }

    private void initializeFxPanel() {
        fxPanel = new JFXPanel();
        fxPanel.setVisible(false); // Start hidden to prevent flicker
        add(fxPanel, BorderLayout.CENTER);
        revalidate();
        repaint();

        Platform.runLater(() -> {
            var view = new WebView();
            webViewRef.set(view); // Store reference for later theme updates
            var scene = new Scene(view);
            requireNonNull(fxPanel).setScene(scene);
            var bridge = new MOPBridge(view.getEngine());
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

            // Add listener for resource loading errors
            view.getEngine().setOnError(errorEvent -> {
                logger.error("WebView Resource Load Error: {}", errorEvent.getMessage());
            });

            // Expose Java object to JS after the page loads
            view.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                    var window = (JSObject) view.getEngine().executeScript("window");
                    window.setMember("javaBridge", bridge);
                    
                    // Inject JavaScript to intercept console methods and forward to Java bridge
                    view.getEngine().executeScript("""
                        (function() {
                            var originalLog = console.log;
                            var originalError = console.error;
                            var originalWarn = console.warn;
                            
                            console.log = function() {
                                var msg = Array.prototype.slice.call(arguments).join(' ');
                                if (window.javaBridge) window.javaBridge.jsLog('INFO', msg);
                                originalLog.apply(console, arguments);
                            };
                            console.error = function() {
                                var msg = Array.prototype.slice.call(arguments).join(' ');
                                if (window.javaBridge) window.javaBridge.jsLog('ERROR', msg);
                                originalError.apply(console, arguments);
                            };
                            console.warn = function() {
                                var msg = Array.prototype.slice.call(arguments).join(' ');
                                if (window.javaBridge) window.javaBridge.jsLog('WARN', msg);
                                originalWarn.apply(console, arguments);
                            };
                        })();
                        """);
                    // Now that the page is loaded, flush any buffered commands
                    flushBufferedCommands();
                    // Show the panel only after the page is fully loaded
                    SwingUtilities.invokeLater(() -> requireNonNull(fxPanel).setVisible(true));
                } else if (newState == javafx.concurrent.Worker.State.FAILED) {
                    logger.error("WebView Page Load Failed");
                    // Show the panel even on failure to display any error content
                    SwingUtilities.invokeLater(() -> requireNonNull(fxPanel).setVisible(true));
                }
            });

            var resourceUrl = getClass().getResource("/mop-web/index.html");
            if (resourceUrl == null) {
                view.getEngine().loadContent("<html><body><h1>Error: mop-web/index.html not found</h1></body></html>", "text/html");
            } else {
                if ("jar".equals(resourceUrl.getProtocol())) {
                    // When running from a JAR, serve resources via an embedded HTTP server to avoid CORS issues
                    int port = ClasspathHttpServer.ensureStarted();
                    var url = "http://127.0.0.1:" + port + "/index.html";
                    logger.info("Loading WebView content from embedded server: {}", url);
                    view.getEngine().load(url);
                } else {
                    // When running from IDE or exploded classes, load directly from file system
                    logger.info("Loading WebView content from filesystem: {}", resourceUrl.toExternalForm());
                    view.getEngine().load(resourceUrl.toExternalForm());
                }
            }
            // Apply initial theme
            applyTheme(darkTheme ? Theme.DARK : Theme.LIGHT);
        });
    }

    public void append(String text, boolean isNewMessage, ChatMessageType msgType, boolean streaming) {
        sendOrQueue(new HostCommand.Append(text, isNewMessage, msgType, streaming),
                     bridge -> bridge.append(text, isNewMessage, msgType, streaming));
    }

    public void setTheme(boolean isDark) {
        darkTheme = isDark; // Remember the last requested theme
        sendOrQueue(new HostCommand.SetTheme(isDark),
                     bridge -> bridge.setTheme(isDark));
        applyTheme(isDark ? Theme.DARK : Theme.LIGHT);
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
                // Update UA stylesheet
                String css = """
                    html, body {
                        background-color: %s !important;
                    }""".formatted(theme.cssColor());
                String dataCssUrl = "data:text/css," + java.net.URLEncoder.encode(css, java.nio.charset.StandardCharsets.UTF_8) + "#t=" + System.currentTimeMillis();
                webView.getEngine().setUserStyleSheetLocation(dataCssUrl);
            });
        }
    }

    public void clear() {
        sendOrQueue(new HostCommand.Clear(),
                     MOPBridge::clear);
    }

    public void showSpinner(String message) {
        sendOrQueue(new HostCommand.ShowSpinner(message),
                     bridge -> bridge.showSpinner(message));
    }

    private void sendOrQueue(HostCommand command, java.util.function.Consumer<MOPBridge> action) {
        var bridge = bridgeRef.get();
        if (bridge == null) {
            pendingCommands.add(command);
            logger.debug("Buffered command, bridge not ready yet: {}", command);
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
                    case HostCommand.Append a -> bridge.append(a.text(), a.isNew(), a.msgType(), a.streaming());
                    case HostCommand.SetTheme t -> bridge.setTheme(t.isDark());
                    case HostCommand.ShowSpinner s -> bridge.showSpinner(s.message());
                    case HostCommand.Clear ignored -> bridge.clear();
                }
            });
            pendingCommands.clear();
        }
    }

    public void dispose() {
        var bridge = bridgeRef.getAndSet(null);
        if (bridge != null) {
            bridge.shutdown();
        }
        webViewRef.set(null);
        Platform.runLater(() -> {
            if (fxPanel != null && fxPanel.getScene() != null && fxPanel.getScene().getRoot() instanceof WebView webView) {
                webView.getEngine().load(null); // release memory
            }
        });
        // Note: ClasspathHttpServer shutdown is handled at application level, not per WebView instance
    }
}
