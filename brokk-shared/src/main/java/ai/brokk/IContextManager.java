package ai.brokk;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.ICoreProject;
import java.nio.file.Path;
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

        // Treat leading '/' or '\' as "absolute-like" regardless of OS. Users often paste paths from other platforms.
        // We attempt to interpret these as project-relative by stripping the leading separator.
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            return toFileFromAbsoluteLike(project.getRoot(), relName, stripLeadingSlashOrBackslash(trimmed));
        }

        // Handle Windows drive-letter absolute paths (C:\foo\bar or C:/foo/bar) by stripping the drive prefix and
        // attempting to interpret the remainder as project-relative (only if it exists).
        if (isWindowsDriveAbsolute(trimmed)) {
            return toFileFromAbsoluteLike(
                    project.getRoot(), relName, stripLeadingSlashOrBackslash(trimmed.substring(2)));
        }

        ProjectFile pf = new ProjectFile(project.getRoot(), trimmed);
        assertWithinProjectRoot(project.getRoot(), pf, relName);
        return pf;
    }

    private static boolean isWindowsDriveAbsolute(String path) {
        if (path.length() < 3) {
            return false;
        }
        char drive = path.charAt(0);
        if (!((drive >= 'A' && drive <= 'Z') || (drive >= 'a' && drive <= 'z'))) {
            return false;
        }
        if (path.charAt(1) != ':') {
            return false;
        }
        char sep = path.charAt(2);
        return sep == '\\' || sep == '/';
    }

    private static String stripLeadingSlashOrBackslash(String path) {
        String p = path.trim();
        while (!p.isEmpty() && (p.startsWith("/") || p.startsWith("\\"))) {
            p = p.substring(1).trim();
        }
        return p;
    }

    private static ProjectFile toFileFromAbsoluteLike(Path projectRoot, String original, String candidateRel) {
        String normalizedRel = candidateRel.replace('\\', '/').trim();
        ProjectFile candidate = new ProjectFile(projectRoot, normalizedRel);
        assertWithinProjectRoot(projectRoot, candidate, original);
        if (candidate.exists()) {
            return candidate;
        }
        throw new IllegalArgumentException(
                "Filename '%s' is absolute-like and does not exist relative to the project root".formatted(original));
    }

    private static void assertWithinProjectRoot(Path projectRoot, ProjectFile file, String original) {
        Path abs = file.absPath().normalize();
        if (!abs.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Filename '%s' resolves outside the project root".formatted(original));
        }
    }
}
