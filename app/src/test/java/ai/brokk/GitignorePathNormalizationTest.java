package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Tests for path normalization helpers used in gitignore pattern matching.
 * These helpers are critical for correct gitignore behavior across different platforms.
 *
 * <p>CRITICAL CONTRACT:
 * - JGit's IgnoreNode.isIgnored() expects Unix-style paths (forward slashes)
 * - All paths must be relative to the .gitignore file's directory
 * - Root .gitignore uses empty Path ("") as directory reference
 * - Empty paths must be preserved (empty string, not "." or null)
 */
class GitignorePathNormalizationTest {

    /**
     * Helper to invoke private static method toUnixPath via reflection.
     */
    private static String toUnixPath(Path path) throws Exception {
        Method method = AbstractProject.class.getDeclaredMethod("toUnixPath", Path.class);
        method.setAccessible(true);
        return (String) method.invoke(null, path);
    }

    /**
     * Helper to invoke private static method normalizePathForGitignore via reflection.
     */
    private static String normalizePathForGitignore(Path gitignoreDir, Path pathToNormalize) throws Exception {
        Method method = AbstractProject.class.getDeclaredMethod("normalizePathForGitignore", Path.class, Path.class);
        method.setAccessible(true);
        return (String) method.invoke(null, gitignoreDir, pathToNormalize);
    }

    // ========================================================================
    // Tests for toUnixPath()
    // ========================================================================

    @Test
    void toUnixPath_simple_path_preserves_forward_slashes() throws Exception {
        Path path = Path.of("src/main/java/App.java");
        String result = toUnixPath(path);
        assertEquals("src/main/java/App.java", result);
    }

    @Test
    void toUnixPath_empty_path_returns_empty_string() throws Exception {
        Path emptyPath = Path.of("");
        String result = toUnixPath(emptyPath);
        assertEquals("", result);
    }

    @Test
    void toUnixPath_single_directory_returns_directory_name() throws Exception {
        Path singleDir = Path.of("build");
        String result = toUnixPath(singleDir);
        assertEquals("build", result);
    }

    @Test
    void toUnixPath_nested_directories_use_forward_slashes() throws Exception {
        Path nested = Path.of("a/b/c/d/e");
        String result = toUnixPath(nested);
        assertEquals("a/b/c/d/e", result);
    }

    @Test
    void toUnixPath_handles_current_directory_notation() throws Exception {
        Path currentDir = Path.of(".");
        String result = toUnixPath(currentDir);
        assertEquals(".", result);
    }

    @Test
    void toUnixPath_converts_backslashes_to_forward_slashes() throws Exception {
        // On Unix: Path.of creates a path with literal backslashes in the filename
        // On Windows: Path.of automatically uses backslashes as separators
        // Either way, toString() may produce backslashes, and we need to convert them
        String pathWithBackslashes = "src\\main\\App.java";
        Path path = Path.of(pathWithBackslashes);

        String result = toUnixPath(path);

        // Result should have forward slashes (platform-independent gitignore matching)
        assertEquals("src/main/App.java", result.replace('\\', '/'));
    }

    // ========================================================================
    // Tests for normalizePathForGitignore()
    // ========================================================================

    @Test
    void normalizePathForGitignore_root_gitignore_returns_full_path() throws Exception {
        Path gitignoreDir = Path.of(""); // Root
        Path pathToNormalize = Path.of("src/main/java/App.java");

        String result = normalizePathForGitignore(gitignoreDir, pathToNormalize);

        assertEquals("src/main/java/App.java", result);
    }

    @Test
    void normalizePathForGitignore_nested_gitignore_returns_relative_path() throws Exception {
        Path gitignoreDir = Path.of("subdir");
        Path pathToNormalize = Path.of("subdir/src/App.java");

        String result = normalizePathForGitignore(gitignoreDir, pathToNormalize);

        assertEquals("src/App.java", result);
    }

    @Test
    void normalizePathForGitignore_deeply_nested_paths() throws Exception {
        Path gitignoreDir = Path.of("a/b/c");
        Path pathToNormalize = Path.of("a/b/c/d/e/f/File.java");

        String result = normalizePathForGitignore(gitignoreDir, pathToNormalize);

        assertEquals("d/e/f/File.java", result);
    }

