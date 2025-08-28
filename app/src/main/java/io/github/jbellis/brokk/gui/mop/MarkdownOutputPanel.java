package io.github.jbellis.brokk.gui.mop;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.context.ContextFragment;
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
    private @Nullable IContextManager currentContextManager;

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

    public void updateTheme(boolean isDark) {
        boolean isDevMode = Boolean.parseBoolean(System.getProperty("brokk.devmode", "false"));
        webHost.setTheme(isDark, isDevMode);
    }

    public void setBlocking(boolean blocked) {
        this.blockClearAndReset = blocked;
    }

    public boolean isBlocking() {
        return blockClearAndReset;
    }

    public void clear() {
        if (blockClearAndReset) {
            logger.debug("Ignoring clear() request while blocking is enabled");
            return;
        }
        messages.clear();
        webHost.clear();
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

    public void setText(List<ChatMessage> newMessages) {
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

    public void setText(TaskEntry taskEntry) {
        SwingUtilities.invokeLater(() -> {
            if (taskEntry.isCompressed()) {
                setText(List.of(Messages.customSystem(Objects.toString(taskEntry.summary(), "Summary not available"))));
            } else {
                var taskFragment = castNonNull(taskEntry.log());
                setText(taskFragment.messages());
            }
        });
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

    public void addSearchStateListener(Consumer<MOPBridge.SearchState> l) {
        webHost.addSearchStateListener(l);
    }

    public void removeSearchStateListener(Consumer<MOPBridge.SearchState> l) {
        webHost.removeSearchStateListener(l);
    }

    public void setProject(IProject project) {
        webHost.setProject(project);
    }

    public void setContextManager(@Nullable IContextManager contextManager) {
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
    }

    public void setSymbolRightClickHandler(@Nullable io.github.jbellis.brokk.gui.Chrome chrome) {
        webHost.setSymbolRightClickHandler(chrome);
    }

    @Override
    public void onAnalyzerUpdated() {
        // No longer needed - onAnalyzerReady handles symbol highlighting
    }

    @Override
    public void onAnalyzerReady() {
        String contextId = webHost.getContextCacheId();
        webHost.onAnalyzerReadyResponse(contextId);
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
