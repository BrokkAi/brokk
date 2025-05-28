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
import io.github.jbellis.brokk.difftool.search.SearchBarDialog;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.util.ArrayList;

/**
 * This panel shows the side-by-side file panels, the diff curves, plus search bars.
 * It no longer depends on custom JMRevision/JMDelta but rather on a Patch<String>.
 */
public class BufferDiffPanel extends AbstractContentPanel
{
    public static final int LEFT = 0;
    public static final int RIGHT = 2;
    public static final int NUMBER_OF_PANELS = 3;

    private final BrokkDiffPanel mainPanel;
    private final boolean isDarkTheme;

    // Instead of JMRevision:
    private Patch<String> patch; // from JMDiffNode
    private AbstractDelta<String> selectedDelta;

    private int selectedLine;
    private SearchBarDialog leftBar;
    private SearchBarDialog rightBar;
    private JCheckBox caseSensitiveCheckBox;

    // The left & right "file panels"
    private FilePanel[] filePanels;
    private int filePanelSelectedIndex = -1;

    private JMDiffNode diffNode; // Where we get the Patch<String>
    private ScrollSynchronizer scrollSynchronizer;
    private JSplitPane splitPane;

    public BufferDiffPanel(BrokkDiffPanel mainPanel)
    {
        this(mainPanel, false);
    }

    public BufferDiffPanel(BrokkDiffPanel mainPanel, boolean isDarkTheme)
    {
        this.mainPanel = mainPanel;
        this.isDarkTheme = isDarkTheme;
        // Let the mainPanel keep a reference to us for toolbar/undo/redo interplay
        mainPanel.setBufferDiffPanel(this);
        init();
        setFocusable(true);
    }

    public void setDiffNode(JMDiffNode diffNode)
    {
        this.diffNode = diffNode;
        refreshDiffNode();
    }

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

        BufferDocumentIF leftDocument = (bnLeft != null ? bnLeft.getDocument() : null);
        BufferDocumentIF rightDocument = (bnRight != null ? bnRight.getDocument() : null);

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

