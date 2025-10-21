package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jakewharton.disklrucache.DiskLruCache;
import io.github.jbellis.brokk.Service.ModelConfig;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitRepoFactory;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.issues.IssueProviderType;
import io.github.jbellis.brokk.mcp.McpConfig;
import io.github.jbellis.brokk.util.AtomicWrites;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.GlobalUiSettings;
import java.io.File;
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
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.util.SystemReader;
import org.jetbrains.annotations.Nullable;

public final class MainProject extends AbstractProject {
    private static final Logger logger =
            LogManager.getLogger(MainProject.class); // Separate logger from AbstractProject

    private final Path propertiesFile;
    private final Properties projectProps;
    private final Path styleGuidePath;
    private final Path reviewGuidePath;
    private final SessionManager sessionManager;
    private volatile CompletableFuture<BuildAgent.BuildDetails> detailsFuture = new CompletableFuture<>();

    @Nullable
    private volatile DiskLruCache diskCache = null;

    private static final long DEFAULT_DISK_CACHE_SIZE = 10L * 1024L * 1024L; // 10 MB

    private static final String BUILD_DETAILS_KEY = "buildDetailsJson";
    private static final String LIVE_DEPENDENCIES_KEY = "liveDependencies";
    private static final String CODE_INTELLIGENCE_LANGUAGES_KEY = "code_intelligence_languages";
    private static final String GITHUB_TOKEN_KEY = "githubToken";

    // Keys for GitHub clone preferences (global user settings)
    private static final String GITHUB_CLONE_PROTOCOL_KEY = "githubCloneProtocol";
    private static final String GITHUB_SHALLOW_CLONE_ENABLED_KEY = "githubShallowCloneEnabled";
    private static final String GITHUB_SHALLOW_CLONE_DEPTH_KEY = "githubShallowCloneDepth";

    // New key for the IssueProvider record as JSON
    private static final String ISSUES_PROVIDER_JSON_KEY = "issuesProviderJson";

    // Keys for Architect Options persistence
    private static final String ARCHITECT_RUN_IN_WORKTREE_KEY = "architectRunInWorktree";
    private static final String MCP_CONFIG_JSON_KEY = "mcpConfigJson";

    // Keys for Plan First and Search First workspace preferences
    private static final String PLAN_FIRST_KEY = "planFirst";
    private static final String SEARCH_FIRST_KEY = "searchFirst";
    private static final String PROP_INSTRUCTIONS_ASK = "instructions.ask";

    private static final String LAST_MERGE_MODE_KEY = "lastMergeMode";
    private static final String MIGRATIONS_TO_SESSIONS_V3_COMPLETE_KEY = "migrationsToSessionsV3Complete";

    // Old keys for migration
    private static final String OLD_ISSUE_PROVIDER_ENUM_KEY = "issueProvider"; // Stores the enum name (GITHUB, JIRA)
    private static final String JIRA_PROJECT_BASE_URL_KEY = "jiraProjectBaseUrl";
    private static final String JIRA_PROJECT_API_TOKEN_KEY = "jiraProjectApiToken";
    private static final String JIRA_PROJECT_KEY_KEY = "jiraProjectKey";

    private record ModelTypeInfo(String configKey, ModelConfig preferredConfig) {}

    private static final Map<String, ModelTypeInfo> MODEL_TYPE_INFOS =
            Map.of("Code", new ModelTypeInfo("codeConfig", new ModelConfig(Service.GPT_5_MINI)));

    private static final String RUN_COMMAND_TIMEOUT_SECONDS_KEY = "runCommandTimeoutSeconds";
    private static final long DEFAULT_RUN_COMMAND_TIMEOUT_SECONDS = Environment.DEFAULT_TIMEOUT.toSeconds();
    private static final String CODE_AGENT_TEST_SCOPE_KEY = "codeAgentTestScope";
    private static final String COMMIT_MESSAGE_FORMAT_KEY = "commitMessageFormat";
    private static final String EXCEPTION_REPORTING_ENABLED_KEY = "exceptionReportingEnabled";

    private static final List<SettingsChangeListener> settingsChangeListeners = new CopyOnWriteArrayList<>();

    public static final String DEFAULT_COMMIT_MESSAGE_FORMAT =
            """
                                                               The commit message should be structured as follows: <type>: <description>
                                                               Use these for <type>: debug, fix, feat, chore, config, docs, style, refactor, perf, test, enh
                                                               """;

    @Nullable
    private static volatile Boolean isDataShareAllowedCache = null;

    @Nullable
    private static Properties globalPropertiesCache = null; // protected by synchronized

