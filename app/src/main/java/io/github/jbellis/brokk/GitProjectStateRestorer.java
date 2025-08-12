package io.github.jbellis.brokk;

import io.github.jbellis.brokk.context.ContextHistory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

public class GitProjectStateRestorer {

    private static final Logger logger = LogManager.getLogger(GitProjectStateRestorer.class);
    private final IProject project;
    private final IConsoleIO io;

    public GitProjectStateRestorer(IProject project, IConsoleIO io) {
        this.project = project;
        this.io = io;
    }

    public void restore(ContextHistory.GitState gitState) {
        var repo = project.getRepo();
        try {
            repo.checkout(gitState.commitHash());
            if (gitState.diff() != null) {
                repo.applyDiff(gitState.diff());
            }
        } catch (GitAPIException e) {
            logger.error("Failed to restore session state", e);
            io.toolError("Failed to restore session state: " + e.getMessage(), "Error");
        }
    }
}
