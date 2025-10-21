package io.github.jbellis.brokk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Languages;
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
class ProjectFilteringTest {

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
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("src/Main.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("build/Generated.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("test/Test.java")));

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
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("src/Main.java")));
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().contains(".class")));
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().contains(".log")));

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
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("src/Main.java")));
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().startsWith("build/")));
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().startsWith("target/")));

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
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("app.py")));
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("important.pyc")));
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().equals("app.pyc")));

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
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("src/Main.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("build/keep/Important.java")));
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().equals("build/Generated.java")));

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
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().equals("logs/debug.log")));
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().equals("logs/error.log")));
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("logs/important/critical.log")));
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("logs/important/audit.log")));

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
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().equals("app.pyc")));
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().equals("src/module.pyc")));
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("src/important/critical.pyc")));

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

        // Invalidate cache to force re-filtering with new build details
        project.invalidateAllFiles();

        var allFiles = project.getAllFiles();

        // Should exclude baseline-excluded directories
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("src/Main.java")));
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().startsWith("generated/")));
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().startsWith("vendor/")));

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

        // Invalidate cache to force re-filtering
        project.invalidateAllFiles();

        var allFiles = project.getAllFiles();

        // Should exclude both gitignore and baseline exclusions
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("src/Main.java")));
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().startsWith("build/")));
        assertFalse(allFiles.stream().anyMatch(pf -> pf.toString().startsWith("vendor/")));

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
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("src/Main.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("build/Generated.java")));

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
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("src/Main.java")));
        assertTrue(allFiles.stream().anyMatch(pf -> pf.toString().equals("build/Generated.java")));

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
        assertTrue(javaFiles.stream().anyMatch(p -> p.endsWith("Main.java")));

        // Get Python files
        var pythonFiles = project.getAnalyzableFiles(Languages.PYTHON);
        assertEquals(1, pythonFiles.size());
        assertTrue(pythonFiles.stream().anyMatch(p -> p.endsWith("app.py")));

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
        assertTrue(javaFiles.stream().anyMatch(p -> p.endsWith("src/Main.java")));
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
        project.invalidateAllFiles();

        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);

        // Should only include src/Main.java
        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(p -> p.endsWith("src/Main.java")));
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
        project.invalidateAllFiles();

        var javaFiles = project.getAnalyzableFiles(Languages.JAVA);

        // Should only include src/Main.java (filtered by gitignore, baseline, and extension)
        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(p -> p.endsWith("src/Main.java")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.toString().contains("build/")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.toString().contains("vendor/")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.endsWith(".py")));

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
        assertTrue(javaFiles.stream().anyMatch(p -> p.endsWith("src/Main.java")));
        assertTrue(javaFiles.stream().anyMatch(p -> p.endsWith("build/keep/Important.java")));
        assertFalse(javaFiles.stream().anyMatch(p -> p.endsWith("build/Generated.java")));

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
        assertTrue(javaFiles.stream().anyMatch(p -> p.endsWith("Main.java")));

        var pythonFiles = project.getAnalyzableFiles(Languages.PYTHON);
        assertEquals(1, pythonFiles.size());
        assertTrue(pythonFiles.stream().anyMatch(p -> p.endsWith("app.py")));

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
        assertTrue(javaFiles.stream().anyMatch(p -> p.endsWith("App.java")));
        assertTrue(javaFiles.stream().anyMatch(p -> p.endsWith("AppTest.java")));
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
        var javaFiles = project.getFiles(Languages.JAVA);

        // getFiles() should also respect filtering
        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(pf -> pf.toString().endsWith("Main.java")));

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
        var javaFiles = project.getFiles(Languages.JAVA);

        // getFiles() should respect gitignore
        assertEquals(1, javaFiles.size());
        assertTrue(javaFiles.stream().anyMatch(pf -> pf.toString().endsWith("src/Main.java")));
        assertFalse(javaFiles.stream().anyMatch(pf -> pf.toString().contains("build/")));

        project.close();
    }
}
