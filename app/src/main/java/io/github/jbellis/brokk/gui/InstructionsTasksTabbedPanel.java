package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.gui.terminal.TaskListPanel;
import java.awt.BorderLayout;
import javax.swing.JTabbedPane;
import javax.swing.JPanel;

/**
 * Container panel that hosts the InstructionsPanel and the TaskListPanel in a tabbed UI.
 *
 * <p>Usage:
 * - Construct with the main Chrome instance and the already-created InstructionsPanel.
 * - This panel creates its own TaskListPanel bound to the same Chrome instance.
 * - The tab order is: Instructions, then Tasks.
 *
 * <p>Theming:
 * - Only the TaskListPanel requires explicit theme application here.
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
    // InstructionsPanel does not require applyTheme here
    taskListPanel.applyTheme(guiTheme);
  }

  public TaskListPanel getTaskListPanel() {
    return taskListPanel;
  }

  public InstructionsPanel getInstructionsPanel() {
    return instructionsPanel;
  }
}
