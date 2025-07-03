package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.jbellis.brokk.Service.ModelConfig;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.issues.IssueProviderType;
import org.jetbrains.annotations.Nullable;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.util.AtomicWrites;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public final class MainProject extends AbstractProject {
    private static final Logger logger = LogManager.getLogger(MainProject.class); // Separate logger from AbstractProject

    private final Path propertiesFile;
    private final Properties projectProps;
    private final Path styleGuidePath;
    private final Path reviewGuidePath;
    private final SessionManager sessionManager;
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
    @Nullable private static volatile Boolean isDataShareAllowedCache = null;
    @Nullable private static Properties globalPropertiesCache = null; // protected by synchronized

    private static final Path BROKK_CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "brokk");
    private static final Path PROJECTS_PROPERTIES_PATH = BROKK_CONFIG_DIR.resolve("projects.properties");
    private static final Path GLOBAL_PROPERTIES_PATH = BROKK_CONFIG_DIR.resolve("brokk.properties");

    private final Path sessionsDir;

    public enum LlmProxySetting {BROKK, LOCALHOST, STAGING}

    private static final String LLM_PROXY_SETTING_KEY = "llmProxySetting";
    public static final String BROKK_PROXY_URL = "https://proxy.brokk.ai";
    public static final String LOCALHOST_PROXY_URL = "http://localhost:4000";
    public static final String STAGING_PROXY_URL = "https://staging.brokk.ai";

    private static final String DATA_RETENTION_POLICY_KEY = "dataRetentionPolicy";
    private static final String FAVORITE_MODELS_KEY = "favoriteModelsJson";

    public static final String DEFAULT_REVIEW_GUIDE = """
            When reviewing the pull request, please address the following points:
            - explain your understanding of what this PR is intended to do
            - does it accomplish its goals
            - does it conform to the style guidelines
            - what parts are the trickiest and how could they be simplified
            """.stripIndent();

    public record ProjectPersistentInfo(long lastOpened, List<String> openWorktrees) {
        public ProjectPersistentInfo {
        }

        public static ProjectPersistentInfo fromTimestamp(long lastOpened) {
            return new ProjectPersistentInfo(lastOpened, List.of());
        }
    }

    public MainProject(Path root) {
        super(root); // Initializes this.root and this.repo

        this.propertiesFile = this.masterRootPathForConfig.resolve(".brokk").resolve("project.properties");
        this.styleGuidePath = this.masterRootPathForConfig.resolve(".brokk").resolve("style.md");
        this.reviewGuidePath = this.masterRootPathForConfig.resolve(".brokk").resolve("review.md");
        this.sessionsDir = this.masterRootPathForConfig.resolve(".brokk").resolve("sessions");
        this.sessionManager = new SessionManager(sessionsDir);

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
                    props.remove(typeInfo.oldReasoningKey());
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
        Objects.requireNonNull(typeInfo, "typeInfo should not be null for modelTypeKey: " + modelTypeKey);

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
        var props = loadGlobalProperties();
        var typeInfo = MODEL_TYPE_INFOS.get(modelTypeKey);
        Objects.requireNonNull(typeInfo, "typeInfo should not be null for modelTypeKey: " + modelTypeKey);

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
        if (format.isBlank() || format.trim().equals(DEFAULT_COMMIT_MESSAGE_FORMAT)) {
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
        if (languages.isEmpty() || ((languages.size() == 1) && languages.contains(Language.NONE))) {
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
        projectProps.setProperty(CODE_AGENT_TEST_SCOPE_KEY, scope.name());
        saveProjectProperties();
    }

    @Nullable private volatile IssueProvider issuesProviderCache = null;

    @Override
    public IssueProvider getIssuesProvider() {
        if (issuesProviderCache != null) {
            return issuesProviderCache;
        }

        String json = projectProps.getProperty(ISSUES_PROVIDER_JSON_KEY);
        if (json != null && !json.isBlank()) {
            try {
                issuesProviderCache = objectMapper.readValue(json, IssueProvider.class);
                return issuesProviderCache;
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize IssueProvider from JSON: {}. Will attempt migration or default.", json, e);
            }
        }
        
        // Defaulting logic if no JSON and no old properties
        if (isGitHubRepo()) {
            issuesProviderCache = IssueProvider.github();
        } else {
            issuesProviderCache = IssueProvider.none();
        }

        // Save the default so it's persisted
        setIssuesProvider(issuesProviderCache);
        logger.info("Defaulted issue provider to {} for project {}", issuesProviderCache.type(), getRoot().getFileName());
        return issuesProviderCache;
    }

    @Override
    public void setIssuesProvider(IssueProvider provider) {
        IssueProvider oldProvider = this.issuesProviderCache;
        IssueProviderType oldType = null;
        if (oldProvider != null) {
            oldType = oldProvider.type();
        } else {
            // Attempt to load from props if cache is null to get a definitive "before" type
            String currentJsonInProps = projectProps.getProperty(ISSUES_PROVIDER_JSON_KEY);
            if (currentJsonInProps != null && !currentJsonInProps.isBlank()) {
                try {
                    IssueProvider providerFromProps = objectMapper.readValue(currentJsonInProps, IssueProvider.class);
                    oldType = providerFromProps.type();
                } catch (JsonProcessingException e) {
                    // Log or ignore, oldType remains null or determined by migration if applicable
                    logger.debug("Could not parse existing IssueProvider JSON from properties while determining old type: {}", e.getMessage());
                }
            }
        }

        var newType = provider.type();

        try {
            String json = objectMapper.writeValueAsString(provider);
            projectProps.setProperty(ISSUES_PROVIDER_JSON_KEY, json);
            this.issuesProviderCache = provider; // Update cache

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

            // Notify listeners if the provider *type* has changed.
            if (oldType != newType) {
                logger.debug("Issue provider type changed from {} to {}. Notifying listeners.", oldType, newType);
                notifyIssueProviderChanged();
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize IssueProvider to JSON: {}. Settings not saved.", provider, e);
            throw new RuntimeException("Failed to serialize IssueProvider", e);
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

    @Override
    public String getReviewGuide() {
        try {
            if (Files.exists(reviewGuidePath)) {
                return Files.readString(reviewGuidePath);
            }
        } catch (IOException e) {
            logger.error("Error reading review guide: {}", e.getMessage());
        }
        return ""; // Return empty string if not found or error
    }

    @Override
    public void saveReviewGuide(String reviewGuide) {
        try {
            Files.createDirectories(reviewGuidePath.getParent());
            AtomicWrites.atomicOverwrite(reviewGuidePath, reviewGuide);
        } catch (IOException e) {
            logger.error("Error saving review guide: {}", e.getMessage());
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
        if (token.isBlank()) {
            props.remove(GITHUB_TOKEN_KEY);
        } else {
            props.setProperty(GITHUB_TOKEN_KEY, token.trim());
        }
        saveGlobalProperties(props);
        notifyGitHubTokenChanged();
    }

    private static void notifyIssueProviderChanged() {
        for (SettingsChangeListener listener : settingsChangeListeners) {
            try {
                listener.issueProviderChanged();
            } catch (Exception e) {
                logger.error("Error notifying listener of issue provider change", e);
            }
        }
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
    public ArchitectAgent.ArchitectOptions getArchitectOptions() {
        String json = projectProps.getProperty(ARCHITECT_OPTIONS_JSON_KEY);
        if (json != null && !json.isBlank()) {
            try {
                return objectMapper.readValue(json, ArchitectAgent.ArchitectOptions.class);
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize ArchitectOptions from JSON: {}. Returning defaults.", json, e);
            }
        }
        return ArchitectAgent.ArchitectOptions.DEFAULTS;
    }

    @Override
    public boolean getArchitectRunInWorktree() {
        return Boolean.parseBoolean(projectProps.getProperty(ARCHITECT_RUN_IN_WORKTREE_KEY, "false"));
    }

    @Override
    public void setArchitectOptions(ArchitectAgent.ArchitectOptions options, boolean runInWorktree) {
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

    public static String getTheme() {
        var props = loadGlobalProperties();
        return props.getProperty("theme", "dark");
    }

    public static void setTheme(String theme) {
        var props = loadGlobalProperties();
        props.setProperty("theme", theme);
        saveGlobalProperties(props);
    }
    
    public static String getBrokkKey() {
        var props = loadGlobalProperties();
        return props.getProperty("brokkApiKey", "");
    }

    public static void setBrokkKey(String key) {
        var props = loadGlobalProperties();
        if (key.isBlank()) {
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
        assert policy != DataRetentionPolicy.UNSET : "Cannot set policy to UNSET or null";
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
        var allLoadedEntries = new HashMap<Path, ProjectPersistentInfo>();
        var props = loadProjectsProperties();
        for (String key : props.stringPropertyNames()) {
            if (!key.contains(java.io.File.separator) || key.endsWith("_activeSession")) {
                continue;
            }
            String propertyValue = props.getProperty(key);
            try {
                Path projectPath = Path.of(key); // Create path once
                ProjectPersistentInfo persistentInfo = objectMapper.readValue(propertyValue, ProjectPersistentInfo.class);
                allLoadedEntries.put(projectPath, persistentInfo);
            } catch (JsonProcessingException e) {
                // Likely old-format timestamp, try to parse as long
                try {
                    Path projectPath = Path.of(key); // Create path once
                    long parsedLongValue = Long.parseLong(propertyValue);
                    ProjectPersistentInfo persistentInfo = ProjectPersistentInfo.fromTimestamp(parsedLongValue);
                    allLoadedEntries.put(projectPath, persistentInfo);
                } catch (NumberFormatException nfe) {
                    logger.warn("Could not parse value for key '{}' in projects.properties as JSON or long: {}", key, propertyValue);
                }
            } catch (Exception e) {
                logger.warn("Error processing recent project entry for key '{}': {}", key, e.getMessage());
            }
        }

        var validEntries = new HashMap<Path, ProjectPersistentInfo>();
        boolean entriesFiltered = false;

        for (Map.Entry<Path, ProjectPersistentInfo> entry : allLoadedEntries.entrySet()) {
            Path projectPath = entry.getKey();
            ProjectPersistentInfo persistentInfo = entry.getValue();
            if (Files.isDirectory(projectPath)) {
                validEntries.put(projectPath, persistentInfo);
            } else {
                logger.warn("Recent project path '{}' no longer exists or is not a directory. Removing from recent projects list.", projectPath);
                entriesFiltered = true;
            }
        }

        if (entriesFiltered) {
            saveRecentProjects(validEntries); // Persist the cleaned list
        }

        return validEntries;
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
                    if (!worktreePathStr.isBlank()) {
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
        return SessionManager.getActiveSessionTitle(worktreeRoot);
    }

    @Override
    public void close() {
        super.close();
        sessionManager.close();
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

    @Override
    public SessionManager getSessionManager() {
        return sessionManager;
    }
}
