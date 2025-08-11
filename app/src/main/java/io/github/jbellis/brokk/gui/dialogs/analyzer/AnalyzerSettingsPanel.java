package io.github.jbellis.brokk.gui.dialogs.analyzer;

import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.gui.dialogs.SettingsProjectPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

public abstract class AnalyzerSettingsPanel extends JPanel {

    protected final Logger logger = LoggerFactory.getLogger(AnalyzerSettingsPanel.class);

    protected final Language language;
    protected final Path projectRoot;

    public AnalyzerSettingsPanel(BorderLayout borderLayout, Language language, Path projectRoot) {
        super(borderLayout);
        this.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        this.language = language;
        this.projectRoot = projectRoot;
    }

    public static AnalyzerSettingsPanel createAnalyzersPanel(SettingsProjectPanel parent, Language language, Path projectRoot) {
        if (language.internalName().equals("JAVA")) {
            return new JavaAnalyzerSettingsPanel(parent, language, projectRoot);
        } else {
            return new EmptyAnalyzerSettingsPanel(language, projectRoot);
        }
    }

    public void saveSettings() {

    }

}
