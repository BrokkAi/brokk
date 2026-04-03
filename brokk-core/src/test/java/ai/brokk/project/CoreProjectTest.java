package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreProjectTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private CoreProject project;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("repo");
        Files.createDirectories(projectRoot);
    }

    @AfterEach
    void tearDown() {
        if (project != null) {
            project.close();
        }
    }

    private void initGitRepo(Path root) throws GitAPIException, IOException {
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            var readme = root.resolve("README.md");
            Files.writeString(readme, "# Test Project");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Test", "test@test.com")
                    .setSign(false)
                    .call();
        }
    }

    // -- Initialization tests --

    @Test
    void initializesWithGitRepo() throws Exception {
        initGitRepo(projectRoot);
        project = new CoreProject(projectRoot);

        assertEquals(projectRoot.toAbsolutePath().normalize(), project.getRoot());
        assertTrue(project.hasGit());
        assertNotNull(project.getRepo());
    }

    @Test
    void initializesWithoutGitRepo() {
        project = new CoreProject(projectRoot);

        assertEquals(projectRoot.toAbsolutePath().normalize(), project.getRoot());
        assertFalse(project.hasGit());
        assertNotNull(project.getRepo());
    }

    // -- File discovery tests --

    @Test
    void discoversTrackedFiles() throws Exception {
        initGitRepo(projectRoot);
        Files.writeString(projectRoot.resolve("Main.java"), "public class Main {}");
        try (Git git = Git.open(projectRoot.toFile())) {
            git.add().addFilepattern("Main.java").call();
            git.commit()
                    .setMessage("Add Main.java")
                    .setAuthor("Test", "test@test.com")
                    .setSign(false)
                    .call();
        }

        project = new CoreProject(projectRoot);
        var files = project.getAllFiles();
        var names = files.stream().map(ProjectFile::toString).collect(Collectors.toSet());

        assertTrue(names.contains("README.md"), "Should contain README.md");
        assertTrue(names.contains("Main.java"), "Should contain Main.java");
    }

    @Test
    void discoversFilesWithoutGit() throws Exception {
        Files.writeString(projectRoot.resolve("hello.py"), "print('hello')");
        Files.createDirectories(projectRoot.resolve("sub"));
        Files.writeString(projectRoot.resolve("sub/util.py"), "x = 1");

        project = new CoreProject(projectRoot);
        var files = project.getAllFiles();
        var names = files.stream().map(ProjectFile::toString).collect(Collectors.toSet());

        assertTrue(names.contains("hello.py"), "Should contain hello.py");
        assertTrue(names.contains("sub/util.py"), "Should contain sub/util.py");
    }

    @Test
    void getFileByRelPathFindsExistingFile() throws Exception {
        initGitRepo(projectRoot);
        project = new CoreProject(projectRoot);

        var result = project.getFileByRelPath(Path.of("README.md"));
        assertTrue(result.isPresent(), "Should find README.md by relative path");
    }

    @Test
    void getFileByRelPathReturnsEmptyForMissing() throws Exception {
        initGitRepo(projectRoot);
        project = new CoreProject(projectRoot);

        var result = project.getFileByRelPath(Path.of("nonexistent.txt"));
        assertFalse(result.isPresent());
    }

    // -- Language detection tests --

    @Test
    void detectsJavaLanguage() throws Exception {
        initGitRepo(projectRoot);
        Files.writeString(projectRoot.resolve("Foo.java"), "public class Foo {}");
        try (Git git = Git.open(projectRoot.toFile())) {
            git.add().addFilepattern("Foo.java").call();
            git.commit()
                    .setMessage("Add Java file")
                    .setAuthor("Test", "test@test.com")
                    .setSign(false)
                    .call();
        }

        project = new CoreProject(projectRoot);
        var languages = project.getAnalyzerLanguages();

        assertTrue(
                languages.stream()
                        .map(Language::name)
                        .anyMatch(n -> n.toLowerCase(Locale.ROOT).contains("java")),
                "Should detect Java language from .java files");
    }

    @Test
    void detectsPythonLanguage() throws Exception {
        initGitRepo(projectRoot);
        Files.writeString(projectRoot.resolve("app.py"), "print('hello')");
        try (Git git = Git.open(projectRoot.toFile())) {
            git.add().addFilepattern("app.py").call();
            git.commit()
                    .setMessage("Add Python file")
                    .setAuthor("Test", "test@test.com")
                    .setSign(false)
                    .call();
        }

        project = new CoreProject(projectRoot);
        var languages = project.getAnalyzerLanguages();

        assertTrue(
                languages.stream()
                        .map(Language::name)
                        .anyMatch(n -> n.toLowerCase(Locale.ROOT).contains("python")),
                "Should detect Python language from .py files");
    }

    @Test
    void returnsNoneForUnrecognizedFiles() throws Exception {
        initGitRepo(projectRoot);
        // README.md alone won't match any analyzer language
        project = new CoreProject(projectRoot);
        var languages = project.getAnalyzerLanguages();

        assertEquals(Set.of(Languages.NONE), languages, "Should return NONE for non-code files");
    }

    // -- Empty project detection --

    @Test
    void detectsEmptyProject() throws Exception {
        initGitRepo(projectRoot);
        // Only has README.md, no analyzable code
        project = new CoreProject(projectRoot);
        assertTrue(project.isEmptyProject(), "Project with only markdown should be empty");
    }

    @Test
    void detectsNonEmptyProject() throws Exception {
        initGitRepo(projectRoot);
        Files.writeString(projectRoot.resolve("Main.java"), "public class Main {}");
        try (Git git = Git.open(projectRoot.toFile())) {
            git.add().addFilepattern("Main.java").call();
            git.commit()
                    .setMessage("Add code")
                    .setAuthor("Test", "test@test.com")
                    .setSign(false)
                    .call();
        }

        project = new CoreProject(projectRoot);
        assertFalse(project.isEmptyProject(), "Project with Java files should not be empty");
    }

    // -- Exclusion pattern tests --

    @Test
    void exclusionPatternsEmptyByDefault() {
        project = new CoreProject(projectRoot);
        assertTrue(project.getExclusionPatterns().isEmpty());
    }

    @Test
    void exclusionPatternsFromProjectProperties() throws Exception {
        var brokkDir = projectRoot.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Files.writeString(brokkDir.resolve("project.properties"), "exclusionPatterns=build,dist,*.log");

        project = new CoreProject(projectRoot);
        var patterns = project.getExclusionPatterns();

        assertEquals(Set.of("build", "dist", "*.log"), patterns);
    }

    @Test
    void isPathExcludedMatchesDirectories() throws Exception {
        var brokkDir = projectRoot.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Files.writeString(brokkDir.resolve("project.properties"), "exclusionPatterns=build,node_modules");

        project = new CoreProject(projectRoot);

        assertTrue(project.isPathExcluded("build", true));
        assertTrue(project.isPathExcluded("node_modules", true));
        assertFalse(project.isPathExcluded("src", true));
    }

    // -- Disk cache tests --

    @Test
    void getDiskCacheReturnsWorkingCache() {
        project = new CoreProject(projectRoot);
        var cache = project.getDiskCache();
        assertNotNull(cache, "Should return a disk cache or noop fallback");
    }

    // -- Invalidation tests --

    @Test
    void invalidateAllFilesResetsCache() throws Exception {
        initGitRepo(projectRoot);
        project = new CoreProject(projectRoot);

        // Prime the cache
        var filesBefore = project.getAllFiles();
        assertFalse(filesBefore.isEmpty());

        // Invalidate
        project.invalidateAllFiles();

        // Should re-scan and still find files
        var filesAfter = project.getAllFiles();
        assertEquals(filesBefore, filesAfter);
    }

    // -- Source roots tests --

    @Test
    void setAndGetSourceRoots() {
        project = new CoreProject(projectRoot);
        var roots = java.util.List.of("src/main/java", "src/test/java");
        project.setSourceRoots(Languages.JAVA, roots);

        var retrieved = project.getSourceRoots(Languages.JAVA);
        assertEquals(roots, retrieved);
    }

    // -- Close/cleanup tests --

    @Test
    void closeReleasesResources() {
        project = new CoreProject(projectRoot);
        // Access cache to allocate resources
        project.getDiskCache();
        // Should not throw
        project.close();
        // Create fresh instance so tearDown doesn't double-close
        project = new CoreProject(projectRoot);
    }

    // -- Analyzable files test --

    @Test
    void getAnalyzableFilesFiltersByLanguage() throws Exception {
        initGitRepo(projectRoot);
        Files.writeString(projectRoot.resolve("App.java"), "public class App {}");
        Files.writeString(projectRoot.resolve("util.py"), "x = 1");
        try (Git git = Git.open(projectRoot.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("Add mixed files")
                    .setAuthor("Test", "test@test.com")
                    .setSign(false)
                    .call();
        }

        project = new CoreProject(projectRoot);
        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);
        var pyFiles = project.getAnalyzableFiles(Languages.PYTHON);

        assertEquals(1, javaFiles.size(), "Should find exactly one Java file");
        assertEquals(1, pyFiles.size(), "Should find exactly one Python file");
        assertTrue(javaFiles.iterator().next().toString().endsWith(".java"));
        assertTrue(pyFiles.iterator().next().toString().endsWith(".py"));
    }
}
