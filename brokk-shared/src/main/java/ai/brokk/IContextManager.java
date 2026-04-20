package ai.brokk;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.ICoreProject;
import java.io.File;
import org.jetbrains.annotations.Blocking;

/**
 * Core, shared ContextManager surface required by analyzer + context-fragment code.
 *
 * <p>App/UI wiring belongs in {@code ai.brokk.IAppContextManager} (in {@code :app}).
 */
public interface IContextManager {
    ICoreProject getProject();

    /**
     * Returns the current analyzer, without throwing {@link InterruptedException}.
     *
     * <p>Implementations should ensure this returns a usable analyzer for read-only analysis work.
     */
    IAnalyzer getAnalyzerUninterrupted();

    /** Convenience for older call sites; shared code should prefer {@link #getAnalyzerUninterrupted()}. */
    default IAnalyzer getAnalyzer() {
        return getAnalyzerUninterrupted();
    }

    /**
     * Given a relative path, uses the current project root to construct a valid {@link ProjectFile}. If the path is
     * suffixed by a leading '/', this is stripped and attempted to be interpreted as a relative path.
     *
     * @param relName a relative path.
     * @return a {@link ProjectFile} instance, if valid.
     * @throws IllegalArgumentException if the path is not relative or normalized.
     */
    @Blocking
    default ProjectFile toFile(String relName) {
        var trimmed = relName.trim();
        var project = getProject();

        // If an absolute-like path is provided (leading '/' or '\'), attempt to interpret it as a
        // project-relative path by stripping the leading slash. If that file exists, return it.
        if (trimmed.startsWith(File.separator)) {
            var candidateRel = trimmed.substring(File.separator.length()).trim();
            var candidate = new ProjectFile(project.getRoot(), candidateRel);
            if (candidate.exists()) {
                return candidate;
            }
            // The path looked absolute (or root-anchored) but does not exist relative to the project.
            // Treat this as invalid to avoid resolving to a location outside the project root.
            throw new IllegalArgumentException(
                    "Filename '%s' is absolute-like and does not exist relative to the project root"
                            .formatted(relName));
        }

        return new ProjectFile(project.getRoot(), trimmed);
    }
}
