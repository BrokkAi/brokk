package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.doc.FileDocument;
import io.github.jbellis.brokk.difftool.doc.JMDocumentEvent;
import io.github.jbellis.brokk.difftool.doc.StringDocument;
import io.github.jbellis.brokk.difftool.node.BufferNode;
import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.scroll.DiffScrollComponent;
import io.github.jbellis.brokk.difftool.scroll.ScrollSynchronizer;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.util.SlidingWindowCache;
import io.github.jbellis.brokk.gui.search.GenericSearchBar;
import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * This panel shows the side-by-side file panels, the diff curves, plus search bars.
 * It no longer depends on custom JMRevision/JMDelta but rather on a Patch<String>.
 */
public class BufferDiffPanel extends AbstractContentPanel implements ThemeAware, SlidingWindowCache.Disposable
{
    private static final Logger logger = LogManager.getLogger(BufferDiffPanel.class);

   /**
     * Enum representing the two sides of the diff panel.
     * Provides type safety and clarity compared to magic numbers.
     */
    public enum PanelSide
    {
        LEFT(BufferDocumentIF.ORIGINAL, 0),
        RIGHT(BufferDocumentIF.REVISED, 1);

        private final String documentType;
        private final int index;

        PanelSide(String documentType, int index) {
            this.documentType = documentType;
            this.index = index;
        }

        public String getDocumentType() {
            return documentType;
        }

        public int getIndex() {
            return index;
        }
    }

    private final BrokkDiffPanel mainPanel;
    private GuiTheme guiTheme;

    // Instead of JMRevision:
    @Nullable
    private Patch<String> patch; // from JMDiffNode
    @Nullable
    private AbstractDelta<String> selectedDelta;

    /**
     * Ensures that the automatic centering of the first difference executes at
     * most once per BufferDiffPanel lifecycle.
     */
    private volatile boolean initialAutoScrollDone = false;

    /**
     * Guards against concurrent auto-scroll attempts to prevent interference.
     */
    private volatile boolean autoScrollInProgress = false;

    private int selectedLine;

    /**
     * Dirty flag that tracks whether there are any unsaved changes.
     */
    private boolean dirtySinceOpen = false;

    /**
     * Tracks applied diff operations that haven't been saved yet.
     * Maps filename to count of operations applied.
     * Thread-safe to handle concurrent access from EDT and background threads.
     */
    private final Map<String, Integer> pendingDiffChanges = new ConcurrentHashMap<>();

    /**
     * Tracks the content of files before any diff changes were applied.
     * Used to generate unified diffs showing what changes were made.
     * Thread-safe to handle concurrent access from EDT and background threads.
     */
    private final Map<String, String> contentBeforeChanges = new ConcurrentHashMap<>();


    /**
    * Recalculate dirty status by checking if any FilePanel has unsaved changes.
    * When the state changes, update tab title and toolbar buttons.
    */
    void recalcDirty() {
            // Check if either side has unsaved changes (document changed since last save)
            boolean newDirty = filePanels.values().stream().anyMatch(FilePanel::isDocumentChanged);

        if (dirtySinceOpen != newDirty) {
            dirtySinceOpen = newDirty;
            SwingUtilities.invokeLater(() -> {
                mainPanel.refreshTabTitle(BufferDiffPanel.this);
                mainPanel.updateUndoRedoButtons();
            });
        }
    }

    @Nullable
    private GenericSearchBar leftSearchBar;
    @Nullable
    private GenericSearchBar rightSearchBar;

    // The left & right "file panels" using type-safe enum map
    private final EnumMap<PanelSide, FilePanel> filePanels = new EnumMap<>(PanelSide.class);
    private PanelSide selectedPanelSide = PanelSide.LEFT;

    @Nullable
    private JMDiffNode diffNode; // Where we get the Patch<String>
    @Nullable
    private ScrollSynchronizer scrollSynchronizer;

    public BufferDiffPanel(BrokkDiffPanel mainPanel, GuiTheme theme)
    {
        this.mainPanel = mainPanel;
        this.guiTheme = theme;

        // Let the mainPanel keep a reference to us for toolbar/undo/redo interplay
        mainPanel.setBufferDiffPanel(this);

        init();
        setFocusable(true);
    }

    public void setDiffNode(@Nullable JMDiffNode diffNode)
    {
        this.diffNode = diffNode;
        refreshDiffNode();
    }

    @Nullable
    public JMDiffNode getDiffNode()
    {
        return diffNode;
    }

    /**
     * Re-read the patch from the node, re-bind the left & right documents, etc.
     */
    private void refreshDiffNode()
    {
        if (diffNode == null) {
            return;
        }
        BufferNode bnLeft = diffNode.getBufferNodeLeft();
        BufferNode bnRight = diffNode.getBufferNodeRight();

        BufferDocumentIF leftDocument = bnLeft != null ? bnLeft.getDocument() : null;
        BufferDocumentIF rightDocument = bnRight != null ? bnRight.getDocument() : null;

        // Calculate the diff to get the patch with actual differences
        diffNode.diff();
        this.patch = diffNode.getPatch(); // new Patch or null

        // Initialize selectedDelta to first change for proper navigation button states
        if (patch != null && !patch.getDeltas().isEmpty()) {
            selectedDelta = patch.getDeltas().getFirst();
        } else {
            selectedDelta = null;
        }

        // Set the documents into our file panels:
        // Order is important, The left panel will use the left panel syntax type if it can't figure its own
        var rightPanel = getFilePanel(PanelSide.RIGHT);
        if (rightPanel != null && rightDocument != null) {
            rightPanel.setBufferDocument(rightDocument);
        }
        var leftPanel = getFilePanel(PanelSide.LEFT);
        if (leftPanel != null && leftDocument != null) {
            leftPanel.setBufferDocument(leftDocument);
        }

        // Don't apply theme here - let it happen after the panel is added to the UI

        // Initialize dirty flag - should be false since no edits have been made yet
        recalcDirty();

        // Reset auto-scroll flag for new file/diff content to ensure fresh auto-scroll opportunity
        initialAutoScrollDone = false;

        // Auto-scroll to first difference when diff is opened, or to top when no differences
        if (selectedDelta != null && scrollSynchronizer != null) {
            // Check if auto-scroll should be skipped
            boolean skipAutoScroll = shouldSkipAutoScroll();

            if (!skipAutoScroll) {
                logger.debug("Auto-scrolling to first difference at line {}", selectedDelta.getSource().getPosition());
                SwingUtilities.invokeLater(this::scrollToFirstDifference);
            }
        } else if (scrollSynchronizer != null) {
            scrollToTop();
        }
    }

    /**
     * Rerun the diff from scratch, if needed. For Phase 2 we re-run if a doc changed
     * (the old incremental logic is removed).
     */
    public void diff() {
        diff(false); // Don't scroll by default (used for document changes)
    }

