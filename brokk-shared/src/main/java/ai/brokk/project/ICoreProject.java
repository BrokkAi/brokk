package ai.brokk.project;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.IGitRepo;
import ai.brokk.util.IStringDiskCache;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal project interface for code intelligence.
 * Contains only file discovery, language config, exclusion filtering, and git access.
 * No LLM, GUI, session, or build-agent dependencies.
 */
public interface ICoreProject extends AutoCloseable {

    /** Standard directory name for Brokk project configuration. */
    String BROKK_DIR = ".brokk";

    /** Subdirectory for code intelligence state files. */
    String CODE_INTELLIGENCE_DIR = "code_intelligence";

    /** Subdirectory for imported dependency sources. */
    String DEPENDENCIES_DIR = "dependencies";

    /** Project root directory. */
    Path getRoot();

    /** All tracked project files (cached). */
    @Blocking
    Set<ProjectFile> getAllFiles();

    /** Look up a file by relative path. */
    Optional<ProjectFile> getFileByRelPath(Path relPath);

    /** Whether the project has no analyzable files. */
    boolean isEmptyProject();

    /** Files analyzable for a specific language. */
    Set<ProjectFile> getAnalyzableFiles(Language language);

    /** Languages the analyzer should process. */
    Set<Language> getAnalyzerLanguages();

    /** Set the languages the analyzer should process. */
    void setAnalyzerLanguages(Set<Language> languages);

    /** Force language re-detection on next access. */
    void invalidateAutoDetectedLanguages();

    /** Language-specific source roots. */
    @Blocking
    List<String> getSourceRoots(Language language);

    /** Set language-specific source roots. */
    void setSourceRoots(Language language, List<String> roots);

    /** Check if a path is gitignored. */
    boolean isGitignored(Path relPath);

    /** Check if a path is gitignored, with directory hint. */
    boolean isGitignored(Path relPath, boolean isDirectory);

    /** Combined gitignore + exclusion check. */
    boolean shouldSkipPath(Path relPath, boolean isDirectory);

    /** All exclusion patterns (simple names + globs). */
    Set<String> getExclusionPatterns();

    /** Simple directory/file names to exclude (no wildcards). */
    Set<String> getExcludedDirectories();

    /** Glob-style patterns to exclude. */
    Set<String> getExcludedGlobPatterns();

    /** Check if a relative path matches any exclusion pattern. */
    boolean isPathExcluded(String relativePath, boolean isDirectory);

    /** Filter out excluded files from a set. */
    Set<ProjectFile> filterExcludedFiles(Set<ProjectFile> files);

    /** Invalidate the file cache. */
    void invalidateAllFiles();

    /** Get the git repo, or null if no git. */
    @Nullable
    IGitRepo getRepo();

    /** Whether this project is in a git repository. */
    boolean hasGit();

    /** The root path for config and session storage (worktree-aware). */
    Path getMasterRootPathForConfig();

    /** Disk cache for project-scoped caching. */
    IStringDiskCache getDiskCache();


    @Override
    void close();
}
