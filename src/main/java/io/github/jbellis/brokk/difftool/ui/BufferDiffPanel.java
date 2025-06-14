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
import io.github.jbellis.brokk.gui.search.GenericSearchBar;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.KeyboardFocusManager;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Arrays;

import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;

/**
 * This panel shows the side-by-side file panels, the diff curves, plus search bars.
 * It no longer depends on custom JMRevision/JMDelta but rather on a Patch<String>.
 */
public class BufferDiffPanel extends AbstractContentPanel implements ThemeAware
{
    public static final int LEFT = 0;
    public static final int RIGHT = 2;
    public static final int NUMBER_OF_PANELS = 3;

    @NotNull
    private final BrokkDiffPanel mainPanel;
    @NotNull
    private GuiTheme guiTheme;

    // Instead of JMRevision:
    @Nullable
    private Patch<String> patch; // from JMDiffNode
    @Nullable
    private AbstractDelta<String> selectedDelta;

    private int selectedLine;
    private io.github.jbellis.brokk.gui.search.GenericSearchBar leftSearchBar;
    private io.github.jbellis.brokk.gui.search.GenericSearchBar rightSearchBar;

    // The left & right "file panels"
    private FilePanel[] filePanels;
    private int filePanelSelectedIndex = -1;

    @Nullable
    private JMDiffNode diffNode; // Where we get the Patch<String>
    private ScrollSynchronizer scrollSynchronizer;
    private JSplitPane splitPane;

    public BufferDiffPanel(BrokkDiffPanel mainPanel, @NotNull GuiTheme theme)
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

        // Set the documents into our file panels:
        // Order is important, The left panel will use the left panel syntax type if it can't figure its own
        if (filePanels[RIGHT] != null && rightDocument != null) {
            filePanels[RIGHT].setBufferDocument(rightDocument);
        }
        if (filePanels[LEFT] != null && leftDocument != null) {
            filePanels[LEFT].setBufferDocument(leftDocument);
        }

