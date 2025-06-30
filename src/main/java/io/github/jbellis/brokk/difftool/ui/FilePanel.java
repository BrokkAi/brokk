package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentChangeListenerIF;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.doc.JMDocumentEvent;
import io.github.jbellis.brokk.difftool.search.SearchHit;
import io.github.jbellis.brokk.difftool.search.SearchHits;
import io.github.jbellis.brokk.difftool.utils.Colors;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
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

    // Navigation state to ensure highlights appear when scrolling to diffs
    private final AtomicBoolean isNavigatingToDiff = new AtomicBoolean(false);

    // Viewport cache for thread-safe access
    private final AtomicReference<ViewportCache> viewportCache = new AtomicReference<>();

    public FilePanel(@NotNull BufferDiffPanel diffPanel, @NotNull String name) {
        this.diffPanel = diffPanel;
        this.name = name;
        init();
    }

    private void init() {
        visualComponentContainer = new JPanel(new BorderLayout());

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
            isActivelyTyping.set(false);
            // Trigger comprehensive update after typing stops to ensure diff and highlights are current
            if (initialSetupComplete && hasDeferredUpdates.getAndSet(false)) {
                logger.trace("Typing stopped, applying deferred updates");
                SwingUtilities.invokeLater(() -> {
                    // First update the diff to reflect all document changes made during typing
                    diffPanel.diff();
                    // Then update highlights based on the new diff
                    reDisplayInternal();
                });
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
        return RTextAreaSearchableComponent.wrap(getEditor());
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

            this.bufferDocument = bd;
            visualComponentContainer.removeAll(); // Clear previous content

            // Always add the scrollPane (which contains the editor)
            visualComponentContainer.add(scrollPane, BorderLayout.CENTER);

            if (bd != null) {
                newDocument = bd.getDocument();
                // Copy text into RSyntaxDocument instead of replacing the model
                String txt = newDocument.getText(0, newDocument.getLength());

                // PERFORMANCE OPTIMIZATION: Apply file size-based optimizations for large files
                applyPerformanceOptimizations(txt.length());

                editor.setText(txt);
                editor.setTabSize(PerformanceConstants.DEFAULT_EDITOR_TAB_SIZE);
                bd.addChangeListener(this);

                // Setup bidirectional mirroring between PlainDocument and RSyntaxDocument
                installMirroring(newDocument);

                // Undo tracking on the RSyntaxDocument (what the user edits)
                editor.getDocument().addUndoableEditListener(diffPanel.getUndoHandler());

                // Ensure highlighter is still properly connected after setText
                if (editor.getHighlighter() instanceof CompositeHighlighter) {
                    // Force reinstall to ensure proper binding
                    var highlighter = editor.getHighlighter();
                    highlighter.install(editor);
                }
                editor.setEditable(!bd.isReadonly());
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
            JOptionPane.showMessageDialog(diffPanel, "Could not read file or set document: "
                                                  + (bd != null ? bd.getName() : "Unknown")
                                                  + "\n" + ex.getMessage(),
                                          "Error processing file", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void reDisplay() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reDisplay);
            return;
        }

        // Skip reDisplay entirely during active typing to prevent flickering
        if (isActivelyTyping.get()) {
            logger.trace("Skipping reDisplay during active typing");
            hasDeferredUpdates.set(true); // Mark that we have updates to apply later
            return;
        }

        // Use the unified timer for debounced updates
        if (timer != null) {
            timer.restart();
        }
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
    /**
     * PERFORMANCE OPTIMIZATION: Only highlights deltas visible in the current viewport
     * for massive performance improvement with large files.
     */
    private void paintRevisionHighlights()
    {
        assert SwingUtilities.isEventDispatchThread() : "NOT ON EDT";
        if (bufferDocument == null) return;

        // Access the shared patch from the parent BufferDiffPanel
        var patch = diffPanel.getPatch();
        if (patch == null) return;

        // Skip viewport optimization when navigating to ensure highlights appear
        if (isNavigatingToDiff.get()) {
            logger.debug("Navigation mode: highlighting all deltas to ensure target is visible");
            paintAllDeltas(patch);
            return;
        }

        // Get visible line range with caching for performance
        var visibleRange = getVisibleLineRange();
        if (visibleRange == null) {
            // Fallback to highlighting all deltas if viewport calculation fails
            paintAllDeltas(patch);
            return;
        }

        int startLine = visibleRange.start;
        int endLine = visibleRange.end;

        // Filter deltas to only those intersecting visible area
        int totalDeltas = patch.getDeltas().size();
        int visibleCount = 0;

        for (var delta : patch.getDeltas()) {
            if (deltaIntersectsViewport(delta, startLine, endLine)) {
                visibleCount++;
                // Are we the "original" side or the "revised" side?
                if (BufferDocumentIF.ORIGINAL.equals(name)) {
                    new HighlightOriginal(delta).highlight();
                } else if (BufferDocumentIF.REVISED.equals(name)) {
                    new HighlightRevised(delta).highlight();
                }
            }
        }

        logger.trace("Painted {} of {} deltas for viewport lines {}-{}",
                     visibleCount, totalDeltas, startLine, endLine);
    }

    /**
     * Fallback method to paint all deltas (original behavior).
     */
    private void paintAllDeltas(com.github.difflib.patch.Patch<String> patch) {
        for (var delta : patch.getDeltas()) {
            if (BufferDocumentIF.ORIGINAL.equals(name)) {
                new HighlightOriginal(delta).highlight();
            } else if (BufferDocumentIF.REVISED.equals(name)) {
                new HighlightRevised(delta).highlight();
            }
        }
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
            logger.debug("Error calculating visible range, falling back to full highlighting: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if a delta intersects with the visible line range.
     */
    private boolean deltaIntersectsViewport(AbstractDelta<String> delta, int startLine, int endLine) {
        Chunk<String> chunk = BufferDocumentIF.ORIGINAL.equals(name) ? delta.getSource() : delta.getTarget();
        if (chunk == null) return false;

        int deltaStart = chunk.getPosition();
        int deltaEnd = deltaStart + Math.max(1, chunk.size()) - 1;

        // Check if delta range overlaps with visible range
        return !(deltaEnd < startLine || deltaStart > endLine);
    }

    /**
     * Clear viewport cache when scrolling to ensure fresh calculations.
     */
    public void invalidateViewportCache() {
        viewportCache.set(null);
    }

    /**
     * Check if user is actively typing to prevent scroll sync interference.
     */
    public boolean isActivelyTyping() {
        return isActivelyTyping.get();
    }

    /**
     * Mark that we're navigating to a diff to ensure highlights appear.
     */
    public void setNavigatingToDiff(boolean navigating) {
        isNavigatingToDiff.set(navigating);
        if (navigating) {
            // Invalidate viewport cache to force recalculation with new position
            invalidateViewportCache();
        }
    }

    /**
     * PERFORMANCE OPTIMIZATION: Apply performance optimizations based on file size.
     */
    private void applyPerformanceOptimizations(long contentLength) {
        boolean isLargeFile = contentLength > PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES;

        if (isLargeFile) {
            logger.info("Applying performance optimizations for large file: {}KB", contentLength / 1024);

            // Use longer debounce times for large files to reduce update frequency
            if (timer != null) {
                timer.setDelay(PerformanceConstants.LARGE_FILE_UPDATE_TIMER_DELAY_MS);
            }
            logger.debug("Increased timer delay for large file");
        } else {
            // Use normal timing for smaller files
            if (timer != null) {
                timer.setDelay(PerformanceConstants.DEFAULT_UPDATE_TIMER_DELAY_MS);
            }
        }
    }

    /**
     * Simple data record for visible line range.
     */
    private record VisibleRange(int start, int end) {}

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        // Apply current theme
        GuiTheme.loadRSyntaxTheme(guiTheme.isDarkTheme()).ifPresent(theme -> {
            // Apply theme to the composite highlighter (which will forward to JMHighlighter)
            if (editor.getHighlighter() instanceof ThemeAware high) {
                high.applyTheme(guiTheme);
            }
            theme.apply(editor);
            reDisplay();
        });
    }

    abstract class AbstractHighlight {
        protected final AbstractDelta<String> delta;

        AbstractHighlight(AbstractDelta<String> delta) {
            this.delta = delta;
        }

        public void highlight() {
            if (bufferDocument == null) return;
            // Retrieve the chunk relevant to this side
            var chunk = getChunk(delta);
            var fromOffset = bufferDocument.getOffsetForLine(chunk.getPosition());
            if (fromOffset < 0) return;
            var toOffset = bufferDocument.getOffsetForLine(chunk.getPosition() + chunk.size());
            if (toOffset < 0) return;

            // Check if chunk is effectively "empty line" in the old code
            boolean isEmpty = (chunk.size() == 0);

            // End offset might be the doc length; check trailing newline logic:
            boolean isEndAndNewline = isEndAndLastNewline(toOffset);

            // Decide color. For Insert vs Delete vs Change we do:
            var isDark = diffPanel.isDarkTheme();
            var type = delta.getType(); // DeltaType.INSERT, DELETE, CHANGE
            var painter = switch (type) {
                case INSERT ->
                        isEmpty
                                ? new JMHighlightPainter.JMHighlightLinePainter(Colors.getAdded(isDark))
                                : isEndAndNewline
                                ? new JMHighlightPainter.JMHighlightNewLinePainter(Colors.getAdded(isDark))
                                : new JMHighlightPainter(Colors.getAdded(isDark));

                case DELETE ->
                        isEmpty
                                ? new JMHighlightPainter.JMHighlightLinePainter(Colors.getDeleted(isDark))
                                : isEndAndNewline
                                ? new JMHighlightPainter.JMHighlightNewLinePainter(Colors.getDeleted(isDark))
                                : new JMHighlightPainter(Colors.getDeleted(isDark));

                case CHANGE ->
                        isEndAndNewline
                                ? new JMHighlightPainter.JMHighlightNewLinePainter(Colors.getChanged(isDark))
                                : new JMHighlightPainter(Colors.getChanged(isDark));
                case EQUAL -> throw new IllegalStateException();
            };
            setHighlight(fromOffset, toOffset, painter);
        }

        // Check if the last char is a newline *and* if offset is doc length
        private boolean isEndAndLastNewline(int toOffset) {
            if (bufferDocument == null) {
                return false;
            }
            try {
                var docLen = bufferDocument.getDocument().getLength();
                int endOffset = toOffset - 1;
                if (endOffset < 0 || endOffset >= docLen) {
                    return false;
                }
                // If the final character is a newline & chunk touches doc-end
                boolean lastCharIsNL = "\n".equals(bufferDocument.getDocument().getText(endOffset, 1));
                return (endOffset == docLen - 1) && lastCharIsNL;
            } catch (BadLocationException e) {
                // This exception indicates an issue with offsets, likely a bug
                throw new RuntimeException("Bad location accessing document text", e);
            }
        }

        protected abstract Chunk<String> getChunk(AbstractDelta<String> d);
    }

    class HighlightOriginal extends AbstractHighlight {
        HighlightOriginal(AbstractDelta<String> delta) {
            super(delta);
        }

        @Override
        protected Chunk<String> getChunk(AbstractDelta<String> d) {
            return d.getSource(); // For the original side
        }
    }

    class HighlightRevised extends AbstractHighlight {
        HighlightRevised(AbstractDelta<String> delta) {
            super(delta);
        }

        @Override
        protected Chunk<String> getChunk(AbstractDelta<String> d) {
            return d.getTarget(); // For the revised side
        }
    }


    /** Package-clients (e.g. BufferDiffPanel) need to query the composite highlighter. */
    public JMHighlighter getHighlighter() {
        return jmHighlighter;
    }

    private void removeHighlights() {
        JMHighlighter jmhl = getHighlighter();
        jmhl.removeHighlights(JMHighlighter.LAYER0);
        jmhl.removeHighlights(JMHighlighter.LAYER1);
        jmhl.removeHighlights(JMHighlighter.LAYER2);
    }

    private void setHighlight(int offset, int size,
                              Highlighter.HighlightPainter highlight) {
        setHighlight(JMHighlighter.LAYER0, offset, size, highlight);
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
            return;
        }

        // Ensure we're on EDT for UI operations
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> handleUnifiedUpdate(e));
            return;
        }

        long startTime = System.currentTimeMillis();

        try {
            // First, update the diff (this may change the patch)
            diffPanel.diff();

            // Then update highlights based on new diff state
            // The viewport optimization will ensure only visible deltas are processed
            reDisplayInternal();

            long duration = System.currentTimeMillis() - startTime;
            if (duration > PerformanceConstants.SLOW_UPDATE_THRESHOLD_MS) {
                logger.debug("Unified update took {}ms for document: {}",
                           duration, bufferDocument != null ? bufferDocument.getName() : "unknown");
            }
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
            isActivelyTyping.set(true);
            if (typingStateTimer != null) {
                typingStateTimer.restart();
            }
        }

        // Suppress ALL updates during active typing to prevent flickering
        if (isActivelyTyping.get()) {
            logger.trace("Suppressing document change updates during active typing");
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
        editor.setEditable(true);
    }

    /**
     * Chooses a syntax style for the current document based on its filename.
     * Falls back to plain-text when the extension is not recognised.
     */
    private void updateSyntaxStyle() {
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
            logger.info("File type detection heuristic 1 type: {}, filename: {}, style {}", name, Objects.toString(fileName, "<null_filename>"), style);
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
                var docName = Optional.ofNullable(otherPanel.getBufferDocument()).map(BufferDocumentIF::getName).orElse("'<No other document>'");
                logger.info("File type detection heuristic 2 type: {}, filename: {}, style {}", name, docName, style);
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
            var firstDelta = patch.getDeltas().get(0);
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
     * Synchronizes a specific document change incrementally to preserve cursor position
     * and avoid replacing the entire document content.
     */
    private static void syncDocumentChange(DocumentEvent e, Document sourceDoc, Document destinationDoc) {
        try {
            int offset = e.getOffset();
            int length = e.getLength();

            var eventType = e.getType();
            if (eventType == DocumentEvent.EventType.INSERT) {
                // Get the inserted text from the source document
                String insertedText = sourceDoc.getText(offset, length);
                // Validate offset is within bounds for destination document
                if (offset <= destinationDoc.getLength()) {
                    // Check if text is already there to avoid duplicates
                    if (offset + length <= destinationDoc.getLength()) {
                        String existingText = destinationDoc.getText(offset, length);
                        if (existingText.equals(insertedText)) {
                            return; // Text already exists, skip insertion
                        }
                    }
                    destinationDoc.insertString(offset, insertedText, null);
                } else {
                    // Offset is beyond destination document, append at end
                    destinationDoc.insertString(destinationDoc.getLength(), insertedText, null);
                }
            } else if (eventType == DocumentEvent.EventType.REMOVE) {
                // Remove the same range from the destination document
                if (offset < destinationDoc.getLength() && offset + length <= destinationDoc.getLength()) {
                    destinationDoc.remove(offset, length);
                }
            } else if (eventType == DocumentEvent.EventType.CHANGE) {
                // For change events, replace the content at the same position
                String changedText = sourceDoc.getText(offset, length);
                if (offset < destinationDoc.getLength()) {
                    int removeLength = Math.min(length, destinationDoc.getLength() - offset);
                    destinationDoc.remove(offset, removeLength);
                    destinationDoc.insertString(offset, changedText, null);
                }
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

    SearchCommand getSearchCommand() {
        // Default to case insensitive - GenericSearchBar handles case sensitivity through its own toggle
        return new SearchCommand("", false);
    }

    public SearchHits doSearch() {
        if (!SwingUtilities.isEventDispatchThread()) {
            logger.warn("doSearch called off EDT, redirecting to EDT");
            try {
                var result = SwingUtil.runOnEdt(() -> doSearch(), new SearchHits());
                return result != null ? result : new SearchHits();
            } catch (Exception e) {
                logger.error("Failed to run doSearch on EDT", e);
                return new SearchHits();
            }
        }

        int numberOfLines;
        BufferDocumentIF doc;
        String text;
        int index, fromIndex;
        boolean caseSensitive;
        String searchText, searchTextToCompare, textToSearch;
        SearchHit searchHit;
        int offset;
        SearchCommand searchCommand;

        searchCommand = getSearchCommand();
        // If searchText is empty, return empty hits. getSearchCommand() always returns non-null.
        if (searchCommand.searchText().isEmpty()) {
            this.searchHits = new SearchHits(); // Ensure searchHits is non-null for getSearchHits()
            reDisplay(); // Clear previous highlights
            return this.searchHits;
        }

        searchText = searchCommand.searchText();
        caseSensitive = searchCommand.isCaseSensitive(); // Get case-sensitive flag

        doc = getBufferDocument();
        if (doc == null) { // Should not happen if isDisplayingEditor is true and doc set
            this.searchHits = new SearchHits();
            return this.searchHits;
        }
        numberOfLines = doc.getNumberOfLines();

        this.searchHits = new SearchHits();
        for (int line = 0; line < numberOfLines; line++) {
            text = doc.getLineText(line);
            var nonNullText = Objects.requireNonNullElse(text, "");

            // Adjust case based on case-sensitive flag
            if (!caseSensitive) {
                textToSearch = nonNullText.toLowerCase(Locale.ROOT);
                searchTextToCompare = searchText.toLowerCase(Locale.ROOT);
            } else {
                textToSearch = nonNullText;
                searchTextToCompare = searchText;
            }

            fromIndex = 0;
            while ((index = textToSearch.indexOf(searchTextToCompare, fromIndex)) != -1) {
                // Use the local 'doc' variable which is already null-checked for this search operation.
                offset = doc.getOffsetForLine(line);
                if (offset < 0) {
                    // Can this actually happen?
                    fromIndex = index + searchTextToCompare.length(); // Advance past this match to avoid infinite loop on bad offset
                    continue;
                }

                searchHit = new SearchHit(line, offset + index, searchText.length());
                this.searchHits.add(searchHit);

                fromIndex = index + searchHit.getSize();
            }
        }

        reDisplay(); // This will also check isDisplayingEditor
        scrollToSearch(this, this.searchHits);
        return getSearchHits();
    }


    SearchHits getSearchHits() {
        return requireNonNullElseGet(searchHits, SearchHits::new);
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


    public void doPreviousSearch() {
        SearchHits sh = getSearchHits();
        sh.previous();
        reDisplay();
        scrollToSearch(this, sh);
    }

    private void scrollToSearch(FilePanel fp, SearchHits searchHitsToScroll) {
        SearchHit currentHit = searchHitsToScroll.getCurrent();
        if (currentHit != null) {
            int line = currentHit.getLine();
            var scrollSync = diffPanel.getScrollSynchronizer();
            if (scrollSync != null) {
                scrollSync.scrollToLine(fp, line);
            }
            diffPanel.setSelectedLine(line);
        }
    }

    public void doNextSearch() {
        SearchHits sh = getSearchHits();
        sh.next();
        reDisplay();
        scrollToSearch(this, sh);
    }
}
