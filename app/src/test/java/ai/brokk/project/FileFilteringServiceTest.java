package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitTestCleanupUtil;
import ai.brokk.util.Environment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Tests for FileFilteringService.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ai.brokk.util.Environment.shellCommandRunnerFactory")
public class FileFilteringServiceTest {
    @TempDir
    Path tempDir;

    private Path mainRepoPath;
    private GitRepo mainRepo;
    private Path worktreePath;
    private GitRepo worktreeRepo;

    @BeforeEach
    void setUp() throws Exception {
        Environment.shellCommandRunnerFactory = Environment.DEFAULT_SHELL_COMMAND_RUNNER_FACTORY;

        // Create main repository
        mainRepoPath = tempDir.resolve("mainRepo");
        Files.createDirectories(mainRepoPath);

        try (Git git = Git.init().setDirectory(mainRepoPath.toFile()).call()) {
            Path readme = mainRepoPath.resolve("README.md");
            Files.writeString(readme, "Main repo");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("Initial commit")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        mainRepo = new GitRepo(mainRepoPath);
    }

    @AfterEach
    void tearDown() {
        GitTestCleanupUtil.cleanupGitResources(worktreeRepo);
        GitTestCleanupUtil.cleanupGitResources(mainRepo);
        Environment.shellCommandRunnerFactory = Environment.DEFAULT_SHELL_COMMAND_RUNNER_FACTORY;
    }

    @Test
    void testWorktreeGitignoreIsHonored() throws Exception {
        assumeTrue(mainRepo.supportsWorktrees(), "Worktrees not supported, skipping test");

        // Create a worktree in a sibling directory (different from main repo)
        worktreePath = tempDir.resolve("feature-worktree");
        mainRepo.addWorktree("feature-branch", worktreePath);

        // Open GitRepo for the worktree
        worktreeRepo = new GitRepo(worktreePath);

        // Verify worktree setup
        assertTrue(worktreeRepo.isWorktree(), "Should be recognized as a worktree");
        assertNotEquals(
                mainRepoPath, worktreeRepo.getWorkTreeRoot(), "Worktree root should differ from main repo root");

        // Create a .gitignore in the worktree root (not in main repo)
        Path worktreeGitignore = worktreePath.resolve(".gitignore");
        Files.writeString(worktreeGitignore, "*.log\nbuild/\n");

        // Create files in the worktree - some should be ignored, some not
        Path srcDir = worktreePath.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), "public class Main {}");
        Files.writeString(srcDir.resolve("debug.log"), "log content");

        Path buildDir = worktreePath.resolve("build");
        Files.createDirectories(buildDir);
        Files.writeString(buildDir.resolve("output.class"), "bytecode");

        // Create FileFilteringService for the worktree
        var filteringService = new FileFilteringService(worktreePath, worktreeRepo);

        // Test isGitignored for various paths (original single-arg method)
        assertTrue(
                filteringService.isGitignored(Path.of("src/debug.log")), "*.log pattern should ignore src/debug.log");
        assertTrue(filteringService.isGitignored(Path.of("build")), "build/ pattern should ignore build directory");
        assertTrue(
                filteringService.isGitignored(Path.of("build/output.class")),
                "build/ pattern should ignore files under build/");

        assertFalse(filteringService.isGitignored(Path.of("src/Main.java")), "Java files should not be ignored");
        assertFalse(filteringService.isGitignored(Path.of("src")), "src directory should not be ignored");

