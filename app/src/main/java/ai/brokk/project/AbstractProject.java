package ai.brokk.project;

import ai.brokk.IAnalyzerWrapper;
import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.AtomicWrites;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.git.IGitRepo;
import ai.brokk.git.LocalFileRepo;
import ai.brokk.util.EnvironmentJava;
import ai.brokk.util.PathNormalizer;
import ai.brokk.util.ProjectBuildRunner;
import ai.brokk.util.ShellConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.swing.JFrame;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
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
    public static final String DEBUG_LOG_FILE = "debug.log";
    protected static final String LIVE_DEPENDENCIES_KEY = "liveDependencies";

    protected final Path root;
    protected final IGitRepo repo;
    protected final Path workspacePropertiesFile;
    protected final Properties workspaceProps;
    protected final Path masterRootPathForConfig;
    protected final Path propertiesFile;
    protected final Properties projectProps;
    protected final ProjectBuildRunner buildRunner;

    // File filtering service that encapsulates baseline exclusions + gitignore handling.
    protected final FileFilteringService fileFilteringService;
    private static final String BUILD_DETAILS_KEY = "buildDetailsJson";

    private volatile CompletableFuture<BuildAgent.BuildDetails> detailsFuture = new CompletableFuture<>();

    // Cached pattern matcher for file exclusions (invalidated when patterns change)
    // Using volatile for thread-safe reads without synchronization
    private volatile Set<String> cachedPatternSet = Set.of();
    private volatile FileFilteringService.FilePatternMatcher cachedPatternMatcher =
            FileFilteringService.createPatternMatcher(Set.of());

    public AbstractProject(Path root) {
        this(root.toAbsolutePath().normalize(), null, null);
    }

    protected AbstractProject(Path root, Path masterRootPathForConfig) {
        this(
                root.toAbsolutePath().normalize(),
                masterRootPathForConfig.toAbsolutePath().normalize(),
                null);
    }

    /**
     * Master constructor.
     * Assumes root and masterRootPathForConfig (if provided) are already absolute and normalized.
     */
    protected AbstractProject(Path root, @Nullable Path masterRootPathForConfig, @Nullable IGitRepo repo) {
        assert root.isAbsolute() && root.equals(root.normalize()) : "Root must be absolute and normalized: " + root;
        if (masterRootPathForConfig != null) {
            assert masterRootPathForConfig.isAbsolute()
                            && masterRootPathForConfig.equals(masterRootPathForConfig.normalize())
                    : "Master root must be absolute and normalized: " + masterRootPathForConfig;
        }

        this.root = root;
        this.repo = repo != null
                ? repo
                : (GitRepoFactory.hasGitRepo(this.root) ? new GitRepo(this.root) : new LocalFileRepo(this.root));
        this.masterRootPathForConfig = masterRootPathForConfig != null
                ? masterRootPathForConfig
                : computeMasterRootForConfig(this.root, this.repo);

        this.workspacePropertiesFile = this.root.resolve(BROKK_DIR).resolve(WORKSPACE_PROPERTIES_FILE);
        this.workspaceProps = new Properties();
        this.propertiesFile = this.root.resolve(BROKK_DIR).resolve(PROJECT_PROPERTIES_FILE);
        this.projectProps = new Properties();
        this.fileFilteringService = new FileFilteringService(this.root, this.repo);
        this.buildRunner = new ProjectBuildRunner(this);

        initializeProject();
        initializeProjectProperties();
        initializeBuildDetails();
    }

    private void initializeProjectProperties() {
        try {
            if (Files.exists(propertiesFile)) {
                try (var reader = Files.newBufferedReader(propertiesFile)) {
                    projectProps.load(reader);
                }
            }
        } catch (IOException e) {
            logger.error("Error loading project properties from {}: {}", propertiesFile, e.getMessage());
            projectProps.clear();
        }
    }

    private void initializeBuildDetails() {
        var bdOpt = loadBuildDetails();
        if (bdOpt.isPresent()) {
            this.detailsFuture.complete(bdOpt.get());
        } else {
            this.detailsFuture.complete(BuildAgent.BuildDetails.EMPTY);
        }
    }

    private static Path computeMasterRootForConfig(Path normalizedRoot, IGitRepo repo) {
        if (repo instanceof GitRepo gitRepo && gitRepo.isWorktree()) {
            return gitRepo.getGitTopLevel().toAbsolutePath().normalize();
        }
        return normalizedRoot;
    }

    private void initializeProject() {
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
    public ProjectBuildRunner getBuildRunner() {
        return buildRunner;
    }

    @Override
    public final boolean hasGit() {
        return repo instanceof GitRepo;
    }

    /** Re-reads workspace properties from disk, picking up changes made by other processes. */
    @Override
    public final void reloadWorkspaceProperties() {
        if (Files.exists(workspacePropertiesFile)) {
            try (var reader = Files.newBufferedReader(workspacePropertiesFile)) {
                workspaceProps.clear();
                workspaceProps.load(reader);
            } catch (Exception e) {
                logger.error(
                        "Error reloading workspace properties from {}: {}", workspacePropertiesFile, e.getMessage());
            }
        }
    }

    /** Saves workspace-specific properties (window positions, etc.) */
    public final void saveWorkspaceProperties() {
        saveProperties(workspacePropertiesFile, workspaceProps, "Brokk workspace configuration");
    }

    /** Generic method to save properties to a file */
    protected static void saveProperties(Path file, Properties properties, String comment) {
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
            Files.createDirectories(file.getParent());
            AtomicWrites.save(file, properties, comment);
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

    @Override
    public CompletableFuture<Void> updateLiveDependencies(
            Set<Path> newLiveDependencyDirs, @Nullable IAnalyzerWrapper analyzerWrapper) {
        return LoggingFuture.supplyAsync(() -> {
            // If analyzer provided, pause watcher and compute prev files
            Set<ProjectFile> prevFiles = null;
            if (analyzerWrapper != null) {
                analyzerWrapper.pause();
                prevFiles = new HashSet<>();
                for (var d : getLiveDependencies()) {
                    prevFiles.addAll(d.files());
                }
            }

            // Invalidate auto-detected languages cache to force re-detection including new dependencies
            // This preserves any explicit user configuration while ensuring new dependencies are considered
            invalidateAutoDetectedLanguages();

            // Always persist
            saveLiveDependencies(newLiveDependencyDirs);

            // If analyzer provided, compute diff and update
            if (analyzerWrapper != null) {
                var nextFiles = new HashSet<ProjectFile>();
                for (var d : getLiveDependencies()) {
                    nextFiles.addAll(d.files());
                }

                // Symmetric difference: files that changed (added or removed)
                var changedFiles = new HashSet<>(nextFiles);
                changedFiles.removeAll(prevFiles);
                var removedFiles = new HashSet<>(prevFiles);
                removedFiles.removeAll(nextFiles);
                changedFiles.addAll(removedFiles);

                if (!changedFiles.isEmpty()) {
                    try {
                        analyzerWrapper.updateFiles(changedFiles).get();
                    } catch (Exception e) {
                        logger.error("Error updating analyzer with dependency changes", e);
                    }
                }

                analyzerWrapper.resume();
            }

            return null;
        });
    }

    @Override
    public abstract Set<Dependency> getLiveDependencies();

    @Override
    public abstract void saveLiveDependencies(Set<Path> dependencyTopLevelDirs);

    @Override
    public CompletableFuture<Void> addLiveDependency(
            String dependencyName, @Nullable IAnalyzerWrapper analyzerWrapper) {
        // Build new live set = current live deps + new dependency
        var liveDependencyTopLevelDirs = new HashSet<Path>();
        for (var dep : getLiveDependencies()) {
            liveDependencyTopLevelDirs.add(dep.root().absPath());
        }
        var newDepDir = masterRootPathForConfig
                .resolve(BROKK_DIR)
                .resolve(DEPENDENCIES_DIR)
                .resolve(dependencyName);
        liveDependencyTopLevelDirs.add(newDepDir);

        return updateLiveDependencies(liveDependencyTopLevelDirs, analyzerWrapper);
    }

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
        history.add(0, item);
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

        boolean isWorktree = (Object) this instanceof WorktreeProject;
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
    public boolean hasJdkOverride() {
        var value = workspaceProps.getProperty(PROP_JDK_HOME);
        return value != null && !value.isBlank();
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
    public ShellConfig getShellConfig() {
        String executor = workspaceProps.getProperty(PROP_COMMAND_EXECUTOR);
        String argsStr = workspaceProps.getProperty(PROP_EXECUTOR_ARGS);
        return ShellConfig.fromConfigsOrDefault(executor, argsStr);
    }

    @Override
    public void setShellConfig(@Nullable ShellConfig config) {
        if (config == null) {
            workspaceProps.remove(PROP_COMMAND_EXECUTOR);
            workspaceProps.remove(PROP_EXECUTOR_ARGS);
        } else {
            workspaceProps.setProperty(PROP_COMMAND_EXECUTOR, config.executable());
            workspaceProps.setProperty(PROP_EXECUTOR_ARGS, String.join(" ", config.args()));
        }
        saveWorkspaceProperties();
    }

    // --- UI Filter persistence ---

    private static final String UI_FILTER_PREFIX = "ui.filter.";

    @Override
    public @Nullable String getUiFilterProperty(String key) {
        return workspaceProps.getProperty(UI_FILTER_PREFIX + key);
    }

    @Override
    public void setUiFilterProperty(String key, @Nullable String value) {
        if (value == null || value.isEmpty()) {
            workspaceProps.remove(UI_FILTER_PREFIX + key);
        } else {
            workspaceProps.setProperty(UI_FILTER_PREFIX + key, value);
        }
        saveWorkspaceProperties();
    }

    // --- Project Tree expansion persistence ---

    private static final String PROP_TREE_EXPANDED = "ui.projectTree.expandedPaths";

    @Override
    public List<Path> getExpandedTreePaths() {
        var json = workspaceProps.getProperty(PROP_TREE_EXPANDED);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> pathStrings = objectMapper.readValue(
                    json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            // Filter out empty/blank paths and normalize separators for cross-platform compatibility
            return pathStrings.stream()
                    .filter(s -> !s.isBlank())
                    .map(s -> s.replace('\\', '/'))
                    .map(Path::of)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("Error parsing expanded tree paths: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void setExpandedTreePaths(List<Path> paths) {
        try {
            // Filter out empty/blank paths and normalize to forward slashes for cross-platform compatibility
            var pathStrings = paths.stream()
                    .map(p -> PathNormalizer.canonicalizeForProject(p.toString(), getRoot()))
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());
            var json = objectMapper.writeValueAsString(pathStrings);
            workspaceProps.setProperty(PROP_TREE_EXPANDED, json);
            saveWorkspaceProperties();
        } catch (Exception e) {
            logger.error("Error saving expanded tree paths: {}", e.getMessage());
        }
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

    /** Marks onboarding as completed so dialogs won't show again. */
    public final void markOnboardingCompleted() {
        workspaceProps.setProperty("onboardingCompleted", "true");
        saveWorkspaceProperties();
    }

    private static double clampProportion(double p) {
        if (Double.isNaN(p) || Double.isInfinite(p)) return -1.0;
        if (p <= 0.0 || p >= 1.0) return -1.0;
        return Math.max(0.05, Math.min(0.95, p));
    }

    @Override
    @Blocking
    public boolean isEmptyProject() {
        Set<String> analyzableExtensions = Languages.ALL_LANGUAGES.stream()
                .filter(lang -> lang != Languages.NONE)
                .flatMap(lang -> lang.getExtensions().stream())
                .collect(Collectors.toSet());

        return getAllFiles().stream().map(ProjectFile::extension).noneMatch(analyzableExtensions::contains);
    }

    @Override
    public void close() {
        buildRunner.close();
        if (repo instanceof AutoCloseable autoCloseableRepo) {
            try {
                autoCloseableRepo.close();
            } catch (Exception e) {
                logger.error("Error closing repo for project {}: {}", root, e.getMessage());
            }
        }
    }

    @Override
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

    @Nullable
    private volatile Map<Path, ProjectFile> filesByRelPathCache;

    private Set<ProjectFile> getAllFilesRaw() {
        // Use getFilesForAnalysis() which handles fallback to filesystem scan for empty Git repos
        var trackedFiles = repo.getFilesForAnalysis();

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
    @Blocking
    public final synchronized Set<ProjectFile> getAllFiles() {
        if (filesByRelPathCache == null) {
            var files = filterExcludedFiles(getAllFilesRaw());
            filesByRelPathCache = files.stream()
                    .collect(Collectors.toMap(ProjectFile::getRelPath, f -> f, (existing, replacement) -> existing));
        }
        return Set.copyOf(filesByRelPathCache.values());
    }

    @Override
    @Blocking
    public final synchronized Optional<ProjectFile> getFileByRelPath(Path relPath) {
        if (filesByRelPathCache == null) {
            getAllFiles(); // Populate the cache (this uses a side effect, so linter won't see this)
        }
        // The below is to keep the linter happy
        var match = filesByRelPathCache != null ? filesByRelPathCache.get(relPath) : null;
        return Optional.ofNullable(match);
    }

    @Override
    public Set<ProjectFile> filterExcludedFiles(Set<ProjectFile> files) {
        var buildDetails = awaitBuildDetails();
        return fileFilteringService.filterFiles(files, buildDetails.exclusionPatterns());
    }

    @Override
    public synchronized void invalidateAllFiles() {
        filesByRelPathCache = null;
        try {
            fileFilteringService.invalidateCaches();
        } catch (Exception e) {
            logger.debug("Error invalidating file filtering caches: {}", e.getMessage());
        }
    }

    @Override
    public boolean isGitignored(Path relPath) {
        if (!(repo instanceof GitRepo)) {
            return false; // No git repo = nothing is ignored
        }

        try {
            return fileFilteringService.isGitignored(relPath);
        } catch (Exception e) {
            logger.warn("Error checking if path {} is gitignored: {}", relPath, e.getMessage());
            return false; // On error, assume not ignored (conservative)
        }
    }

    @Override
    public boolean isGitignored(Path relPath, boolean isDirectory) {
        if (!(repo instanceof GitRepo)) {
            return false;
        }

        try {
            return fileFilteringService.isGitignored(relPath, isDirectory);
        } catch (Exception e) {
            logger.warn("Error checking if path {} is gitignored: {}", relPath, e.getMessage());
            return false;
        }
    }

    public Optional<Path> getGlobalGitignorePath() {
        if (!(repo instanceof GitRepo)) {
            return Optional.empty();
        }
        return fileFilteringService.getGlobalGitignorePath();
    }

    @Override
    public Set<String> getExclusionPatterns() {
        var exclusions = new HashSet<String>();
        exclusions.addAll(awaitBuildDetails().exclusionPatterns());

        // Also exclude non-live dependencies
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

    @Override
    public boolean isPathExcluded(String relativePath, boolean isDirectory) {
        var patterns = getExclusionPatterns();
        // Read volatile fields once for consistency
        var currentPatterns = cachedPatternSet;
        var currentMatcher = cachedPatternMatcher;
        // Check if cache is still valid; if not, update (benign race: might compute twice)
        if (!patterns.equals(currentPatterns)) {
            currentMatcher = FileFilteringService.createPatternMatcher(patterns);
            cachedPatternMatcher = currentMatcher;
            cachedPatternSet = patterns;
        }
        return currentMatcher.isPathExcluded(relativePath, isDirectory);
    }

    @Override
    public boolean hasBuildDetails() {
        return detailsFuture.isDone();
    }

    @Override
    public Optional<BuildAgent.BuildDetails> loadBuildDetails() {
        String json = projectProps.getProperty(BUILD_DETAILS_KEY);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }

        try {
            var details = objectMapper.readValue(json, BuildAgent.BuildDetails.class);

            // Canonicalize exclusion patterns
            var canonicalExclusions = PathNormalizer.canonicalizeExclusionPatterns(
                    details.exclusionPatterns(), getMasterRootPathForConfig());

            // Normalize environment variables and migrate JAVA_HOME to workspace properties
            Map<String, String> envIn = details.environmentVariables();
            Map<String, String> canonicalEnv = new LinkedHashMap<>(envIn.size());

            for (Map.Entry<String, String> e : envIn.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (v == null) {
                    continue;
                }
                if ("JAVA_HOME".equalsIgnoreCase(k)) {
                    // Migration: Move JAVA_HOME from project build details to workspace properties
                    String canonicalPath = PathNormalizer.canonicalizeEnvPathValue(v);
                    if (!canonicalPath.isBlank()) {
                        setJdk(canonicalPath);
                        logger.info("Migrated JAVA_HOME from project build details to workspace JDK settings.");
                    }
                } else {
                    canonicalEnv.put(k, v);
                }
            }

            // Return a re-wrapped BuildDetails with canonicalized content
            return Optional.of(new BuildAgent.BuildDetails(
                    details.buildLintCommand(),
                    details.buildLintEnabled(),
                    details.testAllCommand(),
                    details.testAllEnabled(),
                    details.testSomeCommand(),
                    details.testSomeEnabled(),
                    canonicalExclusions,
                    canonicalEnv,
                    details.maxBuildAttempts(),
                    details.afterTaskListCommand(),
                    details.modules()));
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize BuildDetails from JSON: {}", json, e);
        }
        return Optional.empty();
    }

    @Override
    public void saveBuildDetails(BuildAgent.BuildDetails details) {
        // Build canonical details for stable on-disk representation
        // 1) Canonicalize exclusion patterns that look like paths
        var canonicalExclusions = new LinkedHashSet<String>();
        for (String pattern : details.exclusionPatterns()) {
            // Only canonicalize patterns that look like directory paths (contain / or \\)
            if (pattern.contains("/") || pattern.contains("\\")) {
                String c = PathNormalizer.canonicalizeForProject(pattern, getMasterRootPathForConfig());
                if (!c.isBlank()) {
                    canonicalExclusions.add(c);
                }
            } else {
                canonicalExclusions.add(pattern);
            }
        }

        // 2) Normalize environment variables.
        // Omit JAVA_HOME from project-scoped storage as it is persisted in workspace properties.
        Map<String, String> envIn = details.environmentVariables();
        Map<String, String> canonicalEnv = new LinkedHashMap<>(envIn.size());
        for (Map.Entry<String, String> e : envIn.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (v == null || "JAVA_HOME".equalsIgnoreCase(k)) {
                continue;
            }
            canonicalEnv.put(k, v);
        }

        var canonicalDetails = new BuildAgent.BuildDetails(
                details.buildLintCommand(),
                details.buildLintEnabled(),
                details.testAllCommand(),
                details.testAllEnabled(),
                details.testSomeCommand(),
                details.testSomeEnabled(),
                canonicalExclusions,
                canonicalEnv,
                details.maxBuildAttempts(),
                details.afterTaskListCommand(),
                details.modules());

        try {
            String json = objectMapper.writeValueAsString(canonicalDetails);
            projectProps.setProperty(BUILD_DETAILS_KEY, json);
            logger.debug("Saving build details to project properties.");
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        saveProjectProperties();
        setBuildDetails(canonicalDetails);
        invalidateAllFiles();
    }

    /**
     * Used by BrokkCli to override build details; deliberately does not save
     */
    @Override
    public void setBuildDetails(BuildAgent.BuildDetails details) {
        // not threadsafe, that's okay;
        // the only caller (outside of tests) does so during construction before anyone else can see it
        if (detailsFuture.isDone()) {
            // existing Future completed with an unknown value; overwrite it with ours
            // (again: we don't care about potential references to the old Future; there aren't any)
            logger.warn("Project build details are already saved; overwriting them with " + details);
            detailsFuture = CompletableFuture.completedFuture(details);
        } else {
            detailsFuture.complete(details);
        }
    }

    @Override
    public CompletableFuture<BuildAgent.BuildDetails> getBuildDetailsFuture() {
        return detailsFuture;
    }

    /**
     * Blocking call that waits for build details to be available.
     *
     * <p>Important: this must NOT be invoked on the Swing Event Dispatch Thread (EDT) as it will
     * block the UI and can deadlock. From the EDT, prefer {@link #getBuildDetailsFuture()} and
     * update the UI when the future completes.
     *
     * @return the resolved build details
     * @throws IllegalStateException if called on the Swing EDT
     */
    @Override
    @Blocking
    public BuildAgent.BuildDetails awaitBuildDetails() {
        try {
            return detailsFuture.get();
        } catch (ExecutionException e) {
            logger.error("ExecutionException while awaiting build details completion", e);
            return BuildAgent.BuildDetails.EMPTY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void saveProjectProperties() {
        saveProperties(propertiesFile, projectProps, "Brokk project configuration");
    }

}
