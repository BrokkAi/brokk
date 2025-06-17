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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import java.awt.*;
import io.github.jbellis.brokk.gui.SwingUtil;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class FilePanel implements BufferDocumentChangeListenerIF, ThemeAware {
    private static final Logger logger = LogManager.getLogger(FilePanel.class);

    @NotNull
    private final BufferDiffPanel diffPanel;
    private final String name;
    private JPanel visualComponentContainer; // Main container for editor or "new file" label
    private JScrollPane scrollPane;
    private RSyntaxTextArea editor;
    private JMHighlighter jmHighlighter;
    @Nullable
    private BufferDocumentIF bufferDocument;

    /* ------------- mirroring PlainDocument <-> RSyntaxDocument ------------- */
    private Document plainDocument;
    private DocumentListener plainToEditorListener;
    private DocumentListener editorToPlainListener;
    private Timer timer;
    private SearchHits searchHits;
    private volatile boolean initialSetupComplete = false;

    public FilePanel(@NotNull BufferDiffPanel diffPanel, String name) {
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

        // Setup a one-time timer to refresh the UI after 100ms
        timer = new Timer(100, refresh());
        timer.setRepeats(false);
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
                if (previousDocument != null) {
                    previousDocument.removeUndoableEditListener(diffPanel.getUndoHandler());
                }
            }

            this.bufferDocument = bd;
            visualComponentContainer.removeAll(); // Clear previous content

            // Always add the scrollPane (which contains the editor)
            visualComponentContainer.add(scrollPane, BorderLayout.CENTER);

            if (bd != null) {
                newDocument = bd.getDocument();
                if (newDocument != null) {
                    // Copy text into RSyntaxDocument instead of replacing the model
                    String txt = newDocument.getText(0, newDocument.getLength());
                    editor.setText(txt);
                    editor.setTabSize(4); // TODO: Make configurable
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
                }
                editor.setEditable(!bd.isReadonly());
                updateSyntaxStyle();            // pick syntax based on filename
            } else {
                // If BufferDocumentIF is null, clear the editor and make it non-editable
                removeMirroring();
                editor.setText("");
                editor.setEditable(false);
            }

            visualComponentContainer.revalidate();
            visualComponentContainer.repaint();

            // Initialize configuration - this sets border etc.
            initConfiguration();

            // Mark initial setup as complete
            initialSetupComplete = true;

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(diffPanel, "Could not read file or set document: "
                                                  + (bd != null ? bd.getName() : "Unknown")
                                                  + "\n" + ex.getMessage(),
                                          "Error processing file", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void reDisplay() {
        removeHighlights();
        paintSearchHighlights();
        paintRevisionHighlights();
        // Force both the JMHighlighter and editor to repaint
        if (jmHighlighter != null) {
            jmHighlighter.repaint();
        }
        if (editor != null) {
            editor.repaint();
        }
    }

    /**
     * Repaint highlights: we get the patch from BufferDiffPanel, then highlight
     * each delta's relevant lines in *this* panel (ORIGINAL or REVISED).
     */
    private void paintRevisionHighlights()
    {
        assert SwingUtilities.isEventDispatchThread() : "NOT ON EDT";
        var doc = bufferDocument;
        if (doc == null) return;

        // Access the shared patch from the parent BufferDiffPanel
        var patch = diffPanel.getPatch();
        if (patch == null) return;

        for (var delta : patch.getDeltas()) {
            // Are we the "original" side or the "revised" side?
            if (BufferDocumentIF.ORIGINAL.equals(name)) {
                new HighlightOriginal(delta).highlight();
            } else if (BufferDocumentIF.REVISED.equals(name)) {
                new HighlightRevised(delta).highlight();
            }
        }
    }

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

    @Override
    public void documentChanged(JMDocumentEvent de) {
        // Don't trigger timer during initial setup
        if (!initialSetupComplete) return;

        if (de.getStartLine() == -1 && de.getDocumentEvent() == null) {
            // Refresh the diff of whole document.
            timer.restart();
        } else {
            // Try to update the revision instead of doing a full diff.
            if (!diffPanel.revisionChanged(de)) {
                timer.restart();
            }
        }
    }


    private ActionListener refresh() {
        return ae -> diffPanel.diff();
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
            logger.info("File type detection heuristic 1 type: {}, filename: {}, style {}", name, fileName, style);
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

        // After setting syntax style, scroll to show the first diff if available
        SwingUtilities.invokeLater(this::scrollToFirstDiff);
    }

    /**
     * Scrolls the editor to show the first diff highlight if available.
     */
    private void scrollToFirstDiff() {
        var patch = diffPanel.getPatch();
        if (patch != null && !patch.getDeltas().isEmpty()) {
            var firstDelta = patch.getDeltas().get(0);
            int lineToShow = BufferDocumentIF.ORIGINAL.equals(name)
                ? firstDelta.getSource().getPosition()
                : firstDelta.getTarget().getPosition();

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
            private void performSync() {
                if (!guard.compareAndSet(false, true)) { // Attempt to acquire lock
                    return; // Lock not acquired, another sync operation is in progress
                }
                try {
                    copyText(sourceDoc, destinationDoc);
                } finally {
                    guard.set(false); // Release lock
                }
            }

            private void sync() {
                if (runDestinationUpdateOnEdt) {
                    // Updates to the destination document (e.g., editor's document) must occur on the EDT.
                    SwingUtilities.invokeLater(this::performSync);
                } else {
                    // Source document changes (e.g., editor) are already on EDT,
                    // or destination document (e.g., plain model) can be updated directly.
                    performSync();
                }
            }

            @Override public void insertUpdate(DocumentEvent e) { sync(); }
            @Override public void removeUpdate(DocumentEvent e)  { sync(); }
            @Override public void changedUpdate(DocumentEvent e){ sync(); }
        };
    }

    /**
     * Removes previously-installed mirroring listeners, if any.
     */
    private void removeMirroring() {
        if (plainDocument != null && plainToEditorListener != null) {
            plainDocument.removeDocumentListener(plainToEditorListener);
        }
        if (editor != null && editorToPlainListener != null) {
            editor.getDocument().removeDocumentListener(editorToPlainListener);
        }
        plainDocument = null;
        plainToEditorListener = null;
        editorToPlainListener = null;
    }

    /**
     * Replaces the full content of the destination document with the text from
     * the source document.
     * Brute force copying of the full document could be a performance issue for large
     * documents
     */
    private static void copyText(Document src, Document dst) {
        try {
            String txt = src.getText(0, src.getLength());
            dst.remove(0, dst.getLength());
            dst.insertString(0, txt, null);
        } catch (BadLocationException e) {
            throw new RuntimeException("Mirroring documents failed", e);
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
        if (searchCommand == null) {
            return null;
        }

        searchText = searchCommand.searchText();
        caseSensitive = searchCommand.isCaseSensitive(); // Get case-sensitive flag

        doc = getBufferDocument();
        if (doc == null) { // Should not happen if isDisplayingEditor is true and doc set
            searchHits = new SearchHits();
            return searchHits;
        }
        numberOfLines = doc.getNumberOfLines();

        searchHits = new SearchHits();

        if (!searchText.isEmpty()) {
            for (int line = 0; line < numberOfLines; line++) {
                text = doc.getLineText(line);

                // Adjust case based on case-sensitive flag
                if (!caseSensitive) {
                    textToSearch = text.toLowerCase(Locale.ROOT);
                    searchTextToCompare = searchText.toLowerCase(Locale.ROOT);
                } else {
                    textToSearch = text;
                    searchTextToCompare = searchText;
                }

                fromIndex = 0;
                while ((index = textToSearch.indexOf(searchTextToCompare, fromIndex)) != -1) {
                    offset = bufferDocument.getOffsetForLine(line);
                    if (offset < 0) {
                        continue;
                    }

                    searchHit = new SearchHit(line, offset + index, searchText.length());
                    searchHits.add(searchHit);

                    fromIndex = index + searchHit.getSize();
                }
            }
        }

        reDisplay(); // This will also check isDisplayingEditor
        scrollToSearch(this, searchHits);
        return getSearchHits();
    }


    SearchHits getSearchHits() {
        return searchHits;
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
        if (sh == null) {
            return;
        }
        sh.previous();
        reDisplay();
        scrollToSearch(this, sh);
    }

    private void scrollToSearch(FilePanel fp, SearchHits searchHitsToScroll) {
        if (searchHitsToScroll == null) {
            return;
        }

        SearchHit currentHit = searchHitsToScroll.getCurrent();
        if (currentHit != null) {
            int line = currentHit.getLine();
            diffPanel.getScrollSynchronizer().scrollToLine(fp, line);
            diffPanel.setSelectedLine(line);
        }
    }

    public void doNextSearch() {
        SearchHits sh = getSearchHits();
        if (sh == null) {
            return;
        }
        sh.next();
        reDisplay();
        scrollToSearch(this, sh);
    }
}
