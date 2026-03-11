package ai.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.*;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.git.GitRepo;
import ai.brokk.ranking.ImportPageRanker;
import ai.brokk.testutil.AnalyzerCreator;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    public void gitInsufficientResults_usesImportRankingFallback() throws Exception {
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

    @Test
    public void testRelativeRankingOfHubNode() throws Exception {
        // Construct a "Star" graph where many files import 'Hub.java'.
        // Seed is one of the leaf nodes. 'Hub' should be the top result.
        try (var project = InlineTestProjectCreator.code("package test; public class Hub {}", "test/Hub.java")
                .addFileContents("package test; import test.Hub; public class Leaf1 {}", "test/Leaf1.java")
                .addFileContents("package test; import test.Hub; public class Leaf2 {}", "test/Leaf2.java")
                .addFileContents("package test; import test.Hub; public class Leaf3 {}", "test/Leaf3.java")
                .addFileContents("package test; import test.Hub; public class Leaf4 {}", "test/Leaf4.java")
                .build()) {

            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            Map<String, ProjectFile> files = analyzer.getAllDeclarations().stream()
                    .map(CodeUnit::source)
                    .distinct()
                    .collect(Collectors.toMap(f -> f.getFileName().toString(), f -> f));

            ProjectFile hub = files.get("Hub.java");
            ProjectFile leaf1 = files.get("Leaf1.java");

            // Seed from a leaf
            Map<ProjectFile, Double> seeds = Map.of(leaf1, 1.0);
            List<IAnalyzer.FileRelevance> results =
                    ImportPageRanker.getRelatedFilesByImports(analyzer, seeds, 10, false);

            assertFalse(results.isEmpty(), "Should have results");
            assertEquals(hub, results.get(0).file(), "The central Hub should be the top ranked result");
        }
    }

    @Test
    public void testRankFlowsThroughChain() throws Exception {
        // A -> B -> C -> D. Seed is A.
        // With IMPORT_DEPTH=2, it should reach C.
        try (var project = InlineTestProjectCreator.code(
                        "package test; import test.B; public class A {}", "test/A.java")
                .addFileContents("package test; import test.C; public class B {}", "test/B.java")
                .addFileContents("package test; import test.D; public class C {}", "test/C.java")
                .addFileContents("package test; public class D {}", "test/D.java")
                .build()) {

            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            Map<String, ProjectFile> files = analyzer.getAllDeclarations().stream()
                    .map(CodeUnit::source)
                    .distinct()
                    .collect(Collectors.toMap(f -> f.getFileName().toString(), f -> f));

            ProjectFile a = files.get("A.java");
            ProjectFile b = files.get("B.java");
            ProjectFile c = files.get("C.java");

            List<IAnalyzer.FileRelevance> results =
                    ImportPageRanker.getRelatedFilesByImports(analyzer, Map.of(a, 1.0), 10, false);

            List<ProjectFile> resultFiles =
                    results.stream().map(IAnalyzer.FileRelevance::file).toList();

            assertTrue(resultFiles.contains(b), "Direct import B should be present");
            assertTrue(resultFiles.contains(c), "Indirect import C (2 hops) should be present");
            assertFalse(resultFiles.contains(files.get("D.java")), "D is 3 hops away, should be outside IMPORT_DEPTH");

            // Relative order: B should be higher than C
            int indexB = resultFiles.indexOf(b);
            int indexC = resultFiles.indexOf(c);
            assertTrue(indexB < indexC, "Direct neighbor B should rank higher than indirect neighbor C");
        }
    }

    @Test
    public void pageRank_handlesCircularImports() throws Exception {
        // A -> B -> C -> A
        try (var project = InlineTestProjectCreator.code(
                        "package test; import test.B; public class A {}", "test/A.java")
                .addFileContents("package test; import test.C; public class B {}", "test/B.java")
                .addFileContents("package test; import test.A; public class C {}", "test/C.java")
                .build()) {

            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            Map<String, ProjectFile> files = analyzer.getAllDeclarations().stream()
                    .map(CodeUnit::source)
                    .distinct()
                    .collect(Collectors.toMap(f -> f.getFileName().toString(), f -> f));

            ProjectFile a = files.get("A.java");
            ProjectFile b = files.get("B.java");
            ProjectFile c = files.get("C.java");

            // Seed with A
            Map<ProjectFile, Double> seeds = Map.of(a, 1.0);
            List<IAnalyzer.FileRelevance> results =
                    ImportPageRanker.getRelatedFilesByImports(analyzer, seeds, 10, false);

            List<ProjectFile> resultFiles =
                    results.stream().map(IAnalyzer.FileRelevance::file).toList();

            // Should contain B and C, but not A (the seed)
            assertFalse(resultFiles.contains(a), "Seed A should be excluded");
            assertTrue(resultFiles.contains(b), "B should be reached in the cycle");
            assertTrue(resultFiles.contains(c), "C should be reached in the cycle");

            // Verify scores are stable (non-zero and reasonable)
            for (IAnalyzer.FileRelevance fr : results) {
                assertTrue(fr.score() > 0, "Scores should be positive");
                assertTrue(fr.score() < 1.0, "Scores should be normalized");
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

    @Test
    public void testReverseImportTraversal() throws Exception {
        // Importer -> Imported. Seed is Imported.
        // Even though Imported has no outgoing imports, it should find Importer via reverse traversal.
        try (var project = InlineTestProjectCreator.code(
                        "package test; import test.Imported; public class Importer {}", "test/Importer.java")
                .addFileContents("package test; public class Imported {}", "test/Imported.java")
                .build()) {

            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            Map<String, ProjectFile> files = analyzer.getAllDeclarations().stream()
                    .map(CodeUnit::source)
                    .distinct()
                    .collect(Collectors.toMap(f -> f.getFileName().toString(), f -> f));

            ProjectFile importer = files.get("Importer.java");
            ProjectFile imported = files.get("Imported.java");

            assert importer != null : "Importer.java not found in project";
            assert imported != null : "Imported.java not found in project";

            // Seed with Imported (the file being imported)
            Map<ProjectFile, Double> seeds = Map.of(imported, 1.0);

            // reversed=true means we are looking for files that import our seeds
            List<IAnalyzer.FileRelevance> results =
                    ImportPageRanker.getRelatedFilesByImports(analyzer, seeds, 10, true);

            List<ProjectFile> resultFiles =
                    results.stream().map(IAnalyzer.FileRelevance::file).toList();

            assertTrue(
                    resultFiles.contains(importer),
                    "Importer.java should be found from Imported.java via reverse reference traversal");
        }
    }

    /**
     * Verifies the semantic distinction between reversed=false and reversed=true:
     * - reversed=false: ranks files that the seed IMPORTS (outgoing edges)
     * - reversed=true: ranks files that IMPORT the seed (incoming edges)
     */
    @Test
    public void testDirectionalityOfReversedFlag() throws Exception {
        // Setup: Upstream <- Middle <- Downstream
        try (var project = InlineTestProjectCreator.code("package test; public class Upstream {}", "test/Upstream.java")
                .addFileContents("package test; import test.Upstream; public class Middle {}", "test/Middle.java")
                .addFileContents("package test; import test.Middle; public class Downstream {}", "test/Downstream.java")
                .build()) {

            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            Map<String, ProjectFile> files = analyzer.getAllDeclarations().stream()
                    .map(CodeUnit::source)
                    .distinct()
                    .collect(Collectors.toMap(f -> f.getFileName().toString(), f -> f));

            ProjectFile upstream = files.get("Upstream.java");
            ProjectFile middle = files.get("Middle.java");
            ProjectFile downstream = files.get("Downstream.java");

            Map<ProjectFile, Double> seeds = Map.of(middle, 1.0);

            // 1. reversed=false (outgoing: what does Middle import?)
            List<ProjectFile> forwardResults =
                    ImportPageRanker.getRelatedFilesByImports(analyzer, seeds, 10, false).stream()
                            .map(IAnalyzer.FileRelevance::file)
                            .toList();

            assertTrue(forwardResults.contains(upstream), "reversed=false should include files the seed imports");
            assertFalse(
                    forwardResults.contains(downstream),
                    "reversed=false should NOT include files that import the seed");

            // 2. reversed=true (incoming: what imports Middle?)
            List<ProjectFile> reverseResults =
                    ImportPageRanker.getRelatedFilesByImports(analyzer, seeds, 10, true).stream()
                            .map(IAnalyzer.FileRelevance::file)
                            .toList();

            assertTrue(reverseResults.contains(downstream), "reversed=true should include files that import the seed");
            assertFalse(reverseResults.contains(upstream), "reversed=true should NOT include files the seed imports");
        }
    }

    @Test
    public void multiAnalyzerWithMultipleLanguages_usesCorrectDelegates() throws Exception {
        // Create a project with Java and Python
        try (var project = InlineTestProjectCreator.code(
                        """
                        package test;
                        import test.Target;
                        public class Source { }
                        """,
                        "test/Source.java")
                .addFileContents("package test; public class Target { }", "test/Target.java")
                .addFileContents(
                        """
                        from other_module import other_fn
                        def py_source_fn():
                            other_fn()
                        """,
                        "py_source.py")
                .addFileContents(
                        """
                        def other_fn():
                            pass
                        """,
                        "other_module.py")
                .build()) {

            // Use the convenience API to create a MultiAnalyzer with Java and Python delegates
            IAnalyzer analyzer = AnalyzerCreator.createMultiAnalyzer(project, Languages.JAVA, Languages.PYTHON);

            Map<String, ProjectFile> filesByRelPath = analyzer.getAllDeclarations().stream()
                    .map(CodeUnit::source)
                    .distinct()
                    .collect(Collectors.toMap(
                            f -> project.getRoot()
                                    .relativize(f.absPath())
                                    .toString()
                                    .replace('\\', '/'),
                            f -> f));

            ProjectFile javaSource = filesByRelPath.get("test/Source.java");
            ProjectFile javaTarget = filesByRelPath.get("test/Target.java");
            ProjectFile pySource = filesByRelPath.get("py_source.py");
            ProjectFile pyTarget = filesByRelPath.get("other_module.py");

            // Force update with files to ensure ImportGraph is populated for both languages
            analyzer = analyzer.update(Set.copyOf(filesByRelPath.values()));

            // 1. Test Java branch of MultiAnalyzer
            List<IAnalyzer.FileRelevance> javaResults =
                    ImportPageRanker.getRelatedFilesByImports(analyzer, Map.of(javaSource, 1.0), 10, false);
            Set<ProjectFile> javaResultFiles =
                    javaResults.stream().map(IAnalyzer.FileRelevance::file).collect(Collectors.toSet());

            assertTrue(javaResultFiles.contains(javaTarget), "MultiAnalyzer should delegate Java import lookup");
            assertFalse(javaResultFiles.contains(pyTarget), "Java source should not link to Python target");

            // 2. Test Python branch of MultiAnalyzer
            List<IAnalyzer.FileRelevance> pyResults =
                    ImportPageRanker.getRelatedFilesByImports(analyzer, Map.of(pySource, 1.0), 10, false);
            Set<ProjectFile> pyResultFiles =
                    pyResults.stream().map(IAnalyzer.FileRelevance::file).collect(Collectors.toSet());

            assertTrue(pyResultFiles.contains(pyTarget), "MultiAnalyzer should delegate Python import lookup");
            assertFalse(pyResultFiles.contains(javaTarget), "Python source should not link to Java target");
        }
    }
}