    /**
     * Rerun the diff and optionally scroll to the selected delta.
     * @param scrollToSelection whether to scroll to the selected delta after recalculation
     */
    public void diff(boolean scrollToSelection)
    {
        // Typically, we'd just re-call diffNode.diff() then re-pull patch.
        if (diffNode != null) {
            diffNode.diff();
            this.patch = diffNode.getPatch();

            // Try to preserve selected delta position, or find best alternative
            var previousDelta = selectedDelta;
            if (patch != null && !patch.getDeltas().isEmpty()) {
                if (previousDelta != null && patch.getDeltas().contains(previousDelta)) {
                    // Keep the same delta if it's still valid
                    selectedDelta = previousDelta;
                } else if (previousDelta != null) {
                    // Find the closest delta by line position
                    selectedDelta = findClosestDelta(previousDelta, patch.getDeltas());
                } else {
                    // No previous selection, start with first
                    selectedDelta = patch.getDeltas().getFirst();
                }
            } else {
                selectedDelta = null;
            }

            // Refresh display and optionally scroll to selected delta
            reDisplay();
            if (scrollToSelection && selectedDelta != null) {
                showSelectedDelta();
            }
        }
    }

    /**
     * Find the closest delta by line position when the previously selected delta is no longer valid.
     */
    @Nullable
    private AbstractDelta<String> findClosestDelta(AbstractDelta<String> previousDelta, List<AbstractDelta<String>> availableDeltas) {
        if (availableDeltas.isEmpty()) {
            return null;
        }

        // Use the start line of the previous delta's source as reference
        int targetLine = previousDelta.getSource().getPosition();

        // Find the delta with the closest line position
        return availableDeltas.stream()
            .min((d1, d2) -> Integer.compare(
                Math.abs(d1.getSource().getPosition() - targetLine),
                Math.abs(d2.getSource().getPosition() - targetLine)
            ))
            .orElse(availableDeltas.getFirst());
    }

    /**
     * Tells each FilePanel to re-apply highlights, then repaint the parent panel.
     */
    private void reDisplay()
    {
        for (var fp : filePanels.values()) {
            fp.reDisplay();
        }
        mainPanel.repaint();
    }

    /**
     * Synchronizes the content of a source Document to a destination Document.
     * This is a fallback mechanism to ensure documents are identical.
     *
     * @param srcDoc the source document
     * @param dstDoc the destination document
     */
    public static void synchronizeDocuments(Document srcDoc, Document dstDoc)
    {
        if (srcDoc != dstDoc) { // copy only when different
            try {
                var len = srcDoc.getLength();
                var text = srcDoc.getText(0, len);
                dstDoc.remove(0, dstDoc.getLength());
                dstDoc.insertString(0, text, null);
            } catch (BadLocationException ex) {
                throw new RuntimeException("Failed to synchronize documents", ex);
            }
        }
    }

    /**
     * Synchronizes the BufferDocument's underlying document with the editor's document.
     * This ensures both documents contain identical content after text modifications.
     */
    private void synchronizeDocuments(JTextComponent editor, BufferDocumentIF bufferDoc) {
        synchronizeDocuments(editor.getDocument(), bufferDoc.getDocument());
    }

    public String getTitle()
    {
        if (diffNode != null && !diffNode.getName().isBlank()) {
            var name = diffNode.getName();
            return isDirty() ? name + " *" : name;
        }

        // Fallback if diffNode or its name is not available
        var titles = new ArrayList<String>();
        for (var fp : filePanels.values()) {
            var bd = fp.getBufferDocument();
            if (bd != null && !bd.getShortName().isBlank()) {
                titles.add(bd.getShortName());
            }
        }
        if (titles.isEmpty()) {
            return "Diff"; // Generic fallback
        }
        String base;
        if (titles.size() == 1) {
            base = titles.getFirst();
        } else if (titles.get(0).equals(titles.get(1))) {
            base = titles.getFirst();
        } else {
            base = String.join(" vs ", titles);
        }
        return isDirty() ? base + " *" : base;
    }

    /**
     * Returns true if there are any unsaved changes on either side.
     */
    public boolean isDirty() {
        return dirtySinceOpen;
    }


    /**
     * Do not try incremental updates. We just re-diff the whole thing.
     */
    public boolean revisionChanged(JMDocumentEvent de)
    {
        // Old incremental logic removed
        diff();
        return true;
    }

    /**
     * The top-level UI for the left & right file panels plus the "diff scroll component".
     */
    private void init()
    {
        var columns = "3px, pref, 3px, 0:grow, 5px, min, 60px, 0:grow, 25px, min, 3px, pref, 3px";
        var rows = "6px, pref, 3px, fill:0:grow, pref";

        setLayout(new BorderLayout());

        // Build file panels first so they exist when creating search bars
        var filePanelComponent = buildFilePanel(columns, rows);
        var searchBarComponent = activateBarDialog(columns);

        // Add components directly to BorderLayout without vertical resize capability
        add(searchBarComponent, BorderLayout.NORTH);
        add(filePanelComponent, BorderLayout.CENTER);

        // Create the scroll synchronizer for the left & right panels
        scrollSynchronizer = new ScrollSynchronizer(this, requireFilePanel(PanelSide.LEFT), requireFilePanel(PanelSide.RIGHT));
        setSelectedPanel(PanelSide.LEFT);
        mainPanel.updateUndoRedoButtons();
        // Apply initial theme for syntax highlighting (but not diff highlights yet)
        applyTheme(guiTheme);
        // Register keyboard shortcuts for search functionality
        registerSearchKeyBindings();

        // Register save shortcut (Ctrl+S / Cmd+S)
        registerSaveShortcut();
    }

