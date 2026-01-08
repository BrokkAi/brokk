package ai.brokk.gui.mop.webview;

import ai.brokk.ContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.gui.Chrome;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.Component;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.JComponent;
import org.jetbrains.annotations.Nullable;

/**
 * Common interface for WebView host implementations.
 * Allows switching between JavaFX WebView (MOPWebViewHost) and JCEF (JCEFWebViewHost).
 */
public interface IWebViewHost {

    /**
     * Get the Swing component to add to the UI.
     */
    JComponent getComponent();

    /**
     * Append text to the current message or start a new message.
     */
    void append(String text, boolean isNew, ChatMessageType msgType, boolean streaming, boolean reasoning);

    /**
     * Clear all messages.
     */
    void clear();

    /**
     * Dispose resources.
     */
    void dispose();

    // History methods
    void historyReset();

    void historyTask(TaskEntry entry);

    // UI feedback methods
    void showSpinner(String message);

    void hideSpinner();

    void showTransientMessage(String message);

    void hideTransientMessage();

    void setTaskInProgress(boolean inProgress);

    // Environment and context
    void sendEnvironmentInfo(boolean analyzerReady);

    void sendLiveSummary(int taskSequence, boolean compressed, String summary);

    void onAnalyzerReadyResponse(String contextId);

    String getContextCacheId();

    // Theme
    void setInitialTheme(String themeName, boolean isDevMode, boolean wrapMode);

    void setRuntimeTheme(String themeName, boolean isDevMode, boolean wrapMode);

    // Async operations
    CompletableFuture<Void> flushAsync();

    CompletableFuture<String> getSelectedText();

    // Zoom
    void setZoom(double zoom);

    void zoomIn();

    void zoomOut();

    void resetZoom();

    // Search
    void setSearch(String query, boolean caseSensitive);

    void clearSearch();

    void nextMatch();

    void prevMatch();

    void scrollToCurrent();

    void addSearchStateListener(Consumer<MOPBridge.SearchState> listener);

    void removeSearchStateListener(Consumer<MOPBridge.SearchState> listener);

    // Context manager integration
    void setContextManager(@Nullable ContextManager contextManager);

    void setSymbolRightClickHandler(@Nullable Chrome chrome);

    void setHostComponent(@Nullable Component hostComponent);
}
