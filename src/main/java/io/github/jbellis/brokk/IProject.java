package io.github.jbellis.brokk;

import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.DisabledAnalyzer;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public interface IProject {

    default IGitRepo getRepo() {
        return Set::of;
    }

    /**
     * Gets the set of Brokk Language enums configured for the project.
     * @return A set of Language enums.
     */
    default Set<io.github.jbellis.brokk.analyzer.Language> getAnalyzerLanguages() {
        throw new UnsupportedOperationException();
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

    default Project.DataRetentionPolicy getDataRetentionPolicy() {
        return Project.DataRetentionPolicy.MINIMAL;
    }

    default List<String> overrideMissingModels(Set<String> availableModels, String genericDefaultModel) {
        return List.of();
    }

    default String getStyleGuide() {
        return "";
    }
}
