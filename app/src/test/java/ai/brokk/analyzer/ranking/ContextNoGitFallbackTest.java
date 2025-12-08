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
import ai.brokk.testutil.AnalyzerCreator;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class ContextNoGitFallbackTest {

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
                String name = cu.source().getFileName().toLowerCase(Locale.ROOT);
                byName.putIfAbsent(name, cu.source());
            }

            ProjectFile a = byName.get("a.java");
            ProjectFile b = byName.get("b.java");
            ProjectFile c = byName.get("c.java");

            AtomicBoolean repoInvoked = new AtomicBoolean(false);

            IGitRepo stubRepo = new IGitRepo() {
                @Override
                public Set<GitRepo.ModifiedFile> getModifiedFiles() {
                    return java.util.Set.of();
                }

                @Override
                public Set<ProjectFile> getTrackedFiles() {
                    return Set.of();
                }

                @Override
                public void add(Collection<ai.brokk.analyzer.ProjectFile> files) {
                    // no-op
                }

                @Override
                public void add(Path path) {
                    // no-op
                }

                @Override
                public void remove(ProjectFile file) {
                    // no-op
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
}
