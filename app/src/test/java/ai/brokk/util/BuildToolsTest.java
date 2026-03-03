package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildToolsTest {

    @Test
    void testMixedTemplateInterpolation(@TempDir Path tempDir) throws Exception {
        // 1. Set up a realistic Python structure:
        // projectRoot/
        //   myapp/
        //     __init__.py
        //     tests/
        //       __init__.py
        //       test_logic.py
        Path myapp = tempDir.resolve("myapp");
        Files.createDirectories(myapp);
        Files.createFile(myapp.resolve("__init__.py"));

        Path tests = myapp.resolve("tests");
        Files.createDirectories(tests);
        Files.createFile(tests.resolve("__init__.py"));

        Path testFilePath = tests.resolve("test_logic.py");
        Files.createFile(testFilePath);

        TestProject project = new TestProject(tempDir);
        ProjectFile testFile =
                new ProjectFile(tempDir, tempDir.relativize(testFilePath).toString());

        TestAnalyzer testAnalyzer = new TestAnalyzer();
        CodeUnit testCu = CodeUnit.cls(testFile, "myapp.tests.test_logic", "TestLogic");
        testAnalyzer.addDeclaration(testCu);
        testAnalyzer.setSource(testCu, testFile.toString());

        // Use TestContextManager instead of anonymous IContextManager
        TestContextManager mockCm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), testAnalyzer);

        BuildDetails details = new BuildDetails(
                "python -m compileall .",
                "pytest",
                "pytest {{#packages}}{{value}}{{/packages}} -k {{#classes}}{{value}}{{/classes}}",
                Set.of());

        String result = BuildTools.getBuildLintSomeCommand(mockCm, details, List.of(testFile));

        // Verify:
        // 1. packages: derived via toPythonModuleLabel/detectModuleAnchor (myapp.tests.test_logic)
        // 2. classes: derived via AnalyzerUtil (TestLogic)
        assertEquals("pytest myapp.tests.test_logic -k TestLogic", result);
    }

    @Test
    void testExtractRunnerAnchorFromCommands(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("tests"));
        Files.createFile(tempDir.resolve("tests/run.py"));

        // 1. Basic case
        var anchor = BuildTools.extractRunnerAnchorFromCommands(tempDir, List.of("python tests/run.py"));
        assertEquals(tempDir.resolve("tests"), anchor.orElse(null));

        // 2. Quoted strings (double)
        anchor = BuildTools.extractRunnerAnchorFromCommands(tempDir, List.of("python \"tests/run.py\""));
        assertEquals(tempDir.resolve("tests"), anchor.orElse(null));

        // 3. Quoted strings (single)
        anchor = BuildTools.extractRunnerAnchorFromCommands(tempDir, List.of("python 'tests/run.py'"));
        assertEquals(tempDir.resolve("tests"), anchor.orElse(null));

        // 4. Shell operators
        anchor = BuildTools.extractRunnerAnchorFromCommands(tempDir, List.of("python tests/run.py && echo done"));
        assertEquals(tempDir.resolve("tests"), anchor.orElse(null));

        // 5. Flags (should be ignored)
        // This relies on the file existing, so 'tests/run.py' is the only valid candidate
        anchor = BuildTools.extractRunnerAnchorFromCommands(tempDir, List.of("python -v tests/run.py"));
        assertEquals(tempDir.resolve("tests"), anchor.orElse(null));

        // 6. Assignments (should be ignored)
        anchor = BuildTools.extractRunnerAnchorFromCommands(tempDir, List.of("python tests/run.py --config=foo"));
        assertEquals(tempDir.resolve("tests"), anchor.orElse(null));

        // 7. No match
        anchor = BuildTools.extractRunnerAnchorFromCommands(tempDir, List.of("echo hello"));
        assertEquals(null, anchor.orElse(null));
    }

    @Test
    void testExtractRunnerAnchorWithShellSeparators(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("scripts"));
        Path runner = tempDir.resolve("scripts/runner.py");
        Files.createFile(runner);

        // Verify that tokens adjacent to shell operators are correctly parsed
        var anchor = BuildTools.extractRunnerAnchorFromCommands(tempDir, List.of("python scripts/runner.py;echo done"));
        assertEquals(tempDir.resolve("scripts"), anchor.orElse(null));

        anchor = BuildTools.extractRunnerAnchorFromCommands(tempDir, List.of("python scripts/runner.py&&echo done"));
        assertEquals(tempDir.resolve("scripts"), anchor.orElse(null));

        anchor = BuildTools.extractRunnerAnchorFromCommands(tempDir, List.of("python scripts/runner.py|grep foo"));
        assertEquals(tempDir.resolve("scripts"), anchor.orElse(null));
    }
}
