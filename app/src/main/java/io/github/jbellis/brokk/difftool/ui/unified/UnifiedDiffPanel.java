package io.github.jbellis.brokk.difftool.ui.unified;

import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.ui.AbstractDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferSource;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.util.SyntaxDetector;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jetbrains.annotations.Nullable;

/**
 * Unified diff panel that displays diffs in GitHub-style unified format. This implementation starts as read-only and
 * provides navigation between hunks.
 */
public class UnifiedDiffPanel extends AbstractDiffPanel {
    private static final Logger logger = LogManager.getLogger(UnifiedDiffPanel.class);

    // Legacy fields for backward compatibility (deprecated)
    @Deprecated
    @Nullable
    private final BufferSource leftSource;

    @Deprecated
    @Nullable
    private final BufferSource rightSource;

    private final RSyntaxTextArea textArea;
    private final RTextScrollPane scrollPane;

    @Nullable
    private UnifiedDiffDocument unifiedDocument;

    @Nullable
    private UnifiedDiffNavigator navigator;

    private boolean autoScrollFlag = true;

    // Custom document filter for line-level editing control (initialized in setupUI)
    @SuppressWarnings("unused") // Will be used for future editing support
    private UnifiedDiffDocumentFilter documentFilter;

    /**
     * Preferred constructor using JMDiffNode (integrates with existing diff processing pipeline).
     *
     * @param parent The parent BrokkDiffPanel
     * @param theme The GUI theme
     * @param diffNode The JMDiffNode containing pre-processed diff information
     */
    public UnifiedDiffPanel(BrokkDiffPanel parent, GuiTheme theme, JMDiffNode diffNode) {
        super(parent, theme);
        this.leftSource = null;
        this.rightSource = null;

        // Create text area for unified diff display
        this.textArea = new RSyntaxTextArea();
        this.scrollPane = new RTextScrollPane(textArea);

        setupUI();
        setDiffNode(diffNode);
    }

    /**
     * Legacy constructor using BufferSource (deprecated - use JMDiffNode constructor instead). This constructor is
     * retained for backward compatibility.
     *
     * @param parent The parent BrokkDiffPanel
     * @param theme The GUI theme
     * @param leftSource Source for left side
     * @param rightSource Source for right side
     */
    @Deprecated
    public UnifiedDiffPanel(BrokkDiffPanel parent, GuiTheme theme, BufferSource leftSource, BufferSource rightSource) {
        super(parent, theme);
        this.leftSource = leftSource;
        this.rightSource = rightSource;

        // Create text area for unified diff display
        this.textArea = new RSyntaxTextArea();
        this.scrollPane = new RTextScrollPane(textArea);

        setupUI();
        generateDiffFromBufferSources();
    }

    /** Set up the UI components and layout. */
    private void setupUI() {
        setLayout(new BorderLayout());
        add(scrollPane, BorderLayout.CENTER);

        // Configure text area
        textArea.setEditable(false); // Start as read-only
        textArea.setCodeFoldingEnabled(false);
        textArea.setAntiAliasingEnabled(true);
        textArea.setAutoIndentEnabled(false);
        textArea.setTabsEmulated(true);
        textArea.setTabSize(4);

        // Set up custom document filter for future editing support
        this.documentFilter = new UnifiedDiffDocumentFilter();

        // Add scroll pane configuration
        scrollPane.setLineNumbersEnabled(true);
        scrollPane.setFoldIndicatorEnabled(false);

        logger.debug("UnifiedDiffPanel UI setup complete");
    }

    /** Generate the unified diff content from JMDiffNode (preferred approach). */
    private void generateDiffFromDiffNode(JMDiffNode diffNode) {
        var contextMode = UnifiedDiffDocument.ContextMode.STANDARD_3_LINES; // Default
        this.unifiedDocument = UnifiedDiffGenerator.generateFromDiffNode(diffNode, contextMode);

        textArea.setDocument(unifiedDocument);
        this.navigator = new UnifiedDiffNavigator(unifiedDocument, textArea);

        // Apply syntax highlighting and theme
        applySyntaxHighlighting();
        applyDiffColoring();

        logger.debug(
                "Generated unified diff from JMDiffNode {} with {} lines",
                diffNode.getName(),
                unifiedDocument.getLineCount());
    }

    /** Generate the unified diff content from BufferSources (legacy approach). */
    @Deprecated
    private void generateDiffFromBufferSources() {
        if (leftSource == null || rightSource == null) {
            logger.error("Cannot generate diff from BufferSources - one or both sources are null");
            throw new IllegalStateException("Cannot generate diff - BufferSources are null");
        }

        var contextMode = UnifiedDiffDocument.ContextMode.STANDARD_3_LINES; // Default
        this.unifiedDocument = UnifiedDiffGenerator.generateUnifiedDiff(leftSource, rightSource, contextMode);

        textArea.setDocument(unifiedDocument);
        this.navigator = new UnifiedDiffNavigator(unifiedDocument, textArea);

        // Apply syntax highlighting and theme
        applySyntaxHighlighting();
        applyDiffColoring();

        logger.debug("Generated unified diff from BufferSources with {} lines", unifiedDocument.getLineCount());
    }

