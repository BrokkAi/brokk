package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.tests.TestRunnerPanel;

import javax.swing.*;
import java.awt.*;

public class ProjectFilesPanel extends JPanel {
    private final ProjectTree projectTree;
    private final JSplitPane splitPane;
    private final TestRunnerPanel testRunnerPanel;

    private boolean testRunnerVisible = true;
    private int lastDividerLocation = -1;
    private int normalDividerSize = 8;

    public ProjectFilesPanel(Chrome chrome, ContextManager contextManager, TestRunnerPanel testRunnerPanel) {
        super(new BorderLayout());

        this.testRunnerPanel = testRunnerPanel;

        projectTree = new ProjectTree(contextManager.getProject(), contextManager, chrome);
        var projectTreeScrollPane = new JScrollPane(projectTree);

        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, projectTreeScrollPane, this.testRunnerPanel);
        splitPane.setResizeWeight(0.7);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setOneTouchExpandable(true); // allow very simple hide/show by user
        normalDividerSize = splitPane.getDividerSize();

        add(splitPane, BorderLayout.CENTER);
    }

    public void updatePanel() {
        projectTree.onTrackedFilesChanged();
    }

    public void showFileInTree(ProjectFile projectFile) {
        projectTree.selectAndExpandToFile(projectFile);
    }

    /**
     * Show or hide the bottom Test Runner panel.
     * When hidden, the project tree occupies the full height.
     */
    public void setTestRunnerVisible(boolean visible) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setTestRunnerVisible(visible));
            return;
        }
        if (this.testRunnerVisible == visible) return;

        this.testRunnerVisible = visible;

        if (visible) {
            // Restore divider and bottom component visibility
            splitPane.setDividerSize(normalDividerSize);
            testRunnerPanel.setVisible(true);
            splitPane.setResizeWeight(0.7);
            if (lastDividerLocation > 0) {
                splitPane.setDividerLocation(lastDividerLocation);
            } else {
                // Fall back to a reasonable ratio if we have no previous divider location
                splitPane.setDividerLocation(0.7);
            }
        } else {
            // Remember current divider location and collapse bottom component
            lastDividerLocation = splitPane.getDividerLocation();
            testRunnerPanel.setVisible(false);
            splitPane.setDividerSize(0);
            splitPane.setResizeWeight(1.0);
            splitPane.setDividerLocation(1.0);
        }
        revalidate();
        repaint();
    }

    public boolean isTestRunnerVisible() {
        return testRunnerVisible;
    }

    public void toggleTestRunnerVisibility() {
        setTestRunnerVisible(!testRunnerVisible);
    }
}
