package ai.brokk.testutil;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Minimal inline test project creator for {@code :brokk-shared} tests.
 *
 * <p>This is intentionally small: enough to support analyzer + context-fragment tests without pulling in app wiring.
 */
public final class InlineTestProjectCreator {
    private InlineTestProjectCreator() {}

    public static TestProjectBuilder code(String contents, String filename) {
        return new TestProjectBuilder(contents, filename);
    }

    public static final class TestProjectBuilder {
        private final Map<String, String> files = new LinkedHashMap<>();

        private TestProjectBuilder(String contents, String filename) {
            this.files.put(filename, contents);
        }

        public TestProjectBuilder addFileContents(String contents, String filename) {
            this.files.put(filename, contents);
            return this;
        }

        public ITestProject build() {
            try {
                var root = Files.createTempDirectory("brokk-analyzer-test-");
                for (var entry : files.entrySet()) {
                    var target = root.resolve(entry.getKey());
                    var parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
                }
                var langs = files.keySet().stream()
                        .map(TestProjectBuilder::extension)
                        .map(Languages::fromExtension)
                        .collect(java.util.stream.Collectors.toSet());
                return new InlineTestProject(root, langs);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String extension(String filename) {
            int dot = filename.lastIndexOf('.');
            return dot == -1 ? "" : filename.substring(dot + 1);
        }
    }

    private static final class InlineTestProject implements ITestProject {
        private final CoreTestProject core;

        InlineTestProject(Path root, Set<Language> languages) {
            this.core = new CoreTestProject(root, languages);
            for (var language : languages) {
                core.setSourceRoots(language, java.util.List.of(""));
            }
        }

        @Override
        public ai.brokk.analyzer.IAnalyzer getAnalyzer() {
            return AnalyzerCreator.createFor(this).update();
        }

        @Override
        public Path getRoot() {
            return core.getRoot();
        }

        @Override
        public Set<ai.brokk.analyzer.ProjectFile> getAllFiles() {
            return core.getAllFiles();
        }

        @Override
        public java.util.Optional<ai.brokk.analyzer.ProjectFile> getFileByRelPath(Path relPath) {
            return core.getFileByRelPath(relPath);
        }

        @Override
        public boolean isEmptyProject() {
            return core.isEmptyProject();
        }

        @Override
        public Set<ai.brokk.analyzer.ProjectFile> getAnalyzableFiles(Language language) {
            return core.getAnalyzableFiles(language);
        }

        @Override
        public Set<Language> getAnalyzerLanguages() {
            return core.getAnalyzerLanguages();
        }

        @Override
        public void setAnalyzerLanguages(Set<Language> languages) {
            core.setAnalyzerLanguages(languages);
        }

        @Override
        public void invalidateAutoDetectedLanguages() {
            core.invalidateAutoDetectedLanguages();
        }

        @Override
        public java.util.List<String> getSourceRoots(Language language) {
            return core.getSourceRoots(language);
        }

        @Override
        public void setSourceRoots(Language language, java.util.List<String> roots) {
            core.setSourceRoots(language, roots);
        }

        @Override
        public boolean isGitignored(Path relPath) {
            return core.isGitignored(relPath);
        }

        @Override
        public boolean isGitignored(Path relPath, boolean isDirectory) {
            return core.isGitignored(relPath, isDirectory);
        }

        @Override
        public boolean shouldSkipPath(Path relPath, boolean isDirectory) {
            return core.shouldSkipPath(relPath, isDirectory);
        }

        @Override
        public Set<String> getExclusionPatterns() {
            return core.getExclusionPatterns();
        }

        @Override
        public Set<String> getExcludedDirectories() {
            return core.getExcludedDirectories();
        }

        @Override
        public Set<String> getExcludedGlobPatterns() {
            return core.getExcludedGlobPatterns();
        }

        @Override
        public boolean isPathExcluded(String relativePath, boolean isDirectory) {
            return core.isPathExcluded(relativePath, isDirectory);
        }

        @Override
        public Set<ai.brokk.analyzer.ProjectFile> filterExcludedFiles(Set<ai.brokk.analyzer.ProjectFile> files) {
            return core.filterExcludedFiles(files);
        }

        @Override
        public void invalidateAllFiles() {
            core.invalidateAllFiles();
        }

        @Override
        public ai.brokk.git.IGitRepo getRepo() {
            return core.getRepo();
        }

        @Override
        public boolean hasGit() {
            return core.hasGit();
        }

        @Override
        public Path getMasterRootPathForConfig() {
            return core.getMasterRootPathForConfig();
        }

        @Override
        public ai.brokk.util.IStringDiskCache getDiskCache() {
            return core.getDiskCache();
        }

        @Override
        public void close() {
            core.close();
        }
    }
}
