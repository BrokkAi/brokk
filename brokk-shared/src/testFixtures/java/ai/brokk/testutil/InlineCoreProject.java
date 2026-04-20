package ai.brokk.testutil;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.MultiAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.ICoreProject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class InlineCoreProject {

    private InlineCoreProject() {}

    public static Builder empty() {
        return new Builder();
    }

    public static Builder code(String contents, String relPath) {
        return new Builder().addFile(contents, relPath);
    }

    public static final class Builder {
        private final List<FileEntry> files = new ArrayList<>();
        private Set<Language> languages = Set.of();

        public Builder addFile(String contents, String relPath) {
            files.add(new FileEntry(relPath, contents));
            return this;
        }

        public Builder addFileContents(String contents, String relPath) {
            return addFile(contents, relPath);
        }

        public Builder languages(Set<Language> languages) {
            this.languages = Set.copyOf(languages);
            return this;
        }

        public BuiltProject build() {
            Path root;
            try {
                root = Files.createTempDirectory("brokk-inline-project-")
                        .toAbsolutePath()
                        .normalize();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            for (var entry : files) {
                var absPath = root.resolve(entry.relPath()).normalize();
                try {
                    if (absPath.getParent() != null) {
                        Files.createDirectories(absPath.getParent());
                    }
                    Files.writeString(
                            absPath, entry.contents(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            var detected = languages.isEmpty() ? detectLanguages(files) : languages;
            var project = new CoreTestProject(root, detected);
            var analyzer = createAnalyzer(project, detected);
            return new BuiltProject(root, project, analyzer);
        }

        private static Set<Language> detectLanguages(List<FileEntry> files) {
            var detected = new java.util.LinkedHashSet<Language>();
            for (var file : files) {
                String filename = Path.of(file.relPath()).getFileName().toString();
                int dot = filename.lastIndexOf('.');
                String ext = dot != -1 ? filename.substring(dot + 1) : "";
                var lang = Languages.fromExtension(ext);
                if (lang != Languages.NONE) {
                    detected.add(lang);
                }
            }
            return detected.isEmpty() ? Set.of(Languages.NONE) : Set.copyOf(detected);
        }

        private static IAnalyzer createAnalyzer(CoreTestProject project, Set<Language> languages) {
            var active = languages.stream().filter(l -> l != Languages.NONE).toList();
            if (active.isEmpty()) {
                return Languages.NONE.createAnalyzer(project).update();
            }
            if (active.size() == 1) {
                return active.getFirst().createAnalyzer(project).update();
            }

            Map<Language, IAnalyzer> delegates = new HashMap<>();
            for (var language : active) {
                delegates.put(language, language.createAnalyzer(project));
            }
            return new MultiAnalyzer(delegates).update();
        }
    }

    private record FileEntry(String relPath, String contents) {}

    public record BuiltProject(Path root, CoreTestProject project, IAnalyzer analyzer) implements ICoreProject {
        public IAnalyzer getAnalyzer() {
            return analyzer;
        }

        public CoreTestProject getProject() {
            return project;
        }

        public ProjectFile file(String relPath) {
            return new ProjectFile(root, Path.of(relPath));
        }

        @Override
        public Path getRoot() {
            return project.getRoot();
        }

        @Override
        public java.util.Set<ProjectFile> getAllFiles() {
            return project.getAllFiles();
        }

        @Override
        public java.util.Optional<ProjectFile> getFileByRelPath(Path relPath) {
            return project.getFileByRelPath(relPath);
        }

        @Override
        public boolean isEmptyProject() {
            return project.isEmptyProject();
        }

        @Override
        public java.util.Set<ProjectFile> getAnalyzableFiles(Language language) {
            return project.getAnalyzableFiles(language);
        }

        @Override
        public java.util.Set<Language> getAnalyzerLanguages() {
            return project.getAnalyzerLanguages();
        }

        @Override
        public void setAnalyzerLanguages(java.util.Set<Language> languages) {
            project.setAnalyzerLanguages(languages);
        }

        @Override
        public void invalidateAutoDetectedLanguages() {
            project.invalidateAutoDetectedLanguages();
        }

        @Override
        public java.util.List<String> getSourceRoots(Language language) {
            return project.getSourceRoots(language);
        }

        @Override
        public void setSourceRoots(Language language, java.util.List<String> roots) {
            project.setSourceRoots(language, roots);
        }

        @Override
        public boolean isGitignored(Path relPath) {
            return project.isGitignored(relPath);
        }

        @Override
        public boolean isGitignored(Path relPath, boolean isDirectory) {
            return project.isGitignored(relPath, isDirectory);
        }

        @Override
        public boolean shouldSkipPath(Path relPath, boolean isDirectory) {
            return project.shouldSkipPath(relPath, isDirectory);
        }

        @Override
        public java.util.Set<String> getExclusionPatterns() {
            return project.getExclusionPatterns();
        }

        @Override
        public java.util.Set<String> getExcludedDirectories() {
            return project.getExcludedDirectories();
        }

        @Override
        public java.util.Set<String> getExcludedGlobPatterns() {
            return project.getExcludedGlobPatterns();
        }

        @Override
        public boolean isPathExcluded(String relativePath, boolean isDirectory) {
            return project.isPathExcluded(relativePath, isDirectory);
        }

        @Override
        public java.util.Set<ProjectFile> filterExcludedFiles(java.util.Set<ProjectFile> files) {
            return project.filterExcludedFiles(files);
        }

        @Override
        public void invalidateAllFiles() {
            project.invalidateAllFiles();
        }

        @Override
        public ai.brokk.git.IGitRepo getRepo() {
            return project.getRepo();
        }

        @Override
        public boolean hasGit() {
            return project.hasGit();
        }

        @Override
        public Path getMasterRootPathForConfig() {
            return project.getMasterRootPathForConfig();
        }

        @Override
        public ai.brokk.util.IStringDiskCache getDiskCache() {
            return project.getDiskCache();
        }

        @Override
        public void close() {
            project.close();
        }
    }
}
