package ai.brokk.difftool.ui;

import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.util.Icons;
import java.util.Set;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Configurable toolbar panel for diff viewers.
 * Button groups can be enabled/disabled via {@link ToolbarFeature} set.
 */
public class DiffToolbarPanel extends JToolBar {
    private static final Logger logger = LogManager.getLogger(DiffToolbarPanel.class);

    private final Set<ToolbarFeature> features;
    private final DiffToolbarCallbacks callbacks;

    // Navigation buttons
    @Nullable
    private MaterialButton btnPrevious;

    @Nullable
    private MaterialButton btnNext;

    @Nullable
    private MaterialButton btnPreviousFile;

    @Nullable
    private MaterialButton btnNextFile;

    // Edit buttons
    @Nullable
    private MaterialButton btnUndo;

    @Nullable
    private MaterialButton btnRedo;

    @Nullable
    private MaterialButton btnSaveAll;

    // View mode
    @Nullable
    private JToggleButton viewModeToggle;

    @Nullable
    private JCheckBoxMenuItem menuShowBlame;

    @Nullable
    private JCheckBoxMenuItem menuShowAllLines;

    @Nullable
    private JCheckBoxMenuItem menuShowBlankLineDiffs;

    // Font controls
    @Nullable
    private MaterialButton btnDecreaseFont;

    @Nullable
    private MaterialButton btnResetFont;

    @Nullable
    private MaterialButton btnIncreaseFont;

    // Capture buttons
    @Nullable
    private MaterialButton captureDiffButton;

    @Nullable
    private MaterialButton captureAllDiffsButton;

    public DiffToolbarPanel(Set<ToolbarFeature> features, DiffToolbarCallbacks callbacks) {
        assert SwingUtilities.isEventDispatchThread() : "Must be constructed on EDT";
        this.features = features;
        this.callbacks = callbacks;
        buildToolbar();
    }