    @Override
    public void setDiffNode(@Nullable JMDiffNode diffNode) {
        super.setDiffNode(diffNode);
        if (diffNode != null) {
            generateDiffFromDiffNode(diffNode);
        } else {
            logger.warn("setDiffNode called with null - clearing unified diff content");
            // Clear the content when no diff node is provided
            this.unifiedDocument = new UnifiedDiffDocument(
                    new java.util.ArrayList<>(), UnifiedDiffDocument.ContextMode.STANDARD_3_LINES);
            textArea.setDocument(unifiedDocument);
            this.navigator = new UnifiedDiffNavigator(unifiedDocument, textArea);
        }
    }

    /** Apply syntax highlighting based on detected file type using BufferDiffPanel's robust logic. */
    private void applySyntaxHighlighting() {
        updateSyntaxStyle(); // Use same logic as BufferDiffPanel
        logger.debug("Applied syntax highlighting using BufferDiffPanel logic");
    }

    /**
     * Chooses a syntax style for the current document based on its filename.
     * Uses shared logic from AbstractDiffPanel.detectSyntaxStyle().
     */
    private void updateSyntaxStyle() {
        var diffNode = getDiffNode();
        var filename = diffNode != null ? diffNode.getName() : null;

        // Note: In unified diff view, we can't inherit from side-by-side panels
        // since they may not exist, so we pass null for fallbackEditor
        var style = AbstractDiffPanel.detectSyntaxStyle(filename, null);

        textArea.setSyntaxEditingStyle(style);
        logger.debug("Set syntax style to: {} for unified diff", style);
    }

    /** Apply diff-specific coloring to the text area (now handled by syntax highlighting). */
    private void applyDiffColoring() {
        // Diff coloring is now integrated with syntax highlighting in applySyntaxHighlighting()
        // This method is kept for compatibility but no longer needed
        logger.debug("Diff coloring integrated with syntax highlighting - no separate coloring needed");
    }



    /** Set the context mode for the unified diff. */
    public void setContextMode(UnifiedDiffDocument.ContextMode contextMode) {
        if (unifiedDocument != null && unifiedDocument.getContextMode() != contextMode) {
            logger.debug("Switching context mode from {} to {}", unifiedDocument.getContextMode(), contextMode);

            unifiedDocument.switchContextMode(contextMode);
            if (navigator != null) {
                navigator.refreshHunkPositions();
            }

            // Reapply coloring after content change
            applyDiffColoring();

            textArea.revalidate();
            textArea.repaint();
        }
    }

    /** Get the current context mode. */
    public UnifiedDiffDocument.ContextMode getContextMode() {
        return unifiedDocument != null
                ? unifiedDocument.getContextMode()
                : UnifiedDiffDocument.ContextMode.STANDARD_3_LINES;
    }

    // IDiffPanel implementation

    @Override
    public boolean isUnifiedView() {
        return true;
    }

    @Override
    public void resetAutoScrollFlag() {
        this.autoScrollFlag = true;
    }

    @Override
    public void resetToFirstDifference() {
        if (navigator != null) {
            navigator.goToFirstHunk();
        }
    }

    @Override
    public void diff(boolean autoScroll) {
        // Note: No need to regenerate diff since it's already processed in JMDiffNode
        // This method is primarily for navigation and scrolling

        if (autoScroll && autoScrollFlag) {
            resetToFirstDifference();
            autoScrollFlag = false;
        }

        // Ensure diff coloring is applied (in case theme changed)
        applyDiffColoring();
    }

    @Override
    public void doUp() {
        if (navigator != null) {
            navigator.navigateToPreviousHunk();
        }
    }

    @Override
    public void doDown() {
        if (navigator != null) {
            navigator.navigateToNextHunk();
        }
    }

    @Override
    public boolean isAtFirstLogicalChange() {
        return navigator == null || navigator.isAtFirstHunk();
    }

    @Override
    public boolean isAtLastLogicalChange() {
        return navigator == null || navigator.isAtLastHunk();
    }

    @Override
    public void goToLastLogicalChange() {
        if (navigator != null) {
            navigator.goToLastHunk();
        }
    }

