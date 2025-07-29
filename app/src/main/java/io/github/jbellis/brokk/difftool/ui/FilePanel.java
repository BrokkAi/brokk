package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentChangeListenerIF;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.doc.JMDocumentEvent;
import io.github.jbellis.brokk.difftool.search.SearchHit;
import io.github.jbellis.brokk.difftool.search.SearchHits;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.search.RTextAreaSearchableComponent;
import io.github.jbellis.brokk.gui.search.SearchCommand;
import io.github.jbellis.brokk.gui.search.SearchableComponent;
import io.github.jbellis.brokk.util.SyntaxDetector;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.Font;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import java.awt.*;
import io.github.jbellis.brokk.gui.SwingUtil;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingWorker;

import static java.util.Objects.requireNonNullElseGet;

public class FilePanel implements BufferDocumentChangeListenerIF, ThemeAware {
    private static final Logger logger = LogManager.getLogger(FilePanel.class);

    private final BufferDiffPanel diffPanel;
    private final String name;
    private JPanel visualComponentContainer; // Main container for editor or "new file" label
    private JScrollPane scrollPane;
    private RSyntaxTextArea editor;
    private JMHighlighter jmHighlighter;
    @Nullable
    private BufferDocumentIF bufferDocument;

    /* ------------- mirroring PlainDocument <-> RSyntaxDocument ------------- */
    @Nullable
    private Document plainDocument;
    @Nullable
    private DocumentListener plainToEditorListener;
    @Nullable
    private DocumentListener editorToPlainListener;
    @Nullable
    private Timer timer;
    @Nullable
    private SearchHits searchHits;
    private volatile boolean initialSetupComplete = false;


    // Typing state detection to prevent scroll sync interference
    private final AtomicBoolean isActivelyTyping = new AtomicBoolean(false);
    @Nullable
    private Timer typingStateTimer;

    // Track if updates were deferred during typing and need to be applied
    private final AtomicBoolean hasDeferredUpdates = new AtomicBoolean(false);

    // Track when typing state was last set to detect stuck states
    private volatile long lastTypingStateChange = System.currentTimeMillis();

    // Navigation state to ensure highlights appear when scrolling to diffs
    private final AtomicBoolean isNavigatingToDiff = new AtomicBoolean(false);

    // Background worker handling large-file optimisation off the EDT
    @Nullable
    private volatile SwingWorker<?, ?> optimisationWorker;

    // Flag to force plain text mode for performance with large single-line files
    private volatile boolean forcePlainText = false;

    // Flag to indicate file content was truncated for safety
    private volatile boolean contentTruncated = false;

    // Status panel for showing file truncation and editing status
    private JPanel statusPanel;
    private JLabel statusLabel;

    // Viewport cache for thread-safe access
    private final AtomicReference<ViewportCache> viewportCache = new AtomicReference<>();

    public FilePanel(BufferDiffPanel diffPanel, String name) {
        this.diffPanel = diffPanel;
        this.name = name;
        init();
    }

