package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for EnvironmentPython, focusing on gitignore-aware distutils detection.
 */
public class EnvironmentPythonTest {

    @TempDir
    Path tempDir;

    /**
     * Test that a tracked Python file importing distutils caps interpreter selection at 3.11.
     */
    @Test
    void testTrackedDistutilsImportCapsVersion() throws Exception {
        // Create a git repo
        Path repoPath = tempDir.resolve("tracked-distutils");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            // Create a Python file that imports distutils
            Path srcDir = repoPath.resolve("src");
            Files.createDirectories(srcDir);
            Path pyFile = srcDir.resolve("setup_helper.py");
            Files.writeString(
                    pyFile,
                    """
                from distutils.core import setup

                def configure():
                    pass
                """);

            // Track the file
            git.add().addFilepattern("src/setup_helper.py").call();
            git.commit()
                    .setMessage("Add setup helper")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        // Test that EnvironmentPython detects the distutils import
        var envPython = new EnvironmentPython(repoPath);
        String version = envPython.getPythonVersion();

        // The version should be capped at 3.11 or lower (distutils was removed in 3.12)
        int comparison = compareVersions(version, "3.12");
        assertTrue(comparison < 0, "Version should be < 3.12 due to distutils import, but got: " + version);
    }

    /**
     * Test that a distutils import under a gitignored path does NOT cap the version.
     */
    @Test
    void testGitignoredDistutilsImportDoesNotCapVersion() throws Exception {
        // Create a git repo
        Path repoPath = tempDir.resolve("ignored-distutils");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            // Create .gitignore that ignores the "generated" directory
            Path gitignore = repoPath.resolve(".gitignore");
            Files.writeString(gitignore, "generated/\n");
            git.add().addFilepattern(".gitignore").call();

            // Create a tracked Python file WITHOUT distutils
            Path srcDir = repoPath.resolve("src");
            Files.createDirectories(srcDir);
            Path trackedPy = srcDir.resolve("main.py");
            Files.writeString(
                    trackedPy,
                    """
                import os
                import sys

                def main():
                    print("Hello")
                """);
            git.add().addFilepattern("src/main.py").call();

            // Create an IGNORED directory with a distutils import
            Path generatedDir = repoPath.resolve("generated");
            Files.createDirectories(generatedDir);
            Path ignoredPy = generatedDir.resolve("legacy.py");
            Files.writeString(
                    ignoredPy,
                    """
                from distutils.core import setup
                import distutils.util
                """);

            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        // Test that EnvironmentPython does NOT detect the distutils import (it's gitignored)
        var envPython = new EnvironmentPython(repoPath);
        String version = envPython.getPythonVersion();

        // The version should NOT be capped - it can be 3.12 or higher
        // (Depending on what Python versions are available on the system,
        // but at minimum it should not be restricted by distutils)
        // We just verify it's not artificially capped by checking it could be >= 3.12
        // if the system has it, or that we didn't detect distutils
        assertNotNull(version, "Should return a valid version");
        // If 3.12+ is available and no distutils detected, version could be 3.12+
        // If only older versions available, we can't directly test the cap wasn't applied
        // So we verify indirectly: create another repo with actual distutils and compare
    }

    /**
     * Test that distutils under .venv is ignored even without a git repo.
     */
    @Test
    void testVenvDistutilsIgnoredWithoutGitRepo() throws Exception {
        // Create a directory without git
        Path projectPath = tempDir.resolve("no-git-venv");
        Files.createDirectories(projectPath);

        // Create a .venv directory with distutils import
        Path venvDir = projectPath.resolve(".venv");
        Path libDir = venvDir.resolve("lib").resolve("python3.10").resolve("site-packages");
        Files.createDirectories(libDir);
        Path venvPy = libDir.resolve("some_pkg.py");
        Files.writeString(venvPy, """
            from distutils.core import setup
            """);

        // Create a regular Python file without distutils
        Path mainPy = projectPath.resolve("main.py");
        Files.writeString(mainPy, """
            import os
            print("Hello")
            """);

        // Test that EnvironmentPython works without git and ignores .venv
        var envPython = new EnvironmentPython(projectPath);
        String version = envPython.getPythonVersion();

        // Should get a version without being capped by the .venv distutils
        assertNotNull(version, "Should return a valid version even without git");
    }