    private void buildToolbar() {

        // Change navigation buttons (chevrons for fine navigation)
        if (features.contains(ToolbarFeature.CHANGE_NAVIGATION)) {
            btnPrevious = new MaterialButton();
            btnPrevious.setIcon(Icons.CHEVRON_LEFT);
            btnPrevious.setToolTipText("Previous Change");
            btnPrevious.addActionListener(e -> callbacks.navigateToPreviousChange());

            btnNext = new MaterialButton();
            btnNext.setIcon(Icons.CHEVRON_RIGHT);
            btnNext.setToolTipText("Next Change");
            btnNext.addActionListener(e -> callbacks.navigateToNextChange());

            add(btnPrevious);
            add(Box.createHorizontalStrut(10));
            add(btnNext);
        }

        // File navigation buttons (larger arrows for coarse navigation)
        if (features.contains(ToolbarFeature.FILE_NAVIGATION) && callbacks.isMultiFile()) {
            btnPreviousFile = new MaterialButton();
            btnPreviousFile.setIcon(Icons.NAVIGATE_BEFORE);
            btnPreviousFile.setToolTipText("Previous File");
            btnPreviousFile.addActionListener(e -> callbacks.previousFile());

            btnNextFile = new MaterialButton();
            btnNextFile.setIcon(Icons.NAVIGATE_NEXT);
            btnNextFile.setToolTipText("Next File");
            btnNextFile.addActionListener(e -> callbacks.nextFile());

            add(Box.createHorizontalStrut(20));
            addSeparator();
            add(Box.createHorizontalStrut(10));
            add(btnPreviousFile);
            add(Box.createHorizontalStrut(10));
            add(btnNextFile);
        }

        // Edit controls
        if (features.contains(ToolbarFeature.EDIT_CONTROLS)) {
            btnUndo = new MaterialButton();
            btnUndo.setIcon(Icons.UNDO);
            btnUndo.setToolTipText("Undo");
            btnUndo.addActionListener(e -> callbacks.performUndo());

            btnRedo = new MaterialButton();
            btnRedo.setIcon(Icons.REDO);
            btnRedo.setToolTipText("Redo");
            btnRedo.addActionListener(e -> callbacks.performRedo());

            btnSaveAll = new MaterialButton();
            btnSaveAll.setIcon(Icons.SAVE);
            btnSaveAll.setToolTipText("Save");
            btnSaveAll.addActionListener(e -> callbacks.saveAll());

            add(Box.createHorizontalStrut(20));
            addSeparator();
            add(Box.createHorizontalStrut(10));
            add(btnUndo);
            add(Box.createHorizontalStrut(10));
            add(btnRedo);
            add(Box.createHorizontalStrut(10));
            add(btnSaveAll);
        }

        // View mode toggle and tools menu
        if (features.contains(ToolbarFeature.VIEW_MODE_TOGGLE) || features.contains(ToolbarFeature.TOOLS_MENU)) {
            add(Box.createHorizontalStrut(20));
            addSeparator();
            add(Box.createHorizontalStrut(10));
        }

        if (features.contains(ToolbarFeature.VIEW_MODE_TOGGLE)) {
            var toggle = new JToggleButton();
            toggle.setIcon(Icons.VIEW_UNIFIED);
            toggle.setSelectedIcon(Icons.VIEW_SIDE_BY_SIDE);
            toggle.setText(null);
            toggle.setToolTipText("Toggle Unified View");
            toggle.setSelected(callbacks.isUnifiedView());
            toggle.addActionListener(e -> callbacks.switchViewMode(toggle.isSelected()));
            viewModeToggle = toggle;
            add(toggle);
            add(Box.createHorizontalStrut(10));
        }

        if (features.contains(ToolbarFeature.TOOLS_MENU)) {
            var tools = new MaterialButton();
            tools.setIcon(Icons.DIFF_TOOLS);
            tools.setToolTipText("View Options");
            tools.setText(null);
            tools.setBorderPainted(false);
            tools.setContentAreaFilled(false);
            tools.setFocusPainted(false);

            var toolsMenu = new JPopupMenu();

            var blameItem = new JCheckBoxMenuItem("Show Git Blame");
            blameItem.setSelected(callbacks.isShowingBlame());
            blameItem.addActionListener(e -> callbacks.setShowBlame(blameItem.isSelected()));
            toolsMenu.add(blameItem);
            menuShowBlame = blameItem;

            var allLinesItem = new JCheckBoxMenuItem("Show All Lines");
            allLinesItem.setSelected(callbacks.isShowingAllLines());
            allLinesItem.addActionListener(e -> callbacks.setShowAllLines(allLinesItem.isSelected()));
            toolsMenu.add(allLinesItem);
            menuShowAllLines = allLinesItem;

            var blankLinesItem = new JCheckBoxMenuItem("Show Empty Line Diffs");
            blankLinesItem.setSelected(callbacks.isShowingBlankLineDiffs());
            blankLinesItem.addActionListener(e -> callbacks.setShowBlankLineDiffs(blankLinesItem.isSelected()));
            toolsMenu.add(blankLinesItem);
            menuShowBlankLineDiffs = blankLinesItem;

            tools.addActionListener(e -> toolsMenu.show(tools, 0, tools.getHeight()));
            add(tools);
        }

        // Push remaining items to the right
        add(Box.createHorizontalGlue());

        // Font controls
        if (features.contains(ToolbarFeature.FONT_CONTROLS)) {
            btnDecreaseFont = callbacks.createDecreaseFontButton(callbacks::decreaseEditorFont);
            btnResetFont = callbacks.createResetFontButton(callbacks::resetEditorFont);
            btnIncreaseFont = callbacks.createIncreaseFontButton(callbacks::increaseEditorFont);

            add(btnDecreaseFont);
            add(Box.createHorizontalStrut(4));
            add(btnResetFont);
            add(Box.createHorizontalStrut(4));
            add(btnIncreaseFont);
            add(Box.createHorizontalStrut(8));
        }

        // Capture controls
        if (features.contains(ToolbarFeature.CAPTURE_CONTROLS)) {
            captureDiffButton = new MaterialButton();
            captureDiffButton.setIcon(Icons.CONTENT_CAPTURE);
            captureDiffButton.setToolTipText("Capture Diff");
            captureDiffButton.addActionListener(e -> callbacks.captureCurrentDiff());
            add(captureDiffButton);

            if (callbacks.isMultiFile()) {
                captureAllDiffsButton = new MaterialButton();
                captureAllDiffsButton.setText("Capture All Diffs");
                captureAllDiffsButton.setToolTipText("Capture all file diffs to the context");
                captureAllDiffsButton.addActionListener(e -> callbacks.captureAllDiffs());
                add(Box.createHorizontalStrut(8));
                add(captureAllDiffsButton);
            }
        }

        updateToolbarForViewMode();
    }

