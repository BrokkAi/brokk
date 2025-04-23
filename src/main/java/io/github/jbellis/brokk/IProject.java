package io.github.jbellis.brokk;

import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.DisabledAnalyzer;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;

import java.nio.file.Path;
import java.util.Set;

public interface IProject {
    default IAnalyzer getAnalyzer() throws InterruptedException {
        return new DisabledAnalyzer();
    }

    default IGitRepo getRepo() {
        return Set::of;
    }

    default Path getRoot() {
        return null;
    }

    /**
     * All files in the project, including decompiled dependencies that are not in the git repo.
     */
    default Set<ProjectFile> getAllFiles() {
        return Set.of();
    }

    /**
     * Gets the structured build details inferred by the BuildAgent.
     * @return BuildDetails record, potentially BuildDetails.EMPTY if not found or on error.
     */
    default BuildAgent.BuildDetails getBuildDetails() {
        return BuildAgent.BuildDetails.EMPTY; // Default implementation returns empty
    }

    /** for when you're confident the thread should never be interrupted */
    default IAnalyzer getAnalyzerUninterrupted() {
        try {
            return getAnalyzer();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
