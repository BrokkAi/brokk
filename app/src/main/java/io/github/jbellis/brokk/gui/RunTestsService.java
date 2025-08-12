package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.util.Collection;
import javax.swing.SwingUtilities;

public final class RunTestsService {
    private RunTestsService() {}

    public static void runTests(Chrome chrome, ContextManager cm, Collection<ProjectFile> testFiles) {
        if (testFiles.isEmpty()) {
            chrome.toolError("No test files specified to run.");
            return;
        }

        String cmd = BuildAgent.getBuildLintSomeCommand(cm, cm.getProject().loadBuildDetails(), testFiles);
        if (cmd.isEmpty()) {
            chrome.toolError("Run in Shell: build commands are unknown; run Build Setup first");
            return;
        }

        SwingUtilities.invokeLater(() -> {
            chrome.getInstructionsPanel().runRunCommand(cmd);
        });
    }
}
