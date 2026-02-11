package ai.brokk.project;

import ai.brokk.AbstractService.ModelConfig;
import ai.brokk.Brokk;
import ai.brokk.IConsoleIO;
import ai.brokk.IssueProvider;
import ai.brokk.Service;
import ai.brokk.SessionManager;
import ai.brokk.SessionRegistry;
import ai.brokk.SettingsChangeListener;
import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.AtomicWrites;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.init.onboarding.GitIgnoreUtils;
import ai.brokk.init.onboarding.StyleGuideMigrator;
import ai.brokk.mcp.McpConfig;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.util.BrokkConfigPaths;
import ai.brokk.util.DependencyUpdateScheduler;
import ai.brokk.util.Environment;
import ai.brokk.util.GlobalUiSettings;
import ai.brokk.util.IStringDiskCache;
import ai.brokk.util.PathNormalizer;
import ai.brokk.util.StringDiskCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import com.jakewharton.disklrucache.DiskLruCache;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.util.SystemReader;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

public final class MainProject extends AbstractProject {
    private static final Logger logger =
            LogManager.getLogger(MainProject.class); // Separate logger from AbstractProject

    private final Path propertiesFile;
    private final Properties projectProps;
    private final Path styleGuidePath;
    private final Path legacyStyleGuidePath;
    private final SessionManager sessionManager;
    private final SessionRegistry sessionRegistry = new SessionRegistry();
    private volatile CompletableFuture<BuildAgent.BuildDetails> detailsFuture = new CompletableFuture<>();

    @Nullable
    private volatile Set<Language> autoDetectedLanguagesCache = null;

    @Nullable
    private volatile StringDiskCache diskCache = null;

    @Nullable
    private FileLock cacheFileLock = null;

    @Nullable
    private FileChannel cacheLockChannel = null;

    private final DependencyUpdateScheduler dependencyUpdateScheduler;

    private static final long DEFAULT_DISK_CACHE_SIZE = 10L * 1024L * 1024L; // 10 MB

    private static final String BUILD_DETAILS_KEY = "buildDetailsJson";
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
    private static final String MIGRATION_DECLINED_KEY = "styleMdMigrationDeclined";

    // Old keys for migration
    private static final String OLD_ISSUE_PROVIDER_ENUM_KEY = "issueProvider"; // Stores the enum name (GITHUB, JIRA)
    private static final String JIRA_PROJECT_BASE_URL_KEY = "jiraProjectBaseUrl";
    private static final String JIRA_PROJECT_API_TOKEN_KEY = "jiraProjectApiToken";
    private static final String JIRA_PROJECT_KEY_KEY = "jiraProjectKey";

    private static final String RUN_COMMAND_TIMEOUT_SECONDS_KEY = "runCommandTimeoutSeconds";
    private static final long DEFAULT_RUN_COMMAND_TIMEOUT_SECONDS = Environment.DEFAULT_TIMEOUT.toSeconds();
    private static final String CODE_AGENT_TEST_SCOPE_KEY = "codeAgentTestScope";
    private static final String COMMIT_MESSAGE_FORMAT_KEY = "commitMessageFormat";
    private static final String EXCEPTION_REPORTING_ENABLED_KEY = "exceptionReportingEnabled";
    private static final String AUTO_UPDATE_LOCAL_DEPENDENCIES_KEY = "autoUpdateLocalDependencies";
    private static final String AUTO_UPDATE_GIT_DEPENDENCIES_KEY = "autoUpdateGitDependencies";
    private static final String OPENAI_CODEX_OAUTH_CONNECTED_KEY = "openAiCodexOauthConnected";

    private static final List<SettingsChangeListener> settingsChangeListeners = new CopyOnWriteArrayList<>();

    public static final String DEFAULT_COMMIT_MESSAGE_FORMAT =
            """
                                                               The commit message should be structured as follows: <type>: <description>
                                                               Use these for <type>: debug, fix, feat, chore, config, docs, style, refactor, perf, test, enh
                                                               """;

    @Nullable
    private static volatile Boolean isDataShareAllowedCache = null;

    @Nullable
    private static volatile String headlessBrokkApiKeyOverride = null;

    @Nullable
    private static volatile LlmProxySetting headlessProxySettingOverride = null;

    @Nullable
    private static volatile List<Service.FavoriteModel> headlessFavoriteModelsOverride = null;

    @Nullable
    private static volatile Path cachedGlobalConfigDir = null;

    @Nullable
    @VisibleForTesting
    static Properties globalPropertiesCache = null; // protected by synchronized

    private static Path getCachedGlobalConfigDir() {
        Path result = cachedGlobalConfigDir;
        if (result == null) {
            synchronized (MainProject.class) {
                result = cachedGlobalConfigDir;
                if (result == null) {
                    result = BrokkConfigPaths.getGlobalConfigDir();
                    cachedGlobalConfigDir = result;
                }
            }
        }
        return result;
    }

    @VisibleForTesting
    static void resetGlobalConfigCachesForTests() {
        synchronized (MainProject.class) {
            cachedGlobalConfigDir = null;
            globalPropertiesCache = null;
        }
    }

    private static Path getGlobalPropertiesPath() {
        return getCachedGlobalConfigDir().resolve("brokk.properties");
    }

    private static Path getProjectsPropertiesPath() {
        return getCachedGlobalConfigDir().resolve("projects.properties");
    }

