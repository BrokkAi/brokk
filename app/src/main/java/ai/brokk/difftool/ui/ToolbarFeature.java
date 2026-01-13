package ai.brokk.difftool.ui;

import java.util.EnumSet;
import java.util.Set;

/**
 * Defines which button groups are visible in a diff toolbar.
 * Use with {@link DiffToolbarPanel} to create toolbars with different feature sets.
 */
public enum ToolbarFeature {
    /** Previous/next change navigation buttons */
    CHANGE_NAVIGATION,

    /** Previous/next file buttons (auto-hidden for single-file diffs) */
    FILE_NAVIGATION,

    /** Undo/redo/save buttons */
    EDIT_CONTROLS,

    /** Unified/side-by-side view toggle button */
    VIEW_MODE_TOGGLE,

    /** Tools menu (blame, show all lines, blank line diffs) */
    TOOLS_MENU,

    /** Font size adjustment buttons */
    FONT_CONTROLS,

    /** Capture diff buttons */
    CAPTURE_CONTROLS;

    /** All features enabled (default for BrokkDiffPanel) */
    public static Set<ToolbarFeature> all() {
        return EnumSet.allOf(ToolbarFeature.class);
    }

    /** View-only features for read-only diff preview (no edit/capture) */
    public static Set<ToolbarFeature> viewOnly() {
        return EnumSet.of(
                CHANGE_NAVIGATION,
                FILE_NAVIGATION,
                VIEW_MODE_TOGGLE,
                TOOLS_MENU,
                FONT_CONTROLS);
    }
}
