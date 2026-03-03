package ai.brokk.gui.dialogs;

import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.Language;
import ai.brokk.project.IProject;
import java.awt.BorderLayout;
import java.util.Arrays;
import java.util.stream.Collectors;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class JavaAnalyzerSettingsPanel extends AnalyzerSettingsPanel {
    private final IProject project;
    private final JTextArea sourceRootsArea;

    public JavaAnalyzerSettingsPanel(Language language, IProject project, IConsoleIO io) {
        super(new BorderLayout(), language, project.getRoot(), io);
        this.project = project;

        JPanel contentPanel = new JPanel(new BorderLayout(5, 5));
        contentPanel.add(new JLabel("Source Roots (relative to project root):"), BorderLayout.NORTH);

        String currentRoots = String.join("\n", project.getJavaSourceRoots());
        sourceRootsArea = new JTextArea(currentRoots, 10, 40);
        contentPanel.add(new JScrollPane(sourceRootsArea), BorderLayout.CENTER);

        add(contentPanel, BorderLayout.CENTER);
    }

    @Override
    public void saveSettings() {
        var roots = Arrays.stream(sourceRootsArea.getText().split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        project.setJavaSourceRoots(roots);
        logger.debug(
                "Saved {} Java source roots for project {}",
                roots.size(),
                project.getRoot().getFileName());
    }
}
