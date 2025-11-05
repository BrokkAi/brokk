package ai.brokk.difftool.ui;

import ai.brokk.MainProject;
import ai.brokk.difftool.doc.BufferDocumentChangeListenerIF;
import ai.brokk.difftool.doc.BufferDocumentIF;
import ai.brokk.difftool.doc.JMDocumentEvent;
import ai.brokk.difftool.performance.PerformanceConstants;
import ai.brokk.difftool.search.SearchHit;
import ai.brokk.difftool.search.SearchHits;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.search.RTextAreaSearchableComponent;
import ai.brokk.gui.search.SearchableComponent;
import ai.brokk.gui.theme.FontSizeAware;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

public class FilePanel implements BufferDocumentChangeListenerIF, ThemeAware {
    private static final Logger logger = LogManager.getLogger(FilePanel.class);

    private final BufferDiffPanel diffPanel;
    private final String name;
    private JPanel visualComponentContainer; // Main container for editor or "new file" label
    private JScrollPane scrollPane;
    private FileEditorArea editor;
    private JMHighlighter jmHighlighter;
    private DiffGutterComponent gutterComponent;

    @Nullable
    private BufferDocumentIF bufferDocument;

    // Compatibility shim for legacy focus traversal methods.
    // Modern focus traversal should use a FocusTraversalPolicy; this field preserves behavior for callers
    // that still set/query the "next focusable component" via the old API.
    private @Nullable Component nextFocusableComponent;

    // Mouse motion tracking for Codestral requests
    private Timer mouseStopTimer;
    private final AtomicReference<Point> lastMousePosition = new AtomicReference<>();

    /** Custom RSyntaxTextArea that preserves font sizes during theme changes */
    private class FileEditorArea extends RSyntaxTextArea implements FontSizeAware, ThemeAware {
        @Override
        public boolean hasExplicitFontSize() {
            return diffPanel.getMainPanel().hasExplicitFontSize();
        }

        @Override
        public float getExplicitFontSize() {
            return diffPanel.getMainPanel().getExplicitFontSize();
        }

        @Override
        public void setExplicitFontSize(float size) {
            diffPanel.getMainPanel().setExplicitFontSize(size);
        }

        @Override
        public void applyTheme(GuiTheme guiTheme) {
            if (hasExplicitFontSize()) {
                guiTheme.applyThemePreservingFont(this);
            } else {
                guiTheme.applyCurrentThemeToComponent(this);
            }
        }

        /**
         * Compatibility no-op: some callers in the codebase attempt to set an explicit tooltip location on the editor.
         * RSyntaxTextArea (or the version used) may not expose setToolTipLocation(Point). Provide a no-op here so
         * such calls compile and have reasonable behaviour (tooltip text is still shown by Swing).
         */
        public void setToolTipLocation(Point p) {
            // Intentionally no-op for compatibility; Swing tooltip location is typically driven by mouse events.
        }
    }

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
    private Timer typingStateTimer;

    public FilePanel(BufferDiffPanel diffPanel, String name) {
        this.diffPanel = diffPanel;
        this.name = name;
    }

    private void initializeEditor() {
        editor = new FileEditorArea();
        editor.setEditable(false);
        editor.setAntiAliasingEnabled(true);
        
        // Add mouse motion listener for Codestral requests
        editor.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                handleMouseMoved(e);
            }
            
