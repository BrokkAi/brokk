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

public class UsagePromptJavaTest {

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
        UsagePrompt prompt = UsagePrompt.build(
                hit, target, List.of(), List.of(), true, analyzer, 10_000, List.of(target) // generous token budget
                );

        // Field-level assertions
        assertNotNull(prompt.filterDescription(), "filterDescription should not be null");
        assertTrue(
                prompt.filterDescription().contains(target.fqName()),
                "filterDescription should include the target fqName");
        assertTrue(
                prompt.filterDescription().contains(target.kind().name()),
                "filterDescription should include the target kind");
        assertTrue(
                prompt.filterDescription().contains("(none)"),
                "filterDescription should mention (none) when no alternatives provided");

        String text = prompt.promptText();
        assertTrue(text.contains(prompt.candidateText()), "promptText should contain the candidateText XML block");
        AssertionHelperUtil.assertCodeEquals(
                """
                <candidate filename="A.java">
                  <imports>
                    import java.util.function.Function;
                  </imports>
                  <snippet sourcemethod="A">
                    { // line1
                    	A.method2();//<T> & "quotes" and 'single'
                    } // line3
                  </snippet>
                </candidate>
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
                UsagePrompt.build(hit, target, List.of(alt1, alt2), List.of(), true, analyzer, 10_000, List.of(target));

        assertTrue(
                prompt.filterDescription().contains("other.method2, another.method2"),
                "filterDescription should mention alternatives");

        String text = prompt.promptText();
        assertFalse(text.contains("Other Possible Matches"), "Metadata headers should be removed from promptText");
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
        UsagePrompt prompt =
                UsagePrompt.build(hit, target, List.of(), List.of(), true, analyzer, maxTokens, List.of(target));

        String text = prompt.promptText();

        // 1. Verify truncation marker is present
        assertTrue(text.contains("truncated due to token limit"), "Expected truncation note in prompt");

        // 2. Verify length is within reasonable budget (maxChars + safety for marker)
        assertTrue(
                text.length() <= maxChars + 100,
                "Prompt length " + text.length() + " exceeded budget of " + (maxChars + 100));

        // 3. Verify it is well-formed enough
        assertTrue(
                text.contains("</candidate>") || text.contains("... [truncated"),
                "Prompt should contain closing tags or truncation marker");
    }

    @Test
    public void buildHasXmlStructure() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "", "A");
        CodeUnit target = CodeUnit.fn(file, "", "A.method2");
        UsageHit hit = new UsageHit(file, 5, 0, 3, enclosing, 1.0, "sa");

        UsagePrompt prompt =
                UsagePrompt.build(hit, target, List.of(), List.of(), true, analyzer, 10_000, List.of(target));

        String text = prompt.promptText();
        assertFalse(text.contains("Short Name of Search: "), "Metadata headers should be removed");
        assertTrue(text.contains("<candidate filename=\"A.java\">"), "Expected candidate tag with filename");
        assertTrue(text.contains("<imports>"), "Expected imports tag");
        assertTrue(text.contains("<snippet sourcemethod=\"A\">"), "Expected snippet tag with sourcemethod");
        assertTrue(text.endsWith("</candidate>"), "Expected prompt to end with closing tag");
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

        UsagePrompt prompt = UsagePrompt.build(
                List.of(hit1, hit2), target, List.of(), List.of(), true, analyzer, 10_000, List.of(target));

        String text = prompt.promptText();
        assertTrue(text.contains("foo();"), "Prompt should contain the first snippet code");
        assertTrue(text.contains("bar();"), "Prompt should contain the second snippet code");
        assertTrue(text.contains("..."), "Prompt should contain an ellipsis separator for non-overlapping hits");
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

        UsagePrompt prompt = UsagePrompt.build(
                List.of(hit1, hit2), target, List.of(), List.of(), true, analyzer, 10_000, List.of(target));

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

        UsagePrompt singlePrompt =
                UsagePrompt.build(hit, target, List.of(), List.of(), true, analyzer, 10_000, List.of(target));
        UsagePrompt listPrompt =
                UsagePrompt.build(List.of(hit), target, List.of(), List.of(), true, analyzer, 10_000, List.of(target));

        assertEquals(singlePrompt.promptText(), listPrompt.promptText(), "Prompt text should match");
        assertEquals(singlePrompt.candidateText(), listPrompt.candidateText(), "Candidate text should match");
        assertEquals(
                singlePrompt.filterDescription(), listPrompt.filterDescription(), "Filter description should match");
    }

    @Test
    public void testAdjacentSnippetsMergeWithoutDuplicatingBoundary() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "test", "A");
        CodeUnit target = CodeUnit.fn(file, "test", "method2");

        // Hit at line 5: context covers lines [2, 8]
        String snippet1 = "line2\nline3\nline4\nline5-hit\nline6\nline7\nline8";
        UsageHit hit1 = new UsageHit(file, 5, 10, 20, enclosing, 1.0, snippet1);

        // Hit at line 11: context covers lines [8, 14] - just touches at line 8
        String snippet2 = "line8\nline9\nline10\nline11-hit\nline12\nline13\nline14";
        UsageHit hit2 = new UsageHit(file, 11, 30, 40, enclosing, 1.0, snippet2);

        UsagePrompt prompt = UsagePrompt.build(
                List.of(hit1, hit2), target, List.of(), List.of(), true, analyzer, 10_000, List.of(target));

        String text = prompt.promptText();

        // Should contain both hits
        assertTrue(text.contains("line5-hit"), "Should contain first hit");
        assertTrue(text.contains("line11-hit"), "Should contain second hit");

        // Should NOT have ellipsis since they're adjacent/touching
        assertFalse(text.contains("..."), "Should not contain ellipsis for adjacent hits");

        // Line 8 should appear exactly once (not duplicated)
        int firstIndex = text.indexOf("line8");
        int lastIndex = text.lastIndexOf("line8");
        assertEquals(firstIndex, lastIndex, "Boundary line 8 should appear exactly once");
    }

    @Test
    public void testOverlappingSnippetsMergeWithProperDeduplication() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "test", "A");
        CodeUnit target = CodeUnit.fn(file, "test", "method2");

        // Hit at line 5: context covers lines [2, 8]
        String snippet1 = "line2\nline3\nline4\nline5-hit\nline6\nline7\nline8";
        UsageHit hit1 = new UsageHit(file, 5, 10, 20, enclosing, 1.0, snippet1);

        // Hit at line 9: context covers lines [6, 12] - overlaps lines 6,7,8
        String snippet2 = "line6\nline7\nline8\nline9-hit\nline10\nline11\nline12";
        UsageHit hit2 = new UsageHit(file, 9, 30, 40, enclosing, 1.0, snippet2);

        UsagePrompt prompt = UsagePrompt.build(
                List.of(hit1, hit2), target, List.of(), List.of(), true, analyzer, 10_000, List.of(target));

        String text = prompt.promptText();

        // Both hits should be present
        assertTrue(text.contains("line5-hit"), "Should contain first hit");
        assertTrue(text.contains("line9-hit"), "Should contain second hit");

        // No ellipsis for overlapping
        assertFalse(text.contains("..."), "Should not contain ellipsis for overlapping hits");

        // Verify no duplicate lines in overlapping region
        String candidateText = prompt.candidateText();
        assertEquals(1, countOccurrences(candidateText, "line6"), "line6 should appear exactly once");
        assertEquals(1, countOccurrences(candidateText, "line7"), "line7 should appear exactly once");
    }

    @Test
    public void testNonOverlappingSnippetsStaySeparateWithEllipsis() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "test", "A");
        CodeUnit target = CodeUnit.fn(file, "test", "method2");

        // Hit at line 5: context covers lines [2, 8]
        String snippet1 = "line2\nline3\nline4\nline5-hit\nline6\nline7\nline8";
        UsageHit hit1 = new UsageHit(file, 5, 10, 20, enclosing, 1.0, snippet1);

        // Hit at line 20: context covers lines [17, 23] - far from first hit
        String snippet2 = "line17\nline18\nline19\nline20-hit\nline21\nline22\nline23";
        UsageHit hit2 = new UsageHit(file, 20, 100, 110, enclosing, 1.0, snippet2);

        UsagePrompt prompt = UsagePrompt.build(
                List.of(hit1, hit2), target, List.of(), List.of(), true, analyzer, 10_000, List.of(target));

        String text = prompt.promptText();

        // Both hits present
        assertTrue(text.contains("line5-hit"), "Should contain first hit");
        assertTrue(text.contains("line20-hit"), "Should contain second hit");

        // Should have ellipsis separator
        assertTrue(text.contains("..."), "Should contain ellipsis for non-overlapping hits");

        // Both full snippets should be present
        assertTrue(text.contains("line2"), "Should contain start of first snippet");
        assertTrue(text.contains("line8"), "Should contain end of first snippet");
        assertTrue(text.contains("line17"), "Should contain start of second snippet");
        assertTrue(text.contains("line23"), "Should contain end of second snippet");
    }

    @Test
    public void testSimilarContentAtDifferentLocationsNotMerged() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "test", "A");
        CodeUnit target = CodeUnit.fn(file, "test", "method2");

        // Identical snippet content at two very different locations
        String identicalSnippet =
                "commonLine1\ncommonLine2\ncommonLine3\ncommonHit\ncommonLine5\ncommonLine6\ncommonLine7";

        // Hit at line 10: context covers lines [7, 13]
        UsageHit hit1 = new UsageHit(file, 10, 100, 110, enclosing, 1.0, identicalSnippet);

        // Hit at line 100: context covers lines [97, 103] - same text but very different location
        UsageHit hit2 = new UsageHit(file, 100, 500, 510, enclosing, 1.0, identicalSnippet);

        UsagePrompt prompt = UsagePrompt.build(
                List.of(hit1, hit2), target, List.of(), List.of(), true, analyzer, 10_000, List.of(target));

        String candidateText = prompt.candidateText();

        // Should have ellipsis separator because they're at different locations
        assertTrue(
                candidateText.contains("..."),
                "Should contain ellipsis separator - identical content at different lines should NOT be merged");

        // The identical content should appear TWICE (once for each location)
        assertEquals(
                2,
                countOccurrences(candidateText, "commonHit"),
                "Identical content at different locations should both be included");
    }

    @Test
    public void testSingleHitReturnsUnchanged() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "test", "A");
        CodeUnit target = CodeUnit.fn(file, "test", "method2");

        String originalSnippet = "line1\nline2\nline3\nhit-line\nline5\nline6\nline7";
        UsageHit hit = new UsageHit(file, 5, 10, 20, enclosing, 1.0, originalSnippet);

        UsagePrompt prompt =
                UsagePrompt.build(List.of(hit), target, List.of(), List.of(), true, analyzer, 10_000, List.of(target));

        // candidateText should contain the original snippet
        assertTrue(prompt.candidateText().contains("hit-line"), "Single hit should contain the snippet");
        assertTrue(prompt.candidateText().startsWith("<candidate"), "Should start with candidate tag");

        // No ellipsis
        assertFalse(prompt.candidateText().contains("..."), "Single hit should not contain ellipsis");
    }

    @Test
    public void testPolymorphicMatchesIncludedInFilterDescription() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "test", "A");
        CodeUnit target = CodeUnit.fn(file, "test", "method2");
        CodeUnit poly1 = CodeUnit.cls(file, "test", "SubA1");
        CodeUnit poly2 = CodeUnit.cls(file, "test", "SubA2");
        UsageHit hit = new UsageHit(file, 10, 0, 10, enclosing, 1.0, "snippet");

        UsagePrompt prompt = UsagePrompt.build(
                hit, target, List.of(), List.of(poly1, poly2), true, analyzer, 10_000, List.of(target));

        String desc = prompt.filterDescription();
        assertTrue(desc.contains("SubA1"), "filterDescription should contain first polymorphic match");
        assertTrue(desc.contains("SubA2"), "filterDescription should contain second polymorphic match");
        assertTrue(
                desc.contains("part of the inheritance hierarchy for this method"),
                "filterDescription should contain explanatory text for polymorphic matches");
        assertTrue(
                desc.contains("including via overrides"),
                "filterDescription should mention that overrides are included");
    }

    @Test
    public void testHierarchyNotSupportedIndicatedInFilterDescription() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "test", "A");
        CodeUnit target = CodeUnit.fn(file, "test", "method2");
        UsageHit hit = new UsageHit(file, 10, 0, 10, enclosing, 1.0, "snippet");

        UsagePrompt prompt =
                UsagePrompt.build(hit, target, List.of(), List.of(), false, analyzer, 10_000, List.of(target));

        String desc = prompt.filterDescription();
        assertTrue(
                desc.contains(
                        "Note: type hierarchy information is not available for this language, so polymorphic usages cannot be detected."),
                "filterDescription should contain hierarchy unavailable note");
    }

    private int countOccurrences(String text, String search) {
        int count = 0;
        int index = 0;
        // Strip leading whitespace from each line of text for matching because of XML indentation
        String normalizedText =
                Arrays.stream(text.split("\n")).map(String::stripLeading).collect(Collectors.joining("\n"));

        while ((index = normalizedText.indexOf(search, index)) != -1) {
            // Make sure we're matching a whole line, not a substring
            boolean isLineStart = index == 0 || normalizedText.charAt(index - 1) == '\n';
            boolean isLineEnd = index + search.length() >= normalizedText.length()
                    || normalizedText.charAt(index + search.length()) == '\n';
            if (isLineStart && isLineEnd) {
                count++;
            }
            index += search.length();
        }
        return count;
    }
}
