package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
     * Test that a tracked Python file importing distutils is detected.
     */
    @Test
    void testTrackedDistutilsImportCapsVersion() throws Exception {
        Path repoPath = tempDir.resolve("tracked-distutils");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
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

            git.add().addFilepattern("src/setup_helper.py").call();
            git.commit()
                    .setMessage("Add setup helper")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        var envPython = new EnvironmentPython(repoPath);
        assertTrue(envPython.repoImportsDistutils(), "Should detect distutils import in tracked file");
    }

    /**
     * Test that a distutils import under a gitignored path is NOT detected.
     */
    @Test
    void testGitignoredDistutilsImportDoesNotCapVersion() throws Exception {
        Path repoPath = tempDir.resolve("ignored-distutils");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            Path gitignore = repoPath.resolve(".gitignore");
            Files.writeString(gitignore, "generated/\n");
            git.add().addFilepattern(".gitignore").call();

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

        var envPython = new EnvironmentPython(repoPath);
        assertFalse(envPython.repoImportsDistutils(), "Should not detect distutils in gitignored directory");
    }

    /**
     * Test that distutils under .venv is ignored even without a git repo.
     */
    @Test
    void testVenvDistutilsIgnoredWithoutGitRepo() throws Exception {
        Path projectPath = tempDir.resolve("no-git-venv");
        Files.createDirectories(projectPath);

        Path venvDir = projectPath.resolve(".venv");
        Path libDir = venvDir.resolve("lib").resolve("python3.10").resolve("site-packages");
        Files.createDirectories(libDir);
        Path venvPy = libDir.resolve("some_pkg.py");
        Files.writeString(venvPy, """
            from distutils.core import setup
            """);

        Path mainPy = projectPath.resolve("main.py");
        Files.writeString(mainPy, """
            import os
            print("Hello")
            """);

        var envPython = new EnvironmentPython(projectPath);
        assertFalse(envPython.repoImportsDistutils(), "Should not detect distutils under .venv");
    }

    /**
     * Test that distutils under common artifact directories (.gradle, node_modules, etc.)
     * is ignored in fallback (non-git) mode.
     */
    @Test
    void testArtifactDirectoryDistutilsIgnoredWithoutGitRepo() throws Exception {
        Path projectPath = tempDir.resolve("no-git-artifacts");
        Files.createDirectories(projectPath);

        for (String artifactDir : List.of(".gradle", "node_modules", "build", "target", "__pycache__", "dist")) {
            Path dir = projectPath.resolve(artifactDir);
            Files.createDirectories(dir);
            Path pyFile = dir.resolve("generated.py");
            Files.writeString(pyFile, """
                from distutils.core import setup
                """);
        }

        Path mainPy = projectPath.resolve("main.py");
        Files.writeString(mainPy, """
            import os
            print("Hello")
            """);

        var envPython = new EnvironmentPython(projectPath);
        assertFalse(envPython.repoImportsDistutils(), "Should not detect distutils under artifact directories");
    }

    /**
     * Regression test: non-git project with distutils in .gradle should not be detected,
     * but distutils in a normal source file should still be detected.
     */
    @Test
    void testGradleDirectorySkippedButRealSourceStillCaps() throws Exception {
        // Part 1: .gradle distutils should NOT be detected
        Path projectWithGradle = tempDir.resolve("gradle-ignored");
        Files.createDirectories(projectWithGradle);

        Path gradleDir = projectWithGradle.resolve(".gradle");
        Files.createDirectories(gradleDir);
        Files.writeString(
                gradleDir.resolve("wrapper.py"), """
            from distutils.core import setup
            """);

        Path mainPy1 = projectWithGradle.resolve("main.py");
        Files.writeString(mainPy1, """
            import os
            """);

        var envPython1 = new EnvironmentPython(projectWithGradle);
        assertFalse(envPython1.repoImportsDistutils(), "Should not detect distutils under .gradle");

        // Part 2: Real source distutils SHOULD be detected
        Path projectWithDistutils = tempDir.resolve("real-distutils");
        Files.createDirectories(projectWithDistutils);

        Path srcDir = projectWithDistutils.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(
                srcDir.resolve("setup_helper.py"), """
            from distutils.core import setup
            """);

        var envPython2 = new EnvironmentPython(projectWithDistutils);
        assertTrue(envPython2.repoImportsDistutils(), "Should detect distutils in real source directory");
    }

    /**
     * Test that deeply nested artifact directories are also skipped in fallback mode.
     */
    @Test
    void testDeeplyNestedArtifactDirectorySkipped() throws Exception {
        Path projectPath = tempDir.resolve("nested-artifacts");
        Files.createDirectories(projectPath);

        Path nestedDir = projectPath
                .resolve("subproject")
                .resolve("node_modules")
                .resolve("some-pkg")
                .resolve("scripts");
        Files.createDirectories(nestedDir);
        Files.writeString(
                nestedDir.resolve("install.py"), """
            from distutils.core import setup
            """);

        Path mainPy = projectPath.resolve("main.py");
        Files.writeString(mainPy, """
            print("Hello")
            """);

        var envPython = new EnvironmentPython(projectPath);
        assertFalse(envPython.repoImportsDistutils(), "Should not detect distutils under nested node_modules");
    }

    /**
     * Test that distutils is detected when project is not in a git repo.
     */
    @Test
    void testNoGitRepoFallbackBehavior() throws Exception {
        Path projectPath = tempDir.resolve("no-git");
        Files.createDirectories(projectPath);

        Path pyFile = projectPath.resolve("setup.py");
        Files.writeString(
                pyFile,
                """
            from distutils.core import setup
            setup(name='test')
            """);

        var envPython = new EnvironmentPython(projectPath);
        assertTrue(envPython.repoImportsDistutils(), "Should detect distutils in non-ignored file without git repo");
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
        assertTrue(envPython.repoImportsDistutils(), "Should detect 'import distutils' pattern");
    }

    /**
     * Test that nested gitignored directories are properly skipped.
     */
    @Test
    void testNestedGitignoredDirectorySkipped() throws Exception {
        Path repoPath = tempDir.resolve("nested-ignored");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            Path gitignore = repoPath.resolve(".gitignore");
            Files.writeString(gitignore, "build/\n");
            git.add().addFilepattern(".gitignore").call();

            Path deepDir =
                    repoPath.resolve("build").resolve("temp").resolve("cache").resolve("generated");
            Files.createDirectories(deepDir);

            Path ignoredPy = deepDir.resolve("auto_generated.py");
            Files.writeString(ignoredPy, """
                from distutils.core import setup
                """);

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
        assertFalse(envPython.repoImportsDistutils(), "Should not detect distutils under gitignored build directory");
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
        assertTrue(envPython.repoImportsDistutils(), "Should detect distutils in gitignore-negated (un-ignored) file");
    }

    /**
     * Test that repoImportsDistutils() falls back safely when there is no git repo,
     * verifying that FALLBACK_SKIP_DIRECTORIES are respected.
     */
    @Test
    void testNoGitRepoFallbackSkipsArtifactDirectories() throws Exception {
        Path projectPath = tempDir.resolve("no-git-fallback");
        Files.createDirectories(projectPath);

        // Put distutils in a fallback-skipped directory (.gradle)
        Path gradleDir = projectPath.resolve(".gradle");
        Files.createDirectories(gradleDir);
        Files.writeString(gradleDir.resolve("build.py"), "from distutils.core import setup\n");

        // Put a clean Python file in the main project
        Path mainPy = projectPath.resolve("main.py");
        Files.writeString(mainPy, "print('hello')\n");

        var envPython = new EnvironmentPython(projectPath);
        assertFalse(envPython.repoImportsDistutils(), "Fallback should skip .gradle distutils");
    }
}
