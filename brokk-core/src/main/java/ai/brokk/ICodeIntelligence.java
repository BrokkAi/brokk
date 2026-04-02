package ai.brokk;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.ICoreProject;
import java.io.File;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal interface providing access to code intelligence capabilities.
 * Used by SearchTools and the MCP server as a lightweight alternative to IContextManager.
 */
public interface ICodeIntelligence {
    IAnalyzer getAnalyzer();

    ICoreProject getProject();

    @Nullable
    IGitRepo getRepo();

    default ProjectFile toFile(String relName) {
        var trimmed = relName.trim();
        var project = getProject();

        // If an absolute-like path is provided (leading '/' or '\'), attempt to interpret it as a
        // project-relative path by stripping the leading slash.
        if (trimmed.startsWith(File.separator)) {
            var candidateRel = trimmed.substring(File.separator.length()).trim();
            var candidate = new ProjectFile(project.getRoot(), candidateRel);
            if (candidate.exists()) {
                return candidate;
            }
            throw new IllegalArgumentException(
                    "Filename '%s' is absolute-like and does not exist relative to the project root"
                            .formatted(relName));
        }

        return new ProjectFile(project.getRoot(), trimmed);
    }
}
