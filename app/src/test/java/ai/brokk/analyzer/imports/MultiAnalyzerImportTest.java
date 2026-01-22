package ai.brokk.analyzer.imports;

import static ai.brokk.testutil.AnalyzerCreator.createMultiAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AnalyzerUtil;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class MultiAnalyzerImportTest {

    @Test
    public void testDelegationToJavaAnalyzer() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import java.util.List;
                public class JavaClass {
                    private List<String> items;
                }
                """,
                        "JavaClass.java")
                .build()) {

            var multiAnalyzer = createMultiAnalyzer(testProject, Languages.JAVA);

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
        try (var testProject = InlineTestProjectCreator.code(
                        """
                import os
                def python_fn():
                    pass
                """,
                        "script.py")
                .build()) {

            var multiAnalyzer = createMultiAnalyzer(testProject, Languages.PYTHON);

            var pythonFile = AnalyzerUtil.getFileFor(multiAnalyzer, "script").orElseThrow();

            ImportAnalysisProvider provider =
                    multiAnalyzer.as(ImportAnalysisProvider.class).orElseThrow();

            // Verify importInfoOf delegation - check raw snippet since Python doesn't extract identifiers
            var pythonImports = provider.importInfoOf(pythonFile);
            assertEquals(1, pythonImports.size());
            assertTrue(
                    pythonImports.getFirst().rawSnippet().contains("import os"),
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
        try (var testProject = InlineTestProjectCreator.code(
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

            var multiAnalyzer = createMultiAnalyzer(testProject, Languages.JAVA, Languages.PYTHON);

            // Get files from multi analyzer
            var javaFile = AnalyzerUtil.getFileFor(multiAnalyzer, "JavaClass").orElseThrow();
            var pythonFile = AnalyzerUtil.getFileFor(multiAnalyzer, "script").orElseThrow();

            ImportAnalysisProvider provider =
                    multiAnalyzer.as(ImportAnalysisProvider.class).orElseThrow();

            // Verify importInfoOf routes to correct language analyzer
            var javaImports = provider.importInfoOf(javaFile);
            assertEquals(1, javaImports.size());
            assertEquals("List", javaImports.getFirst().identifier());

            // Python import - check raw snippet since Python doesn't extract identifiers
            var pythonImports = provider.importInfoOf(pythonFile);
            assertEquals(1, pythonImports.size());
            assertTrue(
                    pythonImports.getFirst().rawSnippet().contains("import os"),
                    "Python import raw snippet should contain 'import os'");

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
            assertTrue(relevantJava.stream().anyMatch(s -> s.contains("java.util.List")));

            // Python - just verify delegation works (relevantImportsFor may return empty)
            Set<String> relevantPython = provider.relevantImportsFor(pythonUnit);
            // No assertion on content - we're testing delegation, not Python's import parsing
        }
    }
}