        // Test the two-arg overload with explicit isDirectory flag
        // This is useful for filesystem walkers that already know the type
        assertTrue(
                filteringService.isGitignored(Path.of("build"), true),
                "build/ pattern should ignore when isDirectory=true");
        assertTrue(
                filteringService.isGitignored(Path.of("src/debug.log"), false),
                "*.log pattern should ignore when isDirectory=false");
        assertFalse(
                filteringService.isGitignored(Path.of("src/Main.java"), false),
                "Java files should not be ignored with explicit flag");
    }

    @Test
    void testWorktreeFilterFilesUsesWorktreeGitignore() throws Exception {
        assumeTrue(mainRepo.supportsWorktrees(), "Worktrees not supported, skipping test");

        // Create worktree
        worktreePath = tempDir.resolve("filter-worktree");
        mainRepo.addWorktree("filter-branch", worktreePath);
        worktreeRepo = new GitRepo(worktreePath);

        // Create .gitignore in worktree with patterns
        Files.writeString(worktreePath.resolve(".gitignore"), "*.tmp\n");

        // Create test files
        Files.writeString(worktreePath.resolve("keep.txt"), "keep");
        Files.writeString(worktreePath.resolve("ignore.tmp"), "ignore");

        // Create FileFilteringService
        var filteringService = new FileFilteringService(worktreePath, worktreeRepo);

        // Create ProjectFile instances
        var keepFile = new ProjectFile(worktreePath, Path.of("keep.txt"));
        var ignoreFile = new ProjectFile(worktreePath, Path.of("ignore.tmp"));

        // Filter files
        Set<ProjectFile> inputFiles = Set.of(keepFile, ignoreFile);
        Set<ProjectFile> filtered = filteringService.filterFiles(inputFiles, Set.of());

        assertTrue(filtered.contains(keepFile), "keep.txt should pass filter");
        assertFalse(filtered.contains(ignoreFile), "ignore.tmp should be filtered out by .gitignore");
    }

    @Test
    void testWorktreeGitignorePrecedenceOverMainRepo() throws Exception {
        assumeTrue(mainRepo.supportsWorktrees(), "Worktrees not supported, skipping test");

        // Add a .gitignore to main repo that ignores *.txt
        Path mainGitignore = mainRepoPath.resolve(".gitignore");
        Files.writeString(mainGitignore, "*.txt\n");
        try (Git git = Git.open(mainRepoPath.toFile())) {
            git.add().addFilepattern(".gitignore").call();
            git.commit()
                    .setMessage("Add .gitignore")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        // Create worktree - it should inherit the committed .gitignore
        worktreePath = tempDir.resolve("precedence-worktree");
        mainRepo.addWorktree("precedence-branch", worktreePath);
        worktreeRepo = new GitRepo(worktreePath);

        // Verify the worktree has the .gitignore from main repo
        assertTrue(Files.exists(worktreePath.resolve(".gitignore")), "Worktree should have .gitignore from main repo");

        // Create test file
        Files.writeString(worktreePath.resolve("test.txt"), "content");

        var filteringService = new FileFilteringService(worktreePath, worktreeRepo);

        // The .txt file should be ignored (pattern from worktree's .gitignore copy)
        assertTrue(
                filteringService.isGitignored(Path.of("test.txt")),
                "*.txt should be ignored via worktree's .gitignore");
    }

    @Test
    void testGetFixedGitignoreFilesIncludesWorktreeRoot() throws Exception {
        assumeTrue(mainRepo.supportsWorktrees(), "Worktrees not supported, skipping test");

        // Create worktree
        worktreePath = tempDir.resolve("fixed-gitignore-worktree");
        mainRepo.addWorktree("fixed-branch", worktreePath);
        worktreeRepo = new GitRepo(worktreePath);

        // Create .gitignore in worktree root
        Path worktreeGitignore = worktreePath.resolve(".gitignore");
        Files.writeString(worktreeGitignore, "*.ignored\n");

        // Get fixed gitignore files from the worktree repo
        var fixedFiles = worktreeRepo.getFixedGitignoreFiles();

        // Should include the worktree's root .gitignore
        assertTrue(
                fixedFiles.contains(worktreeGitignore),
                "Fixed gitignore files should include worktree's root .gitignore at: " + worktreeGitignore);
    }

    @Test
    void testIsGitIgnoredWithExplicitDirectoryFlag() throws Exception {
        // Test that isGitignored(path, isDirectory) correctly handles directory-only patterns
        // like "generated/" which should ignore directories but not files with the same name

        // Create .gitignore with a directory-only pattern
        Path gitignore = mainRepoPath.resolve(".gitignore");
        Files.writeString(gitignore, "generated/\n");

        var filteringService = new FileFilteringService(mainRepoPath, mainRepo);

        // When isDirectory=true, "generated/" pattern should match
        assertTrue(
                filteringService.isGitignored(Path.of("generated"), true),
                "generated/ pattern should ignore path when isDirectory=true");

        // When isDirectory=false, "generated/" pattern should NOT match (it's a dir-only pattern)
        assertFalse(
                filteringService.isGitignored(Path.of("generated"), false),
                "generated/ pattern should NOT ignore path when isDirectory=false");

        // Nested paths under a directory pattern
        assertTrue(
                filteringService.isGitignored(Path.of("generated/output.txt"), false),
                "Files under ignored directory should also be ignored");

        // Verify the original single-arg method still works (backward compatibility)
        // Create an actual directory so Files.isDirectory returns true
        Path generatedDir = mainRepoPath.resolve("generated");
        Files.createDirectories(generatedDir);
        assertTrue(
                filteringService.isGitignored(Path.of("generated")),
                "Original isGitignored(Path) should detect directory and apply pattern");
    }

    @Test
    void testIsGitIgnoredWithExplicitFlagForFilePatterns() throws Exception {
        // Test patterns that should match files regardless of isDirectory flag

        Path gitignore = mainRepoPath.resolve(".gitignore");
        Files.writeString(gitignore, "*.log\n");

        var filteringService = new FileFilteringService(mainRepoPath, mainRepo);

        // *.log pattern should ignore files
        assertTrue(filteringService.isGitignored(Path.of("debug.log"), false), "*.log pattern should ignore files");

        // *.log pattern behavior for directories (gitignore treats *.log as matching both by default)
        assertTrue(
                filteringService.isGitignored(Path.of("debug.log"), true),
                "*.log pattern should also match directories with that name");
    }

    @Test
    void testWorktreeIncludesSharedInfoExclude() throws Exception {
        assumeTrue(mainRepo.supportsWorktrees(), "Worktrees not supported, skipping test");

        // Create .git/info/exclude in the main repo (shared across worktrees)
        Path mainInfoExclude = mainRepoPath.resolve(".git/info/exclude");
        Files.createDirectories(mainInfoExclude.getParent());
        Files.writeString(mainInfoExclude, "*.local\n");

        // Create worktree
        worktreePath = tempDir.resolve("info-exclude-worktree");
        mainRepo.addWorktree("info-exclude-branch", worktreePath);
        worktreeRepo = new GitRepo(worktreePath);

        // Get fixed gitignore files from the worktree repo
        var fixedFiles = worktreeRepo.getFixedGitignoreFiles();

        // Should include the main repo's shared info/exclude
        boolean hasSharedInfoExclude = fixedFiles.stream().anyMatch(path -> path.endsWith(Path.of("info", "exclude")));

        assertTrue(hasSharedInfoExclude, "Worktree should include main repo's shared .git/info/exclude");

        // Verify the pattern is actually applied
        Files.writeString(worktreePath.resolve("test.local"), "content");
        var filteringService = new FileFilteringService(worktreePath, worktreeRepo);

        assertTrue(
                filteringService.isGitignored(Path.of("test.local")),
                "*.local pattern from shared info/exclude should be honored in worktree");
    }
}
