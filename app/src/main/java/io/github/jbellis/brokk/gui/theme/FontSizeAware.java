package io.github.jbellis.brokk.gui.theme;

/**
 * Interface for components that have explicit font size settings that should be preserved
 * during theme application. This allows the theme system to distinguish between
 * default fonts (which can be overridden) and explicitly set fonts (which should be preserved).
 */
public interface FontSizeAware {
    /**
     * @return true if this component has an explicitly set font size that should be preserved
     */
    boolean hasExplicitFontSize();

    /**
     * @return the explicitly set font size, or -1 if no explicit font size is set
     */
    float getExplicitFontSize();

    /**
     * Sets an explicit font size for this component
     * @param size the font size to set
     */
    void setExplicitFontSize(float size);
}
