package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.jbellis.brokk.Service.ModelConfig;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.util.AtomicWrites;
import io.github.jbellis.brokk.util.HistoryIo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.HashSet;

public final class MainProject extends AbstractProject {
    private static final Logger logger = LogManager.getLogger(MainProject.class); // Separate logger from AbstractProject

    private final Path propertiesFile;
    private final Properties projectProps;
    private final Path styleGuidePath;
    private volatile CompletableFuture<BuildAgent.BuildDetails> detailsFuture = new CompletableFuture<>();

    private static final String BUILD_DETAILS_KEY = "buildDetailsJson";
    private static final String CODE_INTELLIGENCE_LANGUAGES_KEY = "code_intelligence_languages";
    private static final String GITHUB_TOKEN_KEY = "githubToken";

    // New key for the IssueProvider record as JSON
    private static final String ISSUES_PROVIDER_JSON_KEY = "issuesProviderJson";

    // Keys for Architect Options persistence
    private static final String ARCHITECT_OPTIONS_JSON_KEY = "architectOptionsJson";
    private static final String ARCHITECT_RUN_IN_WORKTREE_KEY = "architectRunInWorktree";

    // Old keys for migration
    private static final String OLD_ISSUE_PROVIDER_ENUM_KEY = "issueProvider"; // Stores the enum name (GITHUB, JIRA)
    private static final String JIRA_PROJECT_BASE_URL_KEY = "jiraProjectBaseUrl";
    private static final String JIRA_PROJECT_API_TOKEN_KEY = "jiraProjectApiToken";
    private static final String JIRA_PROJECT_KEY_KEY = "jiraProjectKey";

    private record ModelTypeInfo(String configKey, ModelConfig preferredConfig, String oldModelNameKey,
                                 String oldReasoningKey) {
    }

    private static final Map<String, ModelTypeInfo> MODEL_TYPE_INFOS = Map.of(
            "Architect", new ModelTypeInfo("architectConfig", new ModelConfig(Service.O3, Service.ReasoningLevel.HIGH), "architectModel", "architectReasoning"),
            "Code", new ModelTypeInfo("codeConfig", new ModelConfig(Service.GEMINI_2_5_PRO, Service.ReasoningLevel.DEFAULT), "codeModel", "codeReasoning"),
            "Ask", new ModelTypeInfo("askConfig", new ModelConfig(Service.GEMINI_2_5_PRO, Service.ReasoningLevel.DEFAULT), "askModel", "askReasoning"),
            "Search", new ModelTypeInfo("searchConfig", new ModelConfig(Service.GEMINI_2_5_PRO, Service.ReasoningLevel.DEFAULT), "searchModel", "searchReasoning")
    );

    private static final String CODE_AGENT_TEST_SCOPE_KEY = "codeAgentTestScope";
    private static final String COMMIT_MESSAGE_FORMAT_KEY = "commitMessageFormat";

    private static final List<SettingsChangeListener> settingsChangeListeners = new CopyOnWriteArrayList<>();

    public static final String DEFAULT_COMMIT_MESSAGE_FORMAT = """
                                                               The commit message should be structured as follows: <type>: <description>
                                                               Use these for <type>: debug, fix, feat, chore, config, docs, style, refactor, perf, test, enh
                                                               """.stripIndent();
    private static volatile Boolean isDataShareAllowedCache = null;
    private static Properties globalPropertiesCache = null; // protected by synchronized

