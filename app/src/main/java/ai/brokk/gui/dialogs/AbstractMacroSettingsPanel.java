package ai.brokk.gui.dialogs;

import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.macro.MacroPolicy;
import ai.brokk.analyzer.macro.MacroPolicyLoader;
import ai.brokk.project.IProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.swing.*;

public abstract class AbstractMacroSettingsPanel extends AnalyzerSettingsPanel {

    protected final JTextArea yamlEditor;
    protected static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    protected AbstractMacroSettingsPanel(Language language, IProject project, IConsoleIO io) {
        super(new BorderLayout(), language, project.getRoot(), project, io);

        this.yamlEditor = new JTextArea();
        this.yamlEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        this.yamlEditor.setTabSize(2);

        // Intercept Tab to insert 2 spaces
        this.yamlEditor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_TAB) {
                    e.consume();
                    yamlEditor.replaceSelection("  ");
                }
            }
        });

        MacroPolicy policy = project.getMacroPolicies().get(language);
        if (policy != null) {
            try {
                this.yamlEditor.setText(
                        YAML_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(policy));
            } catch (IOException e) {
                logger.error("Error formatting macro policy for display", e);
            }
        }

        JScrollPane scrollPane = new JScrollPane(yamlEditor);
        this.add(new JLabel("Macro Policy (YAML):"), BorderLayout.NORTH);
        this.add(scrollPane, BorderLayout.CENTER);
    }

    @Override
    public void saveSettings() {
        String text = yamlEditor.getText();
        if (text == null
                || text.isBlank()
                || text.trim().equals("# Use \"Find unmapped macros\" to populate macros used in your codebase")) {
            project.setMacroPolicy(language, null);
            return;
        }

        try (var is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) {
            MacroPolicy policy = MacroPolicyLoader.load(is);
            if (policy == null) {
                project.setMacroPolicy(language, null);
            } else {
                project.setMacroPolicy(language, policy);
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("No content to map due to end-of-input")) {
                project.setMacroPolicy(language, null);
                return;
            }
            logger.warn("Invalid macro policy YAML for {}: {}", language.name(), e.getMessage());
            io.toolError("Failed to save macro policy: " + e.getMessage());
        }
    }
}
