package io.github.jbellis.brokk.gui;

import org.fife.ui.rsyntaxtextarea.Theme;

/**
 * Components that know how to (re-)apply a syntax theme should implement this
 * interface so that {@link GuiTheme} can delegate the work to them while
 * walking the Swing component tree.
 */
public interface ThemeAware {

    /**
     * Called by {@link GuiTheme} when the global theme changes.
     * Implementations should call SwingUtilities.updateComponentTreeUI(this) on themselves
     * This method is always called on the EDT
     *
     * @param guiTheme    reference to the central theme manager (handy for
     *                    delegating to helper methods such as
     *                    {@link GuiTheme#applyCurrentThemeToComponent})
     */
    void applyTheme(GuiTheme guiTheme);
}
