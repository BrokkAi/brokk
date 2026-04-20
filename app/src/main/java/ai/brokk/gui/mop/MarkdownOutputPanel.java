package ai.brokk.gui.mop;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.ContextManager;
import ai.brokk.IAppContextManager;
import ai.brokk.LlmOutputMeta;
import ai.brokk.TaskEntry;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.mop.webview.MOPBridge;
import ai.brokk.gui.mop.webview.MOPWebViewHost;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.project.MainProject;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * A Swing JPanel that uses a JavaFX WebView to display structured conversations. This is a modern, web-based
 * alternative to the pure-Swing MarkdownOutputPanel.
 */
public class MarkdownOutputPanel extends JPanel implements ThemeAware, Scrollable, IAppContextManager.AnalyzerCallback {
    private static final Logger logger = LogManager.getLogger(MarkdownOutputPanel.class);

    private final MOPWebViewHost webHost;
    private boolean taskInProgress = false;
    private boolean showEmptyState = false;
    private final List<Runnable> textChangeListeners = new ArrayList<>();
    private final List<ChatMessage> messages = new ArrayList<>();
    private @Nullable String staticMarkdown = null;
    private @Nullable ContextManager currentContextManager;
    private @Nullable String lastHistorySignature = null;
    private boolean transientMessageVisible = false;

