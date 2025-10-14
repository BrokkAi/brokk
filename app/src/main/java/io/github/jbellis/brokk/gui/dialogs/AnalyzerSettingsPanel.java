package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.analyzer.Language;
import java.awt.*;
import java.nio.file.Path;
import javax.swing.*;
import org.slf4j.LoggerFactory;

public abstract class AnalyzerSettingsPanel extends JPanel {

    protected final org.slf4j.Logger logger = LoggerFactory.getLogger(AnalyzerSettingsPanel.class);

    protected final Language language;
    protected final Path projectRoot;
    protected final IConsoleIO io;

    protected AnalyzerSettingsPanel(BorderLayout borderLayout, Language language, Path projectRoot, IConsoleIO io) {
        super(borderLayout);
        this.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        this.language = language;
        this.projectRoot = projectRoot;
        this.io = io;
    }

    public static AnalyzerSettingsPanel createAnalyzersPanel(
            SettingsProjectPanel parent, Language language, Path projectRoot, IConsoleIO io) {
        return new EmptyAnalyzerSettingsPanel(language, projectRoot, io);
    }

    public void saveSettings() {}

    public static class EmptyAnalyzerSettingsPanel extends AnalyzerSettingsPanel {

        public EmptyAnalyzerSettingsPanel(Language language, Path projectRoot, IConsoleIO io) {
            super(new BorderLayout(), language, projectRoot, io);
            this.add(new JLabel(language.name() + " analyzer (no configurable settings)"), BorderLayout.CENTER);
        }
    }
}
