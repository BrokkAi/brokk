package io.github.jbellis.brokk.gui.mop.stream.blocks;

import javax.swing.*;

/**
 * Represents a component that can be rendered in the UI.
 * Each component has a stable ID and a fingerprint for change detection.
 */
public sealed interface ComponentData
    permits MarkdownComponentData, CodeBlockComponentData, EditBlockComponentData, CompositeComponentData {
    
    /**
     * Returns the unique identifier for this component.
     */
    int id();
    
    /**
     * Returns a fingerprint that changes when the component's content changes.
     */
    String fp();
    
    /**
     * Creates a new Swing component for this data.
     * 
     * @param darkTheme whether to use dark theme styling
     * @return a new Swing component
     */
    JComponent createComponent(boolean darkTheme);
    
    /**
     * Updates an existing component with this data.
     * Implementations should preserve caret position and scroll state when possible.
     * 
     * @param component the component to update
     */
    void updateComponent(JComponent component);
}
