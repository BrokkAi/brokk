package ai.brokk.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.git.GitRepoFactory;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RealProjectFixtureTest {

    @TempDir
    Path tempDir;

    @Test
    void testFromZip() throws IOException {
        Path zipPath = tempDir.resolve("test.zip");
        createTestZip(zipPath, "src/Main.java", "public class Main { public void hello() {} }");

        try (ITestProject project = InlineTestProjectCreator.fromZip(zipPath).build()) {
            assertTrue(Files.exists(project.getRoot().resolve("src/Main.java")));

            IAnalyzer analyzer = project.getAnalyzer();
            assertNotNull(analyzer);
            assertFalse(analyzer.isEmpty());

            var definitions = analyzer.getDefinitions("Main");
            assertFalse(definitions.isEmpty(), "Should find Main class definition from zip");
        }
    }

    @Test
    @Tag("git-integration")
    void testRuffProject() throws Exception {
        String url = "https://github.com/astral-sh/ruff.git";
        String commitId = "0e19fc9a61477e71abc4eb76f05a129b6b9ab873";

        try (ITestProject project =
                InlineTestProjectCreator.fromGitUrl(url, commitId).build()) {
            assertNotNull(project, "Project should not be null");
            assertNotNull(project.getRoot(), "Project root should not be null");
            assertTrue(Files.exists(project.getRoot().resolve("pyproject.toml")), "pyproject.toml should exist");
            assertTrue(Files.exists(project.getRoot().resolve("Cargo.toml")), "Cargo.toml should exist");

            assertTrue(project.hasGit(), "Project should have git");
            assertNotNull(project.getRepo(), "Project repo should not be null");
            assertEquals(commitId, project.getRepo().getCurrentCommitId(), "Commit ID should match");

            IAnalyzer analyzer = project.getAnalyzer();
            assertNotNull(analyzer, "Analyzer should not be null");

            assertFalse(
                    analyzer.isEmpty(),
                    "Analyzer should not be empty for Ruff project. Detected project languages: "
                            + project.getAnalyzerLanguages() + " Analyzer languages: " + analyzer.languages());

            assertTrue(
                    analyzer.languages().contains(ai.brokk.analyzer.Languages.PYTHON),
                    "Should detect Python. Analyzer languages: " + analyzer.languages());
            assertTrue(
                    analyzer.languages().contains(ai.brokk.analyzer.Languages.RUST),
                    "Should detect Rust. Analyzer languages: " + analyzer.languages());

            // Ruff is a Python project, so we expect to find some Python declarations
            var declarations = analyzer.getAllDeclarations();
            assertFalse(declarations.isEmpty(), "Should find some declarations in Ruff project");
        }
    }

    @Test
    @Tag("git-integration")
    void testFromGitUrlWithCompressedCache(@TempDir Path customCache) throws Exception {
        InlineTestProjectCreator.GitCloneStrategy.setCacheRoot(customCache);

        // 1. Create a local source repo
        Path sourceRepoPath = tempDir.resolve("source-repo-cached");
        Files.createDirectories(sourceRepoPath);
        GitRepoFactory.initRepo(sourceRepoPath);
        Files.writeString(sourceRepoPath.resolve("Foo.java"), "public class Foo {}");

        String commitId;
        try (Git git = Git.open(sourceRepoPath.toFile())) {
            git.add().addFilepattern("Foo.java").call();
            commitId = git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Tester", "tester@brokk.ai")
                    .setSign(false)
                    .call()
                    .name();
        }

        String url = sourceRepoPath.toUri().toString();

        // 2. First run: should create .tar.lz4 in cache
        try (ITestProject project =
                InlineTestProjectCreator.fromGitUrl(url, "HEAD").build()) {
            assertTrue(Files.exists(project.getRoot().resolve("Foo.java")));
            assertEquals(commitId, project.getRepo().getCurrentCommitId());
        }

        try (var stream = Files.list(customCache)) {
            List<Path> files = stream.map(Path::getFileName).toList();
            assertTrue(files.stream().anyMatch(p -> p.toString().endsWith(".tar.lz4")), "Cache archive should exist");
            assertFalse(
                    files.stream().anyMatch(p -> p.toString().endsWith(".expanded")),
                    "Expanded directory should have been cleaned up");
        }

        // 3. Second run: should use the archive
        try (ITestProject project =
                InlineTestProjectCreator.fromGitUrl(url, "HEAD").build()) {
            assertTrue(Files.exists(project.getRoot().resolve("Foo.java")));
            assertEquals(commitId, project.getRepo().getCurrentCommitId());
        }

        // 4. Simulate corruption
        Path archive = Files.list(customCache)
                .filter(p -> p.toString().endsWith(".tar.lz4"))
                .findFirst()
                .orElseThrow();
        Files.writeString(archive, "corrupt content");

        try (ITestProject project =
                InlineTestProjectCreator.fromGitUrl(url, "HEAD").build()) {
            assertTrue(Files.exists(project.getRoot().resolve("Foo.java")), "Should recover from corrupt archive");
            assertEquals(commitId, project.getRepo().getCurrentCommitId());
        }

        // 5. Ensure archive was rebuilt (is now valid LZ4 again)
        try (var fis = Files.newInputStream(archive);
                var lz4In = new net.jpountz.lz4.LZ4FrameInputStream(fis)) {
            // If this doesn't throw, it's at least a valid LZ4 frame
        }
    }

    @Test
    @Tag("git-integration")
    void testFromGitUrl() throws Exception {
        // 1. Create a local source repo
        Path sourceRepoPath = tempDir.resolve("source-repo");
        Files.createDirectories(sourceRepoPath);
        GitRepoFactory.initRepo(sourceRepoPath);

        Path javaFile = sourceRepoPath.resolve("Foo.java");
        Files.writeString(javaFile, "public class Foo {}");

        String commitId;
        try (Git git = Git.open(sourceRepoPath.toFile())) {
            git.add().addFilepattern("Foo.java").call();
            var commit = git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Tester", "tester@brokk.ai")
                    .setSign(false)
                    .call();
            commitId = commit.name();
        }

        // 2. Use fromGitUrl to "clone" from the local path.
        // Using "HEAD" ensures we get the default branch regardless of whether it is 'master' or 'main'.
        String url = sourceRepoPath.toUri().toString();
        try (ITestProject project =
                InlineTestProjectCreator.fromGitUrl(url, "HEAD").build()) {
            assertTrue(project.hasGit());
            assertNotNull(project.getRepo());
            assertEquals(commitId, project.getRepo().getCurrentCommitId());

            assertTrue(Files.exists(project.getRoot().resolve("Foo.java")));

            IAnalyzer analyzer = project.getAnalyzer();
            assertNotNull(analyzer, "Analyzer should not be null");
            assertFalse(analyzer.isEmpty(), "Analyzer should not be empty");
            assertFalse(analyzer.getDefinitions("Foo").isEmpty(), "Should find Foo class definition");
        }
    }

    @Test
    @Tag("git-integration")
    void testFromGitUsesRepoWorkTreeRoot() throws Exception {
        Path sourceRepoPath = tempDir.resolve("source-repo-from-git");
        Files.createDirectories(sourceRepoPath);
        GitRepoFactory.initRepo(sourceRepoPath);

        Path javaFile = sourceRepoPath.resolve("Foo.java");
        Files.writeString(javaFile, "public class Foo {}");

        try (Git git = Git.open(sourceRepoPath.toFile())) {
            git.add().addFilepattern("Foo.java").call();
            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Tester", "tester@brokk.ai")
                    .setSign(false)
                    .call();
        }

        try (ai.brokk.git.GitRepo repo = new ai.brokk.git.GitRepo(sourceRepoPath)) {
            ITestProject project = InlineTestProjectCreator.fromGit(repo).build();
            try (project) {
                assertEquals(repo.getWorkTreeRoot(), project.getRoot());
                assertTrue(project.hasGit());
                assertTrue(project.getRepo() == repo);
                assertTrue(Files.exists(project.getRoot().resolve("Foo.java")));
            }

            // fromGit(repo) should not delete caller-owned repository files
            assertTrue(Files.exists(sourceRepoPath.resolve("Foo.java")));
        }
    }

    private void createTestZip(Path zipPath, String fileName, String content) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            ZipEntry entry = new ZipEntry(fileName);
            zos.putNextEntry(entry);
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