    private static final Path BROKK_CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "brokk");
    private static final Path PROJECTS_PROPERTIES_PATH = BROKK_CONFIG_DIR.resolve("projects.properties");
    private static final Path GLOBAL_PROPERTIES_PATH = BROKK_CONFIG_DIR.resolve("brokk.properties");
    private static final Path OUT_OF_MEMORY_EXCEPTION_FLAG = BROKK_CONFIG_DIR.resolve("oom.flag");

    public enum LlmProxySetting {
        BROKK,
        LOCALHOST,
        STAGING
    }

    public enum StartupOpenMode {
        LAST,
        ALL
    }

    private static final String LLM_PROXY_SETTING_KEY = "llmProxySetting";
    public static final String BROKK_PROXY_URL = "https://proxy.brokk.ai";
    public static final String LOCALHOST_PROXY_URL = "http://localhost:4000";
    public static final String STAGING_PROXY_URL = "https://staging.brokk.ai";
    public static final String BROKK_SERVICE_URL = "https://app.brokk.ai";
    public static final String STAGING_SERVICE_URL = "https://brokk-backend-staging.up.railway.app";

    private static final String DATA_RETENTION_POLICY_KEY = "dataRetentionPolicy";
    private static final String FAVORITE_MODELS_KEY = "favoriteModelsJson";

    public static final String DEFAULT_REVIEW_GUIDE =
            """
            When reviewing the pull request, please address the following points:
            - Explain your understanding of what this PR is intended to do.
            - Does it accomplish its goals in the simplest way possible?
            - What parts are the trickiest and how could they be simplified?
            - What additional tests, if any, would add the most value?

            Conclude with a summary of serious functional or design issues ONLY.
            """;

    public record ProjectPersistentInfo(long lastOpened, List<String> openWorktrees) {
        public ProjectPersistentInfo {}

        public static ProjectPersistentInfo fromTimestamp(long lastOpened) {
            return new ProjectPersistentInfo(lastOpened, List.of());
        }
    }

    public MainProject(Path root) {
        super(root); // Initializes this.root and this.repo

        this.propertiesFile = this.masterRootPathForConfig.resolve(BROKK_DIR).resolve(PROJECT_PROPERTIES_FILE);
        this.styleGuidePath = this.masterRootPathForConfig.resolve(BROKK_DIR).resolve(STYLE_GUIDE_FILE);
        this.reviewGuidePath = this.masterRootPathForConfig.resolve(BROKK_DIR).resolve(REVIEW_GUIDE_FILE);
        var sessionsDir = this.masterRootPathForConfig.resolve(BROKK_DIR).resolve(SESSIONS_DIR);
        this.sessionManager = new SessionManager(sessionsDir);

        this.projectProps = new Properties();

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

        // Migrate Architect options from projectProps to workspace properties (centralized in AbstractProject)
        boolean needsProjectSave = false;
        boolean migratedArchitectSettings = false;

        if (projectProps.containsKey(ARCHITECT_RUN_IN_WORKTREE_KEY)) {
            if (!workspaceProps.containsKey(ARCHITECT_RUN_IN_WORKTREE_KEY)
                    || !workspaceProps
                            .getProperty(ARCHITECT_RUN_IN_WORKTREE_KEY)
                            .equals(projectProps.getProperty(ARCHITECT_RUN_IN_WORKTREE_KEY))) {
                workspaceProps.setProperty(
                        ARCHITECT_RUN_IN_WORKTREE_KEY, projectProps.getProperty(ARCHITECT_RUN_IN_WORKTREE_KEY));
                migratedArchitectSettings = true;
            }
            projectProps.remove(ARCHITECT_RUN_IN_WORKTREE_KEY);
            needsProjectSave = true;
        }

        // Migrate Live Dependencies from projectProps to workspace properties
        boolean migratedLiveDeps = false;
        if (projectProps.containsKey(LIVE_DEPENDENCIES_KEY)) {
            if (!workspaceProps.containsKey(LIVE_DEPENDENCIES_KEY)) {
                workspaceProps.setProperty(LIVE_DEPENDENCIES_KEY, projectProps.getProperty(LIVE_DEPENDENCIES_KEY));
                migratedLiveDeps = true;
            }
            projectProps.remove(LIVE_DEPENDENCIES_KEY);
            needsProjectSave = true;
        }

        if (migratedArchitectSettings || migratedLiveDeps) { // Data was written to workspace properties
            saveWorkspaceProperties();
            if (migratedArchitectSettings) {
                logger.info(
                        "Migrated Architect options from project.properties to workspace.properties for {}",
                        root.getFileName());
            }
            if (migratedLiveDeps) {
                logger.info(
                        "Migrated Live Dependencies from project.properties to workspace.properties for {}",
                        root.getFileName());
            }
        }
        if (needsProjectSave) { // Keys were removed from projectProps
            saveProjectProperties();
            if (!migratedArchitectSettings) { // Log if keys were only removed but not "migrated" (i.e. already in
                // workspace)
                logger.info(
                        "Removed Architect/Dependency options from project.properties (already in or now moved to workspace.properties) for {}",
                        root.getFileName());
            }
        }

        // Load build details AFTER projectProps might have been modified by migration (though build details keys are
        // not affected here)
        var bd = loadBuildDetailsInternal(); // Uses projectProps
        if (!bd.equals(BuildAgent.BuildDetails.EMPTY)) {
            this.detailsFuture.complete(bd);
        }

        // Initialize cache and trigger migration/defaulting if necessary
        this.issuesProviderCache = getIssuesProvider();
    }

    @Override
    public MainProject getParent() {
        return this;
    }

    @Override
    public MainProject getMainProject() {
        return this;
    }

    @Override
    public synchronized DiskLruCache getDiskCache() {
        if (diskCache != null) {
            return diskCache;
        }
        var cacheDir = getMasterRootPathForConfig().resolve(BROKK_DIR).resolve("cache");
        try {
            Files.createDirectories(cacheDir);
            diskCache = DiskLruCache.open(cacheDir.toFile(), 1, 1, DEFAULT_DISK_CACHE_SIZE);
            logger.debug("Initialized disk cache at {} (max {} bytes)", cacheDir, DEFAULT_DISK_CACHE_SIZE);
            return diskCache;
        } catch (IOException e) {
            logger.error("Unable to open disk cache at {}: {}", cacheDir, e.getMessage());
            throw new RuntimeException("Unable to open disk cache", e);
        }
    }

    public static synchronized Properties loadGlobalProperties() {
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
        globalPropertiesCache = (Properties) props.clone();
        return props;
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
        setBuildDetails(details);
    }

    public void setBuildDetails(BuildAgent.BuildDetails details) {
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

        // read user-specified value
        String jsonString = props.getProperty(typeInfo.configKey());
        if (jsonString != null && !jsonString.isBlank()) {
            try {
                var mc = objectMapper.readValue(jsonString, ModelConfig.class);
                // Null Away doesn't prevent Jackson from reading a null via reflection. All the
                // "official" Jackson ways to fix this are horrible.
                @SuppressWarnings("RedundantNullCheck")
                ModelConfig checkedMc = (mc.tier() == null)
                        ? new ModelConfig(mc.name(), mc.reasoning(), Service.ProcessingTier.DEFAULT)
                        : mc;
                return checkedMc;
            } catch (JsonProcessingException e) {
                logger.warn(
                        "Error parsing ModelConfig JSON for {} from key '{}': {}. Using preferred default. JSON: '{}'",
                        modelTypeKey,
                        typeInfo.configKey(),
                        e.getMessage(),
                        jsonString);
            }
        }

        // fallback to hardcoded default
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
            logger.error(
                    "Error serializing ModelConfig for {} (key '{}'): {}",
                    modelTypeKey,
                    typeInfo.configKey(),
                    config,
                    e);
            throw new RuntimeException("Failed to serialize ModelConfig for " + modelTypeKey, e);
        }
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

    public long getRunCommandTimeoutSeconds() {
        String valueStr = projectProps.getProperty(RUN_COMMAND_TIMEOUT_SECONDS_KEY);
        if (valueStr == null) {
            return DEFAULT_RUN_COMMAND_TIMEOUT_SECONDS;
        }
        try {
            long seconds = Long.parseLong(valueStr);
            return seconds > 0 ? seconds : DEFAULT_RUN_COMMAND_TIMEOUT_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_RUN_COMMAND_TIMEOUT_SECONDS;
        }
    }

    public void setRunCommandTimeoutSeconds(long seconds) {
        if (seconds > 0 && seconds != DEFAULT_RUN_COMMAND_TIMEOUT_SECONDS) {
            projectProps.setProperty(RUN_COMMAND_TIMEOUT_SECONDS_KEY, String.valueOf(seconds));
        } else {
            projectProps.remove(RUN_COMMAND_TIMEOUT_SECONDS_KEY);
        }
        saveProjectProperties();
    }

    /**
     * Returns the size of the given {@link ProjectFile} in bytes. Any {@link IOException} is logged and a size of
     * {@code 0} is returned so that a single problematic file does not break language detection.
     */
    private static long getFileSize(ProjectFile pf) {
        try {
            return Files.size(pf.absPath());
        } catch (IOException e) {
            logger.warn("Unable to determine size of file {}: {}", pf, e.getMessage());
            return 0L;
        }
    }

    /**
     * Detects the primary Language used by a dependency directory by scanning file extensions inside it and selecting
     * the most frequently occurring language. Falls back to Language.NONE if none detected.
     */
    private static Language detectLanguageForDependency(ProjectFile depDir) {
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

    @Override
    public Set<Language> getAnalyzerLanguages() {
        String langsProp = projectProps.getProperty(CODE_INTELLIGENCE_LANGUAGES_KEY);
        if (langsProp != null && !langsProp.isBlank()) {
            return Arrays.stream(langsProp.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(langName -> {
                        try {
                            return Languages.valueOf(langName.toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException e) {
                            logger.warn("Invalid language '{}' in project properties, ignoring.", langName);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }

        Map<Language, Long> languageSizes = repo.getTrackedFiles().stream() // repo from AbstractProject
                .filter(pf -> Languages.fromExtension(pf.extension()) != Languages.NONE)
                .collect(Collectors.groupingBy(
                        pf -> Languages.fromExtension(pf.extension()),
                        Collectors.summingLong(MainProject::getFileSize)));

        if (languageSizes.isEmpty()) {
            logger.debug(
                    "No files with recognized (non-NONE) languages found for {}. Defaulting to Language.NONE.", root);
            return Set.of(Languages.NONE);
        }

        long totalRecognizedBytes =
                languageSizes.values().stream().mapToLong(Long::longValue).sum();
        Set<Language> detectedLanguages = new HashSet<>();

        languageSizes.entrySet().stream()
                .filter(entry -> (double) entry.getValue() / totalRecognizedBytes >= 0.10)
                .forEach(entry -> detectedLanguages.add(entry.getKey()));

        if (detectedLanguages.isEmpty()) {
            var mostCommonEntry = languageSizes.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElseThrow();
            detectedLanguages.add(mostCommonEntry.getKey());
            logger.debug(
                    "No language met 10% threshold for {}. Adding most common: {}",
                    root, mostCommonEntry.getKey().name());
        }

        if (languageSizes.containsKey(Languages.SQL)) {
            boolean addedByThisRule = detectedLanguages.add(Languages.SQL);
            if (addedByThisRule) {
                logger.debug("SQL files present for {}, ensuring SQL is included in detected languages.", root);
            }
        }
        logger.debug(
                "Auto-detected languages for {}: {}",
                root,
                detectedLanguages.stream().map(Language::name).collect(Collectors.joining(", ")));
        return detectedLanguages;
    }

    @Override
    public void setAnalyzerLanguages(Set<Language> languages) {
        if (languages.isEmpty() || ((languages.size() == 1) && languages.contains(Languages.NONE))) {
            projectProps.remove(CODE_INTELLIGENCE_LANGUAGES_KEY);
        } else {
            String langsString = languages.stream().map(Language::name).collect(Collectors.joining(","));
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

    @Nullable
    private volatile IssueProvider issuesProviderCache = null;

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
                logger.error(
                        "Failed to deserialize IssueProvider from JSON: {}. Will attempt migration or default.",
                        json,
                        e);
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
        logger.info(
                "Defaulted issue provider to {} for project {}",
                issuesProviderCache.type(),
                getRoot().getFileName());
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
                    logger.debug(
                            "Could not parse existing IssueProvider JSON from properties while determining old type: {}",
                            e.getMessage());
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
            logger.info(
                    "Set issue provider to type '{}' for project {}",
                    provider.type(),
                    getRoot().getFileName());

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
            Properties existingProps = new Properties();
            if (Files.exists(propertiesFile)) {
                try (var reader = Files.newBufferedReader(propertiesFile)) {
                    existingProps.load(reader);
                } catch (IOException e) {
                    /* ignore loading error, will attempt to save anyway */
                }
            }

            if (Objects.equals(existingProps, projectProps)) {
                return;
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
        String remoteUrl = gitRepo.remote().getUrl("origin");
        if (remoteUrl == null || remoteUrl.isBlank()) return false;
        return remoteUrl.contains("github.com");
    }

    @Override
    public boolean isGitIgnoreSet() {
        try {
            var gitignorePath = getMasterRootPathForConfig().resolve(".gitignore");
            if (isBrokkIgnored(gitignorePath)) {
                logger.debug(".gitignore at {} is set to ignore Brokk files.", gitignorePath);
                return true;
            }
        } catch (IOException e) {
            logger.error(
                    "Error checking .gitignore at {}: {}",
                    getMasterRootPathForConfig().resolve(".gitignore"),
                    e.getMessage());
        }
        try {
            var gitUserConfig = SystemReader.getInstance().getUserConfig();
            var excludesFile = gitUserConfig.getString("core", null, "excludesfile");
            if (excludesFile != null && !excludesFile.isBlank()) {
                try {
                    var excludesFilePath = Path.of(excludesFile);
                    if (isBrokkIgnored(excludesFilePath)) {
                        logger.debug("core.excludesfile at {} is set to ignore Brokk files.", excludesFilePath);
                        return true;
                    }
                } catch (IOException e) {
                    logger.error("Error checking core.excludesfile at {}: {}", excludesFile, e.getMessage());
                }
            }
        } catch (IOException | ConfigInvalidException e) {
            logger.error("Error checking core.excludesfile setting in ~/.gitconfig: {}", e.getMessage());
        }
        return false;
    }

    private static boolean isBrokkIgnored(Path gitignorePath) throws IOException {
        if (Files.exists(gitignorePath)) {
            var content = Files.readString(gitignorePath);
            return content.contains(".brokk/") || content.contains(".brokk/**");
        }
        return false;
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

    public static String getServiceUrl() {
        return switch (getProxySetting()) {
            case BROKK -> BROKK_SERVICE_URL;
            case LOCALHOST -> BROKK_SERVICE_URL;
            case STAGING -> STAGING_SERVICE_URL;
        };
    }

    public static MainProject.StartupOpenMode getStartupOpenMode() {
        var props = loadGlobalProperties();
        String val = props.getProperty(STARTUP_OPEN_MODE_KEY, StartupOpenMode.LAST.name());
        try {
            return StartupOpenMode.valueOf(val);
        } catch (IllegalArgumentException e) {
            return StartupOpenMode.LAST;
        }
    }

    public static void setStartupOpenMode(MainProject.StartupOpenMode mode) {
        var props = loadGlobalProperties();
        props.setProperty(STARTUP_OPEN_MODE_KEY, mode.name());
        saveGlobalProperties(props);
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
    public boolean getArchitectRunInWorktree() {
        return Boolean.parseBoolean(workspaceProps.getProperty(ARCHITECT_RUN_IN_WORKTREE_KEY, "false"));
    }

    @Override
    public boolean getPlanFirst() {
        return getLayoutBoolean(PLAN_FIRST_KEY);
    }

    @Override
    public void setPlanFirst(boolean v) {
        setLayoutBoolean(PLAN_FIRST_KEY, v);
    }

    @Override
    public boolean getSearch() {
        return getLayoutBoolean(SEARCH_FIRST_KEY);
    }

    @Override
    public void setSearch(boolean v) {
        setLayoutBoolean(SEARCH_FIRST_KEY, v);
    }

    @Override
    public boolean getInstructionsAskMode() {
        return getLayoutBoolean(PROP_INSTRUCTIONS_ASK);
    }

    @Override
    public void setInstructionsAskMode(boolean ask) {
        setLayoutBoolean(PROP_INSTRUCTIONS_ASK, ask);
    }

    private boolean getLayoutBoolean(String key) {
        // Per-project first if enabled; else global. If per-project is enabled but unset, fallback to global.
        if (GlobalUiSettings.isPersistPerProjectBounds()) {
            String v = workspaceProps.getProperty(key);
            if (v != null) {
                return Boolean.parseBoolean(v);
            }
        }
        var props = loadGlobalProperties();
        return Boolean.parseBoolean(props.getProperty(key, "true"));
    }

    private void setLayoutBoolean(String key, boolean v) {
        // Always persist globally so the preference carries across projects.
        var props = loadGlobalProperties();
        props.setProperty(key, String.valueOf(v));
        saveGlobalProperties(props);

        // Persist per-project only when per-project layout persistence is enabled.
        if (GlobalUiSettings.isPersistPerProjectBounds()) {
            workspaceProps.setProperty(key, String.valueOf(v));
            saveWorkspaceProperties();
        }
    }

    @Override
    public McpConfig getMcpConfig() {
        var props = loadGlobalProperties();
        String json = props.getProperty(MCP_CONFIG_JSON_KEY);
        if (json == null || json.isBlank()) {
            return McpConfig.EMPTY;
        }
        logger.info("Deserializing McpConfig from JSON: {}", json);
        try {
            return objectMapper.readValue(json, McpConfig.class);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize McpConfig from JSON. JSON: {}", json, e);
            return McpConfig.EMPTY;
        }
    }

    @Override
    public void setMcpConfig(McpConfig config) {
        var props = loadGlobalProperties();
        try {
            if (config.servers().isEmpty()) {
                props.remove(MCP_CONFIG_JSON_KEY);
            } else {
                String newJson = objectMapper.writeValueAsString(config);
                logger.info("Serialized McpConfig to JSON: {}", newJson);
                props.setProperty(MCP_CONFIG_JSON_KEY, newJson);
            }
            saveGlobalProperties(props);
            logger.debug("Saved MCP configuration to global properties.");
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize McpConfig to JSON: {}. Settings not saved.", config, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Set<Dependency> getLiveDependencies() {
        var allDeps = getAllOnDiskDependencies();
        var liveDepsNames = workspaceProps.getProperty(LIVE_DEPENDENCIES_KEY);

        Set<ProjectFile> selected;
        if (liveDepsNames == null) {
            // Property not set: default to all dependencies enabled
            selected = allDeps;
        } else if (liveDepsNames.isBlank()) {
            // Property explicitly set but empty: user disabled all dependencies
            return Set.of();
        } else {
            var liveNamesSet = Arrays.stream(liveDepsNames.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());

            selected = allDeps.stream()
                    .filter(dep -> {
                        // .brokk/dependencies/dep-name/file.java -> path has 3+ parts
                        if (dep.getRelPath().getNameCount() < 3) {
                            return false;
                        }
                        // relPath is relative to masterRootPathForConfig, so .brokk is first component
                        var depName = dep.getRelPath().getName(2).toString();
                        return liveNamesSet.contains(depName);
                    })
                    .collect(Collectors.toSet());
        }

        // Wrap with detected language for each dependency root directory
        return selected.stream()
                .map(dep -> new Dependency(dep, detectLanguageForDependency(dep)))
                .collect(Collectors.toSet());
    }

    @Override
    public void saveLiveDependencies(Set<Path> dependencyTopLevelDirs) {
        var names = dependencyTopLevelDirs.stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.joining(","));
        workspaceProps.setProperty(LIVE_DEPENDENCIES_KEY, names);
        saveWorkspaceProperties();
        invalidateAllFiles();
    }

    public Optional<GitRepo.MergeMode> getLastMergeMode() {
        String modeName = workspaceProps.getProperty(LAST_MERGE_MODE_KEY);
        if (modeName == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(GitRepo.MergeMode.valueOf(modeName));
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid merge mode '{}' in workspace properties, ignoring.", modeName);
            return Optional.empty();
        }
    }

    public void setLastMergeMode(GitRepo.MergeMode mode) {
        workspaceProps.setProperty(LAST_MERGE_MODE_KEY, mode.name());
        saveWorkspaceProperties();
    }

    public boolean isMigrationsToSessionsV3Complete() {
        return Boolean.parseBoolean(workspaceProps.getProperty(MIGRATIONS_TO_SESSIONS_V3_COMPLETE_KEY, "false"));
    }

    public void setMigrationsToSessionsV3Complete(boolean complete) {
        workspaceProps.setProperty(MIGRATIONS_TO_SESSIONS_V3_COMPLETE_KEY, String.valueOf(complete));
        saveWorkspaceProperties();
    }

    public static String getGitHubToken() {
        var props = loadGlobalProperties();
        return props.getProperty(GITHUB_TOKEN_KEY, "");
    }

    public static String getGitHubCloneProtocol() {
        var props = loadGlobalProperties();
        return props.getProperty(GITHUB_CLONE_PROTOCOL_KEY, "https");
    }

    public static void setGitHubCloneProtocol(String protocol) {
        var props = loadGlobalProperties();
        props.setProperty(GITHUB_CLONE_PROTOCOL_KEY, protocol);
        saveGlobalProperties(props);
    }

    public static boolean getGitHubShallowCloneEnabled() {
        var props = loadGlobalProperties();
        return Boolean.parseBoolean(props.getProperty(GITHUB_SHALLOW_CLONE_ENABLED_KEY, "false"));
    }

    public static void setGitHubShallowCloneEnabled(boolean enabled) {
        var props = loadGlobalProperties();
        props.setProperty(GITHUB_SHALLOW_CLONE_ENABLED_KEY, String.valueOf(enabled));
        saveGlobalProperties(props);
    }

    public static int getGitHubShallowCloneDepth() {
        var props = loadGlobalProperties();
        return Integer.parseInt(props.getProperty(GITHUB_SHALLOW_CLONE_DEPTH_KEY, "1"));
    }

    public static void setGitHubShallowCloneDepth(int depth) {
        var props = loadGlobalProperties();
        props.setProperty(GITHUB_SHALLOW_CLONE_DEPTH_KEY, String.valueOf(depth));
        saveGlobalProperties(props);
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

    public static boolean getCodeBlockWrapMode() {
        var props = loadGlobalProperties();
        return Boolean.parseBoolean(props.getProperty("wordWrap", "true"));
    }

    public static void setCodeBlockWrapMode(boolean wrap) {
        var props = loadGlobalProperties();
        props.setProperty("wordWrap", String.valueOf(wrap));
        saveGlobalProperties(props);
    }

    public static boolean getExceptionReportingEnabled() {
        var props = loadGlobalProperties();
        return Boolean.parseBoolean(props.getProperty(EXCEPTION_REPORTING_ENABLED_KEY, "false"));
    }

    public static void setExceptionReportingEnabled(boolean enabled) {
        var props = loadGlobalProperties();
        props.setProperty(EXCEPTION_REPORTING_ENABLED_KEY, String.valueOf(enabled));
        saveGlobalProperties(props);
    }

    // UI Scale global preference
    // Values:
    //  - "auto" (default): detect from environment (kscreen-doctor/gsettings on Linux)
    //  - numeric value (e.g., "1.25"), applied to sun.java2d.uiScale at startup, capped elsewhere to sane bounds
    private static final String UI_SCALE_KEY = "uiScale";
    private static final String MOP_ZOOM_KEY = "mopZoom";
    private static final String TERMINAL_FONT_SIZE_KEY = "terminalFontSize";
    private static final String STARTUP_OPEN_MODE_KEY = "startupOpenMode";
    private static final String FORCE_TOOL_EMULATION_KEY = "forceToolEmulation";
    private static final String HISTORY_AUTO_COMPRESS_KEY = "historyAutoCompress";
    private static final String HISTORY_AUTO_COMPRESS_THRESHOLD_PERCENT_KEY = "historyAutoCompressThresholdPercent";

    public static String getUiScalePref() {
        var props = loadGlobalProperties();
        return props.getProperty(UI_SCALE_KEY, "auto");
    }

    public static void setUiScalePrefAuto() {
        var props = loadGlobalProperties();
        props.setProperty(UI_SCALE_KEY, "auto");
        saveGlobalProperties(props);
    }

    public static void setUiScalePrefCustom(double scale) {
        var props = loadGlobalProperties();
        props.setProperty(UI_SCALE_KEY, Double.toString(scale));
        saveGlobalProperties(props);
    }

    public static double getMopZoom() {
        var props = loadGlobalProperties();
        String s = props.getProperty(MOP_ZOOM_KEY, "1.0");
        double z;
        try {
            z = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            z = 1.0;
        }
        if (z < 0.5) z = 0.5;
        if (z > 2.0) z = 2.0;
        return z;
    }

    public static void setMopZoom(double zoom) {
        double clamped = Math.max(0.5, Math.min(2.0, zoom));
        var props = loadGlobalProperties();
        props.setProperty(MOP_ZOOM_KEY, Double.toString(clamped));
        saveGlobalProperties(props);
    }

    public static float getTerminalFontSize() {
        var props = loadGlobalProperties();
        String valueStr = props.getProperty(TERMINAL_FONT_SIZE_KEY);
        if (valueStr != null) {
            try {
                return Float.parseFloat(valueStr);
            } catch (NumberFormatException e) {
                // fall through and return default
            }
        }
        return 11.0f;
    }

    public static void setTerminalFontSize(float size) {
        var props = loadGlobalProperties();
        if (size == 11.0f) {
            props.remove(TERMINAL_FONT_SIZE_KEY);
        } else {
            props.setProperty(TERMINAL_FONT_SIZE_KEY, Float.toString(size));
        }
        saveGlobalProperties(props);
    }

    // ------------------------------------------------------------
    // Git branch poller (global) settings
    // ------------------------------------------------------------

    public static boolean getForceToolEmulation() {
        var props = loadGlobalProperties();
        return Boolean.parseBoolean(props.getProperty(FORCE_TOOL_EMULATION_KEY, "false"));
    }

    public static void setForceToolEmulation(boolean force) {
        var props = loadGlobalProperties();
        if (force) {
            props.setProperty(FORCE_TOOL_EMULATION_KEY, "true");
        } else {
            props.remove(FORCE_TOOL_EMULATION_KEY);
        }
        saveGlobalProperties(props);
    }

    public static boolean getHistoryAutoCompress() {
        var props = loadGlobalProperties();
        return Boolean.parseBoolean(props.getProperty(HISTORY_AUTO_COMPRESS_KEY, "true"));
    }

    public static void setHistoryAutoCompress(boolean autoCompress) {
        var props = loadGlobalProperties();
        props.setProperty(HISTORY_AUTO_COMPRESS_KEY, Boolean.toString(autoCompress));
        saveGlobalProperties(props);
    }

    public static int getHistoryAutoCompressThresholdPercent() {
        var props = loadGlobalProperties();
        String value = props.getProperty(HISTORY_AUTO_COMPRESS_THRESHOLD_PERCENT_KEY);
        int def = 10;
        if (value == null || value.isBlank()) {
            return def;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) parsed = 1;
            if (parsed > 50) parsed = 50;
            return parsed;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static void setHistoryAutoCompressThresholdPercent(int percent) {
        int clamped = Math.max(1, Math.min(50, percent));
        var props = loadGlobalProperties();
        if (clamped == 10) {
            props.remove(HISTORY_AUTO_COMPRESS_THRESHOLD_PERCENT_KEY);
        } else {
            props.setProperty(HISTORY_AUTO_COMPRESS_THRESHOLD_PERCENT_KEY, Integer.toString(clamped));
        }
        saveGlobalProperties(props);
    }

    // JVM memory settings (global)
    private static final String JVM_MEMORY_MODE_KEY = "jvmMemoryMode";
    private static final String JVM_MEMORY_MB_KEY = "jvmMemoryMb";

    public record JvmMemorySettings(boolean automatic, int manualMb) {}

    public static JvmMemorySettings getJvmMemorySettings() {
        var props = loadGlobalProperties();
        String mode = props.getProperty(JVM_MEMORY_MODE_KEY, "auto");
        boolean automatic = !"manual".equalsIgnoreCase(mode);
        int mb = 4096;
        String mbStr = props.getProperty(JVM_MEMORY_MB_KEY);
        if (mbStr != null) {
            try {
                mb = Integer.parseInt(mbStr.trim());
            } catch (NumberFormatException ignore) {
                // keep default
            }
        }
        return new JvmMemorySettings(automatic, mb);
    }

    public static void setJvmMemorySettings(JvmMemorySettings settings) {
        var props = loadGlobalProperties();
        if (settings.automatic()) {
            props.setProperty(JVM_MEMORY_MODE_KEY, "auto");
            props.remove(JVM_MEMORY_MB_KEY);
        } else {
            props.setProperty(JVM_MEMORY_MODE_KEY, "manual");
            props.setProperty(JVM_MEMORY_MB_KEY, Integer.toString(settings.manualMb()));
        }
        saveGlobalProperties(props);
        logger.debug(
                "Saved JVM memory settings: mode={}, mb={}",
                settings.automatic() ? "auto" : "manual",
                settings.automatic() ? "n/a" : settings.manualMb());
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
            new Service.FavoriteModel("GPT-5", new ModelConfig(Service.GPT_5)),
            new Service.FavoriteModel("GPT-5 mini", new ModelConfig("gpt-5-mini")),
            new Service.FavoriteModel("Gemini Pro 2.5", new ModelConfig(Service.GEMINI_2_5_PRO)),
            new Service.FavoriteModel("Flash 2.5", new ModelConfig("gemini-2.5-flash")),
            new Service.FavoriteModel("Sonnet 4", new ModelConfig("claude-4-sonnet", Service.ReasoningLevel.LOW)));

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

    /**
     * Look up a favourite model by its alias (case-insensitive).
     *
     * @param alias the alias supplied by the user (e.g. from the CLI)
     * @return the matching {@link Service.FavoriteModel}
     * @throws IllegalArgumentException if no favourite model with the given alias exists
     */
    public static Service.FavoriteModel getFavoriteModel(String alias) {
        return loadFavoriteModels().stream()
                .filter(fm -> fm.alias().equalsIgnoreCase(alias))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown favorite model alias: " + alias));
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
            AtomicWrites.atomicSaveProperties(
                    PROJECTS_PROPERTIES_PATH, props, "Brokk projects: recently opened and currently open");
        } catch (IOException e) {
            logger.error("Error saving projects properties: {}", e.getMessage());
        }
    }

    public static Map<Path, ProjectPersistentInfo> loadRecentProjects() {
        var allLoadedEntries = new HashMap<Path, ProjectPersistentInfo>();
        var props = loadProjectsProperties();
        for (String key : props.stringPropertyNames()) {
            if (!key.contains(File.separator) || key.endsWith("_activeSession")) {
                continue;
            }
            String propertyValue = props.getProperty(key);
            try {
                Path projectPath = Path.of(key); // Create path once
                ProjectPersistentInfo persistentInfo =
                        objectMapper.readValue(propertyValue, ProjectPersistentInfo.class);
                allLoadedEntries.put(projectPath, persistentInfo);
            } catch (JsonProcessingException e) {
                // Likely old-format timestamp, try to parse as long
                try {
                    Path projectPath = Path.of(key); // Create path once
                    long parsedLongValue = Long.parseLong(propertyValue);
                    ProjectPersistentInfo persistentInfo = ProjectPersistentInfo.fromTimestamp(parsedLongValue);
                    allLoadedEntries.put(projectPath, persistentInfo);
                } catch (NumberFormatException nfe) {
                    logger.warn(
                            "Could not parse value for key '{}' in projects.properties as JSON or long: {}",
                            key,
                            propertyValue);
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
                logger.warn(
                        "Recent project path '{}' no longer exists or is not a directory. Removing from recent projects list.",
                        projectPath);
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
                .sorted(Map.Entry.<Path, ProjectPersistentInfo>comparingByValue(
                                Comparator.comparingLong(ProjectPersistentInfo::lastOpened))
                        .reversed())
                .toList();

        // Collect current project paths to keep
        Set<String> pathsToKeep = sorted.stream()
                .map(entry -> entry.getKey().toAbsolutePath().toString())
                .collect(Collectors.toSet());

        List<String> keysToRemove = props.stringPropertyNames().stream()
                .filter(key -> key.contains(File.separator) && !key.endsWith("_activeSession"))
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

        if (GitRepoFactory.hasGitRepo(projectDir)) {
            try (var tempRepo = new GitRepo(projectDir)) {
                isWorktree = tempRepo.isWorktree();
                if (isWorktree) {
                    pathForRecentProjectsMap = tempRepo.getGitTopLevel();
                }
            } catch (Exception e) {
                logger.warn(
                        "Could not determine if {} is a worktree during updateRecentProject: {}",
                        projectDir,
                        e.getMessage());
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
            String mainProjectPathString =
                    pathForRecentProjectsMap.toAbsolutePath().normalize().toString();
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

        if (GitRepoFactory.hasGitRepo(projectDir)) {
            try (var tempRepo = new GitRepo(projectDir)) {
                isWorktree = tempRepo.isWorktree();
                if (isWorktree) {
                    mainProjectPathKey = tempRepo.getGitTopLevel();
                }
            } catch (Exception e) {
                logger.warn(
                        "Could not determine if {} is a worktree during removeFromOpenProjectsListAndClearActiveSession: {}",
                        projectDir,
                        e.getMessage());
            }
        }

        boolean recentProjectsMapModified = false;

        if (isWorktree) {
            ProjectPersistentInfo mainProjectInfo = recentProjectsMap.get(mainProjectPathKey);
            if (mainProjectInfo != null) {
                List<String> openWorktrees = new ArrayList<>(mainProjectInfo.openWorktrees());
                if (openWorktrees.remove(projectDir.toAbsolutePath().normalize().toString())) {
                    recentProjectsMap.put(
                            mainProjectPathKey, new ProjectPersistentInfo(mainProjectInfo.lastOpened(), openWorktrees));
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
                                logger.warn(
                                        "Invalid worktree path '{}' found for main project '{}', not adding to open list.",
                                        worktreePathStr,
                                        mainProjectPathKey);
                            }
                        } catch (Exception e) {
                            logger.warn(
                                    "Error processing worktree path '{}' for main project '{}': {}",
                                    worktreePathStr,
                                    mainProjectPathKey,
                                    e.getMessage());
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

    /**
     * Attempts to persist the fact that an {@link OutOfMemoryError} occurred during this session. The JVM would be in a
     * fatal state, and thus writing this to project properties would not work and creating a file is usually
     * successful.
     */
    public static void setOomFlag() {
        try {
            Files.createFile(OUT_OF_MEMORY_EXCEPTION_FLAG);
        } catch (IOException e) {
            logger.error("Unable to persist OutOfMemoryError flag.");
        }
    }

    public static boolean initializeOomFlag() {
        try {
            if (Files.exists(OUT_OF_MEMORY_EXCEPTION_FLAG)) {
                Files.delete(OUT_OF_MEMORY_EXCEPTION_FLAG);
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            logger.error("Unable to determine if OutOfMemoryError flag was present or not.");
            return false;
        }
    }

    public static void clearActiveSessions() {
        var props = loadProjectsProperties();
        props.setProperty("openProjectsList", "");
        saveProjectsProperties(props);
    }

    public static Optional<String> getActiveSessionTitle(Path worktreeRoot) {
        return SessionManager.getActiveSessionTitle(worktreeRoot);
    }

    @Override
    public void close() {
        // Close disk cache if open
        try {
            if (diskCache != null) {
                diskCache.close();
                diskCache = null;
                logger.debug("Closed disk cache for project {}", root.getFileName());
            }
        } catch (Exception e) {
            logger.warn("Error closing disk cache for {}: {}", root.getFileName(), e.getMessage());
        }

        // Close session manager and other resources
        sessionManager.close();
        super.close();
    }

    public Path getWorktreeStoragePath() {
        return Path.of(
                System.getProperty("user.home"),
                BROKK_DIR,
                "worktrees",
                getMasterRootPathForConfig().getFileName().toString());
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

                if (Brokk.isProjectOpen(wtPath)) {
                    continue;
                }

                var wsPropsPath = wtPath.resolve(BROKK_DIR).resolve(WORKSPACE_PROPERTIES_FILE);
                if (!Files.exists(wsPropsPath)) {
                    continue;
                }

                var props = new Properties();
                try (var reader = Files.newBufferedReader(wsPropsPath)) {
                    props.load(reader);
                    String sessionIdStr = props.getProperty("lastActiveSession");
                    if (sessionIdStr != null && !sessionIdStr.isBlank()) {
                        UUID sessionId = UUID.fromString(sessionIdStr.trim());
                        if (SessionRegistry.claim(wtPath, sessionId)) {
                            logger.info(
                                    "Reserved session {} for non-open worktree {}", sessionId, wtPath.getFileName());
                        } else {
                            logger.warn(
                                    "Failed to reserve session {} for worktree {} (already claimed elsewhere or error).",
                                    sessionId,
                                    wtPath.getFileName());
                        }
                    }
                } catch (IOException | IllegalArgumentException e) {
                    logger.warn(
                            "Error reading last active session for worktree {} or claiming it: {}",
                            wtPath.getFileName(),
                            e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error(
                    "Error listing worktrees or reserving their sessions for main project {}: {}",
                    this.root.getFileName(),
                    e.getMessage(),
                    e);
        }
    }

    // ------------------------------------------------------------------
    // Blitz-History (parallel + post-processing instructions)
    // ------------------------------------------------------------------
    private static final String BLITZ_HISTORY_KEY = "blitzHistory";

    private void saveBlitzHistory(List<List<String>> historyItems, int maxItems) {
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

    @Override
    public SessionManager getSessionManager() {
        return sessionManager;
    }

    @Override
    public void sessionsListChanged() {
        var mainChrome = Brokk.findOpenProjectWindow(getRoot());
        var worktreeChromes = Brokk.getWorktreeChromes(this);

        var allChromes = new ArrayList<Chrome>();
        if (mainChrome != null) {
            allChromes.add(mainChrome);
        }
        allChromes.addAll(worktreeChromes);

        for (var chrome : allChromes) {
            SwingUtilities.invokeLater(() -> chrome.getHistoryOutputPanel().updateSessionComboBox());
        }
    }
}
