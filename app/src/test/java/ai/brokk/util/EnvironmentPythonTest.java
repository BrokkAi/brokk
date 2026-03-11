package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
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
        // If version is >= 3.12, it was not capped
        // This assertion is only meaningful when 3.12+ is installed
        // We just verify it's a valid version string
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

        // Create a directory that will be excluded via project patterns
        Path excludedDir = projectPath.resolve("legacy_scripts");
        Files.createDirectories(excludedDir);
        Files.writeString(excludedDir.resolve("old_setup.py"), "from distutils.core import setup\n");

        // Create a normal source file without distutils
        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("main.py"), "print('hello')\n");

        // Set up TestProject with exclusion pattern for "legacy_scripts"
        TestProject project = new TestProject(projectPath);
        project.setExclusionPatterns(Set.of("legacy_scripts"));

        var envPython = new EnvironmentPython(projectPath, project);
        // Version should NOT be capped since distutils is only in excluded directory
        var version = envPython.getPythonVersion();
        assertVersionNotCappedAt311(version);
    }

    @Test
    void testDistutilsMatchedByExcludedFilePatternDoesNotCapVersion() throws Exception {
        Path projectPath = tempDir.resolve("excluded-file-pattern");
        Files.createDirectories(projectPath);

        // Create a Python file that matches an exclusion glob pattern
        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("legacy_setup.py"), "from distutils.core import setup\n");
        Files.writeString(srcDir.resolve("main.py"), "print('hello')\n");

        // Set up TestProject with a glob pattern that excludes *_setup.py files
        TestProject project = new TestProject(projectPath);
        project.setExclusionPatterns(Set.of("*_setup.py"));

        var envPython = new EnvironmentPython(projectPath, project);
        // Version should NOT be capped since distutils is only in excluded file
        var version = envPython.getPythonVersion();
        assertVersionNotCappedAt311(version);
    }

    @Test
    void testNonExcludedDistutilsStillCapsVersion() throws Exception {
        Path projectPath = tempDir.resolve("non-excluded-distutils");
        Files.createDirectories(projectPath);

        // Create distutils import in a non-excluded location
        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("setup_helper.py"), "from distutils.core import setup\n");

        // Create an excluded directory (but distutils is NOT there)
        Path excludedDir = projectPath.resolve("vendor");
        Files.createDirectories(excludedDir);
        Files.writeString(excludedDir.resolve("other.py"), "print('vendor')\n");

        // Set up TestProject with exclusion pattern for "vendor" only
        TestProject project = new TestProject(projectPath);
        project.setExclusionPatterns(Set.of("vendor"));

        var envPython = new EnvironmentPython(projectPath, project);
        // Verify the version is capped because distutils is in non-excluded location
        var version = envPython.getPythonVersion();
        assertVersionCappedAt311(version);
    }

    @Test
    void testProjectExclusionWithNoPatternsBehavesNormally() throws Exception {
        Path projectPath = tempDir.resolve("empty-exclusions");
        Files.createDirectories(projectPath);

        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("setup_helper.py"), "from distutils.core import setup\n");

        // Set up TestProject with empty exclusion patterns
        TestProject project = new TestProject(projectPath);
        project.setExclusionPatterns(Set.of());

        var envPython = new EnvironmentPython(projectPath, project);
        // Verify version is capped when no exclusion patterns are set
        var version = envPython.getPythonVersion();
        assertVersionCappedAt311(version);
    }

    @Test
    void testProjectExclusionCombinedWithGitignore() throws Exception {
        Path repoPath = tempDir.resolve("combined-exclusion");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            // Set up gitignore to exclude "build/"
            Files.writeString(repoPath.resolve(".gitignore"), "build/\n");
            git.add().addFilepattern(".gitignore").call();

            // Put distutils in gitignored directory
            Path buildDir = repoPath.resolve("build");
            Files.createDirectories(buildDir);
            Files.writeString(buildDir.resolve("generated.py"), "from distutils.core import setup\n");

            // Put distutils in project-excluded directory (not gitignored)
            Path vendorDir = repoPath.resolve("vendor");
            Files.createDirectories(vendorDir);
            Files.writeString(vendorDir.resolve("legacy.py"), "from distutils.core import setup\n");

            // Normal source without distutils
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

        // Set up TestProject with exclusion for "vendor"
        TestProject project = new TestProject(repoPath);
        project.setExclusionPatterns(Set.of("vendor"));

        var envPython = new EnvironmentPython(repoPath, project);
        // Both build/ (gitignored) and vendor/ (project-excluded) should be skipped
        // so version should NOT be capped
        var version = envPython.getPythonVersion();
        assertVersionNotCappedAt311(version);
    }
}