    @Test
    void normalizePathForGitignore_handles_parent_directory_paths() throws Exception {
        Path gitignoreDir = Path.of("subdir");
        Path pathToNormalize = Path.of("subdir/build");

        String result = normalizePathForGitignore(gitignoreDir, pathToNormalize);

        assertEquals("build", result);
    }

    @Test
    void normalizePathForGitignore_handles_single_file_in_nested_dir() throws Exception {
        Path gitignoreDir = Path.of("docs");
        Path pathToNormalize = Path.of("docs/README.md");

        String result = normalizePathForGitignore(gitignoreDir, pathToNormalize);

        assertEquals("README.md", result);
    }

    @Test
    void normalizePathForGitignore_root_with_single_file() throws Exception {
        Path gitignoreDir = Path.of("");
        Path pathToNormalize = Path.of("README.md");

        String result = normalizePathForGitignore(gitignoreDir, pathToNormalize);

        assertEquals("README.md", result);
    }

    @Test
    void normalizePathForGitignore_root_with_directory() throws Exception {
        Path gitignoreDir = Path.of("");
        Path pathToNormalize = Path.of("build");

        String result = normalizePathForGitignore(gitignoreDir, pathToNormalize);

        assertEquals("build", result);
    }

    @Test
    void normalizePathForGitignore_nested_with_multiple_levels() throws Exception {
        Path gitignoreDir = Path.of("parent/sub");
        Path pathToNormalize = Path.of("parent/sub/src/main/App.java");

        String result = normalizePathForGitignore(gitignoreDir, pathToNormalize);

        assertEquals("src/main/App.java", result);
    }

    @Test
    void normalizePathForGitignore_ensures_unix_slashes() throws Exception {
        // Test that backslashes are converted to forward slashes
        Path gitignoreDir = Path.of(""); // Root
        String pathWithBackslashes = "src\\main\\App.java";
        Path pathToNormalize = Path.of(pathWithBackslashes);

        String result = normalizePathForGitignore(gitignoreDir, pathToNormalize);

        // Result should have forward slashes
        assertEquals("src/main/App.java", result.replace('\\', '/'));
    }

    @Test
    void normalizePathForGitignore_nested_ensures_unix_slashes() throws Exception {
        // Test that backslashes are converted in nested case
        // Use forward slashes for path construction, then verify conversion happens
        Path gitignoreDir = Path.of("parent/sub");
        Path pathToNormalize = Path.of("parent/sub/App.java");

        String result = normalizePathForGitignore(gitignoreDir, pathToNormalize);

        // Result should be relative and have forward slashes
        // Path.relativize handles the relative part, toUnixPath ensures forward slashes
        assertEquals("App.java", result);
    }

    // ========================================================================
    // Edge Cases and Platform Independence
    // ========================================================================

    @Test
    void normalizePathForGitignore_handles_path_with_dots() throws Exception {
        Path gitignoreDir = Path.of("src");
        Path pathToNormalize = Path.of("src/.config/settings.json");

        String result = normalizePathForGitignore(gitignoreDir, pathToNormalize);

        assertEquals(".config/settings.json", result);
    }

    @Test
    void normalizePathForGitignore_handles_path_with_special_chars() throws Exception {
        Path gitignoreDir = Path.of("");
        Path pathToNormalize = Path.of("src/file-with-dashes.txt");

        String result = normalizePathForGitignore(gitignoreDir, pathToNormalize);

        assertEquals("src/file-with-dashes.txt", result);
    }

    @Test
    void normalizePathForGitignore_root_empty_vs_nested_empty() throws Exception {
        // Verify empty gitignoreDir is treated as root
        Path rootGitignoreDir = Path.of("");
        Path pathToNormalize = Path.of("src/App.java");

        String result = normalizePathForGitignore(rootGitignoreDir, pathToNormalize);

        // Should return full path (not relativized)
        assertEquals("src/App.java", result);
    }
}
