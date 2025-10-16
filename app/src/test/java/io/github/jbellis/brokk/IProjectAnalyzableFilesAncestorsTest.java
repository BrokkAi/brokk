package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.mcp.McpConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Small focused test to validate that files under directories ignored by root .gitignore
 * (directory patterns with trailing slashes) are excluded by IProject.getAnalyzableFiles.
 *
 * This is an additional test to exercise ancestor-directory .gitignore matching.
 */
public class IProjectAnalyzableFilesAncestorsTest {

    @TempDir
    Path tempDir;

    @Test
    void ignoredDirectoryPatterns_exclude_files_under_them() throws Exception {
        Path projectRoot = tempDir.resolve("testProject");
        Files.createDirectories(projectRoot);

        // Initialize a git repo (JGit) so GitRepo can operate
        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            Path readme = projectRoot.resolve("README.md");
            Files.writeString(readme, "Test project");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        // Write root .gitignore that ignores directories via trailing slash
        Files.writeString(projectRoot.resolve(".gitignore"), "**/.idea/\n**/node_modules/\n");

        // Create files and directories
        Files.createDirectories(projectRoot.resolve("src"));
        Files.createDirectories(projectRoot.resolve(".idea"));
        Files.createDirectories(projectRoot.resolve("frontend/node_modules"));

        Files.writeString(projectRoot.resolve("src/App.js"), "console.log('app')");
        Files.writeString(projectRoot.resolve(".idea/workspace.xml"), "xml");
        Files.writeString(projectRoot.resolve("frontend/node_modules/lib.js"), "module");

        TestProjectImpl project = new TestProjectImpl(projectRoot);
        project.invalidateAllFiles();

        var jsFiles = project.getAnalyzableFiles(Languages.JAVASCRIPT);

        // Expect only the src/App.js to be present (node_modules and .idea files excluded)
        assertEquals(1, jsFiles.size(), "Only one analyzable JS file should remain after .gitignore filtering");
        assertTrue(jsFiles.get(0).endsWith("src/App.js"));

        // Helper expectations
        assertFalse(jsFiles.stream().anyMatch(p -> p.toString().contains(".idea")));
        assertFalse(jsFiles.stream().anyMatch(p -> p.toString().contains("node_modules")));
    }

    // Minimal TestProjectImpl similar to the other test helpers in the repo. Provides getAllFiles and
    // getExcludedDirectories.
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
            } catch (Exception e) {
                return Set.of();
            }
        }

        @Override
        public Set<String> getExcludedDirectories() {
            return excludedDirectories;
        }

        @Override
        public void invalidateAllFiles() {}

        // Stubs for other methods used by analyzers/tests; keep minimal
        @Override
        public Set<Language> getAnalyzerLanguages() {
            return Set.of(Languages.JAVASCRIPT);
        }

        @Override
        public Language getBuildLanguage() {
            return Languages.JAVASCRIPT;
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

        @Override
        public void close() {}
    }
}
