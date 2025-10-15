package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.mcp.McpConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class IProjectAnalyzableFilesTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private TestProjectImpl project;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("testProject");
        Files.createDirectories(projectRoot);

        // Initialize a git repo
        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            Path readme = projectRoot.resolve("README.md");
            Files.writeString(readme, "Test project");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Test User", "test@example.com")
                    .setSign(false)
                    .call();
        }

        project = new TestProjectImpl(projectRoot);
    }

    @Test
    void getAnalyzableFiles_filters_by_language_extension() throws Exception {
        // Create files with different extensions
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/Test.java"), "public class Test {}");
        Files.writeString(projectRoot.resolve("script.py"), "print('hello')");
        Files.writeString(projectRoot.resolve("config.json"), "{}");

        project.invalidateAllFiles();

        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);
        var pythonFiles = project.getAnalyzableFiles(Languages.PYTHON);

        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.get(0).endsWith("src/Test.java"));

        assertEquals(1, pythonFiles.size());
        assertTrue(pythonFiles.get(0).endsWith("script.py"));
    }

    @Test
    void getAnalyzableFiles_respects_baseline_exclusions() throws Exception {
        // Create files
        Files.createDirectories(projectRoot.resolve("src"));
        Files.createDirectories(projectRoot.resolve("target"));
        Files.writeString(projectRoot.resolve("src/Main.java"), "public class Main {}");
        Files.writeString(projectRoot.resolve("target/Generated.java"), "public class Generated {}");

        project.invalidateAllFiles();
        project.setExcludedDirectories(Set.of("target"));

        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);

        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.get(0).endsWith("src/Main.java"));
        assertFalse(javaFiles.stream().anyMatch(p -> p.toString().contains("target")));
    }

    @Test
    void getAnalyzableFiles_respects_gitignore() throws Exception {
        // Create .gitignore
        Files.writeString(projectRoot.resolve(".gitignore"), "**/.idea/\n**/node_modules/\n");

        // Create files
        Files.createDirectories(projectRoot.resolve("src"));
        Files.createDirectories(projectRoot.resolve(".idea"));
        Files.createDirectories(projectRoot.resolve("frontend/node_modules"));
        Files.writeString(projectRoot.resolve("src/App.js"), "console.log('app')");
        Files.writeString(projectRoot.resolve(".idea/workspace.xml"), "xml");
        Files.writeString(projectRoot.resolve("frontend/node_modules/lib.js"), "module");

        project.invalidateAllFiles();

        var jsFiles = project.getAnalyzableFiles(Languages.JAVASCRIPT);

        assertEquals(1, jsFiles.size());
        assertTrue(jsFiles.get(0).endsWith("src/App.js"));
        assertFalse(jsFiles.stream().anyMatch(p -> p.toString().contains(".idea")));
        assertFalse(jsFiles.stream().anyMatch(p -> p.toString().contains("node_modules")));
    }

    @Test
    void getAnalyzableFiles_combines_baseline_and_gitignore() throws Exception {
        // Create .gitignore
        Files.writeString(projectRoot.resolve(".gitignore"), "**/build/\n");

        // Create files
        Files.createDirectories(projectRoot.resolve("src"));
        Files.createDirectories(projectRoot.resolve("target"));
        Files.createDirectories(projectRoot.resolve("build"));
        Files.writeString(projectRoot.resolve("src/Main.java"), "public class Main {}");
        Files.writeString(projectRoot.resolve("target/Gen1.java"), "public class Gen1 {}");
        Files.writeString(projectRoot.resolve("build/Gen2.java"), "public class Gen2 {}");

        project.invalidateAllFiles();
        project.setExcludedDirectories(Set.of("target"));

        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);

        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.get(0).endsWith("src/Main.java"));
        assertFalse(javaFiles.stream().anyMatch(p -> p.toString().contains("target")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.toString().contains("build")));
    }

    @Test
    void getAnalyzableFiles_handles_missing_gitignore() throws Exception {
        // Don't create .gitignore
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/Test.java"), "public class Test {}");

        project.invalidateAllFiles();

        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);

        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.get(0).endsWith("src/Test.java"));
    }

    private static class TestProjectImpl implements IProject {
        private final Path root;
        private final GitRepo repo;
        private Set<String> excludedDirectories = Set.of();

        TestProjectImpl(Path root) {
            this.root = root;
            this.repo = new GitRepo(root);
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public IGitRepo getRepo() {
            return repo;
        }

        @Override
        public Set<ProjectFile> getAllFiles() {
            try (Stream<Path> stream = Files.walk(root)) {
                return stream.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().equals(".git"))
                        .map(p -> new ProjectFile(root, root.relativize(p)))
                        .collect(Collectors.toSet());
            } catch (IOException e) {
                return Set.of();
            }
        }

        @Override
        public Set<String> getExcludedDirectories() {
            return excludedDirectories;
        }

        public void setExcludedDirectories(Set<String> excluded) {
            this.excludedDirectories = excluded;
            invalidateAllFiles();
        }

        @Override
        public Set<Language> getAnalyzerLanguages() {
            return Set.of(Languages.JAVA, Languages.JAVASCRIPT, Languages.PYTHON);
        }

        @Override
        public Language getBuildLanguage() {
            return Languages.JAVA;
        }

        @Override
        public BuildAgent.BuildDetails loadBuildDetails() {
            return BuildAgent.BuildDetails.EMPTY;
        }

        @Override
        public BuildAgent.BuildDetails awaitBuildDetails() {
            return BuildAgent.BuildDetails.EMPTY;
        }

        @Override
        public void setCodeAgentTestScope(IProject.CodeAgentTestScope scope) {}

        @Override
        public IProject.CodeAgentTestScope getCodeAgentTestScope() {
            return IProject.CodeAgentTestScope.WORKSPACE;
        }

        @Override
        public String getStyleGuide() {
            return "";
        }

        @Override
        public McpConfig getMcpConfig() {
            return McpConfig.EMPTY;
        }

        @Override
        public void setMcpConfig(McpConfig config) {}
    }
}