    /**
     * Update button enable/disable states based on current callbacks state.
     * Should be called after navigation or edit operations.
     */
    public void updateButtonStates() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        logger.debug("Updating toolbar button states");

        // Change navigation
        if (btnPrevious != null) {
            btnPrevious.setEnabled(callbacks.canNavigateToPreviousChange());
        }
        if (btnNext != null) {
            btnNext.setEnabled(callbacks.canNavigateToNextChange());
        }

        // File navigation
        if (btnPreviousFile != null) {
            btnPreviousFile.setEnabled(callbacks.canNavigateToPreviousFile());
        }
        if (btnNextFile != null) {
            btnNextFile.setEnabled(callbacks.canNavigateToNextFile());
        }

        // Edit controls
        if (btnUndo != null) {
            btnUndo.setEnabled(callbacks.isUndoEnabled());
        }
        if (btnRedo != null) {
            btnRedo.setEnabled(callbacks.isRedoEnabled());
        }
        if (btnSaveAll != null) {
            int unsavedCount = callbacks.getUnsavedCount();
            String baseSaveText = callbacks.isMultiFile() ? "Save All" : "Save";
            btnSaveAll.setToolTipText(unsavedCount > 0 ? baseSaveText + " (" + unsavedCount + ")" : baseSaveText);
            btnSaveAll.setEnabled(callbacks.isSaveEnabled());
        }

        // View mode toggle
        if (viewModeToggle != null) {
            viewModeToggle.setSelected(callbacks.isUnifiedView());
        }

        // Blame menu
        if (menuShowBlame != null) {
            menuShowBlame.setEnabled(callbacks.canShowBlame());
            menuShowBlame.setSelected(callbacks.isShowingBlame());
        }

        // Other menu items
        if (menuShowAllLines != null) {
            menuShowAllLines.setSelected(callbacks.isShowingAllLines());
        }
        if (menuShowBlankLineDiffs != null) {
            menuShowBlankLineDiffs.setSelected(callbacks.isShowingBlankLineDiffs());
        }

        // Capture buttons always enabled
        if (captureDiffButton != null) {
            captureDiffButton.setEnabled(true);
        }
        if (captureAllDiffsButton != null) {
            captureAllDiffsButton.setEnabled(true);
        }
    }

    /**
     * Update toolbar menu visibility based on current view mode.
     * Shows "Show All Lines" in unified view, "Show Empty Line Diffs" in side-by-side.
     */
    public void updateToolbarForViewMode() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        logger.debug("Updating toolbar for view mode: unified={}", callbacks.isUnifiedView());
        boolean unifiedView = callbacks.isUnifiedView();

        if (menuShowAllLines != null) {
            menuShowAllLines.setVisible(unifiedView);
        }
        if (menuShowBlankLineDiffs != null) {
            menuShowBlankLineDiffs.setVisible(!unifiedView);
        }

        revalidate();
        repaint();
    }

    /**
     * Show or hide navigation buttons.
     * Used to hide navigation when in guided review mode (navigation is controlled by review items).
     */
    public void setNavigationVisible(boolean visible) {
        if (btnPrevious != null) btnPrevious.setVisible(visible);
        if (btnNext != null) btnNext.setVisible(visible);
        if (btnPreviousFile != null) btnPreviousFile.setVisible(visible);
        if (btnNextFile != null) btnNextFile.setVisible(visible);
    }

    /**
     * Disable all control buttons during loading/switching operations.
     */
    public void disableAllControlButtons() {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";

        if (btnPreviousFile != null) btnPreviousFile.setEnabled(false);
        if (btnNextFile != null) btnNextFile.setEnabled(false);
        if (btnNext != null) btnNext.setEnabled(false);
        if (btnPrevious != null) btnPrevious.setEnabled(false);
        if (btnUndo != null) btnUndo.setEnabled(false);
        if (btnRedo != null) btnRedo.setEnabled(false);
        if (btnSaveAll != null) btnSaveAll.setEnabled(false);
    }

    // Accessors for buttons that may need external access

    @Nullable
    public MaterialButton getUndoButton() {
        return btnUndo;
    }

    @Nullable
    public MaterialButton getRedoButton() {
        return btnRedo;
    }

    @Nullable
    public MaterialButton getSaveButton() {
        return btnSaveAll;
    }

    public DiffToolbarCallbacks getCallbacks() {
        return callbacks;
    }
}
