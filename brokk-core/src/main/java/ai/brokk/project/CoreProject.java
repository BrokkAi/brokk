package ai.brokk.project;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceRootScanner;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.git.IGitRepo;
import ai.brokk.git.LocalFileRepo;
import ai.brokk.util.IStringDiskCache;
import ai.brokk.util.StringDiskCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jakewharton.disklrucache.DiskLruCache;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Standalone project implementation for brokk-core.
 * Implements ICoreProject with file discovery, language detection, gitignore handling,
 * and disk caching. No LLM, GUI, session, or build-agent dependencies.
 */
public final class CoreProject implements ICoreProject {
    private static final Logger logger = LogManager.getLogger(CoreProject.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PROJECT_PROPERTIES_FILE = "project.properties";
    private static final String CACHE_DIR = "cache";
    private static final long DEFAULT_DISK_CACHE_SIZE = 50L * 1024 * 1024;
    private static final String CODE_INTELLIGENCE_LANGUAGES_KEY = "codeIntelligenceLanguages";
    private static final String EXCLUSION_PATTERNS_KEY = "exclusionPatterns";

    private final Path root;
    private final IGitRepo repo;
    private final Path masterRootPathForConfig;
    private final Properties projectProps;
    private final FileFilteringService fileFilteringService;

    @Nullable
    private volatile Map<Path, ProjectFile> filesByRelPathCache;

    @Nullable
    private volatile Set<Language> autoDetectedLanguagesCache;

    @Nullable
    private IStringDiskCache diskCache;

    @Nullable
    private FileChannel cacheLockChannel;

    @Nullable
    private FileLock cacheFileLock;

    private volatile Set<String> cachedPatternSet = Set.of();
    private volatile FileFilteringService.FilePatternMatcher cachedPatternMatcher =
            FileFilteringService.createPatternMatcher(Set.of());

    public CoreProject(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.repo = GitRepoFactory.hasGitRepo(this.root) ? new GitRepo(this.root) : new LocalFileRepo(this.root);
        this.masterRootPathForConfig = computeMasterRootForConfig(this.root, this.repo);
        this.projectProps = new Properties();
        this.fileFilteringService = new FileFilteringService(this.root, this.repo);
        initializeProjectProperties();
        logger.debug("CoreProject initialized at {}", this.root);
    }

    private void initializeProjectProperties() {
        var propertiesFile = root.resolve(BROKK_DIR).resolve(PROJECT_PROPERTIES_FILE);
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

    private static Path computeMasterRootForConfig(Path normalizedRoot, IGitRepo repo) {
        if (repo instanceof GitRepo gitRepo && gitRepo.isWorktree()) {
            return gitRepo.getGitTopLevel().toAbsolutePath().normalize();
        }
        return normalizedRoot;
    }

    @Override
    public Path getRoot() {
        return root;
    }

    @Override
    @Blocking
    public synchronized Set<ProjectFile> getAllFiles() {
        if (filesByRelPathCache == null) {
            var rawFiles = repo.getFilesForAnalysis();
            var files = filterExcludedFiles(rawFiles);
            filesByRelPathCache = files.stream()
                    .collect(Collectors.toMap(ProjectFile::getRelPath, f -> f, (existing, replacement) -> existing));
        }
        return Set.copyOf(filesByRelPathCache.values());
    }

    @Override
    @Blocking
    public synchronized Optional<ProjectFile> getFileByRelPath(Path relPath) {
        if (filesByRelPathCache == null) {
            getAllFiles();
        }
        var match = filesByRelPathCache != null ? filesByRelPathCache.get(relPath) : null;
        return Optional.ofNullable(match);
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
    public Set<ProjectFile> getAnalyzableFiles(Language language) {
        var extensions = language.getExtensions();
        return getAllFiles().stream()
                .filter(pf -> extensions.contains(pf.extension()))
                .collect(Collectors.toSet());
    }

    @Override
    public Set<Language> getAnalyzerLanguages() {
        var configured = getConfiguredAnalyzerLanguagesOrNull();
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

            Set<Language> detected = new HashSet<>();
            for (ProjectFile pf : repo.getTrackedFiles()) {
                Language lang = Languages.fromExtension(pf.extension());
                if (lang != Languages.NONE) {
                    detected.add(lang);
                }
            }

            if (detected.isEmpty()) {
                logger.debug("No recognized languages found for {}. Defaulting to NONE.", root);
                autoDetectedLanguagesCache = Set.of(Languages.NONE);
            } else {
                logger.debug(
                        "Auto-detected languages for {}: {}",
                        root,
                        detected.stream().map(Language::name).collect(Collectors.joining(", ")));
                autoDetectedLanguagesCache = Set.copyOf(detected);
            }
            return autoDetectedLanguagesCache;
        }
    }

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
        return parsed.isEmpty() ? Set.of(Languages.NONE) : parsed;
    }

    @Override
    public void setAnalyzerLanguages(Set<Language> languages) {
        if (languages.isEmpty() || (languages.size() == 1 && languages.contains(Languages.NONE))) {
            projectProps.remove(CODE_INTELLIGENCE_LANGUAGES_KEY);
        } else {
            var langsString =
                    languages.stream().map(Language::internalName).sorted().collect(Collectors.joining(","));
            projectProps.setProperty(CODE_INTELLIGENCE_LANGUAGES_KEY, langsString);
        }
        autoDetectedLanguagesCache = null;
    }

    @Override
    public void invalidateAutoDetectedLanguages() {
        autoDetectedLanguagesCache = null;
    }

    @Override
    @Blocking
    public List<String> getSourceRoots(Language language) {
        var key = language.internalName() + "SourceRoots";
        var json = projectProps.getProperty(key);
        if (json == null || json.isBlank()) {
            return SourceRootScanner.scan(this, language);
        }
        try {
            var tf = objectMapper.getTypeFactory();
            return objectMapper.readValue(json, tf.constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize source roots for {} from JSON: {}", language.name(), json, e);
            return SourceRootScanner.scan(this, language);
        }
    }

    @Override
    public void setSourceRoots(Language language, List<String> roots) {
        var key = language.internalName() + "SourceRoots";
        try {
            projectProps.setProperty(key, objectMapper.writeValueAsString(roots));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize source roots for " + language.name(), e);
        }
    }

    @Override
    public boolean isGitignored(Path relPath) {
        if (!(repo instanceof GitRepo)) {
            return false;
        }
        try {
            return fileFilteringService.isGitignored(relPath);
        } catch (Exception e) {
            logger.warn("Error checking if path {} is gitignored: {}", relPath, e.getMessage());
            return false;
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

    @Override
    public boolean shouldSkipPath(Path relPath, boolean isDirectory) {
        return isPathExcluded(relPath.toString(), isDirectory) || isGitignored(relPath, isDirectory);
    }

    @Override
    public Set<String> getExclusionPatterns() {
        var patterns = projectProps.getProperty(EXCLUSION_PATTERNS_KEY);
        if (patterns == null || patterns.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(patterns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> getExcludedDirectories() {
        return getExclusionPatterns().stream()
                .filter(p -> !p.contains("*") && !p.contains("?"))
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> getExcludedGlobPatterns() {
        return getExclusionPatterns().stream()
                .filter(p -> p.contains("*") || p.contains("?"))
                .collect(Collectors.toSet());
    }

    @Override
    public boolean isPathExcluded(String relativePath, boolean isDirectory) {
        var patterns = getExclusionPatterns();
        var currentPatterns = cachedPatternSet;
        var currentMatcher = cachedPatternMatcher;
        if (!patterns.equals(currentPatterns)) {
            currentMatcher = FileFilteringService.createPatternMatcher(patterns);
            cachedPatternMatcher = currentMatcher;
            cachedPatternSet = patterns;
        }
        return currentMatcher.isPathExcluded(relativePath, isDirectory);
    }

    @Override
    public Set<ProjectFile> filterExcludedFiles(Set<ProjectFile> files) {
        return fileFilteringService.filterFiles(files, getExclusionPatterns());
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
    @Nullable
    public IGitRepo getRepo() {
        return repo;
    }

    @Override
    public boolean hasGit() {
        return repo instanceof GitRepo;
    }

    @Override
    public Path getMasterRootPathForConfig() {
        return masterRootPathForConfig;
    }

    @Override
    public synchronized IStringDiskCache getDiskCache() {
        if (diskCache != null) {
            return diskCache;
        }

        Path primaryCacheDir = masterRootPathForConfig.resolve(BROKK_DIR).resolve(CACHE_DIR);
        if (tryOpenCache(primaryCacheDir)) {
            return Objects.requireNonNull(diskCache);
        }

        try {
            Path tempCacheDir = Files.createTempDirectory("brokk-core-cache-");
            logger.info("Primary cache locked; falling back to temporary cache at {}", tempCacheDir);
            if (tryOpenCache(tempCacheDir)) {
                return Objects.requireNonNull(diskCache);
            }
        } catch (IOException e) {
            logger.error("Failed to create temporary cache directory: {}", e.getMessage());
        }

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
            var dlc = DiskLruCache.open(cacheDir.toFile(), 1, 1, DEFAULT_DISK_CACHE_SIZE);
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

    @Override
    public Set<Path> getAllOnDiskDependencies() {
        var dependenciesPath = masterRootPathForConfig.resolve(BROKK_DIR).resolve(DEPENDENCIES_DIR);
        if (!Files.exists(dependenciesPath) || !Files.isDirectory(dependenciesPath)) {
            return Set.of();
        }
        try (var pathStream = Files.list(dependenciesPath)) {
            return pathStream.filter(Files::isDirectory).collect(Collectors.toSet());
        } catch (IOException e) {
            logger.error("Error listing dependency directories from {}: {}", dependenciesPath, e.getMessage());
            return Set.of();
        }
    }

    @Override
    public void close() {
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
        if (diskCache != null) {
            try {
                diskCache.close();
            } catch (Exception e) {
                logger.warn("Error closing disk cache: {}", e.getMessage());
            }
        }
        if (repo instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception e) {
                logger.error("Error closing repo for project {}: {}", root, e.getMessage());
            }
        }
    }
}
