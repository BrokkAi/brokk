package ai.brokk.gui.dialogs;

import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.project.IProject;
import java.awt.*;
import java.nio.file.Path;
import javax.swing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AnalyzerSettingsPanel extends JPanel {

    protected final Logger logger = LoggerFactory.getLogger(AnalyzerSettingsPanel.class);

    protected final Language language;
    protected final Path projectRoot;
    protected final IProject project;
    protected final IConsoleIO io;

    protected AnalyzerSettingsPanel(
            BorderLayout borderLayout, Language language, Path projectRoot, IProject project, IConsoleIO io) {
        super(borderLayout);
        this.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        this.language = language;
        this.projectRoot = projectRoot;
        this.project = project;
        this.io = io;
    }

    public static AnalyzerSettingsPanel createAnalyzersPanel(
            SettingsProjectPanel parent, Language language, Path projectRoot, IConsoleIO io) {
        IProject project = parent.getChrome().getProject();
        if (language == Languages.JAVA || language.internalName().equals("JAVA")) {
            return new JavaAnalyzerSettingsPanel(language, project, io);
        }
        if (language == Languages.RUST || language.internalName().equals("RUST")) {
            return new RustAnalyzerSettingsPanel(language, project, io);
        }
        return new EmptyAnalyzerSettingsPanel(language, projectRoot, project, io);
    }

    public void saveSettings() {}

    public static class EmptyAnalyzerSettingsPanel extends AnalyzerSettingsPanel {

        public EmptyAnalyzerSettingsPanel(Language language, Path projectRoot, IProject project, IConsoleIO io) {
            super(new BorderLayout(), language, projectRoot, project, io);
            this.add(new JLabel(language.name() + " analyzer (no configurable settings)"), BorderLayout.CENTER);
        }
    }
}
