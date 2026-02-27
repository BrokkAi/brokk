package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.BuildTools;
import ai.brokk.util.BuildVerifier;
import ai.brokk.util.Environment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
    void testResolveModule_selectsLongestMatchingPrefix(@TempDir Path tempDir) throws Exception {
        var project = ai.brokk.project.MainProject.forTests(tempDir);
        var cm = new ai.brokk.testutil.TestContextManager(project);

        var details = new BuildAgent.BuildDetails(
                "mvn compile",
                "mvn test",
                "",
                Set.of(),
                Map.of(),
                null,
                "",
                List.of(
                        new BuildAgent.ModuleBuildEntry("root", "", "mvn compile -root", "mvn test -root", "", "JAVA"),
                        new BuildAgent.ModuleBuildEntry("a", "a", "mvn compile -a", "mvn test -a", "", "JAVA"),
                        new BuildAgent.ModuleBuildEntry("ab", "a/b", "mvn compile -ab", "mvn test -ab", "", "JAVA")));

        // File is in a/b/c/File.java, which matches root (""), a ("a"), and ab ("a/b").
        // "a/b" is the longest match.
        var testFile = new ai.brokk.analyzer.ProjectFile(tempDir, "a/b/c/File.java");
        String cmd = BuildAgent.getBuildLintSomeCommand(cm, details, List.of(testFile));

        assertEquals("mvn test -ab", cmd);
    }

    @Test
    void testGetBuildLintSomeCommand_fallsBackToTestAll(@TempDir Path tempDir) throws Exception {
        var project = ai.brokk.project.MainProject.forTests(tempDir);
        var cm = new ai.brokk.testutil.TestContextManager(project);

        var details = new BuildAgent.BuildDetails(
                "mvn compile",
                "mvn test",
                "", // testSome blank
                Set.of(),
                Map.of(),
                null,
                "",
                List.of(new BuildAgent.ModuleBuildEntry(
                        "mod", "mod", "mvn compile -pl mod", "mvn test -pl mod", "", "JAVA")));

        var testFile = new ai.brokk.analyzer.ProjectFile(tempDir, "mod/src/Test.java");
        String cmd = BuildAgent.getBuildLintSomeCommand(cm, details, List.of(testFile));

        assertEquals("mvn test -pl mod", cmd);
    }

    @Test
    void testInterpolateModulesTemplate() {
        String template = "tests/runtests.py{{#modules}} {{value}}{{/modules}}";
        List<String> modules = List.of("servers.tests");

        String result = BuildTools.interpolateMustacheTemplate(template, modules, "modules");

        assertEquals("tests/runtests.py servers.tests", result);
    }

    @Test
    void testInterpolateModulesTemplateMultiple() {
        String template = "pytest{{#modules}} {{value}}{{/modules}}";
        List<String> modules = List.of("tests.unit", "tests.integration", "tests.e2e");

        String result = BuildTools.interpolateMustacheTemplate(template, modules, "modules");

        assertEquals("pytest tests.unit tests.integration tests.e2e", result);
    }

    @Test
    void testInterpolateFilesTemplate() {
        String template = "jest{{#files}} {{value}}{{/files}}";
        List<String> files = List.of("src/app.test.js", "src/util.test.js");

        String result = BuildTools.interpolateMustacheTemplate(template, files, "files");

        assertEquals("jest src/app.test.js src/util.test.js", result);
    }

    @Test
    void testInterpolateEmptyList() {
        String template = "pytest{{#modules}} {{value}}{{/modules}}";
        List<String> modules = List.of();

        String result = BuildTools.interpolateMustacheTemplate(template, modules, "modules");

        assertEquals("pytest", result);
    }

    @Test
    void testInterpolateSingleItem() {
        String template = "go test -run '{{#classes}} {{value}}{{/classes}}'";
        List<String> classes = List.of("TestFoo");

        String result = BuildTools.interpolateMustacheTemplate(template, classes, "classes");

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

        String result = BuildTools.interpolateMustacheTemplate(template, empty, "modules", "3.11");

        assertEquals("python3.11 -m pytest", result);
    }

    @Test
    void testInterpolatePythonVersionWithoutVariable() {
        String template = "pytest";
        List<String> empty = List.of();

        String result = BuildTools.interpolateMustacheTemplate(template, empty, "modules", "3.11");

        assertEquals("pytest", result);
    }

    @Test
    void testInterpolatePythonVersionEmpty() {
        String template = "pytest --pyver={{pyver}}";
        List<String> empty = List.of();

        String result = BuildTools.interpolateMustacheTemplate(template, empty, "modules", null);

        assertEquals("pytest --pyver=", result);
    }

    @Test
    void testInterpolateModulesAndPythonVersion() {
        String template = "python{{pyver}} tests/runtests.py{{#modules}} {{value}}{{/modules}}";
        List<String> modules = List.of("tests.unit", "tests.integration");

        String result = BuildTools.interpolateMustacheTemplate(template, modules, "modules", "3.10");

        assertEquals("python3.10 tests/runtests.py tests.unit tests.integration", result);
    }

    @Test
    void testInterpolateEmptyPythonVersion() {
        String template = "uv run {{#modules}}{{value}}{{/modules}}";
        List<String> modules = List.of("tests.e2e");

        String result = BuildTools.interpolateMustacheTemplate(template, modules, "modules", "");

        assertEquals("uv run tests.e2e", result);
    }

    @Test
    void testReportBuildDetailsPreservesExistingPatterns(@TempDir Path tempDir) throws Exception {
        // Create a project with existing exclusion patterns
        Files.createDirectory(tempDir.resolve("src"));
        Files.createDirectory(tempDir.resolve("build"));
        var testProject = new TestProject(tempDir);
        testProject.setExclusionPatterns(Set.of("*.svg", "*.png", "build"));

        // Create a BuildAgent - we don't need LLM for this test
        var agent = new BuildAgent(testProject, null, null, new TestConsoleIO());

        // Call reportBuildDetails with new patterns from "LLM"
        // This simulates what happens when BuildAgent runs again
        agent.reportBuildDetails(
                "mvn compile",
                true,
                "mvn test",
                true,
                "mvn test -Dtest={{#classes}}{{value}}{{/classes}}",
                List.of("target"), // LLM suggests target directory
                List.of("*.gif"), // LLM suggests gif pattern
                List.of());

        // Verify existing patterns are preserved AND new patterns are added
        var reportedDetails = agent.getReportedDetails();
        assert reportedDetails != null;
        assertEquals("", reportedDetails.afterTaskListCommand());
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
        var agent = new BuildAgent(project, null, null, new TestConsoleIO());

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
        var agent = new BuildAgent(project, null, null, new TestConsoleIO());

        agent.reportBuildDetails(
                "npm run build",
                true,
                "npm test",
                true,
                "npm test {{#files}}{{value}}{{/files}}",
                List.of("node_modules", "build"),
                List.of("*.map"),
                List.of());

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
    void testReportBuildDetails_DisablesGlobalCommands(@TempDir Path tempDir) throws Exception {
        var testProject = new TestProject(tempDir);
        var agent = new BuildAgent(testProject, null, null, new TestConsoleIO());

        agent.reportBuildDetails("mvn compile", false, "mvn test", false, "", List.of(), List.of(), List.of());

        var details = agent.getReportedDetails();
        assertNotNull(details);
        assertFalse(details.buildLintEnabled(), "Build lint should be disabled");
        assertFalse(details.testAllEnabled(), "Test all should be disabled");
        assertEquals("mvn compile", details.buildLintCommand());
        assertEquals("mvn test", details.testAllCommand());
    }

    @Test
    void testRunExplicitCommandSuccessStreamsAndClearsBuildError(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);
            project.setBuildDetails(
                    new BuildAgent.BuildDetails("lint", "testAll", "testSome", Set.of(), java.util.Map.of(), null, ""));
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

            var updated = BuildTools.runExplicitCommand(ctx, cmd, project.awaitBuildDetails());

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
                    new BuildAgent.BuildDetails("lint", "testAll", "testSome", Set.of(), java.util.Map.of(), null, ""));
            var io = new TestConsoleIO();
            var cm = new TestContextManager(project, io, Set.of(), new ai.brokk.testutil.TestAnalyzer());
            var ctx = cm.liveContext();

            String cmd = "explicit-failure";
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                outputConsumer.accept("some output");
                throw new Environment.FailureException("boom", "stdout:\nfail", 1);
            };

            var updated = BuildTools.runExplicitCommand(ctx, cmd, project.awaitBuildDetails());

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

            var updated = BuildTools.runExplicitCommand(ctx, cmd, project.awaitBuildDetails());

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
                new BuildAgent.BuildDetails("lint", "testAll", "testSome", Set.of(), java.util.Map.of(), null, ""));

        var io = new TestConsoleIO();
        var cm = new TestContextManager(project, io, Set.of(), new ai.brokk.testutil.TestAnalyzer());
        var ctx = cm.liveContext();

        var ctxWithError = ctx.withBuildResult(false, "previous failure");

        var updated = BuildTools.runExplicitCommand(ctxWithError, "   ", project.awaitBuildDetails());

        assertTrue(updated.getBuildError().isBlank(), "Blank command should clear any existing build error");
        assertTrue(io.getOutputLog().contains("No explicit command specified, skipping."), "Should log skip message");
    }

    @Test
    void testRunExplicitCommandUsesTestTimeout(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);
            project.setTestCommandTimeoutSeconds(120L); // Custom test timeout
            project.setBuildDetails(
                    new BuildAgent.BuildDetails("lint", "testAll", "testSome", Set.of(), java.util.Map.of()));
            var io = new TestConsoleIO();
            var cm = new TestContextManager(project, io, Set.of(), new ai.brokk.testutil.TestAnalyzer());
            var ctx = cm.liveContext();

            var capturedTimeout = new java.util.concurrent.atomic.AtomicReference<Duration>();
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                capturedTimeout.set(timeout);
                return "ok";
            };

            BuildTools.runExplicitCommand(ctx, "test-cmd", project.awaitBuildDetails());

            assertEquals(
                    Duration.ofSeconds(120), capturedTimeout.get(), "Test command should use test-specific timeout");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testRunExplicitCommandUnlimitedTimeout(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);
            project.setTestCommandTimeoutSeconds(-1L); // Unlimited timeout
            project.setBuildDetails(
                    new BuildAgent.BuildDetails("lint", "testAll", "testSome", Set.of(), java.util.Map.of()));
            var io = new TestConsoleIO();
            var cm = new TestContextManager(project, io, Set.of(), new ai.brokk.testutil.TestAnalyzer());
            var ctx = cm.liveContext();

            var capturedTimeout = new java.util.concurrent.atomic.AtomicReference<Duration>();
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                capturedTimeout.set(timeout);
                return "ok";
            };

            BuildTools.runExplicitCommand(ctx, "test-cmd", project.awaitBuildDetails());

            assertEquals(
                    Environment.UNLIMITED_TIMEOUT,
                    capturedTimeout.get(),
                    "Timeout of -1 should result in UNLIMITED_TIMEOUT");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testRunVerificationUsesRunTimeout(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);
            project.setRunCommandTimeoutSeconds(45L); // Custom run timeout
            project.setBuildDetails(
                    new BuildAgent.BuildDetails("lint-cmd", "testAll", "testSome", Set.of(), java.util.Map.of()));
            var io = new TestConsoleIO();
            var cm = new TestContextManager(project, io, Set.of(), new ai.brokk.testutil.TestAnalyzer());
            var ctx = cm.liveContext();

            var capturedTimeout = new java.util.concurrent.atomic.AtomicReference<Duration>();
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                capturedTimeout.set(timeout);
                return "ok";
            };

            BuildTools.runVerification(ctx);

            assertEquals(
                    Duration.ofSeconds(45),
                    capturedTimeout.get(),
                    "Verification command should use run-specific timeout");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testVerifyWithRetriesLintFailureStopsImmediately(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);

            var callCount = new AtomicInteger(0);
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                callCount.incrementAndGet();
                outputConsumer.accept("lint-error");
                throw new Environment.FailureException("lint failed", "compile error", 1);
            };

            var result = BuildVerifier.verifyWithRetries(project, "lint-cmd", "test-cmd", 5, Map.of(), null);

            assertFalse(result.success(), "Should fail when lint fails");
            assertEquals(1, callCount.get(), "Should only run lint once, never reach tests");
            assertEquals(1, result.exitCode());
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testVerifyWithRetriesTestPassesOnFirstTry(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);

            var callCount = new AtomicInteger(0);
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                callCount.incrementAndGet();
                outputConsumer.accept("ok");
                return "ok";
            };

            var result = BuildVerifier.verifyWithRetries(project, "lint-cmd", "test-cmd", 5, Map.of(), null);

            assertTrue(result.success(), "Should succeed");
            assertEquals(2, callCount.get(), "Should run lint once + test once");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testVerifyWithRetriesTestPassesOnThirdAttempt(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);

            var callCount = new AtomicInteger(0);
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                int n = callCount.incrementAndGet();
                if (command.equals("lint-cmd")) {
                    return "lint ok";
                }
                // First two test attempts fail, third succeeds
                if (n <= 3) { // call 1 = lint, call 2 = test attempt 1, call 3 = test attempt 2
                    outputConsumer.accept("flaky failure");
                    throw new Environment.FailureException("test failed", "flaky", 1);
                }
                outputConsumer.accept("test passed");
                return "test ok";
            };

            var result = BuildVerifier.verifyWithRetries(project, "lint-cmd", "test-cmd", 5, Map.of(), null);

            assertTrue(result.success(), "Should succeed after retries");
            assertEquals(4, callCount.get(), "Should run lint(1) + test fail(2) + test fail(3) + test pass(4)");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testVerifyWithRetriesAllTestAttemptsExhausted(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);

            var callCount = new AtomicInteger(0);
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                callCount.incrementAndGet();
                if (command.equals("lint-cmd")) {
                    return "lint ok";
                }
                outputConsumer.accept("persistent failure");
                throw new Environment.FailureException("test failed", "always fails", 42);
            };

            var result = BuildVerifier.verifyWithRetries(project, "lint-cmd", "test-cmd", 5, Map.of(), null);

            assertFalse(result.success(), "Should fail after all retries exhausted");
            assertEquals(6, callCount.get(), "Should run lint(1) + 5 test attempts");
            assertEquals(42, result.exitCode(), "Should return exit code from last attempt");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testVerifyWithRetriesBlankLintSkipsToTests(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);

            var callCount = new AtomicInteger(0);
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                callCount.incrementAndGet();
                assertEquals("test-cmd", command, "Only test command should be run");
                return "test ok";
            };

            var result = BuildVerifier.verifyWithRetries(project, "", "test-cmd", 5, Map.of(), null);

            assertTrue(result.success(), "Should succeed");
            assertEquals(1, callCount.get(), "Should only run the test command");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testVerifyWithRetriesBlankTestReturnsSuccessAfterLint(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);

            var callCount = new AtomicInteger(0);
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                callCount.incrementAndGet();
                return "lint ok";
            };

            var result = BuildVerifier.verifyWithRetries(project, "lint-cmd", "", 5, Map.of(), null);

            assertTrue(result.success(), "Should succeed when lint passes and no test command");
            assertEquals(1, callCount.get(), "Should only run lint");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testVerifyWithRetriesStreamsOutputForEachAttempt(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);

            var callCount = new AtomicInteger(0);
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                int n = callCount.incrementAndGet();
                if (command.equals("lint-cmd")) {
                    outputConsumer.accept("lint output");
                    return "lint ok";
                }
                outputConsumer.accept("test-attempt-" + (n - 1));
                throw new Environment.FailureException("fail", "fail", 1);
            };

            var streamedLines = new ArrayList<String>();
            var result =
                    BuildVerifier.verifyWithRetries(project, "lint-cmd", "test-cmd", 3, Map.of(), streamedLines::add);

            assertFalse(result.success());
            assertTrue(streamedLines.contains("lint output"), "Should stream lint output");
            assertTrue(streamedLines.contains("test-attempt-1"), "Should stream first test attempt");
            assertTrue(streamedLines.contains("test-attempt-2"), "Should stream second test attempt");
            assertTrue(streamedLines.contains("test-attempt-3"), "Should stream third test attempt");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testDetermineVerificationCommand_FallbackToModules_WhenGlobalEmpty(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("mod1"));
        Files.createDirectories(tempDir.resolve("mod2"));
        var project = new TestProject(tempDir);
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        var details = new BuildAgent.BuildDetails(
                "",
                "", // Empty global testAll
                "",
                Set.of(),
                Map.of(),
                null,
                "",
                List.of(
                        new BuildAgent.ModuleBuildEntry("m1", "mod1", "lint1", "test1", "some1", ""),
                        new BuildAgent.ModuleBuildEntry("m2", "mod2", "lint2", "test2", "some2", "")));
        project.setBuildDetails(details);

        var cm = new TestContextManager(project);
        var ctx = cm.liveContext();

        String cmd = BuildAgent.determineVerificationCommand(ctx);
        assertEquals("test1 && test2", cmd);
    }

    @Test
    void testDetermineVerificationCommand_VerificationFallback_WhenGlobalEmpty(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("mod1"));
        var project = new TestProject(tempDir);
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.WORKSPACE);

        var details = new BuildAgent.BuildDetails(
                "", // Empty global buildLint
                "",
                "",
                Set.of(),
                Map.of(),
                null,
                "",
                List.of(
                        new BuildAgent.ModuleBuildEntry("m1", "mod1", "lint1", "test1", "some1", ""),
                        new BuildAgent.ModuleBuildEntry("m2", "mod2", "lint2", "test2", "some2", "")));
        project.setBuildDetails(details);

        var cm = new TestContextManager(project);
        // Create context with a file that does NOT map to a module
        var file = new ai.brokk.analyzer.ProjectFile(tempDir, "README.md");
        Files.writeString(tempDir.resolve("README.md"), "info");
        var ctx = cm.liveContext().addFragments(new ai.brokk.context.ContextFragments.ProjectPathFragment(file, cm));

        String cmd = BuildAgent.determineVerificationCommand(ctx);
        assertEquals("lint1 && lint2", cmd);
    }

    @Test
    void testDetermineVerificationCommand_RespectsDisabledGlobalTest(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("mod1"));
        var project = new TestProject(tempDir);
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        // Global test command is present but disabled.
        var details = new BuildAgent.BuildDetails(
                "lint",
                true,
                "global test",
                false,
                "",
                Set.of(),
                Map.of(),
                null,
                "",
                List.of(new BuildAgent.ModuleBuildEntry("m1", "mod1", "lint1", "test1", "some1", "")));
        project.setBuildDetails(details);

        var cm = new TestContextManager(project);
        var ctx = cm.liveContext();

        String cmd = BuildAgent.determineVerificationCommand(ctx);
        // Should fallback to module command since global is disabled.
        assertEquals("test1", cmd);
    }

    @Test
    void testGetBuildLintSomeCommandGoModules(@TempDir Path tempDir) throws Exception {
        TestProject project = new TestProject(tempDir, Languages.GO);
        // Mock GoAnalyzer to return specific test packages
        TestAnalyzer analyzer = new TestAnalyzer() {
            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public List<String> getTestModules(Collection<ProjectFile> files) {
                return List.of(".", "./auth");
            }
        };
        TestContextManager cm = new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer);

        ProjectFile file1 = new ProjectFile(tempDir, "main_test.go");
        ProjectFile file2 = new ProjectFile(tempDir, "auth/auth_test.go");

        BuildAgent.BuildDetails details = new BuildAgent.BuildDetails(
                "go build", "go test ./...", "go test {{#packages}}{{value}} {{/packages}}", Set.of());

        String result = BuildTools.getBuildLintSomeCommand(cm, details, List.of(file1, file2));

        // GoAnalyzer.getTestModules returns ./path and .
        assertEquals("go test . ./auth ", result);
    }

    @Test
    void testGetBuildLintSomeCommandPythonPackages(@TempDir Path tempDir) throws Exception {
        TestProject project = new TestProject(tempDir, Languages.PYTHON);
        // Mock PythonAnalyzer to return specific test modules
        TestAnalyzer analyzer = new TestAnalyzer() {
            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public List<String> getTestModules(Collection<ProjectFile> files) {
                return List.of("auth.test_login", "tests.test_foo");
            }
        };
        TestContextManager cm = new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer);

        ProjectFile file1 = new ProjectFile(tempDir, "tests/test_foo.py");
        ProjectFile file2 = new ProjectFile(tempDir, "auth/test_login.py");

        BuildAgent.BuildDetails details = new BuildAgent.BuildDetails(
                "python -m compile",
                "python -m pytest",
                "python -m pytest {{#packages}}{{value}} {{/packages}}",
                Set.of());

        String result = BuildTools.getBuildLintSomeCommand(cm, details, List.of(file1, file2));

        // Result should be sorted dotted labels: auth.test_login tests.test_foo
        assertEquals("python -m pytest auth.test_login tests.test_foo ", result);
    }

    @Test
    void testGetBuildLintSomeCommandJavaPackages(@TempDir Path tempDir) throws Exception {
        TestProject project = new TestProject(tempDir, Languages.JAVA);
        // Java uses default IAnalyzer.getTestModules which extracts packageName from CodeUnits
        TestAnalyzer analyzer = new TestAnalyzer() {
            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public List<String> getTestModules(Collection<ProjectFile> files) {
                return files.stream()
                        .flatMap(f -> getTopLevelDeclarations(f).stream())
                        .map(ai.brokk.analyzer.CodeUnit::packageName)
                        .distinct()
                        .sorted()
                        .toList();
            }
        };
        TestContextManager cm = new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer);

        ProjectFile file1 = new ProjectFile(tempDir, "src/main/java/com/example/App.java");
        ProjectFile file2 = new ProjectFile(tempDir, "src/main/java/com/example/util/Helper.java");

        analyzer.addDeclaration(CodeUnit.cls(file1, "com.example", "App"));
        analyzer.addDeclaration(CodeUnit.cls(file2, "com.example.util", "Helper"));

        BuildAgent.BuildDetails details = new BuildAgent.BuildDetails(
                "mvn compile",
                "mvn test",
                "mvn test -Dtest={{#packages}}{{value}}.*{{^last}} {{/last}}{{/packages}}",
                Set.of());

        String result = BuildTools.getBuildLintSomeCommand(cm, details, List.of(file1, file2));

        assertEquals("mvn test -Dtest=com.example.* com.example.util.*", result);
    }

    @Test
    void testGetBuildLintSomeCommandRustModules(@TempDir Path tempDir) throws Exception {
        TestProject project = new TestProject(tempDir, Languages.RUST);
        TestAnalyzer analyzer = new TestAnalyzer() {
            private final Map<ProjectFile, List<CodeUnit>> fileToDecls = new java.util.HashMap<>();

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public void addDeclaration(CodeUnit cu) {
                super.addDeclaration(cu);
                fileToDecls.computeIfAbsent(cu.source(), k -> new ArrayList<>()).add(cu);
            }

            @Override
            public List<CodeUnit> getTopLevelDeclarations(ProjectFile file) {
                return fileToDecls.getOrDefault(file, List.of());
            }

            @Override
            public List<String> getTestModules(Collection<ProjectFile> files) {
                return files.stream()
                        .flatMap(f -> getTopLevelDeclarations(f).stream())
                        .map(ai.brokk.analyzer.CodeUnit::packageName)
                        .distinct()
                        .sorted()
                        .toList();
            }
        };
        TestContextManager cm = new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer);

        ProjectFile file1 = new ProjectFile(tempDir, "src/lib.rs");
        ProjectFile file2 = new ProjectFile(tempDir, "src/foo.rs");

        analyzer.addDeclaration(new CodeUnit(file1, CodeUnitType.FUNCTION, "crate", "test_lib"));
        analyzer.addDeclaration(new CodeUnit(file2, CodeUnitType.FUNCTION, "crate::foo", "test_foo"));

        BuildAgent.BuildDetails details = new BuildAgent.BuildDetails(
                "cargo check",
                "cargo test",
                "cargo test {{#packages}}{{value}}{{^last}} {{/last}}{{/packages}}",
                Set.of());

        String result = BuildTools.getBuildLintSomeCommand(cm, details, List.of(file1, file2));

        assertEquals("cargo test crate crate::foo", result);
    }

    @Test
    void testValidateBuildDetailsSuccess(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);

            // Lint command succeeds
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> "ok";

            var agent = new BuildAgent(project, null, null, new TestConsoleIO());
            var details = new BuildAgent.BuildDetails("lint-cmd", "test-all", "", Set.of(), Map.of());

            String result = agent.validateBuildDetails(details);
            assertNull(result, "validateBuildDetails should return null when commands pass");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testValidateBuildDetailsLintFailure(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);

            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                outputConsumer.accept("compile error");
                throw new Environment.FailureException("build failed", "compile error", 1);
            };

            var agent = new BuildAgent(project, null, null, new TestConsoleIO());
            var details = new BuildAgent.BuildDetails("lint-cmd", "test-all", "", Set.of(), Map.of());

            String result = agent.validateBuildDetails(details);
            assertNotNull(result, "validateBuildDetails should return error when lint fails");
            assertTrue(result.contains("Build/lint command failed"), "Error should mention build/lint failure");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }

    @Test
    void testValidateBuildDetailsBlankCommandsSkipsValidation(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "x");
        var project = new TestProject(tempDir);

        var agent = new BuildAgent(project, null, null, new TestConsoleIO());
        // Both commands are blank - should skip validation entirely and return null
        var details = new BuildAgent.BuildDetails("", "", "", Set.of(), Map.of());

        String result = agent.validateBuildDetails(details);
        assertNull(result, "validateBuildDetails should return null when all commands are blank");
    }

    @Test
    void testModuleBuildEntrySerialization() throws Exception {
        var entry = new BuildAgent.ModuleBuildEntry(
                "core", "core", "mvn compile", "mvn test", "mvn test -Dtest={{classes}}", "Java");
        var details = new BuildAgent.BuildDetails(
                "mvn compile", "mvn test", "", Set.of(), Map.of(), null, "", List.of(entry));

        String json = ai.brokk.util.Json.toJson(details);
        // Ensure the field is present in the serialized output
        assertTrue(
                json.contains("\"language\":\"Java\"") || json.contains("\"language\" : \"Java\""),
                "Serialized JSON should contain module language key. Actual JSON: " + json);

        var deserialized = ai.brokk.util.Json.fromJson(json, BuildAgent.BuildDetails.class);
        assertEquals(1, deserialized.modules().size());
        assertEquals("Java", deserialized.modules().getFirst().language());
    }

    @Test
    void testEmptyBuildDetails_HasDisabledCommands() {
        assertFalse(
                BuildAgent.BuildDetails.EMPTY.buildLintEnabled(),
                "buildLintEnabled should be false by default in EMPTY");
        assertFalse(
                BuildAgent.BuildDetails.EMPTY.testAllEnabled(), "testAllEnabled should be false by default in EMPTY");
    }

    @Test
    void testDetermineVerificationCommand_ChainsMultipleModules(@TempDir Path tempDir) throws Exception {
        var project = ai.brokk.project.MainProject.forTests(tempDir);
        var cm = new ai.brokk.testutil.TestContextManager(project);

        // Define modules in a specific order
        var details = new BuildAgent.BuildDetails(
                "",
                false,
                "",
                false,
                "",
                Set.of(),
                Map.of(),
                null,
                "",
                List.of(
                        new BuildAgent.ModuleBuildEntry("m1", "mod1", "lint1", "test1", "some1", "JAVA"),
                        new BuildAgent.ModuleBuildEntry("m2", "mod2", "lint2", "test2", "some2", "JAVA")));
        project.setBuildDetails(details);
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.WORKSPACE);

        // Create files in both modules
        var file1 = new ai.brokk.analyzer.ProjectFile(tempDir, "mod1/File1.java");
        var file2 = new ai.brokk.analyzer.ProjectFile(tempDir, "mod2/File2.java");
        Files.createDirectories(tempDir.resolve("mod1"));
        Files.createDirectories(tempDir.resolve("mod2"));
        Files.writeString(file1.absPath(), "class File1 {}");
        Files.writeString(file2.absPath(), "class File2 {}");

        // Add both files to context
        var ctx = cm.liveContext()
                .addFragments(new ai.brokk.context.ContextFragments.ProjectPathFragment(file1, cm))
                .addFragments(new ai.brokk.context.ContextFragments.ProjectPathFragment(file2, cm));

        // Invoke determination
        String cmd = BuildAgent.determineVerificationCommand(ctx);

        // Assert chained lint commands in order (since files are not test files)
        assertEquals("lint1 && lint2", cmd);
    }

    @Test
    void testVerifyWithRetriesUsesCustomMaxRetries(@TempDir Path tempDir) throws Exception {
        var originalFactory = Environment.shellCommandRunnerFactory;
        try {
            Files.writeString(tempDir.resolve("README.md"), "x");
            var project = new TestProject(tempDir);

            var callCount = new AtomicInteger(0);
            Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
                callCount.incrementAndGet();
                if (command.equals("lint-cmd")) return "ok";
                throw new Environment.FailureException("fail", "fail", 1);
            };

            BuildVerifier.verifyWithRetries(project, "lint-cmd", "test-cmd", 7, Map.of(), null);

            assertEquals(8, callCount.get(), "Should run lint(1) + 7 test attempts");
        } finally {
            Environment.shellCommandRunnerFactory = originalFactory;
        }
    }
}
