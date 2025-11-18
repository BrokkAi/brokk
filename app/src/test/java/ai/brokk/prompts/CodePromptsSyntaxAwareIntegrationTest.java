package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for enabling SYNTAX_AWARE instruction flags using a temporary project.
 *
 * These tests validate that:
 * - Java-only workspaces enable SYNTAX_AWARE.
 * - Multi-language workspaces that include Java also enable SYNTAX_AWARE.
 * - Workspaces without any Java editable files do NOT enable SYNTAX_AWARE.
 * - An empty editable set does NOT enable SYNTAX_AWARE.
 *
 * The projects are created using InlineTestProjectCreator which configures the project's language(s)
 * based on the files present (single-language or a Language.MultiLanguage for mixed projects).
 */
public class CodePromptsSyntaxAwareIntegrationTest {

    @Test
    void testJavaOnlyProject_enablesSyntaxAware() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                        """
                        package p;

                        public class A {
                            public String greet(String name) { return "Hello, " + name; }
                        }
                        """
                                .stripIndent(),
                        "src/main/java/p/A.java")
                .build()) {

            var editable = Set.of(new ProjectFile(project.getRoot(), "src/main/java/p/A.java"));
            var flags = CodePrompts.instructionsFlags(editable);

            assertTrue(
                    flags.contains(CodePrompts.InstructionsFlags.SYNTAX_AWARE),
                    "Expected SYNTAX_AWARE to be enabled for Java-only project");
        }
    }

    @Test
    void testMultiLanguageWithJava_disablesSyntaxAwareWhenEditableNotAllJava() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                        """
                        package p;

                        public class A {
                            public int add(int a, int b) { return a + b; }
                        }
                        """
                                .stripIndent(),
                        "src/main/java/p/A.java")
                .addFileContents(
                        """
                        export function sum(a, b) { return a + b; }
                        """,
                        "web/app.js")
                .build()) {

            var editable = Set.of(
                    new ProjectFile(project.getRoot(), "src/main/java/p/A.java"),
                    new ProjectFile(project.getRoot(), "web/app.js"));
            var flags = CodePrompts.instructionsFlags(editable);

            assertFalse(
                    flags.contains(CodePrompts.InstructionsFlags.SYNTAX_AWARE),
                    "Expected SYNTAX_AWARE to be disabled when editable set includes non-Java files");
        }
    }

    @Test
    void testMultiLanguageWithoutJava_disablesSyntaxAware() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                        """
                        def greet(name):
                            return f"Hello, {name}"
                        """
                                .stripIndent(),
                        "tools/util.py")
                .addFileContents(
                        """
                        export function greet(name) { return `Hello, ${name}`; }
                        """,
                        "web/app.js")
                .build()) {

            var editable = Set.of(
                    new ProjectFile(project.getRoot(), "tools/util.py"),
                    new ProjectFile(project.getRoot(), "web/app.js"));
            var flags = CodePrompts.instructionsFlags(editable);

            assertFalse(
                    flags.contains(CodePrompts.InstructionsFlags.SYNTAX_AWARE),
                    "Expected SYNTAX_AWARE to be disabled when no editable Java files are present");
        }
    }

    @Test
    void testEmptyEditableSet_disablesSyntaxAware() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                        """
                        package p;
                        public class B {}
                        """
                                .stripIndent(),
                        "src/main/java/p/B.java")
                .build()) {

            var editable = Set.<ProjectFile>of(); // intentionally empty
            var flags = CodePrompts.instructionsFlags(editable);

            assertFalse(
                    flags.contains(CodePrompts.InstructionsFlags.SYNTAX_AWARE),
                    "Expected SYNTAX_AWARE to be disabled for empty editable set");
        }
    }
}
