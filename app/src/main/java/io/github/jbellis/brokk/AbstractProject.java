package io.github.jbellis.brokk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.git.LocalFileRepo;
import io.github.jbellis.brokk.util.AtomicWrites;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
    public static final String STYLE_GUIDE_FILE = "style.md";
    public static final String REVIEW_GUIDE_FILE = "review.md";
    public static final String DEBUG_LOG_FILE = "debug.log";

    protected final Path root;
    protected final IGitRepo repo;
    protected final Path workspacePropertiesFile;
    protected final Properties workspaceProps;
    protected final Path masterRootPathForConfig;

    public AbstractProject(Path root) {
        assert root.isAbsolute() : root;
        this.root = root.toAbsolutePath().normalize();
        this.repo = GitRepo.hasGitRepo(this.root) ? new GitRepo(this.root) : new LocalFileRepo(this.root);

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

    // ------------------------------------------------------------------
    // Blitz-History (parallel + post-processing instructions)
    // ------------------------------------------------------------------
    private static final String BLITZ_HISTORY_KEY = "blitzHistory";

    public final void saveBlitzHistory(List<List<String>> historyItems, int maxItems) {
        try {
            var limited = historyItems.stream().limit(maxItems).toList();
            String json = objectMapper.writeValueAsString(limited);
            workspaceProps.setProperty(BLITZ_HISTORY_KEY, json);
            saveWorkspaceProperties();
        } catch (Exception e) {
            logger.error("Error saving Blitz history: {}", e.getMessage());
        }
    }

    @Override
    public List<List<String>> loadBlitzHistory() {
        try {
            String json = workspaceProps.getProperty(BLITZ_HISTORY_KEY);
            if (json != null && !json.isEmpty()) {
                var tf = objectMapper.getTypeFactory();
                var type = tf.constructCollectionType(List.class, tf.constructCollectionType(List.class, String.class));
                return objectMapper.readValue(json, type);
            }
        } catch (Exception e) {
            logger.error("Error loading Blitz history: {}", e.getMessage(), e);
        }
        return new ArrayList<>();
    }

    @Override
    public List<List<String>> addToBlitzHistory(String parallel, String post, int maxItems) {
        if (parallel.trim().isEmpty() && post.trim().isEmpty()) {
            return loadBlitzHistory();
        }
        var history = new ArrayList<>(loadBlitzHistory());
        history.removeIf(
                p -> p.size() >= 2 && p.get(0).equals(parallel) && p.get(1).equals(post));
        history.add(0, List.of(parallel, post));
        if (history.size() > maxItems) {
            history = new ArrayList<>(history.subList(0, maxItems));
        }
        saveBlitzHistory(history, maxItems);
        return history;
    }

    public abstract Set<ProjectFile> getLiveDependencies();

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
        return new ArrayList<>();
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

    @Override
    public @Nullable String getJdk() {
        var value = workspaceProps.getProperty(PROP_JDK_HOME);
        if (value == null || value.isBlank()) {
            value = BuildAgent.detectJdk();
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
                return Language.valueOf(configured);
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

    private Language computeMostCommonLanguage() {
        try {
            var counts = getAllFiles().stream()
                    .map(pf -> com.google.common.io.Files.getFileExtension(
                            pf.absPath().toString()))
                    .filter(ext -> !ext.isEmpty())
                    .map(Language::fromExtension)
                    .filter(lang -> lang != Language.NONE)
                    .collect(Collectors.groupingBy(lang -> lang, Collectors.counting()));

            return counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(Language.NONE);
        } catch (Exception e) {
            logger.warn("Failed to compute most common language for {}: {}", root, e.getMessage());
            return Language.NONE;
        }
    }

    @Override
    public void close() {
        SessionRegistry.release(this.root);
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
            try (var pathStream = Files.walk(live.absPath())) {
                pathStream
                        .filter(Files::isRegularFile)
                        .map(path -> {
                            var relPath = masterRootPathForConfig.relativize(path);
                            return new ProjectFile(masterRootPathForConfig, relPath);
                        })
                        .forEach(allFiles::add);
            } catch (IOException e) {
                logger.error("Error loading dependency files from {}: {}", dependenciesPath, e.getMessage());
                return trackedFiles;
            }
        }

        return allFiles;
    }

    @Override
    public final synchronized Set<ProjectFile> getAllFiles() {
        if (allFilesCache == null) {
            allFilesCache = getAllFilesRaw();
        }
        return allFilesCache;
    }

    @Override
    public final synchronized void invalidateAllFiles() {
        allFilesCache = null;
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
                getLiveDependencies().stream().map(ProjectFile::absPath).collect(Collectors.toSet());

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
