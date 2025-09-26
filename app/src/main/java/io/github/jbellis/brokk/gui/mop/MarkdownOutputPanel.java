package io.github.jbellis.brokk.gui.mop;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.mop.webview.MOPBridge;
import io.github.jbellis.brokk.gui.mop.webview.MOPWebViewHost;
import io.github.jbellis.brokk.util.Messages;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * A Swing JPanel that uses a JavaFX WebView to display structured conversations. This is a modern, web-based
 * alternative to the pure-Swing MarkdownOutputPanel.
 */
public class MarkdownOutputPanel extends JPanel implements ThemeAware, Scrollable, IContextManager.AnalyzerCallback {
    private static final Logger logger = LogManager.getLogger(MarkdownOutputPanel.class);

    private final MOPWebViewHost webHost;
    private boolean blockClearAndReset = false;
    private final List<Runnable> textChangeListeners = new ArrayList<>();
    private final List<ChatMessage> messages = new ArrayList<>();
    private @Nullable ContextManager currentContextManager;
    private @Nullable String lastHistorySignature = null;

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
        updateTheme(guiTheme.isDarkTheme());
    }

    @Override
    public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
        updateTheme(guiTheme.isDarkTheme(), wordWrap);
    }

    public void updateTheme(boolean isDark) {
        boolean wrapMode = MainProject.getCodeBlockWrapMode();
        updateTheme(isDark, wrapMode);
    }

    public void updateTheme(boolean isDark, boolean wordWrap) {
        boolean isDevMode = Boolean.parseBoolean(System.getProperty("brokk.devmode", "false"));
        webHost.setRuntimeTheme(isDark, isDevMode, wordWrap);
    }

    public void setBlocking(boolean blocked) {
        this.blockClearAndReset = blocked;
    }

    public boolean isBlocking() {
        return blockClearAndReset;
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
                sb.append("U:").append(Objects.hashCode(entry.log()));
            }
            sb.append(';');
        }
        return sb.toString();
    }

    private void setMainIfChanged(List<? extends ChatMessage> newMessages) {
        if (isBlocking()) {
            logger.debug("Ignoring setMainIfChanged() while blocking is enabled.");
            return;
        }
        if (getRawMessages(true).equals(newMessages)) {
            logger.debug("Skipping MOP main update, content is unchanged.");
            return;
        }
        setText(newMessages);
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
    public java.util.concurrent.CompletableFuture<Void> setMainThenHistoryAsync(
            List<? extends ChatMessage> mainMessages, List<TaskEntry> history) {
        if (isBlocking()) {
            logger.debug("Ignoring setMainThenHistoryAsync() while blocking is enabled.");
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        setMainIfChanged(mainMessages);
        return flushAsync().thenRun(() -> SwingUtilities.invokeLater(() -> setHistoryIfChanged(history)));
    }

    /** Convenience overload to accept a TaskEntry as the main content. */
    public java.util.concurrent.CompletableFuture<Void> setMainThenHistoryAsync(
            TaskEntry main, List<TaskEntry> history) {
        List<? extends ChatMessage> mainMessages = main.isCompressed()
                ? List.of(Messages.customSystem(Objects.toString(main.summary(), "Summary not available")))
                : castNonNull(main.log()).messages();
        return setMainThenHistoryAsync(mainMessages, history);
    }

    public void clear() {
        if (blockClearAndReset) {
            logger.debug("Ignoring clear() request while blocking is enabled");
            return;
        }
        messages.clear();
        lastHistorySignature = null;
        webHost.clear();
        webHost.historyReset();
        textChangeListeners.forEach(Runnable::run);
    }

    public void append(String text, ChatMessageType type, boolean isNewMessage) {
        append(text, type, isNewMessage, false);
    }

    public void append(String text, ChatMessageType type, boolean isNewMessage, boolean reasoning) {
        if (text.isEmpty()) {
            return;
        }

        var lastMessageIsReasoning = !messages.isEmpty() && isReasoningMessage(messages.getLast());
        if (isNewMessage
                || messages.isEmpty()
                || reasoning != lastMessageIsReasoning
                || (!reasoning && type != messages.getLast().type())) {
            // new message
            messages.add(Messages.create(text, type, reasoning));
        } else {
            // merge with last message
            var lastIdx = messages.size() - 1;
            var last = messages.get(lastIdx);
            var combined = Messages.getText(last) + text;
            messages.set(lastIdx, Messages.create(combined, type, reasoning));
        }

        webHost.append(text, isNewMessage, type, true, reasoning);
        textChangeListeners.forEach(Runnable::run);
    }

    public void setText(ContextFragment.TaskFragment newOutput) {
        setText(newOutput.messages());
    }

    public void setText(List<? extends ChatMessage> newMessages) {
        if (blockClearAndReset && !messages.isEmpty()) {
            logger.debug("Ignoring setText() while blocking is enabled and panel already has content");
            return;
        }
        messages.clear();
        messages.addAll(newMessages);
        webHost.clear();
        for (var message : newMessages) {
            // reasoning is false atm, only transient via streamed append calls (not persisted)
            var isReasoning = isReasoningMessage(message);
            webHost.append(Messages.getText(message), true, message.type(), false, isReasoning);
        }
        // All appends are sent, now flush to make sure they are processed.
        webHost.flushAsync();
        textChangeListeners.forEach(Runnable::run);
    }

    public String getText() {
        return messages.stream().map(Messages::getRepr).collect(java.util.stream.Collectors.joining("\n\n"));
    }

    public List<ChatMessage> getRawMessages(boolean includeReasoning) {
        if (includeReasoning) {
            return List.copyOf(messages);
        }
        return messages.stream().filter(m -> !isReasoningMessage(m)).toList();
    }

    public static boolean isReasoningMessage(ChatMessage msg) {
        if (msg instanceof AiMessage ai) {
            var reasoning = ai.reasoningContent();
            return reasoning != null && !reasoning.isEmpty();
        }
        return false;
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
        return messages.stream().map(Messages::getText).collect(java.util.stream.Collectors.joining("\n\n"));
    }

    public String getSelectedText() {
        try {
            return webHost.getSelectedText().get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.warn("Failed to fetch selected text from WebView", e);
            return "";
        }
    }

    public void copy() {
        String selectedText = getSelectedText();
        String textToCopy = selectedText.isEmpty() ? getDisplayedText() : selectedText;
        if (!textToCopy.isEmpty()) {
            var selection = new StringSelection(textToCopy);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
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

    public void withContextForLookups(@Nullable ContextManager contextManager, @Nullable Chrome chrome) {
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
