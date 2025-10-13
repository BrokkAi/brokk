package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.tests.TestRunnerPanel;
import java.awt.*;
import javax.swing.*;

public class ProjectFilesPanel extends JPanel {
    private final ProjectTree projectTree;
    private final JSplitPane splitPane;
    private final TestRunnerPanel testRunnerPanel;

    public ProjectFilesPanel(Chrome chrome, ContextManager contextManager, TestRunnerPanel testRunnerPanel) {
        super(new BorderLayout());

        this.testRunnerPanel = testRunnerPanel;

        projectTree = new ProjectTree(contextManager.getProject(), contextManager, chrome);
        var projectTreeScrollPane = new JScrollPane(projectTree);

        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, projectTreeScrollPane, this.testRunnerPanel);
        splitPane.setResizeWeight(0.7);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setOneTouchExpandable(false);
        splitPane.setMinimumSize(new java.awt.Dimension(100, 200));

        this.testRunnerPanel.setMinimumSize(new java.awt.Dimension(100, 200));

        add(splitPane, BorderLayout.CENTER);
    }

    public void updatePanel() {
        projectTree.onTrackedFilesChanged();
    }

    public void showFileInTree(ProjectFile projectFile) {
        projectTree.selectAndExpandToFile(projectFile);
    }
}
