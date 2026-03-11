package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for EnvironmentPython, verifying that distutils detection
 * caps the selected Python version at 3.11 via the public getPythonVersion() API.
 */
public class EnvironmentPythonTest {

    @TempDir
    Path tempDir;

    private static void assertVersionCappedAt311(String version) {
        var parts = version.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        assertTrue(major < 3 || (major == 3 && minor <= 11),
                   "distutils import should cap version at 3.11, got " + version);
    }

    @Test
    void testTrackedDistutilsImportCapsVersion() throws Exception {
        Path repoPath = tempDir.resolve("tracked-distutils");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            Path srcDir = repoPath.resolve("src");
            Files.createDirectories(srcDir);
            Files.writeString(srcDir.resolve("setup_helper.py"),
                              "from distutils.core import setup\n");

            git.add().addFilepattern("src/setup_helper.py").call();
            git.commit()
                    .setMessage("Add setup helper")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        var version = new EnvironmentPython(repoPath).getPythonVersion();
        assertVersionCappedAt311(version);
    }

    @Test
    void testNoGitRepoFallbackDetectsDistutils() throws Exception {
        Path projectPath = tempDir.resolve("no-git");
        Files.createDirectories(projectPath);

        Files.writeString(projectPath.resolve("setup.py"),
                          "from distutils.core import setup\nsetup(name='test')\n");

        var version = new EnvironmentPython(projectPath).getPythonVersion();
        assertVersionCappedAt311(version);
    }

    @Test
    void testImportDistutilsPatternCapsVersion() throws Exception {
        Path projectPath = tempDir.resolve("import-distutils");
        Files.createDirectories(projectPath);

        Files.writeString(projectPath.resolve("util.py"),
                          "import distutils\nimport distutils.util\n");

        var version = new EnvironmentPython(projectPath).getPythonVersion();
        assertVersionCappedAt311(version);
    }

    @Test
    void testGitignoreNegationAllowsDetection() throws Exception {
        Path repoPath = tempDir.resolve("negation-test");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            Files.writeString(repoPath.resolve(".gitignore"),
                              "generated/**\n!generated/important.py\n");
            git.add().addFilepattern(".gitignore").call();

            Path generatedDir = repoPath.resolve("generated");
            Files.createDirectories(generatedDir);

            Files.writeString(generatedDir.resolve("ignored.py"),
                              "from distutils.core import setup");
            Files.writeString(generatedDir.resolve("important.py"),
                              "from distutils.core import setup");

            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        var version = new EnvironmentPython(repoPath).getPythonVersion();
        assertVersionCappedAt311(version);
    }

    @Test
    void testRealSourceDistutilsCapsVersion() throws Exception {
        Path projectPath = tempDir.resolve("real-distutils");
        Files.createDirectories(projectPath);

        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("setup_helper.py"),
                          "from distutils.core import setup\n");

        var version = new EnvironmentPython(projectPath).getPythonVersion();
        assertVersionCappedAt311(version);
    }
}
