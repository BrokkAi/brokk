package ai.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.IProject;
import ai.brokk.ranking.ImportPageRanker;
import ai.brokk.testutil.AnalyzerCreator;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class ImportPageRankerTest {

    @Test
    public void seedsExcludeSelfAndRankImportedNeighborsHigher_reversedFalse() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                        """
                        package test;
                        import test.B;
                        public class A {
                            public void a() {}
                        }
                        """,
                        "test/A.java")
                .addFileContents(
                        """
                        package test;
                        import test.C;
                        public class B {
                            public void b() {}
                        }
                        """,
                        "test/B.java")
                .addFileContents(
                        """
                        package test;
                        public class C {
                            public void c() {}
                        }
                        """,
                        "test/C.java")
                .addFileContents(
                        """
                        package test;
                        public class D {
                            public void d() {}
                        }
                        """,
                        "test/D.java")
                .build()) {

            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);

            Map<String, ProjectFile> byName = new HashMap<>();
            for (CodeUnit cu : analyzer.getAllDeclarations()) {
                String name = cu.source().getFileName().toString().toLowerCase(Locale.ROOT);
                byName.putIfAbsent(name, cu.source());
            }

            ProjectFile a = byName.get("a.java");
            ProjectFile b = byName.get("b.java");
            ProjectFile c = byName.get("c.java");

            Map<ProjectFile, Double> seeds = Map.of(a, 1.0);

            List<IAnalyzer.FileRelevance> results =
                    ImportPageRanker.getRelatedFilesByImports(analyzer, seeds, 10, false);

            assertFalse(results.stream().anyMatch(fr -> fr.file().equals(a)));

            assertTrue(results.size() >= 2, "Expected at least two related files");

            ProjectFile first = results.get(0).file();
            ProjectFile second = results.get(1).file();
            assertTrue(
                    (first.equals(b) && second.equals(c)) || (first.equals(c) && second.equals(b)),
                    "Top two related files should be B and C (any order)");
        }
    }

    @Test
    public void gitDisabledWhenManySeedsUntracked_usesImportRanking() throws Exception {
        // We create 11 files. 10 will be seeds.
        // 7 tracked seeds, 3 untracked seeds (3/10 = 30%).
        // File 'K1' is NOT a seed, but is imported by seed 'A1'.
        try (var project = InlineTestProjectCreator.code(
                        "package test; import test.K1; public class A1 {}", "test/A1.java")
                .addFileContents("package test; public class B1 {}", "test/B1.java")
                .addFileContents("package test; public class C1 {}", "test/C1.java")
                .addFileContents("package test; public class D1 {}", "test/D1.java")
                .addFileContents("package test; public class E1 {}", "test/E1.java")
                .addFileContents("package test; public class F1 {}", "test/F1.java")
                .addFileContents("package test; public class G1 {}", "test/G1.java")
                // Untracked seeds:
                .addFileContents("package test; public class H1 {}", "test/H1.java")
                .addFileContents("package test; public class I1 {}", "test/I1.java")
                .addFileContents("package test; public class J1 {}", "test/J1.java")
                // The target result (tracked):
                .addFileContents("package test; public class K1 {}", "test/K1.java")
                .withGit()
                // Track 7 seeds + the target:
                .addCommit("test/A1.java", "test/B1.java")
                .addCommit("test/C1.java", "test/D1.java")
                .addCommit("test/E1.java", "test/F1.java")
                .addCommit("test/G1.java", "test/K1.java")
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

            IContextManager cm = new IContextManager() {
                @Override
                public IAnalyzer getAnalyzer() {
                    return analyzer;
                }

                @Override
                public IProject getProject() {
                    return project;
                }

                @Override
                public IGitRepo getRepo() {
                    return project.getRepo();
                }
            };

            // Create context with 10 seed fragments (A1..J1)
            Context ctx = new Context(cm);
            List<ContextFragment> fragments = new ArrayList<>();
            String[] seedPaths = {
                "test/A1.java", "test/B1.java", "test/C1.java", "test/D1.java", "test/E1.java",
                "test/F1.java", "test/G1.java", "test/H1.java", "test/I1.java", "test/J1.java"
            };
            for (String path : seedPaths) {
                fragments.add(new ContextFragment.ProjectPathFragment(filesByRelPath.get(path), cm));
            }
            ctx = ctx.addFragments(fragments);

            // A1 imports K1.
            // 3/10 seeds (H1, I1, J1) are untracked.
            // This meets the 30% threshold, so Git ranking is disabled.
            // getMostRelevantFiles should fall back to ImportPageRanker and find K1.
            List<ProjectFile> results = ctx.getMostRelevantFiles(5);

            ProjectFile k1 = filesByRelPath.get("test/K1.java");
            assertTrue(results.contains(k1), "Expected K1.java in results via import ranking fallback");

            // Verify seeds are excluded
            for (String path : seedPaths) {
                assertFalse(results.contains(filesByRelPath.get(path)), "Seed " + path + " should be excluded");
            }
        }
    }

    @Test
    public void noProjectImportsHandledGracefully() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                        """
                        package test;
                        import java.util.List;
                        import java.util.ArrayList;
                        public class A {
                            public List<String> list = new ArrayList<>();
                        }
                        """,
                        "test/A.java")
                .addFileContents(
                        """
                        package test;
                        import java.util.Map;
                        public class B {
                            public Map<String, String> map;
                        }
                        """,
                        "test/B.java")
                .build()) {

            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);

            Map<String, ProjectFile> byName = new HashMap<>();
            for (CodeUnit cu : analyzer.getAllDeclarations()) {
                String name = cu.source().getFileName().toString().toLowerCase(Locale.ROOT);
                byName.putIfAbsent(name, cu.source());
            }

            ProjectFile a = byName.get("a.java");
            Map<ProjectFile, Double> seeds = Map.of(a, 1.0);

            // Should not throw and should return empty or minimal results since there are no internal links
            List<IAnalyzer.FileRelevance> results =
                    ImportPageRanker.getRelatedFilesByImports(analyzer, seeds, 10, false);

            assertFalse(results.stream().anyMatch(fr -> fr.file().equals(a)), "Seed A should be excluded");
            // Since there are no project-internal imports, B should not be reached/ranked via imports.
            assertTrue(results.isEmpty(), "Expected no related files when no project-internal imports exist");
        }
    }
}
