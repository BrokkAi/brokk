package ai.brokk;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.git.IGitRepo;
import ai.brokk.git.LocalFileRepo;
import ai.brokk.util.AtomicWrites;
import ai.brokk.util.EnvironmentJava;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.swing.JFrame;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.ignore.IgnoreNode.MatchResult;
import org.eclipse.jgit.util.FS;
import org.jetbrains.annotations.Nullable;

public abstract sealed class AbstractProject implements IProject permits MainProject, WorktreeProject {
    protected static final Logger logger = LogManager.getLogger(AbstractProject.class);
    public static final ObjectMapper objectMapper = new ObjectMapper();

    // Brokk directory structure constants
    public static final String BROKK_DIR = ".brokk";
    public static final String SESSIONS_DIR = "sessions";
    public static final String DEPENDENCIES_DIR = "dependencies";
    public static final String CACHE_DIR = "cache";
    public static final String PROJECT_PROPERTIES_FILE = "project.properties";
    public static final String WORKSPACE_PROPERTIES_FILE = "workspace.properties";
    public static final String STYLE_GUIDE_FILE = "AGENTS.md";
    public static final String LEGACY_STYLE_GUIDE_FILE = "style.md";
    public static final String REVIEW_GUIDE_FILE = "review.md";
    public static final String DEBUG_LOG_FILE = "debug.log";
    protected static final String LIVE_DEPENDENCIES_KEY = "liveDependencies";

    protected final Path root;
    protected final IGitRepo repo;
    protected final Path workspacePropertiesFile;
    protected final Properties workspaceProps;
    protected final Path masterRootPathForConfig;

    // Cache for parsed IgnoreNode objects to avoid repeated file I/O and parsing
    // Maps gitignore file path to parsed IgnoreNode
    private final Map<Path, IgnoreNode> ignoreNodeCache = new ConcurrentHashMap<>();

    // Cache for gitignore chains per directory to avoid repeated discovery
    // Maps directory path to list of (gitignoreDir, gitignoreFile) pairs
    // This dramatically reduces the number of Files.exists() calls during filtering
    private final Map<Path, List<Map.Entry<Path, Path>>> gitignoreChainCache = new ConcurrentHashMap<>();

    public AbstractProject(Path root) {
        assert root.isAbsolute() : root;
        this.root = root.toAbsolutePath().normalize();
        this.repo = GitRepoFactory.hasGitRepo(this.root) ? new GitRepo(this.root) : new LocalFileRepo(this.root);

        this.workspacePropertiesFile = this.root.resolve(BROKK_DIR).resolve(WORKSPACE_PROPERTIES_FILE);
        this.workspaceProps = new Properties();

        // Determine masterRootPathForConfig based on this.root and this.repo
        if (this.repo instanceof GitRepo gitRepoInstance && gitRepoInstance.isWorktree()) {
            this.masterRootPathForConfig =
                    gitRepoInstance.getGitTopLevel().toAbsolutePath().normalize();
        } else {
            this.masterRootPathForConfig = this.root; // Already absolute and normalized by super
        }
        logger.debug("Project root: {}, Master root for config/sessions: {}", this.root, this.masterRootPathForConfig);

        if (Files.exists(workspacePropertiesFile)) {
            try (var reader = Files.newBufferedReader(workspacePropertiesFile)) {
                workspaceProps.load(reader);
            } catch (Exception e) {
                logger.error("Error loading workspace properties from {}: {}", workspacePropertiesFile, e.getMessage());
                workspaceProps.clear();
            }
        }
    }

    public static AbstractProject createProject(Path projectPath, @Nullable MainProject parent) {
        return parent == null ? new MainProject(projectPath) : new WorktreeProject(projectPath, parent);
    }

    @Override
    public Path getMasterRootPathForConfig() {
        return masterRootPathForConfig;
    }

    @Override
    public final Path getRoot() {
        return root;
    }

    @Override
    public final IGitRepo getRepo() {
        return repo;
    }

    @Override
    public final boolean hasGit() {
        return repo instanceof GitRepo;
    }

    /** Saves workspace-specific properties (window positions, etc.) */
    public final void saveWorkspaceProperties() {
        saveProperties(workspacePropertiesFile, workspaceProps, "Brokk workspace configuration");
    }

    /** Generic method to save properties to a file */
    private void saveProperties(Path file, Properties properties, String comment) {
        try {
            if (Files.exists(file)) {
                Properties existingProps = new Properties();
                try (var reader = Files.newBufferedReader(file)) {
                    existingProps.load(reader);
                } catch (IOException e) {
                    // Ignore read error, proceed to save anyway
                }
                if (existingProps.equals(properties)) {
                    return;
                }
            }
            AtomicWrites.atomicSaveProperties(file, properties, comment);
        } catch (IOException e) {
            logger.error("Error saving properties to {}: {}", file, e.getMessage());
        }
    }

