package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.EditBlock;
import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.AnalyzerCreator;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Ensures BRK_* markers are rejected when the editable workspace contains non-Java files.
 * The defensive assertion in EditBlock.resolveBrkSnippet should throw AssertionError.
 *
 * Also validates that in a multi-language repository, when the editable workspace is Java-only,
 * BRK_FUNCTION resolves correctly and applies without interference from other languages.
 */
public class EditBlockSyntaxAwareAssertionTest {

    @Test
    void brkFunctionResolvesInMultiLanguageRepoWhenEditableIsJavaOnly() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                        """
                        package p;

                        public class A {
                            public String greet(String name) {
                                return "Hello, " + name;
                            }
                        }
                        """
                                .stripIndent(),
                        "src/main/java/p/A.java")
                // Add a non-Java file to make the repository multi-language,
                // but do NOT include it in the editable set.
                .addFileContents(
                        """
                        export function sum(a, b) { return a + b; }
                        """,
                        "web/app.js")
                .build()) {

            var javaFile = new ProjectFile(project.getRoot(), "src/main/java/p/A.java");
            var editable = Set.of(javaFile);

            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            IConsoleIO io = new NoOpConsoleIO();
            var cm = new TestContextManager((TestProject) project, io, editable, analyzer);

            var block = new EditBlock.SearchReplaceBlock(
                    "src/main/java/p/A.java",
                    "BRK_FUNCTION p.A.greet",
                    """
                    public String greet(String name) {
                        return "Hi, " + name;
                    }
                    """
                            .stripIndent());

            var result = assertDoesNotThrow(() -> EditBlock.apply(cm, io, List.of(block)));

            assertTrue(result.hadSuccessfulEdits(), "Expected the BRK_FUNCTION edit to apply successfully");
            assertTrue(result.failedBlocks().isEmpty(), "No failed blocks expected for valid BRK_FUNCTION resolution");

            var updated = javaFile.read().orElseThrow();
            assertTrue(updated.contains("return \"Hi, \" + name;"), "Updated method body should be present");
            assertFalse(updated.contains("return \"Hello, \" + name;"), "Old method body should be replaced");
        }
    }

    @Test
    void brkFunctionWithJavaDocPreservesJavaDoc() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                        """
                        package p;

                        public class A {
                            /**
                             * Greets a person.
                             * @param name the name of the person.
                             * @return a greeting.
                             */
                            public String greet(String name) {
                                return "Hello, " + name;
                            }
                        }
                        """
                                .stripIndent(),
                        "src/main/java/p/A.java")
                .build()) {

            var javaFile = new ProjectFile(project.getRoot(), "src/main/java/p/A.java");
            var editable = Set.of(javaFile);

            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            IConsoleIO io = new NoOpConsoleIO();
            var cm = new TestContextManager((TestProject) project, io, editable, analyzer);

            var block = new EditBlock.SearchReplaceBlock(
                    "src/main/java/p/A.java",
                    "BRK_FUNCTION p.A.greet",
                    """
                    public String greet(String name) {
                        return "Hi, " + name + "!";
                    }
                    """
                            .stripIndent());

            var result = assertDoesNotThrow(() -> EditBlock.apply(cm, io, List.of(block)));

            assertTrue(result.hadSuccessfulEdits(), "Expected the BRK_FUNCTION edit to apply successfully");
            assertTrue(result.failedBlocks().isEmpty(), "No failed blocks expected for valid BRK_FUNCTION resolution");

            var updated = javaFile.read().orElseThrow();
            assertTrue(updated.contains("/**"), "Javadoc should be preserved");
            assertTrue(updated.contains("* Greets a person."), "Javadoc should be preserved");
            assertTrue(updated.contains("return \"Hi, \" + name + \"!\";"), "Updated method body should be present");
            assertFalse(updated.contains("return \"Hello, \" + name;"), "Old method body should be replaced");
        }
    }
}
