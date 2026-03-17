package ai.brokk.gui.dialogs;

import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.RustAnalyzer;
import ai.brokk.analyzer.macro.MacroPolicy;
import ai.brokk.analyzer.macro.MacroPolicy.MacroMatch;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.project.IProject;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
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

        // Check macroList which is now the source of truth
        Set<String> existingNames = macroList.stream().map(MacroMatch::name).collect(Collectors.toSet());

        boolean added = false;
        for (String name : discoveredMacroNames) {
            if (!existingNames.contains(name)) {
                macroList.add(new MacroMatch(name, null, MacroPolicy.MacroStrategy.BYPASS, null));
                added = true;
            }
        }

        if (added) {
            tableModel.fireTableDataChanged();
            io.showNotification(IConsoleIO.NotificationRole.INFO, "Discovered and added new unmapped macros.");
        } else {
            io.showNotification(IConsoleIO.NotificationRole.INFO, "No new unmapped macros discovered.");
        }
    }
}
