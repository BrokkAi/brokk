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
        // 1. packages: derived via Analyzer.getTestModules (myapp.tests.test_logic)
        // 2. classes: derived via AnalyzerUtil (TestLogic)
        assertEquals("pytest myapp.tests.test_logic -k TestLogic", result);
    }

    @Test
    void testGetBuildLintSomeCommand_StaticCommand(@TempDir Path tempDir) throws Exception {
        TestProject project = new TestProject(tempDir);
        TestAnalyzer testAnalyzer = new TestAnalyzer();
        TestContextManager mockCm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), testAnalyzer);

        // A static command without any mustache tags
        BuildDetails details = new BuildDetails("build-cmd", "test-all-cmd", "pytest -q", Set.of());

        // Even with empty workspace test files, it should return the static command verbatim
        String result = BuildTools.getBuildLintSomeCommand(mockCm, details, List.of());
        assertEquals("pytest -q", result);
    }
}