            @Override
            public void mouseDragged(MouseEvent e) {
                handleMouseMoved(e);
            }
        });

        jmHighlighter = new JMHighlighter();

        // Create CompositeHighlighter with JMHighlighter.
        // The JMHighlighter is responsible for highlighting the diffs.
        // RSyntaxTextAreaHighlighter (superclass of CompositeHighlighter) will handle syntax highlighting;
        // supply the JMHighlighter as the secondary highlighter.
        editor.setHighlighter(new CompositeHighlighter(jmHighlighter));

        // Set up the editor
        editor.setEditable(false);
        editor.setLineWrap(false);
        editor.setWrapStyleWord(false);
        editor.setCodeFoldingEnabled(true);
        editor.setAntiAliasingEnabled(true);
        editor.setAnimateBracketMatching(true);
        editor.setAutoIndentEnabled(true);
        editor.setBracketMatchingEnabled(true);
        editor.setCloseCurlyBraces(true);
        editor.setCloseMarkupTags(true);
        editor.setEOLMarkersVisible(false);
        editor.setHyperlinksEnabled(true);
        editor.setMarkOccurrences(true);
        editor.setPaintTabLines(true);
        // editor.setShowWhitespaceLines(false); // not available in current RSyntaxTextArea API - removed
        editor.setWhitespaceVisible(false);
        editor.setHighlightSecondaryLanguages(true);

        // Set the font
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));

        // Set up the scroll pane
        scrollPane = new JScrollPane(editor);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        // Create the visual component container
        visualComponentContainer = new JPanel(new BorderLayout());
        visualComponentContainer.add(scrollPane, BorderLayout.CENTER);

        // Set up the gutter (use explicit display mode rather than passing the scroll pane)
        gutterComponent = new DiffGutterComponent(editor, DiffGutterComponent.DisplayMode.SIDE_BY_SIDE_SINGLE);
        // Attach gutter to the scroll pane as the row header
        scrollPane.setRowHeaderView(gutterComponent);

        // Set up the document listener
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                handleDocumentChange();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                handleDocumentChange();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                handleDocumentChange();
            }
        });

        // Set up the focus listener
        editor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                diffPanel.setSelectedPanel(FilePanel.this);
            }
        });

        // Set up the typing state timer
        typingStateTimer = new Timer(1000, e -> isActivelyTyping.set(false));
        typingStateTimer.setRepeats(false);

        // Set up the document listener for typing detection
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                handleTyping();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                handleTyping();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                handleTyping();
            }
        });

        initialSetupComplete = true;
    }

    private void handleMouseMoved(MouseEvent e) {
        // Cancel any pending timer
        if (mouseStopTimer != null && mouseStopTimer.isRunning()) {
            mouseStopTimer.stop();
        } else {
            mouseStopTimer = new Timer(500, evt -> {
                Point currentPos = lastMousePosition.get();
                if (currentPos != null && currentPos.distance(e.getPoint()) < 5) {
                    sendCodestralRequest(e);
                }
            });
            mouseStopTimer.setRepeats(false);
        }
        
        // Store current mouse position
        lastMousePosition.set(e.getPoint());
        
        // Restart the timer
        mouseStopTimer.start();
    }
    
    private void sendCodestralRequest(MouseEvent e) {
        if (editor == null || !editor.isShowing()) {
            return;
        }
        
        try {
            int pos = editor.viewToModel2D(new Point2D.Double(e.getX(), e.getY()));
            if (pos < 0) return;
            
            String text = editor.getText();
            if (text == null || text.isEmpty()) return;
            
            int line = 0;
            int column = 0;
            try {
                line = editor.getLineOfOffset(pos);
                column = pos - editor.getLineStartOffset(line);
            } catch (Exception ex) {
                logger.warn("Error calculating line and column", ex);
                return;
            }
            
            String word = getWordAtPosition(pos);
            
            // TODO: Implement Codestral API call here
            // codestralService.requestCompletion(text, line, column, word);
            
            logger.debug("Sending request to Codestral at line: {}, column: {}, word: {}", line, column, word);
            
        } catch (Exception ex) {
            logger.error("Error processing Codestral request", ex);
        }
    }
    
    private String getWordAtPosition(int pos) {
        if (editor == null) return "";
        
        try {
            String text = editor.getText();
            if (pos < 0 || pos >= text.length()) return "";
            
            // Find word start
            int start = pos;
            while (start > 0 && isWordChar(text.charAt(start - 1))) {
                start--;
            }
            
            // Find word end
            int end = pos;
            while (end < text.length() && isWordChar(text.charAt(end))) {
                end++;
            }
            
            return text.substring(start, end);
        } catch (Exception e) {
            logger.warn("Error getting word at position", e);
            return "";
        }
    }
    
    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void handleTyping() {
        isActivelyTyping.set(true);
        if (typingStateTimer != null) {
            typingStateTimer.restart();
        }
    }

    private void handleDocumentChange() {
        // Update the document mirror if needed
        if (plainDocument != null && editor != null) {
            try {
                // Remove the listener to prevent infinite recursion
                Document doc = editor.getDocument();
                doc.removeDocumentListener(editorToPlainListener);

                // Update the plain document
                plainDocument.remove(0, plainDocument.getLength());
                plainDocument.insertString(0, editor.getText(), null);
            } catch (BadLocationException e) {
                logger.error("Error updating document mirror", e);
            } finally {
                // Re-add the listener
                if (editor != null) {
                    editor.getDocument().addDocumentListener(editorToPlainListener);
                }
            }
        }

        // Notify the diff panel that the document has changed (manual edit)
        if (bufferDocument != null) {
            diffPanel.recordManualEdit(bufferDocument);
        }
    }

    public JComponent getVisualComponent() {
        if (visualComponentContainer == null) {
            initializeEditor();
        }
        return visualComponentContainer;
    }

    public void setBufferDocument(@Nullable BufferDocumentIF bufferDocument) {
        // Remove the listener from the old document
        if (this.bufferDocument != null) {
            this.bufferDocument.removeChangeListener(this);
        }

        this.bufferDocument = bufferDocument;

        // Add the listener to the new document
        if (bufferDocument != null) {
            bufferDocument.addChangeListener(this);
            updateFromBufferDocument();
        } else {
            // Clear the editor
            if (editor != null) {
                editor.setText("");
            }
        }
    }

    @Nullable
    public BufferDocumentIF getBufferDocument() {
        return bufferDocument;
    }

    public String getName() {
        return name;
    }

    public RSyntaxTextArea getEditor() {
        return editor;
    }

    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    public void scrollToLine(int line) {
        if (editor != null && line >= 0) {
            try {
                int pos = editor.getLineStartOffset(line);
                editor.setCaretPosition(pos);
                var r2 = editor.modelToView2D(pos);
                if (r2 != null) {
                    editor.scrollRectToVisible(r2.getBounds());
                }
            } catch (BadLocationException e) {
                logger.warn("Error scrolling to line: " + line, e);
            }
        }
    }

    /**
     * Returns the owning diff panel.
     */
    public BufferDiffPanel getDiffPanel() {
        return diffPanel;
    }

    /**
     * Returns true when the underlying buffer document reports unsaved changes.
     */
    public boolean isDocumentChanged() {
        if (bufferDocument == null) {
            return false;
        }
        // Prefer direct query if concrete AbstractBufferDocument is available
        if (bufferDocument instanceof ai.brokk.difftool.doc.AbstractBufferDocument abd) {
            return abd.isChanged();
        }
        // Fallback: call the interface method if available
        try {
            return bufferDocument.isChanged();
        } catch (Throwable t) {
            return false;
        }
    }

    public DiffGutterComponent getGutterComponent() {
        return gutterComponent;
    }

    /**
     * Re-apply lightweight visual updates (repaint/revalidate).
     */
    public void reDisplay() {
        if (editor != null) {
            editor.revalidate();
            editor.repaint();
        }
        if (gutterComponent != null) {
            gutterComponent.revalidate();
            gutterComponent.repaint();
        }
    }

    /**
     * Adapter for search UI to operate on this panel's editor.
     */
    public SearchableComponent createSearchableComponent() {
        return new RTextAreaSearchableComponent(editor != null ? editor : new RSyntaxTextArea());
    }

    /**
     * Clear any viewport-related caches. Kept no-op for now (placeholder for future optimisations).
     */
    public void clearViewportCache() {
        // Intentionally lightweight/no-op; specific cache owners (scroll synchronizer/gutter) can override behavior.
    }

    /**
     * Clear any cached search state kept by this panel.
     */
    public void clearSearchCache() {
        searchHits = null;
        if (editor != null) {
            editor.repaint();
        }
    }

    /**
     * Store search hits and request a repaint. JMHighlighter currently does not expose a public setSearchHits API,
     * so we avoid calling a method that may not exist and instead trigger repaint for any highlighters reading
     * this panel's state.
     */
    public void setSearchHits(@Nullable SearchHits searchHits) {
        this.searchHits = searchHits;
        if (editor != null) {
            editor.repaint();
        }
    }

    @Nullable
    public SearchHits getSearchHits() {
        return searchHits;
    }

    public void clearSearchHits() {
        setSearchHits(null);
    }

    public void selectSearchHit(SearchHit hit) {
        if (editor != null && hit != null) {
            try {
                int start = hit.getFromOffset();
                int end = hit.getToOffset();
                // Ensure offsets are in valid range before selecting
                int docLength = editor.getDocument().getLength();
                if (start < 0) start = 0;
                if (end < 0) end = 0;
                if (start > docLength) start = docLength;
                if (end > docLength) end = docLength;

                editor.setSelectionStart(start);
                editor.setSelectionEnd(end);

                var r2 = editor.modelToView2D(start);
                if (r2 != null) {
                    editor.scrollRectToVisible(r2.getBounds());
                }
            } catch (BadLocationException e) {
                logger.warn("Error selecting search hit", e);
            } catch (Exception e) {
                // Defensive: any unexpected runtime error should be logged but not crash the UI
                logger.warn("Unexpected error selecting search hit", e);
            }
        }
    }

    public boolean isInitialSetupComplete() {
        return initialSetupComplete;
    }

    public void setInitialSetupComplete(boolean initialSetupComplete) {
        this.initialSetupComplete = initialSetupComplete;
    }

    @Override
    public void documentChanged(JMDocumentEvent e) {
        // Only react to events for our bound buffer document
        if (e != null && e.getDocument() == bufferDocument) {
            updateFromBufferDocument();
        }
    }

    private void updateFromBufferDocument() {
        if (bufferDocument != null && editor != null) {
            try {
                // Save the caret position and scroll
                int caretPosition = editor.getCaretPosition();
                int scrollPosition = (scrollPane != null && scrollPane.getVerticalScrollBar() != null)
                        ? scrollPane.getVerticalScrollBar().getValue()
                        : 0;

                // Read text from the underlying Swing Document to avoid relying on a possibly-missing helper method
                var doc = bufferDocument.getDocument();
                String text = "";
                if (doc != null) {
                    try {
                        text = doc.getText(0, doc.getLength());
                    } catch (BadLocationException ble) {
                        logger.warn("Failed to read buffer document text", ble);
                    }
                }

                // Update the editor content
                editor.setText(text);

                // Restore caret and scroll if still valid
                if (caretPosition <= editor.getDocument().getLength()) {
                    editor.setCaretPosition(caretPosition);
                    if (scrollPane != null && scrollPane.getVerticalScrollBar() != null) {
                        scrollPane.getVerticalScrollBar().setValue(scrollPosition);
                    }
                }
            } catch (Exception e) {
                logger.error("Error updating editor from buffer document", e);
            }
        }
    }

    public void setEditable(boolean editable) {
        if (editor != null) {
            editor.setEditable(editable);
        }
    }

    public boolean isEditable() {
        return editor != null && editor.isEditable();
    }

    public void setSyntaxEditingStyle(String style) {
        if (editor != null) {
            editor.setSyntaxEditingStyle(style);
        }
    }

    public String getSyntaxEditingStyle() {
        return editor != null ? editor.getSyntaxEditingStyle() : SyntaxConstants.SYNTAX_STYLE_NONE;
    }

    public void setFont(Font font) {
        if (editor != null) {
            editor.setFont(font);
        }
    }

    public Font getFont() {
        return editor != null ? editor.getFont() : null;
    }

    public void setBackground(Color color) {
        if (editor != null) {
            editor.setBackground(color);
        }
    }

    public Color getBackground() {
        return editor != null ? editor.getBackground() : null;
    }

    public void setForeground(Color color) {
        if (editor != null) {
            editor.setForeground(color);
        }
    }

    public Color getForeground() {
        return editor != null ? editor.getForeground() : null;
    }

    public void setSelectionColor(Color color) {
        if (editor != null) {
            editor.setSelectionColor(color);
        }
    }

    public Color getSelectionColor() {
        return editor != null ? editor.getSelectionColor() : null;
    }

    public void setCurrentLineHighlightColor(Color color) {
        if (editor != null) {
            editor.setCurrentLineHighlightColor(color);
        }
    }

    public Color getCurrentLineHighlightColor() {
        return editor != null ? editor.getCurrentLineHighlightColor() : null;
    }

    public void setCaretColor(Color color) {
        if (editor != null) {
            editor.setCaretColor(color);
        }
    }

    public Color getCaretColor() {
        return editor != null ? editor.getCaretColor() : null;
    }

    public void setSelectionStart(int start) {
        if (editor != null) {
            editor.setSelectionStart(start);
        }
    }

    public void setSelectionEnd(int end) {
        if (editor != null) {
            editor.setSelectionEnd(end);
        }
    }

    public int getSelectionStart() {
        return editor != null ? editor.getSelectionStart() : -1;
    }

    public int getSelectionEnd() {
        return editor != null ? editor.getSelectionEnd() : -1;
    }

    public String getSelectedText() {
        return editor != null ? editor.getSelectedText() : null;
    }

    public void select(int selectionStart, int selectionEnd) {
        if (editor != null) {
            editor.select(selectionStart, selectionEnd);
        }
    }

    public void selectAll() {
        if (editor != null) {
            editor.selectAll();
        }
    }

    public void setText(String text) {
        if (editor != null) {
            editor.setText(text);
        }
    }

    public String getText() {
        return editor != null ? editor.getText() : "";
    }

    public int getCaretPosition() {
        return editor != null ? editor.getCaretPosition() : 0;
    }

    public void setCaretPosition(int position) {
        if (editor != null) {
            editor.setCaretPosition(position);
        }
    }

    public void moveCaretPosition(int position) {
        if (editor != null) {
            editor.moveCaretPosition(position);
        }
    }

    public int getLineCount() {
        return editor != null ? editor.getLineCount() : 0;
    }

    public int getLineOfOffset(int offset) throws BadLocationException {
        return editor != null ? editor.getLineOfOffset(offset) : 0;
    }

    public int getLineStartOffset(int line) throws BadLocationException {
        return editor != null ? editor.getLineStartOffset(line) : 0;
    }

    public int getLineEndOffset(int line) throws BadLocationException {
        return editor != null ? editor.getLineEndOffset(line) : 0;
    }

    public String getLineText(int line) throws BadLocationException {
        if (editor == null) {
            return "";
        }
        int start = editor.getLineStartOffset(line);
        int end = editor.getLineEndOffset(line);
        return editor.getText(start, end - start);
    }

    public void replaceSelection(String content) {
        if (editor != null) {
            editor.replaceSelection(content);
        }
    }

    public void replaceRange(String str, int start, int end) {
        if (editor != null) {
            try {
                editor.getDocument().remove(start, end - start);
                editor.getDocument().insertString(start, str, null);
            } catch (BadLocationException e) {
                logger.error("Error replacing range", e);
            }
        }
    }

    public void setDocument(Document doc) {
        if (editor != null) {
            editor.setDocument(doc);
        }
    }

    public Document getDocument() {
        return editor != null ? editor.getDocument() : null;
    }

    public void setHighlighter(Highlighter h) {
        if (editor != null) {
            editor.setHighlighter(h);
        }
    }

    public Highlighter getHighlighter() {
        return editor != null ? editor.getHighlighter() : null;
    }

    public void setCaretVisible(boolean visible) {
        if (editor != null && editor.getCaret() != null) {
            editor.getCaret().setVisible(visible);
        }
    }

    public boolean getCaretVisible() {
        return editor != null && editor.getCaret() != null && editor.getCaret().isVisible();
    }

    public void setEnabled(boolean enabled) {
        if (editor != null) {
            editor.setEnabled(enabled);
        }
    }

    public boolean isEnabled() {
        return editor != null && editor.isEnabled();
    }

    public void setFocusable(boolean focusable) {
        if (editor != null) {
            editor.setFocusable(focusable);
        }
    }

    public boolean isFocusable() {
        return editor != null && editor.isFocusable();
    }

    public void requestFocus() {
        if (editor != null) {
            editor.requestFocus();
        }
    }

    public boolean hasFocus() {
        return editor != null && editor.hasFocus();
    }

    public void addCaretListener(javax.swing.event.CaretListener listener) {
        if (editor != null) {
            editor.addCaretListener(listener);
        }
    }

    public void removeCaretListener(javax.swing.event.CaretListener listener) {
        if (editor != null) {
            editor.removeCaretListener(listener);
        }
    }

    public void addKeyListener(java.awt.event.KeyListener listener) {
        if (editor != null) {
            editor.addKeyListener(listener);
        }
    }

    public void removeKeyListener(java.awt.event.KeyListener listener) {
        if (editor != null) {
            editor.removeKeyListener(listener);
        }
    }

    public void addMouseListener(java.awt.event.MouseListener listener) {
        if (editor != null) {
            editor.addMouseListener(listener);
        }
    }

    public void removeMouseListener(java.awt.event.MouseListener listener) {
        if (editor != null) {
            editor.removeMouseListener(listener);
        }
    }

    public void addMouseMotionListener(java.awt.event.MouseMotionListener listener) {
        if (editor != null) {
            editor.addMouseMotionListener(listener);
        }
    }

    public void removeMouseMotionListener(java.awt.event.MouseMotionListener listener) {
        if (editor != null) {
            editor.removeMouseMotionListener(listener);
        }
    }

    public void addFocusListener(java.awt.event.FocusListener listener) {
        if (editor != null) {
            editor.addFocusListener(listener);
        }
    }

    public void removeFocusListener(java.awt.event.FocusListener listener) {
        if (editor != null) {
            editor.removeFocusListener(listener);
        }
    }

    public void addComponentListener(java.awt.event.ComponentListener listener) {
        if (editor != null) {
            editor.addComponentListener(listener);
        }
    }

    public void removeComponentListener(java.awt.event.ComponentListener listener) {
        if (editor != null) {
            editor.removeComponentListener(listener);
        }
    }

    public void addHierarchyListener(java.awt.event.HierarchyListener listener) {
        if (editor != null) {
            editor.addHierarchyListener(listener);
        }
    }

    public void removeHierarchyListener(java.awt.event.HierarchyListener listener) {
        if (editor != null) {
            editor.removeHierarchyListener(listener);
        }
    }

    public void addHierarchyBoundsListener(java.awt.event.HierarchyBoundsListener listener) {
        if (editor != null) {
            editor.addHierarchyBoundsListener(listener);
        }
    }

    public void removeHierarchyBoundsListener(java.awt.event.HierarchyBoundsListener listener) {
        if (editor != null) {
            editor.removeHierarchyBoundsListener(listener);
        }
    }

    public void addInputMethodListener(java.awt.event.InputMethodListener listener) {
        if (editor != null) {
            editor.addInputMethodListener(listener);
        }
    }

    public void removeInputMethodListener(java.awt.event.InputMethodListener listener) {
        if (editor != null) {
            editor.removeInputMethodListener(listener);
        }
    }

    public void addPropertyChangeListener(java.beans.PropertyChangeListener listener) {
        if (editor != null) {
            editor.addPropertyChangeListener(listener);
        }
    }

    public void removePropertyChangeListener(java.beans.PropertyChangeListener listener) {
        if (editor != null) {
            editor.removePropertyChangeListener(listener);
        }
    }

    public void addVetoableChangeListener(java.beans.VetoableChangeListener listener) {
        if (editor != null) {
            editor.addVetoableChangeListener(listener);
        }
    }

    public void removeVetoableChangeListener(java.beans.VetoableChangeListener listener) {
        if (editor != null) {
            editor.removeVetoableChangeListener(listener);
        }
    }

    public void addAncestorListener(javax.swing.event.AncestorListener listener) {
        if (editor != null) {
            editor.addAncestorListener(listener);
        }
    }

    public void removeAncestorListener(javax.swing.event.AncestorListener listener) {
        if (editor != null) {
            editor.removeAncestorListener(listener);
        }
    }

    public void addNotify() {
        if (editor != null) {
            editor.addNotify();
        }
    }

    public void removeNotify() {
        if (editor != null) {
            editor.removeNotify();
        }
    }

    public void repaint() {
        if (editor != null) {
            editor.repaint();
        }
    }

    public void repaint(long tm) {
        if (editor != null) {
            editor.repaint(tm);
        }
    }

    public void repaint(int x, int y, int width, int height) {
        if (editor != null) {
            editor.repaint(x, y, width, height);
        }
    }

    public void repaint(Rectangle r) {
        if (editor != null && r != null) {
            editor.repaint(r.x, r.y, r.width, r.height);
        }
    }

    public void revalidate() {
        if (editor != null) {
            editor.revalidate();
        }
    }

    public void validate() {
        if (editor != null) {
            editor.validate();
        }
    }

    public void invalidate() {
        if (editor != null) {
            editor.invalidate();
        }
    }

    public void paint(Graphics g) {
        if (editor != null) {
            editor.paint(g);
        }
    }

    public void update(Graphics g) {
        if (editor != null) {
            editor.update(g);
        }
    }

    public void paintAll(Graphics g) {
        if (editor != null) {
            editor.paintAll(g);
        }
    }

    public void paintComponents(Graphics g) {
        if (editor != null) {
            editor.paintComponents(g);
        }
    }

    public void paintImmediately(int x, int y, int w, int h) {
        if (editor != null) {
            editor.paintImmediately(x, y, w, h);
        }
    }

    public void paintImmediately(Rectangle r) {
        if (editor != null && r != null) {
            editor.paintImmediately(r);
        }
    }

    public void printAll(Graphics g) {
        if (editor != null) {
            editor.printAll(g);
        }
    }

    public void print(Graphics g) {
        if (editor != null) {
            editor.print(g);
        }
    }

    public void printComponents(Graphics g) {
        if (editor != null) {
            editor.printComponents(g);
        }
    }

    public void setVisible(boolean visible) {
        if (editor != null) {
            editor.setVisible(visible);
        }
    }

    public boolean isVisible() {
        return editor != null && editor.isVisible();
    }

    public void setOpaque(boolean opaque) {
        if (editor != null) {
            editor.setOpaque(opaque);
        }
    }

    public boolean isOpaque() {
        return editor != null && editor.isOpaque();
    }

    public void setDoubleBuffered(boolean doubleBuffered) {
        if (editor != null) {
            editor.setDoubleBuffered(doubleBuffered);
        }
    }

    public boolean isDoubleBuffered() {
        return editor != null && editor.isDoubleBuffered();
    }

    public void setRequestFocusEnabled(boolean requestFocusEnabled) {
        if (editor != null) {
            editor.setRequestFocusEnabled(requestFocusEnabled);
        }
    }

    public boolean isRequestFocusEnabled() {
        return editor != null && editor.isRequestFocusEnabled();
    }

    public void setVerifyInputWhenFocusTarget(boolean verifyInputWhenFocusTarget) {
        if (editor != null) {
            editor.setVerifyInputWhenFocusTarget(verifyInputWhenFocusTarget);
        }
    }

    public boolean getVerifyInputWhenFocusTarget() {
        return editor != null && editor.getVerifyInputWhenFocusTarget();
    }

    public void setNextFocusableComponent(Component c) {
        // Compatibility shim: store the next focusable component locally.
        // Modern focus traversal should use a FocusTraversalPolicy; we avoid calling deprecated JComponent APIs.
        this.nextFocusableComponent = c;
    }

    public Component getNextFocusableComponent() {
        // Return the stored compatibility value rather than calling the deprecated editor method.
        return nextFocusableComponent;
    }

    public void setToolTipText(String text) {
        if (editor != null) {
            editor.setToolTipText(text);
        }
    }

    public String getToolTipText() {
        return editor != null ? editor.getToolTipText() : null;
    }

    public String getToolTipText(MouseEvent event) {
        return editor != null ? editor.getToolTipText(event) : null;
    }

    public Point getToolTipLocation(MouseEvent event) {
        return editor != null ? editor.getToolTipLocation(event) : null;
    }

    public void setToolTipLocation(Point location) {
        if (editor != null) {
            editor.setToolTipLocation(location);
        }
    }

    public Point getToolTipLocation() {
        // JComponent.getToolTipLocation requires a MouseEvent argument; there is no zero-arg variant.
        // Returning null is a safe fallback when a concrete MouseEvent is not available.
        return null;
    }

    public void setToolTipText(String text, int x, int y) {
        if (editor != null) {
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(x, y));
        }
    }

    public void setToolTipText(String text, Point p) {
        if (editor != null && p != null) {
            editor.setToolTipText(text);
            editor.setToolTipLocation(p);
        }
    }

    public void setToolTipText(String text, int x, int y, int width, int height) {
        if (editor != null) {
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(x + width / 2, y + height / 2));
        }
    }

    public void setToolTipText(String text, Rectangle bounds) {
        if (editor != null && bounds != null) {
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
        }
    }

    public void setToolTipText(String text, Component c) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + c.getWidth() / 2, p.y + c.getHeight() / 2));
        }
    }

    public void setToolTipText(String text, Component c, int x, int y) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + x, p.y + y));
        }
    }

    public void setToolTipText(String text, Component c, Point p) {
        if (editor != null && c != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(loc.x + p.x, loc.y + p.y));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + bounds.x + bounds.width / 2, p.y + bounds.y + bounds.height / 2));
        }
    }

    public void setToolTipText(String text, Component c, int x, int y, int width, int height) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + x + width / 2, p.y + y + height / 2));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, int x, int y) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + bounds.x + x, p.y + bounds.y + y));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, Point p) {
        if (editor != null && c != null && bounds != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x, loc.y + bounds.y + p.y));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, int x, int y, int width, int height) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + bounds.x + x + width / 2, p.y + bounds.y + y + height / 2));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, Point p, int width, int height) {
        if (editor != null && c != null && bounds != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + width / 2, loc.y + bounds.y + p.y + height / 2));
        }
    }

    public void setToolTipText(String text, Component c, int x, int y, int width, int height, boolean center) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(p.x + x + width / 2, p.y + y + height / 2));
            } else {
                editor.setToolTipLocation(new Point(p.x + x, p.y + y));
            }
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, int x, int y, int width, int height, boolean center) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(p.x + bounds.x + x + width / 2, p.y + bounds.y + y + height / 2));
            } else {
                editor.setToolTipLocation(new Point(p.x + bounds.x + x, p.y + bounds.y + y));
            }
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, Point p, int width, int height, boolean center) {
        if (editor != null && c != null && bounds != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + width / 2, loc.y + bounds.y + p.y + height / 2));
            } else {
                editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x, loc.y + bounds.y + p.y));
            }
        }
    }

    public void setToolTipText(String text, Component c, int x, int y, int width, int height, int xOffset, int yOffset) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + x + xOffset, p.y + y + yOffset));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, int x, int y, int width, int height, int xOffset, int yOffset) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + bounds.x + x + xOffset, p.y + bounds.y + y + yOffset));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, Point p, int width, int height, int xOffset, int yOffset) {
        if (editor != null && c != null && bounds != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + xOffset, loc.y + bounds.y + p.y + yOffset));
        }
    }

    public void setToolTipText(String text, Component c, int x, int y, int width, int height, int xOffset, int yOffset, boolean center) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(p.x + x + width / 2 + xOffset, p.y + y + height / 2 + yOffset));
            } else {
                editor.setToolTipLocation(new Point(p.x + x + xOffset, p.y + y + yOffset));
            }
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, int x, int y, int width, int height, int xOffset, int yOffset, boolean center) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(p.x + bounds.x + x + width / 2 + xOffset, p.y + bounds.y + y + height / 2 + yOffset));
            } else {
                editor.setToolTipLocation(new Point(p.x + bounds.x + x + xOffset, p.y + bounds.y + y + yOffset));
            }
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, Point p, int width, int height, int xOffset, int yOffset, boolean center) {
        if (editor != null && c != null && bounds != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + width / 2 + xOffset, loc.y + bounds.y + p.y + height / 2 + yOffset));
            } else {
                editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + xOffset, loc.y + bounds.y + p.y + yOffset));
            }
        }
    }

    public void setToolTipText(String text, Component c, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + x + xOffset + xTextOffset, p.y + y + yOffset + yTextOffset));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + bounds.x + x + xOffset + xTextOffset, p.y + bounds.y + y + yOffset + yTextOffset));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, Point p, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset) {
        if (editor != null && c != null && bounds != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + xOffset + xTextOffset, loc.y + bounds.y + p.y + yOffset + yTextOffset));
        }
    }

    public void setToolTipText(String text, Component c, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, boolean center) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(p.x + x + width / 2 + xOffset + xTextOffset, p.y + y + height / 2 + yOffset + yTextOffset));
            } else {
                editor.setToolTipLocation(new Point(p.x + x + xOffset + xTextOffset, p.y + y + yOffset + yTextOffset));
            }
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, boolean center) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(p.x + bounds.x + x + width / 2 + xOffset + xTextOffset, p.y + bounds.y + y + height / 2 + yOffset + yTextOffset));
            } else {
                editor.setToolTipLocation(new Point(p.x + bounds.x + x + xOffset + xTextOffset, p.y + bounds.y + y + yOffset + yTextOffset));
            }
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, Point p, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, boolean center) {
        if (editor != null && c != null && bounds != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + width / 2 + xOffset + xTextOffset, loc.y + bounds.y + p.y + height / 2 + yOffset + yTextOffset));
            } else {
                editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + xOffset + xTextOffset, loc.y + bounds.y + p.y + yOffset + yTextOffset));
            }
        }
    }

    public void setToolTipText(String text, Component c, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + x + xOffset + xTextOffset + xTextPadding, p.y + y + yOffset + yTextOffset + yTextPadding));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + bounds.x + x + xOffset + xTextOffset + xTextPadding, p.y + bounds.y + y + yOffset + yTextOffset + yTextPadding));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, Point p, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding) {
        if (editor != null && c != null && bounds != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + xOffset + xTextOffset + xTextPadding, loc.y + bounds.y + p.y + yOffset + yTextOffset + yTextPadding));
        }
    }

    public void setToolTipText(String text, Component c, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, boolean center) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(p.x + x + width / 2 + xOffset + xTextOffset + xTextPadding, p.y + y + height / 2 + yOffset + yTextOffset + yTextPadding));
            } else {
                editor.setToolTipLocation(new Point(p.x + x + xOffset + xTextOffset + xTextPadding, p.y + y + yOffset + yTextOffset + yTextPadding));
            }
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, boolean center) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(p.x + bounds.x + x + width / 2 + xOffset + xTextOffset + xTextPadding, p.y + bounds.y + y + height / 2 + yOffset + yTextOffset + yTextPadding));
            } else {
                editor.setToolTipLocation(new Point(p.x + bounds.x + x + xOffset + xTextOffset + xTextPadding, p.y + bounds.y + y + yOffset + yTextOffset + yTextPadding));
            }
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, Point p, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, boolean center) {
        if (editor != null && c != null && bounds != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + width / 2 + xOffset + xTextOffset + xTextPadding, loc.y + bounds.y + p.y + height / 2 + yOffset + yTextOffset + yTextPadding));
            } else {
                editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + xOffset + xTextOffset + xTextPadding, loc.y + bounds.y + p.y + yOffset + yTextOffset + yTextPadding));
            }
        }
    }

    public void setToolTipText(String text, Component c, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, int xToolTipOffset, int yToolTipOffset) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + x + xOffset + xTextOffset + xTextPadding + xToolTipOffset, p.y + y + yOffset + yTextOffset + yTextPadding + yToolTipOffset));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, int xToolTipOffset, int yToolTipOffset) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + bounds.x + x + xOffset + xTextOffset + xTextPadding + xToolTipOffset, p.y + bounds.y + y + yOffset + yTextOffset + yTextPadding + yToolTipOffset));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, Point p, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, int xToolTipOffset, int yToolTipOffset) {
        if (editor != null && c != null && bounds != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + xOffset + xTextOffset + xTextPadding + xToolTipOffset, loc.y + bounds.y + p.y + yOffset + yTextOffset + yTextPadding + yToolTipOffset));
        }
    }

    public void setToolTipText(String text, Component c, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, int xToolTipOffset, int yToolTipOffset, boolean center) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(p.x + x + width / 2 + xOffset + xTextOffset + xTextPadding + xToolTipOffset, p.y + y + height / 2 + yOffset + yTextOffset + yTextPadding + yToolTipOffset));
            } else {
                editor.setToolTipLocation(new Point(p.x + x + xOffset + xTextOffset + xTextPadding + xToolTipOffset, p.y + y + yOffset + yTextOffset + yTextPadding + yToolTipOffset));
            }
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, int xToolTipOffset, int yToolTipOffset, boolean center) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(p.x + bounds.x + x + width / 2 + xOffset + xTextOffset + xTextPadding + xToolTipOffset, p.y + bounds.y + y + height / 2 + yOffset + yTextOffset + yTextPadding + yToolTipOffset));
            } else {
                editor.setToolTipLocation(new Point(p.x + bounds.x + x + xOffset + xTextOffset + xTextPadding + xToolTipOffset, p.y + bounds.y + y + yOffset + yTextOffset + yTextPadding + yToolTipOffset));
            }
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, Point p, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, int xToolTipOffset, int yToolTipOffset, boolean center) {
        if (editor != null && c != null && bounds != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + width / 2 + xOffset + xTextOffset + xTextPadding + xToolTipOffset, loc.y + bounds.y + p.y + height / 2 + yOffset + yTextOffset + yTextPadding + yToolTipOffset));
            } else {
                editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + xOffset + xTextOffset + xTextPadding + xToolTipOffset, loc.y + bounds.y + p.y + yOffset + yTextOffset + yTextPadding + yToolTipOffset));
            }
        }
    }

    public void setToolTipText(String text, Component c, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, int xToolTipOffset, int yToolTipOffset, int xFinalOffset, int yFinalOffset) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + x + xOffset + xTextOffset + xTextPadding + xToolTipOffset + xFinalOffset, p.y + y + yOffset + yTextOffset + yTextPadding + yToolTipOffset + yFinalOffset));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, int xToolTipOffset, int yToolTipOffset, int xFinalOffset, int yFinalOffset) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(p.x + bounds.x + x + xOffset + xTextOffset + xTextPadding + xToolTipOffset + xFinalOffset, p.y + bounds.y + y + yOffset + yTextOffset + yTextPadding + yToolTipOffset + yFinalOffset));
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, Point p, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, int xToolTipOffset, int yToolTipOffset, int xFinalOffset, int yFinalOffset) {
        if (editor != null && c != null && bounds != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + xOffset + xTextOffset + xTextPadding + xToolTipOffset + xFinalOffset, loc.y + bounds.y + p.y + yOffset + yTextOffset + yTextPadding + yToolTipOffset + yFinalOffset));
        }
    }

    public void setToolTipText(String text, Component c, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, int xToolTipOffset, int yToolTipOffset, int xFinalOffset, int yFinalOffset, boolean center) {
        if (editor != null && c != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(p.x + x + width / 2 + xOffset + xTextOffset + xTextPadding + xToolTipOffset + xFinalOffset, p.y + y + height / 2 + yOffset + yTextOffset + yTextPadding + yToolTipOffset + yFinalOffset));
            } else {
                editor.setToolTipLocation(new Point(p.x + x + xOffset + xTextOffset + xTextPadding + xToolTipOffset + xFinalOffset, p.y + y + yOffset + yTextOffset + yTextPadding + yToolTipOffset + yFinalOffset));
            }
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, int x, int y, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, int xToolTipOffset, int yToolTipOffset, int xFinalOffset, int yFinalOffset, boolean center) {
        if (editor != null && c != null && bounds != null) {
            Point p = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(p.x + bounds.x + x + width / 2 + xOffset + xTextOffset + xTextPadding + xToolTipOffset + xFinalOffset, p.y + bounds.y + y + height / 2 + yOffset + yTextOffset + yTextPadding + yToolTipOffset + yFinalOffset));
            } else {
                editor.setToolTipLocation(new Point(p.x + bounds.x + x + xOffset + xTextOffset + xTextPadding + xToolTipOffset + xFinalOffset, p.y + bounds.y + y + yOffset + yTextOffset + yTextPadding + yToolTipOffset + yFinalOffset));
            }
        }
    }

    public void setToolTipText(String text, Component c, Rectangle bounds, Point p, int width, int height, int xOffset, int yOffset, int xTextOffset, int yTextOffset, int xTextPadding, int yTextPadding, int xToolTipOffset, int yToolTipOffset, int xFinalOffset, int yFinalOffset, boolean center) {
        if (editor != null && c != null && bounds != null && p != null) {
            Point loc = c.getLocation();
            editor.setToolTipText(text);
            if (center) {
                editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + width / 2 + xOffset + xTextOffset + xTextPadding + xToolTipOffset + xFinalOffset, loc.y + bounds.y + p.y + height / 2 + yOffset + yTextOffset + yTextPadding + yToolTipOffset + yFinalOffset));
            } else {
                editor.setToolTipLocation(new Point(loc.x + bounds.x + p.x + xOffset + xTextOffset + xTextPadding + xToolTipOffset + xFinalOffset, loc.y + bounds.y + p.y + yOffset + yTextOffset + yTextPadding + yToolTipOffset + yFinalOffset));
            }
        }
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        if (editor != null) {
            if (editor instanceof ThemeAware) {
                ((ThemeAware) editor).applyTheme(guiTheme);
            } else {
                guiTheme.applyCurrentThemeToComponent(editor);
            }
        }
    }

    public void invalidateViewportCacheForBothSides() {
        if (diffPanel != null) {
            diffPanel.invalidateViewportCacheForBothSides();
        }
    }

    /**
     * Clear any per-panel viewport cache (alias used by ScrollSynchronizer).
     */
    public void invalidateViewportCache() {
        clearViewportCache();
    }

    /**
     * Called by ScrollSynchronizer to indicate programmatic navigation is in progress.
     * Kept lightweight for now.
     */
    public void setNavigatingToDiff(boolean navigating) {
        // placeholder for potential visual state; no-op to satisfy callers
    }

    /**
     * Returns true while the user is actively typing (used to avoid scroll-sync interference).
     */
    public boolean isActivelyTyping() {
        return isActivelyTyping != null && isActivelyTyping.get();
    }

    public void dispose() {
        // Clean up resources
        if (mouseStopTimer != null) {
            mouseStopTimer.stop();
            mouseStopTimer = null;
        }
        
        if (typingStateTimer != null) {
            typingStateTimer.stop();
            typingStateTimer = null;
        }
        
        if (editor != null) {
            editor.removeAll();
            editor = null;
        }
        
        if (scrollPane != null) {
            scrollPane.removeAll();
            scrollPane = null;
        }
        
        if (visualComponentContainer != null) {
            visualComponentContainer.removeAll();
            visualComponentContainer = null;
        }
        
        if (gutterComponent != null) {
            // DiffGutterComponent may not expose a dispose() method on all versions; removeAll() is safe and releases UI children.
            gutterComponent.removeAll();
            gutterComponent = null;
        }
        
        jmHighlighter = null;
        bufferDocument = null;
        searchHits = null;
        plainDocument = null;
        plainToEditorListener = null;
        editorToPlainListener = null;
        timer = null;
    }
}