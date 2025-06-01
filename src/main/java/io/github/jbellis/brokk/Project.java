package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jbellis.brokk.Service.ModelConfig;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.git.LocalFileRepo;
import io.github.jbellis.brokk.util.AtomicWrites;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class Project implements IProject, AutoCloseable {
    private final Path propertiesFile;
    private final Path workspacePropertiesFile;
    private final Path root;
    private final Properties projectProps;
    private final Properties workspaceProps;
    private final Path styleGuidePath;
    private final IGitRepo repo;
    private final Set<ProjectFile> dependencyFiles;
    private volatile CompletableFuture<BuildAgent.BuildDetails> detailsFuture = new CompletableFuture<>();

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LogManager.getLogger(Project.class);
    private static final String BUILD_DETAILS_KEY = "buildDetailsJson";
    private static final String CODE_INTELLIGENCE_LANGUAGES_KEY = "code_intelligence_languages";
    private static final String GITHUB_TOKEN_KEY = "githubToken";

    // Helper structure for managing model type configurations
    // Includes old keys for migration purposes.
    private record ModelTypeInfo(String configKey, ModelConfig preferredConfig, String oldModelNameKey, String oldReasoningKey) {}

    private static final Map<String, ModelTypeInfo> MODEL_TYPE_INFOS = Map.of(
        "Architect", new ModelTypeInfo("architectConfig", new ModelConfig(Service.O3, Service.ReasoningLevel.HIGH), "architectModel", "architectReasoning"),
        "Code", new ModelTypeInfo("codeConfig", new ModelConfig(Service.GEMINI_2_5_PRO, Service.ReasoningLevel.DEFAULT), "codeModel", "codeReasoning"),
        "Ask", new ModelTypeInfo("askConfig", new ModelConfig(Service.GEMINI_2_5_PRO, Service.ReasoningLevel.DEFAULT), "askModel", "askReasoning"),
        "Search", new ModelTypeInfo("searchConfig", new ModelConfig(Service.GEMINI_2_5_PRO, Service.ReasoningLevel.DEFAULT), "searchModel", "searchReasoning")
    );

    private static final String CODE_AGENT_TEST_SCOPE_KEY = "codeAgentTestScope";
    private static final String COMMIT_MESSAGE_FORMAT_KEY = "commitMessageFormat";

    public static final String DEFAULT_COMMIT_MESSAGE_FORMAT = """
            The commit message should be structured as follows: <type>: <description>
            Use these for <type>: debug, fix, feat, chore, config, docs, style, refactor, perf, test, enh
            """.stripIndent();

    // Cache for organization-level data sharing policy
    private static volatile Boolean isDataShareAllowedCache = null;


    // --- Static paths ---
    private static final Path BROKK_CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "brokk");
    private static final Path PROJECTS_PROPERTIES_PATH = BROKK_CONFIG_DIR.resolve("projects.properties");
    private static final Path GLOBAL_PROPERTIES_PATH = BROKK_CONFIG_DIR.resolve("brokk.properties");

    // New enum to represent just which proxy to use
    public enum LlmProxySetting {BROKK, LOCALHOST, STAGING}

    private static final String LLM_PROXY_SETTING_KEY = "llmProxySetting";

    /**
     * Gets the stored LLM proxy setting (BROKK or LOCALHOST).
     */
    public static LlmProxySetting getProxySetting() {
        var props = loadGlobalProperties();
        String val = props.getProperty(LLM_PROXY_SETTING_KEY, LlmProxySetting.BROKK.name());
        try {
            return LlmProxySetting.valueOf(val);
        } catch (IllegalArgumentException e) {
            return LlmProxySetting.BROKK;
        }
    }

    public CompletableFuture<BuildAgent.BuildDetails> getDetailsFuture() {
        return this.detailsFuture;
    }

    /**
     * Sets the global LLM proxy setting (BROKK or LOCALHOST).
     */
    public static void setLlmProxySetting(LlmProxySetting setting) {
        var props = loadGlobalProperties();
        props.setProperty(LLM_PROXY_SETTING_KEY, setting.name());
        saveGlobalProperties(props);
    }

    // The actual endpoint URLs for each proxy
    public static final String BROKK_PROXY_URL = "https://proxy.brokk.ai";
    public static final String LOCALHOST_PROXY_URL = "http://localhost:4000";
    public static final String STAGING_PROXY_URL = "https://staging.brokk.ai";

    public Project(Path root) {
        assert root.isAbsolute() : root;
        root = root.normalize();
        this.repo = GitRepo.hasGitRepo(root) ? new GitRepo(root) : new LocalFileRepo(root);
        this.root = root;
        this.propertiesFile = root.resolve(".brokk").resolve("project.properties");
        this.workspacePropertiesFile = root.resolve(".brokk").resolve("workspace.properties");
        this.styleGuidePath = root.resolve(".brokk").resolve("style.md");
        this.projectProps = new Properties();
        this.workspaceProps = new Properties();
        this.dependencyFiles = loadDependencyFiles();

        // Load project properties and attempt to initialize build details future
        try {
            if (Files.exists(propertiesFile)) {
                try (var reader = Files.newBufferedReader(propertiesFile)) {
                    projectProps.load(reader); // Attempt to load properties
                }

                var bd = getBuildDetails();
                if (!bd.equals(BuildAgent.BuildDetails.EMPTY)) {
                    this.detailsFuture.complete(bd);
                }
            }
        } catch (IOException e) { // Catches IOException from Files.newBufferedReader or projectProps.load()
            logger.error("Error loading project properties from {}: {}", propertiesFile, e.getMessage());
            projectProps.clear(); // Ensure props are in a clean state (empty) after a load failure
        }

        // Load workspace properties
        if (Files.exists(workspacePropertiesFile)) {
            try (var reader = Files.newBufferedReader(workspacePropertiesFile)) {
                workspaceProps.load(reader);
            } catch (Exception e) {
                logger.error("Error loading workspace properties: {}", e.getMessage());
                workspaceProps.clear();
            }
        }
    }

    // --- Static methods for global properties ---

    /**
     * Reads the global properties file (~/.config/brokk/brokk.properties).
     * Returns an empty Properties object if the file doesn't exist or can't be read.
     */
    private static Properties loadGlobalProperties() {
        var props = new Properties();
        if (Files.exists(GLOBAL_PROPERTIES_PATH)) {
            try (var reader = Files.newBufferedReader(GLOBAL_PROPERTIES_PATH)) {
                props.load(reader);
            } catch (IOException e) {
                logger.warn("Unable to read global properties file: {}", e.getMessage());
                // Return empty props if read fails, to avoid processing potentially corrupted data
                return props;
            }
        }

        // Attempt to migrate old model configuration keys
        boolean migrated = migrateOldModelConfigsIfNecessary(props);
        if (migrated) {
            // Save properties immediately if migration occurred
            // This avoids re-migration attempts and ensures consistency
            saveGlobalProperties(props);
        }
        return props;
    }

    /**
     * Checks for old-style model name/reasoning properties and converts them to the new JSON-based single property.
     * Modifies the passed-in Properties object directly.
     *
     * @param props The Properties object to check and modify.
     * @return true if any migration was performed, false otherwise.
     */
    private static boolean migrateOldModelConfigsIfNecessary(Properties props) {
        boolean changed = false;
        for (var entry : MODEL_TYPE_INFOS.entrySet()) {
            String modelType = entry.getKey();
            ModelTypeInfo typeInfo = entry.getValue();

            // Check if old keys exist AND new key does NOT
            if (props.containsKey(typeInfo.oldModelNameKey()) && !props.containsKey(typeInfo.configKey())) {
                String modelName = props.getProperty(typeInfo.oldModelNameKey());
                // Old reasoning might be missing, default to preferred in that case
                Service.ReasoningLevel reasoningLevel = Service.ReasoningLevel.fromString(
                    props.getProperty(typeInfo.oldReasoningKey()),
                    typeInfo.preferredConfig().reasoning()
                );

                if (modelName == null || modelName.isBlank()) {
                    // This case should be rare if oldModelNameKey exists, but handle defensively.
                    logger.warn("Old model name key '{}' for {} exists but value is blank. Skipping migration for this type.", typeInfo.oldModelNameKey(), modelType);
                    continue;
                }

                ModelConfig migratedConfig = new ModelConfig(modelName, reasoningLevel);
                try {
                    String jsonString = objectMapper.writeValueAsString(migratedConfig);
                    props.setProperty(typeInfo.configKey(), jsonString);
                    props.remove(typeInfo.oldModelNameKey());
                    // oldReasoningKey might be null in ModelTypeInfo if it wasn't used historically,
                    // but for current models, it should exist.
                    if (typeInfo.oldReasoningKey() != null) {
                         props.remove(typeInfo.oldReasoningKey());
                    }
                    changed = true;
                    logger.info("Migrated model config for {} from old keys ('{}', '{}') to new key '{}'.",
                                modelType, typeInfo.oldModelNameKey(), typeInfo.oldReasoningKey(), typeInfo.configKey());
                } catch (JsonProcessingException e) {
                    logger.error("Error serializing migrated ModelConfig for {} to JSON. Old keys ('{}', '{}') will be kept. Error: {}",
                                 modelType, typeInfo.oldModelNameKey(), typeInfo.oldReasoningKey(), e.getMessage());
                }
            }
        }
        return changed;
    }

    /**
     * Atomically saves the given Properties object to ~/.config/brokk/brokk.properties.
     * Only writes if the properties have actually changed.
     */
    private static void saveGlobalProperties(Properties props) {
        try {
            // Check if properties file exists and compare
            if (Files.exists(GLOBAL_PROPERTIES_PATH)) {
                Properties existingProps = new Properties();
                try (var reader = Files.newBufferedReader(GLOBAL_PROPERTIES_PATH)) {
                    existingProps.load(reader);
                } catch (IOException e) {
                    // Ignore read error, proceed to save anyway
                }

                // Compare properties - only save if different
                if (propsEqual(existingProps, props)) {
                    return; // Skip saving if properties are identical
                }
            }

            // Use atomic save method
            AtomicWrites.atomicSaveProperties(GLOBAL_PROPERTIES_PATH, props, "Brokk global configuration");
        } catch (IOException e) {
            logger.error("Error saving global properties: {}", e.getMessage());
        }
    }

    // --- Instance methods ---

    @Override
    public Set<ProjectFile> getAllFiles() {
        var trackedFiles = repo.getTrackedFiles();
        var allFiles = new java.util.HashSet<>(trackedFiles);
        allFiles.addAll(dependencyFiles);
        return allFiles;
    }

    /**
     * Loads all files from the .brokk/dependencies directory
     *
     * @return Set of RepoFile objects for all dependency files
     */
    private Set<ProjectFile> loadDependencyFiles() {
        var dependenciesPath = root.resolve(".brokk").resolve("dependencies");
        if (!Files.exists(dependenciesPath) || !Files.isDirectory(dependenciesPath)) {
            return Set.of();
        }

        try (var pathStream = Files.walk(dependenciesPath)) {
            return pathStream
                    .filter(Files::isRegularFile)
                    .map(path -> {
                        var relPath = root.relativize(path);
                        return new ProjectFile(root, relPath);
                    })
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            logger.error("Error loading dependency files", e);
            return Set.of();
        }
    }

    @Override
    public IGitRepo getRepo() {
        return repo;
    }

    public boolean hasBuildDetails() {
        return detailsFuture.isDone();
    }

    @Override
    public BuildAgent.BuildDetails getBuildDetails() {
        // Build details are project-specific, not workspace-specific
        String json = projectProps.getProperty(BUILD_DETAILS_KEY);
        if (json != null && !json.isEmpty()) {
            try {
                return objectMapper.readValue(json, BuildAgent.BuildDetails.class);
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize BuildDetails from JSON: {}", json, e);
            }
        }
        return BuildAgent.BuildDetails.EMPTY;
    }

    public void saveBuildDetails(BuildAgent.BuildDetails details) {
        assert details != null;
        if (!details.equals(BuildAgent.BuildDetails.EMPTY)) {
            try {
                String json = objectMapper.writeValueAsString(details);
                projectProps.setProperty(BUILD_DETAILS_KEY, json);
                logger.debug("Saving build details to project properties.");
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            saveProjectProperties();
        }

        if (detailsFuture.isDone()) {
            // we're modifying it after initial creation
            detailsFuture = new CompletableFuture<>();
            detailsFuture.complete(details);
        } else {
            detailsFuture.complete(details);
        }
    }

    /**
     * Waits for the build details to become available.
     * This method will block until the build details are loaded or generated.
     *
     * @return The {@link BuildAgent.BuildDetails}.
     */
    public BuildAgent.BuildDetails awaitBuildDetails() {
        try {
            return detailsFuture.get();
        } catch (ExecutionException e) {
            logger.error("ExecutionException while awaiting build details completion", e);
            return BuildAgent.BuildDetails.EMPTY;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Internal helper to get a ModelConfig for a given model type.
     * Reads from global properties, deserializes JSON, or returns preferred default.
     */
    private ModelConfig getModelConfigInternal(String modelTypeKey) {
        var props = loadGlobalProperties(); // Ensures migration has run if needed
        var typeInfo = MODEL_TYPE_INFOS.get(modelTypeKey);
        assert typeInfo != null : "Unknown modelTypeKey: " + modelTypeKey;

        String jsonString = props.getProperty(typeInfo.configKey());
        if (jsonString != null && !jsonString.isBlank()) {
            try {
                return objectMapper.readValue(jsonString, ModelConfig.class);
            } catch (JsonProcessingException e) {
                logger.warn("Error parsing ModelConfig JSON for {} from key '{}': {}. Using preferred default. JSON: '{}'",
                            modelTypeKey, typeInfo.configKey(), e.getMessage(), jsonString);
                // Fall through to return preferred default
            }
        }
        return typeInfo.preferredConfig();
    }

    /**
     * Internal helper to set a ModelConfig for a given model type.
     * Serializes to JSON and saves to global properties.
     */
    private void setModelConfigInternal(String modelTypeKey, ModelConfig config) {
        assert config != null;
        var props = loadGlobalProperties(); // Ensures migration has run if needed
        var typeInfo = MODEL_TYPE_INFOS.get(modelTypeKey);
        assert typeInfo != null : "Unknown modelTypeKey: " + modelTypeKey;

        try {
            String jsonString = objectMapper.writeValueAsString(config);
            props.setProperty(typeInfo.configKey(), jsonString);
            saveGlobalProperties(props);
        } catch (JsonProcessingException e) {
            // Log error and rethrow as unchecked, as this indicates a programming error (e.g., unmarshallable config)
            logger.error("Error serializing ModelConfig for {} (key '{}'): {}", modelTypeKey, typeInfo.configKey(), config, e);
            throw new RuntimeException("Failed to serialize ModelConfig for " + modelTypeKey, e);
        }
    }

    /**
     * Gets the configured model and reasoning for architect tasks.
     */
    public ModelConfig getArchitectModelConfig() {
        return getModelConfigInternal("Architect");
    }

    /**
     * Sets the model and reasoning for architect tasks.
     */
    public void setArchitectModelConfig(ModelConfig config) {
        setModelConfigInternal("Architect", config);
    }

    /**
     * Gets the configured model and reasoning for code generation tasks.
     */
    public ModelConfig getCodeModelConfig() {
        return getModelConfigInternal("Code");
    }

    /**
     * Sets the model and reasoning for code generation tasks.
     */
    public void setCodeModelConfig(ModelConfig config) {
        setModelConfigInternal("Code", config);
    }

    /**
     * Gets the configured model and reasoning for ask tasks.
     */
    public ModelConfig getAskModelConfig() {
        return getModelConfigInternal("Ask");
    }

    /**
     * Sets the model and reasoning for ask tasks.
     */
    public void setAskModelConfig(ModelConfig config) {
        setModelConfigInternal("Ask", config);
    }


    /**
     * Gets the configured model and reasoning for search tasks.
     */
    public ModelConfig getSearchModelConfig() {
        return getModelConfigInternal("Search");
    }

    /**
     * Sets the model and reasoning for search tasks.
     */
    public void setSearchModelConfig(ModelConfig config) {
        setModelConfigInternal("Search", config);
    }

    /**
     * Gets the configured commit message format instruction string.
     * @return The format string, or the default if not set.
     */
    public String getCommitMessageFormat() {
        return projectProps.getProperty(COMMIT_MESSAGE_FORMAT_KEY, DEFAULT_COMMIT_MESSAGE_FORMAT);
    }

    /**
     * Sets the commit message format instruction string.
     * If the provided format is null, blank, or equal to the default, the property is removed.
     */
    public void setCommitMessageFormat(String format) {
        if (format == null || format.isBlank() || format.trim().equals(DEFAULT_COMMIT_MESSAGE_FORMAT)) {
            if (projectProps.containsKey(COMMIT_MESSAGE_FORMAT_KEY)) {
                projectProps.remove(COMMIT_MESSAGE_FORMAT_KEY);
                saveProjectProperties();
                logger.debug("Removed commit message format, reverting to default.");
            }
        } else if (!format.trim().equals(projectProps.getProperty(COMMIT_MESSAGE_FORMAT_KEY))) {
            projectProps.setProperty(COMMIT_MESSAGE_FORMAT_KEY, format.trim());
            saveProjectProperties();
            logger.debug("Set commit message format.");
        }
    }

    @Override
    public Set<Language> getAnalyzerLanguages() {
        String langsProp = projectProps.getProperty(CODE_INTELLIGENCE_LANGUAGES_KEY);
        if (langsProp != null && !langsProp.isBlank()) {
            // User has explicitly set languages, use those.
            return Arrays.stream(langsProp.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(langName -> {
                    try {
                        return Language.valueOf(langName.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        logger.warn("Invalid language '{}' in project properties, ignoring.", langName);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        }

        // Auto-detect languages:
        // 1. Include all languages with >= 10% of recognized (non-NONE) files.
        // 2. If #1 results in an empty set, include the most common recognized language.
        // 3. Always include SQL if any SQL files are present.
        // This is the runtime default if no specific languages are set in projectProps.
        // This detected default is NOT written back to projectProps automatically.
        Map<Language, Long> languageCounts = repo.getTrackedFiles().stream()
            .map(ProjectFile::getLanguage)
            .filter(l -> l != Language.NONE) // Ignore files with no specific language or unclassifiable extensions
            .collect(Collectors.groupingBy(l -> l, Collectors.counting()));

        if (languageCounts.isEmpty()) {
            logger.debug("No files with recognized (non-NONE) languages found for {}. Defaulting to Language.NONE.", root);
            return Set.of(Language.NONE);
        }

        long totalRecognizedFiles = languageCounts.values().stream().mapToLong(Long::longValue).sum();
        // totalRecognizedFiles is > 0 here because languageCounts is not empty.

        Set<Language> detectedLanguages = new HashSet<>();

        // 1. Include all languages with >= 10% of recognized (non-NONE) files.
        languageCounts.entrySet().stream()
            .filter(entry -> (double) entry.getValue() / totalRecognizedFiles >= 0.10)
            .forEach(entry -> detectedLanguages.add(entry.getKey()));

        // 2. If #1 results in an empty set (and languageCounts is not empty),
        //    include the most common recognized language.
        if (detectedLanguages.isEmpty()) {
            // languageCounts is guaranteed non-empty here.
            var mostCommonEntry = languageCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow(); // Should not happen as languageCounts is not empty
            detectedLanguages.add(mostCommonEntry.getKey());
            logger.debug("No language met 10% threshold for {}. Adding most common: {}", root, mostCommonEntry.getKey().name());
        }

        // 3. Always include SQL if any SQL files are present.
        if (languageCounts.containsKey(Language.SQL)) {
            boolean addedByThisRule = detectedLanguages.add(Language.SQL);
            if (addedByThisRule) {
                logger.debug("SQL files present for {}, ensuring SQL is included in detected languages.", root);
            }
        }

        // If languageCounts was not empty, detectedLanguages should not be empty at this point.
        logger.debug("Auto-detected languages for {}: {}", root,
                     detectedLanguages.stream().map(Language::name).collect(Collectors.joining(", ")));
        return detectedLanguages;
    }

    /**
     * Sets the primary languages for code intelligence.
     * @param languages The set of languages to set.
     */
    public void setAnalyzerLanguages(Set<Language> languages) {
        if (languages == null || languages.isEmpty() || (languages.size() == 1 && languages.contains(Language.NONE))) {
            projectProps.remove(CODE_INTELLIGENCE_LANGUAGES_KEY);
        } else {
            String langsString = languages.stream()
                .map(Language::name)
                .collect(Collectors.joining(","));
            projectProps.setProperty(CODE_INTELLIGENCE_LANGUAGES_KEY, langsString);
        }
        saveProjectProperties();
        // Request for analyzer rebuild is now handled by ContextManager after this call
    }

    public enum CodeAgentTestScope {
        ALL, WORKSPACE;

        @Override
        public String toString() {
            return switch (this) {
                case ALL -> "Run All Tests";
                case WORKSPACE -> "Run Tests in Workspace";
            };
        }

        public static CodeAgentTestScope fromString(String value, CodeAgentTestScope defaultScope) {
            if (value == null || value.isBlank()) {
                return defaultScope;
            }
            try {
                return CodeAgentTestScope.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                return defaultScope;
            }
        }
    }

    public CodeAgentTestScope getCodeAgentTestScope() {
        String value = projectProps.getProperty(CODE_AGENT_TEST_SCOPE_KEY);
        return CodeAgentTestScope.fromString(value, CodeAgentTestScope.WORKSPACE); // Default to WORKSPACE
    }

    public void setCodeAgentTestScope(CodeAgentTestScope scope) {
        assert scope != null;
        projectProps.setProperty(CODE_AGENT_TEST_SCOPE_KEY, scope.name());
        saveProjectProperties();
    }


    /**
     * Gets the name of the last used LLM model for this project.
     *
     * @return Model name, or null if not set.
     */
    public String getLastUsedModel() {
        return workspaceProps.getProperty("lastUsedModel");
    }

    /**
     * Sets the name of the last used LLM model for this project.
     *
     * @param modelName The name of the model.
     */
    public void setLastUsedModel(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            workspaceProps.setProperty("lastUsedModel", modelName);
            saveWorkspaceProperties();
        }
    }

    /**
     * Saves project-specific properties (buildCommand, code_intelligence_refresh)
     */
    public void saveProjectProperties() {
        saveProperties(propertiesFile, projectProps, "Brokk project configuration");
    }

    /**
     * Saves workspace-specific properties (window positions, etc.)
     */
    public void saveWorkspaceProperties() {
        saveProperties(workspacePropertiesFile, workspaceProps, "Brokk workspace configuration");
    }

    /**
     * Generic method to save properties to a file
     */
    private void saveProperties(Path file, Properties properties, String comment) {
        try {
            // Check if properties file exists
            if (Files.exists(file)) {
                // Load existing properties to compare
                Properties existingProps = new Properties();
                try (var reader = Files.newBufferedReader(file)) {
                    existingProps.load(reader);
                }

                // Compare properties - only save if different
                if (propsEqual(existingProps, properties)) {
                    return; // Skip saving if properties are identical
                }
            }

            // Use atomic save method
            AtomicWrites.atomicSaveProperties(file, properties, comment);
        } catch (IOException e) {
            logger.error("Error saving properties to {}: {}", file, e.getMessage());
        }
    }

    /**
     * Compares two Properties objects to see if they have the same key-value pairs
     *
     * @return true if properties are equal
     */
    private static boolean propsEqual(Properties p1, Properties p2) {
        if (p1 == null || p2 == null || p1.size() != p2.size()) {
            return false;
        }

        return p1.entrySet().stream()
                .allMatch(e -> {
                    String key = (String) e.getKey();
                    String value = (String) e.getValue();
                    return value.equals(p2.getProperty(key));
                });
    }

    /**
     * @return the absolute, normalized path to the project root
     */
    public Path getRoot() {
        return root;
    }

    public boolean hasGit() {
        return repo instanceof GitRepo;
    }

    /**
     * Checks if the project's Git repository is hosted on GitHub by inspecting the "origin" remote URL.
     *
     * @return {@code true} if the "origin" remote URL contains "github.com", {@code false} otherwise,
     *         or if the remote URL is not set or the project doesn't use Git.
     */
    public boolean isGitHubRepo() {
        if (!hasGit()) {
            return false;
        }
        // We can safely cast to GitRepo because hasGit() checks this.
        // However, getRemoteUrl is available on IGitRepo, which GitRepo implements.
        // But getRemoteUrl is specific to GitRepo, not on IGitRepo in general.
        // The IGitRepo interface in the provided context does not have getRemoteUrl.
        // The GitRepo class *does* have getRemoteUrl("origin").
        // So, we need to ensure 'repo' is indeed a GitRepo instance if we want to call that specific method.
        // The current getRepo() returns IGitRepo.
        // Let's assume getRepo() returns a GitRepo instance when hasGit() is true.
        // If getRemoteUrl("origin") was on IGitRepo, we wouldn't need the cast.
        // Given the context, if hasGit() is true, repo *is* a GitRepo.
        var gitRepo = (GitRepo) getRepo(); // Assuming getRepo() returns GitRepo when hasGit() is true
        String remoteUrl = gitRepo.getRemoteUrl("origin");

        if (remoteUrl == null || remoteUrl.isBlank()) {
            return false;
        }
        return remoteUrl.contains("github.com");
    }

    public enum CpgRefresh {
        AUTO,
        ON_RESTART,
        MANUAL,
        UNSET
    }

    /**
     * Check if .brokk entries exist in .gitignore
     *
     * @return true if .gitignore contains entries for .brokk
     */
    public boolean isGitIgnoreSet() {
        try {
            var gitignorePath = repo.getGitTopLevel().resolve(".gitignore");
            if (Files.exists(gitignorePath)) {
                var content = Files.readString(gitignorePath);
                return content.contains(".brokk/") || content.contains(".brokk/**");
            }
        } catch (IOException e) {
            logger.error("Error checking .gitignore: {}", e.getMessage());
        }
        return false;
    }

    public CpgRefresh getAnalyzerRefresh() {
        String value = projectProps.getProperty("code_intelligence_refresh");
        if (value == null) {
            return CpgRefresh.UNSET;
        }
        try {
            return CpgRefresh.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CpgRefresh.UNSET;
        }
    }

    public void setAnalyzerRefresh(CpgRefresh value) {
        assert value != null;
        projectProps.setProperty("code_intelligence_refresh", value.name());
        saveProjectProperties();
    }

    public String getStyleGuide() {
        try {
            if (Files.exists(styleGuidePath)) {
                return Files.readString(styleGuidePath);
            }
        } catch (IOException e) {
            logger.error("Error reading style guide: {}", e.getMessage());
        }
        return "";
    }

    public void saveStyleGuide(String styleGuide) {
        try {
            Files.createDirectories(styleGuidePath.getParent());
            AtomicWrites.atomicOverwrite(styleGuidePath, styleGuide);
        } catch (IOException e) {
            logger.error("Error saving style guide: {}", e.getMessage());
        }
    }

    /**
     * Saves a serialized Context object to the workspace properties
     */
    public void saveContext(Context context) {
        try {
            // Save the context
            byte[] serialized = Context.serialize(context);
            String encoded = java.util.Base64.getEncoder().encodeToString(serialized);
            workspaceProps.setProperty("context", encoded);

            // Save the current fragment ID counter
            int currentMaxId = ContextFragment.getCurrentMaxId();
            workspaceProps.setProperty("contextFragmentNextId", String.valueOf(currentMaxId));

            saveWorkspaceProperties();
        } catch (Exception e) {
            logger.error("Error saving context: {}", e.getMessage());
        }
    }

    /**
     * Loads a serialized Context object from the workspace properties
     *
     * @return The loaded Context, or null if none exists
     */
    public Context loadContext(IContextManager contextManager, String welcomeMessage) {
        try {
            // Restore the fragment ID counter first
            String nextIdStr = workspaceProps.getProperty("contextFragmentNextId");
            if (nextIdStr != null && !nextIdStr.isEmpty()) {
                try {
                    int nextId = Integer.parseInt(nextIdStr);
                    ContextFragment.setNextId(nextId);
                    logger.debug("Restored fragment ID counter to {}", nextId);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid fragment ID counter value: {}", nextIdStr);
                }
            }

            // Then load the context
            String encoded = workspaceProps.getProperty("context");
            if (encoded != null && !encoded.isEmpty()) {
                byte[] serialized = java.util.Base64.getDecoder().decode(encoded);
                var context = Context.deserialize(serialized, welcomeMessage).withContextManager(contextManager);
                logger.debug("Deserialized context with {} fragments", context.allFragments().count());
                return context;
            }
        } catch (Throwable e) {
            logger.error("Error loading context: {}", e.getMessage());
            clearSavedContext();
        }
        return null;
    }

    private void clearSavedContext() {
        workspaceProps.remove("context");
        workspaceProps.remove("contextFragmentNextId");
        saveWorkspaceProperties();
        logger.debug("Cleared saved context from workspace properties");
    }

    /**
     * Saves a list of text history items to workspace properties
     *
     * @param historyItems The list of text history items to save (newest first)
     * @param maxItems     Maximum number of items to store (older items are trimmed)
     */
    public void saveTextHistory(List<String> historyItems, int maxItems) {
        try {
            // Limit the list to the specified maximum size
            var limitedItems = historyItems.stream()
                    .limit(maxItems)
                    .collect(Collectors.toList());

            // Convert to JSON and store in properties
            String json = objectMapper.writeValueAsString(limitedItems);
            workspaceProps.setProperty("textHistory", json);
            saveWorkspaceProperties();
        } catch (Exception e) {
            logger.error("Error saving text history: {}", e.getMessage());
        }
    }

    /**
     * Loads the saved text history items
     *
     * @return List of text history items (newest first), or empty list if none found
     */
    public List<String> loadTextHistory() {
        try {
            String json = workspaceProps.getProperty("textHistory");
            if (json != null && !json.isEmpty()) {
                List<String> result = objectMapper.readValue(json,
                                                             objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                logger.trace("Loaded {} history items", result.size());
                return result;
            }
        } catch (Exception e) {
            logger.error("Error loading text history: {}", e.getMessage(), e);
        }
        logger.trace("No text history found, returning empty list");
        return new ArrayList<>();
    }

    /**
     * Adds a new item to the text history, maintaining the maximum size
     *
     * @param item     New item to add to history
     * @param maxItems Maximum history size
     * @return The updated history list
     */
    public List<String> addToInstructionsHistory(String item, int maxItems) {
        if (item == null || item.trim().isEmpty()) {
            return loadTextHistory(); // Don't add empty items
        }

        var history = new ArrayList<>(loadTextHistory());

        // Remove item if it already exists to avoid duplicates
        history.removeIf(i -> i.equals(item));

        // Add the new item at the beginning (newest first)
        history.addFirst(item);

        // Trim to max size
        if (history.size() > maxItems) {
            history = new ArrayList<>(history.subList(0, maxItems));
        }

        // Save and return the updated list
        saveTextHistory(history, maxItems);
        return history;
    }

    /**
     * Save a window's position and size
     *
     * @param key    identifier for the window
     * @param window the window to save position for
     */
    public void saveWindowBounds(String key, JFrame window) {
        if (window == null || !window.isDisplayable()) {
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

    /**
     * Get the saved window bounds as a Rectangle
     *
     * @param key           identifier for the window
     * @param defaultWidth  default width if not found
     * @param defaultHeight default height if not found
     * @return Rectangle with the window bounds
     */
    public java.awt.Rectangle getWindowBounds(String key, int defaultWidth, int defaultHeight) {
        var result = new java.awt.Rectangle(-1, -1, defaultWidth, defaultHeight);

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

    /**
     * Gets the saved main window bounds
     * @return Optional containing the saved bounds if valid, or empty if no valid bounds are saved
     */
    public java.util.Optional<java.awt.Rectangle> getMainWindowBounds() {
        var bounds = getWindowBounds("mainFrame", 0, 0);
        if (bounds.x == -1 && bounds.y == -1) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(bounds);
    }

    /**
     * Gets the saved preview window bounds
     */
    public java.awt.Rectangle getPreviewWindowBounds() {
        return getWindowBounds("previewFrame", 600, 400);
    }

    /**
     * Gets the saved diff window bounds
     */
    public java.awt.Rectangle getDiffWindowBounds() {
        return getWindowBounds("diffFrame", 900, 600);
    }

    /**
     * Gets the saved output window bounds
     */
    public java.awt.Rectangle getOutputWindowBounds() {
        return getWindowBounds("outputFrame", 800, 600);
    }

    /**
     * Save main window bounds
     */
    public void saveMainWindowBounds(JFrame window) {
        saveWindowBounds("mainFrame", window);
    }

    /**
     * Save preview window bounds
     */
    public void savePreviewWindowBounds(JFrame window) {
        saveWindowBounds("previewFrame", window);
    }

    /**
     * Save diff window bounds
     */
    public void saveDiffWindowBounds(JFrame frame) {
        saveWindowBounds("diffFrame", frame);
    }

    /**
     * Save output window bounds
     */
    public void saveOutputWindowBounds(JFrame frame) {
        saveWindowBounds("outputFrame", frame);
    }

    /**
     * Store the GitHub token in properties.
     */
    public static void setGitHubToken(String token) {
        var props = loadGlobalProperties();
        if (token == null || token.isBlank()) {
            props.remove(GITHUB_TOKEN_KEY);
        } else {
            props.setProperty(GITHUB_TOKEN_KEY, token.trim());
        }
        saveGlobalProperties(props);
    }

    /**
     * Retrieve the GitHub token. For projects, this now delegates to the global token.
     */
    public static String getGitHubToken() {
        var props = loadGlobalProperties();
        return props.getProperty(GITHUB_TOKEN_KEY, "");
    }

    /**
     * Save vertical split pane position
     */
    public void saveVerticalSplitPosition(int position) {
        if (position > 0) {
            workspaceProps.setProperty("verticalSplitPosition", String.valueOf(position));
            saveWorkspaceProperties();
        }
    }

    /**
     * Get vertical split pane position
     */
    public int getVerticalSplitPosition() {
        try {
            String posStr = workspaceProps.getProperty("verticalSplitPosition");
            return posStr != null ? Integer.parseInt(posStr) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Save history split pane position
     */
    public void saveTopSplitPosition(int position) {
        if (position > 0) {
            workspaceProps.setProperty("topSplitPosition", String.valueOf(position));
            saveWorkspaceProperties();
        }
    }

    /**
     * Get history split pane position
     */
    public int getTopSplitPosition() {
        try {
            String posStr = workspaceProps.getProperty("topSplitPosition");
            return posStr != null ? Integer.parseInt(posStr) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Save context/git split pane position
     */
    public void saveContextGitSplitPosition(int position) {
        if (position > 0) {
            workspaceProps.setProperty("contextGitSplitPosition", String.valueOf(position));
            saveWorkspaceProperties();
        }
    }

    /**
     * Get context/git split pane position
     */
    public int getContextGitSplitPosition() {
        try {
            String posStr = workspaceProps.getProperty("contextGitSplitPosition");
            return posStr != null ? Integer.parseInt(posStr) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Gets the current global UI theme (dark or light)
     *
     * @return "dark" or "light" (defaults to "light" if not set)
     */
    public static String getTheme() {
        var props = loadGlobalProperties();
        return props.getProperty("theme", "dark"); // Default to light
    }

    /**
     * Gets the saved Brokk API key from global settings.
     *
     * @return The saved key, or an empty string if not set.
     */
    @NotNull
    public static String getBrokkKey() {
        var props = loadGlobalProperties();
        return props.getProperty("brokkApiKey", ""); // Default to empty string
    }

    /**
     * Sets the global Brokk API key.
     *
     * @param key The API key to save.
     */
    public static void setBrokkKey(String key) {
        var props = loadGlobalProperties();
        if (key == null || key.isBlank()) {
            props.remove("brokkApiKey"); // Remove key if blank
        } else {
            props.setProperty("brokkApiKey", key.trim());
        }
        saveGlobalProperties(props);

        // Clear cache when key changes
        logger.trace("Cleared data share allowed cache.");
        isDataShareAllowedCache = null;
    }

    /**
     * Checks if data sharing is allowed by the organization.
     * This is fetched from the Brokk service and cached.
     * Defaults to true (allowed) if the key is not set or fetching fails.
     *
     * @return true if data sharing is allowed by the organization, false otherwise.
     */
    public boolean isDataShareAllowed() {
        if (isDataShareAllowedCache != null) {
            return isDataShareAllowedCache;
        }

        String brokkKey = getBrokkKey();
        if (brokkKey.isEmpty()) {
            isDataShareAllowedCache = true; // No key, assume allowed
            return true;
        }

        // Pass the key directly to the static Models method
        boolean allowed = Service.getDataShareAllowed(brokkKey);
        isDataShareAllowedCache = allowed;
        logger.info("Data sharing allowed for organization: {}", allowed);
        return allowed;
    }


    /**
     * Gets the configured LLM proxy URL (including scheme, e.g., "https://...") from global settings.
     *
     * @return The configured proxy URL, defaulting to DEFAULT_LLM_PROXY if not set or blank.
     */
    public static String getProxyUrl() {
        // Return the actual endpoint URL based on the enum setting
        return switch (getProxySetting()) {
            case BROKK -> BROKK_PROXY_URL;
            case LOCALHOST -> LOCALHOST_PROXY_URL;
            case STAGING -> STAGING_PROXY_URL;
        };
    }

    /**
     * Sets the global UI theme
     *
     * @param theme "dark" or "light"
     */
    public static void setTheme(String theme) {
        var props = loadGlobalProperties();
        props.setProperty("theme", theme);
        saveGlobalProperties(props);
    }

    /**
     * Enum defining the data retention policies.
     */
    public enum DataRetentionPolicy {
        IMPROVE_BROKK("Make Brokk Better for Everyone"),
        MINIMAL("Essential Use Only"),
        UNSET("Unset");

        private final String displayName;

        DataRetentionPolicy(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }

        public static DataRetentionPolicy fromString(String value) {
            if (value == null) return UNSET;
            for (DataRetentionPolicy policy : values()) {
                if (policy.name().equalsIgnoreCase(value)) {
                    return policy;
                }
            }
            return UNSET;
        }
    }

    private static final String DATA_RETENTION_POLICY_KEY = "dataRetentionPolicy";
    private static final String FAVORITE_MODELS_KEY = "favoriteModelsJson";

    /**
     * Gets the data retention policy for the project.
     * Defaults to UNSET if not explicitly set.
     *
     * @return The current DataRetentionPolicy.
     */
    @Override
    public DataRetentionPolicy getDataRetentionPolicy() {
        if (!isDataShareAllowed()) {
            return DataRetentionPolicy.MINIMAL;
        }

        String value = projectProps.getProperty(DATA_RETENTION_POLICY_KEY);
        return DataRetentionPolicy.fromString(value);
    }

    /**
     * Sets the data retention policy for the project and saves it.
     *
     * @param policy The DataRetentionPolicy to set. Must not be UNSET or null.
     */
    public void setDataRetentionPolicy(DataRetentionPolicy policy) {
        assert policy != null && policy != DataRetentionPolicy.UNSET : "Cannot set policy to UNSET or null";
        projectProps.setProperty(DATA_RETENTION_POLICY_KEY, policy.name());
        saveProjectProperties();
        logger.info("Set Data Retention Policy to {} for project {}", policy, root.getFileName());
        // TODO: Potentially invalidate/update available models based on policy change here or elsewhere
    }

    /**
     * Default favorite model aliases.
     */
    public static final List<Service.FavoriteModel> DEFAULT_FAVORITE_MODELS = List.of(
            new Service.FavoriteModel("o3", Service.O3, Service.ReasoningLevel.DEFAULT),
            new Service.FavoriteModel("Gemini Pro 2.5", Service.GEMINI_2_5_PRO, Service.ReasoningLevel.DEFAULT),
            new Service.FavoriteModel("Sonnet 3.7", "claude-3.7-sonnet", Service.ReasoningLevel.DEFAULT),
            new Service.FavoriteModel("Flash 2.0", "gemini-2.0-flash", Service.ReasoningLevel.DEFAULT)
    );

    /**
     * Loads the list of favorite models from global properties.
     * Returns defaults if not found or invalid.
     *
     * @return List of FavoriteModel records.
     */
    public static List<Service.FavoriteModel> loadFavoriteModels() {
        var props = loadGlobalProperties();
        String json = props.getProperty(FAVORITE_MODELS_KEY);
        if (json != null && !json.isEmpty()) {
            try {
                // Need to handle deserialization carefully, maybe custom deserializer if needed
                // For now, assuming ObjectMapper can handle the record directly
                var typeFactory = objectMapper.getTypeFactory();
                var listType = typeFactory.constructCollectionType(List.class, Service.FavoriteModel.class);
                // Explicit cast needed as readValue with JavaType returns Object
                @SuppressWarnings("unchecked") // Cast is safe due to the type factory construction
                List<Service.FavoriteModel> loadedList = objectMapper.readValue(json, listType);
                logger.debug("Loaded {} favorite models from global properties.", loadedList.size());
                return loadedList;
            } catch (JsonProcessingException | ClassCastException e) { // Catch potential ClassCastException too
                logger.error("Error loading/casting favorite models from JSON: {}", json, e);
                // Fall through to return defaults
            }
        }
        logger.debug("No favorite models found or error loading, returning defaults.");
        return new ArrayList<>(DEFAULT_FAVORITE_MODELS); // Return mutable copy of defaults
    }

    /**
     * Saves the list of favorite models to global properties as JSON.
     * Only saves if the list is different from the currently saved one.
     *
     * @param favorites The list of FavoriteModel records to save.
     */
    public static void saveFavoriteModels(List<Service.FavoriteModel> favorites) {
        assert favorites != null;
        var props = loadGlobalProperties();
        String newJson = "";
        try {
            newJson = objectMapper.writeValueAsString(favorites);
        } catch (JsonProcessingException e) {
            logger.error("Error serializing favorite models to JSON", e);
            return; // Don't save if serialization fails
        }

        String oldJson = props.getProperty(FAVORITE_MODELS_KEY, ""); // Default to empty string if not present

        // Only save if the JSON representation has changed
        if (!newJson.equals(oldJson)) {
            props.setProperty(FAVORITE_MODELS_KEY, newJson);
            saveGlobalProperties(props);
            logger.debug("Saved {} favorite models to global properties.", favorites.size());
        } else {
            logger.trace("Favorite models unchanged, skipping save.");
        }
    }

    // --- Static methods for managing projects.properties ---

    /**
     * Reads the projects properties file (~/.config/brokk/projects.properties).
     * Returns an empty Properties object if the file doesn't exist or can't be read.
     */
    private static Properties loadProjectsProperties() {
        var props = new Properties();
        if (Files.exists(PROJECTS_PROPERTIES_PATH)) {
            try (var reader = Files.newBufferedReader(PROJECTS_PROPERTIES_PATH)) {
                props.load(reader);
            } catch (IOException e) {
                logger.warn("Unable to read projects properties file: {}", e.getMessage());
            }
        }
        return props;
    }

    /**
     * Atomically saves the given Properties object to ~/.config/brokk/projects.properties.
     */
    private static void saveProjectsProperties(Properties props) {
        try {
            AtomicWrites.atomicSaveProperties(PROJECTS_PROPERTIES_PATH, props, "Brokk projects: recently opened and currently open");
        } catch (IOException e) {
            logger.error("Error saving projects properties: {}", e.getMessage());
        }
    }

    /**
     * Reads the recent projects list from ~/.config/brokk/projects.properties.
     * Returns a map of projectPath -> lastOpenedMillis.
     * If the file doesn't exist or can't be read, returns an empty map.
     */
    public static Map<String, Long> loadRecentProjects() {
        var result = new HashMap<String, Long>();
        var props = loadProjectsProperties();

        for (String key : props.stringPropertyNames()) {
            // Only process keys that look like paths (simple heuristic) and ignore the open list
            if (key.contains(java.io.File.separator) && !key.equals("openProjectsList")) {
                try {
                    var value = Long.parseLong(props.getProperty(key));
                    result.put(key, value);
                } catch (NumberFormatException nfe) {
                    logger.warn("Invalid timestamp for key {} in projects.properties", key);
                }
            }
        }
        return result;
    }

    /**
     * Saves the given map of recent projectPath -> lastOpenedMillis to
     * ~/.config/brokk/projects.properties, trimming recent projects to the 10 most recent.
     * Preserves the existing 'openProjectsList' property.
     */
    public static void saveRecentProjects(Map<String, Long> projects) {
        // Load existing properties to preserve the open projects list
        var existingProps = loadProjectsProperties();
        var openProjectsList = existingProps.getProperty("openProjectsList", ""); // Default to empty if not found

        // Sort recent projects entries by lastOpened descending
        var sorted = projects.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .toList();

        var props = new Properties();
        // Add sorted recent projects
        for (var e : sorted) {
            props.setProperty(e.getKey(), Long.toString(e.getValue()));
        }
        // Add the (potentially preserved) open projects list
        props.setProperty("openProjectsList", openProjectsList);

        saveProjectsProperties(props);
    }

    /**
     * Updates the projects.properties with a single entry for the given directory path,
     * setting last opened to the current time, and adds it to the list of open projects.
     */
    public static void updateRecentProject(Path projectDir) {
        var abs = projectDir.toAbsolutePath().toString();
        var currentMap = loadRecentProjects();
        currentMap.put(abs, System.currentTimeMillis());
        saveRecentProjects(currentMap); // saveRecentProjects preserves the open list now

        // Also add to open projects list within the same properties file
        addToOpenProjects(projectDir);
    }

    /**
     * Adds a project to the 'openProjectsList' property in projects.properties
     */
    private static void addToOpenProjects(Path projectDir) {
        var abs = projectDir.toAbsolutePath().toString();
        var props = loadProjectsProperties(); // Load current properties

        var openListStr = props.getProperty("openProjectsList", "");
        var openSet = new java.util.HashSet<>(List.of(openListStr.split(";")));
        openSet.remove(""); // Remove empty string artifact if list was empty

        if (openSet.add(abs)) { // Add returns true if the set was modified
            props.setProperty("openProjectsList", String.join(";", openSet));
            saveProjectsProperties(props); // Save updated properties
        }
    }

    /**
     * Removes a project from the 'openProjectsList' property in projects.properties
     */
    public static void removeFromOpenProjects(Path projectDir) {
        var abs = projectDir.toAbsolutePath().toString();
        var props = loadProjectsProperties(); // Load current properties

        var openListStr = props.getProperty("openProjectsList", "");
        var openSet = new java.util.HashSet<>(List.of(openListStr.split(";")));
        openSet.remove(""); // Remove empty string artifact

        if (openSet.remove(abs)) { // remove returns true if the set was modified
            props.setProperty("openProjectsList", String.join(";", openSet));
            saveProjectsProperties(props); // Save updated properties
        }
    }

    /**
     * Gets the list of currently open projects from the 'openProjectsList' property
     * in projects.properties. Performs validation and cleanup of invalid entries.
     *
     * @return List of validated paths to currently open projects
     */
    public static List<Path> getOpenProjects() {
        var result = new ArrayList<Path>();
        var props = loadProjectsProperties();
        var openListStr = props.getProperty("openProjectsList", "");

        if (openListStr.isEmpty()) {
            return result;
        }

        var pathsToRemove = new ArrayList<String>();
        var openPaths = List.of(openListStr.split(";"));

        for (String pathStr : openPaths) {
            if (pathStr.isEmpty()) continue; // Skip empty strings from split

            try {
                var path = Path.of(pathStr);
                // Only include paths that still exist and have git repos
                if (Files.isDirectory(path)) {
                    result.add(path);
                } else {
                    // Mark for removal if invalid
                    logger.warn("Removing invalid or non-existent project from open list: {}", pathStr);
                    pathsToRemove.add(pathStr);
                }
            } catch (Exception e) {
                logger.warn("Invalid path string in openProjectsList: {}", pathStr, e);
                pathsToRemove.add(pathStr);
            }
        }

        // Clean up entries for non-existent/invalid projects if any were found
        if (!pathsToRemove.isEmpty()) {
            var openSet = new java.util.HashSet<>(openPaths);
            openSet.removeAll(pathsToRemove);
            openSet.remove(""); // Ensure empty string is not present
            props.setProperty("openProjectsList", String.join(";", openSet));
            saveProjectsProperties(props); // Save cleaned-up list
        }

        return result;
    }

    @Override
    public void close() {
        // analyzerWrapper is now closed by ContextManager
    }
}
