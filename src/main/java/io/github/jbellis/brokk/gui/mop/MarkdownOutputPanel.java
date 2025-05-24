package io.github.jbellis.brokk.gui.mop;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * A Swing JPanel designed to display structured conversations as formatted text content which may include
 * standard Markdown, Markdown code fences (```), and Brokk-specific `SEARCH/REPLACE` edit blocks.
 * <p>
 * The panel internally maintains a list of {@link ChatMessage} objects, each representing a
 * message in the conversation (AI, User, System, etc.). Each message is rendered according to its type:
 *
 * <ul>
 *   <li>AI messages are parsed for edit blocks first, and if found, they are rendered with special formatting.
 *       Otherwise, they are rendered as Markdown with code syntax highlighting.</li>
 *   <li>User messages are rendered as plain text or simple Markdown.</li>
 *   <li>System and other message types are rendered as plain text.</li>
 * </ul>
 * <p>
 * The panel updates incrementally when messages are appended, only re-rendering the affected message
 * rather than the entire content, which prevents flickering during streaming updates.
 */
public class MarkdownOutputPanel extends JPanel implements Scrollable {
    private static final Logger logger = LogManager.getLogger(MarkdownOutputPanel.class);

    // Holds the structured messages that have been added to the panel
    private final List<ChatMessage> messages = new ArrayList<>();

    // Parallel list of UI components for each message (1:1 mapping with messages)
    private final List<Component> messageComponents = new ArrayList<>();
    
    // Track renderers for each message (parallel to messageComponents)
    private final List<IncrementalBlockRenderer> messageRenderers = new ArrayList<>();
    
    // For streaming work off the EDT
    private StreamingWorker worker;

    // Listeners to notify whenever text changes
    private final List<Runnable> textChangeListeners = new ArrayList<>();

    // Theme-related fields
    private boolean isDarkTheme = false;
    private boolean blockClearAndReset = false;

    public MarkdownOutputPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(true);
    }

    /**
     * Updates the theme colors used by this panel. Must be called before adding text,
     * or whenever you want to re-theme existing blocks.
     */
    public void updateTheme(boolean isDark) {
        this.isDarkTheme = isDark;

        var backgroundColor = ThemeColors.getColor(isDark, "chat_background");
        setOpaque(true);
        setBackground(backgroundColor);

        var parent = getParent();
        if (parent instanceof JViewport vp) {
            vp.setOpaque(true);
            vp.setBackground(backgroundColor);
            var gp = vp.getParent();
            if (gp instanceof JScrollPane sp) {
                sp.setOpaque(true);
                sp.setBackground(backgroundColor);
            }
        }

        // Update spinner background if visible
        if (spinnerPanel != null) {
            spinnerPanel.updateBackgroundColor(backgroundColor);
        }

        // Re-render all components with new theme
        setText(messages);

        revalidate();
        repaint();
    }

    /**
     * Sets the blocking state that prevents clearing or resetting the panel contents.
     * When blocking is enabled, clear() and setText() methods will be ignored.
     * 
     * @param blocked true to prevent clear/reset operations, false to allow them
     */
    public void setBlocking(boolean blocked) {
        this.blockClearAndReset = blocked; 
        // use the unblocking state as trigger for compacting markdown
        if (!blocked) {
            compactAllMessages();
        }
    }

    /**
     * Returns the current blocking state.
     *
     * @return true if clear/reset operations are blocked, false otherwise
     */
    public boolean isBlocking() {
        return blockClearAndReset;
    }
    
    /**
     * Clears all text and displayed components.
     */
    public void clear() {
        if (blockClearAndReset) {
            logger.debug("Ignoring clear() request while blocking is enabled");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            logger.debug("Clearing all content from MarkdownOutputPanel");
            internalClear();
            revalidate();
            repaint();
        });
        textChangeListeners.forEach(Runnable::run); // Notify listeners about the change
    }

    /**
     * Internal helper to clear all state
     */
    private void internalClear() {
        if (worker != null) {
            worker.shutdown();
            worker = null;
        }
        messages.clear();
        messageComponents.clear();
        messageRenderers.clear();
        removeAll();
        spinnerPanel = null;
    }

    /**
     * Appends a new message or content to the last message if type matches.
     * This is the primary method for adding content during streaming.
     *
     * @param text The text content to append
     * @param type The type of message being appended
     */
    public void append(String text, ChatMessageType type) {
        assert text != null && type != null;
        if (text.isEmpty()) {
            return;
        }

        // Check if we're appending to an existing message of the same type
        if (!messages.isEmpty() && messages.getLast().type() == type) {
            // Append to existing message
            updateLastMessage(text);
        } else {
            // Create a new message
            ChatMessage newMessage = Messages.create(text, type);
            addNewMessage(newMessage);
        }

        textChangeListeners.forEach(Runnable::run);
    }

    /**
     * Updates the last message by appending text to it
     */
    private void updateLastMessage(String additionalText) {
        assert SwingUtilities.isEventDispatchThread();
        
        if (messages.isEmpty()) return;

        var lastMessage = messages.getLast();
        var type = lastMessage.type();
        
        // Queue the chunk for background processing
        worker.appendChunk(additionalText);
        
        // Update our model with the combined text
        var updatedText = Messages.getRepr(lastMessage) + additionalText;
        ChatMessage updatedMessage = Messages.create(updatedText, type);
        messages.set(messages.size() - 1, updatedMessage);

        // No need to call revalidate/repaint - worker will do this when parsing is done
    }

    /**
     * Adds a new message to the display
     */
    private void addNewMessage(ChatMessage message) {
        // Shutdown previous worker if exists
        if (worker != null) {
            worker.shutdown();
        }
        
        // Add to our message list
        messages.add(message);

        // If spinner is showing, remove it temporarily
        boolean spinnerWasVisible = false;
        if (spinnerPanel != null) {
            remove(spinnerPanel);
            spinnerWasVisible = true;
        }

        // Determine styling based on message type
        String title = null;
        String iconText = null;
        Color highlightColor = null;
        
        switch (message.type()) {
            case AI:
                title = "Brokk";
                iconText = "\uD83D\uDCBB"; // Unicode for computer emoji
                highlightColor = ThemeColors.getColor(isDarkTheme, "message_border_ai");
                break;
            case USER:
                title = "You";
                iconText = "\uD83D\uDCBB"; // Unicode for computer emoji
                highlightColor = ThemeColors.getColor(isDarkTheme, "message_border_user");
                break;
            case CUSTOM:
            case SYSTEM:
                title = "System";
                iconText = "\uD83D\uDCBB"; // Unicode for computer emoji
                highlightColor = ThemeColors.getColor(isDarkTheme, "message_border_custom");
                break;
            default:
                title = message.type().toString();
                iconText = "\uD83D\uDCBB"; // Unicode for computer emoji
                highlightColor = ThemeColors.getColor(isDarkTheme, "message_border_custom");
        }
        
        // Create a new renderer for this message - disable edit blocks for user messages
        boolean enableEditBlocks = message.type() != ChatMessageType.USER;
        var renderer = new IncrementalBlockRenderer(isDarkTheme, enableEditBlocks);
        messageRenderers.add(renderer);
        
        // Start a worker for this message
        worker = new StreamingWorker(renderer);
        
        // Create the base panel with the renderer's root component
        var basePanel = new MessageBubble(
            title, 
            iconText, 
            renderer.getRoot(), 
            isDarkTheme, 
            highlightColor
        );
        
        // Add the component to our UI
        messageComponents.add(basePanel);
        add(basePanel);
        
        // Process the message content through the worker instead of directly
        worker.appendChunk(Messages.getText(message));

        // Re-add spinner if it was visible
        if (spinnerWasVisible) {
            add(spinnerPanel);
        }

        revalidate();
        repaint();
    }

    /**
     * Sets the content from a list of ChatMessages
     */
    public void setText(ContextFragment.TaskFragment newOutput) {
        if (blockClearAndReset) {
            logger.debug("Ignoring setText() request while blocking is enabled");
            return;
        }
        internalClear();

        if (newOutput == null) {
            return;
        }

        setText(newOutput.messages());
    }

    // private for changing theme -- parser doesn't need to change
    private void setText(List<ChatMessage> messages) {
        if (blockClearAndReset && !this.messages.isEmpty()) {
            logger.debug("Ignoring private setText() request while blocking is enabled");
            return;
        }
        
        for (var message : messages) {
            addNewMessage(message);
        }
        revalidate();
        repaint();
        textChangeListeners.forEach(Runnable::run);
    }

    /**
     * Sets the content from a TaskEntry
     */
    public void setText(TaskEntry taskEntry) {
        if (blockClearAndReset) {
            logger.debug("Ignoring setText(TaskEntry) request while blocking is enabled");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (taskEntry == null) {
                clear();
                return;
            }

            if (taskEntry.isCompressed()) {
                setText(List.of(Messages.customSystem(taskEntry.summary())));
            } else {
                var taskFragment = taskEntry.log();
                setText(taskFragment.messages());
            }
        });
    }

    /**
     * Returns text representation of all messages.
     * For backward compatibility with code that expects a String.
     */
    public String getText() {
        if (!SwingUtilities.isEventDispatchThread()) {
            flushPendingChangesSync();
        }
        return messages.stream()
                .map(Messages::getRepr)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Returns the raw ChatMessage objects.
     * Similar to getMessages() but with a more descriptive name.
     *
     * @return An unmodifiable list of the current messages
     */
    public List<ChatMessage> getRawMessages() {
        if (!SwingUtilities.isEventDispatchThread()) {
            flushPendingChangesSync();
        }
        return Collections.unmodifiableList(messages);
    }

    /**
     * Let callers listen for changes in the text.
     */
    public void addTextChangeListener(Runnable listener) {
        textChangeListeners.add(listener);
    }


    // --- Spinner Logic ---

    // We keep a reference to the spinner panel itself, so we can remove it later
    private SpinnerIndicatorPanel spinnerPanel = null;

    /**
     * Shows a small spinner (or message) at the bottom of the panel,
     * underneath whatever content the user just appended.
     * If already showing, does nothing.
     *
     * @param message The text to display next to the spinner (e.g. "Thinking...")
     */
    public void showSpinner(String message) {
        if (spinnerPanel != null) {
            // Already showing, update the message and return
                spinnerPanel.setMessage(message);
                return;
            }
            // Create a new spinner instance each time
            spinnerPanel = new SpinnerIndicatorPanel(message, isDarkTheme, 
                                 ThemeColors.getColor(isDarkTheme, "chat_background"));

        // Add to the end of this panel. Since we have a BoxLayout (Y_AXIS),
        // it shows up below the existing rendered content.
        add(spinnerPanel);

        revalidate();
        repaint();
    }

    /**
     * Hides the spinner panel if it is visible, removing it from the UI.
     */
    public void hideSpinner() {
        if (spinnerPanel == null) {
            return; // not showing
        }
        remove(spinnerPanel); // Remove from components
        spinnerPanel = null; // Release reference

        revalidate();
        repaint();
    }

    /**
     * Get the current messages in the panel.
     * This is useful for code that needs to access the structured message data.
     *
     * @return An unmodifiable list of the current messages
     */
    public List<ChatMessage> getMessages() {
        if (!SwingUtilities.isEventDispatchThread()) {
            flushPendingChangesSync();
        }
        return Collections.unmodifiableList(messages);
    }

    /**
     * Returns a future that completes when the worker is idle and all pending changes are flushed.
     * The future completes on the EDT.
     */
    private CompletableFuture<Void> flushPendingChangesAsync() {
        return (worker == null) ? CompletableFuture.completedFuture(null)
                                : worker.flushAsync();
    }

    /**
     * Synchronously flushes pending changes. MUST NOT be called on EDT.
     */
    private void flushPendingChangesSync() {
        assert !SwingUtilities.isEventDispatchThread() : "flushPendingChangesSync must not be called on EDT";
        if (worker != null) {
            worker.flush();    // blocks
        }
    }
    
    /**
     * Compacts all messages by merging consecutive Markdown blocks in each message renderer.
     * This improves the user experience for selecting text across multiple Markdown blocks.
     * This operation is performed asynchronously and completes on the EDT.
     */
    public void compactAllMessages() {
        flushPendingChangesAsync().thenRun(() -> {
            assert SwingUtilities.isEventDispatchThread(); // Future from flushAsync completes on EDT
            for (var renderer : messageRenderers) {
                renderer.compactMarkdown();
            }
            revalidate();
            repaint();
            logger.debug("Compacted all messages for better text selection");
        });
    }

    // --- Text Access and Manipulation Methods for Copy/Paste ---

    /**
     * Returns all displayed text content from the messages.
     * This concatenates the pure text of each message.
     *
     * @return A string containing all displayed text.
     */
    public String getDisplayedText() {
        if (!SwingUtilities.isEventDispatchThread()) {
            flushPendingChangesSync();
        }
        return messages.stream()
                .map(Messages::getText) // Gets the actual content of the message
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Gets the currently selected text from all underlying text components.
     * Traverses the component hierarchy to find JTextPanes, JEditorPanes, or JTextAreas
     * and concatenates their selected text.
     *
     * @return The selected text, or an empty string if no text is selected.
     */
    public String getSelectedText() {
        return SwingUtil.runOnEdt(() -> {
            var sb = new StringBuilder();
            for (var renderer : messageRenderers) {
                // renderer.getRoot() is the JComponent (likely a JPanel) holding the rendered blocks for a single message.
                // We need to look inside this root for text components.
                collectSelectedText(renderer.getRoot(), sb);
            }
            return sb.toString();
        }, "");
    }

    /**
     * Helper method to recursively collect selected text from a component and its children.
     *
     * @param comp The component to search within.
     * @param accumulator A StringBuilder to append selected text to.
     */
    private void collectSelectedText(Component comp, StringBuilder accumulator) {
        String selected = null;
        if (comp instanceof JTextPane textPane) {
            selected = textPane.getSelectedText();
        } else if (comp instanceof JEditorPane editorPane) {
            selected = editorPane.getSelectedText();
        } else if (comp instanceof JTextArea textArea) {
            selected = textArea.getSelectedText();
        }

        if (selected != null && !selected.isEmpty()) {
            if (accumulator.length() > 0) {
                accumulator.append("\n"); // Separator for selections from different components
            }
            accumulator.append(selected);
        }

        if (comp instanceof Container container) {
            for (var child : container.getComponents()) {
                collectSelectedText(child, accumulator);
            }
        }
    }

    /**
     * Copies text to the system clipboard.
     * If a text component within this panel has focus, its own copy action is triggered.
     * Otherwise, if there is any selected text (aggregated from `getSelectedText()`), that is copied.
     * This operation is performed asynchronously after ensuring all content is rendered.
     */
    public void copy() {
        flushPendingChangesAsync().thenRun(() -> SwingUtilities.invokeLater(this::performCopyAction));
    }

    /**
     * Performs the actual copy action. This method should be called on the EDT.
     */
    private void performCopyAction() {
        assert SwingUtilities.isEventDispatchThread();
        // Attempt to delegate to a focused text component first
        for (var renderer : messageRenderers) {
            var focusedTextComponent = findFocusedTextComponentIn(renderer.getRoot());
            if (focusedTextComponent != null) {
                focusedTextComponent.copy(); // Trigger the component's native copy action
                return;
            }
        }

        // If no specific text component is focused, copy aggregated selected text
        String selectedText = getSelectedText(); // getSelectedText is already EDT-safe
        if (selectedText != null && !selectedText.isEmpty()) {
            var sel = new java.awt.datatransfer.StringSelection(selectedText);
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
        }
        // If no text is focused and no text is selected, this method does nothing,
        // mirroring standard text component behavior.
    }

    /**
     * Helper method to find a focused JTextComponent within a given component hierarchy.
     *
     * @param comp The root component of the hierarchy to search.
     * @return The focused JTextComponent if found, otherwise null.
     */
    private javax.swing.text.JTextComponent findFocusedTextComponentIn(Component comp) {
        if (comp instanceof javax.swing.text.JTextComponent tc && tc.isFocusOwner()) {
            return tc;
        }

        if (comp instanceof Container container) {
            for (var child : container.getComponents()) {
                var focused = findFocusedTextComponentIn(child);
                if (focused != null) {
                    return focused;
                }
            }
        }
        return null;
    }

    // --- Scrollable interface methods ---

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 20;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL
               ? visibleRect.height : visibleRect.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