    private static Path getOomFlagPath() {
        return getCachedGlobalConfigDir().resolve("oom.flag");
    }

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
    public static final String BROKK_FRONTEND_URL = "https://brokk.ai";
    public static final String LOCALHOST_FRONTEND_URL = "http://localhost:5173";
    public static final String STAGING_FRONTEND_URL = "https://brokk-frontend-staging.up.railway.app";

    private static final String DATA_RETENTION_POLICY_KEY = "dataRetentionPolicy";

    public record ProjectPersistentInfo(long lastOpened, List<String> openWorktrees) {
        public ProjectPersistentInfo {}

        public static ProjectPersistentInfo fromTimestamp(long lastOpened) {
            return new ProjectPersistentInfo(lastOpened, List.of());
        }
    }

    public MainProject(Path root) {
        super(root); // Initializes this.root and this.repo

        this.propertiesFile = this.masterRootPathForConfig.resolve(BROKK_DIR).resolve(PROJECT_PROPERTIES_FILE);
        this.styleGuidePath = this.masterRootPathForConfig.resolve(STYLE_GUIDE_FILE);
        this.legacyStyleGuidePath =
                this.masterRootPathForConfig.resolve(BROKK_DIR).resolve(LEGACY_STYLE_GUIDE_FILE);
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

        // Load build details
        var bdOpt = loadBuildDetails();
        if (bdOpt.isPresent()) {
            this.detailsFuture.complete(bdOpt.get());
        } else {
            this.detailsFuture.complete(BuildAgent.BuildDetails.EMPTY);
        }

        // Initialize cache and trigger migration/defaulting if necessary
        this.issuesProviderCache = getIssuesProvider();

        // Initialize dependency update scheduler
        this.dependencyUpdateScheduler = new DependencyUpdateScheduler(this);
    }

    @TestOnly
    public static MainProject forTests(Path root) {
        return forTests(root, BuildAgent.BuildDetails.EMPTY);
    }