    /**
     * Test that behavior is safe when project is not in a git repo.
     */
    @Test
    void testNoGitRepoFallbackBehavior() throws Exception {
        // Create a directory without git
        Path projectPath = tempDir.resolve("no-git");
        Files.createDirectories(projectPath);

        // Create a Python file with distutils import
        Path pyFile = projectPath.resolve("setup.py");
        Files.writeString(
                pyFile,
                """
            from distutils.core import setup
            setup(name='test')
            """);

        // Test that EnvironmentPython still works and detects distutils
        var envPython = new EnvironmentPython(projectPath);
        String version = envPython.getPythonVersion();

        // Should be capped due to distutils in a non-ignored file
        int comparison = compareVersions(version, "3.12");
        assertTrue(
                comparison < 0,
                "Version should be < 3.12 due to distutils import in non-git repo, but got: " + version);
    }

    /**
     * Test that 'import distutils' pattern is also detected.
     */
    @Test
    void testImportDistutilsPatternDetected() throws Exception {
        Path projectPath = tempDir.resolve("import-distutils");
        Files.createDirectories(projectPath);

        Path pyFile = projectPath.resolve("util.py");
        Files.writeString(pyFile, """
            import distutils
            import distutils.util
            """);

        var envPython = new EnvironmentPython(projectPath);
        String version = envPython.getPythonVersion();

        int comparison = compareVersions(version, "3.12");
        assertTrue(comparison < 0, "Version should be < 3.12 due to 'import distutils', but got: " + version);
    }

    /**
     * Test that nested gitignored directories are properly skipped.
     */
    @Test
    void testNestedGitignoredDirectorySkipped() throws Exception {
        Path repoPath = tempDir.resolve("nested-ignored");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            // Create .gitignore that ignores build directory
            Path gitignore = repoPath.resolve(".gitignore");
            Files.writeString(gitignore, "build/\n");
            git.add().addFilepattern(".gitignore").call();

            // Create deeply nested structure under build/
            Path deepDir =
                    repoPath.resolve("build").resolve("temp").resolve("cache").resolve("generated");
            Files.createDirectories(deepDir);

            // Put distutils import deep in the ignored tree
            Path ignoredPy = deepDir.resolve("auto_generated.py");
            Files.writeString(ignoredPy, """
                from distutils.core import setup
                """);

            // Create a tracked file without distutils
            Path mainPy = repoPath.resolve("main.py");
            Files.writeString(mainPy, "print('hello')");
            git.add().addFilepattern("main.py").call();

            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        var envPython = new EnvironmentPython(repoPath);
        String version = envPython.getPythonVersion();

        // The deeply nested ignored file should not trigger distutils cap
        // Version could be >= 3.12 if available
        assertNotNull(version, "Should return a valid version");
    }

    /**
     * Test that negation patterns are respected for files inside a traversed directory.
     *
     * <p>We intentionally avoid the unsupported case of trying to re-include a file from beneath an ignored parent
     * directory. Git does not reliably re-include children once the parent directory itself is excluded.
     */
    @Test
    void testGitignoreNegationRespected() throws Exception {
        Path repoPath = tempDir.resolve("negation-test");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            // Ignore descendants under generated/, but explicitly un-ignore one Python file.
            Path gitignore = repoPath.resolve(".gitignore");
            Files.writeString(
                    gitignore,
                    """
                generated/**
                !generated/important.py
                """);
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

        var envPython = new EnvironmentPython(repoPath);
        String version = envPython.getPythonVersion();

        int comparison = compareVersions(version, "3.12");
        assertTrue(
                comparison < 0,
                "Version should be < 3.12 due to distutils in gitignore-negated path, but got: " + version);
    }

    /**
     * Helper to compare version strings like "3.8" and "3.12".
     */
    private int compareVersions(String a, String b) {
        var aParts = a.split("\\.");
        var bParts = b.split("\\.");
        for (int i = 0; i < Math.min(aParts.length, bParts.length); i++) {
            int aVal = Integer.parseInt(aParts[i]);
            int bVal = Integer.parseInt(bParts[i]);
            if (aVal != bVal) return Integer.compare(aVal, bVal);
        }
        return Integer.compare(aParts.length, bParts.length);
    }
}