    private @Nullable StringBuilder currentStreamingBuffer;
    private @Nullable ChatMessageType currentStreamingType;
    private @Nullable LlmOutputMeta currentStreamingMeta;

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return true; // Ensure the panel stretches to fill the viewport height
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize(); // Return the preferred size of the panel
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 10; // Small increment for unit scrolling
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 50; // Larger increment for block scrolling
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true; // Stretch to fill viewport width as well
    }

    public MarkdownOutputPanel(boolean escapeHtml) {
        super(new BorderLayout());
        logger.info("Initializing WebView-based MarkdownOutputPanel");
        this.webHost = new MOPWebViewHost();
        add(webHost, BorderLayout.CENTER);
    }

    public MarkdownOutputPanel() {
        this(true);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        String themeName = MainProject.getTheme();
        updateTheme(themeName);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
        String themeName = MainProject.getTheme();
        updateTheme(themeName, wordWrap);
    }

    public void updateTheme(String themeName) {
        boolean wrapMode = MainProject.getCodeBlockWrapMode();
        updateTheme(themeName, wrapMode);
    }

    public void updateTheme(String themeName, boolean wordWrap) {
        boolean isDevMode = Boolean.parseBoolean(System.getProperty("brokk.devmode", "false"));
        webHost.setRuntimeTheme(themeName, isDevMode, wordWrap);
    }

    public void setTaskInProgress(boolean inProgress) {
        this.taskInProgress = inProgress;
        webHost.setTaskInProgress(inProgress);
    }

    public boolean taskInProgress() {
        return taskInProgress;
    }

    private String getHistorySignature(List<TaskEntry> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        for (var entry : entries) {
            sb.append(entry.sequence()).append(":");
            if (entry.isCompressed()) {
                sb.append("C:").append(Objects.hashCode(entry.summary()));
            } else {
                sb.append("U:").append(Objects.hashCode(entry.mopLog()));
            }
            sb.append(';');
        }
        return sb.toString();
    }

    private void setHistoryIfChanged(List<TaskEntry> entries) {
        String newSignature = getHistorySignature(entries);
        if (Objects.equals(lastHistorySignature, newSignature)) {
            logger.debug("Skipping MOP history update, content is unchanged.");
            return;
        }

        replaceHistory(entries);
        lastHistorySignature = newSignature;
    }

    /**
     * Ensures the main messages render first, then the history after the WebView flushes. The user want to see the main
     * message first
     */
    public CompletableFuture<Void> setMainThenHistoryAsync(
            Collection<? extends ChatMessage> mainMessages, List<TaskEntry> history) {
        if (getRawMessages().equals(List.copyOf(mainMessages))) {
            logger.debug("Skipping MOP main update, content is unchanged.");
        } else {
            setMessages(mainMessages);
        }

        return flushAsync().thenRun(() -> {
            logger.debug("MOP: applying history after main flush ({} entries)", history.size());
            setHistoryIfChanged(history);
        });
    }

    /** Convenience overload to accept a TaskEntry as the main content. */
    public CompletableFuture<Void> setMainThenHistoryAsync(TaskEntry main, List<TaskEntry> history) {
        // Prefer full messages when available (even if compressed); fall back to summary only if log is unavailable
        var mainMessages = main.mopMessages();

        // Send main messages first (which triggers clear on frontend). After the flush, apply history in-order,
        // then send live summary so it cannot be cleared by a subsequent history-reset on the frontend.
        var summary = main.summary();
        CompletableFuture<Void> result = setMainThenHistoryAsync(mainMessages, history);

        if (summary != null && !summary.isEmpty()) {
            // Send live summary strictly after history is applied
            result = result.thenRun(() -> {
                logger.debug(
                        "MOP: sending live-summary after history; seq={} compressed={} len={}",
                        main.sequence(),
                        main.isCompressed(),
                        summary.length());
                webHost.sendLiveSummary(main.sequence(), main.isCompressed(), summary);
            });
        }

        return result;
    }

    private void clearHistory() {
        // local
        lastHistorySignature = null;
        // webhost
        webHost.historyReset();
    }

    private void clearMain() {
        // local
        messages.clear();
        transientMessageVisible = false;
        currentStreamingBuffer = null;
        currentStreamingType = null;
        currentStreamingMeta = null;
        // webhost
        webHost.clear();
    }

    private void clearStaticDocument() {
        // local
        staticMarkdown = null;
        // webhost
        webHost.sendStaticDocument(null);
    }

    public void clear() {
        clearHistory();
        clearMain();
        clearStaticDocument();
        textChangeListeners.forEach(Runnable::run);
    }

    public void append(String text, ChatMessageType type, LlmOutputMeta meta) {
        if (text.isEmpty()) {
            return;
        }

        // 1. Transient message cleanup
        boolean wasTransientVisible = transientMessageVisible;
        if (wasTransientVisible) {
            transientMessageVisible = false;
            webHost.hideTransientMessage();
        }

        // 2. Determine if we must start a new message bubble
        boolean isNew;
        if (meta.isNewMessage() || wasTransientVisible || currentStreamingBuffer == null) {
            isNew = true;
        } else {
            // Streaming state invariant: buffer non-null implies type and meta are also set
            var streamingType = requireNonNull(currentStreamingType);
            var streamingMeta = requireNonNull(currentStreamingMeta);

            if (type != streamingType) {
                isNew = true;
            } else {
                isNew = meta.isReasoning() != streamingMeta.isReasoning()
                        || meta.isTerminal() != streamingMeta.isTerminal();
            }
        }

        if (isNew) {
            finalizeCurrentStreamingMessage();
            currentStreamingBuffer = new StringBuilder(Math.max(text.length(), 4096)).append(text);
            currentStreamingType = type;
            currentStreamingMeta = meta;
        } else {
            castNonNull(currentStreamingBuffer).append(text);
            currentStreamingMeta = meta; // Update meta in case non-structural flags changed
        }

        var chunkMeta = ChunkMeta.fromLlmOutputMeta(meta, isNew);
        webHost.append(text, type, true, chunkMeta);
        textChangeListeners.forEach(Runnable::run);
    }

    /**
     * Creates a snapshot of the current streaming message without clearing the buffer.
     * Returns null if no streaming is in progress.
     */
    private @Nullable ChatMessage getCurrentStreamingSnapshot() {
        if (currentStreamingBuffer == null || currentStreamingBuffer.isEmpty()) {
            return null;
        }
        return Messages.create(
                currentStreamingBuffer.toString(),
                castNonNull(currentStreamingType),
                castNonNull(currentStreamingMeta));
    }

    /**
     * Finalizes the current streaming message by adding it to messages and clearing the buffer.
     * Call this only at actual stream boundaries (new message starting, explicit clear).
     */
    private void finalizeCurrentStreamingMessage() {
        var snapshot = getCurrentStreamingSnapshot();
        if (snapshot != null) {
            messages.add(snapshot);
        }
        currentStreamingBuffer = null;
        currentStreamingType = null;
        currentStreamingMeta = null;
    }

    private List<ChatMessage> getMessagesWithSnapshot() {
        var snapshot = getCurrentStreamingSnapshot();
        if (snapshot == null) {
            return messages;
        }
        var allMessages = new ArrayList<>(messages);
        allMessages.add(snapshot);
        return allMessages;
    }

    public void setMessages(Collection<? extends ChatMessage> newMessages) {
        // No need to finalize before clear—we're replacing all content anyway
        clearMain();
        messages.addAll(newMessages);
        for (var message : newMessages) {
            LlmOutputMeta meta = LlmOutputMeta.DEFAULT
                    .withReasoning(Messages.isReasoningMessage(message))
                    .withTerminal(Messages.isTerminalMessage(message));
            var chunkMeta = ChunkMeta.fromLlmOutputMeta(meta, true);
            webHost.append(Messages.getText(message), message.type(), false, chunkMeta);
        }
        // All appends are sent, now flush to make sure they are processed.
        webHost.flushAsync();
        textChangeListeners.forEach(Runnable::run);
    }

    public void setStaticDocument(String markdown) {
        clearStaticDocument();
        if (markdown.isBlank()) {
            return;
        }
        staticMarkdown = markdown;
        webHost.sendStaticDocument(markdown);
        textChangeListeners.forEach(Runnable::run);
    }

    public String getText() {
        if (staticMarkdown != null) {
            return staticMarkdown;
        }
        return getMessagesWithSnapshot().stream().map(Messages::getRepr).collect(Collectors.joining("\n\n"));
    }

    public List<ChatMessage> getRawMessages() {
        return List.copyOf(getMessagesWithSnapshot());
    }

    public void addTextChangeListener(Runnable listener) {
        textChangeListeners.add(listener);
    }

    public void showSpinner(String message) {
        webHost.showSpinner(message);
    }

    public void hideSpinner() {
        webHost.hideSpinner();
    }

    public CompletableFuture<Void> flushAsync() {
        return webHost.flushAsync();
    }

    public String getDisplayedText() {
        return getMessagesWithSnapshot().stream().map(Messages::getText).collect(Collectors.joining("\n\n"));
    }

    public String getSelectedText() {
        try {
            return webHost.getSelectedText().get(200, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.debug(
                    "Failed to fetch selected text from WebView: {}",
                    e.getClass().getSimpleName());
            return "";
        }
    }

    public void copy() {
        String selectedText = getSelectedText();
        String textToCopy = selectedText.isEmpty() ? getDisplayedText() : selectedText;
        if (!textToCopy.isEmpty() && currentContextManager != null) {
            currentContextManager.copyToClipboard(textToCopy);
        }
    }

    public void setSearch(String query, boolean caseSensitive) {
        webHost.setSearch(query, caseSensitive);
    }

    public void clearSearch() {
        webHost.clearSearch();
    }

    public void nextMatch() {
        webHost.nextMatch();
    }

    public void prevMatch() {
        webHost.prevMatch();
    }

    public void scrollSearchCurrent() {
        webHost.scrollToCurrent();
    }

    public void zoomIn() {
        webHost.zoomIn();
    }

    public void zoomOut() {
        webHost.zoomOut();
    }

    public void resetZoom() {
        webHost.resetZoom();
    }

    public void addSearchStateListener(Consumer<MOPBridge.SearchState> l) {
        webHost.addSearchStateListener(l);
    }

    public void removeSearchStateListener(Consumer<MOPBridge.SearchState> l) {
        webHost.removeSearchStateListener(l);
    }

    public void setContextForLookups(@Nullable ContextManager contextManager, @Nullable Chrome chrome) {
        // Unregister from previous context manager if it exists
        if (currentContextManager != null) {
            currentContextManager.removeAnalyzerCallback(this);
        }

        // Register with new context manager if it exists
        if (contextManager != null) {
            contextManager.addAnalyzerCallback(this);
        }

        currentContextManager = contextManager;
        webHost.setContextManager(contextManager);
        webHost.setSymbolRightClickHandler(chrome);
    }

    public void setShowEmptyState(boolean show) {
        this.showEmptyState = show;
        webHost.setShowEmptyState(show);
    }

    public boolean isShowEmptyState() {
        return showEmptyState;
    }

    @Override
    public void onAnalyzerReady() {
        String contextId = webHost.getContextCacheId();
        webHost.onAnalyzerReadyResponse(contextId);
        // Update environment block in the frontend to reflect readiness and languages
        webHost.sendEnvironmentInfo(true);
    }

    @Override
    public void beforeEachBuild() {
        // Analyzer about to rebuild; reflect "Building..." in environment block
        webHost.sendEnvironmentInfo(false);
    }

    @Override
    public void afterEachBuild(boolean externalRequest) {
        // Build complete; re-send snapshot in case counts/languages changed
        webHost.sendEnvironmentInfo(true);
    }

    @Override
    public void onRepoChange() {
        // Repo changed; update counts promptly (status may change shortly via build events)
        boolean ready = currentContextManager != null && currentContextManager.isAnalyzerReady();
        webHost.sendEnvironmentInfo(ready);
    }

    @Override
    public void onTrackedFileChange() {
        // Files changed; update counts promptly
        boolean ready = currentContextManager != null && currentContextManager.isAnalyzerReady();
        webHost.sendEnvironmentInfo(ready);
    }

    /** Re-sends the entire task history to the WebView. */
    private void replaceHistory(List<TaskEntry> entries) {
        webHost.historyReset();
        for (var entry : entries) {
            webHost.historyTask(entry);
        }
    }

    public void showTransientMessage(String message) {
        transientMessageVisible = true;
        webHost.showTransientMessage(message);
    }

    public void hideTransientMessage() {
        transientMessageVisible = false;
        webHost.hideTransientMessage();
    }

    public void dispose() {
        logger.debug("Disposing WebViewMarkdownOutputPanel.");

        // Unregister analyzer callback before disposing
        if (currentContextManager != null) {
            currentContextManager.removeAnalyzerCallback(this);
            currentContextManager = null;
        }

        webHost.dispose();
    }
}
