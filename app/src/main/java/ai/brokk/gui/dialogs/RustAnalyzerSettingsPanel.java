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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class RustAnalyzerSettingsPanel extends AbstractMacroSettingsPanel {

    private final JProgressBar progressBar;
    private final MaterialButton findMacrosBtn;

    public RustAnalyzerSettingsPanel(Language language, IProject project, IConsoleIO io) {
        super(language, project, io);

        this.progressBar = new JProgressBar();
        this.progressBar.setIndeterminate(true);
        this.progressBar.setVisible(false);

        this.findMacrosBtn = new MaterialButton("Find unmapped macros");
        this.findMacrosBtn.addActionListener(e -> findUnmappedMacros());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonPanel.add(findMacrosBtn);
        buttonPanel.add(progressBar);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void findUnmappedMacros() {
        findMacrosBtn.setEnabled(false);
        progressBar.setVisible(true);

        CompletableFuture.runAsync(() -> {
            Set<String> discoveredMacroNames = new HashSet<>();
            try {
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
            } catch (Exception ex) {
                logger.error("Error during macro discovery", ex);
            } finally {
                SwingUtilities.invokeLater(() -> finalizeDiscovery(discoveredMacroNames));
            }
        });
    }

    private void finalizeDiscovery(Set<String> discoveredMacroNames) {
        progressBar.setVisible(false);
        findMacrosBtn.setEnabled(true);

        // Check macroList which is inherited and the source of truth for the table
        Set<String> existingNames = macroList.stream().map(MacroMatch::name).collect(Collectors.toSet());

        int addedCount = 0;
        for (String name : discoveredMacroNames) {
            if (!existingNames.contains(name)) {
                macroList.add(
                        new MacroMatch(name, null, MacroPolicy.MacroStrategy.BYPASS, new MacroPolicy.BypassConfig()));
                addedCount++;
            }
        }

        if (addedCount > 0) {
            tableModel.fireTableDataChanged();
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO, "Discovered and added " + addedCount + " new unmapped macros.");
        } else {
            io.showNotification(IConsoleIO.NotificationRole.INFO, "No new unmapped macros discovered.");
        }
    }
}
