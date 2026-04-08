package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.DisabledAnalyzer;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.mcpserver.StandaloneCodeIntelligence;
import ai.brokk.project.CoreProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchToolsTest {

    @TempDir
    Path tempDir;

    private CoreProject project;

    @AfterEach
    void tearDown() {
        if (project != null) {
            project.close();
        }
    }

    @Test
    void findFilenames_AppendsRelatedContent() throws Exception {
        Path projectRoot = initRepo();
        commitTrackedFiles(
                projectRoot,
                Map.of("A.java", "class A {}", "B.java", "class B {}"),
                Instant.parse("2025-01-01T00:00:00Z"),
                "Add A and B together");

        project = new CoreProject(projectRoot);
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, new DisabledAnalyzer(project)));

        String result = tools.findFilenames(List.of("A\\.java"), 10);
        String relatedSection = relatedContentSection(result);

        assertTrue(result.contains("## Related Content"), "Should include related content header");
        assertTrue(relatedSection.contains("B.java"), "Should include a related file");
        assertFalse(relatedSection.contains("A.java"), "Should not echo the seed file");
    }

    @Test
    void searchSymbols_AppendsRelatedContent() throws Exception {
        Path projectRoot = initRepo();
        commitTrackedFiles(
                projectRoot,
                Map.of(
                        "A.java",
                        """
                        class A {}
                        """.stripIndent(),
                        "B.java",
                        """
                        class B {}
                        """.stripIndent()),
                Instant.parse("2025-01-01T00:00:00Z"),
                "Add A and B together");

        project = new CoreProject(projectRoot);
        ProjectFile aFile = new ProjectFile(projectRoot, "A.java");
        ProjectFile bFile = new ProjectFile(projectRoot, "B.java");
        CodeUnit aClass = CodeUnit.cls(aFile, "", "A");
        CodeUnit bClass = CodeUnit.cls(bFile, "", "B");
        IAnalyzer analyzer = new DisabledAnalyzer(project) {
            @Override
            public Set<CodeUnit> searchDefinitions(String pattern) {
                return "A".equals(pattern) ? Set.of(aClass) : Set.of();
            }

            @Override
            public SequencedSet<CodeUnit> getDefinitions(String fqName) {
                SequencedSet<CodeUnit> results = new LinkedHashSet<>();
                if ("A".equals(fqName)) {
                    results.add(aClass);
                } else if ("B".equals(fqName)) {
                    results.add(bClass);
                }
                return results;
            }
        };
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, analyzer));

        String result = tools.searchSymbols(List.of("A"), false, 200);
        String relatedSection = relatedContentSection(result);

        assertTrue(result.contains("## Related Content"), "Should include related content header");
        assertTrue(relatedSection.contains("B.java"), "Should include a related file");
        assertFalse(relatedSection.contains("A.java"), "Should not echo the seed file");
    }

    @Test
    void findFilenames_TracksResearchTokensIncludingRelatedContent() throws Exception {
        Path projectRoot = initRepo();
        commitTrackedFiles(
                projectRoot,
                Map.of("A.java", "class A {}", "B.java", "class B {}"),
                Instant.parse("2025-01-01T00:00:00Z"),
                "Add A and B together");

        project = new CoreProject(projectRoot);
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, new DisabledAnalyzer(project)));

        assertEquals(0L, tools.getAndClearResearchTokens(), "Counter should start empty");

        String result = tools.findFilenames(List.of("A\\.java"), 10);
        long countedTokens = tools.getAndClearResearchTokens();

        assertTrue(result.contains("## Related Content"), "Should include related content header");
        assertTrue(countedTokens > 0, "Final output should be counted as research tokens");
        assertEquals(0L, tools.getAndClearResearchTokens(), "Counter should reset after reading");
    }

    private Path initRepo() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Files.createDirectories(projectRoot);
        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            Files.writeString(projectRoot.resolve("README.md"), "# Test");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("init")
                    .setAuthor("Test", "test@test.com")
                    .setSign(false)
                    .call();
        }
        return projectRoot;
    }

    private static void commitTrackedFiles(
            Path projectRoot, Map<String, String> filesByPath, Instant instant, String message) throws Exception {
        try (Git git = Git.open(projectRoot.toFile())) {
            var ident = new PersonIdent("Test User", "test@example.com", instant, ZoneId.of("UTC"));
            for (var entry : filesByPath.entrySet()) {
                Path file = projectRoot.resolve(entry.getKey());
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, entry.getValue());
                git.add().addFilepattern(entry.getKey().replace('\\', '/')).call();
            }
            git.commit()
                    .setMessage(message)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .setSign(false)
                    .call();
        }
    }

    private static String relatedContentSection(String text) {
        int relatedContentIdx = text.indexOf("\n\n## Related Content\n");
        return relatedContentIdx >= 0 ? text.substring(relatedContentIdx) : "";
    }
}
