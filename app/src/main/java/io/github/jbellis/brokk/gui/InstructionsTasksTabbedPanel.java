package io.github.jbellis.brokk.gui;

import static io.github.jbellis.brokk.gui.Constants.*;
import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.gui.components.ModelSelector;
import io.github.jbellis.brokk.gui.components.SplitButton;
import io.github.jbellis.brokk.gui.terminal.TaskListPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.CardLayout;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

/**
 * Container panel that hosts the InstructionsPanel and the TaskListPanel in a tabbed UI.
 *
 * <p>Usage: - Construct with the main Chrome instance and the already-created InstructionsPanel. - This panel creates
 * its own TaskListPanel bound to the same Chrome instance. - The tab order is: Instructions, then Tasks.
 *
 * <p>Theming: - Both tabs (Instructions and Tasks) receive theme updates via applyTheme.
 *
 * <p>Verification: - See docs/ManualVerification.md for a manual verification checklist covering tabs, tasks, Architect
 * runs on tasks, theme toggling, and persistence across restarts.
 */
public class InstructionsTasksTabbedPanel extends JPanel implements ThemeAware {
    private final JTabbedPane tabbedPane;
    private final InstructionsPanel instructionsPanel;
    private final TaskListPanel taskListPanel;

    public InstructionsTasksTabbedPanel(Chrome chrome, InstructionsPanel instructionsPanel) {
        super(new BorderLayout());
        this.instructionsPanel = instructionsPanel;

        this.tabbedPane = new JTabbedPane();
        this.taskListPanel = new TaskListPanel(chrome);

        // Tabs
        tabbedPane.addTab("Instructions", this.instructionsPanel);
        tabbedPane.addTab("Tasks", this.taskListPanel);

        add(tabbedPane, BorderLayout.CENTER);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        // Forward theme updates to both tabs
        instructionsPanel.applyTheme(guiTheme);
        taskListPanel.applyTheme(guiTheme);
    }

    public TaskListPanel getTaskListPanel() {
        return taskListPanel;
    }

    public InstructionsPanel getInstructionsPanel() {
        return instructionsPanel;
    }

    public void selectInstructionsTab() {
        if (SwingUtilities.isEventDispatchThread()) {
            tabbedPane.setSelectedIndex(0);
        } else {
            SwingUtilities.invokeLater(() -> tabbedPane.setSelectedIndex(0));
        }
    }

    public void selectTasksTab() {
        if (SwingUtilities.isEventDispatchThread()) {
            tabbedPane.setSelectedIndex(1);
        } else {
            SwingUtilities.invokeLater(() -> tabbedPane.setSelectedIndex(1));
        }
    }

    /**
     * Routes the action button click to the appropriate panel based on the currently selected tab.
     * Instructions tab (index 0) routes to InstructionsPanel.onActionButtonPressed().
     * Tasks tab (index 1) routes to TaskListPanel.runArchitectOnAll().
     */
    public void onActionButtonPressed() {
        int selectedTab = tabbedPane.getSelectedIndex();
        if (selectedTab == 0) {
            instructionsPanel.onActionButtonPressed();
        } else if (selectedTab == 1) {
            taskListPanel.runArchitectOnAll();
        }
    }

    public static JPanel buildSharedBottomPanel(
            JComponent actionButton,
            ModelSelector modelSelector,
            SplitButton branchSplitButton,
            JCheckBox modeSwitch,
            JPanel optionsPanel,
            JCheckBox searchProjectCheckBox) {
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.LINE_AXIS));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // Group the card panel so it stays aligned with other toolbar controls.
        Box optionGroup = Box.createHorizontalBox();
        optionGroup.setOpaque(false);
        optionGroup.setAlignmentY(Component.CENTER_ALIGNMENT);
        optionsPanel.setAlignmentY(Component.CENTER_ALIGNMENT);

        int planFixedHeight = Math.max(actionButton.getPreferredSize().height, 32);

        // Ensure the card panel has enough width for its widest child (e.g., "Search") and allow horizontal growth.
        int optWidth = Math.max(optionsPanel.getPreferredSize().width, searchProjectCheckBox.getPreferredSize().width);
        if (optWidth <= 0) {
            optWidth = searchProjectCheckBox.getPreferredSize().width + H_GAP;
        }
        optionsPanel.setPreferredSize(new Dimension(optWidth, planFixedHeight));
        optionsPanel.setMinimumSize(new Dimension(optWidth, planFixedHeight));
        optionsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, planFixedHeight));
        optionsPanel.setAlignmentY(Component.CENTER_ALIGNMENT);

        optionGroup.add(optionsPanel);

        bottomPanel.add(optionGroup);
        bottomPanel.add(Box.createHorizontalStrut(H_GAP));

        // Ensure the initial visible card matches the current mode
        ((CardLayout) optionsPanel.getLayout())
                .show(optionsPanel, modeSwitch.isSelected() ? InstructionsPanel.OPTIONS_CARD_ASK : InstructionsPanel.OPTIONS_CARD_CODE);

        // Flexible space between action controls and Go/Stop
        bottomPanel.add(Box.createHorizontalGlue());

        // Add branch button and model selector
        branchSplitButton.setAlignmentY(Component.CENTER_ALIGNMENT);
        bottomPanel.add(branchSplitButton);
        bottomPanel.add(Box.createHorizontalStrut(H_GAP));

        var modelComp = modelSelector.getComponent();
        modelComp.setAlignmentY(Component.CENTER_ALIGNMENT);
        bottomPanel.add(modelComp);
        bottomPanel.add(Box.createHorizontalStrut(H_GAP));

        // Action button (Go/Stop toggle) on the right
        actionButton.setAlignmentY(Component.CENTER_ALIGNMENT);
        // Make the action button slightly smaller while keeping a fixed minimum height
        int fixedHeight = Math.max(actionButton.getPreferredSize().height, 32);
        var prefSize = new Dimension(64, fixedHeight);
        actionButton.setPreferredSize(prefSize);
        actionButton.setMinimumSize(prefSize);
        actionButton.setMaximumSize(prefSize);
        if (actionButton instanceof javax.swing.AbstractButton button) {
            button.setMargin(new Insets(4, 10, 4, 10));
        }

        bottomPanel.add(actionButton);

        // Repaint when focus changes so focus border is visible
        actionButton.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                actionButton.repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                actionButton.repaint();
            }
        });

        // Lock bottom toolbar height so BorderLayout keeps it visible
        Dimension bottomPref = bottomPanel.getPreferredSize();
        bottomPanel.setMinimumSize(new Dimension(0, bottomPref.height));

        return bottomPanel;
    }
}
