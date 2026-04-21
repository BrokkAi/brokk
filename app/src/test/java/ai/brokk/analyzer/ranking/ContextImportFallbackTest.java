package ai.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.git.GitRepo;
import ai.brokk.testutil.AnalyzerCreator;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public final class ContextImportFallbackTest {

    @Test
    void gitInsufficientResults_usesImportRankingFallback() throws Exception {
        // We create a project where Git signal might be insufficient for the requested topK.
        // File 'K1' is NOT a seed, but is imported by seed 'A1'.
        try (var project = InlineTestProjectCreator.code(
                        "package test; import test.K1; public class A1 {}", "test/A1.java")
                .addFileContents("package test; public class B1 {}", "test/B1.java")
                .addFileContents("package test; public class C1 {}", "test/C1.java")
                .addFileContents("package test; public class D1 {}", "test/D1.java")
                .addFileContents("package test; public class E1 {}", "test/E1.java")
                .addFileContents("package test; public class F1 {}", "test/F1.java")
                .addFileContents("package test; public class G1 {}", "test/G1.java")
                .addFileContents("package test; public class H1 {}", "test/H1.java")
                .addFileContents("package test; public class I1 {}", "test/I1.java")
                .addFileContents("package test; public class J1 {}", "test/J1.java")
                // The target result:
                .addFileContents("package test; public class K1 {}", "test/K1.java")
                .withMockGit()
                .addCommit("test/A1.java", "test/B1.java")
                .addCommit("test/B1.java", "test/C1.java")
                .build()) {

            assertTrue(project.hasGit(), "Project should have Git enabled");
            assertTrue(project.getRepo() instanceof GitRepo, "Repo should be a concrete GitRepo");

            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            Map<String, ProjectFile> filesByRelPath = analyzer.getAllDeclarations().stream()
                    .map(CodeUnit::source)
                    .distinct()
                    .collect(Collectors.toMap(
                            f -> project.getRoot()
                                    .relativize(f.absPath())
                                    .toString()
                                    .replace('\\', '/'),
                            f -> f));

            IContextManager cm = new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer);

            // Create context with seed fragments
            Context ctx = new Context(cm);
            List<ContextFragment> fragments = new ArrayList<>();
            String[] seedPaths = {
                "test/A1.java", "test/B1.java", "test/C1.java", "test/D1.java", "test/E1.java",
                "test/F1.java", "test/G1.java", "test/H1.java", "test/I1.java", "test/J1.java"
            };
            for (String path : seedPaths) {
                fragments.add(new ContextFragments.ProjectPathFragment(filesByRelPath.get(path), cm));
            }
            ctx = ctx.addFragments(fragments);

            // A1 imports K1.
            // Even if Git distance finds some files (like B1/C1 if they were not seeds),
            // the fallback to ImportPageRanker should ensure K1 is found.
            List<ProjectFile> results = ctx.getMostRelevantFiles(5);

            ProjectFile k1 = filesByRelPath.get("test/K1.java");
            assertTrue(results.contains(k1), "Expected K1.java in results via import ranking fallback");

            // Verify seeds are excluded
            for (String path : seedPaths) {
                assertFalse(results.contains(filesByRelPath.get(path)), "Seed " + path + " should be excluded");
            }
        }
    }
}
