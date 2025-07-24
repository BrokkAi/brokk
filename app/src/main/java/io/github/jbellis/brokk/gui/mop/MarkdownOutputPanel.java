package io.github.jbellis.brokk.gui.mop;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.mop.stream.HtmlCustomizer;
import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import io.github.jbellis.brokk.gui.mop.webview.MOPWebViewHost;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Element;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

/**
 * A Swing JPanel that uses a JavaFX WebView to display structured conversations.
 * This is a modern, web-based alternative to the pure-Swing MarkdownOutputPanel.
 */
public class MarkdownOutputPanel extends JPanel implements ThemeAware, Scrollable {
    private static final Logger logger = LogManager.getLogger(MarkdownOutputPanel.class);

    private final MOPWebViewHost webHost;
    private boolean blockClearAndReset = false;
    private final List<Runnable> textChangeListeners = new ArrayList<>();
    private final List<ChatMessage> messages = new ArrayList<>();

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
        webHost.setTheme(isDark);
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
        if (text.isEmpty()) {
            return;
        }
        if (isNewMessage || messages.isEmpty() || messages.get(messages.size() - 1).type() != type) {
            messages.add(Messages.create(text, type));
        } else {
            var lastIdx = messages.size() - 1;
            var combined = Messages.getText(messages.get(lastIdx)) + text;
            messages.set(lastIdx, Messages.create(combined, type));
        }
        webHost.append(text, isNewMessage, type, true);
        textChangeListeners.forEach(Runnable::run);
    }

    public void setText(ContextFragment.TaskFragment newOutput) {
        if (blockClearAndReset) {
            logger.debug("Ignoring setText() request while blocking is enabled");
            return;
        }
        setText(newOutput.messages());
    }

    public void setText(List<ChatMessage> newMessages) {
        if (blockClearAndReset) {
            logger.debug("Ignoring setText() request while blocking is enabled");
            return;
        }
        messages.clear();
        messages.addAll(newMessages);
        webHost.clear();
        for (var message : newMessages) {
            webHost.append(Messages.getText(message), true, message.type(), false);
        }
        // All appends are sent, now flush to make sure they are processed.
        webHost.flushAsync();
        textChangeListeners.forEach(Runnable::run);
    }

    public void setText(TaskEntry taskEntry) {
        if (blockClearAndReset) {
            logger.debug("Ignoring setText(TaskEntry) request while blocking is enabled");
            return;
        }
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
        return messages.stream()
                       .map(Messages::getRepr)
                       .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    public List<ChatMessage> getRawMessages() {
        return List.copyOf(messages);
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

    public List<ChatMessage> getMessages() {
        return getRawMessages();
    }

    public CompletableFuture<Void> flushAsync() {
        return webHost.flushAsync();
    }

    public String getDisplayedText() {
        return messages.stream()
                       .map(Messages::getText)
                       .collect(java.util.stream.Collectors.joining("\n\n"));
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

    public void dispose() {
        logger.debug("Disposing WebViewMarkdownOutputPanel.");
        webHost.dispose();
    }

    // TODO: drop the unneeded methods later (they are just here to let the code compile)
    public CompletableFuture<Void> scheduleCompaction() {
        return CompletableFuture.completedFuture(null);
    }

    public Stream<IncrementalBlockRenderer> renderers() {
        return Stream.empty();
    }

    public void setHtmlCustomizerWithCallback(HtmlCustomizer customizer, Runnable callback) {
        callback.run();
        customizer.customize(new Element("body"));
    }

    public void setHtmlCustomizer(HtmlCustomizer customizer) {
        customizer.customize(new Element("body"));
    }
}
