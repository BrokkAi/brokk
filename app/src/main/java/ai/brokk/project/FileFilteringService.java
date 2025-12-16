package ai.brokk.project;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
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
     * Filter files by exclusion patterns and gitignore rules.
     * exclusionPatterns should come from BuildDetails.exclusionPatterns().
     *
     * <p>Pattern semantics:
     * <ul>
     *   <li>Simple names (no wildcards, no slash): match directory prefix OR exact filename
     *   <li>Extension patterns (*.ext): match files by extension
     *   <li>Glob patterns: full path or filename matching depending on presence of /
     * </ul>
     */
    public Set<ProjectFile> filterFiles(Set<ProjectFile> files, Set<String> exclusionPatterns) {
        // Normalize patterns: strip leading slashes, trailing slashes, normalize separators
        var normalizedPatterns = exclusionPatterns.stream()
                .map(s -> toUnixPath(s).trim())
                .map(s -> s.startsWith("/") ? s.substring(1) : s)
                .map(s -> s.startsWith("./") ? s.substring(2) : s)
                .map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        // Pre-compile patterns once for efficient matching across all files
        var compiledPatterns = compilePatterns(normalizedPatterns);

        Set<ProjectFile> patternFiltered = files.stream()
                .filter(file -> !matchesFilePatternStatic(file, compiledPatterns))
                .collect(Collectors.toSet());

        // If no Git repo, return pattern-filtered only
        if (!(repo instanceof GitRepo gitRepo)) {
            return patternFiltered;
        }

        var gitTopLevel = gitRepo.getGitTopLevel();
        var workTreeRoot = gitRepo.getWorkTreeRoot();

        // If project root is outside the git work tree, skip gitignore filtering
        if (!root.startsWith(workTreeRoot)) {
            logger.warn(
                    "Project root {} is outside git working tree {}; gitignore filtering skipped", root, workTreeRoot);
            return patternFiltered;
        }

        var fixedGitignorePairs = computeFixedGitignorePairs(gitRepo, gitTopLevel);

        return patternFiltered.stream()
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
     * @deprecated Use {@link #isGitignored(Path)} instead
     */
    @Deprecated
    public boolean isDirectoryIgnored(Path directoryRelPath) {
        return isGitignored(directoryRelPath);
    }

    /**
     * Determine if a path (file or directory) is ignored by gitignore rules.
     * Returns false on error or if no git repo.
     */
    public boolean isGitignored(Path relPath) {
        if (!(repo instanceof GitRepo gitRepo)) {
            return false;
        }

        var gitTopLevel = gitRepo.getGitTopLevel();
        var workTreeRoot = gitRepo.getWorkTreeRoot();

        if (!root.startsWith(workTreeRoot)) {
            return false;
        }

        var fixedGitignorePairs = computeFixedGitignorePairs(gitRepo, gitTopLevel);

        return isPathIgnored(gitRepo, relPath, fixedGitignorePairs);
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
    // Internal helper methods
    // -------------------------

    /** Represents a pre-compiled file pattern for efficient matching. */
    private sealed interface CompiledPattern {
        /** Simple name matches exact filename OR directory prefix (fast path). */
        record SimpleName(String lowerName) implements CompiledPattern {}

        record Extension(String lowerSuffix) implements CompiledPattern {}

        record Glob(Pattern regex, boolean matchFullPath) implements CompiledPattern {}
    }

    /**
     * A cacheable pattern matcher that holds pre-compiled patterns.
     * Create once via {@link #createPatternMatcher(Set)} and reuse for multiple file checks.
     */
    public static final class FilePatternMatcher {
        private final List<CompiledPattern> compiledPatterns;

        private FilePatternMatcher(List<CompiledPattern> compiledPatterns) {
            this.compiledPatterns = compiledPatterns;
        }

        /** Check if a file matches any exclusion pattern. */
        public boolean matches(ProjectFile file) {
            return matchesFilePatternStatic(file, compiledPatterns);
        }

        /**
         * Check if a path is excluded (for directory prefix matching).
         * Uses fast prefix matching for SimpleName patterns.
         *
         * @param relativePath the path to check
         * @param isDirectory true if the path is a directory (skips Extension pattern checks)
         */
        public boolean isPathExcluded(String relativePath, boolean isDirectory) {
            return isPathExcludedStatic(relativePath, compiledPatterns, isDirectory);
        }

        public boolean isEmpty() {
            return compiledPatterns.isEmpty();
        }
    }

    /**
     * Create a reusable pattern matcher from a set of patterns.
     * The returned matcher can be cached and reused for efficient repeated matching.
     */
    public static FilePatternMatcher createPatternMatcher(Set<String> patterns) {
        if (patterns.isEmpty()) {
            return new FilePatternMatcher(List.of());
        }
        return new FilePatternMatcher(compilePatterns(patterns));
    }

    /**
     * Pre-compile file patterns for efficient reuse across many files.
     *
     * <p>Pattern semantics (all matching is case-insensitive):
     * <ul>
     *   <li><b>Simple name</b> (no wildcards, no slash): matches exact filename OR directory prefix.
     *       Example: {@code node_modules} excludes dir and contents, {@code package-lock.json} excludes file.
     *   <li><b>Extension pattern</b> ({@code *.ext} without additional wildcards in suffix):
     *       matched against filename only. Example: {@code *.svg}, {@code *.min.js}
     *   <li><b>Glob pattern</b> (contains {@code *}, {@code ?}, or {@code /}):
     *       if pattern contains {@code /}, matched against full relative path;
     *       otherwise matched against filename only. Example: {@code *.*}, {@code build/**}
     * </ul>
     *
     * <p>Note: {@code *.*} matches any file with a dot in its name, including dotfiles
     * like {@code .gitignore} and {@code .env}.
     */
    private static List<CompiledPattern> compilePatterns(Set<String> patterns) {
        var compiled = new ArrayList<CompiledPattern>();
        for (String rawPattern : patterns) {
            String pattern = rawPattern.trim();
            if (pattern.isEmpty()) continue;

            // Simple name match (e.g., "node_modules", "package-lock.json")
            // Matches exact filename OR directory prefix
            if (!pattern.contains("*") && !pattern.contains("?") && !pattern.contains("/")) {
                compiled.add(new CompiledPattern.SimpleName(pattern.toLowerCase(Locale.ROOT)));
                continue;
            }

            // Simple extension match (e.g., "*.svg", "*.min.js")
            if (pattern.startsWith("*.") && !pattern.contains("/")) {
                String suffix = pattern.substring(1);
                if (!suffix.contains("*") && !suffix.contains("?")) {
                    compiled.add(new CompiledPattern.Extension(suffix.toLowerCase(Locale.ROOT)));
                    continue;
                }
            }

            // Path glob pattern - convert to regex for platform-independent matching
            try {
                String lowerPattern = pattern.toLowerCase(Locale.ROOT);
                var regex = globToRegex(lowerPattern);
                compiled.add(new CompiledPattern.Glob(regex, pattern.contains("/")));
            } catch (Exception e) {
                logger.debug("Invalid glob pattern '{}': {}", pattern, e.getMessage());
            }
        }
        return compiled;
    }

    /**
     * Convert a glob pattern to a regex Pattern for platform-independent matching.
     * Uses Unix path separators (forward slashes) consistently.
     *
     * <p>Glob syntax:
     * <ul>
     *   <li>{@code **} matches any characters including path separators
     *   <li>{@code *} matches any characters except path separator
     *   <li>{@code ?} matches exactly one character except path separator
     * </ul>
     */
    @VisibleForTesting
    public static Pattern globToRegex(String glob) {
        var regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    // ** matches anything including path separators
                    regex.append(".*");
                    i += 2;
                    // Skip trailing / after **
                    if (i < glob.length() && glob.charAt(i) == '/') {
                        regex.append("/?");
                        i++;
                    }
                } else {
                    // * matches anything except path separator
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else if (".^$+[]{}()|\\".indexOf(c) >= 0) {
                // Escape regex metacharacters
                regex.append('\\').append(c);
                i++;
            } else {
                regex.append(c);
                i++;
            }
        }
        return Pattern.compile(regex.toString());
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

    /**
     * Check if a file matches any of the given patterns.
     * Patterns are compiled on each call; for repeated checks, callers should cache compiled patterns.
     *
     * @param file the project file to check
     * @param patterns the set of patterns to check against
     * @return true if the file matches any pattern
     */
    public static boolean matchesAnyFilePattern(ProjectFile file, Set<String> patterns) {
        if (patterns.isEmpty()) {
            return false;
        }
        var compiled = compilePatterns(patterns);
        return matchesFilePatternStatic(file, compiled);
    }

    private static boolean matchesFilePatternStatic(ProjectFile file, List<CompiledPattern> compiledPatterns) {
        if (compiledPatterns.isEmpty()) {
            return false;
        }

        String filePath = toUnixPath(file.getRelPath());
        String fileName = file.getFileName();
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        String lowerFilePath = filePath.toLowerCase(Locale.ROOT);

        for (var cp : compiledPatterns) {
            boolean matched =
                    switch (cp) {
                        case CompiledPattern.SimpleName sn ->
                            // Match exact filename OR file is under a directory with this name
                            lowerFileName.equals(sn.lowerName())
                                    || lowerFilePath.startsWith(sn.lowerName() + "/")
                                    || lowerFilePath.contains("/" + sn.lowerName() + "/");
                        case CompiledPattern.Extension ext -> lowerFileName.endsWith(ext.lowerSuffix());
                        case CompiledPattern.Glob g ->
                            g.regex()
                                    .matcher(g.matchFullPath() ? lowerFilePath : lowerFileName)
                                    .matches();
                    };
            if (matched) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a path (file or directory) is excluded by any pattern.
     * Uses fast prefix matching for SimpleName patterns.
     *
     * <p>Pattern matching behavior aligns with {@link #matchesFilePatternStatic}:
     * <ul>
     *   <li>SimpleName: matches path prefix or exact name
     *   <li>Extension: matches filename suffix (files only)
     *   <li>Glob with {@code matchFullPath=true}: matches against full path
     *   <li>Glob with {@code matchFullPath=false}: matches against name component only
     * </ul>
     *
     * @param relativePath the path to check
     * @param compiledPatterns pre-compiled patterns
     * @param isDirectory true if the path is a directory (skips Extension pattern checks)
     */
    private static boolean isPathExcludedStatic(
            String relativePath, List<CompiledPattern> compiledPatterns, boolean isDirectory) {
        if (compiledPatterns.isEmpty()) {
            return false;
        }

        String path = toUnixPath(relativePath);
        String lowerPath = path.toLowerCase(Locale.ROOT);
        int lastSlash = lowerPath.lastIndexOf('/');
        String lowerName = lastSlash >= 0 ? lowerPath.substring(lastSlash + 1) : lowerPath;

        for (var cp : compiledPatterns) {
            boolean matched =
                    switch (cp) {
                        case CompiledPattern.SimpleName sn ->
                            // Path equals the name, or starts with name/, or contains /name/
                            lowerPath.equals(sn.lowerName())
                                    || lowerPath.startsWith(sn.lowerName() + "/")
                                    || lowerPath.contains("/" + sn.lowerName() + "/")
                                    || lowerPath.endsWith("/" + sn.lowerName());
                        case CompiledPattern.Extension ext ->
                            // Extension patterns only apply to files, not directories
                            !isDirectory && lowerName.endsWith(ext.lowerSuffix());
                        case CompiledPattern.Glob g ->
                            // Respect matchFullPath: path globs match full path, filename globs match name only
                            g.regex().matcher(g.matchFullPath() ? lowerPath : lowerName).matches();
                    };
            if (matched) {
                return true;
            }
        }
        return false;
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
