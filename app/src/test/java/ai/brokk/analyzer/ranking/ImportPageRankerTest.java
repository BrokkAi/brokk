package ai.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.ranking.ImportPageRanker;
import ai.brokk.testutil.AnalyzerCreator;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
