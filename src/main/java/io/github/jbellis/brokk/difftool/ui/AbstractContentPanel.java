package io.github.jbellis.brokk.difftool.ui;

import javax.swing.*;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;

public abstract class AbstractContentPanel
        extends JPanel
        implements JMeldContentPanelIF {
    private MyUndoManager undoManager = new MyUndoManager();

    // Abstract methods to be implemented by subclasses for navigation logic
    public abstract boolean isAtFirstLogicalChange();
    public abstract boolean isAtLastLogicalChange();
    public abstract void goToLastLogicalChange();

    @Override
    public boolean isUndoEnabled() {
        return getUndoHandler().canUndo();
    }

    @Override
    public void doUndo() {
        if (getUndoHandler().canUndo()) {
            getUndoHandler().undo();
        }
    }

    @Override
    public boolean isRedoEnabled() {
        return getUndoHandler().canRedo();
    }

    @Override
    public void doRedo() {
        if (getUndoHandler().canRedo()) {
            getUndoHandler().redo();
        }
    }

    @Override
    public void doUp() {
    }

    @Override
    public void doDown() {
    }

    public class MyUndoManager
            extends UndoManager
            implements UndoableEditListener {
        CompoundEdit activeEdit;

        private MyUndoManager() {
        }

        public void start(String text) {
            activeEdit = new CompoundEdit();
        }

        public void add(UndoableEdit edit) {
            addEdit(edit);
        }

        public void end(String text) {
            activeEdit.end();
            addEdit(activeEdit);
            activeEdit = null;

            checkActions();
        }

        @Override
        public void undoableEditHappened(UndoableEditEvent e) {
            if (activeEdit != null) {
                activeEdit.addEdit(e.getEdit());
                return;
            }

            addEdit(e.getEdit());
            checkActions();
        }
    }

    public MyUndoManager getUndoHandler() {
        return undoManager;
    }

    public void checkActions() {
    }

}
