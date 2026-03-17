package ai.brokk.gui.dialogs;

import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.RustAnalyzer;
import ai.brokk.analyzer.macro.MacroPolicy;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.project.IProject;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.JPanel;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class RustAnalyzerSettingsPanel extends AbstractMacroSettingsPanel {

    public RustAnalyzerSettingsPanel(Language language, IProject project, IConsoleIO io) {
        super(language, project, io);

        MaterialButton findMacrosBtn = new MaterialButton("Find unmapped macros");
        findMacrosBtn.addActionListener(e -> findUnmappedMacros());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonPanel.add(findMacrosBtn);
        add(buttonPanel, BorderLayout.SOUTH);

        if (yamlEditor.getText().isBlank()) {
            yamlEditor.setText("# Use \"Find unmapped macros\" to populate macros used in your codebase\n");
        }
    }

    private void findUnmappedMacros() {
        Set<String> discoveredMacroNames = new HashSet<>();
        RustAnalyzer analyzer = new RustAnalyzer(project);

        // Scan all Rust files in the project
        for (ProjectFile file : project.getAnalyzableFiles(Languages.RUST)) {
            analyzer.withTreeOf(
                    file,
                    tree -> {
                        analyzer.withSource(
                                file,
                                sourceContent -> {
                                    discoveredMacroNames.addAll(analyzer.findMacroNames(tree, sourceContent));
                                    return true;
                                },
                                false);
                        return true;
                    },
                    false);
        }

        // Parse current YAML to see what we already have
        Set<String> existingNames = new HashSet<>();
        try {
            String currentText = yamlEditor.getText();
            if (currentText != null && !currentText.isBlank()) {
                try (var is = new ByteArrayInputStream(currentText.getBytes(StandardCharsets.UTF_8))) {
                    MacroPolicy policy = YAML_MAPPER.readValue(is, MacroPolicy.class);
                    existingNames.addAll(
                            policy.macros().stream().map(m -> m.name()).collect(Collectors.toSet()));
                }
            }
        } catch (Exception ex) {
            // If YAML is invalid, we'll just append to whatever is there
            logger.warn("Could not parse existing macro policy during discovery", ex);
        }

        StringBuilder newEntries = new StringBuilder();
        for (String name : discoveredMacroNames) {
            if (!existingNames.contains(name)) {
                newEntries.append("\n  - name: \"").append(name).append("\"\n");
                newEntries.append("    strategy: \"BYPASS\"\n");
            }
        }

        if (newEntries.length() > 0) {
            String currentText = yamlEditor.getText();
            if (currentText == null || currentText.isBlank() || !currentText.contains("macros:")) {
                yamlEditor.setText("version: \"1.0\"\nlanguage: \"rust\"\nmacros:" + newEntries.toString());
            } else {
                yamlEditor.append(newEntries.toString());
            }
        } else {
            io.showNotification(IConsoleIO.NotificationRole.INFO, "No new unmapped macros discovered.");
        }
    }
}
