package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.agents.BuildAgent;

import javax.swing.SwingUtilities;
import java.util.Collection;

public final class RunTestsService {
    private RunTestsService() {
    }

    public static void runTests(Chrome chrome,
                                ContextManager cm,
                                Collection<ProjectFile> testFiles) {
        if (testFiles.isEmpty()) {
            chrome.toolError("No test files specified to run.");
            return;
        }

        String cmd = BuildAgent.getBuildLintCommand(cm,
                                                    cm.getProject().loadBuildDetails(),
                                                    testFiles);
        if (cmd.isEmpty()) {
            chrome.toolError("Run in Shell: build commands are unknown; run Build Setup first");
            return;
        }

        SwingUtilities.invokeLater(() -> {
            chrome.getInstructionsPanel().runRunCommand(cmd);
        });
    }
}
