package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.IContextManager;
import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;
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

        IAnalyzer mockAnalyzer = new IAnalyzer() {
            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public List<String> getTestModules(Collection<ProjectFile> files) {
                // Return empty to force BuildTools to use its internal path-to-package logic
                return List.of();
            }

            @Override
            public List<CodeUnit> getTopLevelDeclarations(ProjectFile file) {
                return List.of(CodeUnit.cls(file, "myapp.tests.test_logic", "TestLogic"));
            }

            @Override
            public Set<String> getSources(CodeUnit cu, boolean includeComments) {
                return Set.of(testFile.toString());
            }

            @Override
            public Set<Language> languages() {
                return Set.of();
            }

            @Override
            public IAnalyzer update(Set<ProjectFile> changedFiles) {
                return this;
            }

            @Override
            public IAnalyzer update() {
                return this;
            }

            @Override
            public ai.brokk.project.IProject getProject() {
                return project;
            }

            @Override
            public List<CodeUnit> getAllDeclarations() {
                return List.of();
            }

            @Override
            public Set<CodeUnit> getDeclarations(ProjectFile file) {
                return Set.of();
            }

            @Override
            public SequencedSet<CodeUnit> getDefinitions(String fqName) {
                return new LinkedHashSet<>();
            }

            @Override
            public List<CodeUnit> getDirectChildren(CodeUnit cu) {
                return List.of();
            }

            @Override
            public Optional<String> extractCallReceiver(String reference) {
                return Optional.empty();
            }

            @Override
            public List<String> importStatementsOf(ProjectFile file) {
                return List.of();
            }

            @Override
            public Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, Range range) {
                return Optional.empty();
            }

            @Override
            public Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, int startLine, int endLine) {
                return Optional.empty();
            }

            @Override
            public Optional<String> getSkeleton(CodeUnit cu) {
                return Optional.empty();
            }

            @Override
            public Optional<String> getSkeletonHeader(CodeUnit classUnit) {
                return Optional.empty();
            }

            @Override
            public Optional<String> getSource(CodeUnit codeUnit, boolean includeComments) {
                return Optional.empty();
            }
        };

        IContextManager mockCm = new IContextManager() {
            @Override
            public TestProject getProject() {
                return project;
            }

            @Override
            public IAnalyzer getAnalyzer() {
                return mockAnalyzer;
            }
        };

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
}
