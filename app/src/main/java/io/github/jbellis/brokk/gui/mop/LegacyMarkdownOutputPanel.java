//package io.github.jbellis.brokk.gui.mop;
//
//import dev.langchain4j.data.message.ChatMessage;
//import dev.langchain4j.data.message.ChatMessageType;
//import dev.langchain4j.data.message.UserMessage;
//import io.github.jbellis.brokk.context.ContextFragment;
//import io.github.jbellis.brokk.TaskEntry;
//import io.github.jbellis.brokk.gui.GuiTheme;
//import io.github.jbellis.brokk.gui.SwingUtil;
//import io.github.jbellis.brokk.gui.ThemeAware;
//import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
//import io.github.jbellis.brokk.util.Messages;
//import io.github.jbellis.brokk.gui.mop.stream.HtmlCustomizer;
//import org.jetbrains.annotations.Nullable;
//import java.util.Objects;
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
//
//import javax.swing.*;
//import java.awt.*;
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.List;
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//import java.util.concurrent.TimeUnit;
//import java.util.stream.Collectors;
//import java.util.stream.Stream;
//
//import static java.util.Objects.requireNonNull;
//import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;
//
///**
// * A Swing JPanel designed to display structured conversations as formatted text content which may include
// * standard Markdown, Markdown code fences (```), and Brokk-specific `SEARCH/REPLACE` edit blocks.
// * <p>
// * The panel internally maintains a list of {@link ChatMessage} objects, each representing a
// * message in the conversation (AI, User, System, etc.). Each message is rendered according to its type:
// *
// * <ul>
// *   <li>AI messages are parsed for edit blocks first, and if found, they are rendered with special formatting.
// *       Otherwise, they are rendered as Markdown with code syntax highlighting.</li>
// *   <li>User messages are rendered as plain text or simple Markdown.</li>
// *   <li>System and other message types are rendered as plain text.</li>
// * </ul>
// * <p>
// * The panel updates incrementally when messages are appended, only re-rendering the affected message
// * rather than the entire content, which prevents flickering during streaming updates.
// */
//public class LegacyMarkdownOutputPanel extends JPanel implements Scrollable, ThemeAware {
//    private static final Logger logger = LogManager.getLogger(LegacyMarkdownOutputPanel.class);
//    private static long compactionRoundIdCounter = 0;
//
//    /** Keeps together everything required for one rendered message bubble. */
//    private record Bubble(ChatMessage message,
//                          IncrementalBlockRenderer renderer,
//                          StreamingWorker worker,
//                          Component uiComponent) {}
//
//    // Holds all data and components for each message bubble
//    private final List<Bubble> bubbles = new ArrayList<>();
//
//    // Listeners to notify whenever text changes
//    private final List<Runnable> textChangeListeners = new ArrayList<>();
//
//    // Theme-related fields
//    private boolean isDarkTheme = false;
//    private boolean blockClearAndReset = false;
//    private final ExecutorService compactExec;
//    private final boolean escapeHtml;
//
//    // Global HtmlCustomizer applied to every renderer
//    private HtmlCustomizer htmlCustomizer = HtmlCustomizer.DEFAULT;
//
//    public LegacyMarkdownOutputPanel(boolean escapeHtml) {
//        this.escapeHtml = escapeHtml;
//        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
//        setOpaque(true);
//        this.compactExec = Executors.newSingleThreadExecutor(r -> {
//            var t = new Thread(r, "MOP-Compact-Thread-" + this.hashCode());
//            t.setDaemon(true);
//            return t;
//        });
//    }
//
//    public LegacyMarkdownOutputPanel() {
//        this(true); // Default to escaping HTML
//    }
//
//    @Override
//    public void applyTheme(GuiTheme guiTheme) {
//        updateTheme(guiTheme.isDarkTheme());
//    }
//
//    /**
//     * Updates the theme colors used by this panel. Must be called before adding text,
//     * or whenever you want to re-theme existing blocks.
//     */
//    public void updateTheme(boolean isDark) {
//        this.isDarkTheme = isDark;
//
//        var backgroundColor = ThemeColors.getColor(isDark, "chat_background");
//        setOpaque(true);
//        setBackground(backgroundColor);
//
//        var parent = getParent();
//        if (parent instanceof JViewport vp) {
//            vp.setOpaque(true);
//            vp.setBackground(backgroundColor);
//            var gp = vp.getParent();
//            if (gp instanceof JScrollPane sp) {
//                sp.setOpaque(true);
//                sp.setBackground(backgroundColor);
//            }
//        }
//
//        // Update spinner background if visible
//        if (spinnerPanel != null) {
//            spinnerPanel.updateBackgroundColor(backgroundColor);
//        }
//
//        // Re-render all components with new theme
//        // Extract existing messages to re-add them, which will apply the new theme
//        var currentMessages = bubbles.stream().map(Bubble::message).toList();
//        setText(currentMessages); // This will clear and re-add, applying the new theme
//        SwingUtilities.updateComponentTreeUI(this);
//
//        revalidate();
//        repaint();
//    }
//
//    /**
//     * Sets the blocking state that prevents clearing or resetting the panel contents.
//     * When blocking is enabled, clear() and setText() methods will be ignored.
//     * 
//     * @param blocked true to prevent clear/reset operations, false to allow them
//     */
//    public void setBlocking(boolean blocked) {
//        this.blockClearAndReset = blocked;
//        // use the unblocking state as trigger for compacting markdown
//        if (!blocked) {
//            final long roundId = ++compactionRoundIdCounter;
//            var internalCompletionFuture = new CompletableFuture<Void>();
//            internalCompletionFuture.exceptionally(ex -> {
//                logger.error("[COMPACTION][{}] Error during compaction triggered by setBlocking(false). This error is logged but not propagated further from setBlocking.", roundId, ex);
//                return null;
//            });
//
//            // Ensure flush and EDT execution for compaction triggered by setBlocking
//            flushPendingChangesAsync()
//                .thenRun(() -> SwingUtilities.invokeLater(
//                    () -> compactAllMessages(roundId, internalCompletionFuture)))
//                .exceptionally(ex -> {
//                    // Log and complete the internal future exceptionally if pre-compaction steps fail
//                    logger.error("[COMPACTION][{}] Error in pre-compaction (flush/schedule) for setBlocking(false).", roundId, ex);
//                    internalCompletionFuture.completeExceptionally(ex);
//                    return null;
//                });
//        }
//    }
//
//    /**
//     * Returns the current blocking state.
//     *
//     * @return true if clear/reset operations are blocked, false otherwise
//     */
//    public boolean isBlocking() {
//        return blockClearAndReset;
//    }
//    
//    /**
//     * Clears all text and displayed components.
//     */
//    public void clear() {
//        if (blockClearAndReset) {
//            logger.debug("Ignoring clear() request while blocking is enabled");
//            return;
//        }
//        SwingUtilities.invokeLater(() -> {
//            logger.debug("Clearing all content from MarkdownOutputPanel");
//            internalClear();
//            revalidate();
//            repaint();
//        });
//        textChangeListeners.forEach(Runnable::run); // Notify listeners about the change
//    }
//
//    /**
//     * Internal helper to clear all state
//     */
//    private void internalClear() {
//        // stop all background threads first
//        bubbles.forEach(b -> b.worker().shutdown());
//
//        // compactExec is no longer shut down here; it's managed by dispose()
//
//        bubbles.clear();
//        removeAll();
//        spinnerPanel = null;
//    }
//
//    /**
//     * Appends a new message or content to the last message if type matches.
//     * This is the primary method for adding content during streaming.
//     *
//     * @param text The text content to append
//     * @param type The type of message being appended
//     */
//    public void append(String text, ChatMessageType type, boolean isNewMessage) {
//        if (text.isEmpty()) {
//            return;
//        }
//
//        // isNewMessage parameter takes precedence over type matching
//        if (isNewMessage || bubbles.isEmpty() || bubbles.getLast().message().type() != type) {
//            // Create a new message
//            ChatMessage newMessage = Messages.create(text, type);
//            addNewMessageBubble(newMessage);
//        } else {
//            // Append to existing message
//            updateLastMessage(text);
//        }
//
//        textChangeListeners.forEach(Runnable::run);
//    }
//
//    /**
//     * Updates the last message by appending text to it
//     */
//    private void updateLastMessage(String additionalText) {
//        assert SwingUtilities.isEventDispatchThread();
//        
//        if (bubbles.isEmpty()) return;
//
//        var lastBubble = bubbles.getLast();
//        var lastMessage = lastBubble.message();
//        var type = lastMessage.type();
//
//        // Queue the chunk for background processing
//        lastBubble.worker().appendChunk(additionalText);
//        
//        // Update our model with the combined text
//        var updatedText = Messages.getRepr(lastMessage) + additionalText;
//        ChatMessage updatedMessage = Messages.create(updatedText, type);
//        
//        // Replace the last bubble with an updated one
//        bubbles.set(bubbles.size() - 1, new Bubble(updatedMessage,
//                                                   lastBubble.renderer(),
//                                                   lastBubble.worker(),
//                                                   lastBubble.uiComponent()));
//
//        // No need to call revalidate/repaint - worker will do this when parsing is done
//    }
//
//    /**
//     * Adds a new message to the display
//     */
//    private void addNewMessageBubble(ChatMessage message) {
//        // If spinner is showing, remove it temporarily
//        boolean spinnerWasVisible = false;
//        if (spinnerPanel != null) {
//            remove(spinnerPanel);
//            spinnerWasVisible = true;
//        }
//
//        // Determine styling based on message type
//        Icon iconEmoji;
//        Color highlightColor;
//        String title = requireNonNull(switch (message.type()) {
//            case AI -> {
//                iconEmoji = SwingUtil.uiIcon("FileView.computerIcon");
//                highlightColor = ThemeColors.getColor(isDarkTheme, "message_border_ai");
//                yield "Brokk";
//            }
//            case USER -> {
//                iconEmoji = SwingUtil.uiIcon("FileView.computerIcon");
//                highlightColor = ThemeColors.getColor(isDarkTheme, "message_border_user");
//                if (message instanceof UserMessage userMessage && userMessage.name() != null
//                        && !userMessage.name().isEmpty() && !userMessage.name().contains("MODE")) {
//                    yield userMessage.name();
//                } else {
//                    yield "You";
//                }
//            }
//            case CUSTOM, SYSTEM -> {
//                iconEmoji = SwingUtil.uiIcon("FileView.computerIcon");
//                highlightColor = ThemeColors.getColor(isDarkTheme, "message_border_custom");
//                yield "System";
//            }
//            default -> { // Should ideally not be reached if all ChatMessageType cases are handled.
//                iconEmoji = SwingUtil.uiIcon("FileView.computerIcon");
//                highlightColor = ThemeColors.getColor(isDarkTheme, "message_border_custom");
//                yield message.type().toString();
//            }
//        });
//
//        // Create a new renderer for this message - disable edit blocks for user messages
//        boolean enableEditBlocks = message.type() != ChatMessageType.USER;
//        var renderer = new IncrementalBlockRenderer(isDarkTheme, enableEditBlocks, escapeHtml);
//        renderer.setHtmlCustomizer(htmlCustomizer);
//
//        // Create a new worker for this message
//        var worker = new StreamingWorker(renderer);
//        
//        // Create the UI component (MessageBubble)
//        var bubbleUI = new MessageBubble(
//            title,
//            requireNonNull(iconEmoji),
//            renderer.getRoot(),
//            isDarkTheme,
//            highlightColor
//        );
//
//        // Add to our bubbles list
//        bubbles.add(new Bubble(message, renderer, worker, bubbleUI));
//        
//        // Add the component to the panel's UI
//        add(bubbleUI);
//        
//        // Process the message content through the new worker
//        worker.appendChunk(Messages.getText(message));
//
//        // Re-add spinner if it was visible
//        if (spinnerWasVisible && spinnerPanel != null) {
//            add(spinnerPanel);
//        }
//
//        revalidate();
//        repaint();
//    }
//
//    /**
//     * Sets the content from a list of ChatMessages
//     */
//    public void setText(ContextFragment.TaskFragment newOutput) {
//        if (blockClearAndReset) {
//            logger.debug("Ignoring setText() request while blocking is enabled");
//            return;
//        }
//        internalClear();
//
//        setText(newOutput.messages());
//    }
//
//    // private for changing theme -- parser doesn't need to change
//    private void setText(List<ChatMessage> newMessages) {
//        if (blockClearAndReset && !this.bubbles.isEmpty()) {
//            logger.debug("Ignoring private setText() request while blocking is enabled");
//            return;
//        }
//        internalClear(); // Clear existing bubbles and workers
//
//        for (var message : newMessages) {
//            addNewMessageBubble(message);
//        }
//        revalidate();
//        repaint();
//        textChangeListeners.forEach(Runnable::run);
//    }
//
//    /**
//     * Sets the content from a TaskEntry
//     */
//    public void setText(TaskEntry taskEntry) {
//        if (blockClearAndReset) {
//            logger.debug("Ignoring setText(TaskEntry) request while blocking is enabled");
//            return;
//        }
//        SwingUtilities.invokeLater(() -> {
//            if (taskEntry.isCompressed()) {
//                setText(List.of(Messages.customSystem(Objects.toString(taskEntry.summary(), "Summary not available"))));
//            } else {
//                var taskFragment = castNonNull(taskEntry.log());
//                setText(taskFragment.messages());
//            }
//        });
//    }
//
//    /**
//     * Returns text representation of all messages.
//     * For backward compatibility with code that expects a String.
//     */
//    public String getText() {
//        if (!SwingUtilities.isEventDispatchThread()) {
//            flushPendingChangesSync();
//        }
//        return bubbles.stream()
//                .map(b -> Messages.getRepr(b.message()))
//                .collect(Collectors.joining("\n\n"));
//    }
//
//    /**
//     * Returns the raw ChatMessage objects.
//     * Similar to getMessages() but with a more descriptive name.
//     *
//     * @return An unmodifiable list of the current messages
//     */
//    public List<ChatMessage> getRawMessages() {
//        if (!SwingUtilities.isEventDispatchThread()) {
//            flushPendingChangesSync();
//        }
//        return bubbles.stream().map(Bubble::message).toList();
//    }
//
//    /**
//     * Let callers listen for changes in the text.
//     */
//    public void addTextChangeListener(Runnable listener) {
//        textChangeListeners.add(listener);
//    }
//
//    /**
//     * Sets or clears a global HtmlCustomizer for all renderers.
//     */
//    public void setHtmlCustomizer(HtmlCustomizer customizer) {
//        this.htmlCustomizer = customizer;
//        renderers().forEach(r -> {
//            r.setHtmlCustomizer(this.htmlCustomizer);
//            r.reprocessForCustomizer();           // Refresh already rendered content
//        });
//    }
//    
//    /**
//     * Sets or clears a global HtmlCustomizer for all renderers and executes a callback
//     * after the customizer has been applied and rendered.
//     */
//    public void setHtmlCustomizerWithCallback(HtmlCustomizer customizer, Runnable callback) {
//        this.htmlCustomizer = customizer;
//        var renderersList = renderers().toList();
//        renderersList.forEach(r -> {
//            r.setHtmlCustomizer(this.htmlCustomizer);
//            r.reprocessForCustomizer();           // Refresh already rendered content
//        });
//        
//        // Execute callback on EDT after all customizers have been processed
//        SwingUtilities.invokeLater(callback);
//    }
//
//
//    // --- Spinner Logic ---
//
//    // We keep a reference to the spinner panel itself, so we can remove it later
//    @Nullable private SpinnerIndicatorPanel spinnerPanel = null;
//
//    /**
//     * Shows a small spinner (or message) at the bottom of the panel,
//     * underneath whatever content the user just appended.
//     * If already showing, does nothing.
//     *
//     * @param message The text to display next to the spinner (e.g. "Thinking...")
//     */
//    public void showSpinner(String message) {
//        if (spinnerPanel != null) {
//            // Already showing, update the message and return
//                spinnerPanel.setMessage(message);
//                return;
//            }
//            // Create a new spinner instance each time
//            spinnerPanel = new SpinnerIndicatorPanel(message, isDarkTheme, 
//                                 ThemeColors.getColor(isDarkTheme, "chat_background"));
//
//        // Add to the end of this panel. Since we have a BoxLayout (Y_AXIS),
//        // it shows up below the existing rendered content.
//        add(spinnerPanel);
//
//        revalidate();
//        repaint();
//    }
//
//    /**
//     * Hides the spinner panel if it is visible, removing it from the UI.
//     */
//    public void hideSpinner() {
//        if (spinnerPanel == null) {
//            return; // not showing
//        }
//        remove(spinnerPanel); // Remove from components
//        spinnerPanel = null; // Release reference
//
//        revalidate();
//        repaint();
//    }
//
//    /**
//     * Get the current messages in the panel.
//     * This is useful for code that needs to access the structured message data.
//     *
//     * @return An unmodifiable list of the current messages
//     */
//    public List<ChatMessage> getMessages() {
//        if (!SwingUtilities.isEventDispatchThread()) {
//            flushPendingChangesSync();
//        }
//        return Collections.unmodifiableList(bubbles.stream().map(Bubble::message).toList());
//    }
//
//    private Stream<StreamingWorker> workers() {
//        return bubbles.stream().map(Bubble::worker);
//    }
//
//    public Stream<IncrementalBlockRenderer> renderers() {
//        return bubbles.stream().map(Bubble::renderer);
//    }
//
//    /**
//     * Returns a future that completes when the worker is idle and all pending changes are flushed.
//     * The future completes on the EDT.
//     */
//    private CompletableFuture<Void> flushPendingChangesAsync() {
//        if (bubbles.isEmpty()) {
//            return CompletableFuture.completedFuture(null);
//        }
//        var futures = workers().map(StreamingWorker::flushAsync)
//                               .toArray(CompletableFuture[]::new);
//        return CompletableFuture.allOf(futures);
//    }
//
//    /**
//     * Synchronously flushes pending changes. MUST NOT be called on EDT.
//     */
//    private void flushPendingChangesSync() {
//        assert !SwingUtilities.isEventDispatchThread() : "flushPendingChangesSync must not be called on EDT";
//        workers().forEach(StreamingWorker::flush); // blocks for each worker
//    }
//
//    public CompletableFuture<Void> scheduleCompaction() {
//        var done = new CompletableFuture<Void>();
//        final long roundId = ++compactionRoundIdCounter;
//
//        // be sure any pending changes are flushed before compacting
//        flushPendingChangesAsync()
//            .thenRun(() -> SwingUtilities.invokeLater( // Ensures compactAllMessages is called on EDT
//                () -> compactAllMessages(roundId, done))) // Pass the 'done' future
//            .exceptionally(ex -> {
//                done.completeExceptionally(ex); // Propagate exception from flush or scheduling
//                return null;
//            });
//
//        return done;
//    }
//
//    /**
//     * Compacts all messages by merging consecutive Markdown blocks in each message renderer.
//     * This improves the user experience for selecting text across multiple Markdown blocks.
//     * This operation is performed asynchronously. The provided CompletableFuture is completed
//     * on the EDT after UI updates are done.
//     * Assumes this method is called on the EDT and pending changes have been flushed.
//     *
//     * @param roundId The compaction round ID.
//     * @param completionFuture The future to complete when compaction and UI updates are finished.
//     */
//    private void compactAllMessages(long roundId, CompletableFuture<Void> completionFuture) {
//        assert SwingUtilities.isEventDispatchThread() : "compactAllMessages must be called on the EDT";
//
//        final List<IncrementalBlockRenderer> renderersToCompact = renderers().toList();
//
//        if (renderersToCompact.isEmpty()) {
//            logger.trace("[COMPACTION][{}] No renderers to compact.", roundId);
//            completionFuture.complete(null);
//            return;
//        }
//
//        CompletableFuture.runAsync(() -> { // Heavy lifting on compactExec
//            var compactedSnapshots = renderersToCompact.stream()
//                .map(renderer -> {
//                    try {
//                        return renderer.buildCompactedSnapshot(roundId);
//                    } catch (Exception e) {
//                        logger.warn("[COMPACTION][{}] Error building compacted snapshot for a renderer", roundId, e);
//                        return null; 
//                    }
//                })
//                .toList();
//
//            SwingUtilities.invokeLater(() -> { // UI updates back on EDT
//                assert SwingUtilities.isEventDispatchThread();
//                if (renderersToCompact.size() != compactedSnapshots.size()) {
//                    logger.error("[COMPACTION][{}] Mismatch between renderers ({}) and snapshots ({}) count during compaction. Aborting UI update.",
//                                 roundId, renderersToCompact.size(), compactedSnapshots.size());
//                    completionFuture.completeExceptionally(new IllegalStateException("Compaction snapshot mismatch"));
//                    return;
//                }
//
//                for (int i = 0; i < renderersToCompact.size(); i++) {
//                    var renderer = renderersToCompact.get(i);
//                    var snapshot = compactedSnapshots.get(i);
//                    renderer.applyCompactedSnapshot(snapshot, roundId);
//                }
//                revalidate();
//                repaint();
//                logger.trace("[COMPACTION][{}] Compacted all messages and applied to UI.", roundId);
//                completionFuture.complete(null);
//            });
//        }, this.compactExec).exceptionally(ex -> {
//            logger.error("[COMPACTION][{}] Error during async compaction computation task", roundId, ex);
//            completionFuture.completeExceptionally(ex); // Propagate exception
//            return null;
//        });
//    }
//
//    // --- Text Access and Manipulation Methods for Copy/Paste ---
//
//    /**
//     * Returns all displayed text content from the messages.
//     * This concatenates the pure text of each message.
//     *
//     * @return A string containing all displayed text.
//     */
//    public String getDisplayedText() {
//        if (!SwingUtilities.isEventDispatchThread()) {
//            flushPendingChangesSync();
//        }
//        return bubbles.stream()
//                .map(b -> Messages.getText(b.message())) // Gets the actual content of the message
//                .collect(Collectors.joining("\n\n"));
//    }
//
//    /**
//     * Gets the currently selected text from all underlying text components.
//     * Traverses the component hierarchy to find JTextPanes, JEditorPanes, or JTextAreas
//     * and concatenates their selected text.
//     *
//     * @return The selected text, or an empty string if no text is selected.
//     */
//    public String getSelectedText() {
//        var v = SwingUtil.runOnEdt(() -> {
//            var sb = new StringBuilder();
//            // Iterate over uiComponents within bubbles
//            for (var bubble : bubbles) {
//                // bubble.uiComponent() is the MessageBubble, which contains the renderer's root.
//                // renderer.getRoot() is the JComponent (likely a JPanel) holding the rendered blocks.
//                collectSelectedText(bubble.renderer().getRoot(), sb);
//            }
//            return sb.toString();
//        }, "");
//        return castNonNull(v);
//    }
//
//    /**
//     * Helper method to recursively collect selected text from a component and its children.
//     *
//     * @param comp The component to search within.
//     * @param accumulator A StringBuilder to append selected text to.
//     */
//    private void collectSelectedText(Component comp, StringBuilder accumulator) {
//        String selected = null;
//        if (comp instanceof JTextPane textPane) {
//            selected = textPane.getSelectedText();
//        } else if (comp instanceof JEditorPane editorPane) {
//            selected = editorPane.getSelectedText();
//        } else if (comp instanceof JTextArea textArea) {
//            selected = textArea.getSelectedText();
//        }
//
//        if (selected != null && !selected.isEmpty()) {
//            if (accumulator.length() > 0) {
//                accumulator.append("\n"); // Separator for selections from different components
//            }
//            accumulator.append(selected);
//        }
//
//        if (comp instanceof Container container) {
//            for (var child : container.getComponents()) {
//                collectSelectedText(child, accumulator);
//            }
//        }
//    }
//
//    /**
//     * Copies text to the system clipboard.
//     * If a text component within this panel has focus, its own copy action is triggered.
//     * Otherwise, if there is any selected text (aggregated from `getSelectedText()`), that is copied.
//     * This operation is performed asynchronously after ensuring all content is rendered.
//     */
//    public void copy() {
//        flushPendingChangesAsync().thenRun(() -> SwingUtilities.invokeLater(this::performCopyAction));
//    }
//
//    /**
//     * Performs the actual copy action. This method should be called on the EDT.
//     */
//    private void performCopyAction() {
//        assert SwingUtilities.isEventDispatchThread();
//        // Attempt to delegate to a focused text component first
//        for (var bubble : bubbles) {
//            var focusedTextComponent = findFocusedTextComponentIn(bubble.renderer().getRoot());
//            if (focusedTextComponent != null) {
//                focusedTextComponent.copy(); // Trigger the component's native copy action
//                return;
//            }
//        }
//
//        // If no specific text component is focused, copy aggregated selected text
//        String selectedText = getSelectedText(); // getSelectedText is already EDT-safe
//        if (!selectedText.isEmpty()) {
//            var sel = new java.awt.datatransfer.StringSelection(selectedText);
//            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
//        }
//        // If no text is focused and no text is selected, this method does nothing,
//        // mirroring standard text component behavior.
//    }
//
//    /**
//     * Helper method to find a focused JTextComponent within a given component hierarchy.
//     *
//     * @param comp The root component of the hierarchy to search.
//     * @return The focused JTextComponent if found, otherwise null.
//     */
//    @Nullable
//    private javax.swing.text.JTextComponent findFocusedTextComponentIn(Component comp) {
//        if (comp instanceof javax.swing.text.JTextComponent tc && tc.isFocusOwner()) {
//            return tc;
//        }
//
//        if (comp instanceof Container container) {
//            for (var child : container.getComponents()) {
//                var focused = findFocusedTextComponentIn(child);
//                if (focused != null) {
//                    return focused;
//                }
//            }
//        }
//        return null;
//    }
//
//    // --- Scrollable interface methods ---
//
//    @Override
//    public Dimension getPreferredScrollableViewportSize() {
//        return getPreferredSize();
//    }
//
//    @Override
//    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
//        return 20;
//    }
//
//    @Override
//    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
//        return orientation == SwingConstants.VERTICAL
//               ? visibleRect.height : visibleRect.width;
//    }
//
//    @Override
//    public boolean getScrollableTracksViewportWidth() {
//        return true;
//    }
//
//    @Override
//    public boolean getScrollableTracksViewportHeight() {
//        return false;
//    }
//
//
//
//    /**
//     * Cleans up resources used by this panel, particularly shutting down background thread pools.
//     * This method should be called when the panel is no longer needed (e.g., when its containing window is closed)
//     * to prevent resource leaks.
//     */
//    public void dispose() {
//        logger.debug("Disposing MarkdownOutputPanel and shutting down executors.");
//        // Shut down worker threads for each bubble
//        bubbles.forEach(b -> b.worker().shutdown());
//
//        // Shut down the compaction executor
//        if (!compactExec.isShutdown()) {
//            compactExec.shutdownNow();
//            try {
//                if (!compactExec.awaitTermination(500, TimeUnit.MILLISECONDS)) {
//                    logger.warn("Compaction executor did not terminate in time.");
//                }
//            } catch (InterruptedException e) {
//                logger.warn("Interrupted while waiting for compaction executor to terminate.", e);
//                Thread.currentThread().interrupt();
//            }
//        }
//        // Clear bubbles to release references, though workers are already shut down.
//        bubbles.clear();
//    }
//}
