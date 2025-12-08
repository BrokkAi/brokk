package ai.brokk.project;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.ignore.IgnoreNode.MatchResult;
import org.eclipse.jgit.util.FS;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Encapsulates baseline-exclusion + gitignore filtering.
 * Mirrors prior logic from AbstractProject but is self-contained so it can be tested and reused.
 */
public final class FileFilteringService {
    private static final Logger logger = LogManager.getLogger(FileFilteringService.class);

    private final Path root;
    private final IGitRepo repo;

    // Caches (concurrent for thread-safety)
    private final Map<Path, IgnoreNode> ignoreNodeCache = new ConcurrentHashMap<>();
    private final Map<Path, List<Map.Entry<Path, Path>>> gitignoreChainCache = new ConcurrentHashMap<>();

    public FileFilteringService(Path root, IGitRepo repo) {
        this.root = root;
        this.repo = repo;
    }

    /**
     * Filter files by baseline exclusions and gitignore rules.
     * rawExclusions should come from BuildDetails.excludedDirectories() (un-normalized).
     */
    public Set<ProjectFile> filterFiles(Set<ProjectFile> files, Set<String> rawExclusions) {
        return filterFiles(files, rawExclusions, Set.of());
    }

    /**
     * Filter files by baseline exclusions, file patterns, and gitignore rules.
     * rawExclusions should come from BuildDetails.excludedDirectories() (un-normalized).
     * filePatterns should come from BuildDetails.excludedFilePatterns() (glob patterns like *.svg, package-lock.json).
     */
    public Set<ProjectFile> filterFiles(Set<ProjectFile> files, Set<String> rawExclusions, Set<String> filePatterns) {
        // Normalize baseline exclusions
        var baselineExclusions = rawExclusions.stream()
                .map(s -> toUnixPath(s).trim())
                .map(s -> s.startsWith("/") ? s.substring(1) : s)
                .map(s -> s.startsWith("./") ? s.substring(2) : s)
                .map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        // Also create normalized-from-raw-leading set for tolerant matching
        Set<String> rawLeadingSlashExclusions = rawExclusions.stream()
                .map(String::trim)
                .filter(s -> s.startsWith("/"))
                .collect(Collectors.toSet());

        Set<String> normalizedFromRawLeading = rawLeadingSlashExclusions.stream()
                .map(s -> s.substring(1))
                .map(FileFilteringService::toUnixPath)
                .map(String::trim)
                .map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        Set<String> unionNormalized = new HashSet<>(baselineExclusions);
        unionNormalized.addAll(normalizedFromRawLeading);

        Set<ProjectFile> baselineFiltered = files.stream()
                .filter(file -> !isBaselineExcluded(file, unionNormalized))
                .filter(file -> !matchesFilePattern(file, filePatterns))
                .collect(Collectors.toSet());

        // If no Git repo, return baseline-filtered only
        if (!(repo instanceof GitRepo gitRepo)) {
            return baselineFiltered;
        }

        var gitTopLevel = gitRepo.getGitTopLevel();
        var workTreeRoot = gitRepo.getWorkTreeRoot();

        // If project root is outside the git work tree, skip gitignore filtering
        if (!root.startsWith(workTreeRoot)) {
            logger.warn(
                    "Project root {} is outside git working tree {}; gitignore filtering skipped", root, workTreeRoot);
            return baselineFiltered;
        }

        var fixedGitignorePairs = computeFixedGitignorePairs(gitRepo, gitTopLevel);

        return baselineFiltered.stream()
                .filter(file -> {
                    // do not filter out deps
                    var isDep = file.getRelPath()
                            .startsWith(Path.of(AbstractProject.BROKK_DIR).resolve(AbstractProject.DEPENDENCIES_DIR));
                    return isDep || !isPathIgnored(gitRepo, file.getRelPath(), fixedGitignorePairs);
                })
                .collect(Collectors.toSet());
    }

