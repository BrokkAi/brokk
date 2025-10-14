package io.github.jbellis.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.JavaAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
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
        String snippet = "line1\n<T> & \"quotes\" and 'single'\nline3";
        CodeUnit enclosing = CodeUnit.cls(file, "test", "A");
        CodeUnit target = CodeUnit.fn(file, "test", "method2");
        UsageHit hit = new UsageHit(file, 10, 0, snippet.length(), enclosing, 1.0, snippet);

        // When
        UsagePrompt prompt = UsagePromptBuilder.buildPrompt(
                hit, target, Collections.emptyList(), analyzer, "A.method2", 10_000 // generous token budget
                );

        // Then: record has the three expected accessors and nothing else
        RecordComponent[] components = prompt.getClass().getRecordComponents();
        var names = Arrays.stream(components).map(RecordComponent::getName).collect(Collectors.toSet());
        assertEquals(
                Set.of("filterDescription", "candidateText", "promptText"),
                names,
                "Record must contain only the expected fields");

        // Field-level assertions
        assertNotNull(prompt.filterDescription(), "filterDescription should not be null");
        assertTrue(
                prompt.filterDescription().contains(target.toString()),
                "filterDescription should include the target code unit");
        assertEquals(snippet, prompt.candidateText(), "candidateText should equal the usage snippet");

        String text = prompt.promptText();
        assertNotNull(text, "promptText should not be null");
        assertTrue(text.contains("<file path=\""), "Expected <file> tag with path");
        assertTrue(text.contains(file.absPath().toString()), "Expected absolute path in file tag");
        assertTrue(text.contains("<imports>") && text.contains("</imports>"), "Expected imports section");
        assertTrue(text.contains("<usage>") && text.contains("</usage>"), "Expected a single <usage> block");
        assertFalse(text.contains("id="), "No id attributes should be present");

        // Ensure special chars are escaped
        assertTrue(text.contains("&lt;T&gt;"), "Expected '<' and '>' to be escaped");
        assertTrue(text.contains("&amp;"), "Expected '&' to be escaped");
        assertTrue(text.contains("&quot;quotes&quot;"), "Expected '\"' to be escaped");
        assertTrue(
                text.contains("&apos;single&apos;") || text.contains("&#39;single&#39;"), "Expected ''' to be escaped");
    }

    @Test
    public void buildTruncatesWhenOverTokenLimit() {
        ProjectFile file = fileInProject("A.java");
        // Create a very large snippet to force truncation given a tiny token budget
        String largeSnippet = "x".repeat(10_000);
        CodeUnit enclosing = CodeUnit.cls(file, "", "A");
        CodeUnit target = CodeUnit.fn(file, "", "A.method2");
        UsageHit hit = new UsageHit(file, 1, 0, largeSnippet.length(), enclosing, 1.0, largeSnippet);

        UsagePrompt prompt = UsagePromptBuilder.buildPrompt(
                hit,
                target,
                Collections.emptyList(),
                analyzer,
                "A.method2",
                32 // ~128 chars budget to trigger truncation
                );

        assertTrue(prompt.promptText().contains("truncated due to token limit"), "Expected truncation note in prompt");
    }

    @Test
    public void buildHasNoIdsInPromptText() {
        ProjectFile file = fileInProject("A.java");
        CodeUnit enclosing = CodeUnit.cls(file, "", "A");
        CodeUnit target = CodeUnit.fn(file, "", "A.method2");
        UsageHit hit = new UsageHit(file, 5, 0, 3, enclosing, 1.0, "sa");

        UsagePrompt prompt =
                UsagePromptBuilder.buildPrompt(hit, target, Collections.emptyList(), analyzer, "A.method2", 10_000);

        String text = prompt.promptText();
        assertTrue(text.contains("<usage>") && text.contains("</usage>"), "Expected a single <usage> block");
        assertFalse(text.contains("id="), "No id attributes should be present");
        assertTrue(text.contains(file.absPath().toString()), "Expected the correct file path in prompt");
    }
}
