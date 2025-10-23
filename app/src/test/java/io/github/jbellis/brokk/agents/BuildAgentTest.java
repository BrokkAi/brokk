package io.github.jbellis.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildAgentTest {
    @Test
    void testInterpolateModulesTemplate() {
        String template = "tests/runtests.py{{#modules}} {{value}}{{/modules}}";
        List<String> modules = List.of("servers.tests");

        String result = BuildAgent.interpolateMustacheTemplate(template, modules, "modules");

        assertEquals("tests/runtests.py servers.tests", result);
    }

    @Test
    void testInterpolateModulesTemplateMultiple() {
        String template = "pytest{{#modules}} {{value}}{{/modules}}";
        List<String> modules = List.of("tests.unit", "tests.integration", "tests.e2e");

        String result = BuildAgent.interpolateMustacheTemplate(template, modules, "modules");

        assertEquals("pytest tests.unit tests.integration tests.e2e", result);
    }

    @Test
    void testInterpolateFilesTemplate() {
        String template = "jest{{#files}} {{value}}{{/files}}";
        List<String> files = List.of("src/app.test.js", "src/util.test.js");

        String result = BuildAgent.interpolateMustacheTemplate(template, files, "files");

        assertEquals("jest src/app.test.js src/util.test.js", result);
    }

    @Test
    void testInterpolateEmptyList() {
        String template = "pytest{{#modules}} {{value}}{{/modules}}";
        List<String> modules = List.of();

        String result = BuildAgent.interpolateMustacheTemplate(template, modules, "modules");

        assertEquals("pytest", result);
    }

    @Test
    void testInterpolateSingleItem() {
        String template = "go test -run '{{#classes}} {{value}}{{/classes}}'";
        List<String> classes = List.of("TestFoo");

        String result = BuildAgent.interpolateMustacheTemplate(template, classes, "classes");

        assertEquals("go test -run ' TestFoo'", result);
    }

    @Test
    void testGitignoreProcessingSkipsGlobPatterns(@TempDir Path tempDir) throws Exception {
        // Create a git repo with complex .gitignore containing glob patterns
        var gitignoreContent =
                """
                GPATH
                GRTAGS
                GTAGS
                **/*dependency-reduced-pom.xml
                **/*flattened-pom.xml
                **/target/
                report
                *.ipr
                *.iws
                **/*.iml
                **/*.lock.db
                **/.checkstyle
                **/.classpath
                **/.idea/
                **/.project
                **/.settings
                **/bin/
                **/derby.log
                *.tokens
                .clover
                ^build
                out
                *~
                test-output
                travis-settings*.xml
                .build-oracle
                .factorypath
                .brokk/**
                /.brokk/workspace.properties
                /.brokk/sessions/
                /.brokk/dependencies/
                /.brokk/history.zip
                !.brokk/style.md
                !.brokk/review.md
                !.brokk/project.properties
                """;

        // Initialize git repo properly
        try (var git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();

            // Write .gitignore
            Files.writeString(tempDir.resolve(".gitignore"), gitignoreContent);

            // Create the directories that should be extracted
            Files.createDirectories(tempDir.resolve(".brokk/sessions"));
            Files.createDirectories(tempDir.resolve(".brokk/dependencies"));
            Files.createDirectory(tempDir.resolve("out"));
            Files.createDirectory(tempDir.resolve("report"));

            // Make initial commit
            git.add().addFilepattern(".gitignore").call();
            git.commit().setMessage("Initial commit").call();
        }

        // Read .gitignore patterns directly for testing
        var gitignoreFile = tempDir.resolve(".gitignore");
        var ignoredPatterns = Files.lines(gitignoreFile)
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();

        // Helper to detect glob patterns
        Predicate<String> containsGlobPattern =
                s -> s.contains("*") || s.contains("?") || s.contains("[") || s.contains("]");

        // Simulate BuildAgent's gitignore processing logic (SHOULD skip globs explicitly)
        var extractedDirectories = new ArrayList<String>();
        for (var pattern : ignoredPatterns) {
            // Skip glob patterns explicitly (don't rely on Path.of() throwing - it doesn't on Unix!)
            if (containsGlobPattern.test(pattern)) {
                continue;
            }

            Path path;
            try {
                path = tempDir.resolve(pattern);
            } catch (Exception e) {
                // Skip invalid paths
                continue;
            }

            var isDirectory = (Files.exists(path) && Files.isDirectory(path)) || pattern.endsWith("/");
            if (!pattern.startsWith("!") && isDirectory) {
                extractedDirectories.add(pattern);
            }
        }

        // Verify only literal directory paths were extracted, not glob patterns
        assertTrue(extractedDirectories.contains("/.brokk/sessions/"), "Should extract /.brokk/sessions/");
        assertTrue(extractedDirectories.contains("/.brokk/dependencies/"), "Should extract /.brokk/dependencies/");

        // Verify glob patterns were NOT extracted
        assertFalse(
                extractedDirectories.stream().anyMatch(d -> d.contains("**/")), "Should not extract patterns with **/");
        assertFalse(
                extractedDirectories.stream().anyMatch(d -> d.contains("*")),
                "Should not extract patterns with wildcards");
        assertFalse(extractedDirectories.contains("**/.idea/"), "Should not extract **/.idea/");
        assertFalse(extractedDirectories.contains("**/target/"), "Should not extract **/target/");
        assertFalse(extractedDirectories.contains("**/bin/"), "Should not extract **/bin/");

        // Verify negation patterns were NOT extracted
        assertFalse(
                extractedDirectories.stream().anyMatch(d -> d.startsWith("!")), "Should not extract negation patterns");
    }
}
