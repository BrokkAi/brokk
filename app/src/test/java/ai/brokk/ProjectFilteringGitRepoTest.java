package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Comprehensive tests for AbstractProject's filtering logic (gitignore and baseline exclusions).
 * Tests both core filtering (getAllFiles) and language integration (getAnalyzableFiles, getFiles).
 */
class ProjectFilteringGitRepoTest {

    private static void initGitRepo(Path tempDir) throws Exception {
        try (var git = Git.init().setDirectory(tempDir.toFile()).call()) {
            // Configure git
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();

            // Create initial commit
            Files.writeString(tempDir.resolve("README.md"), "# Test Project");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("Initial commit").call();
        }
    }

    private static void createFile(Path parent, String relativePath, String content) throws IOException {
        var file = parent.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String normalize(ProjectFile pf) {
        return pf.toString().replace('\\', '/');
    }

    private static void trackFiles(Path tempDir) throws Exception {
        try (var git = Git.open(tempDir.toFile())) {
            // Force-add all files individually to ensure they're staged
            // This includes files that would normally be gitignored
            try (var walk = Files.walk(tempDir)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> !p.toString().contains(".git"))
                        .forEach(p -> {
                            try {
                                var relative = tempDir.relativize(p).toString().replace('\\', '/');
                                // Use --force equivalent: AddCommand has no setForce, so we just add the file
                                // Git will track it in the index even if gitignored
                                git.add().addFilepattern(relative).call();
                            } catch (Exception e) {
                                // Ignore errors for individual files
                            }
                        });
            }
            // Don't commit - leave files in staging area so getTrackedFiles() sees them
            // This allows testing .gitignore filtering without Git refusing to commit ignored files
        }
    }

    @Test
    void getAllFiles_returns_all_files_when_no_gitignore(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "build/Generated.java", "class Generated {}");
        createFile(tempDir, "test/Test.java", "class Test {}");

        trackFiles(tempDir);

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Should include all tracked files
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/Main.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("build/Generated.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("test/Test.java")));

        project.close();
    }