    private void init() {
        visualComponentContainer = new JPanel(new BorderLayout());

        // Initialize status panel for file notifications
        statusPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 5, 2));
        statusLabel = new JLabel();
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        statusLabel.setForeground(new java.awt.Color(204, 120, 50)); // Orange color for warnings
        statusPanel.add(statusLabel);
        statusPanel.setVisible(false); // Hidden by default

        // Initialize RSyntaxTextArea with composite highlighter
        editor = new RSyntaxTextArea();
        jmHighlighter = new JMHighlighter();

        // Create CompositeHighlighter with JMHighlighter.
        // RSyntaxTextAreaHighlighter (superclass of CompositeHighlighter) handles syntax.
        // It gets the RSyntaxTextArea instance via its install() method.
        // JMHighlighter (secondary) handles diff/search.
        var compositeHighlighter = new CompositeHighlighter(jmHighlighter);
        editor.setHighlighter(compositeHighlighter);  // layered: syntax first, diff/search second

        editor.addFocusListener(getFocusListener());
        // Undo listener will be added in setBufferDocument when editor is active

        // Wrap editor inside a scroll pane with optimized scrolling
        scrollPane = new JScrollPane(editor);
        scrollPane.getViewport().setScrollMode(JViewport.BLIT_SCROLL_MODE);

        // If the document is "ORIGINAL", reposition the scrollbar to the left
        if (BufferDocumentIF.ORIGINAL.equals(name)) {
            LeftScrollPaneLayout layout = new LeftScrollPaneLayout();
            scrollPane.setLayout(layout);
            layout.syncWithScrollPane(scrollPane);
        }

        // Initially, add scrollPane to the visual container
        visualComponentContainer.add(scrollPane, BorderLayout.CENTER);

        // Unified update timer handles both diff updates and highlight redrawing
        timer = new Timer(PerformanceConstants.DEFAULT_UPDATE_TIMER_DELAY_MS, this::handleUnifiedUpdate);
        timer.setRepeats(false);

        // Typing state timer to detect when user stops typing
        typingStateTimer = new Timer(PerformanceConstants.TYPING_STATE_TIMEOUT_MS, e -> {
            try {
                lastTypingStateChange = System.currentTimeMillis();

                // Trigger comprehensive update after typing stops to ensure diff and highlights are current
                if (initialSetupComplete && hasDeferredUpdates.getAndSet(false)) {
                    SwingUtilities.invokeLater(() -> {
                        try {
                            // First update the diff to reflect all document changes made during typing
                            diffPanel.diff();
                            // Then update highlights based on the new diff
                            reDisplayInternal();
                        } catch (Exception ex) {
                            // Ensure typing state is reset even if update fails
                            isActivelyTyping.set(false);
                        }
                    });
                }
            } catch (Exception ex) {
                logger.error("{}: Error in typing state timer callback: {}", name, ex.getMessage(), ex);
                // Always reset typing state to prevent getting stuck
                isActivelyTyping.set(false);
                hasDeferredUpdates.set(false);
            }
        });
        typingStateTimer.setRepeats(false);
        // Apply syntax theme but don't trigger reDisplay yet (no diff data available)
        GuiTheme.loadRSyntaxTheme(diffPanel.isDarkTheme()).ifPresent(theme ->
                theme.apply(editor)
        );

    }

    public JComponent getVisualComponent() {
        return visualComponentContainer;
    }

    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    public RSyntaxTextArea getEditor() {
        return editor;
    }

    /**
     * Creates a SearchableComponent adapter for this FilePanel's editor.
     * This enables the editor to work with GenericSearchBar.
     */
    public SearchableComponent createSearchableComponent() {
        var searchableComponent = RTextAreaSearchableComponent.wrap(getEditor());

        // Set up navigation callback to trigger scroll synchronization
        searchableComponent.setSearchNavigationCallback(caretPosition -> {
            var bufferDoc = getBufferDocument();
            if (bufferDoc != null) {
                int currentLine = bufferDoc.getLineForOffset(caretPosition);
                var scrollSync = diffPanel.getScrollSynchronizer();
                if (scrollSync != null) {
                    scrollSync.scrollToLineAndSync(FilePanel.this, currentLine);
                }
            }
        });

        return searchableComponent;
    }

    @Nullable
    public BufferDocumentIF getBufferDocument() {
        return bufferDocument;
    }

    public void setBufferDocument(@Nullable BufferDocumentIF bd) {
        assert SwingUtilities.isEventDispatchThread();
        Document previousDocument;
        Document newDocument;

        try {
            if (this.bufferDocument != null) {
                this.bufferDocument.removeChangeListener(this);
                previousDocument = this.bufferDocument.getDocument();
                previousDocument.removeUndoableEditListener(diffPanel.getUndoHandler());
            }

            // Ensure any existing mirroring is cleared before setting up new or leaving none.
            // installMirroring will call removeMirroring again, which is harmless.
            removeMirroring();

            // Reset flags for new document
            this.bufferDocument = bd;
            this.forcePlainText = false;
            this.contentTruncated = false;


            // Clear status panel for new document
            clearStatusPanel();
            visualComponentContainer.removeAll(); // Clear previous content

            // Always add the status panel and scrollPane (which contains the editor)
            visualComponentContainer.add(statusPanel, BorderLayout.NORTH);
            visualComponentContainer.add(scrollPane, BorderLayout.CENTER);

            if (bd != null) {
                newDocument = bd.getDocument();
                // Copy text into RSyntaxDocument instead of replacing the model
                String txt = newDocument.getText(0, newDocument.getLength());

                // EARLY MEMORY PROTECTION: Disable syntax before setText() for dense files
                // This prevents RSyntaxTextArea from tokenizing huge single-line files
                if (shouldUsePlainTextMode(bd, txt.length())) {
                    editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
                    forcePlainText = true;

                    // AGGRESSIVE PROTECTION: For extremely large single-line files,
                    // truncate content to prevent memory explosion even in plain text mode
                    ProtectionLevel level = getProtectionLevel(bd, txt.length());
                    if (level == ProtectionLevel.MINIMAL && bd.getNumberOfLines() <= 2) {
                        int maxDisplayLength = 100 * 1024; // 100KB max for single-line display
                        if (txt.length() > maxDisplayLength) {
                            int originalSizeKB = txt.length() / 1024;
                            txt = txt.substring(0, maxDisplayLength);
                            logger.warn("Truncated huge single-line file {} from {}KB to {}KB for safe display",
                                       bd.getName(), originalSizeKB, txt.length() / 1024);

                            // Show truncation status and mark as truncated
                            contentTruncated = true;
                            updateStatusPanel(originalSizeKB);
                        }
                    }
                }

                // PERFORMANCE OPTIMIZATION: Apply file size-based optimizations for large files
                optimiseAsyncIfLarge(txt.length());

                editor.setText(txt);
                editor.setTabSize(PerformanceConstants.DEFAULT_EDITOR_TAB_SIZE);
                bd.addChangeListener(this);

                // Setup bidirectional mirroring between PlainDocument and RSyntaxDocument
                installMirroring(newDocument);

                // Undo tracking on the RSyntaxDocument (what the user edits)
                // Only enable undo/redo for editable files
                if (!contentTruncated) {
                    editor.getDocument().addUndoableEditListener(diffPanel.getUndoHandler());
                }

                // Ensure highlighter is still properly connected after setText
                if (editor.getHighlighter() instanceof CompositeHighlighter) {
                    // Force reinstall to ensure proper binding
                    var highlighter = editor.getHighlighter();
                    highlighter.install(editor);
                }
                // Disable editing for truncated files or readonly files
                boolean shouldBeEditable = !bd.isReadonly() && !contentTruncated;
                editor.setEditable(shouldBeEditable);
                updateSyntaxStyle();            // pick syntax based on filename
            } else {
                // If BufferDocumentIF is null, clear the editor and make it non-editable
                // removeMirroring() was already called above.
                editor.setText("");
                editor.setEditable(false);
            }

            visualComponentContainer.revalidate();
            visualComponentContainer.repaint();

            // Initialize configuration - this sets border etc.
            initConfiguration();

            // Mark initial setup as complete
            initialSetupComplete = true;

            // Scroll to first diff once after initial setup is complete
            SwingUtilities.invokeLater(this::scrollToFirstDiff);

        } catch (Exception ex) {
            diffPanel.getMainPanel().getConsoleIO().toolError(
                "Could not read file or set document: "
                + (bd != null ? bd.getName() : "Unknown")
                + "\n" + ex.getMessage(),
                "Error processing file"
            );
        }
    }

    public void reDisplay() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reDisplay);
            return;
        }

        // Skip reDisplay entirely during active typing to prevent flickering
        if (isActivelyTyping.get()) {
            hasDeferredUpdates.set(true); // Mark that we have updates to apply later
            return;
        }

        // Check if we have deferred updates to apply
        if (hasDeferredUpdates.get()) {
            hasDeferredUpdates.set(false);
        }

        // Call reDisplayInternal directly instead of using timer
        // This fixes the issue where RIGHT panel highlighting wasn't working
        reDisplayInternal();
    }

    private void reDisplayInternal() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        removeHighlights();
        paintSearchHighlights();
        paintRevisionHighlights();
        // Force both the JMHighlighter and editor to repaint
        jmHighlighter.repaint();
        editor.repaint();
    }

    /**
     * Repaint highlights: we get the patch from BufferDiffPanel, then highlight
     * each delta's relevant lines in *this* panel (ORIGINAL or REVISED).
     */
    private void paintRevisionHighlights()
    {
        assert SwingUtilities.isEventDispatchThread() : "NOT ON EDT";
        if (bufferDocument == null) return;

        // Access the shared patch from the parent BufferDiffPanel
        var patch = diffPanel.getPatch();
        if (patch == null) return;

        boolean isOriginal = BufferDocumentIF.ORIGINAL.equals(name);

        // Skip viewport optimization when navigating to ensure highlights appear
        if (isNavigatingToDiff.get()) {
            paintAllDeltas(patch, isOriginal);
            return;
        }

        // For right side, also check if paired left side is navigating
        if (!isOriginal) {
            try {
                var leftPanel = diffPanel.getFilePanel(BufferDiffPanel.PanelSide.LEFT);
                if (leftPanel != null && leftPanel.isNavigatingToDiff()) {
                    paintAllDeltas(patch, isOriginal);
                    return;
                }
            } catch (Exception e) {
                // Continue with normal highlighting
            }
        }

        // Get visible line range with caching for performance
        var visibleRange = getVisibleLineRange();
        if (visibleRange == null) {
            // Fallback to highlighting all deltas if viewport calculation fails
            paintAllDeltas(patch, isOriginal);
            return;
        }

        // Use streams to filter and highlight visible deltas
        patch.getDeltas().stream()
            .filter(delta -> deltaIntersectsViewport(delta, visibleRange.start, visibleRange.end))
            .forEach(delta -> DeltaHighlighter.highlight(this, delta, isOriginal));
    }

    /**
     * Fallback method to paint all deltas (original behavior).
     */
    private void paintAllDeltas(com.github.difflib.patch.Patch<String> patch, boolean isOriginal) {
        patch.getDeltas().forEach(delta -> DeltaHighlighter.highlight(this, delta, isOriginal));
    }

    /**
     * Cached viewport calculation to avoid expensive repeated calls.
     */
    @Nullable
    private VisibleRange getVisibleLineRange() {
        if (bufferDocument == null) {
            return null;
        }

        long now = System.currentTimeMillis();

        // Try to read from cache first (no locking needed - AtomicReference is thread-safe)
        ViewportCache cached = viewportCache.get();
        if (cached != null && cached.isValid(now)) {
            return new VisibleRange(cached.startLine, cached.endLine);
        }

        try {
            var viewport = scrollPane.getViewport();
            var viewRect = viewport.getViewRect();

            // Calculate visible line range with buffer for smooth scrolling
            // Use larger buffer when navigating to ensure target highlights are visible
            int bufferLines = isNavigatingToDiff.get() ?
                PerformanceConstants.VIEWPORT_BUFFER_LINES * 3 :
                PerformanceConstants.VIEWPORT_BUFFER_LINES;
            int lineHeight = editor.getLineHeight();

            // Calculate start/end offsets with buffer
            int startY = Math.max(0, viewRect.y - bufferLines * lineHeight);
            int endY = viewRect.y + viewRect.height + bufferLines * lineHeight;

            int startOffset = editor.viewToModel2D(new Point(0, startY));
            int endOffset = editor.viewToModel2D(new Point(0, endY));

            // Convert to line numbers - bufferDocument is null-checked above
            int startLine = Math.max(0, bufferDocument.getLineForOffset(startOffset));
            int endLine = Math.min(bufferDocument.getNumberOfLines() - 1, bufferDocument.getLineForOffset(endOffset));

            // Cache the result atomically
            viewportCache.set(new ViewportCache(startLine, endLine, now));

            return new VisibleRange(startLine, endLine);

        } catch (Exception e) {
            // Clear the cache on error to prevent repeated failures
            viewportCache.set(null);
            return null;
        }
    }

    /**
     * Check if a delta intersects with the visible line range.
     */
    private boolean deltaIntersectsViewport(AbstractDelta<String> delta, int startLine, int endLine)
    {
        boolean originalSide = BufferDocumentIF.ORIGINAL.equals(name);
        var result = DiffHighlightUtil.isChunkVisible(delta, startLine, endLine, originalSide);

        // Log any warnings from the utility
        if (result.warning() != null)
            logger.warn("{}: {}", name, result.warning());

        return result.intersects();
    }

    /**
     * Clear viewport cache when scrolling to ensure fresh calculations.
     */
    public void invalidateViewportCache() {
        viewportCache.set(null);
    }

    /**
     * Force invalidation of viewport cache for both sides to ensure consistency
     */
    private void invalidateViewportCacheForBothSides() {
        var scrollSync = diffPanel.getScrollSynchronizer();
        if (scrollSync != null) {
            scrollSync.invalidateViewportCacheForBothPanels();
        } else {
            // Fallback to individual invalidation if synchronizer not available
            invalidateViewportCache();
        }
    }

    /**
     * Check if user is actively typing to prevent scroll sync interference.
     */
    public boolean isActivelyTyping() {
        return isActivelyTyping.get();
    }

    /**
     * Force reset typing state - used for recovery from stuck states
     */
    public void forceResetTypingState() {
        boolean wasTyping = isActivelyTyping.getAndSet(false);
        boolean hadDeferred = hasDeferredUpdates.getAndSet(false);

        if (wasTyping || hadDeferred) {

            if (typingStateTimer != null && typingStateTimer.isRunning()) {
                typingStateTimer.stop();
            }

            // Apply any deferred updates
            if (hadDeferred && initialSetupComplete) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        diffPanel.diff();
                        reDisplayInternal();
                    } catch (Exception e) {
                        logger.warn("{}: Error applying deferred updates after force reset: {}",
                                   name, e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * Mark that we're navigating to a diff to ensure highlights appear.
     */
    public void setNavigatingToDiff(boolean navigating) {
        boolean wasNavigating = isNavigatingToDiff.getAndSet(navigating);

        if (navigating) {
            // Invalidate viewport cache for both sides to ensure consistent full highlighting
            invalidateViewportCacheForBothSides();

            // Force immediate repaint when starting navigation to ensure highlights appear
            SwingUtilities.invokeLater(() -> {
                if (isNavigatingToDiff.get()) { // Check if still navigating
                    reDisplayInternal();
                }
            });
        } else if (wasNavigating) {
            // When navigation ends, do a final update to ensure proper highlighting state
            SwingUtilities.invokeLater(this::reDisplayInternal);
        }
    }

    /**
     * Check if this panel is currently navigating to a diff
     */
    public boolean isNavigatingToDiff() {
        return isNavigatingToDiff.get();
    }

    /**
     * Set navigation state for both panels simultaneously to ensure consistency
     */
    public void setNavigatingToDiffForBothSides(boolean navigating) {
        setNavigatingToDiff(navigating);

        // Also set navigation state for the paired panel
        try {
            var pairedPanel = diffPanel.getFilePanel(BufferDocumentIF.REVISED.equals(name) ?
                BufferDiffPanel.PanelSide.LEFT : BufferDiffPanel.PanelSide.RIGHT);
            if (pairedPanel != null && pairedPanel != this) {
                pairedPanel.setNavigatingToDiff(navigating);
            }
        } catch (Exception e) {
            // Continue without setting paired panel state
        }
    }

    /**
     * Protection levels for memory and performance optimization.
     */
    private enum ProtectionLevel {
        NONE,     // Full syntax highlighting and all features enabled
        REDUCED,  // Syntax highlighting but heavy features disabled
        MINIMAL   // Plain text mode, all features disabled
    }

    /**
     * SHARED PROTECTION LOGIC: Determines the appropriate protection level for a file
     * based on line density and size. Used by both synchronous and asynchronous paths.
     */
    private ProtectionLevel getProtectionLevel(@Nullable BufferDocumentIF document, long contentLength) {
        if (document == null) {
            return ProtectionLevel.NONE;
        }

        int numberOfLines = document.getNumberOfLines();
        long averageLineLength = numberOfLines > 0 ? contentLength / numberOfLines : contentLength;

        // Minimal protection: Plain text for extreme cases
        if (averageLineLength > PerformanceConstants.MINIMAL_SYNTAX_LINE_LENGTH_BYTES ||
            (numberOfLines <= 3 && contentLength > PerformanceConstants.SINGLE_LINE_THRESHOLD_BYTES)) {
            return ProtectionLevel.MINIMAL;
        }

        // Reduced protection: Keep syntax but disable heavy features
        if (averageLineLength > PerformanceConstants.REDUCED_SYNTAX_LINE_LENGTH_BYTES) {
            return ProtectionLevel.REDUCED;
        }

        // No protection needed
        return ProtectionLevel.NONE;
    }

    /**
     * EARLY MEMORY PROTECTION: Determines if a file should use plain text mode
     * to prevent memory explosion during setText(). This check runs BEFORE
     * RSyntaxTextArea tokenization to avoid token allocation.
     */
    private boolean shouldUsePlainTextMode(@Nullable BufferDocumentIF document, long contentLength) {
        ProtectionLevel level = getProtectionLevel(document, contentLength);

        return level == ProtectionLevel.MINIMAL;
    }


    /**
     * PERFORMANCE OPTIMIZATION: Apply performance optimizations based on file size.
     */
    private void applyPerformanceOptimizations(long contentLength) {
        // Reset editor features for normal files, but preserve early protection
        if (!forcePlainText) {
            editor.setCodeFoldingEnabled(true);
            editor.setBracketMatchingEnabled(true);
            editor.setMarkOccurrences(true);
        }

        // Apply protection based on shared logic to ensure consistency
        ProtectionLevel level = getProtectionLevel(bufferDocument, contentLength);

        switch (level) {
            case MINIMAL -> {
                if (!forcePlainText && bufferDocument != null) {
                    forcePlainText = true;
                    editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
                }
                editor.setCodeFoldingEnabled(false);
                editor.setBracketMatchingEnabled(false);
                editor.setMarkOccurrences(false);
            }
            case REDUCED -> {
                // Keep syntax highlighting but disable memory-intensive features
                editor.setCodeFoldingEnabled(false);
                editor.setBracketMatchingEnabled(false);
                editor.setMarkOccurrences(false);
            }
            case NONE -> {
                // Features already reset above, no additional changes needed
            }
        }

        boolean isLargeFile = contentLength > PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES;

        if (isLargeFile) {
            logger.info("Applying general performance optimizations for large file: {}KB", contentLength / 1024);

            // Use longer debounce times for large files to reduce update frequency
            if (timer != null) {
                timer.setDelay(PerformanceConstants.LARGE_FILE_UPDATE_TIMER_DELAY_MS);
            }
        } else {
            // Use normal timing for smaller files
            if (timer != null) {
                timer.setDelay(PerformanceConstants.DEFAULT_UPDATE_TIMER_DELAY_MS);
            }
        }
    }

    /**
     * Execute {@link #applyPerformanceOptimizations(long)} asynchronously for
     * large files so the EDT stays responsive. For small files, call it
     * directly to avoid thread overhead.
     */
    private void optimiseAsyncIfLarge(long contentLength) {
        // For files ≤1MB, apply optimizations immediately
        if (contentLength <= PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES) {
            applyPerformanceOptimizations(contentLength);
            return;
        }

        // For large files, if early plain-text protection is already active we
        // still want to run the full optimisation pass (it is safe-idempotent).
        if (forcePlainText) {
            applyPerformanceOptimizations(contentLength);
            return;
        }
        // Cancel any previous optimisation still running
        if (optimisationWorker != null) {
            optimisationWorker.cancel(true);
        }
        optimisationWorker = new SwingWorker<>() {
            @Override protected Void doInBackground() {
                // Potential heavy analysis could be done here.
                return null;
            }
            @Override protected void done() {
                Runnable r = () -> applyPerformanceOptimizations(contentLength);
                if (SwingUtilities.isEventDispatchThread()) {
                    r.run();
                } else {
                    SwingUtilities.invokeLater(r);
                }
                optimisationWorker = null; // clear after completion
            }
        };
        optimisationWorker.execute();
    }

    /**
     * Simple data record for visible line range.
     */
    private record VisibleRange(int start, int end) {}

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        // Apply current theme
        GuiTheme.loadRSyntaxTheme(guiTheme.isDarkTheme()).ifPresent(theme -> {
            // Ensure syntax style is set before applying theme
            if (bufferDocument != null) {
                updateSyntaxStyle();
            }

            // Apply theme to the composite highlighter (which will forward to JMHighlighter)
            if (editor.getHighlighter() instanceof ThemeAware high) {
                high.applyTheme(guiTheme);
            }
            theme.apply(editor);
            reDisplay();
        });
    }



    /** Package-clients (e.g. BufferDiffPanel) need to query the composite highlighter. */
    public JMHighlighter getHighlighter() {
        return jmHighlighter;
    }

    /** Provide access to the parent diff panel for DeltaHighlighter. */
    public BufferDiffPanel getDiffPanel() {
        return diffPanel;
    }

    private void removeHighlights() {
        JMHighlighter jmhl = getHighlighter();
        jmhl.removeHighlights(JMHighlighter.LAYER0);
        jmhl.removeHighlights(JMHighlighter.LAYER1);
        jmhl.removeHighlights(JMHighlighter.LAYER2);
    }

    private void setHighlight(Integer layer, int offset, int size,
                              Highlighter.HighlightPainter highlight) {
        try {
            getHighlighter().addHighlight(layer, offset, size, highlight);
        } catch (BadLocationException ex) {
            // This usually indicates a logic error in calculating offsets/sizes
            throw new RuntimeException("Error adding highlight at offset " + offset + " size " + size, ex);
        }
    }

    boolean isDocumentChanged() {
        return bufferDocument != null && bufferDocument.isChanged();
    }

    /**
     * PERFORMANCE OPTIMIZATION: Unified update handler to coordinate diff and highlight updates.
     * Reduces timer overhead and prevents conflicting operations.
     */
    private void handleUnifiedUpdate(java.awt.event.ActionEvent e) {
        if (!initialSetupComplete) return;

        // Skip updates while actively typing to prevent flickering
        if (isActivelyTyping.get()) {
            // Check for stuck typing state (longer than reasonable typing session)
            long typingDuration = System.currentTimeMillis() - lastTypingStateChange;
            if (typingDuration > PerformanceConstants.TYPING_STATE_TIMEOUT_MS * 5) {
                forceResetTypingState();
            } else {
                return;
            }
        }

        // Ensure we're on EDT for UI operations
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> handleUnifiedUpdate(e));
            return;
        }

        try {
            // First, update the diff (this may change the patch)
            diffPanel.diff();

            // Invalidate viewport cache for both sides to ensure consistent highlighting
            invalidateViewportCacheForBothSides();

            // Then update highlights based on new diff state
            // The viewport optimization will ensure only visible deltas are processed
            reDisplayInternal();

        } catch (Exception ex) {
            logger.warn("Error during unified update: {}", ex.getMessage(), ex);
            // Fallback to individual operations if unified update fails
            try {
                diffPanel.diff();
            } catch (Exception diffEx) {
                logger.error("Fallback diff update failed: {}", diffEx.getMessage(), diffEx);
            }
        }
    }

    @Override
    public void documentChanged(JMDocumentEvent de) {
        // Don't trigger timer during initial setup
        if (!initialSetupComplete) return;

        // Enhanced typing state detection - catch more user-initiated changes
        boolean isUserEdit = de.getDocumentEvent() != null ||
                            (de.getStartLine() != -1 && de.getNumberOfLines() > 0);

        if (isUserEdit) {
            boolean wasTyping = isActivelyTyping.getAndSet(true);
            if (!wasTyping) {
                lastTypingStateChange = System.currentTimeMillis();
            }

            if (typingStateTimer != null && typingStateTimer.isRunning()) {
                typingStateTimer.restart();
            } else if (typingStateTimer != null) {
                typingStateTimer.start();
            } else {
                isActivelyTyping.set(false);
            }
        }

        // Suppress ALL updates during active typing to prevent flickering
        if (isActivelyTyping.get()) {
            hasDeferredUpdates.set(true); // Mark that we have updates to apply later
            return;
        }

        if (de.getStartLine() == -1 && de.getDocumentEvent() == null) {
            // Refresh the diff of whole document.
            if (timer != null) {
                timer.restart();
            }
        } else {
            // Try to update the revision instead of doing a full diff.
            if (!diffPanel.revisionChanged(de)) {
                if (timer != null) {
                    timer.restart();
                }
            }
        }
    }



    public FocusListener getFocusListener() {
        return new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent fe) {
                diffPanel.setSelectedPanel(FilePanel.this);
            }
        };
    }


    private void initConfiguration() {
        Font font = new Font("Arial", Font.PLAIN, 14);
        editor.setBorder(new LineNumberBorder(this));
        FontMetrics fm = editor.getFontMetrics(font);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(fm.getHeight());

        // Only set editable to true if not truncated and not readonly
        if (bufferDocument != null) {
            boolean shouldBeEditable = !bufferDocument.isReadonly() && !contentTruncated;
            editor.setEditable(shouldBeEditable);
        } else {
            editor.setEditable(false);
        }
    }

    /**
     * Chooses a syntax style for the current document based on its filename.
     * Falls back to plain-text when the extension is not recognised.
     */
    private void updateSyntaxStyle() {
        if (forcePlainText) {
            editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
            return;
        }

        /*
         * Heuristic 1: strip well-known VCS/backup suffixes and decide
         *              the style from the remaining extension.
         * Heuristic 2: if still undecided, inherit the style of the
         */
        var style = SyntaxConstants.SYNTAX_STYLE_NONE;

        // --------------------------- Heuristic 1 -----------------------------
        if (bufferDocument != null) {
            var fileName = bufferDocument.getName();
            if (!fileName.isBlank()) {
                // Remove trailing '~'
                var candidate = fileName.endsWith("~")
                                ? fileName.substring(0, fileName.length() - 1)
                                : fileName;

                // Remove dotted suffixes (case-insensitive)
                for (var suffix : List.of("orig", "base", "mine", "theirs", "backup")) {
                    var sfx = "." + suffix;
                    if (candidate.toLowerCase(Locale.ROOT).endsWith(sfx)) {
                        candidate = candidate.substring(0, candidate.length() - sfx.length());
                        break;
                    }
                }

                // Extract extension
                var lastDot = candidate.lastIndexOf('.');
                if (lastDot > 0 && lastDot < candidate.length() - 1) {
                    var ext = candidate.substring(lastDot + 1).toLowerCase(Locale.ROOT);
                    style = SyntaxDetector.fromExtension(ext);
                }
            }
        }

        // --------------------------- Heuristic 2 -----------------------------
        if (SyntaxConstants.SYNTAX_STYLE_NONE.equals(style)) {
            var otherPanel = BufferDocumentIF.ORIGINAL.equals(name)
                             ? diffPanel.getFilePanel(BufferDiffPanel.PanelSide.RIGHT)
                             : diffPanel.getFilePanel(BufferDiffPanel.PanelSide.LEFT);

            if (otherPanel != null) {
                var otherStyle = otherPanel.getEditor().getSyntaxEditingStyle();
                if (!SyntaxConstants.SYNTAX_STYLE_NONE.equals(otherStyle)) {
                    style = otherStyle;
                }
            }
        }

        editor.setSyntaxEditingStyle(style);

        // Scroll to first diff only after document has been set up
        // This avoids flickering during initial setup
    }

    /**
     * Scrolls the editor to show the first diff highlight if available.
     * Only called during initial setup to avoid flickering.
     */
    private void scrollToFirstDiff() {
        // Don't auto-scroll if user is actively typing
        if (isActivelyTyping.get()) {
            return;
        }

        var patch = diffPanel.getPatch();
        if (patch != null && !patch.getDeltas().isEmpty()) {
            var firstDelta = patch.getDeltas().getFirst();
            Chunk<String> relevantChunk = BufferDocumentIF.ORIGINAL.equals(name)
                                          ? firstDelta.getSource()
                                          : firstDelta.getTarget();

            if (relevantChunk == null) { // If no relevant chunk for the first delta on this side
                return;
            }
            int lineToShow = relevantChunk.getPosition();

            if (bufferDocument != null) {
                int offset = bufferDocument.getOffsetForLine(lineToShow);
                if (offset >= 0) {
                    try {
                        editor.setCaretPosition(offset);
                        // Scroll to make the caret visible
                        Rectangle rect = SwingUtil.modelToView(editor, offset);
                        if (rect != null) {
                            editor.scrollRectToVisible(rect);
                        }
                    } catch (Exception e) {
                        // Ignore scroll errors
                    }
                }
            }
        }
    }

    /**
     * Installs bidirectional listeners that keep the PlainDocument belonging to
     * the model and the RSyntaxDocument shown in the editor in sync. Uses a
     * guard flag to avoid infinite recursion.
     */
    private void installMirroring(Document newPlainDoc) {
        removeMirroring();

        this.plainDocument = newPlainDoc;
        var rsyntaxDoc = editor.getDocument();
        var guard = new java.util.concurrent.atomic.AtomicBoolean(false);

        plainToEditorListener = createMirroringListener(this.plainDocument, rsyntaxDoc, guard, true);
        editorToPlainListener = createMirroringListener(rsyntaxDoc, this.plainDocument, guard, false);

        newPlainDoc.addDocumentListener(plainToEditorListener);
        rsyntaxDoc.addDocumentListener(editorToPlainListener);
    }

    /**
     * Creates a DocumentListener for mirroring text between two documents.
     *
     * @param sourceDoc The source document to copy text from.
     * @param destinationDoc The destination document to copy text to.
     * @param guard The AtomicBoolean guard to prevent recursive updates.
     * @param runDestinationUpdateOnEdt If true, updates to the destination document will be scheduled on the EDT.
     * @return A configured DocumentListener.
     */
    private DocumentListener createMirroringListener(Document sourceDoc, Document destinationDoc,
                                                     java.util.concurrent.atomic.AtomicBoolean guard,
                                                     boolean runDestinationUpdateOnEdt) {
        return new DocumentListener() {
            private void performIncrementalSync(DocumentEvent e) {
                Runnable syncTask = () -> {
                    if (!guard.compareAndSet(false, true)) { // Attempt to acquire lock
                        return; // Lock not acquired, another sync operation is in progress
                    }
                    try {
                        syncDocumentChange(e, sourceDoc, destinationDoc);
                    } finally {
                        guard.set(false); // Release lock
                    }
                };

                // Always ensure document mutations happen on EDT
                if (SwingUtilities.isEventDispatchThread()) {
                    syncTask.run();
                } else {
                    SwingUtilities.invokeLater(syncTask);
                }
            }

            private void syncChange(DocumentEvent e) {
                if (runDestinationUpdateOnEdt && !SwingUtilities.isEventDispatchThread()) {
                    // Updates to the destination document (e.g., editor's document) must occur on the EDT.
                    SwingUtilities.invokeLater(() -> performIncrementalSync(e));
                } else {
                    // Already on EDT or EDT not required
                    performIncrementalSync(e);
                }
            }

            @Override public void insertUpdate(DocumentEvent e) { syncChange(e); }
            @Override public void removeUpdate(DocumentEvent e)  { syncChange(e); }
            @Override public void changedUpdate(DocumentEvent e){ syncChange(e); }
        };
    }

    /**
     * Removes previously-installed mirroring listeners, if any.
     */
    private void removeMirroring() {
        // Capture references atomically to prevent race conditions
        Document plain = plainDocument;
        DocumentListener plainListener = plainToEditorListener;
        DocumentListener editorListener = editorToPlainListener;

        // Clear references first to prevent new operations
        plainDocument = null;
        plainToEditorListener = null;
        editorToPlainListener = null;

        // Remove listeners after clearing references
        if (plain != null && plainListener != null) {
            plain.removeDocumentListener(plainListener);
        }
        if (editorListener != null) {
            Document editorDoc = editor.getDocument();
            if (editorDoc != null) {
                editorDoc.removeDocumentListener(editorListener);
            }
        }
    }

    /**
     * Cleanup method to properly dispose of resources when the panel is no longer needed.
     * Should be called when the FilePanel is being disposed to prevent memory leaks.
     */
    public void dispose() {
        // Stop and null timers to prevent leaks
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        if (typingStateTimer != null) {
            typingStateTimer.stop();
            typingStateTimer = null;
        }

        // Remove document listeners
        removeMirroring();

        // Clear buffer document listener
        if (bufferDocument != null) {
            bufferDocument.removeChangeListener(this);
            bufferDocument = null;
        }

        // Clear cached search hits
        searchHits = null;

        // Cancel any optimisation still running
        if (optimisationWorker != null) {
            optimisationWorker.cancel(true);
            optimisationWorker = null;
        }

        // Clear viewport cache
        viewportCache.set(null);

        // Remove focus listeners
        for (FocusListener fl : editor.getFocusListeners()) {
            if (fl == getFocusListener()) {
                editor.removeFocusListener(fl);
            }
        }
    }

    /**
     * Clear viewport cache to free memory
     */
    public void clearViewportCache() {
        viewportCache.set(null);
    }

    /**
     * Clear search cache to free memory
     */
    public void clearSearchCache() {
        searchHits = null; // Clear reference to search results
    }

    /**
     * Synchronizes a specific document change incrementally to preserve cursor position
     * and avoid replacing the entire document content.
     */
    private static void syncDocumentChange(DocumentEvent e, Document sourceDoc, Document destinationDoc) {
        try {
            int offset = e.getOffset();
            int length = e.getLength();

            var eventType = e.getType();
            if (eventType == DocumentEvent.EventType.INSERT) {
                // Get the inserted text from the source document at the event position
                String insertedText = sourceDoc.getText(offset, length);
                // Validate offset is within bounds for destination document
                if (offset <= destinationDoc.getLength()) {
                    // For safety, always use fallback when documents might be out of sync
                    // This prevents the line joining bug by ensuring complete document consistency
                    if (offset + length <= destinationDoc.getLength()) {
                        String existingText = destinationDoc.getText(offset, length);
                        if (existingText.equals(insertedText)) {
                            return; // Text already exists, skip insertion
                        }
                    }

                    // Check if documents are significantly different - if so, use full sync
                    if (Math.abs(sourceDoc.getLength() - destinationDoc.getLength()) > length) {
                        copyTextFallback(sourceDoc, destinationDoc);
                        return;
                    }

                    destinationDoc.insertString(offset, insertedText, null);
                } else {
                    // Offset is beyond destination document - documents are out of sync, use fallback
                    copyTextFallback(sourceDoc, destinationDoc);
                }
            } else if (eventType == DocumentEvent.EventType.REMOVE) {
                // Remove the same range from the destination document
                if (offset < destinationDoc.getLength() && offset + length <= destinationDoc.getLength()) {
                    destinationDoc.remove(offset, length);
                } else {
                    // Range is invalid, use fallback to resync
                    copyTextFallback(sourceDoc, destinationDoc);
                }
            } else if (eventType == DocumentEvent.EventType.CHANGE) {
                // For change events, always use fallback to ensure consistency
                copyTextFallback(sourceDoc, destinationDoc);
            }
        } catch (BadLocationException ex) {
            // Fallback to full document copy only on error
            copyTextFallback(sourceDoc, destinationDoc);
        }
    }

    /**
     * Fallback method for full document synchronization when incremental sync fails.
     * This preserves the original behavior but should only be used as a last resort.
     */
    private static void copyTextFallback(Document src, Document dst) {
        try {
            // Only perform fallback if documents are significantly out of sync
            String srcText = src.getText(0, src.getLength());
            String dstText = dst.getText(0, dst.getLength());

            // If documents are identical, skip the disruptive full copy
            if (srcText.equals(dstText)) {
                return;
            }

            // If documents differ significantly, perform full copy as last resort
            dst.remove(0, dst.getLength());
            dst.insertString(0, srcText, null);
        } catch (BadLocationException e) {
            throw new RuntimeException("Document mirroring fallback failed", e);
        }
    }


    /**
     * Thread-safe viewport cache to store visible line range information.
     */
    private static class ViewportCache {
        final int startLine;
        final int endLine;
        final long timestamp;

        ViewportCache(int startLine, int endLine, long timestamp) {
            this.startLine = startLine;
            this.endLine = endLine;
            this.timestamp = timestamp;
        }

        boolean isValid(long currentTime) {
            return currentTime - timestamp < PerformanceConstants.VIEWPORT_CACHE_VALIDITY_MS;
        }
    }

    public static class LeftScrollPaneLayout
            extends ScrollPaneLayout {
        @Override
        public void layoutContainer(Container parent) {
            ComponentOrientation originalOrientation;

            // Dirty trick to get the vertical scrollbar to the left side of
            //  a scroll-pane.
            originalOrientation = parent.getComponentOrientation();
            parent.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            super.layoutContainer(parent);
            parent.setComponentOrientation(originalOrientation);
        }
    }

    public void doStopSearch() {
        searchHits = null;
        reDisplay();
    }

    private void paintSearchHighlights() {
        if (searchHits == null) {
            return;
        }
        for (SearchHit sh : searchHits.getSearchHits()) {
            setHighlight(JMHighlighter.LAYER2, sh.getFromOffset(),
                         sh.getToOffset(),
                         searchHits.isCurrent(sh)
                                 ? JMHighlightPainter.CURRENT_SEARCH : JMHighlightPainter.SEARCH);
        }
    }

    /**
     * Update the status panel to show file truncation information.
     * Displays a contextual note near the file content instead of a popup.
     */
    private void updateStatusPanel(int originalSizeKB) {
        SwingUtilities.invokeLater(() -> {
            String message = String.format(
                "⚠ File truncated from %d KB to 100 KB for safe display. Editing is disabled",
                originalSizeKB
            );
            statusLabel.setText(message);
            statusPanel.setVisible(true);
            statusPanel.revalidate();
            statusPanel.repaint();

            // Ensure editing is disabled (double-check after status update)
            editor.setEditable(false);
        });
    }

    /**
     * Clear the status panel when loading normal files.
     */
    private void clearStatusPanel() {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("");
            statusPanel.setVisible(false);
            statusPanel.revalidate();
            statusPanel.repaint();
        });
    }
}
