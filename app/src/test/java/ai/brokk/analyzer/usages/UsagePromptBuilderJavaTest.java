package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.project.IProject;
import ai.brokk.testutil.AssertionHelperUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class UsagePromptBuilderJavaTest {

    private static IProject testProject;
    private static TreeSitterAnalyzer analyzer;

    @BeforeAll
    public static void setup() throws IOException {
        testProject = createTestProject("testcode-java");
        analyzer = new JavaAnalyzer(testProject);
    }

    @AfterAll
    public static void teardown() {
        try {
            testProject.close();
        } catch (Exception ignored) {
        }
    }

    private static IProject createTestProject(String subDir) {
        var testDir = Path.of("./src/test/resources", subDir).toAbsolutePath().normalize();
        assertTrue(Files.exists(testDir), String.format("Test resource dir missing: %s", testDir));
        assertTrue(Files.isDirectory(testDir), String.format("%s is not a directory", testDir));

        return new IProject() {
            @Override
            public Path getRoot() {
                return testDir.toAbsolutePath();
            }

            @Override
            public Set<ProjectFile> getAllFiles() {
                var files = testDir.toFile().listFiles();
                if (files == null) {
                    return Collections.emptySet();
                }
                return Arrays.stream(files)
                        .map(file -> new ProjectFile(testDir, testDir.relativize(file.toPath())))
                        .collect(Collectors.toSet());
            }
        };
    }

    private static ProjectFile fileInProject(String filename) {
        Path abs = testProject.getRoot().resolve(filename).normalize();
        assertTrue(Files.exists(abs), "Missing test file: " + abs);
        return new ProjectFile(testProject.getRoot(), testProject.getRoot().relativize(abs));
    }

    @Test
    public void buildReturnsSingleRecordWithExpectedFields() {
        // Given a single file with a snippet containing XML special chars
        ProjectFile file = fileInProject("A.java");
        String snippet = "{ // line1\n\tA.method2();//<T> & \"quotes\" and 'single'\n} // line3";
        CodeUnit enclosing = CodeUnit.cls(file, "test", "A");
        CodeUnit target = CodeUnit.fn(file, "test", "method2");
        UsageHit hit = new UsageHit(file, 10, 0, snippet.length(), enclosing, 1.0, snippet);

        // When
        UsagePrompt prompt = UsagePromptBuilder.buildPrompt(
                hit, target, Collections.emptyList(), analyzer, "A.method2", 10_000 // generous token budget
                );

        // Field-level assertions
        assertNotNull(prompt.filterDescription(), "filterDescription should not be null");
        assertTrue(
                prompt.filterDescription().contains(target.toString()),
                "filterDescription should include the target code unit");
        assertFalse(
                prompt.filterDescription().contains("alternative code units"),
                "filterDescription should NOT mention alternatives when none provided");
        assertEquals(snippet, prompt.candidateText(), "candidateText should equal the usage snippet");

        String text = prompt.promptText();
        AssertionHelperUtil.assertCodeEquals(
                """
                Short Name of Search: A.method2
                Code Unit Target: FUNCTION[test.method2]
                Other Possible Matches: (none)
                File of Hit: A.java
                ```java
                import java.util.function.Function;

                // snippet of method containing possible usage test.A
                { // line1
                	A.method2();//<T> & "quotes" and 'single'
                } // line3
                // rest of class
                ```
                """,
                text);
    }

    @Test
    public void buildIncludesAlternativesWhenPresent() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "test", "A");
        CodeUnit target = CodeUnit.fn(file, "test", "method2");
        CodeUnit alt1 = CodeUnit.fn(file, "other", "method2");
        CodeUnit alt2 = CodeUnit.fn(file, "another", "method2");
        UsageHit hit = new UsageHit(file, 10, 0, 10, enclosing, 1.0, "snippet");

        UsagePrompt prompt =
                UsagePromptBuilder.buildPrompt(hit, target, List.of(alt1, alt2), analyzer, "method2", 10_000);

        assertTrue(
                prompt.filterDescription().contains("alternative code units"),
                "filterDescription should mention alternatives");

        String text = prompt.promptText();
        assertTrue(
                text.contains("Other Possible Matches:\nother.method2\nanother.method2"),
                "Prompt should list alternative fqNames");
    }

    @Test
    public void buildTruncatesWhenOverTokenLimit() {
        ProjectFile file = fileInProject("A.java");
        // Create a very large snippet to force truncation given a tiny token budget
        String largeSnippet = "x".repeat(10_000);
        CodeUnit enclosing = CodeUnit.cls(file, "", "A");
        CodeUnit target = CodeUnit.fn(file, "", "A.method2");
        UsageHit hit = new UsageHit(file, 1, 0, largeSnippet.length(), enclosing, 1.0, largeSnippet);

        int maxTokens = 200; // 800 chars, well above the 512 min floor in the builder
        int maxChars = maxTokens * 4;
        UsagePrompt prompt = UsagePromptBuilder.buildPrompt(hit, target, List.of(), analyzer, "A.method2", maxTokens);

        String text = prompt.promptText();

        // 1. Verify truncation marker is present
        assertTrue(text.contains("truncated due to token limit"), "Expected truncation note in prompt");

        // 2. Verify length is within reasonable budget (maxChars + safety for marker/fence)
        // The builder uses maxChars as a target for the content before the marker.
        assertTrue(
                text.length() <= maxChars + 100,
                "Prompt length " + text.length() + " exceeded budget of " + (maxChars + 100));

        // 3. Verify it ends with closing fence (even if marker follows) or is well-formed
        // Note: The builder appends the marker AFTER the closing fence logic.
        assertTrue(
                text.strip().endsWith("```") || text.contains("```\n... [truncated"),
                "Prompt should contain a closing code fence to remain well-formed Markdown");
    }

    @Test
    public void buildHasMarkdownStructure() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "", "A");
        CodeUnit target = CodeUnit.fn(file, "", "A.method2");
        UsageHit hit = new UsageHit(file, 5, 0, 3, enclosing, 1.0, "sa");

        UsagePrompt prompt = UsagePromptBuilder.buildPrompt(hit, target, List.of(), analyzer, "A.method2", 10_000);

        String text = prompt.promptText();
        assertTrue(text.contains("Short Name of Search: "), "Expected Short Name of Search: prefix");
        assertTrue(text.contains("Code Unit Target: "), "Expected Code Unit Target: prefix");
        assertTrue(text.contains("File of Hit: "), "Expected File of Hit: prefix");
        assertTrue(text.contains("```"), "Expected Markdown code fence");
        assertTrue(text.contains(file.getRelPath().toString()), "Expected the correct file path in prompt");
    }

    @Test
    public void testMultipleHitsNonOverlappingSnippetsAreBothIncluded() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "test", "A");
        CodeUnit target = CodeUnit.fn(file, "test", "method2");

        String snippet1 = "// hit at line 5\nfoo();";
        UsageHit hit1 = new UsageHit(file, 5, 100, 120, enclosing, 1.0, snippet1);

        String snippet2 = "// hit at line 20\nbar();";
        UsageHit hit2 = new UsageHit(file, 20, 500, 520, enclosing, 1.0, snippet2);

        UsagePrompt prompt = UsagePromptBuilder.buildPrompt(
                List.of(hit1, hit2), target, List.of(), analyzer, "method2", 10_000);

        String text = prompt.promptText();
        assertTrue(text.contains(snippet1), "Prompt should contain the first snippet");
        assertTrue(text.contains(snippet2), "Prompt should contain the second snippet");
        assertTrue(text.contains("...\n"), "Prompt should contain an ellipsis separator for non-overlapping hits");
    }

    @Test
    public void testMultipleHitsOverlappingSnippetsAreDeduplicated() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "test", "A");
        CodeUnit target = CodeUnit.fn(file, "test", "method2");

        // Snippets simulate ~3 lines of context.
        // Hit at line 5: context lines [2, 3, 4, 5, 6, 7, 8]
        String snippet1 = "line2\nline3\nline4\nline5-hit\nline6\nline7\nline8";
        UsageHit hit1 = new UsageHit(file, 5, 10, 20, enclosing, 1.0, snippet1);

        // Hit at line 7: context lines [4, 5, 6, 7, 8, 9, 10]
        String snippet2 = "line4\nline5-hit\nline6\nline7-hit\nline8\nline9\nline10";
        UsageHit hit2 = new UsageHit(file, 7, 30, 40, enclosing, 1.0, snippet2);

        UsagePrompt prompt = UsagePromptBuilder.buildPrompt(
                List.of(hit1, hit2), target, List.of(), analyzer, "method2", 10_000);

        String text = prompt.promptText();

        // Check for merged content
        assertTrue(text.contains("line5-hit"), "Should contain first hit");
        assertTrue(text.contains("line7-hit"), "Should contain second hit");
        assertTrue(text.contains("line2"), "Should contain start of context");
        assertTrue(text.contains("line10"), "Should contain end of context");

        // Check for lack of duplicates and ellipsis
        assertFalse(text.contains("line6\nline6"), "Should not contain duplicate lines");
        assertFalse(text.contains("..."), "Should not contain ellipsis for overlapping hits");
    }

    @Test
    public void testSingleHitDelegatesToListMethod() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "test", "A");
        CodeUnit target = CodeUnit.fn(file, "test", "method2");
        UsageHit hit = new UsageHit(file, 10, 100, 110, enclosing, 1.0, "snippet");

        UsagePrompt singlePrompt = UsagePromptBuilder.buildPrompt(
                hit, target, List.of(), analyzer, "method2", 10_000);
        UsagePrompt listPrompt = UsagePromptBuilder.buildPrompt(
                List.of(hit), target, List.of(), analyzer, "method2", 10_000);

        assertEquals(singlePrompt.promptText(), listPrompt.promptText(), "Prompt text should match");
        assertEquals(singlePrompt.candidateText(), listPrompt.candidateText(), "Candidate text should match");
        assertEquals(singlePrompt.filterDescription(), listPrompt.filterDescription(), "Filter description should match");
    }
}