    @Override
    public String getTitle() {
        // Try to get title from JMDiffNode first (preferred)
        var diffNode = getDiffNode();
        if (diffNode != null) {
            var nodeName = diffNode.getName();
            if (nodeName != null && !nodeName.isEmpty()) {
                // Extract filename from path for display
                var lastSlash = nodeName.lastIndexOf('/');
                var displayName = lastSlash >= 0 && lastSlash < nodeName.length() - 1
                        ? nodeName.substring(lastSlash + 1)
                        : nodeName;
                return displayName + " (Unified)";
            }
        }

        // Fallback to legacy BufferSource approach
        if (leftSource != null && rightSource != null) {
            var leftName = leftSource.title();
            var rightName = rightSource.title();
            var title = leftName.equals(rightName) ? leftName : leftName + " vs " + rightName;
            // Add unified indicator
            return title + " (Unified)";
        }

        // Ultimate fallback
        return "Unified Diff";
    }

    @Override
    public boolean hasUnsavedChanges() {
        // TODO: Implement change tracking for editing support
        return false; // Read-only for now
    }

    @Override
    public boolean isUndoEnabled() {
        // TODO: Implement undo support
        return false;
    }

    @Override
    public boolean isRedoEnabled() {
        // TODO: Implement redo support
        return false;
    }

    @Override
    public void doUndo() {
        // TODO: Implement
        logger.debug("Undo requested (not implemented)");
    }

    @Override
    public void doRedo() {
        // TODO: Implement
        logger.debug("Redo requested (not implemented)");
    }

    @Override
    public void recalcDirty() {
        // TODO: Implement change tracking
        logger.trace("Recalc dirty requested (not implemented)");
    }

    @Override
    public List<BufferDiffPanel.AggregatedChange> collectChangesForAggregation() {
        // TODO: Implement for editing support
        return List.of();
    }

    @Override
    public BufferDiffPanel.SaveResult writeChangedDocuments() {
        // TODO: Implement for editing support
        return new BufferDiffPanel.SaveResult(Set.of(), Map.of());
    }

    @Override
    public void finalizeAfterSaveAggregation(Set<String> successfulFiles) {
        // TODO: Implement
        logger.debug("Finalize after save aggregation requested (not implemented)");
    }

    @Override
    public void refreshComponentListeners() {
        // Update navigator if caret position changed
        if (navigator != null) {
            navigator.updateCurrentHunkFromCaret();
        }
    }

    @Override
    public void clearCaches() {
        // Clear any internal caches
        logger.debug("Clear caches requested");
    }

    @Override
    public void dispose() {
        // Clean up resources
        super.dispose();
        if (unifiedDocument != null) {
            // Any cleanup needed for the document
        }
        logger.debug("UnifiedDiffPanel disposed");
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        SwingUtilities.invokeLater(() -> {
            // Update colors and fonts based on new theme using UIManager
            Color bg = UIManager.getColor("TextArea.background");
            Color fg = UIManager.getColor("TextArea.foreground");
            Color currentLineBg = UIManager.getColor("TextArea.selectionBackground");
            Color selectionBg = UIManager.getColor("TextArea.selectionBackground");
            Color selectionFg = UIManager.getColor("TextArea.selectionForeground");

            // Apply with fallbacks
            if (bg != null) textArea.setBackground(bg);
            if (fg != null) textArea.setForeground(fg);
            if (currentLineBg != null) textArea.setCurrentLineHighlightColor(currentLineBg);
            if (selectionBg != null) textArea.setSelectionColor(selectionBg);
            if (selectionFg != null) textArea.setSelectedTextColor(selectionFg);

            // Reapply diff coloring with new theme
            applyDiffColoring();

            textArea.revalidate();
            textArea.repaint();

            logger.debug("Applied theme to UnifiedDiffPanel");
        });
    }

    /**
     * Custom document filter for controlling which lines can be edited. This will be used when editing support is
     * added.
     */
    private class UnifiedDiffDocumentFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            if (isEditAllowed(offset)) {
                super.insertString(fb, offset, string, attr);
            } else {
                logger.debug("Insert blocked at offset {} (non-editable line)", offset);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            if (isEditAllowed(offset)) {
                super.replace(fb, offset, length, text, attrs);
            } else {
                logger.debug("Replace blocked at offset {} (non-editable line)", offset);
            }
        }

        @Override
        public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
            if (isEditAllowed(offset)) {
                super.remove(fb, offset, length);
            } else {
                logger.debug("Remove blocked at offset {} (non-editable line)", offset);
            }
        }

        private boolean isEditAllowed(int offset) {
            try {
                int line = textArea.getLineOfOffset(offset);
                return unifiedDocument != null && unifiedDocument.isLineEditable(line);
            } catch (BadLocationException e) {
                logger.warn("Failed to check edit permission for offset {}", offset, e);
                return false; // Deny edit if we can't determine
            }
        }
    }

    /** Get navigation info for debugging. */
    public String getNavigationInfo() {
        return navigator != null ? navigator.getNavigationInfo() : "No navigator";
    }
}
