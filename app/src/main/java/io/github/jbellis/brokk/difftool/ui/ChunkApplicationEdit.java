package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.swing.undo.AbstractUndoableEdit;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoableEdit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Custom undoable edit that handles both document text changes and patch state changes when applying chunks via
 * runChange or runDelete operations.
 *
 * <p>This ensures that undo/redo operations maintain consistency between document content and the diff patch state.
 */
public class ChunkApplicationEdit extends AbstractUndoableEdit {
    private static final Logger logger = LogManager.getLogger(ChunkApplicationEdit.class);

    private final BufferDiffPanel diffPanel;
    private final AbstractDelta<String> appliedDelta;
    private final List<UndoableEdit> documentEdits;

    @Nullable
    private final Integer originalDeltaIndex;

    @Nullable
    private final AbstractDelta<String> selectedDeltaSnapshot;

    private final String operationType;

    /** Creates a new chunk application edit that can be undone/redone. */
    public ChunkApplicationEdit(
            BufferDiffPanel diffPanel,
            AbstractDelta<String> appliedDelta,
            List<UndoableEdit> documentEdits,
            @Nullable Integer originalDeltaIndex,
            @Nullable AbstractDelta<String> selectedDeltaSnapshot,
            String operationType) {
        this.diffPanel = diffPanel;
        this.appliedDelta = appliedDelta;
        this.documentEdits = new ArrayList<>(documentEdits);
        this.originalDeltaIndex = originalDeltaIndex;
        this.selectedDeltaSnapshot = selectedDeltaSnapshot;
        this.operationType = operationType;
    }

    @Override
    public void undo() throws CannotUndoException {
        super.undo();
        logger.debug("Undoing chunk application: {}", operationType);

        try {
            // First, undo all document changes in reverse order
            for (int i = documentEdits.size() - 1; i >= 0; i--) {
                var edit = documentEdits.get(i);
                if (edit.canUndo()) {
                    edit.undo();
                }
            }

            // Restore the patch state by re-adding the delta that was removed
            var currentPatch = diffPanel.getPatch();
            if (currentPatch != null && !currentPatch.getDeltas().contains(appliedDelta)) {
                // Insert the delta back at its original position if we have it, otherwise find correct position
                if (originalDeltaIndex != null
                        && originalDeltaIndex >= 0
                        && originalDeltaIndex <= currentPatch.getDeltas().size()) {
                    currentPatch.getDeltas().add(originalDeltaIndex, appliedDelta);
                } else {
                    // Fallback to position-based insertion
                    insertDeltaInCorrectPosition(currentPatch, appliedDelta);
                }
            }

            // Restore selected delta state
            diffPanel.setSelectedDelta(selectedDeltaSnapshot);

            // Check if any documents have returned to their original saved state
            recheckDocumentStates();

            // Refresh the UI to reflect the restored state
            diffPanel.diff(false); // Don't auto-scroll during undo

            // Update save status to reflect the reverted state
            diffPanel.recalcDirty();

        } catch (Exception e) {
            logger.error("Failed to undo chunk application", e);
            throw new CannotUndoException();
        }
    }

    @Override
    public void redo() throws CannotRedoException {
        super.redo();
        logger.debug("Redoing chunk application: {}", operationType);

        try {
            // Redo all document changes
            for (var edit : documentEdits) {
                if (edit.canRedo()) {
                    edit.redo();
                }
            }

            // Remove the delta from patch again (simulate reapplication)
            var currentPatch = diffPanel.getPatch();
            if (currentPatch != null && currentPatch.getDeltas().contains(appliedDelta)) {
                currentPatch.getDeltas().remove(appliedDelta);
            }

            // Check if any documents have returned to their original saved state
            recheckDocumentStates();

            // Refresh the UI
            diffPanel.diff(false); // Don't auto-scroll during redo

            // Update save status to reflect the reapplied state
            diffPanel.recalcDirty();

        } catch (Exception e) {
            logger.error("Failed to redo chunk application", e);
            throw new CannotRedoException();
        }
    }

    @Override
    public boolean canUndo() {
        return super.canUndo() && documentEdits.stream().allMatch(UndoableEdit::canUndo);
    }

    @Override
    public boolean canRedo() {
        return super.canRedo() && documentEdits.stream().allMatch(UndoableEdit::canRedo);
    }

    @Override
    public String getPresentationName() {
        return operationType;
    }

    /**
     * Insert the delta back into the patch at the correct position based on line numbers. This maintains the sorted
     * order of deltas in the patch using binary search for O(log n) performance.
     */
    private void insertDeltaInCorrectPosition(Patch<String> patch, AbstractDelta<String> delta) {
        var deltas = patch.getDeltas();

        // Use binary search to find the correct insertion position
        int searchResult = Collections.binarySearch(
                deltas, delta, Comparator.comparingInt(d -> d.getSource().getPosition()));

        // If searchResult is negative, convert to insertion point
        int insertPosition = searchResult < 0 ? -(searchResult + 1) : searchResult;

        deltas.add(insertPosition, delta);
    }

    /**
     * Check all file panel documents to see if they have returned to their original saved state. If so, reset their
     * changed flags accordingly.
     */
    private void recheckDocumentStates() {
        // Check both left and right file panels
        for (var side : BufferDiffPanel.PanelSide.values()) {
            var filePanel = diffPanel.getFilePanel(side);
            if (filePanel != null) {
                var bufferDoc = filePanel.getBufferDocument();
                if (bufferDoc != null) {
                    bufferDoc.recheckChangedState();
                }
            }
        }
    }
}
