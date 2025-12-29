package ai.brokk.analyzer.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
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
            ContextFragments.ProjectPathFragment seedFragment = new ContextFragments.ProjectPathFragment(a, cm);
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
        // We create a project where:
        // - A imports B, B imports C (Import link chain: A -> B -> C)
        // - A and D are committed together (Git link: A <-> D)
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
                .withGit()
                .addCommit("test/A.java", "test/D.java")
                .build()) {

            assertTrue(project.hasGit(), "Project should have Git enabled");
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

            Context ctx = new Context(cm).addFragments(new ContextFragments.ProjectPathFragment(a, cm));

            // We want topK = 3.
            // 1. Git Distance will find D (co-committed with A).
            // 2. ImportPageRanker will find B (direct import) and C (indirect).
            // 3. Context should merge these results without duplicates.
            List<ProjectFile> results = ctx.getMostRelevantFiles(3);

            // Assertions
            assertFalse(results.contains(a), "Seed A should be excluded from results");
            assertTrue(results.contains(d), "Expected D.java in results (via Git distance)");
            assertTrue(results.contains(b), "Expected B.java in results (via imports)");
            assertTrue(results.contains(c), "Expected C.java in results (via imports)");

            assertEquals(3, results.size(), "Should return exactly topK results");

            // Ensure no duplicates
            Set<ProjectFile> resultSet = new HashSet<>(results);
            assertEquals(results.size(), resultSet.size(), "Results should not contain duplicates");
        }
    }

    @Test
    public void testWithGitOptionTracksFiles() throws Exception {
        try (var project = InlineTestProjectCreator.code("public class A {}", "A.java")
                .addFileContents("public class B {}", "B.java")
                .withGit()
                .build()) {

            assertTrue(project.hasGit(), "Project should have Git enabled");
            IGitRepo repo = project.getRepo();
            assertTrue(repo instanceof GitRepo, "Repo should be a concrete GitRepo instance");

            Set<ProjectFile> tracked = repo.getTrackedFiles();
            // initRepo creates an initial empty commit, then our build adds files.
            // All files added in TestProjectBuilder.build() are tracked.
            assertTrue(tracked.size() >= 2, "Should have at least 2 tracked files");

            boolean foundA = tracked.stream().anyMatch(f -> f.toString().endsWith("A.java"));
            boolean foundB = tracked.stream().anyMatch(f -> f.toString().endsWith("B.java"));

            assertTrue(foundA, "A.java should be tracked");
            assertTrue(foundB, "B.java should be tracked");
        }
    }

    @Test
    public void testGitBuilderCommitsSequentially() throws Exception {
        try (var project = InlineTestProjectCreator.code("content a", "A.txt")
                .addFileContents("content b", "B.txt")
                .addFileContents("content c", "C.txt")
                .withGit()
                .addCommit("A.txt", "B.txt")
                .build()) {

            IGitRepo repo = project.getRepo();
            Set<ProjectFile> tracked = repo.getTrackedFiles();

            // Only A and B were staged in the commit logic of TestGitProjectBuilder
            assertTrue(tracked.stream().anyMatch(f -> f.toString().endsWith("A.txt")), "A should be tracked");
            assertTrue(tracked.stream().anyMatch(f -> f.toString().endsWith("B.txt")), "B should be tracked");
            assertFalse(tracked.stream().anyMatch(f -> f.toString().endsWith("C.txt")), "C should NOT be tracked");
        }
    }

    @Test
    public void testGitBuilderThrowsOnMissingFile() {
        var builder = InlineTestProjectCreator.code("content a", "A.txt").withGit();
        try {
            builder.addCommit("A.txt", "NonExistent.txt");
            org.junit.jupiter.api.Assertions.fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("NonExistent.txt"));
        }
    }
}
