package ai.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import ai.brokk.testutil.AnalyzerCreator;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ContextNoGitFallbackTest {

    @TempDir
    Path tempDir;

    @Test
    public void noGitFallbackUsesImportPageRanker() throws Exception {
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

            assertFalse(project.hasGit(), "Project should not have Git by default");

            AtomicBoolean repoInvoked = new AtomicBoolean(false);

            IGitRepo stubRepo = new IGitRepo() {
                @Override
                public Set<IGitRepo.ModifiedFile> getModifiedFiles() {
                    repoInvoked.set(true);
                    return Set.of();
                }

                @Override
                public Set<ProjectFile> getTrackedFiles() {
                    repoInvoked.set(true);
                    return Set.of();
                }

                @Override
                public void add(Collection<ProjectFile> files) {
                    repoInvoked.set(true);
                }

                @Override
                public void add(ProjectFile file) {
                    repoInvoked.set(true);
                }

                @Override
                public void remove(ProjectFile file) {
                    repoInvoked.set(true);
                }
            };

            IContextManager cm = new IContextManager() {
                @Override
                public IAnalyzer getAnalyzer() {
                    return analyzer;
                }

                @Override
                public IAnalyzer getAnalyzerUninterrupted() {
                    return analyzer;
                }

                @Override
                public IProject getProject() {
                    return project;
                }

                @Override
                public IGitRepo getRepo() {
                    return stubRepo;
                }
            };

            Context ctx = new Context(cm);
            ContextFragment.ProjectPathFragment seedFragment = new ContextFragment.ProjectPathFragment(a, cm);
            ctx = ctx.addFragments(seedFragment);

            List<ProjectFile> results = ctx.getMostRelevantFiles(5);

            assertFalse(results.contains(a), "Seed file should be excluded from results");
            assertTrue(results.contains(b), "Expected B.java in related files");
            assertTrue(results.contains(c), "Expected C.java in related files");
            assertFalse(repoInvoked.get(), "IGitRepo should not be invoked when hasGit() is false");
        }
    }

    @Test
    public void testAreManySeedsNewBoundary() {
        TestContextManager tcm = new TestContextManager(tempDir, Set.of());
        Context ctx = new Context(tcm);

        ProjectFile p1 = tcm.toFile("p1");
        ProjectFile p2 = tcm.toFile("p2");
        ProjectFile p3 = tcm.toFile("p3");
        ProjectFile p4 = tcm.toFile("p4");
        ProjectFile p5 = tcm.toFile("p5");
        ProjectFile p6 = tcm.toFile("p6");
        ProjectFile p7 = tcm.toFile("p7");
        ProjectFile p8 = tcm.toFile("p8");
        ProjectFile p9 = tcm.toFile("p9");
        ProjectFile p10 = tcm.toFile("p10");

        // Use an IGitRepo stub to control getTrackedFiles()
        class StubGitRepo implements IGitRepo {
            Set<ProjectFile> tracked = Set.of();

            @Override
            public Set<ProjectFile> getTrackedFiles() {
                return tracked;
            }

            @Override
            public Set<ModifiedFile> getModifiedFiles() {
                return Set.of();
            }

            @Override
            public void add(Collection<ProjectFile> files) {}

            @Override
            public void add(ProjectFile file) {}

            @Override
            public void remove(ProjectFile file) {}
        }

        StubGitRepo stubRepo = new StubGitRepo();

        // Case 1: 2/7 are new (~28.5%). Should be FALSE (not "many" new seeds).
        // New seeds: p1, p2. Tracked: p3, p4, p5, p6, p7.
        stubRepo.tracked = Set.of(p3, p4, p5, p6, p7);
        Map<ProjectFile, Double> seeds7 = Map.of(p1, 1.0, p2, 1.0, p3, 1.0, p4, 1.0, p5, 1.0, p6, 1.0, p7, 1.0);
        assertFalse(ctx.areManySeedsNew(seeds7, stubRepo), "2/7 untracked is < 30%, should return false");

        // Case 2: 3/10 are new (30%). Should be TRUE (meets threshold).
        // New seeds: p1, p2, p3. Tracked: p4, p5, p6, p7, p8, p9, p10.
        stubRepo.tracked = Set.of(p4, p5, p6, p7, p8, p9, p10);
        Map<ProjectFile, Double> seeds10 =
                Map.of(p1, 1.0, p2, 1.0, p3, 1.0, p4, 1.0, p5, 1.0, p6, 1.0, p7, 1.0, p8, 1.0, p9, 1.0, p10, 1.0);
        assertTrue(ctx.areManySeedsNew(seeds10, stubRepo), "3/10 untracked is >= 30%, should return true");
    }

    @Test
    public void testHybridGitAndImportResults() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                        """
                        package test;
                        import test.B;
                        public class A { }
                        """,
                        "test/A.java")
                .addFileContents(
                        """
                        package test;
                        import test.C;
                        public class B { }
                        """,
                        "test/B.java")
                .addFileContents(
                        """
                        package test;
                        public class C { }
                        """,
                        "test/C.java")
                .addFileContents(
                        """
                        package test;
                        public class D { }
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
            ProjectFile d = byName.get("d.java");

            // Mock Git results: return only 'D'
            IGitRepo stubRepo = new IGitRepo() {
                @Override
                public Set<ProjectFile> getTrackedFiles() {
                    return Set.of(a, b, c, d);
                }

                @Override
                public Set<ModifiedFile> getModifiedFiles() {
                    return Set.of();
                }

                @Override
                public void add(Collection<ProjectFile> files) {}

                @Override
                public void add(ProjectFile file) {}

                @Override
                public void remove(ProjectFile file) {}
            };

            // We need to bypass the real GitDistance.getRelatedFiles because it's static/hard to mock,
            // but we can influence the IContextManager to use our stub repo.
            // Since we want to test the MERGING logic in Context.getMostRelevantFiles,
            // and GitDistance is called directly, we rely on the fact that A/B/C/D are tracked.
            // However, to ensure a hybrid result, we ask for topK=3.
            // If Git returns 1 result (D), it should supplement with 2 from imports (B, C).

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
                    return stubRepo;
                }
            };

            Context ctx = new Context(cm).addFragments(new ContextFragment.ProjectPathFragment(a, cm));

            // We want topK = 3.
            // 1. Git Distance will run (because hasGit is true and seeds are tracked).
            // 2. Git Distance will return some results.
            // 3. If results < 3, ImportPageRanker will supplement.
            List<ProjectFile> results = ctx.getMostRelevantFiles(3);

            // Assertions
            assertFalse(results.contains(a), "Seed A should be excluded");
            assertTrue(results.size() >= 2, "Should have at least B and C from imports if Git returns little");

            // Ensure no duplicates
            Set<ProjectFile> resultSet = new HashSet<>(results);
            assertEquals(results.size(), resultSet.size(), "Results should not contain duplicates");

            // Check that B and C (linked via imports) are present
            assertTrue(results.contains(b), "Expected B.java (direct import)");
            assertTrue(results.contains(c), "Expected C.java (indirect import)");
        }
    }

    @Test
    public void testWithGitOptionTracksFiles() throws Exception {
        try (var project = InlineTestProjectCreator.code(
                        "public class A {}", "A.java")
                .addFileContents("public class B {}", "B.java")
                .withGit()
                .build()) {

            assertTrue(project.hasGit(), "Project should have Git enabled");
            IGitRepo repo = project.getRepo();
            assertTrue(repo instanceof GitRepo, "Repo should be a concrete GitRepo instance");

            Set<ProjectFile> tracked = repo.getTrackedFiles();
            assertEquals(2, tracked.size(), "Should have 2 tracked files");

            boolean foundA = tracked.stream().anyMatch(f -> f.toString().endsWith("A.java"));
            boolean foundB = tracked.stream().anyMatch(f -> f.toString().endsWith("B.java"));

            assertTrue(foundA, "A.java should be tracked");
            assertTrue(foundB, "B.java should be tracked");
        }
    }
}
