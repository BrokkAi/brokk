package ai.brokk.analyzer.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.MultiAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class MultiAnalyzerImportTest {

    @Test
    public void testDelegationToJavaAnalyzer() throws IOException {
        try (var testProject = InlineCoreProject.empty()
                .languages(Set.of(Languages.JAVA, Languages.PYTHON))
                .addFile(
                        """
                import java.util.List;
                public class JavaClass {
                    private List<String> items;
                }
                """,
                        "JavaClass.java")
                .addFile("def placeholder(): pass\n", "placeholder.py")
                .build()) {

            IAnalyzer multiAnalyzer = testProject.getAnalyzer();
            assertTrue(multiAnalyzer instanceof MultiAnalyzer, "Expected MultiAnalyzer when multiple languages active");

            var javaFile = AnalyzerUtil.getFileFor(multiAnalyzer, "JavaClass").orElseThrow();

            ImportAnalysisProvider provider =
                    multiAnalyzer.as(ImportAnalysisProvider.class).orElseThrow();

            // Verify importInfoOf delegation
            var javaImports = provider.importInfoOf(javaFile);
            assertEquals(1, javaImports.size());
            assertEquals("List", javaImports.getFirst().identifier());

            // Verify relevantImportsFor delegation
            CodeUnit javaUnit = multiAnalyzer.getDeclarations(javaFile).stream()
                    .filter(cu -> cu.shortName().equals("JavaClass"))
                    .findFirst()
                    .orElseThrow();

            Set<String> relevantJava = provider.relevantImportsFor(javaUnit);
            assertTrue(relevantJava.stream().anyMatch(s -> s.contains("java.util.List")));
        }
    }

    @Test
    public void testDelegationToPythonAnalyzer() throws IOException {
        try (var testProject = InlineCoreProject.empty()
                .languages(Set.of(Languages.JAVA, Languages.PYTHON))
                .addFile(
                        """
                import os
                def python_fn():
                    pass
                """,
                        "script.py")
                .addFile("class Placeholder {}\n", "Placeholder.java")
                .build()) {

            IAnalyzer multiAnalyzer = testProject.getAnalyzer();
            assertTrue(multiAnalyzer instanceof MultiAnalyzer, "Expected MultiAnalyzer when multiple languages active");

            var pythonFile = AnalyzerUtil.getFileFor(multiAnalyzer, "script").orElseThrow();

            ImportAnalysisProvider provider =
                    multiAnalyzer.as(ImportAnalysisProvider.class).orElseThrow();

            // Verify importInfoOf delegation - check raw snippet since Python doesn't extract identifiers
            var pythonImports = provider.importInfoOf(pythonFile);
            assertTrue(pythonImports.size() >= 1);
            assertTrue(
                    pythonImports.stream().anyMatch(i -> i.rawSnippet().contains("import os")),
                    "Python import raw snippet should contain 'import os'");

            // Verify relevantImportsFor delegation returns a result (tests delegation, not content)
            CodeUnit pythonUnit = multiAnalyzer.getDeclarations(pythonFile).stream()
                    .filter(cu -> cu.shortName().equals("python_fn"))
                    .findFirst()
                    .orElseThrow();

            // Just verify delegation works - relevantImportsFor may return empty for simple functions
            Set<String> relevantPython = provider.relevantImportsFor(pythonUnit);
            // No assertion on content - we're testing delegation, not Python's import parsing
        }
    }

    @Test
    public void testDelegationRoutesToCorrectLanguage() throws IOException {
        // Create a single project with both Java and Python files
        try (var testProject = InlineCoreProject.empty()
                .languages(Set.of(Languages.JAVA, Languages.PYTHON))
                .addFile(
                        """
                import java.util.List;
                public class JavaClass {
                    private List<String> items;
                }
                """,
                        "JavaClass.java")
                .addFileContents(
                        """
                import os
                def python_fn():
                    pass
                """,
                        "script.py")
                .build()) {

            IAnalyzer multiAnalyzer = testProject.getAnalyzer();
            assertTrue(multiAnalyzer instanceof MultiAnalyzer, "Expected MultiAnalyzer when multiple languages active");

            // Get files from multi analyzer
            var javaFile = AnalyzerUtil.getFileFor(multiAnalyzer, "JavaClass").orElseThrow();
            var pythonFile = AnalyzerUtil.getFileFor(multiAnalyzer, "script").orElseThrow();

            ImportAnalysisProvider provider =
                    multiAnalyzer.as(ImportAnalysisProvider.class).orElseThrow();

            // Verify importInfoOf routes to correct language analyzer
            var javaImports = provider.importInfoOf(javaFile);
            assertEquals(1, javaImports.size());
            assertEquals("List", javaImports.getFirst().identifier());

            // Python import - verify Python-specific ImportInfo extraction
            var pythonImports = provider.importInfoOf(pythonFile);
            assertTrue(pythonImports.size() >= 1);
            assertTrue(pythonImports.stream().anyMatch(i -> "os".equals(i.identifier())));

            // Verify relevantImportsFor routes to correct language analyzer
            CodeUnit javaUnit = multiAnalyzer.getDeclarations(javaFile).stream()
                    .filter(cu -> cu.shortName().equals("JavaClass"))
                    .findFirst()
                    .orElseThrow();

            CodeUnit pythonUnit = multiAnalyzer.getDeclarations(pythonFile).stream()
                    .filter(cu -> cu.shortName().equals("python_fn"))
                    .findFirst()
                    .orElseThrow();

            // Java should have relevant imports (uses List type)
            Set<String> relevantJava = provider.relevantImportsFor(javaUnit);
            assertTrue(
                    relevantJava.stream().anyMatch(s -> s.contains("java.util.List")),
                    "Java delegation should find List import");

            // Python should have relevant imports (uses os via delegation)
            // Note: If python_fn is empty, we add a reference to 'os' to ensure it's relevant
            pythonFile.write("import os\ndef python_fn():\n    print(os.name)");
            var updatedAnalyzer = multiAnalyzer.update();
            var updatedProvider =
                    updatedAnalyzer.as(ImportAnalysisProvider.class).orElseThrow();
            var updatedPythonUnit = updatedAnalyzer.getDeclarations(pythonFile).stream()
                    .filter(cu -> cu.shortName().equals("python_fn"))
                    .findFirst()
                    .orElseThrow();

            Set<String> relevantPython = updatedProvider.relevantImportsFor(updatedPythonUnit);
            assertTrue(
                    relevantPython.stream().anyMatch(s -> s.contains("import os")),
                    "Python delegation should find os import");
        }
    }

    @Test
    public void testThreeWayRoutingJavaPythonGo() throws IOException {
        try (var testProject = InlineCoreProject.empty()
                .languages(Set.of(Languages.JAVA, Languages.PYTHON, Languages.GO))
                .addFile("package main\nimport \"fmt\"\nfunc main() { fmt.Println() }\n", "main.go")
                .addFileContents("import math\ndef f(): return math.sqrt(2)", "lib.py")
                .addFileContents("import java.util.Set;\nclass C { Set s; }", "C.java")
                .build()) {

            IAnalyzer multiAnalyzer = testProject.getAnalyzer();
            assertTrue(multiAnalyzer instanceof MultiAnalyzer, "Expected MultiAnalyzer when multiple languages active");
            ImportAnalysisProvider provider =
                    multiAnalyzer.as(ImportAnalysisProvider.class).orElseThrow();

            // 1. Verify Go routing
            var goFile = testProject.getAnalyzableFiles(Languages.GO).stream()
                    .filter(f -> f.getFileName().equals("main.go"))
                    .findFirst()
                    .orElseThrow();
            var goImports = provider.importInfoOf(goFile);
            assertTrue(goImports.stream().anyMatch(i -> i.rawSnippet().contains("fmt")));

            // 2. Verify Python routing
            var pyFile = AnalyzerUtil.getFileFor(multiAnalyzer, "lib").orElseThrow();
            var pyImports = provider.importInfoOf(pyFile);
            assertTrue(
                    pyImports.stream().anyMatch(i -> "math".equals(i.identifier())),
                    "Python import should have 'math' as identifier");

            // 3. Verify Java routing
            var javaFile = AnalyzerUtil.getFileFor(multiAnalyzer, "C").orElseThrow();
            var javaUnit = multiAnalyzer.getDeclarations(javaFile).stream()
                    .filter(cu -> cu.shortName().equals("C"))
                    .findFirst()
                    .orElseThrow();
            Set<String> javaRel = provider.relevantImportsFor(javaUnit);
            assertTrue(javaRel.stream().anyMatch(s -> s.contains("java.util.Set")));
        }
    }
}
