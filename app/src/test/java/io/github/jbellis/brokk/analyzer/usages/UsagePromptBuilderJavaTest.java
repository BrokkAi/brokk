package io.github.jbellis.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer;
import io.github.jbellis.brokk.analyzer.JavaTreeSitterAnalyzer;
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
        analyzer = new JavaTreeSitterAnalyzer(testProject);
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
    public void buildIncludesBasicStructureAndEscaping() {
        // Given a single file with a snippet containing XML special chars
        ProjectFile file = fileInProject("A.java");
        String snippet = "line1\n<T> & \"quotes\" and 'single'\nline3";
        UsageHit hit = new UsageHit(file, 10, 0, snippet.length(), 1.0, snippet);
        Map<ProjectFile, List<UsageHit>> hitsByFile = Map.of(file, List.of(hit));

        // When
        String prompt = UsagePromptBuilder.buildPrompt(
                hitsByFile,
                Collections.emptyList(),      // no candidates
                analyzer,
                "A.method2",
                10_000                        // generous token budget
        );

        // Then
        assertTrue(prompt.contains("<file path=\""), "Expected <file> tag with path");
        assertTrue(prompt.contains(file.absPath().toString()), "Expected absolute path in file tag");
        assertTrue(prompt.contains("<imports>") && prompt.contains("</imports>"), "Expected imports section");
        assertTrue(prompt.contains("<usage id=\"1\">"), "Expected usage id=1");
        // Ensure special chars are escaped
        assertTrue(prompt.contains("&lt;T&gt;"), "Expected '<' and '>' to be escaped");
        assertTrue(prompt.contains("&amp;"), "Expected '&' to be escaped");
        assertTrue(prompt.contains("&quot;quotes&quot;"), "Expected '\"' to be escaped");
        assertTrue(prompt.contains("&apos;single&apos;"), "Expected ''' to be escaped");
    }

    @Test
    public void buildTruncatesWhenOverTokenLimit() {
        ProjectFile file = fileInProject("A.java");
        // Create a very large snippet to force truncation given a tiny token budget
        String largeSnippet = "x".repeat(10_000);
        UsageHit hit = new UsageHit(file, 1, 0, largeSnippet.length(), 1.0, largeSnippet);
        Map<ProjectFile, List<UsageHit>> hitsByFile = Map.of(file, List.of(hit));

        String prompt = UsagePromptBuilder.buildPrompt(
                hitsByFile,
                Collections.emptyList(),
                analyzer,
                "A.method2",
                32 // ~128 chars budget to trigger truncation
        );

        assertTrue(prompt.contains("truncated due to token limit"), "Expected truncation note in prompt");
    }

    @Test
    public void buildOrdersFilesDeterministically() {
        // Use A.java and B.java to ensure consistent lexical ordering by absolute path
        ProjectFile a = fileInProject("A.java");
        ProjectFile b = fileInProject("B.java");

        UsageHit ha = new UsageHit(a, 5, 0, 3, 1.0, "sa");
        UsageHit hb = new UsageHit(b, 6, 0, 3, 1.0, "sb");

        Map<ProjectFile, List<UsageHit>> hitsByFile = new LinkedHashMap<>();
        // Intentionally put B first to ensure builder reorders
        hitsByFile.put(b, List.of(hb));
        hitsByFile.put(a, List.of(ha));

        String prompt = UsagePromptBuilder.buildPrompt(
                hitsByFile,
                Collections.emptyList(),
                analyzer,
                "A.method2",
                10_000
        );

        String pa = a.absPath().toString();
        String pb = b.absPath().toString();
        int idxA = prompt.indexOf(pa);
        int idxB = prompt.indexOf(pb);

        assertTrue(idxA >= 0 && idxB >= 0, "Expected both file paths in prompt");
        assertTrue(idxA < idxB, "Expected A.java to appear before B.java in prompt");
    }
}
