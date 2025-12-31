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
import java.util.stream.Collectors;
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
    public void testFillBehaviorWhenGitResultsAreInsufficient() throws Exception {
        // A and B are linked by Git.
        // A and C are linked by Imports.
        // If we ask for topK=2 with seed A, Git provides 1 result (B).
        // Context should fill the 2nd slot using ImportPageRanker (C).
        try (var project = InlineTestProjectCreator.code(
                        "package test; import test.C; public class A { }", "test/A.java")
                .addFileContents("package test; public class B { }", "test/B.java")
                .addFileContents("package test; public class C { }", "test/C.java")
                .withGit()
                .addCommit("test/A.java", "test/B.java")
                .build()) {

            IAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            Map<String, ProjectFile> files = analyzer.getAllDeclarations().stream()
                    .map(CodeUnit::source).distinct()
                    .collect(Collectors.toMap(f -> f.getFileName().toString(), f -> f));

            ProjectFile a = files.get("A.java");
            ProjectFile b = files.get("B.java");
            ProjectFile c = files.get("C.java");

            IContextManager cm = new IContextManager() {
                @Override public IAnalyzer getAnalyzer() { return analyzer; }
                @Override public IProject getProject() { return project; }
                @Override public IGitRepo getRepo() { return project.getRepo(); }
            };
            Context ctx = new Context(cm).addFragments(new ContextFragments.ProjectPathFragment(a, cm));

            List<ProjectFile> results = ctx.getMostRelevantFiles(2);

            assertEquals(2, results.size(), "Should return exactly topK results");
            assertTrue(results.contains(b), "Should contain Git-linked file B");
            assertTrue(results.contains(c), "Should contain Import-linked file C to fill the list");
            assertFalse(results.contains(a), "Seed A should be excluded");

            // Verify uniqueness
            assertEquals(2, new HashSet<>(results).size(), "Results should be unique");
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
