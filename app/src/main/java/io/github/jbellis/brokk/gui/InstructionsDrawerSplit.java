package io.github.jbellis.brokk.gui;

import java.awt.Component;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

/**
 * A simple horizontal split container that pairs the instructions panel (left) with a right-hand drawer panel. It
 * configures sensible defaults and exposes only explicit setters for the two child components.
 */
public final class InstructionsDrawerSplit extends JSplitPane {

    public InstructionsDrawerSplit() {
        super(JSplitPane.HORIZONTAL_SPLIT);
        setResizeWeight(1.0); // Give extra space to the instructions panel
        setDividerLocation(1.0); // Fully collapse drawer initially
        setContinuousLayout(true);
        setOneTouchExpandable(false);
    }

    public void setInstructionsComponent(Component comp) {
        assert SwingUtilities.isEventDispatchThread() : "Must run on EDT";
        setLeftComponent(comp);
    }

    public void setDrawerComponent(Component comp) {
        assert SwingUtilities.isEventDispatchThread() : "Must run on EDT";
        setRightComponent(comp);
    }
}