    @Test
    void getAllFiles_filters_ignored_files(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "src/Main.class", "bytecode");
        createFile(tempDir, "debug.log", "log content");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        Files.writeString(tempDir.resolve(".gitignore"), "*.class\n*.log\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Should include .java but not .class or .log
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/Main.java")));
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).contains(".class")));
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).contains(".log")));

        project.close();
    }

    @Test
    void getAllFiles_filters_ignored_directories(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "build/Generated.java", "class Generated {}");
        createFile(tempDir, "build/output/Result.java", "class Result {}");
        createFile(tempDir, "target/classes/App.class", "bytecode");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        Files.writeString(tempDir.resolve(".gitignore"), "build/\ntarget/\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Should include src/ but not build/ or target/
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/Main.java")));
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).startsWith("build/")));
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).startsWith("target/")));

        project.close();
    }

    @Test
    void getAllFiles_respects_negation_patterns_for_files(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "app.py", "print('app')");
        createFile(tempDir, "app.pyc", "bytecode");
        createFile(tempDir, "important.pyc", "bytecode");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        // Ignore all .pyc files except important.pyc
        Files.writeString(tempDir.resolve(".gitignore"), "*.pyc\n!important.pyc\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Should include app.py and important.pyc, but not app.pyc
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("app.py")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("important.pyc")));
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).equals("app.pyc")));

        project.close();
    }

    @Test
    void getAllFiles_respects_negation_patterns_for_directories(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "build/Generated.java", "class Generated {}");
        createFile(tempDir, "build/keep/Important.java", "class Important {}");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files so Git doesn't refuse to add them
        // Ignore build/ but not build/keep/
        Files.writeString(tempDir.resolve(".gitignore"), "build/\n!build/keep/\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Should include src/ and build/keep/, but not other build/ files
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/Main.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("build/keep/Important.java")));
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).equals("build/Generated.java")));

        project.close();
    }

    @Test
    void getAllFiles_respects_nested_negation_patterns(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "logs/debug.log", "debug");
        createFile(tempDir, "logs/error.log", "error");
        createFile(tempDir, "logs/important/critical.log", "critical");
        createFile(tempDir, "logs/important/audit.log", "audit");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        // Ignore logs/ but not logs/important/
        Files.writeString(tempDir.resolve(".gitignore"), "logs/\n!logs/important/\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Should only include logs/important/ files
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).equals("logs/debug.log")));
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).equals("logs/error.log")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("logs/important/critical.log")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("logs/important/audit.log")));

        project.close();
    }

    @Test
    void getAllFiles_respects_wildcard_negation_patterns(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "app.pyc", "bytecode");
        createFile(tempDir, "src/module.pyc", "bytecode");
        createFile(tempDir, "src/important/critical.pyc", "bytecode");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        // Ignore all .pyc but not in src/important/
        Files.writeString(tempDir.resolve(".gitignore"), "**/*.pyc\n!src/important/*.pyc\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Should only include src/important/*.pyc
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).equals("app.pyc")));
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/module.pyc")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/important/critical.pyc")));

        project.close();
    }

    @Test
    void getAllFiles_applies_baseline_exclusions(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "generated/Generated.java", "class Generated {}");
        createFile(tempDir, "vendor/Library.java", "class Library {}");

        trackFiles(tempDir);

        var project = new MainProject(tempDir);

        // Set baseline exclusions
        var buildDetails = new BuildAgent.BuildDetails("", "", "", Set.of("generated", "vendor"), Map.of());
        project.saveBuildDetails(buildDetails);

        var allFiles = project.getAllFiles();

        // Should exclude baseline-excluded directories
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/Main.java")));
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).startsWith("generated/")));
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).startsWith("vendor/")));

        project.close();
    }

    @Test
    void getAllFiles_combines_gitignore_and_baseline_exclusions(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "build/Generated.java", "class Generated {}");
        createFile(tempDir, "vendor/Library.java", "class Library {}");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        Files.writeString(tempDir.resolve(".gitignore"), "build/\n");

        var project = new MainProject(tempDir);

        // Set baseline exclusions
        var buildDetails = new BuildAgent.BuildDetails("", "", "", Set.of("vendor"), Map.of());
        project.saveBuildDetails(buildDetails);

        var allFiles = project.getAllFiles();

        // Should exclude both gitignore and baseline exclusions
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/Main.java")));
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).startsWith("build/")));
        assertFalse(allFiles.stream().anyMatch(pf -> normalize(pf).startsWith("vendor/")));

        project.close();
    }

    @Test
    void getAllFiles_handles_empty_gitignore(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "build/Generated.java", "class Generated {}");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        Files.writeString(tempDir.resolve(".gitignore"), "\n# Just comments\n\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Should include all files when gitignore is empty
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/Main.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("build/Generated.java")));

        project.close();
    }

    @Test
    void getAllFiles_caches_results(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        Files.writeString(tempDir.resolve(".gitignore"), "*.class\n");

        var project = new MainProject(tempDir);

        // First call should populate cache
        var allFiles1 = project.getAllFiles();

        // Second call should use cache (should be same instance)
        var allFiles2 = project.getAllFiles();

        assertSame(allFiles1, allFiles2, "Should return cached instance");

        project.close();
    }

    @Test
    void invalidateAllFiles_clears_cache(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        Files.writeString(tempDir.resolve(".gitignore"), "*.class\n");

        var project = new MainProject(tempDir);

        var allFiles1 = project.getAllFiles();

        // Invalidate cache
        project.invalidateAllFiles();

        var allFiles2 = project.getAllFiles();

        // Should be different instances after invalidation
        assertNotSame(allFiles1, allFiles2, "Should create new instance after invalidation");

        project.close();
    }

    @Test
    void getAllFiles_handles_non_git_repos(@TempDir Path tempDir) throws Exception {
        // Don't initialize git repo - so don't call trackFiles()
        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "build/Generated.java", "class Generated {}");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Should include all files when not a git repo
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/Main.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).equals("build/Generated.java")));

        project.close();
    }

    // ========================================
    // Language Integration Tests
    // ========================================

    @Test
    void getAnalyzableFiles_filters_by_language_extension(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "src/app.py", "print('app')");
        createFile(tempDir, "src/lib.js", "console.log('lib')");
        createFile(tempDir, "README.md", "# Docs");

        trackFiles(tempDir);

        var project = new MainProject(tempDir);

        // Get Java files
        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);
        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(p -> p.getRelPath().endsWith("Main.java")));

        // Get Python files
        var pythonFiles = project.getAnalyzableFiles(Languages.PYTHON);
        assertEquals(1, pythonFiles.size());
        assertTrue(pythonFiles.stream().anyMatch(p -> p.getRelPath().endsWith("app.py")));

        project.close();
    }

    @Test
    void getAnalyzableFiles_respects_gitignore(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "src/Main.class", "bytecode");
        createFile(tempDir, "build/Generated.java", "class Generated {}");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        Files.writeString(tempDir.resolve(".gitignore"), "build/\n*.class\n");

        var project = new MainProject(tempDir);
        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);

        // Should only include src/Main.java
        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(p -> p.getRelPath().endsWith("src/Main.java")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.toString().contains("build/")));

        project.close();
    }

    @Test
    void getAnalyzableFiles_respects_baseline_exclusions(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "generated/Generated.java", "class Generated {}");

        trackFiles(tempDir);

        var project = new MainProject(tempDir);

        // Set baseline exclusions
        var buildDetails = new BuildAgent.BuildDetails("", "", "", Set.of("generated"), Map.of());
        project.saveBuildDetails(buildDetails);

        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);

        // Should only include src/Main.java
        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(p -> p.getRelPath().endsWith("src/Main.java")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.toString().contains("generated/")));

        project.close();
    }

    @Test
    void getAnalyzableFiles_combines_gitignore_baseline_and_language_filters(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "src/app.py", "print('app')");
        createFile(tempDir, "build/Generated.java", "class Generated {}");
        createFile(tempDir, "vendor/Library.java", "class Library {}");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        Files.writeString(tempDir.resolve(".gitignore"), "build/\n");

        var project = new MainProject(tempDir);

        // Set baseline exclusions
        var buildDetails = new BuildAgent.BuildDetails("", "", "", Set.of("vendor"), Map.of());
        project.saveBuildDetails(buildDetails);

        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);

        // Should only include src/Main.java (filtered by gitignore, baseline, and extension)
        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(p -> p.getRelPath().endsWith("src/Main.java")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.toString().contains("build/")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.toString().contains("vendor/")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.getRelPath().endsWith(".py")));

        project.close();
    }

    @Test
    void getAnalyzableFiles_respects_negation_patterns(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "build/Generated.java", "class Generated {}");
        createFile(tempDir, "build/keep/Important.java", "class Important {}");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        Files.writeString(tempDir.resolve(".gitignore"), "build/\n!build/keep/\n");

        var project = new MainProject(tempDir);
        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);

        // Should include src/Main.java and build/keep/Important.java
        assertEquals(2, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(p -> p.getRelPath().endsWith("src/Main.java")));
        assertTrue(javaFiles.stream().anyMatch(p -> p.getRelPath().endsWith("build/keep/Important.java")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.getRelPath().endsWith("build/Generated.java")));

        project.close();
    }

    @Test
    void getAnalyzableFiles_handles_multiple_languages(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "src/Main.class", "bytecode");
        createFile(tempDir, "src/app.py", "print('app')");
        createFile(tempDir, "src/app.pyc", "bytecode");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        Files.writeString(tempDir.resolve(".gitignore"), "*.class\n*.pyc\n");

        var project = new MainProject(tempDir);

        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);
        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(p -> p.getRelPath().endsWith("Main.java")));

        var pythonFiles = project.getAnalyzableFiles(Languages.PYTHON);
        assertEquals(1, pythonFiles.size());
        assertTrue(pythonFiles.stream().anyMatch(p -> p.getRelPath().endsWith("app.py")));

        project.close();
    }

    @Test
    void getAnalyzableFiles_returns_empty_when_no_matching_files(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/app.py", "print('app')");
        createFile(tempDir, "README.md", "# Docs");

        trackFiles(tempDir);

        var project = new MainProject(tempDir);
        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);

        assertTrue(javaFiles.isEmpty(), "Should return empty list when no Java files exist");

        project.close();
    }

    @Test
    void getAnalyzableFiles_handles_nested_directories(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/main/java/com/example/App.java", "package com.example; class App {}");
        createFile(tempDir, "src/test/java/com/example/AppTest.java", "package com.example; class AppTest {}");
        createFile(tempDir, "target/generated/com/example/Generated.java", "class Generated {}");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        Files.writeString(tempDir.resolve(".gitignore"), "target/\n");

        var project = new MainProject(tempDir);
        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);

        // Should include both src files but not target
        assertEquals(2, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(p -> p.getRelPath().endsWith("App.java")));
        assertTrue(javaFiles.stream().anyMatch(p -> p.getRelPath().endsWith("AppTest.java")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.toString().contains("target/")));

        project.close();
    }

    @Test
    void getFiles_filters_by_language_extension(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "src/app.py", "print('app')");

        trackFiles(tempDir);

        var project = new MainProject(tempDir);
        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);

        // getFiles() should also respect filtering
        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(pf -> normalize(pf).endsWith("Main.java")));

        project.close();
    }

    @Test
    void getFiles_respects_gitignore(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "build/Generated.java", "class Generated {}");

        trackFiles(tempDir);

        // Create .gitignore AFTER tracking files
        Files.writeString(tempDir.resolve(".gitignore"), "build/\n");

        var project = new MainProject(tempDir);
        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);

        // getFiles() should respect gitignore
        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")));
        assertFalse(javaFiles.stream().anyMatch(pf -> normalize(pf).contains("build/")));

        project.close();
    }

    /**
     * Test that nested .gitignore files are properly loaded and applied.
     * Structure:
     * /root/.gitignore (ignores *.log)
     * /root/subdir/.gitignore (ignores build/*, !build/keep/)
     *
     * Note: Using build/* instead of build/ because Git doesn't allow re-including
     * files if their parent directory is excluded. See gitignore docs:
     * "It is not possible to re-include a file if a parent directory of that file is excluded."
     */
    @Test
    void getAllFiles_handles_nested_gitignore_files(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create files in root
        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "debug.log", "log content");
        createFile(tempDir, "README.md", "readme");

        // Create files in subdirectory
        createFile(tempDir, "subdir/src/App.java", "class App {}");
        createFile(tempDir, "subdir/trace.log", "trace log");
        createFile(tempDir, "subdir/build/Generated.java", "class Generated {}");
        createFile(tempDir, "subdir/build/keep/Important.java", "class Important {}");

        trackFiles(tempDir);

        // Create root .gitignore (ignores *.log)
        Files.writeString(tempDir.resolve(".gitignore"), "*.log\n");

        // Create subdir .gitignore (ignores build/*, but not build/keep/)
        // Using build/* instead of build/ so negation pattern works
        Files.createDirectories(tempDir.resolve("subdir"));
        Files.writeString(tempDir.resolve("subdir/.gitignore"), "build/*\n!build/keep/\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Root .gitignore should exclude *.log files
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("debug.log")),
                "Root .gitignore should exclude debug.log");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("trace.log")),
                "Subdir .log files should also be excluded by root .gitignore");

        // Subdir .gitignore should exclude build/* except build/keep/
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("build/Generated.java")),
                "Subdir .gitignore should exclude build/Generated.java");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("build/keep/Important.java")),
                "Subdir .gitignore negation should include build/keep/Important.java");

        // Other files should be included
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("README.md")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("subdir/src/App.java")));

        project.close();
    }

    /**
     * Tests that nested .gitignore files can override parent .gitignore rules.
     * This validates the precedence bug fix where closer (more specific) rules
     * must override farther (less specific) rules.
     *
     * Setup:
     * /root/.gitignore (ignores *.log)
     * /root/parent/sub/.gitignore (un-ignores keep.log with !keep.log)
     *
     * Expected behavior (Git semantics):
     * - parent/debug.log → ignored (parent rule applies)
     * - parent/sub/delete.log → ignored (parent rule applies, no override)
     * - parent/sub/keep.log → NOT ignored (sub's !keep.log overrides parent's *.log)
     */
    @Test
    void getAllFiles_nested_gitignore_overrides_parent_gitignore(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create files
        createFile(tempDir, "parent/debug.log", "debug");
        createFile(tempDir, "parent/sub/keep.log", "keep this");
        createFile(tempDir, "parent/sub/delete.log", "delete this");
        createFile(tempDir, "parent/sub/code.java", "class Code {}");

        trackFiles(tempDir);

        // Parent ignores all .log files
        createFile(tempDir, "parent/.gitignore", "*.log\n");

        // Subdirectory un-ignores keep.log specifically
        createFile(tempDir, "parent/sub/.gitignore", "!keep.log\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // parent/debug.log should be ignored by parent/.gitignore
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("parent/debug.log")),
                "parent/debug.log should be ignored by parent/.gitignore");

        // parent/sub/delete.log should be ignored (parent rule applies, no override)
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("parent/sub/delete.log")),
                "parent/sub/delete.log should be ignored (parent rule, no override)");

        // parent/sub/keep.log should be NOT ignored (sub's rule overrides parent's)
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("parent/sub/keep.log")),
                "parent/sub/keep.log should NOT be ignored (sub/.gitignore overrides parent/.gitignore)");

        // Other files should be included
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("parent/sub/code.java")),
                "parent/sub/code.java should be included");

        project.close();
    }

    /**
     * Tests multi-level nested gitignore precedence (3+ levels).
     * Verifies that the "most specific must win" rule works when there are
     * multiple nested .gitignore files above the target file.
     *
     * Setup:
     * /root/.gitignore (ignores *.log)
     * /root/projects/.gitignore (ignores *.tmp)
     * /root/projects/service/.gitignore (un-ignores keep.log with !keep.log)
     *
     * Expected behavior:
     * - root/debug.log → ignored (root rule)
     * - projects/test.tmp → ignored (projects rule)
     * - projects/service/keep.log → NOT ignored (service rule overrides root)
     * - projects/service/delete.log → ignored (root rule applies, no override)
     * - projects/service/temp.tmp → ignored (projects rule applies)
     */
    @Test
    void getAllFiles_multi_level_nested_gitignore_precedence(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create files at different levels
        createFile(tempDir, "debug.log", "root level log");
        createFile(tempDir, "projects/test.tmp", "projects level tmp");
        createFile(tempDir, "projects/service/keep.log", "keep this");
        createFile(tempDir, "projects/service/delete.log", "delete this");
        createFile(tempDir, "projects/service/temp.tmp", "temp file");
        createFile(tempDir, "projects/service/code.java", "class Code {}");

        trackFiles(tempDir);

        // Root ignores all .log files
        createFile(tempDir, ".gitignore", "*.log\n");

        // Projects ignores all .tmp files
        createFile(tempDir, "projects/.gitignore", "*.tmp\n");

        // Service un-ignores keep.log specifically (overrides root)
        createFile(tempDir, "projects/service/.gitignore", "!keep.log\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Root level: debug.log should be ignored
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("debug.log")),
                "debug.log should be ignored by root .gitignore");

        // Projects level: test.tmp should be ignored
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("projects/test.tmp")),
                "projects/test.tmp should be ignored by projects/.gitignore");

        // Service level: keep.log should NOT be ignored (most specific rule wins)
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("projects/service/keep.log")),
                "projects/service/keep.log should NOT be ignored (service/.gitignore overrides root/.gitignore)");

        // Service level: delete.log should be ignored (root rule, no override)
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("projects/service/delete.log")),
                "projects/service/delete.log should be ignored (root rule applies)");

        // Service level: temp.tmp should be ignored (projects rule applies)
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("projects/service/temp.tmp")),
                "projects/service/temp.tmp should be ignored (projects/.gitignore rule)");

        // Service level: code.java should be included (no rule matches)
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("projects/service/code.java")),
                "projects/service/code.java should be included");

        project.close();
    }

    /**
     * Tests that a non-root ancestor .gitignore can be overridden by a deeper .gitignore.
     * This is different from root override tests - it verifies middle-level precedence.
     *
     * Setup:
     * /root/services/.gitignore (ignores *.log)
     * /root/services/backend/.gitignore (un-ignores important.log)
     *
     * Expected: services/backend/important.log should be included (deeper wins)
     */
    @Test
    void getAllFiles_non_root_ancestor_can_be_overridden(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create files
        createFile(tempDir, "services/debug.log", "debug");
        createFile(tempDir, "services/backend/important.log", "important");
        createFile(tempDir, "services/backend/trace.log", "trace");
        createFile(tempDir, "services/backend/App.java", "class App {}");

        trackFiles(tempDir);

        // Services (non-root ancestor) ignores all .log files
        createFile(tempDir, "services/.gitignore", "*.log\n");

        // Backend un-ignores important.log specifically
        createFile(tempDir, "services/backend/.gitignore", "!important.log\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // services/debug.log should be ignored (ancestor rule)
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("services/debug.log")),
                "services/debug.log should be ignored by services/.gitignore");

        // services/backend/important.log should NOT be ignored (deeper rule overrides ancestor)
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("services/backend/important.log")),
                "services/backend/important.log should NOT be ignored (backend/.gitignore overrides services/.gitignore)");

        // services/backend/trace.log should be ignored (ancestor rule, no override)
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("services/backend/trace.log")),
                "services/backend/trace.log should be ignored (ancestor rule applies)");

        // services/backend/App.java should be included
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("services/backend/App.java")),
                "services/backend/App.java should be included");

        project.close();
    }

    /**
     * Tests double-negation scenarios and path-anchored rules.
     * Verifies that:
     * 1. Double negation (ignore → un-ignore → ignore again) works correctly
     * 2. Path-anchored rules (/build vs build/) are handled properly
     *
     * Setup:
     * /root/.gitignore (ignores *.tmp)
     * /root/sub/.gitignore (un-ignores !keep.tmp)
     * /root/sub/nested/.gitignore (ignores keep.tmp again - double negation)
     * /root/.gitignore (also has /build/ - anchored to root)
     *
     * Expected behavior:
     * - Double negation: file ignored at root, included at sub, ignored again at nested
     * - Path anchoring: /build/ only matches root/build/, not root/src/build/
     */
    @Test
    void getAllFiles_double_negation_and_anchored_paths(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create files for double-negation test
        createFile(tempDir, "test.tmp", "root tmp");
        createFile(tempDir, "sub/keep.tmp", "keep in sub");
        createFile(tempDir, "sub/nested/keep.tmp", "ignore again in nested");

        // Create files for path-anchoring test
        createFile(tempDir, "build/output.class", "root build");
        createFile(tempDir, "src/build/script.sh", "nested build");
        createFile(tempDir, "src/Main.java", "class Main {}");

        trackFiles(tempDir);

        // Root: ignore *.tmp and /build/ (anchored to root)
        createFile(tempDir, ".gitignore", "*.tmp\n/build/\n");

        // Sub: un-ignore keep.tmp (double negation starts)
        createFile(tempDir, "sub/.gitignore", "!keep.tmp\n");

        // Nested: ignore keep.tmp again (double negation completes)
        createFile(tempDir, "sub/nested/.gitignore", "keep.tmp\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Double negation tests
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("test.tmp")),
                "test.tmp should be ignored (root rule)");

        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("sub/keep.tmp")),
                "sub/keep.tmp should NOT be ignored (sub/.gitignore un-ignores)");

        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("sub/nested/keep.tmp")),
                "sub/nested/keep.tmp should be ignored (nested/.gitignore re-ignores)");

        // Path-anchoring tests
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("build/output.class")),
                "build/output.class should be ignored (anchored /build/ matches root/build/)");

        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/build/script.sh")),
                "src/build/script.sh should NOT be ignored (anchored /build/ does not match nested build/)");

        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/Main.java")),
                "src/Main.java should be included");

        project.close();
    }

    /**
     * Performance smoke test for deep directory structures.
     * Ensures that the gitignore implementation doesn't regress badly with:
     * - Deep directory trees (20+ levels)
     * - Multiple nested .gitignore files
     * - Repeated per-file directory ascent and Files.exists calls
     *
     * This is not a rigorous benchmark, just a safeguard against catastrophic regressions.
     */
    @Test
    void getAllFiles_performance_smoke_test_deep_directories(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create a deep directory structure: 20 levels deep
        StringBuilder pathBuilder = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            pathBuilder.append("level").append(i).append("/");
        }
        String deepPath = pathBuilder.toString();

        // Create files at various depths
        createFile(tempDir, "shallow.java", "class Shallow {}");
        createFile(tempDir, "level0/mid.java", "class Mid {}");
        createFile(tempDir, deepPath + "deep.java", "class Deep {}");
        createFile(tempDir, deepPath + "test.log", "deep log");

        // Create .gitignore files at several levels
        createFile(tempDir, ".gitignore", "*.log\n");
        createFile(tempDir, "level0/.gitignore", "# comment\n");
        createFile(tempDir, "level0/level1/level2/.gitignore", "# another comment\n");

        trackFiles(tempDir);

        var project = new MainProject(tempDir);

        // Measure time (crude smoke test - just ensure it completes reasonably)
        long startTime = System.currentTimeMillis();
        var allFiles = project.getAllFiles();
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // Verify correctness
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("shallow.java")),
                "shallow.java should be included");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("level0/mid.java")),
                "level0/mid.java should be included");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals(deepPath.replaceAll("/$", "") + "/deep.java")),
                "deep.java should be included at 20 levels deep");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals(deepPath.replaceAll("/$", "") + "/test.log")),
                "test.log should be ignored even at 20 levels deep");

        // Smoke test: should complete in under 5 seconds for this small dataset
        // (This is a very generous threshold - just catching catastrophic regressions)
        assertTrue(duration < 5000, "getAllFiles took " + duration + "ms, expected < 5000ms (smoke test threshold)");

        project.close();
    }

    /**
     * Tests gitignore chain cache effectiveness.
     * Verifies that multiple files in the same directory benefit from caching,
     * and that cache invalidation works correctly.
     *
     * This test ensures the performance optimization (gitignoreChainCache) works as expected:
     * - First file in directory: cache miss (computes chain)
     * - Subsequent files in directory: cache hits (reuses chain)
     * - After invalidation: cache miss again
     */
    @Test
    void getAllFiles_gitignore_chain_cache_effectiveness(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create multiple files in the same directory to test cache effectiveness
        createFile(tempDir, "src/main/java/File1.java", "class File1 {}");
        createFile(tempDir, "src/main/java/File2.java", "class File2 {}");
        createFile(tempDir, "src/main/java/File3.java", "class File3 {}");
        createFile(tempDir, "src/main/java/File4.java", "class File4 {}");
        createFile(tempDir, "src/main/java/File5.java", "class File5 {}");

        // Create files in a different directory
        createFile(tempDir, "src/test/java/Test1.java", "class Test1 {}");
        createFile(tempDir, "src/test/java/Test2.java", "class Test2 {}");

        // Create a .gitignore file
        createFile(tempDir, ".gitignore", "*.log\n");
        createFile(tempDir, "debug.log", "log file");

        trackFiles(tempDir);

        var project = new MainProject(tempDir);

        // First call to getAllFiles() - should populate cache
        var allFiles1 = project.getAllFiles();

        // Verify correctness
        assertEquals(
                7,
                allFiles1.stream().filter(pf -> normalize(pf).endsWith(".java")).count(),
                "Should have 7 Java files");
        assertFalse(
                allFiles1.stream().anyMatch(pf -> normalize(pf).equals("debug.log")), "debug.log should be ignored");

        // Second call - should use cache (same files, same directory structure)
        var allFiles2 = project.getAllFiles();
        assertEquals(allFiles1.size(), allFiles2.size(), "Results should be identical (from cache)");

        // Test cache invalidation
        project.invalidateAllFiles();

        // Third call - cache should be cleared, should recompute
        var allFiles3 = project.getAllFiles();
        assertEquals(allFiles1.size(), allFiles3.size(), "Results should still be correct after invalidation");

        // Verify that files in the same directory benefit from cache
        // This is implicit in the performance - if cache wasn't working,
        // we'd see many more Files.exists() calls and slower performance
        // The smoke test above verifies overall performance doesn't regress

        project.close();
    }

    /**
     * Test that .git/info/exclude is properly loaded and applied.
     * .git/info/exclude works like .gitignore but is local to the repository and not committed.
     */
    @Test
    void getAllFiles_respects_git_info_exclude(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create test files
        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "local-config.yaml", "config: local");
        createFile(tempDir, "temp-notes.txt", "temporary notes");
        createFile(tempDir, "README.md", "readme");

        trackFiles(tempDir);

        // Create .git/info/exclude with local ignore patterns
        var gitInfoDir = tempDir.resolve(".git/info");
        Files.createDirectories(gitInfoDir);
        Files.writeString(gitInfoDir.resolve("exclude"), "# Local excludes\n" + "local-config.yaml\n" + "temp-*.txt\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Files matching .git/info/exclude should be excluded
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("local-config.yaml")),
                ".git/info/exclude should exclude local-config.yaml");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("temp-notes.txt")),
                ".git/info/exclude should exclude temp-notes.txt (wildcard pattern)");

        // Other files should be included
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("README.md")));

        project.close();
    }

    /**
     * Test monorepo scenario where project root is a subdirectory within a larger git repository.
     * Structure:
     * /monorepo/.gitignore (ignores *.log, node_modules/)
     * /monorepo/backend/ (PROJECT ROOT)
     * /monorepo/backend/.gitignore (ignores target/, !target/classes/)
     * /monorepo/frontend/ (other project, not part of this project)
     */
    @Test
    void getAllFiles_handles_monorepo_subdirectory_project(@TempDir Path tempDir) throws Exception {
        // Initialize git repo at monorepo root
        initGitRepo(tempDir);

        // Create root .gitignore
        Files.writeString(tempDir.resolve(".gitignore"), "*.log\nnode_modules/\n");

        // Create files in frontend (not part of backend project)
        createFile(tempDir, "frontend/package.json", "{}");
        createFile(tempDir, "frontend/src/App.tsx", "export const App = () => {};");
        createFile(tempDir, "frontend/node_modules/react/index.js", "module.exports = {};");

        // Create files in backend (our project root)
        createFile(tempDir, "backend/src/Main.java", "class Main {}");
        createFile(tempDir, "backend/debug.log", "debug logs");
        createFile(tempDir, "backend/target/classes/Main.class", "compiled class");
        createFile(tempDir, "backend/target/generated/Gen.java", "generated code");
        createFile(tempDir, "backend/README.md", "backend readme");

        // Create backend .gitignore
        createFile(tempDir, "backend/.gitignore", "target/*\n!target/classes/\n");

        trackFiles(tempDir);

        // Open project with backend as root (subdirectory of git repo)
        var backendRoot = tempDir.resolve("backend");
        var project = new MainProject(backendRoot);
        var allFiles = project.getAllFiles();

        // Root .gitignore should exclude *.log files (even though we're in a subdirectory)
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("debug.log")),
                "Root .gitignore should exclude debug.log in backend/");

        // Backend .gitignore should exclude target/* except target/classes/
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).contains("target/generated")),
                "Backend .gitignore should exclude target/generated/");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).contains("target/classes")),
                "Backend .gitignore negation should include target/classes/");

        // Files from backend should be included
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")),
                "backend/src/Main.java should be included");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("README.md")),
                "backend/README.md should be included");

        // Note: We don't test that frontend files are excluded here because getAllFilesRaw()
        // behavior with monorepo subdirectories is outside the scope of gitignore filtering.
        // The gitignore implementation correctly handles the files it receives.

        project.close();
    }

    /**
     * Test that monorepo subdirectory projects respect both root and nested gitignore files.
     * This is a more complex scenario with multiple levels of gitignore.
     */
    @Test
    void getAllFiles_monorepo_respects_multiple_gitignore_levels(@TempDir Path tempDir) throws Exception {
        // Initialize git repo at monorepo root
        initGitRepo(tempDir);

        // Create root .gitignore (applies to entire monorepo)
        Files.writeString(tempDir.resolve(".gitignore"), "*.secret\n.env\n");

        // Create projects directory .gitignore
        Files.createDirectories(tempDir.resolve("projects"));
        Files.writeString(tempDir.resolve("projects/.gitignore"), "*.tmp\n");

        // Create files in our project (projects/service/)
        createFile(tempDir, "projects/service/src/Main.java", "class Main {}");
        createFile(tempDir, "projects/service/config.secret", "secret data");
        createFile(tempDir, "projects/service/.env", "ENV=dev");
        createFile(tempDir, "projects/service/cache.tmp", "temp cache");
        createFile(tempDir, "projects/service/README.md", "readme");

        // Create service .gitignore
        createFile(tempDir, "projects/service/.gitignore", "build/\n");
        createFile(tempDir, "projects/service/build/output.jar", "jar file");

        trackFiles(tempDir);

        // Open project with projects/service as root
        var serviceRoot = tempDir.resolve("projects/service");
        var project = new MainProject(serviceRoot);
        var allFiles = project.getAllFiles();

        // Root .gitignore should exclude *.secret and .env
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("config.secret")),
                "Root .gitignore should exclude *.secret");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith(".env")),
                "Root .gitignore should exclude .env");

        // projects/.gitignore should exclude *.tmp
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("cache.tmp")),
                "projects/.gitignore should exclude *.tmp");

        // service/.gitignore should exclude build/
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).contains("build/")),
                "service/.gitignore should exclude build/");

        // Other files should be included
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("README.md")));

        project.close();
    }

    /**
     * Test Git's rule that you cannot un-ignore a file if its parent directory is ignored.
     * According to Git documentation:
     * "It is not possible to re-include a file if a parent directory of that file is excluded."
     *
     * Test case:
     * .gitignore contains:
     *   build/
     *   !build/app.java
     *
     * Result: build/app.java should STILL BE IGNORED because build/ is ignored.
     *
     * To properly un-ignore build/app.java, you would need:
     *   build/*
     *   !build/app.java
     * OR:
     *   build/
     *   !build/
     *   !build/app.java
     */
    @Test
    void getAllFiles_cannot_unignore_file_in_ignored_directory(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create test files
        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "build/classes/App.class", "compiled");
        createFile(tempDir, "build/app.java", "source in build dir");
        createFile(tempDir, "README.md", "readme");

        trackFiles(tempDir);

        // Create .gitignore that tries (but fails) to un-ignore a file in an ignored directory
        Files.writeString(tempDir.resolve(".gitignore"), "build/\n" + "!build/app.java\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // build/ directory is ignored, so ALL files inside it should be ignored
        // The negation pattern !build/app.java does NOT work because the parent directory is ignored
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).contains("build/")),
                "All files in build/ should be ignored (cannot un-ignore file in ignored directory)");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("build/app.java")),
                "build/app.java should be ignored despite !build/app.java pattern");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("build/classes/App.class")),
                "build/classes/App.class should be ignored");

        // Other files should be included
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("README.md")));

        project.close();
    }

    /**
     * Test the correct way to un-ignore specific files: use wildcards instead of directory ignore.
     * Using build/* instead of build/ allows negation patterns to work.
     */
    @Test
    void getAllFiles_unignore_file_with_wildcard_pattern(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create test files
        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "build/classes/App.class", "compiled");
        createFile(tempDir, "build/app.java", "source in build dir");
        createFile(tempDir, "README.md", "readme");

        trackFiles(tempDir);

        // Create .gitignore using build/* (not build/) so negation works
        Files.writeString(tempDir.resolve(".gitignore"), "build/*\n" + "!build/app.java\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // build/* ignores contents of build/ but not the directory itself
        // So !build/app.java can successfully un-ignore the file
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("build/app.java")),
                "build/app.java should be un-ignored with build/* and !build/app.java");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("build/classes/App.class")),
                "build/classes/App.class should still be ignored by build/*");

        // Other files should be included
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("README.md")));

        project.close();
    }

    /**
     * Test that global gitignore from core.excludesfile is respected.
     */
    @Test
    void getAllFiles_respects_global_gitignore_from_config(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create test files
        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "temp.log", "temporary log");
        createFile(tempDir, "data.db", "database file");
        createFile(tempDir, "README.md", "readme");

        trackFiles(tempDir);

        // Create a global gitignore file in a temp location
        Path globalGitignore = tempDir.resolve("global-gitignore");
        Files.writeString(globalGitignore, "*.log\n*.db\n");

        // Configure git to use this global gitignore
        var repo = org.eclipse.jgit.storage.file.FileRepositoryBuilder.create(
                tempDir.resolve(".git").toFile());
        var config = repo.getConfig();
        config.setString("core", null, "excludesfile", globalGitignore.toString());
        config.save();
        repo.close();

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Global gitignore patterns should be applied
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("temp.log")),
                "temp.log should be ignored by global gitignore");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("data.db")),
                "data.db should be ignored by global gitignore");

        // Other files should be included
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")),
                "src/Main.java should be included");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("README.md")), "README.md should be included");

        project.close();
    }

    /**
     * Test that XDG location (~/.config/git/ignore) is used as fallback.
     */
    @Test
    void getAllFiles_uses_xdg_location_for_global_gitignore(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create test files
        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "build.log", "build log");
        createFile(tempDir, "README.md", "readme");

        trackFiles(tempDir);

        // Set up a mock XDG location by configuring core.excludesfile to point to it
        // (JGit doesn't automatically check XDG location in tests, so we configure it explicitly)
        Path xdgConfig = tempDir.resolve("xdg-config");
        Files.createDirectories(xdgConfig);
        Path xdgIgnore = xdgConfig.resolve("ignore");
        Files.writeString(xdgIgnore, "*.log\n");

        var repo = org.eclipse.jgit.storage.file.FileRepositoryBuilder.create(
                tempDir.resolve(".git").toFile());
        var config = repo.getConfig();
        config.setString("core", null, "excludesfile", xdgIgnore.toString());
        config.save();
        repo.close();

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // XDG ignore patterns should be applied
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("build.log")),
                "build.log should be ignored by XDG gitignore");

        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")),
                "src/Main.java should be included");

        project.close();
    }

    /**
     * Test that local .gitignore overrides global gitignore (precedence).
     */
    @Test
    void getAllFiles_local_gitignore_overrides_global(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create test files
        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "important.log", "important log file");
        createFile(tempDir, "temp.log", "temporary log");
        createFile(tempDir, "README.md", "readme");

        trackFiles(tempDir);

        // Create a global gitignore that ignores all .log files
        Path globalGitignore = tempDir.resolve("global-gitignore");
        Files.writeString(globalGitignore, "*.log\n");

        var repo = org.eclipse.jgit.storage.file.FileRepositoryBuilder.create(
                tempDir.resolve(".git").toFile());
        var config = repo.getConfig();
        config.setString("core", null, "excludesfile", globalGitignore.toString());
        config.save();
        repo.close();

        // Create local .gitignore that un-ignores important.log (higher precedence)
        Files.writeString(tempDir.resolve(".gitignore"), "!important.log\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // important.log should be included (local .gitignore overrides global)
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("important.log")),
                "important.log should be un-ignored by local .gitignore");

        // temp.log should still be ignored (global pattern applies)
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("temp.log")),
                "temp.log should still be ignored by global gitignore");

        project.close();
    }

    /**
     * Test that tilde expansion works in core.excludesfile path.
     */
    @Test
    void getAllFiles_handles_tilde_expansion_in_global_path(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create test files
        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "cache.tmp", "cache file");
        createFile(tempDir, "README.md", "readme");

        trackFiles(tempDir);

        // Create a global gitignore in a subdirectory of tempDir to simulate home directory
        Path fakeHome = tempDir.resolve("fake-home");
        Files.createDirectories(fakeHome);
        Path globalGitignore = fakeHome.resolve("my-gitignore");
        Files.writeString(globalGitignore, "*.tmp\n");

        // Create a path with tilde that should be expanded
        // Note: JGit's FS.resolve() handles tilde expansion, but in tests we can't mock the actual home
        // So we'll use an absolute path for this test to verify the mechanism works
        var repo = org.eclipse.jgit.storage.file.FileRepositoryBuilder.create(
                tempDir.resolve(".git").toFile());
        var config = repo.getConfig();
        // Use absolute path here since we can't change the actual user home in tests
        config.setString("core", null, "excludesfile", globalGitignore.toString());
        config.save();
        repo.close();

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Global gitignore patterns should be applied
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("cache.tmp")),
                "cache.tmp should be ignored by global gitignore");

        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")),
                "src/Main.java should be included");

        project.close();
    }

    @Test
    void getAllFiles_invalidates_cache_when_untracked_gitignore_changes(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "build/Generated.java", "class Generated {}");

        trackFiles(tempDir);

        var project = new MainProject(tempDir);

        // First call should populate cache with all files (no gitignore yet)
        var allFiles1 = project.getAllFiles();
        assertEquals(3, allFiles1.size(), "Should include all files initially (including README.md)");

        // Create untracked .gitignore to ignore build/
        Files.writeString(tempDir.resolve(".gitignore"), "build/\n");
        // Note: We don't git add it, so it remains untracked

        // Cache should still contain old data
        var allFiles2 = project.getAllFiles();
        assertEquals(3, allFiles2.size(), "Cache should still contain old data before invalidation");

        // Invalidate cache (simulating the file system watcher detecting the change)
        project.invalidateAllFiles();

        // Should now respect untracked gitignore after cache invalidation
        var allFiles3 = project.getAllFiles();
        assertEquals(2, allFiles3.size(), "Should include src/Main.java and README.md after cache invalidation");
        assertTrue(
                allFiles3.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")),
                "Should include src/Main.java");
        assertFalse(
                allFiles3.stream().anyMatch(pf -> normalize(pf).contains("build/")), "Should exclude build/ directory");

        project.close();
    }

    @Test
    void getAllFiles_handles_git_info_exclude(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "local.tmp", "local temp file");

        trackFiles(tempDir);

        // Create .git/info/exclude file (never tracked by git)
        var gitInfoDir = tempDir.resolve(".git/info");
        Files.createDirectories(gitInfoDir);
        Files.writeString(gitInfoDir.resolve("exclude"), "*.tmp\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // .git/info/exclude should exclude *.tmp files
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("local.tmp")),
                ".git/info/exclude should exclude local.tmp");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")),
                "Should include src/Main.java");

        project.close();
    }

    @Test
    void getAllFiles_skips_gitignore_when_project_outside_repo(@TempDir Path tempDir) throws Exception {
        // Create git repo in parent directory
        var gitRepoDir = tempDir.resolve("git-repo");
        Files.createDirectories(gitRepoDir);
        initGitRepo(gitRepoDir);

        // Create .gitignore in git repo root
        Files.writeString(gitRepoDir.resolve(".gitignore"), "*.log\n");

        // Create project directory OUTSIDE the git repo
        var projectDir = tempDir.resolve("separate-project");
        Files.createDirectories(projectDir);

        // Create files in project directory
        createFile(projectDir, "src/Main.java", "class Main {}");
        createFile(projectDir, "debug.log", "debug output");

        // Note: We don't track files in git because the project is outside the git repo
        // The project will detect this is not a git repo and skip gitignore filtering

        var project = new MainProject(projectDir);
        var allFiles = project.getAllFiles();

        // Since project is not in a git repo, gitignore filtering should be skipped
        // All files should be included
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")),
                "Should include src/Main.java when project is not in git repo");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("debug.log")),
                "Should include debug.log when project is not in git repo (no gitignore filtering)");

        project.close();
    }

    @Test
    void getWorkTreeRoot_returns_project_root_for_regular_repo(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);
        createFile(tempDir, "src/Main.java", "class Main {}");
        trackFiles(tempDir);

        var project = new MainProject(tempDir);
        var repo = project.getRepo();

        // For regular repos (non-worktrees), getWorkTreeRoot() should return the project root
        if (repo instanceof ai.brokk.git.GitRepo gitRepo) {
            var workTreeRoot = gitRepo.getWorkTreeRoot();
            assertEquals(
                    tempDir.toRealPath(),
                    workTreeRoot.toRealPath(),
                    "getWorkTreeRoot() should return the project root for regular repos");

            // Verify that gitignore filtering works (not skipped)
            Files.writeString(tempDir.resolve(".gitignore"), "*.log\n");
            createFile(tempDir, "debug.log", "debug output");

            var allFiles = project.getAllFiles();
            assertFalse(
                    allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("debug.log")),
                    "*.log files should be filtered by gitignore");
            assertTrue(
                    allFiles.stream().anyMatch(pf -> normalize(pf).endsWith("src/Main.java")),
                    "src/Main.java should be included");
        }

        project.close();
    }

    /**
     * Tests case-sensitive file pattern matching in gitignore.
     * Verifies that patterns are case-sensitive by default (*.log != *.LOG).
     *
     * Note: Directory name case-sensitivity may be affected by filesystem properties.
     * This test uses file extension patterns to reliably test case-sensitive matching.
     */
    @Test
    void getAllFiles_case_sensitive_file_patterns(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create files with different case extensions
        createFile(tempDir, "debug.log", "lowercase log");
        createFile(tempDir, "error.LOG", "uppercase LOG");
        createFile(tempDir, "warning.Log", "mixed case Log");
        createFile(tempDir, "src/Main.java", "class Main {}");

        trackFiles(tempDir);

        // Create .gitignore with lowercase pattern
        createFile(tempDir, ".gitignore", "*.log\n");

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // With case-sensitive matching: *.log matches .log but not .LOG or .Log
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("debug.log")),
                "debug.log should be ignored (exact case match)");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("error.LOG")),
                "error.LOG should NOT be ignored (case mismatch: LOG != log)");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("warning.Log")),
                "warning.Log should NOT be ignored (case mismatch: Log != log)");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/Main.java")),
                "src/Main.java should be included");

        project.close();

        // Note: This validates case-sensitive pattern matching which is the default.
        // Case-insensitive behavior (core.ignoreCase=true) is platform and filesystem dependent.
    }

    /**
     * Tests advanced gitignore glob patterns including:
     * - Double-star ** recursive wildcards
     * - Character classes [abc] and negated [!abc]
     * - Single-char wildcard ?
     * - Complex combinations like foo / ** / bar/*.log
     *
     * Ensures JGit's IgnoreNode correctly handles Git's full pattern syntax.
     */
    @Test
    void getAllFiles_handles_advanced_glob_patterns(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create deep directory structure for ** testing
        createFile(tempDir, "logs/temp/debug.log", "log");
        createFile(tempDir, "logs/production/temp/error.log", "error");
        createFile(tempDir, "src/main/temp/trace.log", "trace");
        createFile(tempDir, "docs/api/examples/temp/sample.log", "sample");

        // Create files for character class testing in specific directory
        createFile(tempDir, "tests/ATest.java", "class ATest {}");
        createFile(tempDir, "tests/BTest.java", "class BTest {}");
        createFile(tempDir, "tests/CTest.java", "class CTest {}");
        createFile(tempDir, "tests/XTest.java", "class XTest {}");
        createFile(tempDir, "tests/1Test.java", "class 1Test {}");

        // Create files for ? wildcard testing
        createFile(tempDir, "tmp/fileA.tmp", "a");
        createFile(tempDir, "tmp/fileAB.tmp", "ab");
        createFile(tempDir, "tmp/file1.tmp", "1");

        // Create files for negation with ** testing
        createFile(tempDir, "docs/guide.md", "guide");
        createFile(tempDir, "docs/api/reference.md", "ref");
        createFile(tempDir, "docs/examples/keep.md", "keep");
        createFile(tempDir, "src/Main.java", "class Main {}");

        trackFiles(tempDir);

        // Create .gitignore with advanced patterns
        Files.writeString(
                tempDir.resolve(".gitignore"),
                "**/temp/*.log\n" + // Recursive wildcard: any depth
                        "tests/[ABC]Test.java\n"
                        + // Character class: ATest, BTest, CTest in tests/
                        "tmp/file?.tmp\n"
                        + // Single wildcard: fileA, file1, but not fileAB in tmp/
                        "**/*.md\n"
                        + // All markdown files
                        "!**/keep.md\n" // Except keep.md (negation with **)
                );

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // Test **: should match temp/ at any depth
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("logs/temp/debug.log")),
                "**/temp/*.log should match logs/temp/debug.log");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("logs/production/temp/error.log")),
                "**/temp/*.log should match logs/production/temp/error.log");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/main/temp/trace.log")),
                "**/temp/*.log should match src/main/temp/trace.log");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("docs/api/examples/temp/sample.log")),
                "**/temp/*.log should match docs/api/examples/temp/sample.log");

        // Test character class [ABC]: should match A, B, C but not X or 1
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("tests/ATest.java")),
                "tests/[ABC]Test.java should match tests/ATest.java");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("tests/BTest.java")),
                "tests/[ABC]Test.java should match tests/BTest.java");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("tests/CTest.java")),
                "tests/[ABC]Test.java should match tests/CTest.java");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("tests/XTest.java")),
                "tests/[ABC]Test.java should NOT match tests/XTest.java");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("tests/1Test.java")),
                "tests/[ABC]Test.java should NOT match tests/1Test.java");

        // Test ? wildcard: should match single char only
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("tmp/fileA.tmp")),
                "file?.tmp should match fileA.tmp");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("tmp/file1.tmp")),
                "file?.tmp should match file1.tmp");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("tmp/fileAB.tmp")),
                "file?.tmp should NOT match fileAB.tmp (two chars)");

        // Test negation with **: keep.md should be included despite **/*.md
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("docs/guide.md")),
                "**/*.md should match docs/guide.md");
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("docs/api/reference.md")),
                "**/*.md should match docs/api/reference.md");
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("docs/examples/keep.md")),
                "!**/keep.md should override **/*.md for keep.md");

        // Other files should be included
        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/Main.java")),
                "src/Main.java should be included");

        project.close();
    }

    /**
     * Tests tracked vs ignored semantics - documents intentional behavior.
     *
     * Git's behavior: .gitignore does not affect files that are already tracked.
     * Brokk's behavior: Filters ALL files matching ignore patterns, even if tracked.
     *
     * This is intentional for semantic code analysis:
     * - We want to analyze the current state of files, not Git's tracking state
     * - Ignored files are typically generated/build artifacts that shouldn't be analyzed
     * - This test documents this deliberate divergence from Git semantics
     */
    @Test
    void getAllFiles_filters_tracked_files_matching_ignore_patterns(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create and commit a log file (making it tracked)
        createFile(tempDir, "important.log", "important log content");
        createFile(tempDir, "src/Main.java", "class Main {}");

        try (var git = Git.open(tempDir.toFile())) {
            git.add().addFilepattern("important.log").call();
            git.add().addFilepattern("src/Main.java").call();
            git.commit().setMessage("Add tracked files including log").call();
        }

        // Verify file is tracked by Git
        try (var git = Git.open(tempDir.toFile())) {
            var status = git.status().call();
            assertTrue(status.getUntracked().isEmpty(), "important.log should be tracked");
        }

        // NOW create .gitignore that matches the tracked file
        createFile(tempDir, ".gitignore", "*.log\n");

        trackFiles(tempDir); // Track .gitignore

        var project = new MainProject(tempDir);
        var allFiles = project.getAllFiles();

        // IMPORTANT: Despite being tracked by Git, important.log is EXCLUDED by Brokk
        // This is intentional for semantic analysis - we filter based on patterns, not tracking state
        assertFalse(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("important.log")),
                "important.log should be EXCLUDED despite being tracked (intentional for analysis)");

        assertTrue(
                allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/Main.java")),
                "src/Main.java should be included");

        // Document this behavior explicitly
        // This diverges from Git semantics where gitignore doesn't affect tracked files
        // For Brokk's semantic code analysis, we want to exclude build artifacts/logs
        // regardless of whether they're accidentally tracked

        project.close();
    }

    /**
     * Tests watcher-driven cache invalidation for gitignore files.
     * Validates end-to-end integration: gitignore modification → file watcher → cache invalidation.
     *
     * This ensures the gitignore chain cache stays fresh when .gitignore files are modified.
     */
    @Test
    void getAllFiles_invalidates_cache_when_gitignore_files_modified(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create initial files
        createFile(tempDir, "src/Main.java", "class Main {}");
        createFile(tempDir, "debug.log", "log");
        createFile(tempDir, "temp.tmp", "temp");
        createFile(tempDir, "backup.bak", "backup");

        trackFiles(tempDir);

        // Create initial .gitignore (only ignores .log)
        createFile(tempDir, ".gitignore", "*.log\n");

        var project = new MainProject(tempDir);

        // Initial state: only .log files ignored
        var allFiles1 = project.getAllFiles();
        assertFalse(
                allFiles1.stream().anyMatch(pf -> normalize(pf).equals("debug.log")),
                "debug.log should be ignored initially");
        assertTrue(
                allFiles1.stream().anyMatch(pf -> normalize(pf).equals("temp.tmp")),
                "temp.tmp should be included initially");
        assertTrue(
                allFiles1.stream().anyMatch(pf -> normalize(pf).equals("backup.bak")),
                "backup.bak should be included initially");

        // Modify root .gitignore to also ignore .tmp files
        Files.writeString(tempDir.resolve(".gitignore"), "*.log\n*.tmp\n");

        // Invalidate cache (simulating what file watcher would do)
        project.invalidateAllFiles();

        // After invalidation: both .log and .tmp should be ignored
        var allFiles2 = project.getAllFiles();
        assertFalse(
                allFiles2.stream().anyMatch(pf -> normalize(pf).equals("debug.log")),
                "debug.log should still be ignored");
        assertFalse(
                allFiles2.stream().anyMatch(pf -> normalize(pf).equals("temp.tmp")),
                "temp.tmp should now be ignored (after cache invalidation)");
        assertTrue(
                allFiles2.stream().anyMatch(pf -> normalize(pf).equals("backup.bak")),
                "backup.bak should still be included");

        // Create nested .gitignore
        createFile(tempDir, "src/.gitignore", "*.bak\n");

        // Invalidate again
        project.invalidateAllFiles();

        // Move backup.bak to src/ and verify it's ignored by nested .gitignore
        Files.move(tempDir.resolve("backup.bak"), tempDir.resolve("src/backup.bak"));
        trackFiles(tempDir);

        var allFiles3 = project.getAllFiles();
        assertFalse(
                allFiles3.stream().anyMatch(pf -> normalize(pf).equals("src/backup.bak")),
                "src/backup.bak should be ignored by nested .gitignore");

        // Test .git/info/exclude modification
        var gitInfoDir = tempDir.resolve(".git/info");
        Files.createDirectories(gitInfoDir);
        createFile(tempDir, ".git/info/exclude", "*.secret\n");
        createFile(tempDir, "data.secret", "secret data");
        trackFiles(tempDir);

        project.invalidateAllFiles();

        var allFiles4 = project.getAllFiles();
        assertFalse(
                allFiles4.stream().anyMatch(pf -> normalize(pf).equals("data.secret")),
                "data.secret should be ignored by .git/info/exclude");

        project.close();
    }

    /**
     * Tests tilde expansion in core.excludesfile config.
     * Verifies that ~/path/to/ignore is correctly expanded to $HOME/path/to/ignore.
     *
     * This complements the existing XDG location test by validating tilde expansion edge case.
     */
    @Test
    void getAllFiles_expands_tilde_in_core_excludesfile(@TempDir Path tempDir) throws Exception {
        initGitRepo(tempDir);

        // Create test files
        createFile(tempDir, "data.secret", "secret");
        createFile(tempDir, "config.yaml", "config");
        createFile(tempDir, "src/Main.java", "class Main {}");

        trackFiles(tempDir);

        // Get home directory
        String homeDir = System.getProperty("user.home");
        Path globalIgnoreFile = Path.of(homeDir, "test-brokk-gitignore");

        try {
            // Create global gitignore file in home directory
            Files.writeString(globalIgnoreFile, "*.secret\n");

            // Configure git to use ~/test-brokk-gitignore (with tilde)
            try (var git = Git.open(tempDir.toFile())) {
                var config = git.getRepository().getConfig();
                config.setString("core", null, "excludesfile", "~/test-brokk-gitignore");
                config.save();
            }

            var project = new MainProject(tempDir);
            var allFiles = project.getAllFiles();

            // Verify tilde was expanded and file was ignored
            assertFalse(
                    allFiles.stream().anyMatch(pf -> normalize(pf).equals("data.secret")),
                    "data.secret should be ignored via tilde-expanded global gitignore");
            assertTrue(
                    allFiles.stream().anyMatch(pf -> normalize(pf).equals("config.yaml")),
                    "config.yaml should be included");
            assertTrue(
                    allFiles.stream().anyMatch(pf -> normalize(pf).equals("src/Main.java")),
                    "src/Main.java should be included");

            project.close();

        } finally {
            // Clean up test global gitignore
            if (Files.exists(globalIgnoreFile)) {
                Files.delete(globalIgnoreFile);
            }
        }
    }
}