    public final void saveTextHistory(List<String> historyItems, int maxItems) {
        try {
            var limitedItems = historyItems.stream().limit(maxItems).collect(Collectors.toList());
            String json = objectMapper.writeValueAsString(limitedItems);
            workspaceProps.setProperty("textHistory", json);
            saveWorkspaceProperties();
        } catch (Exception e) {
            logger.error("Error saving text history: {}", e.getMessage());
        }
    }

    public abstract Set<Dependency> getLiveDependencies();

    public abstract void saveLiveDependencies(Set<Path> dependencyTopLevelDirs);

    @Override
    public final List<String> loadTextHistory() {
        try {
            String json = workspaceProps.getProperty("textHistory");
            if (json != null && !json.isEmpty()) {
                List<String> result = objectMapper.readValue(
                        json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                logger.trace("Loaded {} history items", result.size());
                return result;
            }
        } catch (Exception e) {
            logger.error("Error loading text history: {}", e.getMessage(), e);
        }
        logger.trace("No text history found, returning empty list");
        return List.of();
    }

    @Override
    public final List<String> addToInstructionsHistory(String item, int maxItems) {
        if (item.trim().isEmpty()) {
            return loadTextHistory();
        }
        var history = new ArrayList<>(loadTextHistory());
        history.removeIf(i -> i.equals(item));
        history.addFirst(item);
        if (history.size() > maxItems) {
            history = new ArrayList<>(history.subList(0, maxItems));
        }
        saveTextHistory(history, maxItems);
        return history;
    }

    public final void saveWindowBounds(String key, JFrame window) {
        if (!window.isDisplayable()) {
            return;
        }
        try {
            var node = objectMapper.createObjectNode();
            node.put("x", window.getX());
            node.put("y", window.getY());
            node.put("width", window.getWidth());
            node.put("height", window.getHeight());
            logger.trace("Saving {} bounds as {}", key, node);
            workspaceProps.setProperty(key, objectMapper.writeValueAsString(node));
            saveWorkspaceProperties();
        } catch (Exception e) {
            logger.error("Error saving window bounds: {}", e.getMessage());
        }
    }

    public final Rectangle getWindowBounds(String key, int defaultWidth, int defaultHeight) {
        var result = new Rectangle(-1, -1, defaultWidth, defaultHeight);
        try {
            String json = workspaceProps.getProperty(key);
            logger.trace("Loading {} bounds from {}", key, json);
            if (json != null) {
                var node = objectMapper.readValue(json, ObjectNode.class);
                if (node.has("width") && node.has("height")) {
                    result.width = node.get("width").asInt();
                    result.height = node.get("height").asInt();
                }
                if (node.has("x") && node.has("y")) {
                    result.x = node.get("x").asInt();
                    result.y = node.get("y").asInt();
                }
            }
        } catch (Exception e) {
            logger.error("Error reading window bounds: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public final Optional<Rectangle> getMainWindowBounds() {
        var bounds = getWindowBounds("mainFrame", 0, 0);
        if (bounds.x == -1 && bounds.y == -1) {
            return Optional.empty();
        }
        return Optional.of(bounds);
    }

    @Override
    public final Rectangle getPreviewWindowBounds() {
        return getWindowBounds("previewFrame", 600, 400);
    }

    @Override
    public final Rectangle getDiffWindowBounds() {
        return getWindowBounds("diffFrame", 900, 600);
    }

    @Override
    public final Rectangle getOutputWindowBounds() {
        return getWindowBounds("outputFrame", 800, 600);
    }

    @Override
    public final void saveMainWindowBounds(JFrame window) {
        saveWindowBounds("mainFrame", window);
    }

    @Override
    public final void savePreviewWindowBounds(JFrame window) {
        saveWindowBounds("previewFrame", window);
    }

    @Override
    public final void saveDiffWindowBounds(JFrame frame) {
        saveWindowBounds("diffFrame", frame);
    }

    @Override
    public final void saveOutputWindowBounds(JFrame frame) {
        saveWindowBounds("outputFrame", frame);
    }

    @Override
    public final void saveHorizontalSplitPosition(int position) {
        if (position > 0) {
            workspaceProps.setProperty("horizontalSplitPosition", String.valueOf(position));
            saveWorkspaceProperties();
        }
    }

    @Override
    public final int getHorizontalSplitPosition() {
        try {
            String posStr = workspaceProps.getProperty("horizontalSplitPosition");
            return posStr != null ? Integer.parseInt(posStr) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Computes a context-aware fallback width for split pane positioning. */
    public final int computeContextualFallback(int frameWidth, boolean isWorktree) {
        if (isWorktree) {
            return Math.max(300, Math.min(600, frameWidth * 2 / 5));
        } else {
            return Math.max(250, Math.min(800, frameWidth * 3 / 10));
        }
    }

    /** Gets a safe horizontal split position that validates against frame dimensions. */
    public final int getSafeHorizontalSplitPosition(int frameWidth) {
        int saved = getHorizontalSplitPosition();

        if (saved > 0 && saved < frameWidth - 200) {
            return saved;
        }

        boolean isWorktree = this instanceof WorktreeProject;
        return computeContextualFallback(frameWidth, isWorktree);
    }

    @Override
    public final void saveRightVerticalSplitPosition(int position) {
        if (position > 0) {
            workspaceProps.setProperty("rightVerticalSplitPosition", String.valueOf(position));
            saveWorkspaceProperties();
        }
    }

    @Override
    public final int getRightVerticalSplitPosition() {
        try {
            String posStr = workspaceProps.getProperty("rightVerticalSplitPosition");
            return posStr != null ? Integer.parseInt(posStr) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public final void saveLeftVerticalSplitPosition(int position) {
        if (position > 0) {
            workspaceProps.setProperty("leftVerticalSplitPosition", String.valueOf(position));
            saveWorkspaceProperties();
        }
    }

    @Override
    public final int getLeftVerticalSplitPosition() {
        try {
            String posStr = workspaceProps.getProperty("leftVerticalSplitPosition");
            return posStr != null ? Integer.parseInt(posStr) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public final Optional<UUID> getLastActiveSession() {
        String sessionIdStr = workspaceProps.getProperty("lastActiveSession");
        if (sessionIdStr != null && !sessionIdStr.isBlank()) {
            try {
                return Optional.of(UUID.fromString(sessionIdStr));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid last active session UUID '{}' in workspace properties for {}", sessionIdStr, root);
            }
        }
        return Optional.empty();
    }

    public final void setLastActiveSession(UUID sessionId) {
        workspaceProps.setProperty("lastActiveSession", sessionId.toString());
        saveWorkspaceProperties();
    }

    private static final String PROP_JDK_HOME = "jdk.home";
    private static final String PROP_BUILD_LANGUAGE = "build.language";
    private static final String PROP_COMMAND_EXECUTOR = "commandExecutor";
    private static final String PROP_EXECUTOR_ARGS = "commandExecutorArgs";

    // Terminal drawer per-project persistence
    private static final String PROP_DRAWER_TERM_OPEN = "drawers.terminal.open";
    private static final String PROP_DRAWER_TERM_PROP = "drawers.terminal.proportion";
    private static final String PROP_DRAWER_TERM_LASTTAB = "drawers.terminal.lastTab";

    @Override
    public @Nullable String getJdk() {
        var value = workspaceProps.getProperty(PROP_JDK_HOME);
        if (value == null || value.isBlank()) {
            value = EnvironmentJava.detectJdk();
            setJdk(value);
        }
        return value;
    }

    @Override
    public void setJdk(@Nullable String jdkHome) {
        if (jdkHome == null || jdkHome.isBlank()) {
            workspaceProps.remove(PROP_JDK_HOME);
        } else {
            workspaceProps.setProperty(PROP_JDK_HOME, jdkHome);
        }
        saveWorkspaceProperties();
    }

    @Override
    public Language getBuildLanguage() {
        var configured = workspaceProps.getProperty(PROP_BUILD_LANGUAGE);
        if (configured != null && !configured.isBlank()) {
            try {
                return Languages.valueOf(configured);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid build language '{}' in workspace properties for {}", configured, root);
            }
        }
        return computeMostCommonLanguage();
    }

    @Override
    public void setBuildLanguage(@Nullable Language language) {
        if (language == null) {
            workspaceProps.remove(PROP_BUILD_LANGUAGE);
        } else {
            workspaceProps.setProperty(PROP_BUILD_LANGUAGE, language.name());
        }
        saveWorkspaceProperties();
    }

    @Override
    public @Nullable String getCommandExecutor() {
        return workspaceProps.getProperty(PROP_COMMAND_EXECUTOR);
    }

    @Override
    public void setCommandExecutor(@Nullable String executor) {
        if (executor == null || executor.isBlank()) {
            workspaceProps.remove(PROP_COMMAND_EXECUTOR);
        } else {
            workspaceProps.setProperty(PROP_COMMAND_EXECUTOR, executor);
        }
        saveWorkspaceProperties();
    }

    @Override
    public @Nullable String getExecutorArgs() {
        String args = workspaceProps.getProperty(PROP_EXECUTOR_ARGS);
        return (args == null || args.isBlank()) ? null : args;
    }

    @Override
    public void setExecutorArgs(@Nullable String args) {
        if (args == null || args.isBlank()) {
            workspaceProps.remove(PROP_EXECUTOR_ARGS);
        } else {
            workspaceProps.setProperty(PROP_EXECUTOR_ARGS, args);
        }
        saveWorkspaceProperties();
    }

    // --- Terminal drawer per-project persistence ---

    public @Nullable Boolean getTerminalDrawerOpen() {
        var raw = workspaceProps.getProperty(PROP_DRAWER_TERM_OPEN);
        if (raw == null || raw.isBlank()) return null;
        return Boolean.parseBoolean(raw.trim());
    }

    public void setTerminalDrawerOpen(boolean open) {
        workspaceProps.setProperty(PROP_DRAWER_TERM_OPEN, Boolean.toString(open));
        saveWorkspaceProperties();
    }

    public double getTerminalDrawerProportion() {
        var raw = workspaceProps.getProperty(PROP_DRAWER_TERM_PROP);
        if (raw == null || raw.isBlank()) return -1.0;
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception e) {
            return -1.0;
        }
    }

    public void setTerminalDrawerProportion(double prop) {
        var clamped = clampProportion(prop);
        workspaceProps.setProperty(PROP_DRAWER_TERM_PROP, Double.toString(clamped));
        saveWorkspaceProperties();
    }

    public @Nullable String getTerminalDrawerLastTab() {
        var raw = workspaceProps.getProperty(PROP_DRAWER_TERM_LASTTAB);
        if (raw == null || raw.isBlank()) return null;
        var norm = raw.trim().toLowerCase(Locale.ROOT);
        return ("terminal".equals(norm) || "tasks".equals(norm)) ? norm : null;
    }

    public void setTerminalDrawerLastTab(String tab) {
        var norm = tab.trim().toLowerCase(Locale.ROOT);
        if (!"terminal".equals(norm) && !"tasks".equals(norm)) {
            return;
        }
        workspaceProps.setProperty(PROP_DRAWER_TERM_LASTTAB, norm);
        saveWorkspaceProperties();
    }

    @Override
    public final Optional<String> getActionMode() {
        String mode = workspaceProps.getProperty("actionMode");
        if (mode != null && !mode.isEmpty()) {
            return Optional.of(mode);
        }
        return Optional.empty();
    }

    @Override
    public final void saveActionMode(String mode) {
        if (mode.isEmpty()) {
            workspaceProps.remove("actionMode");
        } else {
            workspaceProps.setProperty("actionMode", mode);
        }
        saveWorkspaceProperties();
    }

    private static double clampProportion(double p) {
        if (Double.isNaN(p) || Double.isInfinite(p)) return -1.0;
        if (p <= 0.0 || p >= 1.0) return -1.0;
        return Math.max(0.05, Math.min(0.95, p));
    }

    private Language computeMostCommonLanguage() {
        try {
            var counts = getAllFiles().stream()
                    .map(pf -> com.google.common.io.Files.getFileExtension(
                            pf.absPath().toString()))
                    .filter(ext -> !ext.isEmpty())
                    .map(Languages::fromExtension)
                    .filter(lang -> lang != Languages.NONE)
                    .collect(Collectors.groupingBy(lang -> lang, Collectors.counting()));

            return counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(Languages.NONE);
        } catch (Exception e) {
            logger.warn("Failed to compute most common language for {}: {}", root, e.getMessage());
            return Languages.NONE;
        }
    }

    @Override
    public void close() {
        if (repo instanceof AutoCloseable autoCloseableRepo) {
            try {
                autoCloseableRepo.close();
            } catch (Exception e) {
                logger.error("Error closing repo for project {}: {}", root, e.getMessage());
            }
        }
    }

    public Set<ProjectFile> getAllOnDiskDependencies() {
        var dependenciesPath = masterRootPathForConfig.resolve(BROKK_DIR).resolve(DEPENDENCIES_DIR);
        if (!Files.exists(dependenciesPath) || !Files.isDirectory(dependenciesPath)) {
            return Set.of();
        }
        try (var pathStream = Files.list(dependenciesPath)) {
            return pathStream
                    .filter(Files::isDirectory)
                    .map(path -> {
                        var relPath = masterRootPathForConfig.relativize(path);
                        return new ProjectFile(masterRootPathForConfig, relPath);
                    })
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            logger.error("Error loading dependency files from {}: {}", dependenciesPath, e.getMessage());
            return Set.of();
        }
    }

    /**
     * Determine the predominant language for a dependency directory by scanning files inside it.
     * This is shared between MainProject and WorktreeProject to avoid duplication.
     */
    protected static Language detectLanguageForDependency(ProjectFile depDir) {
        var counts = new IProject.Dependency(depDir, Languages.NONE)
                .files().stream()
                        .map(pf -> com.google.common.io.Files.getFileExtension(
                                pf.absPath().toString()))
                        .filter(ext -> !ext.isEmpty())
                        .map(Languages::fromExtension)
                        .filter(lang -> lang != Languages.NONE)
                        .collect(Collectors.groupingBy(lang -> lang, Collectors.counting()));

        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Languages.NONE);
    }

    @Nullable
    private volatile Set<ProjectFile> allFilesCache;

    private Set<ProjectFile> getAllFilesRaw() {
        var trackedFiles = repo.getTrackedFiles();

        var dependenciesPath = masterRootPathForConfig.resolve(BROKK_DIR).resolve(DEPENDENCIES_DIR);
        if (!Files.exists(dependenciesPath) || !Files.isDirectory(dependenciesPath)) {
            return trackedFiles;
        }

        var allFiles = new HashSet<>(trackedFiles);
        for (var live : getLiveDependencies()) {
            allFiles.addAll(live.files());
        }

        return allFiles;
    }

    @Override
    public final synchronized Set<ProjectFile> getAllFiles() {
        if (allFilesCache == null) {
            allFilesCache = applyFiltering(getAllFilesRaw());
        }
        return allFilesCache;
    }

    private String toGitRelativePath(GitRepo gitRepo, Path projectRelPath) {
        Path gitTopLevel = gitRepo.getGitTopLevel();
        Path projectAbsPath = root.resolve(projectRelPath);
        Path gitRelPath = gitTopLevel.relativize(projectAbsPath);
        return toUnixPath(gitRelPath);
    }

    /**
     * Converts a Path to Unix-style path string for gitignore matching.
     * All gitignore patterns use forward slashes, even on Windows.
     * JGit's IgnoreNode.isIgnored() requires Unix-style paths.
     *
     * @param path The path to normalize
     * @return Unix-style path string (forward slashes)
     */
    private static String toUnixPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
     * Normalizes a path to Unix-style separators for gitignore matching.
     * Computes the path relative to a gitignore directory and converts to
     * the format expected by JGit's IgnoreNode.isIgnored().
     *
     * CRITICAL CONTRACT:
     * - JGit's IgnoreNode.isIgnored() expects Unix-style paths (forward slashes)
     * - All paths must be relative to the .gitignore file's directory
     * - Root .gitignore uses empty Path ("") as directory reference
     * - Empty paths are handled correctly (empty string, not "." or null)
     *
     * @param gitignoreDir The directory containing the .gitignore file (empty Path for root)
     * @param pathToNormalize The path to normalize (git-relative)
     * @return Unix-style path string relative to gitignoreDir
     */
    private static String normalizePathForGitignore(Path gitignoreDir, Path pathToNormalize) {
        if (gitignoreDir.toString().isEmpty()) {
            // Root .gitignore - use full path as-is
            return toUnixPath(pathToNormalize);
        } else {
            // Nested .gitignore - compute relative path and normalize
            return toUnixPath(gitignoreDir.relativize(pathToNormalize));
        }
    }

    /**
     * Gets the global gitignore file path using JGit's configuration APIs.
     * Checks in order:
     * 1. core.excludesfile from git config (with tilde expansion)
     * 2. XDG standard location: ~/.config/git/ignore
     * 3. Legacy location: ~/.gitignore_global
     *
     * @param gitRepo The git repository
     * @return Optional containing the path to the global gitignore file, or empty if not found
     */
    private Optional<Path> getGlobalGitignoreFile(GitRepo gitRepo) {
        try {
            // Use JGit's Repository config
            var config = gitRepo.getGit().getRepository().getConfig();

            // Check core.excludesfile setting
            String configPath = config.getString("core", null, "excludesfile");
            if (configPath != null && !configPath.isEmpty()) {
                // Use JGit's FS to resolve paths (handles ~/ expansion properly)
                var fs = FS.DETECTED;
                Path globalIgnore;

                if (configPath.startsWith("~/")) {
                    // JGit pattern: resolve relative to user home
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

            // Fallback to XDG standard location: ~/.config/git/ignore
            File userHome = FS.DETECTED.userHome();
            Path xdgIgnore = userHome.toPath().resolve(".config/git/ignore");
            if (Files.exists(xdgIgnore)) {
                logger.debug("Using global gitignore from XDG location: {}", xdgIgnore);
                return Optional.of(xdgIgnore);
            }

            // Fallback to legacy location: ~/.gitignore_global
            Path legacyIgnore = userHome.toPath().resolve(".gitignore_global");
            if (Files.exists(legacyIgnore)) {
                logger.debug("Using global gitignore from legacy location: {}", legacyIgnore);
                return Optional.of(legacyIgnore);
            }

            return Optional.empty();
        } catch (Exception e) {
            logger.debug("Error reading global gitignore config: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Computes the fixed gitignore pairs that apply to all paths in the repository.
     * These are computed once per filtering operation for efficiency.
     *
     * @param gitRepo The git repository
     * @param gitTopLevel The top level of the git repository
     * @return List of fixed gitignore pairs (global, .git/info/exclude, root .gitignore)
     */
    private List<Map.Entry<Path, Path>> computeFixedGitignorePairs(GitRepo gitRepo, Path gitTopLevel) {
        var fixedPairs = new ArrayList<Map.Entry<Path, Path>>();

        // Add global gitignore (lowest precedence)
        getGlobalGitignoreFile(gitRepo).ifPresent(globalIgnore -> {
            fixedPairs.add(Map.entry(Path.of(""), globalIgnore));
        });

        // Add .git/info/exclude
        var gitInfoExclude = gitTopLevel.resolve(".git/info/exclude");
        if (Files.exists(gitInfoExclude)) {
            fixedPairs.add(Map.entry(Path.of(""), gitInfoExclude));
        }

        // Add root .gitignore
        var rootGitignore = gitTopLevel.resolve(".gitignore");
        if (Files.exists(rootGitignore)) {
            fixedPairs.add(Map.entry(Path.of(""), rootGitignore));
        }

        return fixedPairs;
    }

    private MatchResult checkIgnoreFile(Path ignoreFile, String pathToCheck, boolean isDirectory) throws IOException {
        var ignoreNode = ignoreNodeCache.computeIfAbsent(ignoreFile, path -> {
            try {
                var node = new IgnoreNode();
                try (var inputStream = Files.newInputStream(path)) {
                    node.parse(inputStream);
                }
                return node;
            } catch (IOException e) {
                logger.debug("Error parsing gitignore file {}: {}", path, e.getMessage());
                return new IgnoreNode(); // Return empty node on error
            }
        });
        return ignoreNode.isIgnored(pathToCheck, isDirectory);
    }

    /**
     * Collects all relevant gitignore files for a given path in precedence order.
     * Returns a list of (directory, gitignore-file) pairs where directory is the path
     * that the gitignore file's patterns are relative to.
     *
     * @param gitTopLevel The top level of the git repository
     * @param gitRelPath The path to check (relative to git top level)
     * @param fixedGitignorePairs Precomputed fixed gitignore pairs (global, .git/info/exclude, root .gitignore)
     * @return List of (directory, gitignore-file) pairs in precedence order (lowest to highest)
     */
    private List<Map.Entry<Path, Path>> collectGitignorePairs(
            Path gitTopLevel, Path gitRelPath, List<Map.Entry<Path, Path>> fixedGitignorePairs) {

        // Get the directory for this path (use empty path for files at root)
        Path directory = gitRelPath.getParent();
        if (directory == null) {
            directory = Path.of("");
        }

        // Check cache first - significant performance optimization
        // Avoids repeated Files.exists() calls for files in the same directory
        var cached = gitignoreChainCache.get(directory);
        if (cached != null) {
            return cached;
        }

        // Cache miss - compute the gitignore chain for this directory
        var gitignorePairs = new ArrayList<Map.Entry<Path, Path>>();

        // Add precomputed fixed pairs (global, .git/info/exclude, root .gitignore)
        gitignorePairs.addAll(fixedGitignorePairs);

        // Add nested .gitignore files for each directory in the path
        // Collect from farthest ancestor down to immediate parent to maintain correct precedence
        var nestedGitignores = new ArrayList<Map.Entry<Path, Path>>();
        Path currentDir = directory;
        while (currentDir != null && !currentDir.toString().isEmpty()) {
            var nestedGitignore = gitTopLevel.resolve(currentDir).resolve(".gitignore");
            if (Files.exists(nestedGitignore)) {
                nestedGitignores.add(Map.entry(currentDir, nestedGitignore));
            }
            currentDir = currentDir.getParent();
        }
        // Reverse so farthest ancestor is added first (lowest precedence)
        // and closest parent is added last (highest precedence)
        Collections.reverse(nestedGitignores);
        gitignorePairs.addAll(nestedGitignores);

        // Store in cache for future files in the same directory
        gitignoreChainCache.put(directory, gitignorePairs);

        return gitignorePairs;
    }

    /**
     * Checks if a path is ignored by gitignore rules using JGit's IgnoreNode.
     * This approach correctly handles:
     * - Global gitignore files (from core.excludesfile config or XDG location)
     * - .git/info/exclude (local, non-committed ignore rules)
     * - Root and nested .gitignore files
     * - Negation patterns (e.g., !build/keep/)
     * - Directory-specific patterns
     * - Proper precedence (more specific .gitignore files override less specific ones)
     *
     * The key insight: each .gitignore file's patterns are relative to the directory
     * containing that .gitignore. So subdir/.gitignore with pattern "build/*" matches
     * "build/Generated.java" (relative to subdir), not "subdir/build/Generated.java".
     *
     * Git's precedence order (lowest to highest):
     * 1. Global gitignore (core.excludesfile or ~/.config/git/ignore)
     * 2. .git/info/exclude
     * 3. Root .gitignore
     * 4. Nested .gitignore files (closer to file = higher precedence)
     *
     * @param gitRepo The git repository
     * @param projectRelPath Path relative to project root
     * @param fixedGitignorePairs Precomputed fixed gitignore pairs (global, .git/info/exclude, root .gitignore)
     * @return true if the path is ignored by gitignore rules
     */
    private boolean isPathIgnored(GitRepo gitRepo, Path projectRelPath, List<Map.Entry<Path, Path>> fixedGitignorePairs)
            throws IOException {
        String gitRelPath = toGitRelativePath(gitRepo, projectRelPath);
        Path gitRelPathObj = Path.of(gitRelPath);
        var gitTopLevel = gitRepo.getGitTopLevel();

        // Check if path is a directory (needed for IgnoreNode)
        Path absPath = root.resolve(projectRelPath);
        boolean isDirectory = Files.isDirectory(absPath);

        // Collect all gitignore files that apply to this path
        var gitignorePairs = collectGitignorePairs(gitTopLevel, gitRelPathObj, fixedGitignorePairs);

        // Check each ignore file from lowest to highest precedence
        // Later (higher precedence) files override earlier ones
        MatchResult finalResult = MatchResult.CHECK_PARENT;

        for (var entry : gitignorePairs) {
            var gitignoreDir = entry.getKey();
            var gitignoreFile = entry.getValue();

            // Calculate path relative to this .gitignore's directory
            String relativeToGitignoreDir = normalizePathForGitignore(gitignoreDir, gitRelPathObj);

            var result = checkIgnoreFile(gitignoreFile, relativeToGitignoreDir, isDirectory);

            // If this .gitignore has an explicit match (IGNORED or NOT_IGNORED), it overrides previous results
            if (result == MatchResult.IGNORED) {
                finalResult = MatchResult.IGNORED;
            } else if (result == MatchResult.NOT_IGNORED) {
                finalResult = MatchResult.NOT_IGNORED;
            }
            // If CHECK_PARENT, keep current finalResult
        }

        // Handle the final result
        if (finalResult == MatchResult.IGNORED) {
            logger.trace("Path {} (isDir: {}) ignored: true (result: IGNORED)", gitRelPath, isDirectory);
            return true;
        }

        // IMPORTANT: Git's rule - "It is not possible to re-include a file if a parent directory is excluded"
        // Example: build/ + !build/app.java -> app.java is STILL IGNORED because build/ is ignored

        Path parent = gitRelPathObj.getParent();
        while (parent != null && !parent.toString().isEmpty()) {
            String parentPath = toUnixPath(parent);

            // Collect all gitignore files that apply to this parent path
            var parentGitignorePairs = collectGitignorePairs(gitTopLevel, parent, fixedGitignorePairs);

            // Check parent against all .gitignore files
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

    private boolean isBaselineExcluded(ProjectFile file, Set<String> baselineExclusions) {
        return baselineExclusions.stream().anyMatch(exclusion -> {
            var filePath = file.getRelPath();
            var exclusionPath = Path.of(exclusion);
            return filePath.equals(exclusionPath) || filePath.startsWith(exclusionPath);
        });
    }

    /**
     * Applies gitignore and baseline filtering to the given set of files.
     * Uses manual IgnoreNode parsing to provide comprehensive ignore support including
     * nested .gitignore files, global ignores, and .git/info/exclude.
     *
     * Note: We use manual IgnoreNode parsing instead of JGit's WorkingTreeIterator
     * because WorkingTreeIterator is designed for tree walking/discovery, not for
     * efficiently filtering a pre-existing list of files. Using WorkingTreeIterator
     * would require creating thousands of TreeWalk instances or walking the entire
     * repository tree, both of which are significantly slower than direct path checking.
     *
     * @param files The raw set of files to filter
     * @return Filtered set of files that are not ignored by gitignore or baseline exclusions
     */
    protected Set<ProjectFile> applyFiltering(Set<ProjectFile> files) {
        if (!(repo instanceof GitRepo gitRepo)) {
            return files;
        }

        try {
            // Precompute fixed gitignore pairs once for all files (performance optimization)
            var gitTopLevel = gitRepo.getGitTopLevel();
            var workTreeRoot = gitRepo.getWorkTreeRoot();

            // Check if project root is under git working tree
            if (!root.startsWith(workTreeRoot)) {
                logger.warn(
                        "Project root {} is outside git working tree {}; gitignore filtering skipped",
                        root,
                        workTreeRoot);
                return files; // Return all files unfiltered
            }

            var fixedGitignorePairs = computeFixedGitignorePairs(gitRepo, gitTopLevel);

            var baselineExclusions = loadBuildDetails().excludedDirectories().stream()
                    .map(s -> s.replace('\\', '/').trim())
                    .map(s -> s.startsWith("./") ? s.substring(2) : s)
                    .map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());

            return files.stream()
                    .filter(file -> !isBaselineExcluded(file, baselineExclusions))
                    .filter(file -> {
                        try {
                            // do not filter out deps
                            var isDep = file.getRelPath()
                                    .startsWith(Path.of(BROKK_DIR).resolve(DEPENDENCIES_DIR));
                            return isDep || !isPathIgnored(gitRepo, file.getRelPath(), fixedGitignorePairs);
                        } catch (IOException e) {
                            logger.warn(
                                    "Error checking if path {} is ignored, including it: {}",
                                    file.getRelPath(),
                                    e.getMessage());
                            return true; // Include file if we can't determine if it's ignored
                        }
                    })
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            logger.warn("Error applying gitignore filtering, returning all files: {}", e.getMessage());
            return files;
        }
    }

    @Override
    public final synchronized void invalidateAllFiles() {
        allFilesCache = null;
        ignoreNodeCache.clear();
        gitignoreChainCache.clear();
    }

    /**
     * Checks if a directory is ignored by gitignore rules.
     * This is used by BuildAgent to identify excluded directories for LLM context.
     * Uses explicit gitignore validation with isDirectory=true rather than inferring from absence.
     *
     * @param directoryRelPath Path relative to project root
     * @return true if the directory is ignored by gitignore rules, false otherwise
     */
    @Override
    public boolean isDirectoryIgnored(Path directoryRelPath) {
        if (!(repo instanceof GitRepo gitRepo)) {
            return false; // No git repo = nothing is ignored
        }

        try {
            var gitTopLevel = gitRepo.getGitTopLevel();
            var workTreeRoot = gitRepo.getWorkTreeRoot();

            // Check if project root is under git working tree
            if (!root.startsWith(workTreeRoot)) {
                return false; // Project outside working tree = no gitignore filtering
            }

            var fixedGitignorePairs = computeFixedGitignorePairs(gitRepo, gitTopLevel);

            // Check if directory is ignored (isPathIgnored will use Files.isDirectory internally)
            return isPathIgnored(gitRepo, directoryRelPath, fixedGitignorePairs);
        } catch (Exception e) {
            logger.warn("Error checking if directory {} is ignored: {}", directoryRelPath, e.getMessage());
            return false; // On error, assume not ignored (conservative)
        }
    }

    /**
     * Gets the path to the global gitignore file if one is configured and exists.
     * This is used by the file watcher to monitor changes to the global gitignore.
     *
     * @return Optional containing the path to the global gitignore file, or empty if not found
     */
    public Optional<Path> getGlobalGitignorePath() {
        if (!(repo instanceof GitRepo gitRepo)) {
            return Optional.empty();
        }
        return getGlobalGitignoreFile(gitRepo);
    }

    @Override
    public Set<String> getExcludedDirectories() {
        var exclusions = new HashSet<String>();
        exclusions.addAll(loadBuildDetails().excludedDirectories());

        var dependenciesDir = masterRootPathForConfig.resolve(BROKK_DIR).resolve(DEPENDENCIES_DIR);
        if (!Files.exists(dependenciesDir) || !Files.isDirectory(dependenciesDir)) {
            return exclusions;
        }

        var liveDependencyPaths =
                getLiveDependencies().stream().map(d -> d.root().absPath()).collect(Collectors.toSet());

        try (var pathStream = Files.list(dependenciesDir)) {
            pathStream
                    .filter(Files::isDirectory)
                    .filter(path -> !liveDependencyPaths.contains(path))
                    .forEach(path -> {
                        var relPath = masterRootPathForConfig.relativize(path).toString();
                        exclusions.add(relPath);
                    });
        } catch (IOException e) {
            logger.error("Error loading excluded dependency directories from {}: {}", dependenciesDir, e.getMessage());
        }

        return exclusions;
    }
}
