package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.util.Environment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ai.brokk.util.Environment.shellCommandRunnerFactory")
class BuildAgentTest {
    private static final Logger logger = LogManager.getLogger(BuildAgentTest.class);

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
            git.commit().setSign(false).setMessage("Initial commit").call();
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
            } catch (IllegalArgumentException e) {
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

    @Test
    void testIsDirectoryIgnoredDoesNotExcludeEmptyOrNonCodeDirectories(@TempDir Path tempDir) throws Exception {
        // Initialize git repo
        try (var git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();
            // Ensure JGit does not attempt to GPG-sign commits in tests
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();

            // Create .gitignore that only excludes build/
            Files.writeString(tempDir.resolve(".gitignore"), "build/\n");

            // Create empty directory (should NOT be ignored)
            Files.createDirectories(tempDir.resolve("tests/fixtures"));

            // Create directory with only non-code files (should NOT be ignored)
            var docsDir = tempDir.resolve("docs/images");
            Files.createDirectories(docsDir);
            Files.writeString(docsDir.resolve("diagram.png"), "fake image data");

            // Create directory with code that should be included
            Files.createDirectories(tempDir.resolve("src"));
            Files.writeString(tempDir.resolve("src/Main.java"), "class Main {}");

            // Create actually gitignored directory (SHOULD be ignored)
            Files.createDirectories(tempDir.resolve("build/output"));
            Files.writeString(tempDir.resolve("build/output/Generated.java"), "class Generated {}");

            // Commit files
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit").call();
        }

        // Create project and test isGitignored method
        var project = MainProject.forTests(tempDir);

        // Verify empty directory is NOT ignored
        assertFalse(project.isGitignored(Path.of("tests/fixtures")), "Empty directory should NOT be ignored");
        assertFalse(project.isGitignored(Path.of("tests")), "Parent of empty directory should NOT be ignored");

        // Verify directory with only non-code files is NOT ignored
        assertFalse(
                project.isGitignored(Path.of("docs/images")),
                "Directory with only non-code files should NOT be ignored");
        assertFalse(project.isGitignored(Path.of("docs")), "Parent of non-code directory should NOT be ignored");

        // Verify directory with code is NOT ignored
        assertFalse(project.isGitignored(Path.of("src")), "Directory with code should NOT be ignored");

        // Verify actually gitignored directory IS ignored
        assertTrue(project.isGitignored(Path.of("build")), "Gitignored directory SHOULD be ignored");
        assertTrue(project.isGitignored(Path.of("build/output")), "Nested gitignored directory SHOULD be ignored");

        project.close();
    }

    void testInterpolatePythonVersionVariable() {
        String template = "python{{pyver}} -m pytest";
        List<String> empty = List.of();

        String result = BuildAgent.interpolateMustacheTemplate(template, empty, "modules", "3.11");

        assertEquals("python3.11 -m pytest", result);
    }

    @Test
    void testInterpolatePythonVersionWithoutVariable() {
        String template = "pytest";
        List<String> empty = List.of();

        String result = BuildAgent.interpolateMustacheTemplate(template, empty, "modules", "3.11");

        assertEquals("pytest", result);
    }

    @Test
    void testInterpolatePythonVersionEmpty() {
        String template = "pytest --pyver={{pyver}}";
        List<String> empty = List.of();

        String result = BuildAgent.interpolateMustacheTemplate(template, empty, "modules", null);

        assertEquals("pytest --pyver=", result);
    }

    @Test
    void testInterpolateModulesAndPythonVersion() {
        String template = "python{{pyver}} tests/runtests.py{{#modules}} {{value}}{{/modules}}";
        List<String> modules = List.of("tests.unit", "tests.integration");

        String result = BuildAgent.interpolateMustacheTemplate(template, modules, "modules", "3.10");

        assertEquals("python3.10 tests/runtests.py tests.unit tests.integration", result);
    }

    @Test
    void testInterpolateEmptyPythonVersion() {
        String template = "uv run {{#modules}}{{value}}{{/modules}}";
        List<String> modules = List.of("tests.e2e");

        String result = BuildAgent.interpolateMustacheTemplate(template, modules, "modules", "");

        assertEquals("uv run tests.e2e", result);
    }

    @Test
    void testInterpolateDotSyntaxRendersRawStrings() {
        // Regression test: {{.}} should render the raw string value, not Element@...
        String template = "pytest {{#files}}{{.}}{{/files}}";
        List<String> files = List.of("tests/test_foo.py", "tests/test_bar.py");

        String result = BuildAgent.interpolateMustacheTemplate(template, files, "files");

        // Must contain actual file paths
        assertTrue(result.contains("tests/test_foo.py"), "Result should contain first file path");
        assertTrue(result.contains("tests/test_bar.py"), "Result should contain second file path");
        // Must NOT contain Element@ toString representation
        assertFalse(result.contains("Element@"), "Result should not contain Element@ wrapper toString");
    }

    @Test
    void testInterpolateDotSyntaxWithSeparator() {
        // Regression test: {{.}} with {{^last}} separator should work correctly
        String template = "go test -run '{{#classes}}{{.}}{{^last}}|{{/last}}{{/classes}}'";
        List<String> classes = List.of("TestFoo", "TestBar", "TestBaz");

        String result = BuildAgent.interpolateMustacheTemplate(template, classes, "classes");

        // Should produce pipe-separated list without trailing separator
        assertEquals("go test -run 'TestFoo|TestBar|TestBaz'", result);
        // Must NOT contain Element@ toString representation
        assertFalse(result.contains("Element@"), "Result should not contain Element@ wrapper toString");
    }

    @Test
    void testInterpolateDotSyntaxSingleItem() {
        // Regression test: {{.}} with single item should work and not have trailing separator
        String template = "mvn test -Dtest={{#classes}}{{.}}{{^last}},{{/last}}{{/classes}}";
        List<String> classes = List.of("MyTest");

        String result = BuildAgent.interpolateMustacheTemplate(template, classes, "classes");

        assertEquals("mvn test -Dtest=MyTest", result);
        assertFalse(result.contains("Element@"), "Result should not contain Element@ wrapper toString");
    }

    @Test
    void testInterpolateDotSyntaxWithSpaceSeparator() {
        // Regression test: common pattern for file-based test runners
        String template = "jest {{#files}}{{.}}{{^last}} {{/last}}{{/files}}";
        List<String> files = List.of("src/app.test.js", "src/util.test.js");

        String result = BuildAgent.interpolateMustacheTemplate(template, files, "files");

        assertEquals("jest src/app.test.js src/util.test.js", result);
        assertFalse(result.contains("Element@"), "Result should not contain Element@ wrapper toString");
    }

    @Test
    void testReportBuildDetailsPreservesExistingPatterns(@TempDir Path tempDir) throws Exception {
        // Create a project with existing exclusion patterns
        Files.createDirectory(tempDir.resolve("src"));
        Files.createDirectory(tempDir.resolve("build"));
        var testProject = new TestProject(tempDir);
        testProject.setExclusionPatterns(Set.of("*.svg", "*.png", "build"));

        // Create a BuildAgent - we don't need LLM for this test
        var agent = new BuildAgent(testProject, null, null);

        // Call reportBuildDetails with new patterns from "LLM"
        // This simulates what happens when BuildAgent runs again
        agent.reportBuildDetails(
                "mvn compile",
                "mvn test",
                "mvn test -Dtest={{#classes}}{{value}}{{/classes}}",
                List.of("target"), // LLM suggests target directory
                List.of("*.gif") // LLM suggests gif pattern
                );

        // Verify existing patterns are preserved AND new patterns are added
        var reportedDetails = agent.getReportedDetails();
        assert reportedDetails != null;
        var finalPatterns = reportedDetails.exclusionPatterns();

        // Existing patterns should be preserved
        assertTrue(finalPatterns.contains("*.svg"), "Existing *.svg pattern should be preserved");
        assertTrue(finalPatterns.contains("*.png"), "Existing *.png pattern should be preserved");
        assertTrue(finalPatterns.contains("build"), "Existing build pattern should be preserved");

        // New LLM patterns should be added
        assertTrue(finalPatterns.contains("*.gif"), "New *.gif pattern should be added");
        // Note: target directory is filtered out because it doesn't exist in tempDir

        // Verify llmAddedPatterns only contains patterns from this run
        var llmPatterns = agent.getLlmAddedPatterns();
        assertTrue(llmPatterns.contains("*.gif"), "LLM patterns should include *.gif");
        assertFalse(llmPatterns.contains("*.svg"), "LLM patterns should NOT include existing *.svg");
        assertFalse(llmPatterns.contains("build"), "LLM patterns should NOT include existing build");
    }

    @Test
    void testRemoveGitignoreDuplicatesFiltersRedundantDirectories(@TempDir Path tempDir) throws Exception {
        try (var git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();

            Files.writeString(tempDir.resolve(".gitignore"), "node_modules/\ntarget/\n");
            Files.createDirectories(tempDir.resolve("node_modules"));
            Files.createDirectories(tempDir.resolve("target"));
            Files.createDirectories(tempDir.resolve("build"));

            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Initial").call();
        }

        var project = MainProject.forTests(tempDir);
        var agent = new BuildAgent(project, null, null);

        var patterns = Set.of(
                "node_modules", // Gitignored - should be removed
                "target", // Gitignored - should be removed
                "build", // Not gitignored - should be kept
                "*.svg", // File pattern - should be kept
                "**/*.generated" // Glob pattern - should be kept
                );

        var deduplicated = agent.removeGitignoreDuplicates(patterns);

        assertFalse(deduplicated.contains("node_modules"), "Gitignored dir should be removed");
        assertFalse(deduplicated.contains("target"), "Gitignored dir should be removed");
        assertTrue(deduplicated.contains("build"), "Non-gitignored dir should be kept");
        assertTrue(deduplicated.contains("*.svg"), "File pattern should be kept");
        assertTrue(deduplicated.contains("**/*.generated"), "Glob pattern should be kept");

        project.close();
    }

    @Test
    void testReportBuildDetailsDeduplicatesGitignorePatterns(@TempDir Path tempDir) throws Exception {
        try (var git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();

            Files.writeString(tempDir.resolve(".gitignore"), "node_modules/\n");
            Files.createDirectories(tempDir.resolve("node_modules"));
            Files.createDirectories(tempDir.resolve("build"));

            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Initial").call();
        }

        var project = MainProject.forTests(tempDir);
        var agent = new BuildAgent(project, null, null);

        agent.reportBuildDetails(
                "npm run build",
                "npm test",
                "npm test {{#files}}{{value}}{{/files}}",
                List.of("node_modules", "build"),
                List.of("*.map"));

        var details = agent.getReportedDetails();
        assert details != null;
        var finalPatterns = details.exclusionPatterns();

        assertFalse(finalPatterns.contains("node_modules"), "Gitignored directory should not be in final patterns");
        assertTrue(finalPatterns.contains("build"), "Non-gitignored directory should be in final patterns");
        assertTrue(finalPatterns.contains("*.map"), "File pattern should be in final patterns");

        var llmPatterns = agent.getLlmAddedPatterns();
        assertFalse(llmPatterns.contains("node_modules"), "Deduplicated pattern should not be in LLM tracking");
        assertTrue(llmPatterns.contains("build"), "Kept pattern should be in LLM tracking");

        project.close();
    }

    @Test
    void testRunExplicitCommandSuccessStreamsAndClearsBuildError(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);
            project.setBuildDetails(
                    new BuildAgent.BuildDetails("lint", "testAll", "testSome", Set.of(), java.util.Map.of()));
            var io = new TestConsoleIO();
            var cm = new TestContextManager(project, io, Set.of(), new ai.brokk.testutil.TestAnalyzer());
            var ctx = cm.liveContext();

            String cmd = "explicit-success";
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                assertEquals(cmd, command);
                assertTrue(timeout != null && !timeout.isNegative());
                outputConsumer.accept("line1");
                return "ok";
            };

            var updated = BuildAgent.runExplicitCommand(ctx, cmd, project.awaitBuildDetails());

            assertTrue(updated.getBuildError().isBlank(), "Build error should be blank on success");
            assertTrue(io.getOutputLog().contains(cmd), "Console output should contain the command banner");
            assertTrue(io.getOutputLog().contains("line1"), "Console output should contain streamed output");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testRunExplicitCommandFailureSetsBuildErrorAndStreams(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);
            project.setBuildDetails(
                    new BuildAgent.BuildDetails("lint", "testAll", "testSome", Set.of(), java.util.Map.of()));
            var io = new TestConsoleIO();
            var cm = new TestContextManager(project, io, Set.of(), new ai.brokk.testutil.TestAnalyzer());
            var ctx = cm.liveContext();

            String cmd = "explicit-failure";
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                outputConsumer.accept("some output");
                throw new Environment.FailureException("boom", "stdout:\nfail", 1);
            };

            var updated = BuildAgent.runExplicitCommand(ctx, cmd, project.awaitBuildDetails());

            assertFalse(updated.getBuildError().isBlank(), "Build error should be non-blank on failure");
            assertTrue(io.getOutputLog().contains(cmd), "Console output should contain the command banner");
            assertTrue(io.getOutputLog().contains("some output"), "Console output should contain streamed output");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testRunExplicitCommandFailureNullOutputDoesNotEmbedNullLiteral(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);
            project.setBuildDetails(
                    new BuildAgent.BuildDetails("lint", "testAll", "testSome", Set.of(), java.util.Map.of()));
            var io = new TestConsoleIO();
            var cm = new TestContextManager(project, io, Set.of(), new ai.brokk.testutil.TestAnalyzer());
            var ctx = cm.liveContext();

            String cmd = "explicit-failure-null-output";
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                outputConsumer.accept("some output");
                throw new Environment.SubprocessException(null, "ignored") {
                    @Override
                    public String getOutput() {
                        return null;
                    }
                };
            };

            var updated = BuildAgent.runExplicitCommand(ctx, cmd, project.awaitBuildDetails());

            assertFalse(updated.getBuildError().contains("null"), "Build error must not contain the literal 'null'");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testRunExplicitCommandBlankClearsPreviousBuildError(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "x");
        var project = new TestProject(tempDir);
        project.setBuildDetails(
                new BuildAgent.BuildDetails("lint", "testAll", "testSome", Set.of(), java.util.Map.of()));

        var io = new TestConsoleIO();
        var cm = new TestContextManager(project, io, Set.of(), new ai.brokk.testutil.TestAnalyzer());
        var ctx = cm.liveContext();

        var ctxWithError = ctx.withBuildResult(false, "previous failure");

        var updated = BuildAgent.runExplicitCommand(ctxWithError, "   ", project.awaitBuildDetails());

        assertTrue(updated.getBuildError().isBlank(), "Blank command should clear any existing build error");
        assertTrue(io.getOutputLog().contains("No explicit command specified, skipping."), "Should log skip message");
    }

    @Test
    void testInterpolateMustacheTemplateThrowsOnUnsupportedTag() {
        // Template with unsupported variable {{python_version}} instead of {{pyver}}
        String template = "pytest --python={{python_version}}";

        var ex = assertThrows(IllegalArgumentException.class, () -> {
            BuildAgent.interpolateMustacheTemplate(template, List.of(), "files");
        });

        assertTrue(ex.getMessage().contains("python_version"), "Error should mention the unsupported tag");
        assertTrue(ex.getMessage().contains("Allowed"), "Error should mention allowed tags");
    }

    @Test
    void testInterpolateMustacheTemplateThrowsOnUnsupportedSectionTag() {
        // Template with unsupported section {{#targets}} instead of {{#files}}, {{#classes}}, etc.
        String template = "pytest {{#targets}}{{value}}{{/targets}}";

        var ex = assertThrows(IllegalArgumentException.class, () -> {
            BuildAgent.interpolateMustacheTemplate(template, List.of(), "files");
        });

        assertTrue(ex.getMessage().contains("targets"), "Error should mention the unsupported tag");
        assertTrue(ex.getMessage().contains("Allowed"), "Error should mention allowed tags");
    }

    @Test
    void testInterpolateMustacheTemplateAllowsValidTags() {
        // All these should NOT throw - they use valid tags
        // Test with files section
        String result1 = BuildAgent.interpolateMustacheTemplate(
                "pytest {{#files}}{{value}}{{^last}} {{/last}}{{/files}}", List.of("a.py", "b.py"), "files");
        assertEquals("pytest a.py b.py", result1);

        // Test with pyver
        String result2 =
                BuildAgent.interpolateMustacheTemplate("python{{pyver}} -m pytest", List.of(), "modules", "3.11");
        assertEquals("python3.11 -m pytest", result2);

        // Test with dot syntax
        String result3 = BuildAgent.interpolateMustacheTemplate(
                "go test {{#classes}}{{.}}{{/classes}}", List.of("TestFoo"), "classes");
        assertEquals("go test TestFoo", result3);

        // Test with index, first, last
        String result4 = BuildAgent.interpolateMustacheTemplate(
                "{{#modules}}{{index}}:{{value}}{{^last}},{{/last}}{{/modules}}", List.of("a", "b"), "modules");
        assertEquals("0:a,1:b", result4);
    }

    @Test
    void testReportBuildDetailsThrowsToolCallExceptionOnUnsupportedTag(@TempDir Path tempDir) throws Exception {
        Files.createDirectory(tempDir.resolve("src"));
        var testProject = new TestProject(tempDir);

        var agent = new BuildAgent(testProject, null, null);

        // testSomeCommand with unsupported {{python_version}} tag
        var ex = assertThrows(ToolRegistry.ToolCallException.class, () -> {
            agent.reportBuildDetails(
                    "mvn compile",
                    "mvn test",
                    "pytest --python={{python_version}} {{#files}}{{value}}{{/files}}",
                    List.of(),
                    List.of());
        });

        assertEquals(ToolExecutionResult.Status.REQUEST_ERROR, ex.status(), "Should be REQUEST_ERROR status");
        assertTrue(ex.getMessage().contains("testSomeCommand"), "Error should mention the field name");
        assertTrue(ex.getMessage().contains("python_version"), "Error should mention the unsupported tag");
        assertTrue(ex.getMessage().contains("Allowed"), "Error should mention allowed tags");
    }

    @Test
    void testReportBuildDetailsThrowsOnUnsupportedSectionInTestAll(@TempDir Path tempDir) throws Exception {
        Files.createDirectory(tempDir.resolve("src"));
        var testProject = new TestProject(tempDir);

        var agent = new BuildAgent(testProject, null, null);

        // testAllCommand with unsupported {{#targets}} section
        var ex = assertThrows(ToolRegistry.ToolCallException.class, () -> {
            agent.reportBuildDetails(
                    "mvn compile",
                    "mvn test {{#targets}}{{value}}{{/targets}}",
                    "mvn test -Dtest={{#classes}}{{value}}{{/classes}}",
                    List.of(),
                    List.of());
        });

        assertEquals(ToolExecutionResult.Status.REQUEST_ERROR, ex.status());
        assertTrue(ex.getMessage().contains("testAllCommand"), "Error should mention the field name");
        assertTrue(ex.getMessage().contains("targets"), "Error should mention the unsupported tag");
    }

    @Test
    void testReportBuildDetailsAcceptsValidTemplates(@TempDir Path tempDir) throws Exception {
        Files.createDirectory(tempDir.resolve("src"));
        var testProject = new TestProject(tempDir);

        var agent = new BuildAgent(testProject, null, null);

        // Should not throw - all tags are valid
        String result = agent.reportBuildDetails(
                "mvn compile",
                "mvn test",
                "mvn test -Dtest={{#classes}}{{value}}{{^last}},{{/last}}{{/classes}}",
                List.of(),
                List.of());

        assertEquals("Build details report received and processed.", result);
    }

    @Test
    void testExtractMustacheTagsHandlesVariousForms() {
        // Test various Mustache tag forms
        var tags1 = BuildAgent.extractMustacheTags("{{name}} {{{triple}}} {{#section}}{{/section}} {{^inverted}}");
        assertTrue(tags1.contains("name"));
        assertTrue(tags1.contains("triple"));
        assertTrue(tags1.contains("section"));
        assertTrue(tags1.contains("inverted"));

        // Test with whitespace
        var tags2 = BuildAgent.extractMustacheTags("{{ name }} {{# section }}{{/ section }}");
        assertTrue(tags2.contains("name"));
        assertTrue(tags2.contains("section"));

        // Test partials and comments are marked as unsupported
        var tags3 = BuildAgent.extractMustacheTags("{{>partial}} {{!comment}}");
        assertTrue(tags3.contains(">partial"), "Partial should be marked with prefix");
        assertTrue(tags3.contains("!comment"), "Comment should be marked with prefix");

        // Test empty template
        var tags4 = BuildAgent.extractMustacheTags("");
        assertTrue(tags4.isEmpty());

        // Test template with no tags
        var tags5 = BuildAgent.extractMustacheTags("plain text without mustache");
        assertTrue(tags5.isEmpty());

        // Test delimiter-change tags are detected
        var tags6 = BuildAgent.extractMustacheTags("{{= <% %> =}}<%name%>{{other}}");
        assertTrue(tags6.stream().anyMatch(t -> t.startsWith("=")), "Delimiter change should be detected");
        assertTrue(tags6.contains("other"), "Regular tags should still be found");

        // Test empty delimiter change
        var tags7 = BuildAgent.extractMustacheTags("{{==}}");
        assertTrue(tags7.stream().anyMatch(t -> t.startsWith("=")), "Empty delimiter change should be detected");
    }

    @Test
    void testInterpolateMustacheTemplateThrowsOnDelimiterChangeTag() {
        // Template with delimiter-change tag {{= <% %> =}} should be rejected
        String template = "{{= <% %> =}}<%#files%><%value%><%/files%>";

        var ex = assertThrows(IllegalArgumentException.class, () -> {
            BuildAgent.interpolateMustacheTemplate(template, List.of("a.py"), "files");
        });

        assertTrue(ex.getMessage().contains("="), "Error should mention the delimiter change tag");
        assertTrue(ex.getMessage().contains("Allowed"), "Error should mention allowed tags");
    }

    @Test
    void testInterpolateMustacheTemplateAllowsSpecificListKeyEvenIfNotCanonical() {
        // Regression test: interpolateCommandWithPythonVersion uses listKey="unused"
        // The validator must allow the specific listKey passed to the method, not just canonical ones
        String template = "cmd {{#unused}}{{.}}{{/unused}}";
        List<String> items = List.of("x");

        // Should NOT throw - "unused" is the listKey for this call, so it must be allowed
        String result = BuildAgent.interpolateMustacheTemplate(template, items, "unused", null);

        assertEquals("cmd x", result);
    }

    @Test
    void testReportBuildDetailsThrowsOnDelimiterChangeTag(@TempDir Path tempDir) throws Exception {
        Files.createDirectory(tempDir.resolve("src"));
        var testProject = new TestProject(tempDir);

        var agent = new BuildAgent(testProject, null, null);

        // testSomeCommand with delimiter-change tag
        var ex = assertThrows(ToolRegistry.ToolCallException.class, () -> {
            agent.reportBuildDetails(
                    "mvn compile",
                    "mvn test",
                    "{{= <% %> =}}mvn test -Dtest=<%#classes%><%value%><%/classes%>",
                    List.of(),
                    List.of());
        });

        assertEquals(ToolExecutionResult.Status.REQUEST_ERROR, ex.status(), "Should be REQUEST_ERROR status");
        assertTrue(ex.getMessage().contains("testSomeCommand"), "Error should mention the field name");
        assertTrue(ex.getMessage().contains("="), "Error should mention the delimiter change");
        assertTrue(ex.getMessage().contains("Allowed"), "Error should mention allowed tags");
    }
}
