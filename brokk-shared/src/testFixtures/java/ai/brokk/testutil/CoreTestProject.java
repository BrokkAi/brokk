package ai.brokk.testutil;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.ICoreProject;
import ai.brokk.util.IStringDiskCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public final class CoreTestProject implements ICoreProject {
    private final Path root;
    private final Path masterRootPathForConfig;
    private final IGitRepo repo;
    private final IStringDiskCache diskCache;

    private volatile @Nullable Set<ProjectFile> allFiles;
    private volatile @Nullable Map<Path, ProjectFile> fileByRelPath;

    private Set<Language> analyzerLanguages;
    private final Map<Language, List<String>> sourceRootsByLanguage = new ConcurrentHashMap<>();

    public CoreTestProject(Path root, Set<Language> analyzerLanguages) {
        this.root = root.toAbsolutePath().normalize();
        this.analyzerLanguages = Set.copyOf(analyzerLanguages);
        this.repo = new NoopGitRepo();
        this.diskCache = new IStringDiskCache.NoopCache();
        try {
            this.masterRootPathForConfig = Files.createTempDirectory("brokk-core-test-config-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static CoreTestProject fromResourceDir(String resourceDirName, Language language) {
        return new CoreTestProject(TestResourcePaths.resourceDirectory(resourceDirName), Set.of(language));
    }

    public static CoreTestProject fromResourceDir(String resourceDirName, Set<Language> languages) {
        return new CoreTestProject(TestResourcePaths.resourceDirectory(resourceDirName), languages);
    }

    @Override
    public Path getRoot() {
        return root;
    }

    @Override
    @Blocking
    public Set<ProjectFile> getAllFiles() {
        var cached = allFiles;
        if (cached != null) {
            return cached;
        }

        try (Stream<Path> paths = Files.walk(root)) {
            var files = paths.filter(Files::isRegularFile)
                    .map(p -> new ProjectFile(root, root.relativize(p)))
                    .collect(Collectors.toSet());

            var index = new HashMap<Path, ProjectFile>(files.size());
            for (var pf : files) {
                index.put(pf.getRelPath(), pf);
            }

            allFiles = files;
            fileByRelPath = index;
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<ProjectFile> getFileByRelPath(Path relPath) {
        var cachedIndex = fileByRelPath;
        if (cachedIndex == null) {
            getAllFiles();
            cachedIndex = fileByRelPath;
        }
        if (cachedIndex == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cachedIndex.get(relPath.normalize()));
    }

    @Override
    public boolean isEmptyProject() {
        return getAllFiles().isEmpty();
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
        return analyzerLanguages;
    }

    @Override
    public void setAnalyzerLanguages(Set<Language> languages) {
        this.analyzerLanguages = Set.copyOf(languages);
    }

    @Override
    public void invalidateAutoDetectedLanguages() {}

    @Override
    @Blocking
    public List<String> getSourceRoots(Language language) {
        return sourceRootsByLanguage.getOrDefault(language, List.of(""));
    }

    @Override
    public void setSourceRoots(Language language, List<String> roots) {
        sourceRootsByLanguage.put(language, List.copyOf(roots));
    }

    @Override
    public boolean isGitignored(Path relPath) {
        return false;
    }

    @Override
    public boolean isGitignored(Path relPath, boolean isDirectory) {
        return false;
    }

    @Override
    public boolean shouldSkipPath(Path relPath, boolean isDirectory) {
        return false;
    }

    @Override
    public Set<String> getExclusionPatterns() {
        return Set.of();
    }

    @Override
    public Set<String> getExcludedDirectories() {
        return Set.of();
    }

    @Override
    public Set<String> getExcludedGlobPatterns() {
        return Set.of();
    }

    @Override
    public boolean isPathExcluded(String relativePath, boolean isDirectory) {
        return false;
    }

    @Override
    public Set<ProjectFile> filterExcludedFiles(Set<ProjectFile> files) {
        return files;
    }

    @Override
    public void invalidateAllFiles() {
        allFiles = null;
        fileByRelPath = null;
    }

    @Override
    public IGitRepo getRepo() {
        return repo;
    }

    @Override
    public boolean hasGit() {
        return false;
    }

    @Override
    public Path getMasterRootPathForConfig() {
        return masterRootPathForConfig;
    }

    @Override
    public IStringDiskCache getDiskCache() {
        return diskCache;
    }

    @Override
    public void close() {
        try {
            diskCache.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
