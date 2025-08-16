package io.github.jbellis.brokk.gui.dialogs.analyzer;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.gui.dialogs.SettingsProjectPanel;
import java.awt.*;
import java.nio.file.Path;
import javax.swing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AnalyzerSettingsPanel extends JPanel {

    protected final Logger logger = LoggerFactory.getLogger(AnalyzerSettingsPanel.class);

    protected final Language language;
    protected final Path projectRoot;
    protected final IConsoleIO consoleIO;

    protected AnalyzerSettingsPanel(BorderLayout borderLayout, Language language, Path projectRoot, IConsoleIO io) {
        super(borderLayout);
        this.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        this.language = language;
        this.projectRoot = projectRoot;
        this.consoleIO = io;
    }

    public static AnalyzerSettingsPanel createAnalyzersPanel(
            SettingsProjectPanel parent, Language language, Path projectRoot, IConsoleIO io) {
        if (language.internalName().equals("JAVA")) {
            return new JavaAnalyzerSettingsPanel(parent, language, projectRoot, io);
        } else {
            return new EmptyAnalyzerSettingsPanel(language, projectRoot, io);
        }
    }

    public void saveSettings() {}
}
