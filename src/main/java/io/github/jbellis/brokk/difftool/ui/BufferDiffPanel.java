package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.doc.JMDocumentEvent;
import io.github.jbellis.brokk.difftool.node.BufferNode;
import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.scroll.DiffScrollComponent;
import io.github.jbellis.brokk.difftool.scroll.ScrollSynchronizer;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.search.GenericSearchBar;
import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import static java.util.Objects.requireNonNull;


/**
 * This panel shows the side-by-side file panels, the diff curves, plus search bars.
 * It no longer depends on custom JMRevision/JMDelta but rather on a Patch<String>.
 */
public class BufferDiffPanel extends AbstractContentPanel implements ThemeAware
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

    private int selectedLine;
    @Nullable
    private GenericSearchBar leftSearchBar;
    @Nullable
    private GenericSearchBar rightSearchBar;

    // The left & right "file panels" using type-safe enum map
    private final EnumMap<PanelSide, FilePanel> filePanels = new EnumMap<>(PanelSide.class);
    private PanelSide selectedPanelSide = PanelSide.LEFT;

    @Nullable
    private JMDiffNode diffNode; // Where we get the Patch<String>
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

        // After calling diff() on JMDiffNode, we get patch from diffNode.getPatch():
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
     * Refresh highlights for only the specified panel to reduce flickering.
     */
    public void reDisplayPanel(PanelSide side)
    {
        var fp = filePanels.get(side);
        if (fp != null) {
            fp.reDisplay();
        }
    }

    public String getTitle()
    {
        if (diffNode != null && !diffNode.getName().isBlank()) {
            return diffNode.getName();
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
        if (titles.size() == 1) {
            return titles.getFirst();
        }
        if (titles.get(0).equals(titles.get(1))) {
            return titles.getFirst();
        }
        return String.join(" vs ", titles);
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

    public ScrollSynchronizer getScrollSynchronizer()
    {
        return scrollSynchronizer;
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
    @NotNull
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
    @Nullable
    public FilePanel getSelectedFilePanel()
    {
        return filePanels.get(selectedPanelSide);
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
        scrollSynchronizer.showDelta(selectedDelta);
    }

    /**
     * The "change" operation from left->right or right->left.
     * We replicate the old logic, then remove the used delta from the patch
     * so it can't be applied repeatedly.
     */
    public void runChange(int fromPanelIndex, int toPanelIndex, boolean shift)
    {
        var delta = getSelectedDelta();
        if (delta == null) return;

        var fromFilePanel = getFilePanel(fromPanelIndex);
        var toFilePanel = getFilePanel(toPanelIndex);
        if (fromFilePanel == null || toFilePanel == null) return;

        var fromDoc = fromFilePanel.getBufferDocument();
        var toDoc = toFilePanel.getBufferDocument();
        if (fromDoc == null || toDoc == null) return;

        // Decide which side is "source" vs "target" chunk
        var sourceChunk = (fromPanelIndex < toPanelIndex) ? delta.getSource() : delta.getTarget();
        var targetChunk = (fromPanelIndex < toPanelIndex) ? delta.getTarget() : delta.getSource();

        var fromLine = sourceChunk.getPosition();
        var size = sourceChunk.size();
        var fromOffset = fromDoc.getOffsetForLine(fromLine);
        if (fromOffset < 0) return;
        var toOffset = fromDoc.getOffsetForLine(fromLine + size);
        if (toOffset < 0) return;

        try {
            var fromPlainDoc = fromDoc.getDocument();
            var replacedText = fromPlainDoc.getText(fromOffset, toOffset - fromOffset);

            var toLine = targetChunk.getPosition();
            var toSize = targetChunk.size();
            var toFromOffset = toDoc.getOffsetForLine(toLine);
            if (toFromOffset < 0) return;
            var toToOffset = toDoc.getOffsetForLine(toLine + toSize);
            if (toToOffset < 0) return;

            var toEditor = toFilePanel.getEditor();
            toEditor.setSelectionStart(toFromOffset);
            toEditor.setSelectionEnd(toToOffset);

            // SHIFT -> Insert after the existing chunk
            if (!shift) {
                toEditor.replaceSelection(replacedText);
            } else {
                // Insert at the end, effectively appending
                toEditor.getDocument().insertString(toToOffset, replacedText, null);
            }

            // Remove this delta so we can't click it again
            if (patch != null) {
                patch.getDeltas().remove(delta);
            }

            setSelectedDelta(null);
            setSelectedLine(sourceChunk.getPosition());

            // Re-display so the chunk disappears immediately
            reDisplay();
            doSave();
        } catch (BadLocationException ex) {
            throw new RuntimeException("Error applying change operation", ex);
        }
    }

    /**
     * The "delete" operation: remove the chunk from the fromPanel side.
     * Afterward, remove the delta so it doesn't stay clickable.
     */
    public void runDelete(int fromPanelIndex, int toPanelIndex) {
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

        var toEditor = fromFilePanel.getEditor();
        toEditor.setSelectionStart(fromOffset);
        toEditor.setSelectionEnd(toOffset);
        toEditor.replaceSelection("");

        // Remove the just-used delta
        if (patch != null) {
            patch.getDeltas().remove(delta);
        }

        setSelectedDelta(null);
        setSelectedLine(chunk.getPosition());

        // Refresh so the UI doesn't show that chunk anymore
        reDisplay();
        doSave();
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
                logger.debug("Skipping save for read-only document: {}", doc.getName());
                continue;
            }

            try {
                doc.write();
                logger.debug("Successfully saved file: {}", doc.getName());
            } catch (Exception ex) {
                logger.error("Failed to save file: {} - {}", doc.getName(), ex.getMessage(), ex);
                JOptionPane.showMessageDialog(mainPanel,
                                              "Can't save file: " + doc.getName() + "\n" + ex.getMessage(),
                                              "Problem writing file", JOptionPane.ERROR_MESSAGE);
            }
        }
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
    }

    @Override
    public void doRedo()
    {
        super.doRedo();
        mainPanel.updateUndoRedoButtons();
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
        for (FilePanel fp : filePanels.values()) {
            // Note: fp.applyTheme() already calls reDisplay()
            fp.applyTheme(guiTheme);
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
    public void dispose() {
        // Dispose of file panels to clean up their timers and listeners
        for (var fp : filePanels.values()) {
            if (fp != null) {
                fp.dispose();
            }
        }
        filePanels.clear();

        // Clear references
        diffNode = null;
        patch = null;
        selectedDelta = null;
    }
}