    /**
     * Determine if a directory is ignored by gitignore rules.
     * Returns false on error.
     */
    public boolean isDirectoryIgnored(Path directoryRelPath) {
        if (!(repo instanceof GitRepo gitRepo)) {
            return false;
        }

        var gitTopLevel = gitRepo.getGitTopLevel();
        var workTreeRoot = gitRepo.getWorkTreeRoot();

        if (!root.startsWith(workTreeRoot)) {
            return false;
        }

        var fixedGitignorePairs = computeFixedGitignorePairs(gitRepo, gitTopLevel);

        return isPathIgnored(gitRepo, directoryRelPath, fixedGitignorePairs);
    }

    public Optional<Path> getGlobalGitignorePath() {
        if (!(repo instanceof GitRepo gitRepo)) {
            return Optional.empty();
        }
        return getGlobalGitignoreFile(gitRepo);
    }

    /** Clear internal caches. */
    public void invalidateCaches() {
        ignoreNodeCache.clear();
        gitignoreChainCache.clear();
    }

    // -------------------------
    // Internal helper methods (port of previous logic)
    // -------------------------

    private boolean isBaselineExcluded(ProjectFile file, Set<String> baselineExclusions) {
        String fileRel = toUnixPath(file.getRelPath());
        for (String exclusion : baselineExclusions) {
            String ex = toUnixPath(exclusion);
            while (ex.startsWith("./")) ex = ex.substring(2);
            if (ex.startsWith("/")) ex = ex.substring(1);
            if (ex.endsWith("/")) ex = ex.substring(0, ex.length() - 1);
            if (ex.isEmpty()) continue;

            if (fileRel.equals(ex) || fileRel.startsWith(ex + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a file matches any of the given file patterns.
     * Supports exact filename match (package-lock.json), simple extension patterns (*.svg),
     * and path glob patterns using ** for directory matching.
     */
    private boolean matchesFilePattern(ProjectFile file, Set<String> patterns) {
        if (patterns.isEmpty()) {
            return false;
        }

        String filePath = toUnixPath(file.getRelPath());
        String fileName = file.getFileName();

        for (String rawPattern : patterns) {
            if (rawPattern == null) {
                continue;
            }
            String pattern = rawPattern.trim();
            if (pattern.isEmpty()) {
                continue;
            }

            // Exact filename match (e.g., "package-lock.json") - case-insensitive for consistency with UI
            if (!pattern.contains("*") && !pattern.contains("?") && !pattern.contains("/")) {
                if (fileName.equalsIgnoreCase(pattern)) {
                    logger.trace("File {} excluded by exact pattern: {}", filePath, pattern);
                    return true;
                }
                continue;
            }

            // Simple extension match (e.g., "*.svg", "*.min.js") - suffix must not contain wildcards
            // Case-insensitive for consistency with UI
            if (pattern.startsWith("*.") && !pattern.contains("/")) {
                String suffix = pattern.substring(1); // ".svg" or ".min.js"
                // Only use fast path if suffix has no wildcards; otherwise fall through to glob
                if (!suffix.contains("*") && !suffix.contains("?")) {
                    if (fileName.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT))) {
                        logger.trace("File {} excluded by extension pattern: {}", filePath, pattern);
                        return true;
                    }
                    continue;
                }
            }

            // Path glob pattern (e.g., "**/test/resources/**", "src/test/resources/**")
            // For patterns without "/", match against just the filename (e.g., "*.*" matches any file with extension)
            // Normalize to lowercase for case-insensitive matching across all platforms
            try {
                String lowerPattern = pattern.toLowerCase(Locale.ROOT);
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + lowerPattern);
                String pathToMatch = (pattern.contains("/") ? filePath : fileName).toLowerCase(Locale.ROOT);
                if (matcher.matches(Path.of(pathToMatch))) {
                    logger.trace("File {} excluded by glob pattern: {}", filePath, pattern);
                    return true;
                }
            } catch (Exception e) {
                logger.debug("Invalid glob pattern '{}': {}", pattern, e.getMessage());
            }
        }
        return false;
    }

    private Optional<Path> getGlobalGitignoreFile(GitRepo gitRepo) {
        var config = gitRepo.getGit().getRepository().getConfig();

        String configPath = config.getString("core", null, "excludesfile");
        if (configPath != null && !configPath.isEmpty()) {
            var fs = FS.DETECTED;
            Path globalIgnore;

            if (configPath.startsWith("~/")) {
                File resolved = fs.resolve(fs.userHome(), configPath.substring(2));
                globalIgnore = resolved.toPath();
            } else {
                globalIgnore = Path.of(configPath);
            }

            if (Files.exists(globalIgnore)) {
                logger.debug("Using global gitignore from core.excludesfile: {}", globalIgnore);
                return Optional.of(globalIgnore);
            }
        }

        File userHome = FS.DETECTED.userHome();
        Path xdgIgnore = userHome.toPath().resolve(".config/git/ignore");
        if (Files.exists(xdgIgnore)) {
            logger.debug("Using global gitignore from XDG location: {}", xdgIgnore);
            return Optional.of(xdgIgnore);
        }

        Path legacyIgnore = userHome.toPath().resolve(".gitignore_global");
        if (Files.exists(legacyIgnore)) {
            logger.debug("Using global gitignore from legacy location: {}", legacyIgnore);
            return Optional.of(legacyIgnore);
        }

        return Optional.empty();
    }

    private List<Map.Entry<Path, Path>> computeFixedGitignorePairs(GitRepo gitRepo, Path gitTopLevel) {
        var fixedPairs = new ArrayList<Map.Entry<Path, Path>>();

        getGlobalGitignoreFile(gitRepo).ifPresent(globalIgnore -> {
            fixedPairs.add(Map.entry(Path.of(""), globalIgnore));
        });

        var gitInfoExclude = gitTopLevel.resolve(".git/info/exclude");
        if (Files.exists(gitInfoExclude)) {
            fixedPairs.add(Map.entry(Path.of(""), gitInfoExclude));
        }

        var rootGitignore = gitTopLevel.resolve(".gitignore");
        if (Files.exists(rootGitignore)) {
            fixedPairs.add(Map.entry(Path.of(""), rootGitignore));
        }

        return fixedPairs;
    }

    private MatchResult checkIgnoreFile(Path ignoreFile, String pathToCheck, boolean isDirectory) {
        var ignoreNode = ignoreNodeCache.computeIfAbsent(ignoreFile, path -> {
            try {
                var node = new IgnoreNode();
                try (var inputStream = Files.newInputStream(path)) {
                    node.parse(inputStream);
                }
                return node;
            } catch (IOException e) {
                logger.debug("Error parsing gitignore file {}: {}", path, e.getMessage());
                return new IgnoreNode();
            }
        });
        return ignoreNode.isIgnored(pathToCheck, isDirectory);
    }

    private List<Map.Entry<Path, Path>> collectGitignorePairs(
            Path gitTopLevel, Path gitRelPath, List<Map.Entry<Path, Path>> fixedGitignorePairs) {
        Path directory = gitRelPath.getParent();
        if (directory == null) {
            directory = Path.of("");
        }

        var cached = gitignoreChainCache.get(directory);
        if (cached != null) {
            return cached;
        }

        var gitignorePairs = new ArrayList<Map.Entry<Path, Path>>();
        gitignorePairs.addAll(fixedGitignorePairs);

        var nestedGitignores = new ArrayList<Map.Entry<Path, Path>>();
        Path currentDir = directory;
        while (currentDir != null && !currentDir.toString().isEmpty()) {
            var nestedGitignore = gitTopLevel.resolve(currentDir).resolve(".gitignore");
            if (Files.exists(nestedGitignore)) {
                nestedGitignores.add(Map.entry(currentDir, nestedGitignore));
            }
            currentDir = currentDir.getParent();
        }
        Collections.reverse(nestedGitignores);
        gitignorePairs.addAll(nestedGitignores);

        gitignoreChainCache.put(directory, gitignorePairs);

        return gitignorePairs;
    }

    @VisibleForTesting
    public static String toUnixPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    /** Normalize a string path to use forward slashes. */
    public static String toUnixPath(String path) {
        return path.replace('\\', '/');
    }

    @VisibleForTesting
    public static String normalizePathForGitignore(Path gitignoreDir, Path pathToNormalize) {
        if (gitignoreDir.toString().isEmpty()) {
            return toUnixPath(pathToNormalize);
        } else {
            return toUnixPath(gitignoreDir.relativize(pathToNormalize));
        }
    }

    private String toGitRelativePath(GitRepo gitRepo, Path projectRelPath) {
        Path gitTopLevel = gitRepo.getGitTopLevel();
        Path projectAbsPath = root.resolve(projectRelPath);
        Path gitRelPath = gitTopLevel.relativize(projectAbsPath);
        return toUnixPath(gitRelPath);
    }

    private boolean isPathIgnored(
            GitRepo gitRepo, Path projectRelPath, List<Map.Entry<Path, Path>> fixedGitignorePairs) {
        String gitRelPath = toGitRelativePath(gitRepo, projectRelPath);
        Path gitRelPathObj = Path.of(gitRelPath);
        var gitTopLevel = gitRepo.getGitTopLevel();

        Path absPath = root.resolve(projectRelPath);
        boolean isDirectory = Files.isDirectory(absPath);

        var gitignorePairs = collectGitignorePairs(gitTopLevel, gitRelPathObj, fixedGitignorePairs);

        MatchResult finalResult = MatchResult.CHECK_PARENT;

        for (var entry : gitignorePairs) {
            var gitignoreDir = entry.getKey();
            var gitignoreFile = entry.getValue();

            String relativeToGitignoreDir = normalizePathForGitignore(gitignoreDir, gitRelPathObj);

            var result = checkIgnoreFile(gitignoreFile, relativeToGitignoreDir, isDirectory);

            if (result == MatchResult.IGNORED) {
                finalResult = MatchResult.IGNORED;
            } else if (result == MatchResult.NOT_IGNORED) {
                finalResult = MatchResult.NOT_IGNORED;
            }
        }

        if (finalResult == MatchResult.IGNORED) {
            logger.trace("Path {} (isDir: {}) ignored: true (result: IGNORED)", gitRelPath, isDirectory);
            return true;
        }

        Path parent = gitRelPathObj.getParent();
        while (parent != null && !parent.toString().isEmpty()) {
            String parentPath = toUnixPath(parent);

            var parentGitignorePairs = collectGitignorePairs(gitTopLevel, parent, fixedGitignorePairs);

            MatchResult parentResult = MatchResult.CHECK_PARENT;

            for (var entry : parentGitignorePairs) {
                var gitignoreDir = entry.getKey();
                var gitignoreFile = entry.getValue();

                String relativeToGitignoreDir = normalizePathForGitignore(gitignoreDir, Path.of(parentPath));

                var result = checkIgnoreFile(gitignoreFile, relativeToGitignoreDir, true);

                if (result == MatchResult.IGNORED) {
                    parentResult = MatchResult.IGNORED;
                } else if (result == MatchResult.NOT_IGNORED) {
                    parentResult = MatchResult.NOT_IGNORED;
                }
            }

            if (parentResult == MatchResult.IGNORED) {
                logger.trace("Path {} ignored: true (parent {} is ignored)", gitRelPath, parentPath);
                return true;
            }

            if (parentResult == MatchResult.NOT_IGNORED) {
                logger.trace("Path {} ignored: false (parent {} has negation)", gitRelPath, parentPath);
                return false;
            }

            parent = parent.getParent();
        }

        if (finalResult == MatchResult.NOT_IGNORED) {
            logger.trace("Path {} ignored: false (result: NOT_IGNORED, no ignored parents)", gitRelPath);
        } else {
            logger.trace("Path {} ignored: false (result: CHECK_PARENT, no ignored parents)", gitRelPath);
        }
        return false;
    }
}
