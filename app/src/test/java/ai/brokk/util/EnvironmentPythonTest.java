package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.FileFilteringService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
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
        assertTrue(
                major < 3 || (major == 3 && minor <= 11),
                "distutils import should cap version at 3.11, got " + version);
    }

    private static void assertVersionNotCappedAt311(String version) {
        var parts = version.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        assertTrue(major >= 3 && minor >= 0, "Should return a valid Python version, got " + version);
    }

    @Test
    void testTrackedDistutilsImportCapsVersion() throws Exception {
        Path repoPath = tempDir.resolve("tracked-distutils");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            Path srcDir = repoPath.resolve("src");
            Files.createDirectories(srcDir);
            Files.writeString(srcDir.resolve("setup_helper.py"), "from distutils.core import setup\n");

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

        Files.writeString(projectPath.resolve("setup.py"), "from distutils.core import setup\nsetup(name='test')\n");

        var version = new EnvironmentPython(projectPath).getPythonVersion();
        assertVersionCappedAt311(version);
    }

    @Test
    void testImportDistutilsPatternCapsVersion() throws Exception {
        Path projectPath = tempDir.resolve("import-distutils");
        Files.createDirectories(projectPath);

        Files.writeString(projectPath.resolve("util.py"), "import distutils\nimport distutils.util\n");

        var version = new EnvironmentPython(projectPath).getPythonVersion();
        assertVersionCappedAt311(version);
    }

    @Test
    void testGitignoreNegationAllowsDetection() throws Exception {
        Path repoPath = tempDir.resolve("negation-test");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            Files.writeString(repoPath.resolve(".gitignore"), "generated/**\n!generated/important.py\n");
            git.add().addFilepattern(".gitignore").call();

            Path generatedDir = repoPath.resolve("generated");
            Files.createDirectories(generatedDir);

            Files.writeString(generatedDir.resolve("ignored.py"), "from distutils.core import setup");
            Files.writeString(generatedDir.resolve("important.py"), "from distutils.core import setup");

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
        Files.writeString(srcDir.resolve("setup_helper.py"), "from distutils.core import setup\n");

        var version = new EnvironmentPython(projectPath).getPythonVersion();
        assertVersionCappedAt311(version);
    }

    // ===== Project Exclusion Pattern Tests =====

    @Test
    void testDistutilsUnderExcludedDirectoryDoesNotCapVersion() throws Exception {
        Path projectPath = tempDir.resolve("excluded-dir");
        Files.createDirectories(projectPath);

        Path excludedDir = projectPath.resolve("legacy_scripts");
        Files.createDirectories(excludedDir);
        Files.writeString(excludedDir.resolve("old_setup.py"), "from distutils.core import setup\n");

        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("main.py"), "print('hello')\n");

        var matcher = FileFilteringService.createPatternMatcher(Set.of("legacy_scripts"));
        var version = new EnvironmentPython(projectPath, matcher).getPythonVersion();
        assertVersionNotCappedAt311(version);
    }

    @Test
    void testDistutilsMatchedByExcludedFilePatternDoesNotCapVersion() throws Exception {
        Path projectPath = tempDir.resolve("excluded-file-pattern");
        Files.createDirectories(projectPath);

        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("legacy_setup.py"), "from distutils.core import setup\n");
        Files.writeString(srcDir.resolve("main.py"), "print('hello')\n");

        var matcher = FileFilteringService.createPatternMatcher(Set.of("*_setup.py"));
        var version = new EnvironmentPython(projectPath, matcher).getPythonVersion();
        assertVersionNotCappedAt311(version);
    }

    @Test
    void testNonExcludedDistutilsStillCapsVersion() throws Exception {
        Path projectPath = tempDir.resolve("non-excluded-distutils");
        Files.createDirectories(projectPath);

        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("setup_helper.py"), "from distutils.core import setup\n");

        Path excludedDir = projectPath.resolve("vendor");
        Files.createDirectories(excludedDir);
        Files.writeString(excludedDir.resolve("other.py"), "print('vendor')\n");

        var matcher = FileFilteringService.createPatternMatcher(Set.of("vendor"));
        var version = new EnvironmentPython(projectPath, matcher).getPythonVersion();
        assertVersionCappedAt311(version);
    }

    @Test
    void testProjectExclusionWithNoPatternsBehavesNormally() throws Exception {
        Path projectPath = tempDir.resolve("empty-exclusions");
        Files.createDirectories(projectPath);

        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("setup_helper.py"), "from distutils.core import setup\n");

        var matcher = FileFilteringService.createPatternMatcher(Set.of());
        var version = new EnvironmentPython(projectPath, matcher).getPythonVersion();
        assertVersionCappedAt311(version);
    }

    @Test
    void testProjectExclusionCombinedWithGitignore() throws Exception {
        Path repoPath = tempDir.resolve("combined-exclusion");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            Files.writeString(repoPath.resolve(".gitignore"), "build/\n");
            git.add().addFilepattern(".gitignore").call();

            Path buildDir = repoPath.resolve("build");
            Files.createDirectories(buildDir);
            Files.writeString(buildDir.resolve("generated.py"), "from distutils.core import setup\n");

            Path vendorDir = repoPath.resolve("vendor");
            Files.createDirectories(vendorDir);
            Files.writeString(vendorDir.resolve("legacy.py"), "from distutils.core import setup\n");

            Path srcDir = repoPath.resolve("src");
            Files.createDirectories(srcDir);
            Files.writeString(srcDir.resolve("main.py"), "print('hello')\n");
            git.add().addFilepattern("src/main.py").call();

            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        var matcher = FileFilteringService.createPatternMatcher(Set.of("vendor"));
        var version = new EnvironmentPython(repoPath, matcher).getPythonVersion();
        assertVersionNotCappedAt311(version);
    }
}