        // Don't apply theme here - let it happen after the panel is added to the UI
    }

    /**
     * Rerun the diff from scratch, if needed. For Phase 2 we re-run if a doc changed
     * (the old incremental logic is removed).
     */
    public void diff()
    {
        // Typically, we'd just re-call diffNode.diff() then re-pull patch.
        if (diffNode != null) {
            diffNode.diff();
            this.patch = diffNode.getPatch();
            reDisplay();
        }
    }

    /**
     * Tells each FilePanel to re-apply highlights, then repaint the parent panel.
     */
    private void reDisplay()
    {
        if (filePanels != null) {
            for (var fp : filePanels) {
                if (fp != null) {
                    fp.reDisplay();
                }
            }
        }
        mainPanel.repaint();
    }

    public String getTitle()
    {
        if (diffNode != null && !diffNode.getName().isBlank()) {
            return diffNode.getName();
        }

        // Fallback if diffNode or its name is not available
        var titles = new ArrayList<String>();
        for (var fp : filePanels) {
            if (fp == null) continue;
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
        var searchBarComponent = activateBarDialog();
        
        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, true, searchBarComponent, filePanelComponent);
        add(splitPane);

        // Create the scroll synchronizer for the left & right panels
        scrollSynchronizer = new ScrollSynchronizer(this, filePanels[LEFT], filePanels[RIGHT]);
        setSelectedPanel(filePanels[LEFT]);
        mainPanel.updateUndoRedoButtons();
        // Apply initial theme for syntax highlighting (but not diff highlights yet)
        applyTheme(guiTheme);
        
        // Register keyboard shortcuts for search functionality
        registerSearchKeyBindings();
    }


    /**
     * Build the top row that holds search bars.
     */
    public JPanel activateBarDialog()
    {
        // Use the same FormLayout structure as the file panels to align search bars with text areas
        var columns = "3px, pref, 3px, left:pref, 5px, min, 60px, left:pref, fill:0:grow";
        var rows = "6px, pref";
        
        var layout = new com.jgoodies.forms.layout.FormLayout(columns, rows);
        var cc = new com.jgoodies.forms.layout.CellConstraints();
        var barContainer = new JPanel(layout);

        // Create GenericSearchBar instances using the FilePanel's SearchableComponent adapters
        if (filePanels != null && filePanels[LEFT] != null && filePanels[RIGHT] != null) {
            leftSearchBar = new GenericSearchBar(filePanels[LEFT].createSearchableComponent());
            rightSearchBar = new GenericSearchBar(filePanels[RIGHT].createSearchableComponent());
        }

        // Add search bars aligned with the text areas below
        if (leftSearchBar != null) {
            barContainer.add(leftSearchBar, cc.xy(4, 2)); // Column 4 aligns with left text area, row 2 for spacing
        }
        
        if (rightSearchBar != null) {
            barContainer.add(rightSearchBar, cc.xy(8, 2)); // Column 8 aligns with right text area, row 2 for spacing
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

        filePanels = new FilePanel[NUMBER_OF_PANELS];
        filePanels[LEFT] = new FilePanel(this, BufferDocumentIF.ORIGINAL);
        filePanels[RIGHT] = new FilePanel(this, BufferDocumentIF.REVISED);

        // Left side revision bar
        panel.add(new RevisionBar(this, filePanels[LEFT], true), cc.xy(2, 4));
        panel.add(new JLabel(""), cc.xy(2, 2)); // for spacing

        panel.add(filePanels[LEFT].getVisualComponent(), cc.xyw(4, 4, 3));

        // The middle area for drawing the linking curves
        var diffScrollComponent = new DiffScrollComponent(this, LEFT, RIGHT);
        panel.add(diffScrollComponent, cc.xy(7, 4));

        // Right side revision bar
        panel.add(new RevisionBar(this, filePanels[RIGHT], false), cc.xy(12, 4));
        panel.add(filePanels[RIGHT].getVisualComponent(), cc.xyw(8, 4, 3));

        panel.setMinimumSize(new Dimension(300, 200));
        return panel;
    }

    public ScrollSynchronizer getScrollSynchronizer()
    {
        return scrollSynchronizer;
    }

    public @NotNull BrokkDiffPanel getMainPanel()
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

    @Nullable
    public FilePanel getFilePanel(int index)
    {
        if (filePanels == null) return null;
        if (index < 0 || index >= filePanels.length) return null;
        return filePanels[index];
    }

    void setSelectedPanel(FilePanel fp)
    {
        var oldIndex = filePanelSelectedIndex;
        int newIndex = -1;
        
        // Directly check which panel is being selected
        if (fp == filePanels[LEFT]) {
            newIndex = LEFT;
        } else if (fp == filePanels[RIGHT]) {
            newIndex = RIGHT;
        }
        
        if (newIndex != oldIndex) {
            filePanelSelectedIndex = newIndex;
        }
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

        var fromFilePanel = filePanels[fromPanelIndex];
        var toFilePanel = filePanels[toPanelIndex];
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
            patch.getDeltas().remove(delta);

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

        var fromFilePanel = filePanels[fromPanelIndex];
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
        patch.getDeltas().remove(delta);

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
        for (var fp : filePanels) {
            if (fp == null) continue;
            if (!fp.isDocumentChanged()) continue;
            var doc = fp.getBufferDocument();
            try {
                doc.write();
            } catch (Exception ex) {
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
        if (filePanels != null) {
            for (FilePanel fp : filePanels) {
                if (fp == null) continue;
                // Note: fp.applyTheme() already calls reDisplay()
                fp.applyTheme(guiTheme);
            }
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
                leftSearchBar.clearHighlights();
                if (filePanels[LEFT] != null) {
                    filePanels[LEFT].getEditor().requestFocusInWindow();
                }
            });
        }
        if (rightSearchBar != null) {
            KeyboardShortcutUtil.registerSearchEscapeShortcut(rightSearchBar.getSearchField(), () -> {
                rightSearchBar.clearHighlights();
                if (filePanels[RIGHT] != null) {
                    filePanels[RIGHT].getEditor().requestFocusInWindow();
                }
            });
        }
    }

    /**
     * Focuses the search field corresponding to the currently active file panel.
     * Uses real-time focus detection to determine which search bar to focus.
     */
    private void focusActiveSearchField() {
        // Real-time focus detection: check which editor currently has focus
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        
        // Check if the right editor has focus
        if (filePanels[RIGHT] != null && focusOwner == filePanels[RIGHT].getEditor()) {
            if (rightSearchBar != null) {
                rightSearchBar.focusSearchField();
                return;
            }
        }
        
        // Check if the left editor has focus  
        if (filePanels[LEFT] != null && focusOwner == filePanels[LEFT].getEditor()) {
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
}