    private static final Path BROKK_CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "brokk");
    private static final Path PROJECTS_PROPERTIES_PATH = BROKK_CONFIG_DIR.resolve("projects.properties");
    private static final Path GLOBAL_PROPERTIES_PATH = BROKK_CONFIG_DIR.resolve("brokk.properties");

    private final Path sessionsDir;
    private final Path legacySessionsIndexPath;

    public enum LlmProxySetting {BROKK, LOCALHOST, STAGING}

    private static final String LLM_PROXY_SETTING_KEY = "llmProxySetting";
    public static final String BROKK_PROXY_URL = "https://proxy.brokk.ai";
    public static final String LOCALHOST_PROXY_URL = "http://localhost:4000";
    public static final String STAGING_PROXY_URL = "https://staging.brokk.ai";

    private static final String DATA_RETENTION_POLICY_KEY = "dataRetentionPolicy";
    private static final String FAVORITE_MODELS_KEY = "favoriteModelsJson";

    public record ProjectPersistentInfo(long lastOpened, List<String> openWorktrees) {
        public ProjectPersistentInfo {
            if (openWorktrees == null) {
                openWorktrees = List.of();
            }
        }

        public static ProjectPersistentInfo fromTimestamp(long lastOpened) {
            return new ProjectPersistentInfo(lastOpened, List.of());
        }
    }

    public MainProject(Path root) {
        super(root); // Initializes this.root and this.repo

        this.propertiesFile = this.masterRootPathForConfig.resolve(".brokk").resolve("project.properties");
        this.styleGuidePath = this.masterRootPathForConfig.resolve(".brokk").resolve("style.md");
        this.sessionsDir = this.masterRootPathForConfig.resolve(".brokk").resolve("sessions");
        this.legacySessionsIndexPath = this.sessionsDir.resolve("sessions.jsonl");

        this.projectProps = new Properties();

        try {
            if (Files.exists(propertiesFile)) {
                try (var reader = Files.newBufferedReader(propertiesFile)) {
                    projectProps.load(reader);
                }
                var bd = loadBuildDetailsInternal(); // Uses projectProps
                if (!bd.equals(BuildAgent.BuildDetails.EMPTY)) {
                    this.detailsFuture.complete(bd);
                }
            }
        } catch (IOException e) {
            logger.error("Error loading project properties from {}: {}", propertiesFile, e.getMessage());
            projectProps.clear();
        }
        // Initialize cache and trigger migration/defaulting if necessary
        this.issuesProviderCache = getIssuesProvider();
    }

    @Override
    public MainProject getParent() {
        return this;
    }

    @Override
    public Path getMasterRootPathForConfig() {
        return this.masterRootPathForConfig;
    }

    private static synchronized Properties loadGlobalProperties() {
        if (globalPropertiesCache != null) {
            return (Properties) globalPropertiesCache.clone();
        }
        
        var props = new Properties();
        if (Files.exists(GLOBAL_PROPERTIES_PATH)) {
            try (var reader = Files.newBufferedReader(GLOBAL_PROPERTIES_PATH)) {
                props.load(reader);
            } catch (IOException e) {
                logger.warn("Unable to read global properties file: {}", e.getMessage());
                globalPropertiesCache = (Properties) props.clone();
                return props;
            }
        }
        boolean migrated = migrateOldModelConfigsIfNecessary(props);
        if (migrated) {
            saveGlobalProperties(props);
        } else {
            globalPropertiesCache = (Properties) props.clone();
        }
        return props;
    }

    private static boolean migrateOldModelConfigsIfNecessary(Properties props) {
        boolean changed = false;
        for (var entry : MODEL_TYPE_INFOS.entrySet()) {
            String modelType = entry.getKey();
            ModelTypeInfo typeInfo = entry.getValue();

            if (props.containsKey(typeInfo.oldModelNameKey()) && !props.containsKey(typeInfo.configKey())) {
                String modelName = props.getProperty(typeInfo.oldModelNameKey());
                Service.ReasoningLevel reasoningLevel = Service.ReasoningLevel.fromString(
                        props.getProperty(typeInfo.oldReasoningKey()),
                        typeInfo.preferredConfig().reasoning()
                );

                if (modelName == null || modelName.isBlank()) {
                    logger.warn("Old model name key '{}' for {} exists but value is blank. Skipping migration for this type.", typeInfo.oldModelNameKey(), modelType);
                    continue;
                }

                ModelConfig migratedConfig = new ModelConfig(modelName, reasoningLevel);
                try {
                    String jsonString = objectMapper.writeValueAsString(migratedConfig);
                    props.setProperty(typeInfo.configKey(), jsonString);
                    props.remove(typeInfo.oldModelNameKey());
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

    private static synchronized void saveGlobalProperties(Properties props) {
        try {
            if (loadGlobalProperties().equals(props)) {
                return;
            }
            AtomicWrites.atomicSaveProperties(GLOBAL_PROPERTIES_PATH, props, "Brokk global configuration");
            globalPropertiesCache = (Properties) props.clone();
        } catch (IOException e) {
            logger.error("Error saving global properties: {}", e.getMessage());
            globalPropertiesCache = null; // Invalidate cache on error
        }
    }

    @Override
    public boolean hasBuildDetails() {
        return detailsFuture.isDone();
    }

    private BuildAgent.BuildDetails loadBuildDetailsInternal() { // Renamed to avoid conflict with IProject
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
    
    @Override
    public BuildAgent.BuildDetails loadBuildDetails() {
        return loadBuildDetailsInternal();
    }


    @Override
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
            detailsFuture = new CompletableFuture<>();
        }
        detailsFuture.complete(details);
    }

    @Override
    public CompletableFuture<BuildAgent.BuildDetails> getBuildDetailsFuture() {
        return detailsFuture;
    }

    @Override
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

    private ModelConfig getModelConfigInternal(String modelTypeKey) {
        var props = loadGlobalProperties();
        var typeInfo = MODEL_TYPE_INFOS.get(modelTypeKey);
        assert typeInfo != null : "Unknown modelTypeKey: " + modelTypeKey;

        String jsonString = props.getProperty(typeInfo.configKey());
        if (jsonString != null && !jsonString.isBlank()) {
            try {
                return objectMapper.readValue(jsonString, ModelConfig.class);
            } catch (JsonProcessingException e) {
                logger.warn("Error parsing ModelConfig JSON for {} from key '{}': {}. Using preferred default. JSON: '{}'",
                        modelTypeKey, typeInfo.configKey(), e.getMessage(), jsonString);
            }
        }
        return typeInfo.preferredConfig();
    }

    private void setModelConfigInternal(String modelTypeKey, ModelConfig config) {
        assert config != null;
        var props = loadGlobalProperties();
        var typeInfo = MODEL_TYPE_INFOS.get(modelTypeKey);
        assert typeInfo != null : "Unknown modelTypeKey: " + modelTypeKey;

        try {
            String jsonString = objectMapper.writeValueAsString(config);
            props.setProperty(typeInfo.configKey(), jsonString);
            saveGlobalProperties(props);
        } catch (JsonProcessingException e) {
            logger.error("Error serializing ModelConfig for {} (key '{}'): {}", modelTypeKey, typeInfo.configKey(), config, e);
            throw new RuntimeException("Failed to serialize ModelConfig for " + modelTypeKey, e);
        }
    }

    @Override
    public ModelConfig getArchitectModelConfig() {
        return getModelConfigInternal("Architect");
    }

    @Override
    public void setArchitectModelConfig(ModelConfig config) {
        setModelConfigInternal("Architect", config);
    }

    @Override
    public ModelConfig getCodeModelConfig() {
        return getModelConfigInternal("Code");
    }

    @Override
    public void setCodeModelConfig(ModelConfig config) {
        setModelConfigInternal("Code", config);
    }

    @Override
    public ModelConfig getAskModelConfig() {
        return getModelConfigInternal("Ask");
    }

    @Override
    public void setAskModelConfig(ModelConfig config) {
        setModelConfigInternal("Ask", config);
    }

    @Override
    public ModelConfig getSearchModelConfig() {
        return getModelConfigInternal("Search");
    }

    @Override
    public void setSearchModelConfig(ModelConfig config) {
        setModelConfigInternal("Search", config);
    }

    @Override
    public String getCommitMessageFormat() {
        return projectProps.getProperty(COMMIT_MESSAGE_FORMAT_KEY, DEFAULT_COMMIT_MESSAGE_FORMAT);
    }

    @Override
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
            return Arrays.stream(langsProp.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(langName -> {
                        try {
                            return Language.valueOf(langName.toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException e) {
                            logger.warn("Invalid language '{}' in project properties, ignoring.", langName);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }

        Map<Language, Long> languageCounts = repo.getTrackedFiles().stream() // repo from AbstractProject
                .map(ProjectFile::getLanguage)
                .filter(l -> l != Language.NONE)
                .collect(Collectors.groupingBy(l -> l, Collectors.counting()));

        if (languageCounts.isEmpty()) {
            logger.debug("No files with recognized (non-NONE) languages found for {}. Defaulting to Language.NONE.", root);
            return Set.of(Language.NONE);
        }

        long totalRecognizedFiles = languageCounts.values().stream().mapToLong(Long::longValue).sum();
        Set<Language> detectedLanguages = new HashSet<>();

        languageCounts.entrySet().stream()
                .filter(entry -> (double) entry.getValue() / totalRecognizedFiles >= 0.10)
                .forEach(entry -> detectedLanguages.add(entry.getKey()));

        if (detectedLanguages.isEmpty()) {
            var mostCommonEntry = languageCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElseThrow();
            detectedLanguages.add(mostCommonEntry.getKey());
            logger.debug("No language met 10% threshold for {}. Adding most common: {}", root, mostCommonEntry.getKey().name());
        }

        if (languageCounts.containsKey(Language.SQL)) {
            boolean addedByThisRule = detectedLanguages.add(Language.SQL);
            if (addedByThisRule) {
                logger.debug("SQL files present for {}, ensuring SQL is included in detected languages.", root);
            }
        }
        logger.debug("Auto-detected languages for {}: {}", root,
                detectedLanguages.stream().map(Language::name).collect(Collectors.joining(", ")));
        return detectedLanguages;
    }

    @Override
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
    }

    @Override
    public CodeAgentTestScope getCodeAgentTestScope() {
        String value = projectProps.getProperty(CODE_AGENT_TEST_SCOPE_KEY);
        return CodeAgentTestScope.fromString(value, CodeAgentTestScope.WORKSPACE);
    }

    @Override
    public void setCodeAgentTestScope(CodeAgentTestScope scope) {
        assert scope != null;
        projectProps.setProperty(CODE_AGENT_TEST_SCOPE_KEY, scope.name());
        saveProjectProperties();
    }

    private volatile io.github.jbellis.brokk.IssueProvider issuesProviderCache = null;

    @Override
    public io.github.jbellis.brokk.IssueProvider getIssuesProvider() {
        if (issuesProviderCache != null) {
            return issuesProviderCache;
        }

        String json = projectProps.getProperty(ISSUES_PROVIDER_JSON_KEY);
        if (json != null && !json.isBlank()) {
            try {
                issuesProviderCache = objectMapper.readValue(json, io.github.jbellis.brokk.IssueProvider.class);
                return issuesProviderCache;
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize IssueProvider from JSON: {}. Will attempt migration or default.", json, e);
            }
        }

        // Migration from old properties
        String oldProviderEnumName = projectProps.getProperty(OLD_ISSUE_PROVIDER_ENUM_KEY);
        if (oldProviderEnumName != null) {
            io.github.jbellis.brokk.issues.IssueProviderType oldType = io.github.jbellis.brokk.issues.IssueProviderType.fromString(oldProviderEnumName);
            if (oldType == io.github.jbellis.brokk.issues.IssueProviderType.JIRA) {
                String baseUrl = projectProps.getProperty(JIRA_PROJECT_BASE_URL_KEY, "");
                String apiToken = projectProps.getProperty(JIRA_PROJECT_API_TOKEN_KEY, "");
                String projectKey = projectProps.getProperty(JIRA_PROJECT_KEY_KEY, "");
                issuesProviderCache = io.github.jbellis.brokk.IssueProvider.jira(baseUrl, apiToken, projectKey);
            } else if (oldType == io.github.jbellis.brokk.issues.IssueProviderType.GITHUB) {
                // Old GitHub had no specific config, so it's the default GitHub config
                issuesProviderCache = io.github.jbellis.brokk.IssueProvider.github();
            }
            // If migrated, save new format and remove old keys
            if (issuesProviderCache != null) {
                setIssuesProvider(issuesProviderCache); // This will save and clear old keys
                logger.info("Migrated issue provider settings from old format for project {}", getRoot().getFileName());
                return issuesProviderCache;
            }
        }

        // Defaulting logic if no JSON and no old properties
        if (isGitHubRepo()) {
            issuesProviderCache = io.github.jbellis.brokk.IssueProvider.github();
        } else {
            issuesProviderCache = io.github.jbellis.brokk.IssueProvider.none();
        }
        // Save the default so it's persisted
        setIssuesProvider(issuesProviderCache);
        logger.info("Defaulted issue provider to {} for project {}", issuesProviderCache.type(), getRoot().getFileName());
        return issuesProviderCache;
    }

    @Override
    public void setIssuesProvider(io.github.jbellis.brokk.IssueProvider provider) {
        if (provider == null) {
            provider = io.github.jbellis.brokk.IssueProvider.none(); // Default to NONE if null is passed
        }
        try {
            String json = objectMapper.writeValueAsString(provider);
            projectProps.setProperty(ISSUES_PROVIDER_JSON_KEY, json);
            issuesProviderCache = provider;

            // Remove old keys after successful new key storage
            boolean removedOld = projectProps.remove(OLD_ISSUE_PROVIDER_ENUM_KEY) != null;
            removedOld |= projectProps.remove(JIRA_PROJECT_BASE_URL_KEY) != null;
            removedOld |= projectProps.remove(JIRA_PROJECT_API_TOKEN_KEY) != null;
            removedOld |= projectProps.remove(JIRA_PROJECT_KEY_KEY) != null;
            if (removedOld) {
                logger.debug("Removed old issue provider properties after setting new JSON format.");
            }

            saveProjectProperties();
            logger.info("Set issue provider to type '{}' for project {}", provider.type(), getRoot().getFileName());
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize IssueProvider to JSON: {}. Settings not saved.", provider, e);
            throw new RuntimeException("Failed to serialize IssueProvider", e);
        }
    }


    @Override
    @Deprecated
    public String getJiraProjectKey() {
        io.github.jbellis.brokk.IssueProvider provider = getIssuesProvider();
        if (provider.type() == io.github.jbellis.brokk.issues.IssueProviderType.JIRA &&
            provider.config() instanceof io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig jiraConfig) {
            return jiraConfig.projectKey();
        }
        return "";
    }

    @Override
    @Deprecated
    public void setJiraProjectKey(String projectKey) {
        String trimmedValue = (projectKey == null) ? "" : projectKey.trim();
        io.github.jbellis.brokk.IssueProvider currentProvider = getIssuesProvider();
        io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig currentJiraConfig =
            (currentProvider.type() == io.github.jbellis.brokk.issues.IssueProviderType.JIRA &&
             currentProvider.config() instanceof io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig jc)
            ? jc : null;

        if (currentJiraConfig != null) { // Current provider is JIRA
            if (!Objects.equals(trimmedValue, currentJiraConfig.projectKey())) {
                var newJiraConfig = new io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig(
                    currentJiraConfig.baseUrl(), currentJiraConfig.apiToken(), trimmedValue);
                setIssuesProvider(new io.github.jbellis.brokk.IssueProvider(io.github.jbellis.brokk.issues.IssueProviderType.JIRA, newJiraConfig));
            }
        } else { // Current provider is NOT JIRA
            if (!trimmedValue.isEmpty()) { // Trying to set a non-empty Jira project key
                var newJiraConfig = new io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig("", "", trimmedValue);
                setIssuesProvider(new io.github.jbellis.brokk.IssueProvider(io.github.jbellis.brokk.issues.IssueProviderType.JIRA, newJiraConfig));
            }
            // If trimmedValue is empty and current provider is not JIRA, do nothing.
        }
    }

    public void saveProjectProperties() {
        // Use AbstractProject's saveProperties for consistency if it were public static or passed instance
        // For now, keep local implementation matching AbstractProject's logic.
        try {
            Files.createDirectories(propertiesFile.getParent());
            if (Files.exists(propertiesFile)) {
                Properties existingProps = new Properties();
                try (var reader = Files.newBufferedReader(propertiesFile)) {
                    existingProps.load(reader);
                } catch (IOException e) { /* ignore */ }

                if (Objects.equals(existingProps, projectProps)) { // Use AbstractProject.propsEqual
                    return;
                }
            }
            AtomicWrites.atomicSaveProperties(propertiesFile, projectProps, "Brokk project configuration");
        } catch (IOException e) {
            logger.error("Error saving properties to {}: {}", propertiesFile, e.getMessage());
        }
    }

    @Override
    public boolean isGitHubRepo() {
        if (!hasGit()) return false; // hasGit from AbstractProject
        var gitRepo = (GitRepo) getRepo(); // getRepo from AbstractProject
        String remoteUrl = gitRepo.getRemoteUrl("origin");
        if (remoteUrl == null || remoteUrl.isBlank()) return false;
        return remoteUrl.contains("github.com");
    }

    @Override
    public boolean isGitIgnoreSet() {
        try {
            var gitignorePath = getMasterRootPathForConfig().resolve(".gitignore");
            if (Files.exists(gitignorePath)) {
                var content = Files.readString(gitignorePath);
                return content.contains(".brokk/") || content.contains(".brokk/**");
            }
        } catch (IOException e) {
            logger.error("Error checking .gitignore at {}: {}", getMasterRootPathForConfig().resolve(".gitignore"), e.getMessage());
        }
        return false;
    }

    @Override
    public CpgRefresh getAnalyzerRefresh() {
        String value = projectProps.getProperty("code_intelligence_refresh");
        if (value == null) return CpgRefresh.UNSET;
        try {
            return CpgRefresh.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CpgRefresh.UNSET;
        }
    }

    @Override
    public void setAnalyzerRefresh(CpgRefresh value) {
        assert value != null;
        projectProps.setProperty("code_intelligence_refresh", value.name());
        saveProjectProperties();
    }

    @Override
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

    @Override
    public void saveStyleGuide(String styleGuide) {
        try {
            Files.createDirectories(styleGuidePath.getParent());
            AtomicWrites.atomicOverwrite(styleGuidePath, styleGuide);
        } catch (IOException e) {
            logger.error("Error saving style guide: {}", e.getMessage());
        }
    }

    private Path getSessionHistoryPath(UUID sessionId) {
        return sessionsDir.resolve(sessionId.toString() + ".zip");
    }

    private Optional<SessionInfo> readSessionInfoFromZip(Path zipPath) {
        if (!Files.exists(zipPath)) return Optional.empty();
        try (var fs = FileSystems.newFileSystem(zipPath, Map.of())) {
            Path manifestPath = fs.getPath("manifest.json");
            if (Files.exists(manifestPath)) {
                String json = Files.readString(manifestPath);
                return Optional.of(objectMapper.readValue(json, SessionInfo.class));
            }
        } catch (IOException e) {
            logger.warn("Error reading manifest.json from {}: {}", zipPath.getFileName(), e.getMessage());
        }
        return Optional.empty();
    }

    private void writeSessionInfoToZip(Path zipPath, SessionInfo sessionInfo) throws IOException {
        try (var fs = FileSystems.newFileSystem(zipPath, Map.of("create", Files.notExists(zipPath) ? "true" : "false"))) {
            Path manifestPath = fs.getPath("manifest.json");
            String json = objectMapper.writeValueAsString(sessionInfo);
            Files.writeString(manifestPath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.error("Error writing manifest.json to {}: {}", zipPath.getFileName(), e.getMessage());
            throw e;
        }
    }

    @Override
    public void saveHistory(ContextHistory ch, UUID sessionId) {
        Path sessionHistoryPath = getSessionHistoryPath(sessionId);
        SessionInfo infoToSave = null;

        // 1. Try to get current session info to preserve name/created time before altering the zip.
        Optional<SessionInfo> currentInfoOpt = readSessionInfoFromZip(sessionHistoryPath);
        if (currentInfoOpt.isPresent()) {
            SessionInfo currentInfo = currentInfoOpt.get();
            infoToSave = new SessionInfo(currentInfo.id(), currentInfo.name(), currentInfo.created(), System.currentTimeMillis());
        } else {
            // Manifest might be missing (new session just after creation and before manifest write, or legacy). Try listSessions.
            // This listSessions() call might be problematic if newSession() hasn't completed its manifest write yet in some racy test setup.
            // However, for a stable session or legacy session, this is the correct fallback.
            SessionInfo sessionFromList = listSessions().stream()
                    .filter(s -> s.id().equals(sessionId))
                    .findFirst()
                    .orElse(null);
            if (sessionFromList != null) {
                infoToSave = new SessionInfo(sessionFromList.id(), sessionFromList.name(), sessionFromList.created(), System.currentTimeMillis());
                logger.info("Preparing to write/update manifest for session {} based on listSessions data (manifest was missing or is being updated).", sessionId);
            } else {
                // Session is not in current manifests and not in legacy list.
                // This could be a brand new session ID for which newSession() hasn't run or completed.
                logger.warn("Session ID {} has no existing manifest and is not found in current session list. History content will be saved. Manifest cannot be created/updated without session name/creation time.", sessionId);
            }
        }

        try {
            // 2. Write history contents. This might clear the zip if HistoryIo.writeZip is destructive,
            // or simply add/update history files if it's not.
            HistoryIo.writeZip(ch, sessionHistoryPath);

            // 3. If we successfully determined SessionInfo (either from existing manifest or list),
            //    write/rewrite the manifest. This ensures it's present and timestamp is updated.
            if (infoToSave != null) {
                writeSessionInfoToZip(sessionHistoryPath, infoToSave);
            } else {
                // If we reach here, infoToSave is null. This means original manifest was absent,
                // AND session was not found in listSessions.
                // Check if manifest is *still* missing after HistoryIo.writeZip
                if (Files.exists(sessionHistoryPath) && readSessionInfoFromZip(sessionHistoryPath).isEmpty()){
                    logger.warn("History content saved for session {}, but manifest.json is still missing as session details (name, created time) were unavailable.", sessionId);
                }
            }
        } catch (IOException e) {
            logger.error("Error saving context history or updating/creating manifest for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public ContextHistory loadHistory(UUID sessionId, IContextManager contextManager) {
        try {
            var sessionHistoryPath = getSessionHistoryPath(sessionId);
            ContextHistory ch = HistoryIo.readZip(sessionHistoryPath, contextManager);
            if (ch.getHistory().isEmpty()) {
                return ch;
            }
            // Resetting nextId based on loaded fragments.
            // Only consider numeric IDs for dynamic fragments.
            // Hashes will not parse to int and will be skipped by this logic.
            int maxNumericId = 0;
            for (Context ctx : ch.getHistory()) {
                for (ContextFragment fragment : ctx.allFragments().toList()) {
                    try {
                        maxNumericId = Math.max(maxNumericId, Integer.parseInt(fragment.id()));
                    } catch (NumberFormatException e) {
                        // Ignore non-numeric IDs (hashes)
                    }
                }
                for (TaskEntry taskEntry : ctx.getTaskHistory()) {
                    if (taskEntry.log() != null) {
                        try {
                            // TaskFragment IDs are hashes, so this typically won't contribute to maxNumericId.
                            // If some TaskFragments had numeric IDs historically, this would catch them.
                            maxNumericId = Math.max(maxNumericId, Integer.parseInt(taskEntry.log().id()));
                        } catch (NumberFormatException e) {
                            // Ignore non-numeric IDs
                        }
                    }
                }
            }
            // ContextFragment.nextId is an AtomicInteger, its value is the *next* ID to be assigned.
            // If maxNumericId found is, say, 10, nextId should be set to 10 so that getAndIncrement() yields 11.
            // If setNextId ensures nextId will be value+1, then passing maxNumericId is correct.
            // Current ContextFragment.setNextId: if (value >= nextId.get()) { nextId.set(value); }
            // Then nextId.getAndIncrement() will use `value` and then increment it.
            // So we should set it to maxNumericId found.
            if (maxNumericId > 0) { // Only set if we found any numeric IDs
                 ContextFragment.setMinimumId(maxNumericId + 1);
                 logger.debug("Restored dynamic fragment ID counter based on max numeric ID: {}", maxNumericId);
            }
            return ch;
        } catch (IOException e) {
            logger.error("Error loading context history for session {}: {}", sessionId, e.getMessage());
            return new ContextHistory();
        }
    }

    public static LlmProxySetting getProxySetting() {
        var props = loadGlobalProperties();
        String val = props.getProperty(LLM_PROXY_SETTING_KEY, LlmProxySetting.BROKK.name());
        try {
            return LlmProxySetting.valueOf(val);
        } catch (IllegalArgumentException e) {
            return LlmProxySetting.BROKK;
        }
    }

    public static void setLlmProxySetting(LlmProxySetting setting) {
        var props = loadGlobalProperties();
        props.setProperty(LLM_PROXY_SETTING_KEY, setting.name());
        saveGlobalProperties(props);
    }

    public static String getProxyUrl() {
        return switch (getProxySetting()) {
            case BROKK -> BROKK_PROXY_URL;
            case LOCALHOST -> LOCALHOST_PROXY_URL;
            case STAGING -> STAGING_PROXY_URL;
        };
    }

    public static void setGitHubToken(String token) {
        var props = loadGlobalProperties();
        if (token == null || token.isBlank()) {
            props.remove(GITHUB_TOKEN_KEY);
        } else {
            props.setProperty(GITHUB_TOKEN_KEY, token.trim());
        }
        saveGlobalProperties(props);
        notifyGitHubTokenChanged();
    }

    private static void notifyGitHubTokenChanged() {
        for (SettingsChangeListener listener : settingsChangeListeners) {
            try {
                listener.gitHubTokenChanged();
            } catch (Exception e) {
                logger.error("Error notifying listener of GitHub token change", e);
            }
        }
    }

    @Override
    public io.github.jbellis.brokk.agents.ArchitectAgent.ArchitectOptions getArchitectOptions() {
        String json = projectProps.getProperty(ARCHITECT_OPTIONS_JSON_KEY);
        if (json != null && !json.isBlank()) {
            try {
                return objectMapper.readValue(json, io.github.jbellis.brokk.agents.ArchitectAgent.ArchitectOptions.class);
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize ArchitectOptions from JSON: {}. Returning defaults.", json, e);
            }
        }
        return io.github.jbellis.brokk.agents.ArchitectAgent.ArchitectOptions.DEFAULTS;
    }

    @Override
    public boolean getArchitectRunInWorktree() {
        return Boolean.parseBoolean(projectProps.getProperty(ARCHITECT_RUN_IN_WORKTREE_KEY, "false"));
    }

    @Override
    public void setArchitectOptions(io.github.jbellis.brokk.agents.ArchitectAgent.ArchitectOptions options, boolean runInWorktree) {
        assert options != null;
        try {
            String json = objectMapper.writeValueAsString(options);
            projectProps.setProperty(ARCHITECT_OPTIONS_JSON_KEY, json);
            projectProps.setProperty(ARCHITECT_RUN_IN_WORKTREE_KEY, String.valueOf(runInWorktree));
            saveProjectProperties();
            logger.debug("Saved Architect options and worktree preference to project properties.");
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize ArchitectOptions to JSON: {}. Settings not saved.", options, e);
            // Not re-throwing as this is a preference, not critical state.
        }
    }

    public static String getGitHubToken() {
        var props = loadGlobalProperties();
        return props.getProperty(GITHUB_TOKEN_KEY, "");
    }

    @Override
    @Deprecated
    public String getJiraBaseUrl() {
        io.github.jbellis.brokk.IssueProvider provider = getIssuesProvider();
        if (provider.type() == io.github.jbellis.brokk.issues.IssueProviderType.JIRA &&
            provider.config() instanceof io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig jiraConfig) {
            return jiraConfig.baseUrl();
        }
        return "";
    }

    @Override
    @Deprecated
    public void setJiraBaseUrl(String baseUrl) {
        String trimmedValue = (baseUrl == null) ? "" : baseUrl.trim();
        io.github.jbellis.brokk.IssueProvider currentProvider = getIssuesProvider();
        io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig currentJiraConfig =
            (currentProvider.type() == io.github.jbellis.brokk.issues.IssueProviderType.JIRA &&
             currentProvider.config() instanceof io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig jc)
            ? jc : null;

        if (currentJiraConfig != null) { // Current provider is JIRA
            if (!Objects.equals(trimmedValue, currentJiraConfig.baseUrl())) {
                var newJiraConfig = new io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig(
                    trimmedValue, currentJiraConfig.apiToken(), currentJiraConfig.projectKey());
                setIssuesProvider(new io.github.jbellis.brokk.IssueProvider(io.github.jbellis.brokk.issues.IssueProviderType.JIRA, newJiraConfig));
            }
        } else { // Current provider is NOT JIRA
            if (!trimmedValue.isEmpty()) { // Trying to set a non-empty Jira base URL
                var newJiraConfig = new io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig(trimmedValue, "", "");
                setIssuesProvider(new io.github.jbellis.brokk.IssueProvider(io.github.jbellis.brokk.issues.IssueProviderType.JIRA, newJiraConfig));
            }
        }
    }

    @Override
    @Deprecated
    public String getJiraApiToken() {
        io.github.jbellis.brokk.IssueProvider provider = getIssuesProvider();
        if (provider.type() == io.github.jbellis.brokk.issues.IssueProviderType.JIRA &&
            provider.config() instanceof io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig jiraConfig) {
            return jiraConfig.apiToken();
        }
        return "";
    }

    @Override
    @Deprecated
    public void setJiraApiToken(String token) {
        String trimmedValue = (token == null) ? "" : token.trim();
        io.github.jbellis.brokk.IssueProvider currentProvider = getIssuesProvider();
        io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig currentJiraConfig =
            (currentProvider.type() == io.github.jbellis.brokk.issues.IssueProviderType.JIRA &&
             currentProvider.config() instanceof io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig jc)
            ? jc : null;

        if (currentJiraConfig != null) { // Current provider is JIRA
            if (!Objects.equals(trimmedValue, currentJiraConfig.apiToken())) {
                var newJiraConfig = new io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig(
                    currentJiraConfig.baseUrl(), trimmedValue, currentJiraConfig.projectKey());
                setIssuesProvider(new io.github.jbellis.brokk.IssueProvider(io.github.jbellis.brokk.issues.IssueProviderType.JIRA, newJiraConfig));
            }
        } else { // Current provider is NOT JIRA
            if (!trimmedValue.isEmpty()) { // Trying to set a non-empty Jira API token
                var newJiraConfig = new io.github.jbellis.brokk.issues.IssuesProviderConfig.JiraConfig("", trimmedValue, "");
                setIssuesProvider(new io.github.jbellis.brokk.IssueProvider(io.github.jbellis.brokk.issues.IssueProviderType.JIRA, newJiraConfig));
            }
        }
    }

    public static String getTheme() {
        var props = loadGlobalProperties();
        return props.getProperty("theme", "dark");
    }

    public static void setTheme(String theme) {
        var props = loadGlobalProperties();
        props.setProperty("theme", theme);
        saveGlobalProperties(props);
    }
    
    @NotNull
    public static String getBrokkKey() {
        var props = loadGlobalProperties();
        return props.getProperty("brokkApiKey", "");
    }

    public static void setBrokkKey(String key) {
        var props = loadGlobalProperties();
        if (key == null || key.isBlank()) {
            props.remove("brokkApiKey");
        } else {
            props.setProperty("brokkApiKey", key.trim());
        }
        saveGlobalProperties(props);
        isDataShareAllowedCache = null;
        logger.trace("Cleared data share allowed cache.");
    }

    @Override
    public boolean isDataShareAllowed() {
        if (isDataShareAllowedCache != null) {
            return isDataShareAllowedCache;
        }
        String brokkKey = getBrokkKey();
        if (brokkKey.isEmpty()) {
            isDataShareAllowedCache = true;
            return true;
        }
        boolean allowed = Service.getDataShareAllowed(brokkKey);
        isDataShareAllowedCache = allowed;
        logger.info("Data sharing allowed for organization: {}", allowed);
        return allowed;
    }

    public static void addSettingsChangeListener(SettingsChangeListener listener) {
        settingsChangeListeners.add(listener);
    }

    public static void removeSettingsChangeListener(SettingsChangeListener listener) {
        settingsChangeListeners.remove(listener);
    }

    public enum DataRetentionPolicy {
        IMPROVE_BROKK("Make Brokk Better for Everyone"),
        MINIMAL("Essential Use Only"),
        UNSET("Unset");
        private final String displayName;
        DataRetentionPolicy(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
        @Override public String toString() { return displayName; }
        public static DataRetentionPolicy fromString(String value) {
            if (value == null) return UNSET;
            for (DataRetentionPolicy policy : values()) {
                if (policy.name().equalsIgnoreCase(value)) return policy;
            }
            return UNSET;
        }
    }

    @Override
    public DataRetentionPolicy getDataRetentionPolicy() {
        if (!isDataShareAllowed()) {
            return DataRetentionPolicy.MINIMAL;
        }
        String value = projectProps.getProperty(DATA_RETENTION_POLICY_KEY);
        return DataRetentionPolicy.fromString(value);
    }

    @Override
    public void setDataRetentionPolicy(DataRetentionPolicy policy) {
        assert policy != null && policy != DataRetentionPolicy.UNSET : "Cannot set policy to UNSET or null";
        projectProps.setProperty(DATA_RETENTION_POLICY_KEY, policy.name());
        saveProjectProperties();
        logger.info("Set Data Retention Policy to {} for project {}", policy, root.getFileName());
    }

    public static final List<Service.FavoriteModel> DEFAULT_FAVORITE_MODELS = List.of(
            new Service.FavoriteModel("o3", Service.O3, Service.ReasoningLevel.DEFAULT),
            new Service.FavoriteModel("Gemini Pro 2.5", Service.GEMINI_2_5_PRO, Service.ReasoningLevel.DEFAULT),
            new Service.FavoriteModel("Sonnet 3.7", "claude-3.7-sonnet", Service.ReasoningLevel.DEFAULT),
            new Service.FavoriteModel("Flash 2.0", "gemini-2.0-flash", Service.ReasoningLevel.DEFAULT)
    );

    public static List<Service.FavoriteModel> loadFavoriteModels() {
        var props = loadGlobalProperties();
        String json = props.getProperty(FAVORITE_MODELS_KEY);
        if (json != null && !json.isEmpty()) {
            try {
                var typeFactory = objectMapper.getTypeFactory();
                var listType = typeFactory.constructCollectionType(List.class, Service.FavoriteModel.class);
                List<Service.FavoriteModel> loadedList = objectMapper.readValue(json, listType);
                logger.debug("Loaded {} favorite models from global properties.", loadedList.size());
                return loadedList;
            } catch (JsonProcessingException | ClassCastException e) {
                logger.error("Error loading/casting favorite models from JSON: {}", json, e);
            }
        }
        logger.debug("No favorite models found or error loading, returning defaults.");
        return new ArrayList<>(DEFAULT_FAVORITE_MODELS);
    }

    public static void saveFavoriteModels(List<Service.FavoriteModel> favorites) {
        assert favorites != null;
        var props = loadGlobalProperties();
        String newJson;
        try {
            newJson = objectMapper.writeValueAsString(favorites);
        } catch (JsonProcessingException e) {
            logger.error("Error serializing favorite models to JSON", e);
            return;
        }
        String oldJson = props.getProperty(FAVORITE_MODELS_KEY, "");
        if (!newJson.equals(oldJson)) {
            props.setProperty(FAVORITE_MODELS_KEY, newJson);
            saveGlobalProperties(props);
            logger.debug("Saved {} favorite models to global properties.", favorites.size());
        } else {
            logger.trace("Favorite models unchanged, skipping save.");
        }
    }

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

    private static void saveProjectsProperties(Properties props) {
        try {
            Files.createDirectories(PROJECTS_PROPERTIES_PATH.getParent());
            AtomicWrites.atomicSaveProperties(PROJECTS_PROPERTIES_PATH, props, "Brokk projects: recently opened and currently open");
        } catch (IOException e) {
            logger.error("Error saving projects properties: {}", e.getMessage());
        }
    }

    public static Map<Path, ProjectPersistentInfo> loadRecentProjects() {
        var result = new HashMap<Path, ProjectPersistentInfo>();
        var props = loadProjectsProperties();
        for (String key : props.stringPropertyNames()) {
            if (!key.contains(java.io.File.separator) || key.endsWith("_activeSession")) {
                continue;
            }
            String propertyValue = props.getProperty(key);
            try {
                ProjectPersistentInfo persistentInfo = objectMapper.readValue(propertyValue, ProjectPersistentInfo.class);
                result.put(Path.of(key), persistentInfo);
            } catch (JsonProcessingException e) {
                // Likely old-format timestamp, try to parse as long
                try {
                    long parsedLongValue = Long.parseLong(propertyValue);
                    ProjectPersistentInfo persistentInfo = ProjectPersistentInfo.fromTimestamp(parsedLongValue);
                    result.put(Path.of(key), persistentInfo);
                } catch (NumberFormatException nfe) {
                    logger.warn("Could not parse value for key '{}' in projects.properties as JSON or long: {}", key, propertyValue);
                }
            } catch (Exception e) {
                logger.warn("Error processing recent project entry for key '{}': {}", key, e.getMessage());
            }
        }
        return result;
    }

    public static void saveRecentProjects(Map<Path, ProjectPersistentInfo> projects) {
        var props = loadProjectsProperties();
        
        var sorted = projects.entrySet().stream()
                .sorted(Map.Entry.<Path, ProjectPersistentInfo>comparingByValue(Comparator.comparingLong(ProjectPersistentInfo::lastOpened)).reversed())
                .limit(10)
                .toList();
        
        // Collect current project paths to keep
        Set<String> pathsToKeep = sorted.stream()
                .map(entry -> entry.getKey().toAbsolutePath().toString())
                .collect(Collectors.toSet());

        List<String> keysToRemove = props.stringPropertyNames().stream()
                .filter(key -> key.contains(java.io.File.separator) && !key.endsWith("_activeSession"))
                .filter(key -> !pathsToKeep.contains(key))
                .toList();
        keysToRemove.forEach(props::remove);

        for (var entry : sorted) {
            Path projectPath = entry.getKey();
            ProjectPersistentInfo persistentInfo = entry.getValue();
            try {
                String jsonString = objectMapper.writeValueAsString(persistentInfo);
                props.setProperty(projectPath.toAbsolutePath().toString(), jsonString);
            } catch (JsonProcessingException e) {
                logger.error("Error serializing ProjectPersistentInfo for path '{}': {}", projectPath, e.getMessage());
            }
        }
        saveProjectsProperties(props);
    }

    public static void updateRecentProject(Path projectDir) {
        Path pathForRecentProjectsMap = projectDir;
        boolean isWorktree = false;
        
        if (GitRepo.hasGitRepo(projectDir)) {
            try (var tempRepo = new GitRepo(projectDir)) {
                isWorktree = tempRepo.isWorktree();
                if (isWorktree) {
                    pathForRecentProjectsMap = tempRepo.getGitTopLevel();
                }
            } catch (Exception e) {
                logger.warn("Could not determine if {} is a worktree during updateRecentProject: {}", projectDir, e.getMessage());
            }
        }
        
        var currentMap = loadRecentProjects();
        ProjectPersistentInfo persistentInfo = currentMap.get(pathForRecentProjectsMap);
        if (persistentInfo == null) {
            persistentInfo = ProjectPersistentInfo.fromTimestamp(System.currentTimeMillis());
        }
        
        long newTimestamp = System.currentTimeMillis();
        List<String> newOpenWorktrees = new ArrayList<>(persistentInfo.openWorktrees());
        
        if (isWorktree) {
            String worktreePathToAdd = projectDir.toAbsolutePath().normalize().toString();
            String mainProjectPathString = pathForRecentProjectsMap.toAbsolutePath().normalize().toString();
            if (!newOpenWorktrees.contains(worktreePathToAdd) && !worktreePathToAdd.equals(mainProjectPathString)) {
                newOpenWorktrees.add(worktreePathToAdd);
            }
        } else {
            addToOpenProjectsList(projectDir);
        }
        
        currentMap.put(pathForRecentProjectsMap, new ProjectPersistentInfo(newTimestamp, newOpenWorktrees));
        saveRecentProjects(currentMap);
    }

    private static void addToOpenProjectsList(Path projectDir) {
        var absPathStr = projectDir.toAbsolutePath().toString();
        var props = loadProjectsProperties();
        var openListStr = props.getProperty("openProjectsList", "");
        var openSet = new LinkedHashSet<>(Arrays.asList(openListStr.split(";")));
        openSet.remove("");
        if (openSet.add(absPathStr)) {
            props.setProperty("openProjectsList", String.join(";", openSet));
            saveProjectsProperties(props);
        }
    }

    public static void removeFromOpenProjectsListAndClearActiveSession(Path projectDir) {
        var absPathStr = projectDir.toAbsolutePath().toString();
        var props = loadProjectsProperties();
        boolean changed = false;
        var openListStr = props.getProperty("openProjectsList", "");
        var openSet = new LinkedHashSet<>(Arrays.asList(openListStr.split(";")));
        openSet.remove("");
        if (openSet.remove(absPathStr)) {
            props.setProperty("openProjectsList", String.join(";", openSet));
            changed = true;
        }
        if (props.remove(absPathStr + "_activeSession") != null) {
            changed = true;
        }
        if (changed) {
            saveProjectsProperties(props);
        }
        
        // Update ProjectPersistentInfo map
        var recentProjectsMap = loadRecentProjects();
        Path mainProjectPathKey = projectDir;
        boolean isWorktree = false;
        
        if (GitRepo.hasGitRepo(projectDir)) {
            try (var tempRepo = new GitRepo(projectDir)) {
                isWorktree = tempRepo.isWorktree();
                if (isWorktree) {
                    mainProjectPathKey = tempRepo.getGitTopLevel();
                }
            } catch (Exception e) {
                logger.warn("Could not determine if {} is a worktree during removeFromOpenProjectsListAndClearActiveSession: {}", projectDir, e.getMessage());
            }
        }
        
        boolean recentProjectsMapModified = false;
        
        if (isWorktree) {
            ProjectPersistentInfo mainProjectInfo = recentProjectsMap.get(mainProjectPathKey);
            if (mainProjectInfo != null) {
                List<String> openWorktrees = new ArrayList<>(mainProjectInfo.openWorktrees());
                if (openWorktrees.remove(projectDir.toAbsolutePath().normalize().toString())) {
                    recentProjectsMap.put(mainProjectPathKey, new ProjectPersistentInfo(mainProjectInfo.lastOpened(), openWorktrees));
                    recentProjectsMapModified = true;
                }
            }
        }
        
        if (recentProjectsMapModified) {
            saveRecentProjects(recentProjectsMap);
        }
    }

    public static List<Path> getOpenProjects() {
        var result = new ArrayList<Path>();
        var pathsToRemove = new ArrayList<String>();
        var props = loadProjectsProperties();
        var openListStr = props.getProperty("openProjectsList", "");
        if (openListStr.isEmpty()) return result;
        
        var openPathsInList = Arrays.asList(openListStr.split(";"));
        var finalPathsToOpen = new LinkedHashSet<Path>();
        var validPathsFromOpenList = new HashSet<Path>();
        
        // First pass: Process openProjectsList
        for (String pathStr : openPathsInList) {
            if (pathStr.isEmpty()) continue;
            try {
                var path = Path.of(pathStr);
                if (Files.isDirectory(path)) {
                    finalPathsToOpen.add(path);
                    validPathsFromOpenList.add(path);
                } else {
                    logger.warn("Removing invalid or non-existent project from open list: {}", pathStr);
                    pathsToRemove.add(pathStr);
                }
            } catch (Exception e) {
                logger.warn("Invalid path string in openProjectsList: {}", pathStr, e);
                pathsToRemove.add(pathStr);
            }
        }
        
        // Second pass: Add associated open worktrees for main projects found in openProjectsList
        var recentProjectsMap = loadRecentProjects();
        for (var entry : recentProjectsMap.entrySet()) {
            var mainProjectPathKey = entry.getKey();
            var persistentInfo = entry.getValue();
            if (validPathsFromOpenList.contains(mainProjectPathKey)) {
                for (String worktreePathStr : persistentInfo.openWorktrees()) {
                    if (worktreePathStr != null && !worktreePathStr.isBlank()) {
                        try {
                            var worktreePath = Path.of(worktreePathStr);
                            if (Files.isDirectory(worktreePath)) {
                                finalPathsToOpen.add(worktreePath);
                            } else {
                                logger.warn("Invalid worktree path '{}' found for main project '{}', not adding to open list.", worktreePathStr, mainProjectPathKey);
                            }
                        } catch (Exception e) {
                            logger.warn("Error processing worktree path '{}' for main project '{}': {}", worktreePathStr, mainProjectPathKey, e.getMessage());
                        }
                    }
                }
            }
        }
        
        // Cleanup openProjectsList property if necessary
        if (!pathsToRemove.isEmpty()) {
            var updatedOpenSet = new LinkedHashSet<>(openPathsInList);
            updatedOpenSet.removeAll(pathsToRemove);
            updatedOpenSet.remove("");
            props.setProperty("openProjectsList", String.join(";", updatedOpenSet));
            saveProjectsProperties(props);
        }
        
        result.addAll(finalPathsToOpen);
        return result;
    }
    
    public static Optional<String> getActiveSessionTitle(Path worktreeRoot) {
        var wsPropsPath = worktreeRoot.resolve(".brokk").resolve("workspace.properties");
        if (!Files.exists(wsPropsPath)) {
            return Optional.empty();
        }
        var props = new Properties();
        try (var reader = Files.newBufferedReader(wsPropsPath)) {
            props.load(reader);
        } catch (IOException e) {
            logger.warn("Error reading workspace properties at {}: {}", wsPropsPath, e.getMessage());
            return Optional.empty();
        }
        String sessionIdStr = props.getProperty("lastActiveSession");
        if (sessionIdStr == null || sessionIdStr.isBlank()) {
            return Optional.empty();
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(sessionIdStr.trim());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid session UUID '{}' in workspace properties at {}", sessionIdStr, wsPropsPath);
            return Optional.empty();
        }
        Path masterRootPath;
        if (GitRepo.hasGitRepo(worktreeRoot)) {
            try (var tempRepo = new GitRepo(worktreeRoot)) {
                masterRootPath = tempRepo.getGitTopLevel();
            } catch (Exception e) {
                logger.warn("Error determining git top level for {}: {}", worktreeRoot, e.getMessage());
                return Optional.empty();
            }
        } else {
            masterRootPath = worktreeRoot;
        }
        Path sessionZip = masterRootPath.resolve(".brokk").resolve("sessions").resolve(sessionId + ".zip");
        if (!Files.exists(sessionZip)) {
            logger.debug("Session zip not found at {} for session ID {}", sessionZip, sessionId);
            return Optional.empty();
        }
        try (var fs = FileSystems.newFileSystem(sessionZip, Map.of())) {
            Path manifestPath = fs.getPath("manifest.json");
            if (Files.exists(manifestPath)) {
                String json = Files.readString(manifestPath);
                var sessionInfo = objectMapper.readValue(json, SessionInfo.class);
                return Optional.of(sessionInfo.name());
            }
        } catch (IOException e) {
            logger.warn("Error reading session manifest from {}: {}", sessionZip.getFileName(), e.getMessage());
        }
        return Optional.empty();
    }


    @Override
    public List<SessionInfo> listSessions() {
        var sessions = new ArrayList<SessionInfo>();
        var sessionIdsFromManifests = new HashSet<UUID>();
        try {
            Files.createDirectories(sessionsDir);
            try (var stream = Files.list(sessionsDir)) {
                stream.filter(path -> path.toString().endsWith(".zip"))
                        .forEach(zipPath -> readSessionInfoFromZip(zipPath).ifPresent(sessionInfo -> {
                            sessions.add(sessionInfo);
                            sessionIdsFromManifests.add(sessionInfo.id());
                        }));
            }
        } catch (IOException e) {
            logger.error("Error listing session zip files in {}: {}", sessionsDir, e.getMessage());
        }
        var legacySessionIds = new HashSet<UUID>();
        if (Files.exists(legacySessionsIndexPath)) {
            logger.debug("Attempting to read legacy sessions from {}", legacySessionsIndexPath);
            try {
                var lines = Files.readAllLines(legacySessionsIndexPath);
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        try {
                            var legacySessionInfo = objectMapper.readValue(line, SessionInfo.class);
                            legacySessionIds.add(legacySessionInfo.id());
                            if (!sessionIdsFromManifests.contains(legacySessionInfo.id())) {
                                Path correspondingZip = getSessionHistoryPath(legacySessionInfo.id());
                                if (Files.exists(correspondingZip)) {
                                    sessions.add(legacySessionInfo);
                                    
                                    // Migrate on the spot - write manifest to zip
                                    try {
                                        writeSessionInfoToZip(correspondingZip, legacySessionInfo);
                                        sessionIdsFromManifests.add(legacySessionInfo.id());
                                        logger.info("Migrated session {} into its zip manifest", legacySessionInfo.id());
                                    } catch (IOException e) {
                                        logger.warn("Unable to migrate legacy session {}: {}", legacySessionInfo.id(), e.getMessage());
                                    }
                                } else {
                                    logger.warn("Orphaned session {} in legacy sessions.jsonl (no zip found), skipping.", legacySessionInfo.id());
                                }
                            }
                        } catch (JsonProcessingException e) {
                            logger.error("Failed to parse legacy SessionInfo from line: {}", line, e);
                        }
                    }
                }
                
                // Delete legacy file if all sessions were successfully migrated
                if (sessionIdsFromManifests.containsAll(legacySessionIds)) {
                    try {
                        Files.delete(legacySessionsIndexPath);
                        logger.info("Successfully migrated all legacy sessions and deleted {}", legacySessionsIndexPath.getFileName());
                    } catch (IOException e) {
                        logger.warn("Could not delete legacy sessions.jsonl: {}", e.getMessage());
                    }
                }
            } catch (IOException e) {
                logger.error("Error reading legacy sessions index {}: {}", legacySessionsIndexPath, e.getMessage());
            }
        }
        sessions.sort(Comparator.comparingLong(SessionInfo::modified).reversed());
        return sessions;
    }

    @Override
    public SessionInfo newSession(String name) {
        var sessionId = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();
        var newSessionInfo = new SessionInfo(sessionId, name, currentTime, currentTime);
        Path sessionHistoryPath = getSessionHistoryPath(sessionId);
        try {
            Files.createDirectories(sessionHistoryPath.getParent());
            // 1. Create the zip with empty history first. This ensures the zip file exists.
            var emptyHistory = new ContextHistory();
            HistoryIo.writeZip(emptyHistory, sessionHistoryPath); // Uses create="true"

            // 2. Now add/update manifest.json to the existing zip.
            writeSessionInfoToZip(sessionHistoryPath, newSessionInfo); // Should use create="false" as zip exists.
            logger.info("Created new session {} ({}) with manifest and empty history.", name, sessionId);
        } catch (IOException e) {
            logger.error("Error creating new session files for {} ({}): {}", name, sessionId, e.getMessage());
            throw new UncheckedIOException("Failed to create new session " + name, e);
        }
        return newSessionInfo;
    }

    @Override
    public void renameSession(UUID sessionId, String newName) {
        Path sessionHistoryPath = getSessionHistoryPath(sessionId);
        Optional<SessionInfo> oldInfoOpt = readSessionInfoFromZip(sessionHistoryPath); // Read before any modification
        if (oldInfoOpt.isPresent()) {
            SessionInfo oldInfo = oldInfoOpt.get();
            var updatedInfo = new SessionInfo(oldInfo.id(), newName, oldInfo.created(), System.currentTimeMillis()); // new modified time
            try {
                // No history content change, just update manifest
                writeSessionInfoToZip(sessionHistoryPath, updatedInfo);
                logger.info("Renamed session {} to '{}'", sessionId, newName);
            } catch (IOException e) {
                logger.error("Error writing updated manifest for renamed session {}: {}", sessionId, e.getMessage());
            }
        } else {
            logger.warn("Session ID {} not found (manifest missing in zip {}), cannot rename.", sessionId, sessionHistoryPath.getFileName());
        }
    }

    @Override
    public void deleteSession(UUID sessionId) {
        Path historyZipPath = getSessionHistoryPath(sessionId);
        try {
            boolean deleted = Files.deleteIfExists(historyZipPath);
            if (deleted) {
                logger.info("Deleted session zip: {}", historyZipPath.getFileName());
            } else {
                logger.warn("Session zip {} not found for deletion, or already deleted.", historyZipPath.getFileName());
            }
        } catch (IOException e) {
            logger.error("Error deleting history zip for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public SessionInfo copySession(UUID originalSessionId, String newSessionName) throws IOException {
        Path originalHistoryPath = getSessionHistoryPath(originalSessionId);
        if (!Files.exists(originalHistoryPath)) {
            throw new IOException("Original session %s not found, cannot copy".formatted(originalHistoryPath.getFileName()));
        }
        UUID newSessionId = UUID.randomUUID();
        Path newHistoryPath = getSessionHistoryPath(newSessionId);
        long currentTime = System.currentTimeMillis();
        var newSessionInfo = new SessionInfo(newSessionId, newSessionName, currentTime, currentTime);
        Files.createDirectories(newHistoryPath.getParent());
        Files.copy(originalHistoryPath, newHistoryPath);
        logger.info("Copied session zip {} to {}", originalHistoryPath.getFileName(), newHistoryPath.getFileName());
        writeSessionInfoToZip(newHistoryPath, newSessionInfo);
        logger.info("Updated manifest.json in new session zip {} for session ID {}", newHistoryPath.getFileName(), newSessionId);
        return newSessionInfo;
    }

    public Path getWorktreeStoragePath() {
        return Path.of(System.getProperty("user.home"), ".brokk", "worktrees", getMasterRootPathForConfig().getFileName().toString());
    }

    public void reserveSessionsForKnownWorktrees() {
        if (this.repo.isWorktree() || !(this.repo instanceof GitRepo gitRepo) || !gitRepo.supportsWorktrees()) {
            return;
        }
        logger.debug("Main project {} reserving sessions for its known worktrees.", this.root.getFileName());
        try {
            var worktrees = gitRepo.listWorktrees();
            for (var wtInfo : worktrees) {
                Path wtPath = wtInfo.path().toAbsolutePath().normalize();
                if (wtPath.equals(this.root)) continue;

                if (!Brokk.isProjectOpen(wtPath)) {
                    var wsPropsPath = wtPath.resolve(".brokk").resolve("workspace.properties");
                    if (Files.exists(wsPropsPath)) {
                        var props = new Properties();
                        try (var reader = Files.newBufferedReader(wsPropsPath)) {
                            props.load(reader);
                            String sessionIdStr = props.getProperty("lastActiveSession");
                            if (sessionIdStr != null && !sessionIdStr.isBlank()) {
                                UUID sessionId = UUID.fromString(sessionIdStr.trim());
                                if (SessionRegistry.claim(wtPath, sessionId)) {
                                    logger.info("Reserved session {} for non-open worktree {}", sessionId, wtPath.getFileName());
                                } else {
                                    logger.warn("Failed to reserve session {} for worktree {} (already claimed elsewhere or error).", sessionId, wtPath.getFileName());
                                }
                            }
                        } catch (IOException | IllegalArgumentException e) {
                            logger.warn("Error reading last active session for worktree {} or claiming it: {}", wtPath.getFileName(), e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error listing worktrees or reserving their sessions for main project {}: {}", this.root.getFileName(), e.getMessage(), e);
        }
    }
}
