
package io.github.jbellis.brokk.difftool.ui;

public interface JMeldContentPanelIF {

    boolean isUndoEnabled();

    void doUndo();

    boolean isRedoEnabled();

    void doRedo();


    void doUp();

    void doDown();

}
