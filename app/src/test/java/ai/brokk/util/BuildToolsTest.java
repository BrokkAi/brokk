package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.MultiAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

        TestAnalyzer testAnalyzer = new TestAnalyzer() {
            @Override
            public Set<ProjectFile> getAnalyzedFiles() {
                return Set.of(testFile);
            }
        };
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
    void testGetBuildLintSomeCommand_GoTestFunctions(@TempDir Path tempDir) throws Exception {
        TestProject project = new TestProject(tempDir);
        ProjectFile testFile = new ProjectFile(tempDir, "logic_test.go");

        // TestAnalyzer::getDeclarations(file) by default returns all added declarations for that file
        TestAnalyzer testAnalyzer = new TestAnalyzer();
        // Go tests are top-level functions starting with Test
        CodeUnit testFn = CodeUnit.fn(testFile, "main", "TestMyLogic");
        testAnalyzer.addDeclaration(testFn);

        TestContextManager mockCm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), testAnalyzer);

        // Go style template
        BuildDetails details = new BuildDetails(
                "go build",
                "go test ./...",
                "go test . -run '^{{#classes}}{{value}}{{^last}}|{{/last}}{{/classes}}$'",
                Set.of());

        String result = BuildTools.getBuildLintSomeCommand(mockCm, details, List.of(testFile));

        assertEquals("go test . -run '^TestMyLogic$'", result);
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

    @Test
    void testNonPythonProjectSkipsPythonVersionProbing(@TempDir Path tempDir) throws Exception {
        // Create a Python file that imports distutils - would cap to 3.11 if probed
        Path pyFile = tempDir.resolve("app.py");
        Files.writeString(pyFile, "import distutils\nprint('hello')\n");

        // Create a TestProject configured as Java-only
        TestProject project = new TestProject(tempDir);
        project.setAnalyzerLanguages(Set.of(Languages.JAVA));

        TestAnalyzer testAnalyzer = new TestAnalyzer();
        TestContextManager mockCm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), testAnalyzer);

        // Template that uses {{pyver}} - if Python version was probed, it would be "3.11"
        BuildDetails details = new BuildDetails(
                "javac *.java", "java -jar test.jar", "java -jar test.jar --pyver={{pyver}}", Set.of());

        ProjectFile testFile = new ProjectFile(tempDir, "Test.java");

        // Use the PUBLIC 4-arg API - this exercises the real code path where project
        // is obtained from the context manager and passed to the private helper
        String result = BuildTools.getBuildLintSomeCommand(mockCm, details, List.of(testFile));

        // Since project is Java-only, pyver should be empty (not "3.11" from distutils detection)
        assertEquals("java -jar test.jar --pyver=", result);
    }

    @Test
    void testProjectExclusionPatternsArePassedToEnvironmentPython(@TempDir Path tempDir) throws Exception {
        // Template that interpolates {{pyver}} so we can see if exclusions affect the result
        BuildDetails details =
                new BuildDetails("python -m compileall .", "pytest", "pytest --python={{pyver}}", Set.of());

        // BASELINE: pyproject + normal Python source, NO distutils file
        Path baselineDir = tempDir.resolve("baseline");
        Files.createDirectories(baselineDir);
        Files.writeString(baselineDir.resolve("pyproject.toml"), "[project]\nrequires-python = \">=3.8\"\n");
        Path baselineSrc = baselineDir.resolve("src");
        Files.createDirectories(baselineSrc);
        Files.writeString(baselineSrc.resolve("main.py"), "print('hello')\n");

        TestProject baselineProject = new TestProject(baselineDir);
        baselineProject.setAnalyzerLanguages(Set.of(Languages.PYTHON));
        TestContextManager baselineCm =
                new TestContextManager(baselineProject, new NoOpConsoleIO(), Set.of(), new TestAnalyzer());
        ProjectFile baselineTestFile = new ProjectFile(baselineDir, "src/main.py");
        String baselineCommand = BuildTools.getBuildLintSomeCommand(baselineCm, details, List.of(baselineTestFile));

        // CONTROL: identical to baseline but with legacy/old_setup.py importing distutils, NO exclusion
        Path controlDir = tempDir.resolve("control");
        Files.createDirectories(controlDir);
        Files.writeString(controlDir.resolve("pyproject.toml"), "[project]\nrequires-python = \">=3.8\"\n");
        Path controlSrc = controlDir.resolve("src");
        Files.createDirectories(controlSrc);
        Files.writeString(controlSrc.resolve("main.py"), "print('hello')\n");
        Path controlLegacy = controlDir.resolve("legacy");
        Files.createDirectories(controlLegacy);
        Files.writeString(controlLegacy.resolve("old_setup.py"), "from distutils.core import setup\n");

        TestProject controlProject = new TestProject(controlDir);
        controlProject.setAnalyzerLanguages(Set.of(Languages.PYTHON));
        controlProject.setExclusionPatterns(Set.of());
        TestContextManager controlCm =
                new TestContextManager(controlProject, new NoOpConsoleIO(), Set.of(), new TestAnalyzer());
        ProjectFile controlTestFile = new ProjectFile(controlDir, "src/main.py");
        String controlCommand = BuildTools.getBuildLintSomeCommand(controlCm, details, List.of(controlTestFile));

        // EXCLUDED: same as control but with "legacy" excluded
        Path excludedDir = tempDir.resolve("excluded");
        Files.createDirectories(excludedDir);
        Files.writeString(excludedDir.resolve("pyproject.toml"), "[project]\nrequires-python = \">=3.8\"\n");
        Path excludedSrc = excludedDir.resolve("src");
        Files.createDirectories(excludedSrc);
        Files.writeString(excludedSrc.resolve("main.py"), "print('hello')\n");
        Path excludedLegacy = excludedDir.resolve("legacy");
        Files.createDirectories(excludedLegacy);
        Files.writeString(excludedLegacy.resolve("old_setup.py"), "from distutils.core import setup\n");

        TestProject excludedProject = new TestProject(excludedDir);
        excludedProject.setAnalyzerLanguages(Set.of(Languages.PYTHON));
        excludedProject.setExclusionPatterns(Set.of("legacy"));
        TestContextManager excludedCm =
                new TestContextManager(excludedProject, new NoOpConsoleIO(), Set.of(), new TestAnalyzer());
        ProjectFile excludedTestFile = new ProjectFile(excludedDir, "src/main.py");
        String excludedCommand = BuildTools.getBuildLintSomeCommand(excludedCm, details, List.of(excludedTestFile));

        // The key paired control assertion: with exclusion, command should match baseline
        // (no distutils detected because it's in an excluded directory)
        assertEquals(
                baselineCommand,
                excludedCommand,
                "With exclusion, command should match baseline (no distutils detected)");

        // Guard the difference assertion: if no Python > 3.11 is installed, baseline == control
        // because both would fall back to lower bound. Only assert difference if they actually differ.
        assumeTrue(
                !baselineCommand.equals(controlCommand),
                "Skipping difference assertion: no Python version available that differs between capped/uncapped");

        // Control should differ from excluded (the exclusion made a difference)
        assertNotEquals(
                controlCommand,
                excludedCommand,
                "Exclusion pattern should change rendered command by skipping distutils in excluded dir");
    }

    @Test
    void testGetBuildLintSomeCommand_GoMultiAnalyzerRegression(@TempDir Path tempDir) throws Exception {
        TestProject project = new TestProject(tempDir);
        ProjectFile testFile = new ProjectFile(tempDir, "callbacks/logic_test.go");

        // 1. Create a Go analyzer that returns the prefixed path
        TestAnalyzer goAnalyzer = new TestAnalyzer() {
            @Override
            public List<String> getTestModules(Collection<ProjectFile> files) {
                return List.of("./callbacks");
            }
        };
        CodeUnit testFn = CodeUnit.fn(testFile, "callbacks", "TestCallbacks");
        goAnalyzer.addDeclaration(testFn);

        // 2. Setup MultiAnalyzer with the Go delegate
        // Note: MultiAnalyzer.getTestModules delegates to the language-specific analyzer
        var multiAnalyzer = new MultiAnalyzer(Map.of(Languages.GO, goAnalyzer));

        TestContextManager mockCm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), multiAnalyzer);

        // User's reported template
        BuildDetails details = new BuildDetails(
                "go build",
                "go test ./...",
                "go test {{#packages}}{{value}} {{/packages}} -run '^{{#classes}}{{value}}{{^last}}|{{/last}}{{/classes}}$'",
                Set.of());

        // 3. Interpolate
        String result = BuildTools.getBuildLintSomeCommand(mockCm, details, List.of(testFile));

        // 4. Assertions
        // Ensure the package has the ./ prefix and the class (function) is present
        assertEquals("go test ./callbacks  -run '^TestCallbacks$'", result);
    }

    @Test
    void testDetermineVerificationCommand_AllScopeSkipsPythonVersionForNonPythonProject(@TempDir Path tempDir)
            throws Exception {
        TestProject project = new TestProject(tempDir);
        project.setAnalyzerLanguages(Set.of(Languages.JAVA));
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        TestContextManager mockCm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), new TestAnalyzer());

        BuildDetails details = new BuildDetails("build-cmd", "python{{pyver}} -m pytest", "unused-test-some", Set.of());

        String result = BuildTools.determineVerificationCommand(mockCm.liveContext(), details);

        assertEquals("python -m pytest", result);
    }

    @Test
    void testGetBuildLintSomeCommand_SkipsPythonVersionForNonPythonProject(@TempDir Path tempDir) throws Exception {
        TestProject project = new TestProject(tempDir);
        project.setAnalyzerLanguages(Set.of(Languages.JAVA));

        TestContextManager mockCm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), new TestAnalyzer());

        BuildDetails details = new BuildDetails("build-cmd", "unused-test-all", "python{{pyver}} -m pytest", Set.of());

        ProjectFile testFile = new ProjectFile(tempDir, "src/test/java/com/example/AppTest.java");

        String result = BuildTools.getBuildLintSomeCommand(mockCm, details, List.of(testFile));

        assertEquals("python -m pytest", result);
    }
}
