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

    /** The highest version in EnvironmentPython's candidate list when NOT capped. */
    private static final String UNCAPPED_VERSION = "3.13";
    /** The highest version in EnvironmentPython's candidate list when capped at 3.11. */
    private static final String CAPPED_VERSION = "3.11";

    /** Predicate that treats all Python versions as available, for deterministic testing. */
    private static boolean allVersionsAvailable(String version) {
        return true;
    }

    private EnvironmentPython createTestEnvPython(Path projectRoot) {
        return new EnvironmentPython(projectRoot, null, EnvironmentPythonTest::allVersionsAvailable);
    }

    private EnvironmentPython createTestEnvPython(Path projectRoot, FileFilteringService.FilePatternMatcher matcher) {
        return new EnvironmentPython(projectRoot, matcher, EnvironmentPythonTest::allVersionsAvailable);
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

        var version = createTestEnvPython(repoPath).getPythonVersion();
        assertEquals(CAPPED_VERSION, version, "Tracked distutils import should cap version at 3.11");
    }

    @Test
    void testNoGitRepoFallbackDetectsDistutils() throws Exception {
        Path projectPath = tempDir.resolve("no-git");
        Files.createDirectories(projectPath);

        Files.writeString(projectPath.resolve("setup.py"), "from distutils.core import setup\nsetup(name='test')\n");

        var version = createTestEnvPython(projectPath).getPythonVersion();
        assertEquals(CAPPED_VERSION, version, "Non-git distutils import should cap version at 3.11");
    }

    @Test
    void testImportDistutilsPatternCapsVersion() throws Exception {
        Path projectPath = tempDir.resolve("import-distutils");
        Files.createDirectories(projectPath);

        Files.writeString(projectPath.resolve("util.py"), "import distutils\nimport distutils.util\n");

        var version = createTestEnvPython(projectPath).getPythonVersion();
        assertEquals(CAPPED_VERSION, version, "'import distutils' pattern should cap version at 3.11");
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

        var version = createTestEnvPython(repoPath).getPythonVersion();
        // important.py is un-ignored via negation, so distutils is detected
        assertEquals(CAPPED_VERSION, version, "Gitignore negation should allow distutils detection");
    }

    @Test
    void testRealSourceDistutilsCapsVersion() throws Exception {
        Path projectPath = tempDir.resolve("real-distutils");
        Files.createDirectories(projectPath);

        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("setup_helper.py"), "from distutils.core import setup\n");

        var version = createTestEnvPython(projectPath).getPythonVersion();
        assertEquals(CAPPED_VERSION, version, "Real source distutils import should cap version at 3.11");
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
        var version = createTestEnvPython(projectPath, matcher).getPythonVersion();
        assertEquals(UNCAPPED_VERSION, version, "Distutils in excluded directory should not cap version");
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
        var version = createTestEnvPython(projectPath, matcher).getPythonVersion();
        assertEquals(UNCAPPED_VERSION, version, "Distutils in excluded file pattern should not cap version");
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
        var version = createTestEnvPython(projectPath, matcher).getPythonVersion();
        assertEquals(CAPPED_VERSION, version, "Non-excluded distutils should still cap version");
    }

    @Test
    void testProjectExclusionWithNoPatternsBehavesNormally() throws Exception {
        Path projectPath = tempDir.resolve("empty-exclusions");
        Files.createDirectories(projectPath);

        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("setup_helper.py"), "from distutils.core import setup\n");

        var matcher = FileFilteringService.createPatternMatcher(Set.of());
        var version = createTestEnvPython(projectPath, matcher).getPythonVersion();
        assertEquals(CAPPED_VERSION, version, "Empty exclusion patterns should not change capping behavior");
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
        var version = createTestEnvPython(repoPath, matcher).getPythonVersion();
        assertEquals(UNCAPPED_VERSION, version, "Gitignore + project exclusion should skip all distutils");
    }

    // ===== Venv directory pruning tests (should be skipped regardless of git status) =====

    @Test
    void testTrackedVenvDirectoryDoesNotCapVersion() throws Exception {
        // Regression test: .venv/venv directories should be skipped even when tracked in git
        // This catches the bug where FALLBACK_SKIP_DIRECTORIES is only checked in the else branch
        Path repoPath = tempDir.resolve("tracked-venv");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            // Create a tracked .venv directory with distutils import
            Path venvDir = repoPath.resolve(".venv");
            Files.createDirectories(venvDir);
            Files.writeString(venvDir.resolve("setup.py"), "from distutils.core import setup\n");

            // Also create normal source without distutils
            Path srcDir = repoPath.resolve("src");
            Files.createDirectories(srcDir);
            Files.writeString(srcDir.resolve("main.py"), "print('hello')\n");

            // Track both directories (NOT gitignored)
            git.add().addFilepattern(".venv/setup.py").call();
            git.add().addFilepattern("src/main.py").call();
            git.commit()
                    .setMessage("Initial commit with tracked .venv")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        var version = createTestEnvPython(repoPath).getPythonVersion();
        assertEquals(UNCAPPED_VERSION, version, "Tracked .venv directory should still be skipped (not cap version)");
    }

    @Test
    void testTrackedVenvDirectoryWithoutDotDoesNotCapVersion() throws Exception {
        // Same as above but for "venv" (without leading dot)
        Path repoPath = tempDir.resolve("tracked-venv-no-dot");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            Path venvDir = repoPath.resolve("venv");
            Files.createDirectories(venvDir);
            Files.writeString(venvDir.resolve("old_distutils.py"), "import distutils\n");

            Path srcDir = repoPath.resolve("src");
            Files.createDirectories(srcDir);
            Files.writeString(srcDir.resolve("app.py"), "print('app')\n");

            git.add().addFilepattern("venv/old_distutils.py").call();
            git.add().addFilepattern("src/app.py").call();
            git.commit()
                    .setMessage("Initial commit with tracked venv")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        var version = createTestEnvPython(repoPath).getPythonVersion();
        assertEquals(UNCAPPED_VERSION, version, "Tracked venv directory should still be skipped (not cap version)");
    }

    // ===== Gitignored path tests =====

    @Test
    void testGitignoredDistutilsDoesNotCapVersion() throws Exception {
        Path repoPath = tempDir.resolve("gitignored-distutils");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            Files.writeString(repoPath.resolve(".gitignore"), "generated/\n");
            git.add().addFilepattern(".gitignore").call();

            Path generatedDir = repoPath.resolve("generated");
            Files.createDirectories(generatedDir);
            Files.writeString(generatedDir.resolve("setup.py"), "from distutils.core import setup\n");

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

        var version = createTestEnvPython(repoPath).getPythonVersion();
        assertEquals(UNCAPPED_VERSION, version, "Distutils in gitignored directory should not cap version");
    }

    // ===== Fallback pruning tests =====

    @Test
    void testFallbackPrunedDirectoryDoesNotCapVersion() throws Exception {
        // Non-git project with distutils only in a fallback-pruned directory
        Path projectPath = tempDir.resolve("fallback-pruned");
        Files.createDirectories(projectPath);

        // .gradle is in FALLBACK_SKIP_DIRECTORIES
        Path gradleDir = projectPath.resolve(".gradle");
        Files.createDirectories(gradleDir);
        Files.writeString(gradleDir.resolve("setup.py"), "from distutils.core import setup\n");

        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("main.py"), "print('hello')\n");

        var version = createTestEnvPython(projectPath).getPythonVersion();
        assertEquals(UNCAPPED_VERSION, version, "Distutils in fallback-pruned directory should not cap version");
    }

    @Test
    void testFallbackPrunedNodeModulesDoesNotCapVersion() throws Exception {
        Path projectPath = tempDir.resolve("fallback-node-modules");
        Files.createDirectories(projectPath);

        Path nodeModulesDir = projectPath.resolve("node_modules");
        Files.createDirectories(nodeModulesDir);
        Files.writeString(nodeModulesDir.resolve("some_tool.py"), "import distutils\n");

        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("app.py"), "print('app')\n");

        var version = createTestEnvPython(projectPath).getPythonVersion();
        assertEquals(UNCAPPED_VERSION, version, "Distutils in node_modules should not cap version");
    }

    @Test
    void testNoDistutilsReturnsUncappedVersion() throws Exception {
        Path projectPath = tempDir.resolve("no-distutils");
        Files.createDirectories(projectPath);

        Path srcDir = projectPath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("main.py"), "print('hello')\n");
        Files.writeString(srcDir.resolve("utils.py"), "import os\nimport sys\n");

        var version = createTestEnvPython(projectPath).getPythonVersion();
        assertEquals(UNCAPPED_VERSION, version, "Project without distutils should not be capped");
    }
}