        reDisplay();
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
        var titles = new ArrayList<String>();
        for (var fp : filePanels) {
            if (fp == null) continue;
            var bd = fp.getBufferDocument();
            if (bd != null) {
                titles.add(bd.getShortName());
            }
        }
        if (titles.isEmpty()) {
            return "No files";
        }
        if (titles.size() == 1) {
            return titles.get(0);
        }
        if (titles.get(0).equals(titles.get(1))) {
            return titles.get(0);
        }
        return titles.get(0) + "-" + titles.get(1);
    }

    public boolean isDarkTheme()
    {
        return isDarkTheme;
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
     * The top-level UI for the left & right file panels plus the “diff scroll component”.
     */
    private void init()
    {
        var columns = "3px, pref, 3px, 0:grow, 5px, min, 60px, 0:grow, 25px, min, 3px, pref, 3px";
        var rows = "6px, pref, 3px, fill:0:grow, pref";

        setLayout(new BorderLayout());

        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, true, activateBarDialog(), buildFilePanel(columns, rows));
        add(splitPane);

        // Create the scroll synchronizer for the left & right panels
        scrollSynchronizer = new ScrollSynchronizer(this, filePanels[LEFT], filePanels[RIGHT]);
        setSelectedPanel(filePanels[LEFT]);
        mainPanel.updateUndoRedoButtons();
    }

    public JCheckBox getCaseSensitiveCheckBox()
    {
        return caseSensitiveCheckBox;
    }

    /**
     * Build the top row that holds search bars plus a "Case Sensitive" checkbox.
     */
    public JPanel activateBarDialog()
    {
        var barContainer = new JPanel(new BorderLayout());

        caseSensitiveCheckBox = new JCheckBox("Case Sensitive");
        caseSensitiveCheckBox.setFocusable(false);

        leftBar = new SearchBarDialog(mainPanel, this);
        rightBar = new SearchBarDialog(mainPanel, this);

        var leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        leftPanel.add(Box.createHorizontalStrut(5));
        leftPanel.add(leftBar);

        var topLinePanelLeft = new JPanel(new FlowLayout(FlowLayout.LEADING));
        topLinePanelLeft.add(caseSensitiveCheckBox);

        var topLinePanelDown = new JPanel(new FlowLayout(FlowLayout.CENTER));
        topLinePanelDown.add(new JLabel(""));

        var leftMostPane = new JPanel();
        leftMostPane.setLayout(new BoxLayout(leftMostPane, BoxLayout.Y_AXIS));
        leftMostPane.add(topLinePanelLeft);
        leftMostPane.add(topLinePanelDown);
        leftMostPane.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        var rightPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        rightPanel.add(rightBar);
        rightPanel.add(Box.createHorizontalStrut(5));

        barContainer.add(leftMostPane, BorderLayout.WEST);
        barContainer.add(leftPanel, BorderLayout.CENTER);
        barContainer.add(rightPanel, BorderLayout.EAST);

        // Re-run search whenever user toggles "Case Sensitive"
        caseSensitiveCheckBox.addActionListener(e -> {
            if (filePanels[LEFT] != null) filePanels[LEFT].doSearch();
            if (filePanels[RIGHT] != null) filePanels[RIGHT].doSearch();
        });

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
        filePanels[LEFT] = new FilePanel(this, BufferDocumentIF.ORIGINAL, leftBar);
        filePanels[RIGHT] = new FilePanel(this, BufferDocumentIF.REVISED, rightBar);

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

    public BrokkDiffPanel getMainPanel()
    {
        return mainPanel;
    }

    /**
     * We simply retrieve the patch from the node if needed.
     */
    public Patch<String> getPatch()
    {
        return patch;
    }

    /**
     * Return whichever delta is considered “selected” in the UI.
     */
    public AbstractDelta<String> getSelectedDelta()
    {
        return selectedDelta;
    }

    /**
     * Called by `DiffScrollComponent` or `RevisionBar` to set which delta has been clicked.
     */
    public void setSelectedDelta(AbstractDelta<String> newDelta)
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

    public FilePanel getFilePanel(int index)
    {
        if (filePanels == null) return null;
        if (index < 0 || index >= filePanels.length) return null;
        return filePanels[index];
    }

    void setSelectedPanel(FilePanel fp)
    {
        var oldIndex = filePanelSelectedIndex;
        var newIndex = -1;
        for (int i = 0; i < filePanels.length; i++) {
            if (filePanels[i] == fp) {
                newIndex = i;
                break;
            }
        }
        if (newIndex != oldIndex) {
            if (oldIndex != -1 && filePanels[oldIndex] != null) {
                filePanels[oldIndex].setSelected(false);
            }
            filePanelSelectedIndex = newIndex;
            if (newIndex != -1) {
                filePanels[newIndex].setSelected(true);
            }
        }
    }

    /**
     * Called by the top-level toolbar “Next” or “Previous” or by mouse wheel in DiffScrollComponent.
     */
    public void toNextDelta(boolean next)
    {
        if (patch == null || patch.getDeltas().isEmpty()) {
            return;
        }
        var deltas = patch.getDeltas();
        if (selectedDelta == null) {
            // If nothing selected, pick first or last
            setSelectedDelta(next ? deltas.get(0) : deltas.get(deltas.size() - 1));
            showSelectedDelta();
            return;
        }
        var idx = deltas.indexOf(selectedDelta);
        if (idx < 0) {
            // The current selection is not in the patch list, pick first
            setSelectedDelta(deltas.get(0));
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
     * The “change” operation from left->right or right->left.
     * We replicate the old logic, then remove the used delta from the patch
     * so it can’t be applied repeatedly.
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
     * The “delete” operation: remove the chunk from the fromPanel side.
     * Afterward, remove the delta so it doesn’t stay clickable.
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
     * The “down arrow” in the toolbar calls doDown().
     * We step to next delta if possible, or re-scroll from top.
     */
    @Override
    public void doDown()
    {
        toNextDelta(true);
    }

    /**
     * The “up arrow” in the toolbar calls doUp().
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
}
