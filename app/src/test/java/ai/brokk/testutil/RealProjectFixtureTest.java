package ai.brokk.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.project.IProject;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RealProjectFixtureTest {

    @TempDir
    Path tempDir;

    @Test
    void testFromZip() throws IOException {
        Path zipPath = tempDir.resolve("test.zip");
        createTestZip(zipPath, "src/Main.java", "public class Main { public void hello() {} }");

        try (IProject project = InlineTestProjectCreator.fromZip(zipPath).build()) {
            assertTrue(Files.exists(project.getRoot().resolve("src/Main.java")));

            IAnalyzer analyzer = ((ITestProject) project).getAnalyzer();
            assertNotNull(analyzer);
            assertFalse(analyzer.isEmpty());

            var definitions = analyzer.getDefinitions("Main");
            assertFalse(definitions.isEmpty(), "Should find Main class definition from zip");
        }
    }

    @Test
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

        // 2. Use fromGitUrl to "clone" from the local path
        String url = sourceRepoPath.toUri().toString();
        try (IProject project =
                InlineTestProjectCreator.fromGitUrl(url, "master").build()) {
            assertTrue(project.hasGit());
            assertNotNull(project.getRepo());
            assertEquals(commitId, project.getRepo().getCurrentCommitId());

            assertTrue(Files.exists(project.getRoot().resolve("Foo.java")));

            IAnalyzer analyzer = ((ITestProject) project).getAnalyzer();
            assertFalse(analyzer.isEmpty());
            assertFalse(analyzer.getDefinitions("Foo").isEmpty());
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
