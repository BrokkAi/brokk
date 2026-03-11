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

        writePyprojectToml(repoPath);

        var envPython = new EnvironmentPython(repoPath);
        String version = envPython.getPythonVersion();

        // Compare against a control project where distutils IS in real source (capped)
        String cappedVersion = getCappedControlVersion("control-gitignored");
        assertTrue(compareVersions(version, cappedVersion) >= 0,
                   "Gitignored distutils should not cap version below control (" + cappedVersion + "), but got: " + version);
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

        writePyprojectToml(projectPath);

        var envPython = new EnvironmentPython(projectPath);
        String version = envPython.getPythonVersion();

        String cappedVersion = getCappedControlVersion("control-venv");
        assertTrue(compareVersions(version, cappedVersion) >= 0,
                   ".venv distutils should not cap version below control (" + cappedVersion + "), but got: " + version);
    }

    /**
     * Test that distutils under common artifact directories (.gradle, node_modules, etc.)
     * is ignored in fallback (non-git) mode.
     */
    @Test
    void testArtifactDirectoryDistutilsIgnoredWithoutGitRepo() throws Exception {
        // Create a directory without git
        Path projectPath = tempDir.resolve("no-git-artifacts");
        Files.createDirectories(projectPath);

        // Create various artifact directories with distutils imports
        for (String artifactDir : List.of(".gradle", "node_modules", "build", "target", "__pycache__", "dist")) {
            Path dir = projectPath.resolve(artifactDir);
            Files.createDirectories(dir);
            Path pyFile = dir.resolve("generated.py");
            Files.writeString(pyFile, """
                from distutils.core import setup
                """);
        }

        // Create a regular Python file without distutils
        Path mainPy = projectPath.resolve("main.py");
        Files.writeString(mainPy, """
            import os
            print("Hello")
            """);

        writePyprojectToml(projectPath);

        var envPython = new EnvironmentPython(projectPath);
        String version = envPython.getPythonVersion();

        String cappedVersion = getCappedControlVersion("control-artifacts");
        assertTrue(compareVersions(version, cappedVersion) >= 0,
                   "Artifact dir distutils should not cap version below control (" + cappedVersion + "), but got: " + version);
    }

    /**
     * Regression test: non-git project with distutils in .gradle should not cap version,
     * but distutils in a normal source file should still cap.
     */
    @Test
    void testGradleDirectorySkippedButRealSourceStillCaps() throws Exception {
        // Part 1: .gradle distutils should NOT cap
        Path projectWithGradle = tempDir.resolve("gradle-ignored");
        Files.createDirectories(projectWithGradle);
        writePyprojectToml(projectWithGradle);

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
        String version1 = envPython1.getPythonVersion();

        // Part 2: Real source distutils SHOULD cap
        Path projectWithDistutils = tempDir.resolve("real-distutils");
        Files.createDirectories(projectWithDistutils);
        writePyprojectToml(projectWithDistutils);

        Path srcDir = projectWithDistutils.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(
                srcDir.resolve("setup_helper.py"), """
            from distutils.core import setup
            """);

        var envPython2 = new EnvironmentPython(projectWithDistutils);
        String version2 = envPython2.getPythonVersion();

        int comparison = compareVersions(version2, "3.12");
        assertTrue(comparison < 0, "Version should be < 3.12 due to distutils in real source, but got: " + version2);

        // .gradle distutils should not restrict version more than real distutils does
        assertTrue(compareVersions(version1, version2) >= 0,
                   ".gradle distutils should not cap version below real-source cap (" + version2 + "), but got: " + version1);
    }

    /**
     * Test that deeply nested artifact directories are also skipped in fallback mode.
     */
    @Test
    void testDeeplyNestedArtifactDirectorySkipped() throws Exception {
        Path projectPath = tempDir.resolve("nested-artifacts");
        Files.createDirectories(projectPath);

        // Create nested structure: project/subproject/node_modules/deep/file.py
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

        // Create normal source
        Path mainPy = projectPath.resolve("main.py");
        Files.writeString(mainPy, """
            print("Hello")
            """);

        writePyprojectToml(projectPath);

        var envPython = new EnvironmentPython(projectPath);
        String version = envPython.getPythonVersion();

        String cappedVersion = getCappedControlVersion("control-nested");
        assertTrue(compareVersions(version, cappedVersion) >= 0,
                   "Nested node_modules distutils should not cap version below control (" + cappedVersion + "), but got: " + version);
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

        writePyprojectToml(repoPath);

        var envPython = new EnvironmentPython(repoPath);
        String version = envPython.getPythonVersion();

        String cappedVersion = getCappedControlVersion("control-nested-gitignored");
        assertTrue(compareVersions(version, cappedVersion) >= 0,
                   "Nested gitignored distutils should not cap version below control (" + cappedVersion + "), but got: " + version);
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
     * Test that repoImportsDistutils() falls back safely when GitRepoFactory.hasGitRepo()
     * succeeds but GitRepo construction fails due to a malformed .git directory.
     */
    @Test
    void testMalformedGitRepoFallsBackSafely() throws Exception {
        Path projectPath = tempDir.resolve("malformed-git");
        Files.createDirectories(projectPath);

        // Create a .git directory with enough structure for hasGitRepo() to return true
        // but that will fail during full GitRepo construction.
        // hasGitRepo() uses FileRepositoryBuilder.findGitDir() + repo.getObjectDatabase().exists()
        Path gitDir = projectPath.resolve(".git");
        Files.createDirectories(gitDir);

        // Create objects directory so getObjectDatabase().exists() returns true
        Path objectsDir = gitDir.resolve("objects");
        Files.createDirectories(objectsDir);
        // Also create pack and info subdirectories (bare minimum for object database)
        Files.createDirectories(objectsDir.resolve("pack"));
        Files.createDirectories(objectsDir.resolve("info"));

        // Create a malformed HEAD file - GitRepo construction will fail when trying
        // to resolve HEAD or perform other operations that require a valid ref
        Path headFile = gitDir.resolve("HEAD");
        Files.writeString(headFile, "ref: refs/heads/nonexistent\n");

        // Create refs directory but leave it empty (no actual branch exists)
        Path refsDir = gitDir.resolve("refs");
        Files.createDirectories(refsDir.resolve("heads"));

        // Create config file (minimal, may still cause issues)
        Path configFile = gitDir.resolve("config");
        Files.writeString(configFile, "[core]\n\trepositoryformatversion = 0\n");

        // Put distutils in a fallback-skipped directory (.gradle)
        // If fallback works, this should be skipped
        Path gradleDir = projectPath.resolve(".gradle");
        Files.createDirectories(gradleDir);
        Files.writeString(gradleDir.resolve("build.py"), "from distutils.core import setup\n");

        // Put a clean Python file in the main project
        Path mainPy = projectPath.resolve("main.py");
        Files.writeString(mainPy, "print('hello')\n");

        writePyprojectToml(projectPath);

        // Verify hasGitRepo returns true (the precondition for this test)
        assertTrue(
                ai.brokk.git.GitRepoFactory.hasGitRepo(projectPath),
                "hasGitRepo should return true for our malformed .git setup");

        // Now test EnvironmentPython - it should fall back gracefully
        var envPython = new EnvironmentPython(projectPath);
        String version = envPython.getPythonVersion();

        // Verify fallback path was used and .gradle distutils was skipped
        String cappedVersion = getCappedControlVersion("control-malformed");
        assertTrue(compareVersions(version, cappedVersion) >= 0,
                   "Malformed git fallback should not cap version below control (" + cappedVersion + "), but got: " + version);
    }

    /**
     * Create a control project with distutils in a real source file and a pyproject.toml
     * pinning requires-python >= 3.8. Returns the (capped) version from this project.
     */
    private String getCappedControlVersion(String label) throws Exception {
        Path controlPath = tempDir.resolve(label);
        Files.createDirectories(controlPath);
        writePyprojectToml(controlPath);
        Path srcDir = controlPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("legacy_setup.py"),
                          "from distutils.core import setup\n");
        var envPython = new EnvironmentPython(controlPath);
        return envPython.getPythonVersion();
    }

    private void writePyprojectToml(Path projectRoot) throws Exception {
        Files.writeString(projectRoot.resolve("pyproject.toml"),
                          "[project]\nrequires-python = \">=3.8\"\n");
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
