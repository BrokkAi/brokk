package io.github.jbellis.brokk.gui.dialogs.analyzer;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.analyzer.Language;
import java.awt.*;
import java.nio.file.Path;
import javax.swing.*;

public class EmptyAnalyzerSettingsPanel extends AnalyzerSettingsPanel {

    public EmptyAnalyzerSettingsPanel(Language language, Path projectRoot, IConsoleIO io) {
        super(new BorderLayout(), language, projectRoot, io);
        this.add(new JLabel(language.name() + " analyzer (no configurable settings)"), BorderLayout.CENTER);
    }
}
