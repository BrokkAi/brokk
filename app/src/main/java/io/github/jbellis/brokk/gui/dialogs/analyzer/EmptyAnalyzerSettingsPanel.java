package io.github.jbellis.brokk.gui.dialogs.analyzer;

import io.github.jbellis.brokk.analyzer.Language;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

public class EmptyAnalyzerSettingsPanel extends AnalyzerSettingsPanel {

    public EmptyAnalyzerSettingsPanel(Language language, Path projectRoot) {
        super(new BorderLayout(), language, projectRoot);
        this.add(new JLabel(language.name() + " analyzer (no configurable settings)"), BorderLayout.CENTER);
    }

}