    @TestOnly
    public static MainProject forTests(Path root, BuildAgent.BuildDetails buildDetails) {
        var mp = new MainProject(root);
        mp.saveBuildDetails(buildDetails);
        return mp;
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
    public synchronized IStringDiskCache getDiskCache() {
        if (diskCache != null) {
            return diskCache;
        }

        // 1. Try primary cache location
        Path primaryCacheDir = getMasterRootPathForConfig().resolve(BROKK_DIR).resolve(CACHE_DIR);
        if (tryOpenCache(primaryCacheDir)) {
            return Objects.requireNonNull(diskCache);
        }

        // 2. Fallback to unique temporary directory
        try {
            Path tempCacheDir = Files.createTempDirectory("brokk-cache-");
            logger.info("Primary cache locked or inaccessible; falling back to temporary cache at {}", tempCacheDir);
            if (tryOpenCache(tempCacheDir)) {
                return Objects.requireNonNull(diskCache);
            }
        } catch (IOException e) {
            logger.error("Failed to create temporary cache directory: {}", e.getMessage());
        }

        // 3. Absolute fallback to Noop
        return new IStringDiskCache.NoopCache();
    }

    private boolean tryOpenCache(Path cacheDir) {
        Path lockFile = cacheDir.resolve("cache.lock");
        FileChannel channel = null;
        FileLock lock = null;
        try {
            Files.createDirectories(cacheDir);

            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();

            if (lock == null) {
                logger.debug("Unable to acquire lock on {}", lockFile);
                channel.close();
                return false;
            }

            DiskLruCache dlc = DiskLruCache.open(cacheDir.toFile(), 1, 1, DEFAULT_DISK_CACHE_SIZE);
            this.diskCache = new StringDiskCache(dlc);
            this.cacheLockChannel = channel;
            this.cacheFileLock = lock;

            logger.debug("Initialized disk cache at {} (max {} bytes)", cacheDir, DEFAULT_DISK_CACHE_SIZE);
            return true;
        } catch (IOException e) {
            logger.warn("Failed to initialize cache at {}: {}", cacheDir, e.getMessage());
            try {
                if (lock != null) lock.release();
                if (channel != null) channel.close();
            } catch (IOException cleanupEx) {
                // Ignore cleanup errors
            }
            return false;
        }
    }

    private void closeCacheLock() {
        try {
            if (cacheFileLock != null) {
                cacheFileLock.release();
                cacheFileLock = null;
            }
            if (cacheLockChannel != null) {
                cacheLockChannel.close();
                cacheLockChannel = null;
            }
        } catch (IOException e) {
            logger.warn("Error releasing cache lock: {}", e.getMessage());
        }
    }

    public static synchronized Properties loadGlobalProperties() {
        if (globalPropertiesCache != null) {
            return (Properties) globalPropertiesCache.clone();
        }

        var props = new Properties();
        boolean needsSave = false;
        Path globalPath = getGlobalPropertiesPath();
        if (Files.exists(globalPath)) {
            try (var reader = Files.newBufferedReader(globalPath)) {
                props.load(reader);
            } catch (IOException e) {
                logger.warn("Unable to read global properties file: {}", e.getMessage());
                globalPropertiesCache = (Properties) props.clone();
                return props;
            }
        }

        // Perform model settings migration if needed
        int storedVersion = 0;
        String versionStr = props.getProperty(ModelProperties.MODEL_SETTINGS_VERSION_KEY);
        if (versionStr != null) {
            try {
                storedVersion = Integer.parseInt(versionStr.trim());
            } catch (NumberFormatException e) {
                logger.warn("Invalid model settings version in properties: {}", versionStr);
            }
        }

        if (storedVersion < ModelProperties.MODEL_SETTINGS_VERSION) {
            logger.info(
                    "Migrating model settings from version {} to {}. Resetting model defaults.",
                    storedVersion,
                    ModelProperties.MODEL_SETTINGS_VERSION);

            // Remove keys to force fallback to current defaults in ModelProperties
            props.remove(ModelProperties.FAVORITE_MODELS_KEY);
            props.remove(ModelType.CODE.propertyKey);
            props.remove(ModelType.ARCHITECT.propertyKey);

            props.setProperty(
                    ModelProperties.MODEL_SETTINGS_VERSION_KEY, String.valueOf(ModelProperties.MODEL_SETTINGS_VERSION));
            needsSave = true;
        }

        if (needsSave) {
            saveGlobalProperties(props);
        }

        globalPropertiesCache = (Properties) props.clone();
        return props;
    }

    private static synchronized void saveGlobalProperties(Properties props) {
        try {
            // Load directly from disk to avoid re-triggering migration in loadGlobalProperties
            var existingProps = new Properties();
            Path globalPath = getGlobalPropertiesPath();
            if (Files.exists(globalPath)) {
                try (var reader = Files.newBufferedReader(globalPath)) {
                    existingProps.load(reader);
                } catch (IOException e) {
                    // Proceed with save even if we can't read existing props
                }
            }
            if (existingProps.equals(props)) {
                return;
            }

            // Log brokkApiKey changes to help diagnose disappearing key issues
            var existingKey = existingProps.getProperty("brokkApiKey", "");
            var newKey = props.getProperty("brokkApiKey", "");
            if (!existingKey.equals(newKey)) {
                if (newKey.isEmpty() && !existingKey.isEmpty()) {
                    logger.warn(
                            "brokkApiKey is being REMOVED from global properties. Stack trace for diagnosis:",
                            new Exception("brokkApiKey removal trace"));
                } else if (!newKey.isEmpty() && existingKey.isEmpty()) {
                    logger.info("brokkApiKey is being SET in global properties");
                } else {
                    logger.info("brokkApiKey is being CHANGED in global properties");
                }
            }

            // Log githubToken changes to help diagnose disappearing token issues
            var existingGHToken = existingProps.getProperty(GITHUB_TOKEN_KEY, "");
            var newGHToken = props.getProperty(GITHUB_TOKEN_KEY, "");
            if (!existingGHToken.equals(newGHToken)) {
                if (newGHToken.isEmpty() && !existingGHToken.isEmpty()) {
                    logger.warn(
                            "githubToken is being REMOVED from global properties. Stack trace:",
                            new Exception("githubToken removal trace"));
                } else if (!newGHToken.isEmpty() && existingGHToken.isEmpty()) {
                    logger.info("githubToken is being SET in global properties");
                } else {
                    logger.info("githubToken is being CHANGED in global properties");
                }
            }

            Files.createDirectories(globalPath.getParent());
            AtomicWrites.save(globalPath, props, "Brokk global configuration");
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

    @Override
    public Optional<BuildAgent.BuildDetails> loadBuildDetails() {
        String json = projectProps.getProperty(BUILD_DETAILS_KEY);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }

        try {
            var details = objectMapper.readValue(json, BuildAgent.BuildDetails.class);

            // Canonicalize exclusion patterns that look like paths
            var canonicalExclusions = new LinkedHashSet<String>();
            for (String pattern : details.exclusionPatterns()) {
                // Only canonicalize patterns that look like directory paths (contain / or \)
                if (pattern.contains("/") || pattern.contains("\\")) {
                    String c = PathNormalizer.canonicalizeForProject(pattern, getMasterRootPathForConfig());
                    if (!c.isBlank()) {
                        canonicalExclusions.add(c);
                    }
                } else {
                    canonicalExclusions.add(pattern);
                }
            }

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
                    // Migration: Move JAVA_HOME from project.properties to workspace.properties
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
                    details.testAllCommand(),
                    details.testSomeCommand(),
                    canonicalExclusions,
                    canonicalEnv));
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
            // Only canonicalize patterns that look like directory paths (contain / or \)
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
                details.testAllCommand(),
                details.testSomeCommand(),
                canonicalExclusions,
                canonicalEnv);

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

    @Override
    public ModelConfig getModelConfig(ModelType modelType) {
        var props = loadGlobalProperties();
        return ModelProperties.getModelConfig(props, modelType);
    }

    @Override
    public void setModelConfig(ModelType modelType, ModelConfig config) {
        var props = loadGlobalProperties();
        ModelProperties.setModelConfig(props, modelType, config);
        saveGlobalProperties(props);
    }

    public void removeModelConfig(ModelType modelType) {
        var props = loadGlobalProperties();
        if (props.containsKey(modelType.propertyKey)) {
            props.remove(modelType.propertyKey);
            saveGlobalProperties(props);
        }
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
    public boolean getAutoUpdateLocalDependencies() {
        String value = projectProps.getProperty(AUTO_UPDATE_LOCAL_DEPENDENCIES_KEY);
        return value != null && Boolean.parseBoolean(value);
    }

    @Override
    public void setAutoUpdateLocalDependencies(boolean enabled) {
        if (enabled) {
            projectProps.setProperty(AUTO_UPDATE_LOCAL_DEPENDENCIES_KEY, "true");
        } else {
            projectProps.remove(AUTO_UPDATE_LOCAL_DEPENDENCIES_KEY);
        }
        saveProjectProperties();
        notifyAutoUpdateLocalDependenciesChanged();
    }

    @Override
    public boolean getAutoUpdateGitDependencies() {
        String value = projectProps.getProperty(AUTO_UPDATE_GIT_DEPENDENCIES_KEY);
        return value != null && Boolean.parseBoolean(value);
    }

    @Override
    public void setAutoUpdateGitDependencies(boolean enabled) {
        if (enabled) {
            projectProps.setProperty(AUTO_UPDATE_GIT_DEPENDENCIES_KEY, "true");
        } else {
            projectProps.remove(AUTO_UPDATE_GIT_DEPENDENCIES_KEY);
        }
        saveProjectProperties();
        notifyAutoUpdateGitDependenciesChanged();
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
     * Returns the explicitly configured analyzer languages from project properties, if any.
     *
     * @return a non-empty set of Languages, or Set.of(Languages.NONE) if the configuration parses
     *         to an empty set, or null if no explicit configuration is present.
     */
    @Nullable
    private Set<Language> getConfiguredAnalyzerLanguagesOrNull() {
        String langsProp = projectProps.getProperty(CODE_INTELLIGENCE_LANGUAGES_KEY);
        if (langsProp == null || langsProp.isBlank()) {
            return null;
        }

        Set<Language> parsed = Arrays.stream(langsProp.split(","))
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

        if (parsed.isEmpty()) {
            return Set.of(Languages.NONE);
        }
        return parsed;
    }

    @Override
    public Set<Language> getAnalyzerLanguages() {
        Set<Language> configured = getConfiguredAnalyzerLanguagesOrNull();
        if (configured != null) {
            return configured;
        }

        Set<Language> cached = autoDetectedLanguagesCache;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            cached = autoDetectedLanguagesCache;
            if (cached != null) {
                return cached;
            }

            // Auto-detect: consider both tracked repository files and live dependencies.
            Set<Language> detectedLanguages = new HashSet<>();

            // 1) Repo-tracked files
            for (ProjectFile pf : repo.getTrackedFiles()) {
                Language lang = Languages.fromExtension(pf.extension());
                if (lang != Languages.NONE) {
                    detectedLanguages.add(lang);
                }
            }

            // 2) Live dependencies
            for (IProject.Dependency dep : getLiveDependencies()) {
                try {
                    detectedLanguages.addAll(dep.languages());
                } catch (Exception e) {
                    logger.warn("Error detecting languages for dependency {} in {}", dep, root, e);
                }
            }

            if (detectedLanguages.isEmpty()) {
                logger.debug(
                        "No files with recognized (non-NONE) languages found for {} (repo files and live dependencies checked). Defaulting to Language.NONE.",
                        root);
                autoDetectedLanguagesCache = Set.of(Languages.NONE);
            } else {
                logger.debug(
                        "Auto-detected languages for {} (including live dependencies): {}",
                        root,
                        detectedLanguages.stream().map(Language::name).collect(Collectors.joining(", ")));
                autoDetectedLanguagesCache = Set.copyOf(detectedLanguages);
            }
            return autoDetectedLanguagesCache;
        }
    }

    @Override
    public void setAnalyzerLanguages(Set<Language> languages) {
        if (languages.isEmpty() || ((languages.size() == 1) && languages.contains(Languages.NONE))) {
            projectProps.remove(CODE_INTELLIGENCE_LANGUAGES_KEY);
        } else {
            String langsString = languages.stream().map(Language::name).collect(Collectors.joining(","));
            projectProps.setProperty(CODE_INTELLIGENCE_LANGUAGES_KEY, langsString);
        }
        autoDetectedLanguagesCache = null;
        saveProjectProperties();
        invalidateAutoDetectedLanguages();
    }

    @Override
    public void invalidateAutoDetectedLanguages() {
        autoDetectedLanguagesCache = null;
        logger.debug("Invalidated auto-detected languages cache for {}", root.getFileName());
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

        return issuesProviderCache;
    }

    @Override
    public void setIssuesProvider(IssueProvider provider) {
        IssueProvider oldProvider = this.issuesProviderCache;
        if (oldProvider == null) {
            String currentJsonInProps = projectProps.getProperty(ISSUES_PROVIDER_JSON_KEY);
            if (currentJsonInProps != null && !currentJsonInProps.isBlank()) {
                try {
                    oldProvider = objectMapper.readValue(currentJsonInProps, IssueProvider.class);
                } catch (JsonProcessingException e) {
                    logger.debug(
                            "Could not parse existing IssueProvider JSON from properties while determining old provider: {}",
                            e.getMessage());
                }
            }
        }

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

            if (!Objects.equals(oldProvider, provider)) {
                logger.debug("Issue provider changed from {} to {}. Notifying listeners.", oldProvider, provider);
                notifyIssueProviderChanged();
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize IssueProvider to JSON: {}. Settings not saved.", provider, e);
            throw new RuntimeException("Failed to serialize IssueProvider", e);
        }
    }

    private void saveProjectProperties() {
        saveProperties(propertiesFile, projectProps, "Brokk project configuration");
    }

    @Override
    public boolean isGitHubRepo() {
        if (!(getRepo() instanceof GitRepo gitRepo)) return false;
        String remoteUrl = gitRepo.remote().getUrl("origin");
        if (remoteUrl == null || remoteUrl.isBlank()) return false;
        return remoteUrl.contains("github.com");
    }

    @Override
    public boolean isGitIgnoreSet() {
        try {
            var gitignoreFile = new ProjectFile(getMasterRootPathForConfig(), ".gitignore");
            if (GitIgnoreUtils.isBrokkIgnored(gitignoreFile)) {
                logger.debug(".gitignore at {} is set to ignore Brokk files.", gitignoreFile.absPath());
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
                    if (GitIgnoreUtils.isBrokkIgnored(excludesFilePath)) {
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

    @Override
    public String getStyleGuide() {
        try {
            if (Files.exists(styleGuidePath)) {
                logger.debug("Reading style guide from {}", styleGuidePath);
                return Files.readString(styleGuidePath);
            }
        } catch (IOException e) {
            logger.error("Error reading style guide from {}: {}", styleGuidePath, e.getMessage());
        }

        try {
            if (Files.exists(legacyStyleGuidePath)) {
                logger.info(
                        "Reading style guide from legacy location {} (consider migrating to AGENTS.md)",
                        legacyStyleGuidePath);
                return Files.readString(legacyStyleGuidePath);
            }
        } catch (IOException e) {
            logger.error("Error reading legacy style guide from {}: {}", legacyStyleGuidePath, e.getMessage());
        }

        return "";
    }

    @Override
    public void saveStyleGuide(String styleGuide) {
        Path targetPath;

        // Check if legacy style.md exists and has non-empty content
        boolean hasLegacyContent = false;
        if (Files.exists(legacyStyleGuidePath)) {
            try {
                // If an exception is thrown we assume the file is empty
                String legacyContent = Files.readString(legacyStyleGuidePath);
                hasLegacyContent = !legacyContent.isBlank();
            } catch (IOException e) {
                logger.warn("Error reading legacy style guide: {}", e.getMessage());
            }
        }

        // Decision logic:
        // 1. If AGENTS.md already exists → use it (already migrated)
        // 2. Else if .brokk/style.md has content → use it (preserve legacy)
        // 3. Else → use AGENTS.md (default for fresh projects)
        if (Files.exists(styleGuidePath)) {
            targetPath = styleGuidePath;
        } else if (hasLegacyContent) {
            targetPath = legacyStyleGuidePath;
            logger.debug("Legacy style guide has content; saving to .brokk/style.md");
        } else {
            targetPath = styleGuidePath;
            logger.debug("No legacy content; saving to AGENTS.md");
        }

        try {
            Files.createDirectories(targetPath.getParent());
            AtomicWrites.save(targetPath, styleGuide);
            logger.debug("Saved style guide to {}", targetPath);
        } catch (IOException e) {
            logger.error("Error saving style guide to {}: {}", targetPath, e.getMessage());
        }
    }

    public static LlmProxySetting getProxySetting() {
        // Check headless executor override first (process-scoped)
        LlmProxySetting override = headlessProxySettingOverride;
        if (override != null) {
            return override;
        }
        // Fall back to global properties
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

    /**
     * Set a process-scoped override for the LLM proxy setting.
     * Used by headless executors to use a per-executor proxy configuration instead of the global config.
     * If set to a non-null value, getProxySetting() will return this override instead of
     * reading from global properties.
     *
     * @param setting the proxy setting override, or null to clear the override
     */
    public static void setHeadlessProxySettingOverride(@Nullable LlmProxySetting setting) {
        headlessProxySettingOverride = setting;
        logger.debug("Set headless proxy setting override: {}", setting != null ? setting.name() : "(cleared)");
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
            case LOCALHOST -> "http://localhost:8000";
            case STAGING -> STAGING_SERVICE_URL;
        };
    }

    public static String getFrontendUrl() {
        return switch (getProxySetting()) {
            case BROKK -> BROKK_FRONTEND_URL;
            case LOCALHOST -> LOCALHOST_FRONTEND_URL;
            case STAGING -> STAGING_FRONTEND_URL;
        };
    }

    public static MainProject.StartupOpenMode getStartupOpenMode() {
        var props = loadGlobalProperties();
        String val = props.getProperty(STARTUP_OPEN_MODE_KEY, StartupOpenMode.LAST.name());
        try {
            return StartupOpenMode.valueOf(val);
        } catch (IllegalArgumentException e) {
            return StartupOpenMode.ALL;
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

    private static void notifyAutoUpdateLocalDependenciesChanged() {
        for (SettingsChangeListener listener : settingsChangeListeners) {
            try {
                listener.autoUpdateLocalDependenciesChanged();
            } catch (Exception e) {
                logger.error("Error notifying listener of auto-update local dependencies change", e);
            }
        }
    }

    private static void notifyAutoUpdateGitDependenciesChanged() {
        for (SettingsChangeListener listener : settingsChangeListeners) {
            try {
                listener.autoUpdateGitDependenciesChanged();
            } catch (Exception e) {
                logger.error("Error notifying listener of auto-update git dependencies change", e);
            }
        }
    }

    private static void notifyOpenAiOauthConnectionChanged() {
        for (SettingsChangeListener listener : settingsChangeListeners) {
            try {
                listener.openAiOauthConnectionChanged();
            } catch (Exception e) {
                logger.error("Error notifying listener of OpenAI OAuth connection change", e);
            }
        }
    }

    public static boolean isOpenAiCodexOauthConnected() {
        var props = loadGlobalProperties();
        return Boolean.parseBoolean(props.getProperty(OPENAI_CODEX_OAUTH_CONNECTED_KEY, "false"));
    }

    public static void setOpenAiCodexOauthConnected(boolean connected) {
        var props = loadGlobalProperties();
        boolean currentValue = Boolean.parseBoolean(props.getProperty(OPENAI_CODEX_OAUTH_CONNECTED_KEY, "false"));
        if (currentValue != connected) {
            if (connected) {
                props.setProperty(OPENAI_CODEX_OAUTH_CONNECTED_KEY, "true");
            } else {
                props.remove(OPENAI_CODEX_OAUTH_CONNECTED_KEY);
            }
            saveGlobalProperties(props);
            notifyOpenAiOauthConnectionChanged();
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
        var liveDepsNames = workspaceProps.getProperty(LIVE_DEPENDENCIES_KEY);

        if (liveDepsNames == null || liveDepsNames.isBlank()) {
            return Set.of();
        }

        return resolveDependencies(liveDepsNames);
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

    public boolean getMigrationDeclined() {
        return Boolean.parseBoolean(projectProps.getProperty(MIGRATION_DECLINED_KEY, "false"));
    }

    public void setMigrationDeclined(boolean declined) {
        projectProps.setProperty(MIGRATION_DECLINED_KEY, String.valueOf(declined));
        saveProjectProperties();
    }

    /**
     * Performs the actual style.md to AGENTS.md migration.
     * Delegates to StyleGuideMigrator for the core migration logic.
     *
     * @param io the IConsoleIO instance for showing notifications
     * @return true if migration succeeded, false otherwise
     */
    @Blocking
    public boolean performStyleMdToAgentsMdMigration(IConsoleIO io) {
        try {
            var gitTopLevel = getMasterRootPathForConfig();
            var legacyStyle = new ProjectFile(gitTopLevel, BROKK_DIR + "/style.md");
            var agentsFile = new ProjectFile(gitTopLevel, STYLE_GUIDE_FILE);
            var gitRepo = getRepo() instanceof GitRepo g ? g : null;

            logger.info(
                    "Starting style.md to AGENTS.md migration for {} via StyleGuideMigrator",
                    getRoot().getFileName());

            var result = StyleGuideMigrator.migrate(legacyStyle, agentsFile, gitRepo);

            if (result.performed()) {
                logger.info("Migration successful: {}", result.message());
                setMigrationDeclined(false);
                io.showNotification(IConsoleIO.NotificationRole.INFO, result.message());
                return true;
            } else {
                logger.info("Migration not performed: {}", result.message());
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Migration skipped: " + result.message());
                return false;
            }
        } catch (Exception e) {
            logger.error(
                    "Error performing style.md to AGENTS.md migration for {}: {}",
                    getRoot().getFileName(),
                    e.getMessage(),
                    e);
            io.toolError("Migration failed: " + e.getMessage(), "Migration Error");
            return false;
        }
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
        return props.getProperty("theme", GuiTheme.THEME_DARK_PLUS);
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

    public static String getGlobalActionMode() {
        var props = loadGlobalProperties();
        return props.getProperty("actionMode", "");
    }

    public static void setGlobalActionMode(String mode) {
        var props = loadGlobalProperties();
        if (mode.isEmpty()) {
            props.remove("actionMode");
        } else {
            props.setProperty("actionMode", mode);
        }
        saveGlobalProperties(props);
    }

    public static boolean getExceptionReportingEnabled() {
        var props = loadGlobalProperties();
        return Boolean.parseBoolean(props.getProperty(EXCEPTION_REPORTING_ENABLED_KEY, "true"));
    }

    public static void setExceptionReportingEnabled(boolean enabled) {
        var props = loadGlobalProperties();
        props.setProperty(EXCEPTION_REPORTING_ENABLED_KEY, String.valueOf(enabled));
        saveGlobalProperties(props);
    }

    // Preference key for selecting the watch service implementation.
    // Allowed values persisted: "legacy", "native". If unset/blank/unrecognized, treated as "default".
    private static final String WATCH_SERVICE_IMPL_KEY = "watchServiceImpl";

    /**
     * Returns the persisted watch service implementation preference.
     *
     * Normalized return values:
     *  - "legacy"  => legacy implementation
     *  - "native"  => native implementation
     *  - "default" => no explicit preference / use platform-default behavior
     */
    public static String getWatchServiceImplPreference() {
        var props = loadGlobalProperties();
        String raw = props.getProperty(WATCH_SERVICE_IMPL_KEY);
        if (raw == null || raw.isBlank()) {
            return "default";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("legacy".equals(normalized) || "native".equals(normalized)) {
            return normalized;
        }
        return "default";
    }

    /**
     * Persist the watch service implementation preference.
     *
     * Accepts case-insensitive values: "default", "legacy", "native".
     * If "default" (or blank/unrecognized) the property will be removed to fall back to platform default.
     */
    public static void setWatchServiceImplPreference(String v) {
        var props = loadGlobalProperties();
        String normalized = v.trim().toLowerCase(Locale.ROOT);
        if ("default".equals(normalized) || normalized.isBlank()) {
            props.remove(WATCH_SERVICE_IMPL_KEY);
        } else if ("legacy".equals(normalized) || "native".equals(normalized)) {
            props.setProperty(WATCH_SERVICE_IMPL_KEY, normalized);
        } else {
            // Unrecognized value: remove key to ensure default behavior.
            props.remove(WATCH_SERVICE_IMPL_KEY);
            normalized = "default";
        }
        saveGlobalProperties(props);
        logger.debug("Set watch service implementation preference to {}", normalized);
    }

    // UI Scale global preference
    // Values:
    //  - "auto" (default): detect from environment (kscreen-doctor/gsettings on Linux)
    //  - numeric value (e.g., "1.25"), applied to sun.java2d.uiScale at startup, capped elsewhere to sane bounds
    private static final String UI_SCALE_KEY = "uiScale";
    private static final String MOP_ZOOM_KEY = "mopZoom";
    private static final String TERMINAL_FONT_SIZE_KEY = "terminalFontSize";
    private static final String STARTUP_OPEN_MODE_KEY = "startupOpenMode";
    private static final String OTHER_MODELS_VENDOR_KEY = "otherModelsVendor";

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

    public static String getOtherModelsVendorPreference() {
        var props = loadGlobalProperties();
        return props.getProperty(OTHER_MODELS_VENDOR_KEY, "");
    }

    public static void setOtherModelsVendorPreference(String vendor) {
        var props = loadGlobalProperties();
        if (vendor.isBlank()) {
            props.remove(OTHER_MODELS_VENDOR_KEY);
        } else {
            props.setProperty(OTHER_MODELS_VENDOR_KEY, vendor.trim());
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

    // Grouped settings records for atomic batch saving
    public record ServiceSettings(String brokkApiKey, LlmProxySetting proxySetting) {
        public void applyTo(Properties props) {
            var existingKey = props.getProperty("brokkApiKey", "");
            if (brokkApiKey.isBlank()) {
                if (!existingKey.isBlank()) {
                    logger.info("ServiceSettings.applyTo: removing brokkApiKey (blank key in settings record)");
                }
                props.remove("brokkApiKey");
            } else {
                props.setProperty("brokkApiKey", brokkApiKey.trim());
            }
            props.setProperty(LLM_PROXY_SETTING_KEY, proxySetting.name());
        }
    }

    public record AppearanceSettings(String theme, boolean wordWrap, String uiScale, float terminalFontSize) {
        public void applyTo(Properties props) {
            props.setProperty("theme", theme);
            props.setProperty("wordWrap", String.valueOf(wordWrap));
            props.setProperty(UI_SCALE_KEY, uiScale);
            if (terminalFontSize == 11.0f) {
                props.remove(TERMINAL_FONT_SIZE_KEY);
            } else {
                props.setProperty(TERMINAL_FONT_SIZE_KEY, Float.toString(terminalFontSize));
            }
        }
    }

    public record StartupSettings(StartupOpenMode openMode) {
        public void applyTo(Properties props) {
            props.setProperty(STARTUP_OPEN_MODE_KEY, openMode.name());
        }
    }

    public record GeneralSettings(JvmMemorySettings jvmMemory) {
        public void applyTo(Properties props) {
            if (jvmMemory.automatic()) {
                props.setProperty(JVM_MEMORY_MODE_KEY, "auto");
                props.remove(JVM_MEMORY_MB_KEY);
            } else {
                props.setProperty(JVM_MEMORY_MODE_KEY, "manual");
                props.setProperty(JVM_MEMORY_MB_KEY, Integer.toString(jvmMemory.manualMb()));
            }
        }
    }

    public record ModelSettings(List<Service.FavoriteModel> favoriteModels, McpConfig mcpConfig) {
        public void applyTo(Properties props) {
            try {
                String json = objectMapper.writeValueAsString(favoriteModels);
                props.setProperty("favoriteModelsJson", json);
            } catch (JsonProcessingException e) {
                logger.error("Error serializing favorite models to JSON", e);
            }
            try {
                String mcpJson = objectMapper.writeValueAsString(mcpConfig);
                props.setProperty("mcpConfigJson", mcpJson);
            } catch (JsonProcessingException e) {
                logger.error("Error serializing MCP config to JSON", e);
            }
        }
    }

    public static void saveAllGlobalSettings(
            ServiceSettings service,
            AppearanceSettings appearance,
            StartupSettings startup,
            GeneralSettings general,
            ModelSettings models) {
        var props = loadGlobalProperties();
        service.applyTo(props);
        appearance.applyTo(props);
        startup.applyTo(props);
        general.applyTo(props);
        models.applyTo(props);
        saveGlobalProperties(props);

        // Clear cache if brokk key changed
        if (!service.brokkApiKey().equals(getBrokkKey())) {
            isDataShareAllowedCache = null;
        }

        logger.debug("Saved all global settings atomically");
    }

    public static String getBrokkKey() {
        // Check headless executor override first (process-scoped)
        String override = headlessBrokkApiKeyOverride;
        if (override != null && !override.isBlank()) {
            return override;
        }
        // Fall back to global properties
        var props = loadGlobalProperties();
        return props.getProperty("brokkApiKey", "");
    }

    /**
     * Set a process-scoped override for the Brokk API key.
     * Used by headless executors to use a per-executor API key instead of the global config.
     * If set to a non-blank value, getBrokkKey() will return this override instead of
     * reading from global properties.
     *
     * @param key the API key override, or null/blank to clear the override
     */
    public static void setHeadlessBrokkApiKeyOverride(@Nullable String key) {
        headlessBrokkApiKeyOverride = key;
        isDataShareAllowedCache = null; // Clear cache since key changed
        logger.debug(
                "Set headless Brokk API key override: {}",
                (key != null && !key.isBlank()) ? "(non-blank, length=" + key.length() + ")" : "(cleared)");
    }

    public static void setBrokkKey(String key) {
        logger.info(
                "setBrokkKey called with key={}",
                key.isBlank() ? "(blank)" : "(non-blank, length=" + key.length() + ")");
        var props = loadGlobalProperties();
        if (key.isBlank()) {
            logger.info("setBrokkKey: removing brokkApiKey (blank key provided)");
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

    // Backward-compatible alias for UI code expecting MainProject.DEFAULT_FAVORITE_MODELS
    public static final List<Service.FavoriteModel> DEFAULT_FAVORITE_MODELS = ModelProperties.DEFAULT_FAVORITE_MODELS;

    public static List<Service.FavoriteModel> loadFavoriteModels() {
        // Check headless override first
        var override = headlessFavoriteModelsOverride;
        if (override != null) {
            logger.debug("Using headless favorite models override ({} models).", override.size());
            return override;
        }
        var props = loadGlobalProperties();
        var list = ModelProperties.loadFavoriteModels(props);
        logger.debug("Loaded {} favorite models from global properties.", list.size());
        return list;
    }

    /**
     * Sets the headless favorite models override. If set, loadFavoriteModels() and
     * getFavoriteModel() will use this list instead of reading from global properties.
     *
     * @param models the favorite models override, or null to clear the override
     */
    public static void setHeadlessFavoriteModelsOverride(@Nullable List<Service.FavoriteModel> models) {
        headlessFavoriteModelsOverride = models;
        logger.debug(
                "Set headless favorite models override: {}", models != null ? models.size() + " models" : "(cleared)");
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
        boolean changed = ModelProperties.saveFavoriteModels(props, favorites);
        if (changed) {
            saveGlobalProperties(props);
            logger.debug("Saved {} favorite models to global properties.", favorites.size());
        } else {
            logger.trace("Favorite models unchanged, skipping save.");
        }
    }

    private static Properties loadProjectsProperties() {
        var props = new Properties();
        Path projectsPath = getProjectsPropertiesPath();
        if (Files.exists(projectsPath)) {
            try (var reader = Files.newBufferedReader(projectsPath)) {
                props.load(reader);
            } catch (IOException e) {
                logger.warn("Unable to read projects properties file: {}", e.getMessage());
            }
        }
        return props;
    }

    private static void saveProjectsProperties(Properties props) {
        try {
            Path projectsPath = getProjectsPropertiesPath();
            Files.createDirectories(projectsPath.getParent());
            AtomicWrites.save(projectsPath, props, "Brokk projects: recently opened and currently open");
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
            Files.createFile(getOomFlagPath());
        } catch (IOException e) {
            logger.error("Unable to persist OutOfMemoryError flag.");
        }
    }

    public static boolean initializeOomFlag() {
        try {
            Path oomPath = getOomFlagPath();
            if (Files.exists(oomPath)) {
                Files.delete(oomPath);
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
        // Close dependency update scheduler
        dependencyUpdateScheduler.close();

        // Close disk cache if open
        try {
            if (diskCache != null) {
                diskCache.close();
                diskCache = null;
                logger.debug("Closed disk cache for project {}", root.getFileName());
            }
        } catch (Exception e) {
            logger.warn("Error closing disk cache for {}: {}", root.getFileName(), e.getMessage());
        } finally {
            closeCacheLock();
        }

        // Close session manager and other resources
        sessionManager.close();
        super.close();
    }

    /**
     * Returns the dependency update scheduler for this project.
     * Worktree projects delegate to the main project's scheduler.
     */
    public DependencyUpdateScheduler getDependencyUpdateScheduler() {
        return dependencyUpdateScheduler;
    }

    public Path getWorktreeStoragePath() {
        return Path.of(
                System.getProperty("user.home"),
                BROKK_DIR,
                "worktrees",
                getMasterRootPathForConfig().getFileName().toString());
    }

    public void reserveSessionsForKnownWorktrees() {
        if (!repo.supportsWorktrees()) {
            return;
        }
        logger.debug("Main project {} reserving sessions for its known worktrees.", this.root.getFileName());
        try {
            var worktrees = repo.listWorktrees();
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
                        if (sessionRegistry.claim(wtPath, sessionId)) {
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
    public SessionRegistry getSessionRegistry() {
        return sessionRegistry;
    }

    /**
     * Deletes a worktree associated with this project.
     *
     * @param worktreePath The path of the worktree to delete.
     * @param force        If true, force deletion even if the worktree is dirty or locked.
     * @throws GitAPIException if the git operation fails.
     */
    public void deleteWorktree(Path worktreePath, boolean force) throws GitAPIException {
        if (!hasGit() || !getRepo().supportsWorktrees()) {
            throw new GitRepo.GitRepoException("This project does not support worktrees.");
        }
        if (!(getRepo() instanceof GitRepo gitRepo)) {
            throw new GitRepo.GitRepoException("Underlying repository is not a local GitRepo.");
        }

        gitRepo.removeWorktree(worktreePath, force);
        sessionRegistry.release(worktreePath);
        removeFromOpenProjectsListAndClearActiveSession(worktreePath);
    }

    @Override
    public String getRemoteProjectName() {
        String result = null;
        if (hasGit()) {
            result = getRepo().getRemoteUrl();
        }
        if (result == null) {
            result = getRoot().getFileName().toString();
        }
        return result;
    }

    @Override
    public void sessionsListChanged() {
        for (var chrome : Brokk.getProjectAndWorktreeChromes(this)) {
            SwingUtilities.invokeLater(() -> chrome.getRightPanel().updateSessionComboBox());
        }
    }
}