    /**
     * Re-establish component resize listeners after file navigation.
     * This ensures resize events are properly handled after tab operations.
     */
    public void refreshComponentListeners() {
        // Remove existing listeners to avoid duplicates
        var listeners = getComponentListeners();
        for (var listener : listeners) {
            removeComponentListener(listener);
        }

        // Re-add resize listener for scroll synchronization
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                SwingUtilities.invokeLater(() -> {
                    if (scrollSynchronizer != null) {
                        scrollSynchronizer.invalidateViewportCacheForBothPanels();
                    }
                });
            }
        });
    }

    /**
     * Build the top row that holds search bars.
     */
    public JPanel activateBarDialog(String columns)
    {
        // Use the same FormLayout structure as the file panels to align search bars with text areas
        var rows = "6px, pref";
        var layout = new com.jgoodies.forms.layout.FormLayout(columns, rows);
        var cc = new com.jgoodies.forms.layout.CellConstraints();
        var barContainer = new JPanel(layout);

        // Create GenericSearchBar instances using the FilePanel's SearchableComponent adapters
        var leftFilePanel = getFilePanel(PanelSide.LEFT);
        var rightFilePanel = getFilePanel(PanelSide.RIGHT);
        if(leftFilePanel != null && rightFilePanel != null) {
            leftSearchBar = new GenericSearchBar(leftFilePanel.createSearchableComponent());
            rightSearchBar = new GenericSearchBar(rightFilePanel.createSearchableComponent());
        }

        // Add search bars aligned with the text areas below
        // Left search bar spans the same columns as the left text area (columns 4-6)
        if (leftSearchBar != null) {
            barContainer.add(leftSearchBar, cc.xyw(4, 2, 3)); // Same span as left text area
        }
        // Right search bar spans the same columns as the right text area (columns 8-10)
        if (rightSearchBar != null) {
            barContainer.add(rightSearchBar, cc.xyw(8, 2, 3)); // Same span as right text area
        }

        return barContainer;
    }

    /**
     * Build the actual file-panels and the center "diff scroll curves".
     */
    private JPanel buildFilePanel(String columns, String rows)
    {
        var layout = new FormLayout(columns, rows);
        var cc = new CellConstraints();
        var panel = new JPanel(layout);

        // Create file panels using enum-based approach
        filePanels.put(PanelSide.LEFT, new FilePanel(this, PanelSide.LEFT.getDocumentType()));
        filePanels.put(PanelSide.RIGHT, new FilePanel(this, PanelSide.RIGHT.getDocumentType()));

        var leftPanel = requireFilePanel(PanelSide.LEFT);
        var rightPanel = requireFilePanel(PanelSide.RIGHT);

        // Left side revision bar
        panel.add(new RevisionBar(this, leftPanel, true), cc.xy(2, 4));
        panel.add(new JLabel(""), cc.xy(2, 2)); // for spacing

        panel.add(leftPanel.getVisualComponent(), cc.xyw(4, 4, 3));

        // The middle area for drawing the linking curves
        var diffScrollComponent = new DiffScrollComponent(this, PanelSide.LEFT.getIndex(), PanelSide.RIGHT.getIndex());
        panel.add(diffScrollComponent, cc.xy(7, 4));

        // Right side revision bar
        panel.add(new RevisionBar(this, rightPanel, false), cc.xy(12, 4));
        panel.add(rightPanel.getVisualComponent(), cc.xyw(8, 4, 3));

        panel.setMinimumSize(new Dimension(300, 200));
        return panel;
    }

    @Nullable
    public ScrollSynchronizer getScrollSynchronizer()
    {   return scrollSynchronizer;
    }

    public BrokkDiffPanel getMainPanel()
    {
        return mainPanel;
    }

    /**
     * We simply retrieve the patch from the node if needed.
     */
    @Nullable
    public Patch<String> getPatch()
    {
        return patch;
    }

    /**
     * Return whichever delta is considered "selected" in the UI.
     */
    @Nullable
    public AbstractDelta<String> getSelectedDelta()
    {
        return selectedDelta;
    }

    /**
     * Called by `DiffScrollComponent` or `RevisionBar` to set which delta has been clicked.
     */
    public void setSelectedDelta(@Nullable AbstractDelta<String> newDelta)
    {
        this.selectedDelta = newDelta;
        setSelectedLine(newDelta != null ? newDelta.getSource().getPosition() : 0);
    }

    public void setSelectedLine(int line)
    {
        selectedLine = line;
    }

    public int getSelectedLine()
    {
        return selectedLine;
    }

    /**
     * Reset the auto-scroll flag to allow auto-scroll for file navigation.
     * Called by BrokkDiffPanel when switching between files.
     */
    public void resetAutoScrollFlag()
    {
        initialAutoScrollDone = false;
    }

    /**
     * Reset selectedDelta to first difference for consistent file navigation behavior.
     * Ensures all file navigation goes to first diff regardless of caching.
     */
    public void resetToFirstDifference()
    {
        if (patch != null && !patch.getDeltas().isEmpty()) {
            selectedDelta = patch.getDeltas().getFirst();
        } else {
            selectedDelta = null;
        }
    }


    /**
     * Type-safe method to get a file panel by side.
     * @param side the panel side (LEFT or RIGHT)
     * @return the FilePanel for the specified side, or null if not set
     */
    @Nullable
    public FilePanel getFilePanel(PanelSide side)
    {
        return filePanels.get(side);
    }

    /**
     * Type-safe method to get a file panel, throwing if not initialized.
     * @param side the panel side (LEFT or RIGHT)
     * @return the FilePanel for the specified side
     * @throws IllegalStateException if the panel is not initialized
     */
    public FilePanel requireFilePanel(PanelSide side)
    {
        var panel = filePanels.get(side);
        if (panel == null) {
            throw new IllegalStateException("FilePanel for " + side + " is not initialized");
        }
        return panel;
    }

    /**
     * Gets the currently selected panel side.
     */
    public PanelSide getSelectedPanelSide()
    {
        return selectedPanelSide;
    }

    /**
     * Gets the currently selected file panel.
     */
    public FilePanel getSelectedFilePanel()
    {
        return requireFilePanel(selectedPanelSide);
    }

    /**
     * Legacy helper method to get a file panel by integer index.
     * This supports compatibility with existing code that uses integer indices.
     * @param index 0 for LEFT, 1 for RIGHT (or any other integer for RIGHT)
     * @return the FilePanel for the specified index, or null if not set
     */
    @Nullable
    public FilePanel getFilePanel(int index)
    {
        var side = (index == 0) ? PanelSide.LEFT : PanelSide.RIGHT;
        return filePanels.get(side);
    }

    void setSelectedPanel(FilePanel fp)
    {
        var oldSide = selectedPanelSide;
        PanelSide newSide = null;

        // Find which side this panel corresponds to
        for (var entry : filePanels.entrySet()) {
            if (entry.getValue() == fp) {
                newSide = entry.getKey();
                break;
            }
        }

        if (newSide != null && newSide != oldSide) {
            selectedPanelSide = newSide;
        }
    }

    /**
     * Type-safe method to set the selected panel by side.
     */
    public void setSelectedPanel(PanelSide side) {
        this.selectedPanelSide = side;
    }

    /**
     * Called by the top-level toolbar "Next" or "Previous" or by mouse wheel in DiffScrollComponent.
     */
    public void toNextDelta(boolean next)
    {
        if (patch == null || patch.getDeltas().isEmpty()) {
            return;
        }
        var deltas = patch.getDeltas();
        if (selectedDelta == null) {
            // If nothing selected, pick first or last
            setSelectedDelta(next ? deltas.getFirst() : deltas.getLast());
            showSelectedDelta();
            return;
        }
        var idx = deltas.indexOf(selectedDelta);
        if (idx < 0) {
            // The current selection is not in the patch list, pick first
            setSelectedDelta(deltas.getFirst());
        } else {
            var newIdx = next ? idx + 1 : idx - 1;
            if (newIdx < 0) newIdx = deltas.size() - 1;
            if (newIdx >= deltas.size()) newIdx = 0;
            setSelectedDelta(deltas.get(newIdx));
        }
        showSelectedDelta();
    }

    /**
     * Scroll so the selectedDelta is visible in the left panel, then the ScrollSynchronizer
     * will sync the right side.
     */
    private void showSelectedDelta()
    {
        if (selectedDelta == null) return;
        if (scrollSynchronizer != null) {
            scrollSynchronizer.showDelta(selectedDelta);
        }
    }

    /**
     * Auto-scroll to the first difference when a diff is opened.
     * Centers the first difference on both panels for optimal user experience.
     */
    private void scrollToFirstDifference()
    {
        if (selectedDelta == null || scrollSynchronizer == null) {
            return;
        }

        if (initialAutoScrollDone) {
            return;
        }

        if (autoScrollInProgress) {
            logger.debug("Auto-scroll already in progress, skipping concurrent attempt");
            return;
        }

        autoScrollInProgress = true;
        // Use robust retry mechanism to handle UI timing issues
        scheduleAutoScrollWithRetry();
    }


    /**
     * Schedule auto-scroll with ComponentListener approach to handle UI timing issues.
     */
    private void scheduleAutoScrollWithRetry()
    {
        // Use a different approach: wait for components to become visible
        SwingUtilities.invokeLater(this::executeAutoScrollWhenReady);
    }

    /**
     * Execute auto-scroll when UI components are ready, using ComponentListener for reliable timing.
     */
    private void executeAutoScrollWhenReady()
    {
        // Re-check nulls for NullAway
        if (selectedDelta == null || scrollSynchronizer == null) {
            autoScrollInProgress = false;
            return;
        }

        var leftPanel = getFilePanel(PanelSide.LEFT);
        var rightPanel = getFilePanel(PanelSide.RIGHT);

        if (leftPanel == null || rightPanel == null) {
            autoScrollInProgress = false;
            return;
        }

        var leftScrollPane = leftPanel.getScrollPane();
        var rightScrollPane = rightPanel.getScrollPane();

        // Check if components are ready immediately
        if (areComponentsReady(leftScrollPane, rightScrollPane)) {
            performAutoScroll();
            return;
        }

        // Use ComponentListener to wait for visibility
        var componentListener = new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                if (areComponentsReady(leftScrollPane, rightScrollPane)) {
                    performAutoScroll();
                    // Remove listeners to prevent memory leaks
                    leftScrollPane.removeComponentListener(this);
                    rightScrollPane.removeComponentListener(this);
                }
            }
        };

        // Add listener to both scroll panes
        leftScrollPane.addComponentListener(componentListener);
        rightScrollPane.addComponentListener(componentListener);

        // Safety timeout - if components don't become ready within 3 seconds, give up
        javax.swing.Timer timeoutTimer = new javax.swing.Timer(3000, e -> {
            leftScrollPane.removeComponentListener(componentListener);
            rightScrollPane.removeComponentListener(componentListener);
            // Reset the concurrency flag since we're giving up
            autoScrollInProgress = false;
        });
        timeoutTimer.setRepeats(false);
        timeoutTimer.start();
    }

    /**
     * Check if scroll pane components are ready for auto-scroll.
     */
    private boolean areComponentsReady(javax.swing.JScrollPane leftScrollPane, javax.swing.JScrollPane rightScrollPane)
    {
        boolean leftReady = leftScrollPane.isDisplayable() && leftScrollPane.isShowing() &&
                          leftScrollPane.getViewport().getSize().height > 0;
        boolean rightReady = rightScrollPane.isDisplayable() && rightScrollPane.isShowing() &&
                           rightScrollPane.getViewport().getSize().height > 0;
        return leftReady && rightReady;
    }

    /**
     * Perform the actual auto-scroll operation.
     */
    private void performAutoScroll()
    {
        try {
            if (selectedDelta != null && scrollSynchronizer != null) {
                initialAutoScrollDone = true;
                scrollSynchronizer.showDelta(selectedDelta);
            }
        } finally {
            // Always reset the flag to allow future auto-scroll attempts
            autoScrollInProgress = false;
        }
    }

    /**
     * Scroll both panels to the top position (line 0).
     * Used for files without differences to provide consistent starting position.
     */
    private void scrollToTop()
    {
        var leftPanel = getFilePanel(PanelSide.LEFT);
        var rightPanel = getFilePanel(PanelSide.RIGHT);

        if (leftPanel != null && rightPanel != null) {
            SwingUtilities.invokeLater(() -> {
                try {
                    // Scroll both panels to position 0
                    leftPanel.getEditor().setCaretPosition(0);
                    rightPanel.getEditor().setCaretPosition(0);

                    // Ensure the scroll position is at the top
                    leftPanel.getScrollPane().getViewport().setViewPosition(new java.awt.Point(0, 0));
                    rightPanel.getScrollPane().getViewport().setViewPosition(new java.awt.Point(0, 0));

                    logger.debug("scrollToTop: Successfully scrolled both panels to top");
                } catch (Exception e) {
                    logger.debug("scrollToTop: Error scrolling to top", e);
                }
            });
        }
    }

    /**
     * Determines whether auto-scroll should be skipped for this diff.
     * Auto-scroll is skipped for:
     * 1. File additions/deletions (one side ≤ 2 lines)
     * 2. Massive changes (single large delta from beginning)
     * 3. Pure INSERT/DELETE deltas with very asymmetric content (< 5 vs > 20 lines)
     *
     * @return true if auto-scroll should be skipped
     */
    private boolean shouldSkipAutoScroll()
    {
        if (patch == null || patch.getDeltas().isEmpty()) {
            return false;
        }

        // Primary check: file addition/deletion scenarios (one side essentially empty)
        if (isFileAdditionOrDeletion()) {
            logger.debug("Skipping auto-scroll: file addition/deletion detected");
            return true;
        }

        // Check for massive single change covering most of the file
        if (isMassiveFileChange()) {
            logger.debug("Skipping auto-scroll: massive file change detected");
            return true;
        }

        // Only skip pure INSERT/DELETE if BOTH conditions are met:
        // 1. All deltas are pure INSERT/DELETE
        // 2. AND one side is very asymmetric (< 5 lines vs > 20 lines)
        boolean hasOnlyInsertDelete = patch.getDeltas().stream().allMatch(delta -> {
            var sourceSize = delta.getSource().size();
            var targetSize = delta.getTarget().size();
            return sourceSize == 0 || targetSize == 0;
        });

        if (hasOnlyInsertDelete && isVeryAsymmetricContent()) {
            logger.debug("Skipping auto-scroll: pure INSERT/DELETE with asymmetric content");
            return true;
        }

        return false;
    }

    /**
     * Detects file addition or deletion by checking if one side is essentially empty.
     * @return true if one side has very few lines (likely file addition/deletion)
     */
    private boolean isFileAdditionOrDeletion()
    {
        var leftPanel = getFilePanel(PanelSide.LEFT);
        var rightPanel = getFilePanel(PanelSide.RIGHT);

        if (leftPanel == null || rightPanel == null) {
            return false;
        }

        var leftDoc = leftPanel.getBufferDocument();
        var rightDoc = rightPanel.getBufferDocument();

        if (leftDoc == null || rightDoc == null) {
            return false;
        }

        // Get line counts for both sides
        int leftLines = leftDoc.getNumberOfLines();
        int rightLines = rightDoc.getNumberOfLines();

        // Be more conservative: only consider it file addition/deletion if one side is actually empty (0-1 lines)
        // and the other side has substantial content (> 5 lines)
        boolean leftEmpty = leftLines <= 1;
        boolean rightEmpty = rightLines <= 1;
        boolean leftSubstantial = leftLines > 5;
        boolean rightSubstantial = rightLines > 5;

        boolean isAdditionOrDeletion = (leftEmpty && rightSubstantial) || (rightEmpty && leftSubstantial);

        if (isAdditionOrDeletion) {
            logger.debug("File addition/deletion detected: left={} lines, right={} lines", leftLines, rightLines);
        }

        return isAdditionOrDeletion;
    }

    /**
     * Detects massive changes that likely represent file restructuring.
     * @return true if there's a single large delta covering most of the file
     */
    private boolean isMassiveFileChange()
    {
        if (patch == null || patch.getDeltas().size() != 1) {
            return false;
        }

        var delta = patch.getDeltas().getFirst();

        // Be more restrictive: consider it massive only if:
        // 1. Change starts at the very beginning (position <= 1)
        // 2. AND both source and target have very substantial content (> 50 lines)
        // This catches whole-file replacements but not normal large edits
        boolean isMassive = delta.getSource().getPosition() <= 1 &&
               delta.getSource().size() > 50 && delta.getTarget().size() > 50;

        if (isMassive) {
            logger.debug("Massive file change detected: position={}, sourceSize={}, targetSize={}",
                       delta.getSource().getPosition(), delta.getSource().size(), delta.getTarget().size());
        }

        return isMassive;
    }

    /**
     * Detects very asymmetric content where one side is much smaller than the other.
     * This helps distinguish between normal file modifications with INSERT/DELETE deltas
     * vs actual file additions/deletions.
     * @return true if one side has < 3 lines and the other has > 50 lines
     */
    private boolean isVeryAsymmetricContent()
    {
        var leftPanel = getFilePanel(PanelSide.LEFT);
        var rightPanel = getFilePanel(PanelSide.RIGHT);

        if (leftPanel == null || rightPanel == null) {
            return false;
        }

        var leftDoc = leftPanel.getBufferDocument();
        var rightDoc = rightPanel.getBufferDocument();

        if (leftDoc == null || rightDoc == null) {
            return false;
        }

        int leftLines = leftDoc.getNumberOfLines();
        int rightLines = rightDoc.getNumberOfLines();

        // Very asymmetric if one side < 3 lines and other side > 50 lines
        // This is much more restrictive to avoid blocking normal file diffs
        boolean isAsymmetric = (leftLines < 3 && rightLines > 50) || (rightLines < 3 && leftLines > 50);

        if (isAsymmetric) {
            logger.debug("Very asymmetric content detected: left={} lines, right={} lines", leftLines, rightLines);
        }

        return isAsymmetric;
    }

    @FunctionalInterface
    private interface DocumentMutation {
        void perform() throws BadLocationException;
    }

    private void applyDelta(AbstractDelta<String> delta, BufferDocumentIF changedDoc, JTextComponent changedEditor, DocumentMutation mutation, String operationType) {
        assert SwingUtilities.isEventDispatchThread();

        // Create snapshots before applying changes
        var patchSnapshot = createPatchSnapshot();
        var selectedDeltaSnapshot = selectedDelta;
        var documentEdits = new java.util.ArrayList<javax.swing.undo.UndoableEdit>();

        // Create a custom undo listener to capture document edits during the mutation
        var editCapture = new javax.swing.event.UndoableEditListener() {
            @Override
            public void undoableEditHappened(javax.swing.event.UndoableEditEvent e) {
                documentEdits.add(e.getEdit());
            }
        };

        try {
            // Temporarily add the edit capture listener
            changedEditor.getDocument().addUndoableEditListener(editCapture);

            if (scrollSynchronizer != null) {
                try (var ignored = scrollSynchronizer.programmaticSection()) {
                    mutation.perform();
                }
            } else {
                mutation.perform();
            }

            // Remove the delta from patch
            if (patch != null) {
                patch.getDeltas().remove(delta);
            }

            synchronizeDocuments(changedEditor, changedDoc);
            changedDoc.getLines(); // rebuild internal cache

            // Create and add the compound undo operation
            if (!documentEdits.isEmpty()) {
                var chunkEdit = new ChunkApplicationEdit(
                    this, delta, documentEdits, patchSnapshot, selectedDeltaSnapshot, operationType);
                getUndoHandler().add(chunkEdit);
            }

            diff(false); // recalc patch & refresh UI without auto-scrolling
            mainPanel.refreshTabTitle(this);

        } catch (Exception ex) {
            throw new RuntimeException("Error applying delta operation", ex);
        } finally {
            // Always remove the temporary listener
            changedEditor.getDocument().removeUndoableEditListener(editCapture);
        }
    }

    /**
     * Creates a deep copy snapshot of the current patch state for undo operations.
     */
    @Nullable
    private com.github.difflib.patch.Patch<String> createPatchSnapshot() {
        if (patch == null) {
            return null;
        }

        // Create a new patch with copies of all deltas
        var snapshot = new com.github.difflib.patch.Patch<String>();
        for (var delta : patch.getDeltas()) {
            snapshot.addDelta(delta);
        }
        return snapshot;
    }

    /**
     * The "change" operation from left->right or right->left.
     * We replicate the old logic, then remove the used delta from the patch
     * so it can't be applied repeatedly.
     */
    public void runChange(int fromPanelIndex, int toPanelIndex, boolean shift)
    {
        assert SwingUtilities.isEventDispatchThread();
        var delta = getSelectedDelta();
        if (delta == null) return;

        var fromFilePanel = getFilePanel(fromPanelIndex);
        var toFilePanel = getFilePanel(toPanelIndex);
        if (fromFilePanel == null || toFilePanel == null) return;

        var destinationViewport = toFilePanel.getScrollPane().getViewport();
        var originalViewPosition = destinationViewport.getViewPosition();

        var fromDoc = fromFilePanel.getBufferDocument();
        var toDoc = toFilePanel.getBufferDocument();
        if (fromDoc == null || toDoc == null) return;

        var sourceChunk = (fromPanelIndex < toPanelIndex) ? delta.getSource() : delta.getTarget();
        var targetChunk = (fromPanelIndex < toPanelIndex) ? delta.getTarget() : delta.getSource();

        var fromLine = sourceChunk.getPosition();
        var size = sourceChunk.size();
        var fromOffset = fromDoc.getOffsetForLine(fromLine);
        if (fromOffset < 0) return;
        var toOffset = fromDoc.getOffsetForLine(fromLine + size);
        if (toOffset < 0) return;

        var toEditor = toFilePanel.getEditor();
        var operationType = shift ? "Apply Change (Insert)" : "Apply Change (Replace)";
        applyDelta(delta, toDoc, toEditor, () -> {
            var fromPlainDoc = fromDoc.getDocument();
            var replacedText = fromPlainDoc.getText(fromOffset, toOffset - fromOffset);

            var toLine = targetChunk.getPosition();
            var toSize = targetChunk.size();
            var toFromOffset = toDoc.getOffsetForLine(toLine);
            if (toFromOffset < 0) return;
            var toToOffset = toDoc.getOffsetForLine(toLine + toSize);
            if (toToOffset < 0) return;

            // Record that this file was modified by a diff operation BEFORE applying changes
            recordDiffChange(toDoc);

            toEditor.setSelectionStart(toFromOffset);
            toEditor.setSelectionEnd(toToOffset);

            if (!shift) {
                toEditor.replaceSelection(replacedText);
            } else {
                toEditor.getDocument().insertString(toToOffset, replacedText, null);
            }
            SwingUtilities.invokeLater(() -> {
                toEditor.setCaretPosition(toFromOffset);
                destinationViewport.setViewPosition(originalViewPosition);
            });
        }, operationType);

        // Create immediate history entry for this chunk operation
        SwingUtilities.invokeLater(() -> createImmediateHistoryEntry(toDoc, operationType));
    }

    /**
     * The "delete" operation: remove the chunk from the fromPanel side.
     * Afterward, remove the delta so it doesn't stay clickable.
     */
    public void runDelete(int fromPanelIndex, int toPanelIndex) {
        assert SwingUtilities.isEventDispatchThread();
        var delta = getSelectedDelta();
        if (delta == null) return;

        var fromFilePanel = getFilePanel(fromPanelIndex);
        if (fromFilePanel == null) return;

        var fromDoc = fromFilePanel.getBufferDocument();
        if (fromDoc == null) return;

        var chunk = (fromPanelIndex < toPanelIndex) ? delta.getSource() : delta.getTarget();

        var fromLine = chunk.getPosition();
        var size = chunk.size();
        var fromOffset = fromDoc.getOffsetForLine(fromLine);
        if (fromOffset < 0) return;
        var toOffset = fromDoc.getOffsetForLine(fromLine + size);
        if (toOffset < 0) return;

        var editor = fromFilePanel.getEditor();
        applyDelta(delta, fromDoc, editor, () -> {
            // Record that this file was modified by a diff operation BEFORE applying changes
            recordDiffChange(fromDoc);

            editor.setSelectionStart(fromOffset);
            editor.setSelectionEnd(toOffset);
            editor.replaceSelection("");
            editor.setCaretPosition(fromOffset);

        }, "Delete Chunk");

        // Create immediate history entry for this chunk operation
        SwingUtilities.invokeLater(() -> createImmediateHistoryEntry(fromDoc, "Delete Chunk"));
    }

    /**
     * Writes out any changed documents to disk. Typically invoked after applying changes or undo/redo.
     */
    public void doSave()
    {
        for (var fp : filePanels.values()) {
            if (!fp.isDocumentChanged()) continue;
            var doc = requireNonNull(fp.getBufferDocument());

            // Check if document is read-only before attempting to save
            if (doc.isReadonly()) {
                continue;
            }

            try {
                doc.write();
            } catch (Exception ex) {
                logger.error("Failed to save file: {} - {}", doc.getName(), ex.getMessage(), ex);
                mainPanel.getConsoleIO().systemNotify(
                                              "Can't save file: " + doc.getName() + "\n" + ex.getMessage(),
                                              "Problem writing file",
                                              JOptionPane.ERROR_MESSAGE);
            }
        }

        // Generate activity entries for files that had diff changes applied
        // Move expensive operations to background thread to avoid blocking UI
        if (!pendingDiffChanges.isEmpty()) {
            // Capture current state for background processing
            var diffChangesCopy = new HashMap<>(pendingDiffChanges);
            var contentBeforeChangesCopy = new HashMap<>(contentBeforeChanges);

            // Clear the tracking maps immediately to prevent memory leaks
            pendingDiffChanges.clear();
            contentBeforeChanges.clear();

            // Process in background thread
            CompletableFuture.supplyAsync(() -> {
                generateDiffChangeActivityEntriesAsync(diffChangesCopy, contentBeforeChangesCopy);
                return null;
            }).exceptionally(ex -> {
                logger.error("Failed to generate diff change activity entries in background", ex);
                return null;
            });
        }

        // After saving, recalculate dirty status (should be false since undo history is cleared)
        recalcDirty();
    }

    /**
     * The "down arrow" in the toolbar calls doDown().
     * We step to next delta if possible, or re-scroll from top.
     */
    @Override
    public void doDown()
    {
        toNextDelta(true);
    }

    /**
     * The "up arrow" in the toolbar calls doUp().
     * We step to previous delta if possible, or re-scroll from bottom.
     */
    @Override
    public void doUp()
    {
        toNextDelta(false);
    }

    @Override
    public void doUndo()
    {
        super.doUndo();
        mainPanel.updateUndoRedoButtons();
        // ChunkApplicationEdit handles its own patch state restoration and diff() calls
        // For regular document edits, we still need diff recalculation, so always call it
        // The ChunkApplicationEdit.undo() will call diff() anyway, so this ensures consistency
        diff(true); // Scroll to selection since this is user-initiated
        recalcDirty();
    }

    @Override
    public void doRedo()
    {
        super.doRedo();
        mainPanel.updateUndoRedoButtons();
        // ChunkApplicationEdit handles its own patch state restoration and diff() calls
        // For regular document edits, we still need diff recalculation, so always call it
        // The ChunkApplicationEdit.redo() will call diff() anyway, so this ensures consistency
        diff(true); // Scroll to selection since this is user-initiated
        recalcDirty();
    }

    @Override
    public void checkActions() {
        // Update undo/redo button states when edits happen
        SwingUtilities.invokeLater(() -> {
            // Re-evaluate dirty state after the document change
            recalcDirty();

            mainPanel.updateUndoRedoButtons();
            mainPanel.refreshTabTitle(BufferDiffPanel.this);
        });
    }
   /**
     * ThemeAware implementation - update highlight colours and syntax themes
     * when the global GUI theme changes.
     */
    @Override
    public void applyTheme(GuiTheme guiTheme)
    {
        assert javax.swing.SwingUtilities.isEventDispatchThread()
                : "applyTheme must be invoked on the EDT";
        this.guiTheme = guiTheme;

        // Refresh RSyntax themes and highlights in each child FilePanel
        // Apply to RIGHT panel first so LEFT panel can inherit syntax style if needed
        var rightPanel = getFilePanel(PanelSide.RIGHT);
        if (rightPanel != null) {
            rightPanel.applyTheme(guiTheme);
        }
        var leftPanel = getFilePanel(PanelSide.LEFT);
        if (leftPanel != null) {
            leftPanel.applyTheme(guiTheme);
        }

        // Let the Look-and-Feel repaint every child component (headers, scroll-bars, etc.)
        SwingUtilities.updateComponentTreeUI(this);
        revalidate();

        // Repaint diff connectors, revision bars, etc.
        reDisplay();
    }

    public GuiTheme getTheme() {
        return guiTheme;
    }

    public boolean isDarkTheme() {
        return guiTheme.isDarkTheme();
    }

    @Override
    public boolean isAtFirstLogicalChange() {
        if (patch == null || patch.getDeltas().isEmpty()) {
            return true;
        }
        if (selectedDelta == null) {
            return false;
        }
        return patch.getDeltas().indexOf(selectedDelta) == 0;
    }

    @Override
    public boolean isAtLastLogicalChange() {
        if (patch == null || patch.getDeltas().isEmpty()) {
            return true;
        }
        if (selectedDelta == null) {
            return false;
        }
        var deltas = patch.getDeltas();
        var currentIndex = deltas.indexOf(selectedDelta);
        return currentIndex != -1 && currentIndex == deltas.size() - 1;
    }

    @Override
    public void goToLastLogicalChange() {
        if (patch != null && !patch.getDeltas().isEmpty()) {
            setSelectedDelta(patch.getDeltas().getLast());
            showSelectedDelta();
        }
    }

    /**
     * Registers keyboard shortcuts for search functionality.
     * Cmd+F (or Ctrl+F) focuses the search field in the active panel.
     * Esc clears search highlights and returns focus to the editor.
     */
    private void registerSearchKeyBindings() {
        // Cmd+F / Ctrl+F focuses the search field using utility method
        KeyboardShortcutUtil.registerSearchFocusShortcut(this, this::focusActiveSearchField);

        // Register Esc key for both search bars to clear highlights
        // Note: We only register Esc, not Cmd+F, to avoid conflicts with our custom handler
        if (leftSearchBar != null) {
            KeyboardShortcutUtil.registerSearchEscapeShortcut(leftSearchBar.getSearchField(), () -> {
                if (leftSearchBar != null) { // Double check for safety within lambda
                    leftSearchBar.clearHighlights();
                }
                var leftPanel = getFilePanel(PanelSide.LEFT);
                if (leftPanel != null) {
                    leftPanel.getEditor().requestFocusInWindow();
                }
            });
        }
        if (rightSearchBar != null) {
            KeyboardShortcutUtil.registerSearchEscapeShortcut(rightSearchBar.getSearchField(), () -> {
                if (rightSearchBar != null) { // Double check for safety within lambda
                    rightSearchBar.clearHighlights();
                }
                var rightPanel = getFilePanel(PanelSide.RIGHT);
                if (rightPanel != null) {
                    rightPanel.getEditor().requestFocusInWindow();
                }
            });
        }
    }

    /**
     * Registers Ctrl+S / Cmd+S keyboard shortcut for manual saving.
     */
    private void registerSaveShortcut() {
        KeyboardShortcutUtil.registerSaveShortcut(this, this::doSave);
    }

    /**
     * Focuses the search field corresponding to the currently active file panel.
     * Uses real-time focus detection to determine which search bar to focus.
     */
    private void focusActiveSearchField() {
        // Real-time focus detection: check which editor currently has focus
        var focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        // Check if the right editor has focus
        var rightPanel = getFilePanel(PanelSide.RIGHT);
        if (rightPanel != null && focusOwner == rightPanel.getEditor()) {
            if (rightSearchBar != null) {
                rightSearchBar.focusSearchField();
                return;
            }
        }

        // Check if the left editor has focus
        var leftPanel = getFilePanel(PanelSide.LEFT);
        if (leftPanel != null && focusOwner == leftPanel.getEditor()) {
            if (leftSearchBar != null) {
                leftSearchBar.focusSearchField();
                return;
            }
        }
        // Default to left search bar if we can't determine focus or no editor has focus
        if (leftSearchBar != null) {
            leftSearchBar.focusSearchField();
        }
    }

    /**
     * Cleanup method to properly dispose of resources when the panel is no longer needed.
     * Should be called when the BufferDiffPanel is being disposed to prevent memory leaks.
     */

    @Override
    public void dispose() {
        // Dispose of file panels to clean up their timers and listeners
        for (var fp : filePanels.values()) {
            fp.dispose();
        }
        filePanels.clear();

        // Dispose of scroll synchronizer
        if (scrollSynchronizer != null) {
            scrollSynchronizer.dispose();
            scrollSynchronizer = null;
        }

        // Clear diff change tracking
        clearDiffChangeTracking();

        // Clear references
        diffNode = null;
        patch = null;
        selectedDelta = null;
    }

    @Override
    public boolean hasUnsavedChanges() {
        // Check if any file panel has unsaved changes
        for (var fp : filePanels.values()) {
            if (fp.isDocumentChanged()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if at least one side is editable (not read-only).
     * Used by the main toolbar to decide whether undo/redo buttons should be shown.
     */
    public boolean atLeastOneSideEditable() {
        return filePanels.values().stream()
                         .anyMatch(fp -> {
                             var doc = fp.getBufferDocument();
                             return doc != null && !doc.isReadonly();
                         });
    }

    /**
     * Clear caches to free memory while keeping the panel functional.
     * Used by sliding window memory management.
     */
    public void clearCaches() {
        // Clear undo history
        var undoManager = getUndoHandler();
        undoManager.discardAllEdits();

        // Clear file panel caches
        for (var fp : filePanels.values()) {
            fp.clearViewportCache();
            fp.clearSearchCache();
        }
    }

    /**
     * Records that a diff operation was applied to a file.
     * Used for generating activity entries when files are saved.
     * @param doc the document that was modified
     */
    private void recordDiffChange(BufferDocumentIF doc) {
        var filename = doc.getShortName();

        // Save the original content before the first change to this file
        // Using putIfAbsent for thread-safe atomic operation
        if (contentBeforeChanges.get(filename) == null) {
            try {
                var document = doc.getDocument();
                var content = document.getText(0, document.getLength());
                contentBeforeChanges.putIfAbsent(filename, content);
            } catch (BadLocationException e) {
                logger.warn("Failed to capture original content for diff change tracking: {}", filename, e);
            }
        }

        // Thread-safe atomic increment
        pendingDiffChanges.merge(filename, 1, Integer::sum);
    }


    /**
     * Asynchronous version of generateDiffChangeActivityEntries that can run on background threads.
     * This method is safe to call from any thread as it uses copies of the tracking data.
     *
     * @param diffChanges Copy of pending diff changes
     * @param contentBefore Copy of original content before changes
     */
    private void generateDiffChangeActivityEntriesAsync(Map<String, Integer> diffChanges, Map<String, String> contentBefore) {
        if (diffChanges.isEmpty()) {
            return;
        }

        var contextManager = mainPanel.getContextManager();

        for (var entry : diffChanges.entrySet()) {
            var filename = entry.getKey();
            var changeCount = entry.getValue();

            try {
                // Get the current content after changes
                String currentContent = null;
                ProjectFile projectFile = null;

                // Find the document with this filename
                for (var fp : filePanels.values()) {
                    var doc = fp.getBufferDocument();
                    if (doc != null && filename.equals(doc.getShortName())) {
                        try {
                            var document = doc.getDocument();
                            currentContent = document.getText(0, document.getLength());

                            // Try to create ProjectFile for this document
                            // Handle both FileDocument and StringDocument (used in diff panels)
                            if (doc instanceof io.github.jbellis.brokk.difftool.doc.FileDocument) {
                                // For FileDocument, use the file path directly
                                var project = contextManager.getProject();
                                var fullPath = java.nio.file.Paths.get(doc.getName());

                                if (fullPath.toFile().exists()) {
                                    try {
                                        // Convert absolute path to relative path within project
                                        var projectRoot = project.getRoot();
                                        var relativePath = projectRoot.relativize(fullPath);
                                        projectFile = new ProjectFile(projectRoot, relativePath);
                                    } catch (Exception e) {
                                        logger.warn("Failed to create ProjectFile for {}: {}", fullPath, e.getMessage());
                                    }
                                }
                            } else {
                                // For StringDocument or other types, try to create ProjectFile from filename
                                // This handles diff panels where documents might be StringDocument instances
                                var project = contextManager.getProject();
                                var projectRoot = project.getRoot();

                                // Try the filename as-is first
                                var filePath = projectRoot.resolve(filename);

                                if (filePath.toFile().exists()) {
                                    try {
                                        projectFile = new ProjectFile(projectRoot, java.nio.file.Paths.get(filename));
                                    } catch (Exception e) {
                                        logger.warn("Failed to create ProjectFile from filename {}: {}", filename, e.getMessage());
                                    }
                                } else {
                                    // Try using doc.getName() if it's a path
                                    try {
                                        var docNamePath = java.nio.file.Paths.get(doc.getName());
                                        if (docNamePath.toFile().exists()) {
                                            var relativePath = projectRoot.relativize(docNamePath);
                                            projectFile = new ProjectFile(projectRoot, relativePath);
                                        } else {
                                            logger.warn("Could not find file for StringDocument: filename='{}', doc.getName()='{}'",
                                                filename, doc.getName());
                                        }
                                    } catch (Exception e) {
                                        logger.warn("Failed to create ProjectFile from doc.getName() {}: {}", doc.getName(), e.getMessage());
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to get current content for diff change history: {}", filename, e);
                        }
                        break;
                    }
                }

                // Get the original content before changes
                var originalContent = contentBefore.get(filename);
                if (originalContent != null && currentContent != null) {
                    // Generate unified diff showing the changes applied
                    var originalLines = originalContent.lines().collect(Collectors.toList());
                    var currentLines = currentContent.lines().collect(Collectors.toList());
                    var patch = DiffUtils.diff(originalLines, currentLines);
                    var unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(filename,
                                                                            filename,
                                                                            originalLines,
                                                                            patch,
                                                                            3);

                    // Create TaskResult with the diff information
                    String actionDescription;
                    if (changeCount == 1) {
                        actionDescription = "Applied diff change to " + filename;
                    } else {
                        actionDescription = "Applied " + changeCount + " diff changes to " + filename;
                    }

                    // Create message list with the diff
                    var messagesForHistory = new ArrayList<ChatMessage>();
                    messagesForHistory.add(Messages.customSystem("# Diff changes applied\\n\\n```diff\\n" + unifiedDiff + "\\n```"));

                    // Determine which files were affected
                    Set<ProjectFile> affectedFiles = new HashSet<>();
                    if (projectFile != null) {
                        affectedFiles.add(projectFile);
                    } else {
                        logger.warn("projectFile is null, affectedFiles will be empty for file: {}", filename);
                    }

                    var diffResult = new TaskResult(mainPanel.getContextManager(),
                                                    actionDescription,
                                                    messagesForHistory,
                                                    affectedFiles,
                                                    TaskResult.StopReason.SUCCESS);

                    // Add to context history as undoable entry
                    // PROPER APPROACH: Temporarily write original content to file during addToHistory, then restore modified content
                    if (projectFile != null) {
                        try {
                            // Save current modified content
                            var currentModifiedContent = projectFile.read();

                            // Temporarily write original content to file (for context freeze to capture)
                            projectFile.write(originalContent);

                            // Add to history - the context freeze will capture the original content from the file
                            mainPanel.getContextManager().addToHistory(diffResult, false);

                            // Restore the modified content so user sees their changes
                            projectFile.write(currentModifiedContent);
                        } catch (Exception e) {
                            logger.error("Failed to create proper history entry for {}: {}", filename, e.getMessage());
                            // Fallback to regular addToHistory without file manipulation
                            mainPanel.getContextManager().addToHistory(diffResult, false);
                        }
                    } else {
                        logger.warn("projectFile is null, using regular addToHistory");
                        mainPanel.getContextManager().addToHistory(diffResult, false);
                    }
                } else {
                    // Fallback to simple logging if we can't generate diff

                    String message;
                    if (changeCount == 1) {
                        message = "Applied diff change to " + filename;
                    } else {
                        message = "Applied " + changeCount + " diff changes to " + filename;
                    }
                    mainPanel.getConsoleIO().systemOutput(message);
                }
            } catch (Exception e) {
                logger.error("Failed to generate diff change activity entry for {}", filename, e);
                // Fallback to simple logging
                String message;
                if (changeCount == 1) {
                    message = "Applied diff change to " + filename;
                } else {
                    message = "Applied " + changeCount + " diff changes to " + filename;
                }
                mainPanel.getConsoleIO().systemOutput(message);
            }
        }
    }

    /**
     * Clears all diff change tracking state.
     * Should be called when loading a new diff or disposing the panel.
     */
    public void clearDiffChangeTracking() {
        pendingDiffChanges.clear();
        contentBeforeChanges.clear();
    }

    /**
     * Creates an immediate history entry for a chunk operation instead of waiting for save.
     * This ensures each chunk operation can be individually undone from the activity history.
     */
    private void createImmediateHistoryEntry(BufferDocumentIF doc, String operationType) {
        try {
            var filename = doc.getShortName();
            var originalContent = contentBeforeChanges.get(filename);
            if (originalContent == null) {
                logger.warn("No diff change recorded for immediate history entry: {}", filename);
                return;
            }

            var document = doc.getDocument();
            var currentContent = document.getText(0, document.getLength());

            // Only create history entry if content actually changed
            if (originalContent.equals(currentContent)) {
                return;
            }

            // Create ProjectFile for history entry
            ProjectFile projectFile = null;
            try {
                if (doc instanceof FileDocument fileDoc) {
                    var fullPath = Paths.get(fileDoc.getName());
                    var projectRoot = mainPanel.getContextManager().getProject().getRoot();
                    var relativePath = projectRoot.relativize(fullPath);
                    projectFile = new ProjectFile(projectRoot, relativePath);
                } else if (doc instanceof StringDocument) {
                    var path = Paths.get(filename);
                    if (path.isAbsolute()) {
                        var projectRoot = mainPanel.getContextManager().getProject().getRoot();
                        var relativePath = projectRoot.relativize(path);
                        projectFile = new ProjectFile(projectRoot, relativePath);
                    } else {
                        projectFile = new ProjectFile(mainPanel.getContextManager().getProject().getRoot(), path);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to create ProjectFile for immediate history: {}", e.getMessage());
                return;
            }

            if (projectFile == null) {
                logger.warn("Could not create ProjectFile for immediate history entry: {}", filename);
                return;
            }

            // Create TaskResult for this operation
            var messagesForHistory = List.<ChatMessage>of(Messages.customSystem("Diff operation: " + operationType));
            var affectedFiles = Set.of(projectFile);
            var actionDescription = operationType + " applied to " + filename;

            var diffResult = new TaskResult(mainPanel.getContextManager(),
                                          actionDescription,
                                          messagesForHistory,
                                          affectedFiles,
                                          TaskResult.StopReason.SUCCESS);

            // SAFE APPROACH: Use virtual fragment instead of file manipulation
            // Create a virtual fragment containing the original content for undo purposes
            var virtualFragment = new io.github.jbellis.brokk.context.ContextFragment.StringFragment(
                                       mainPanel.getContextManager(),
                                       originalContent,
                                       "Immediate diff change backup for " + filename,
                                       "java");

            // Add the virtual fragment to the context for history capture
            mainPanel.getContextManager().pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragment(virtualFragment));

            mainPanel.getContextManager().addToHistory(diffResult, false);

            // Remove this operation from pending changes and clear original content to prevent memory leak
            pendingDiffChanges.remove(filename);
            contentBeforeChanges.remove(filename);

        } catch (Exception e) {
            logger.error("Failed to create immediate history entry for {}: {}", doc.getShortName(), e.getMessage(), e);
        }
    }
}
