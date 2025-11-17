package ai.brokk.gui.theme;

import ai.brokk.gui.components.EditorFontSizeControl;

/**
 * Interface for components that have explicit font size settings that should be preserved
 * during theme application. This allows the theme system to distinguish between
 * default fonts (which can be overridden) and explicitly set fonts (which should be preserved).
 *
 * <p>Components implementing both FontSizeAware and EditorFontSizeControl get automatic
 * tracking of explicit font state based on currentFontIndex.
 */
public interface FontSizeAware {
    /**
     * @return true if this component has an explicitly set font size that should be preserved.
     * Default implementation returns true if currentFontIndex >= 0 (for EditorFontSizeControl implementers).
     */
    default boolean hasExplicitFontSize() {
        // If component implements EditorFontSizeControl, use index to determine explicit state
        if (this instanceof EditorFontSizeControl control) {
            return control.getCurrentFontIndex() >= 0;
        }
        // Otherwise, subclass must override
        throw new UnsupportedOperationException(
                "Component must implement EditorFontSizeControl or override hasExplicitFontSize()");
    }

    /**
     * @return the explicitly set font size, or -1 if no explicit font size is set.
     * Default implementation uses FONT_SIZES from EditorFontSizeControl.
     */
    default float getExplicitFontSize() {
        if (this instanceof EditorFontSizeControl control) {
            int index = control.getCurrentFontIndex();
            if (index >= 0 && index < EditorFontSizeControl.FONT_SIZES.size()) {
                return EditorFontSizeControl.FONT_SIZES.get(index);
            }
        }
        return -1;
    }

    /**
     * Sets an explicit font size for this component.
     * Default implementation for EditorFontSizeControl components finds closest font index.
     * @param size the font size to set
     */
    default void setExplicitFontSize(float size) {
        if (this instanceof EditorFontSizeControl control) {
            int index = control.findClosestFontIndex(size);
            control.setCurrentFontIndex(index);
        } else {
            throw new UnsupportedOperationException(
                    "Component must implement EditorFontSizeControl or override setExplicitFontSize()");
        }
    }
}
