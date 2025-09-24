package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.gui.GuiTheme;
import javax.swing.JComponent;
import org.jetbrains.annotations.Nullable;

/**
 * Base class for diff panel implementations with common functionality. This class provides default implementations for
 * common operations and maintains shared state between different diff view types.
 *
 * <p>Note: This extends AbstractContentPanel to leverage existing undo/redo infrastructure while adding IDiffPanel
 * functionality.
 */
public abstract class AbstractDiffPanel extends AbstractContentPanel implements IDiffPanel {
    protected final BrokkDiffPanel parent;
    protected final GuiTheme theme;

    @Nullable
    protected JMDiffNode diffNode;

    protected String creationContext = "unknown";

    public AbstractDiffPanel(BrokkDiffPanel parent, GuiTheme theme) {
        this.parent = parent;
        this.theme = theme;
    }

    // Common implementations from IDiffPanel
    @Override
    @Nullable
    public JMDiffNode getDiffNode() {
        return diffNode;
    }

    @Override
    public void setDiffNode(@Nullable JMDiffNode diffNode) {
        this.diffNode = diffNode;
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void markCreationContext(String context) {
        this.creationContext = context;
    }

    @Override
    public String getCreationContext() {
        return creationContext;
    }

    // Provide access to parent for subclasses
    protected BrokkDiffPanel getDiffParent() {
        return parent;
    }

    protected GuiTheme getTheme() {
        return theme;
    }

    // Default implementations that subclasses may override
    @Override
    public void refreshComponentListeners() {
        // Default implementation - subclasses can override if needed
    }

    @Override
    public void clearCaches() {
        // Default implementation - subclasses can override if needed
    }

    @Override
    public void dispose() {
        // Default cleanup - subclasses should override and call super
        removeAll();
        this.diffNode = null;
    }

    // IDiffPanel implementations that delegate to existing AbstractContentPanel methods
    // These provide the bridge between IDiffPanel interface and existing functionality

    // Navigation methods are already defined in AbstractContentPanel as abstract
    // isAtFirstLogicalChange(), isAtLastLogicalChange(), goToLastLogicalChange()
    // doUp(), doDown() are already implemented in AbstractContentPanel

    // Undo/redo methods are already implemented in AbstractContentPanel
    // isUndoEnabled(), doUndo(), isRedoEnabled(), doRedo()
}
