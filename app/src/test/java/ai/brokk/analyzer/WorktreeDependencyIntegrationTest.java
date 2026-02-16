package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.AbstractProject;
import ai.brokk.project.MainProject;
import ai.brokk.project.WorktreeProject;
import ai.brokk.testutil.AssertionHelperUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Worktree Dependency Integration Tests")
class WorktreeDependencyIntegrationTest {

    @TempDir
    Path tempDir;

    private Path mainRoot;
    private Path worktreeRoot;
    private MainProject mainProject;
    private WorktreeProject worktreeProject;

    @BeforeEach
    void setUp() throws IOException {
        mainRoot = tempDir.resolve("main-repo").toAbsolutePath();
        worktreeRoot = tempDir.resolve("worktree").toAbsolutePath();

        Files.createDirectories(mainRoot.resolve(AbstractProject.BROKK_DIR));
        Files.createDirectories(worktreeRoot.resolve(AbstractProject.BROKK_DIR));

        // Use forTests to avoid background build detail fetching that might hang
        mainProject = MainProject.forTests(mainRoot);
    }

    @AfterEach
    void tearDown() {
        if (mainProject != null) mainProject.close();
        if (worktreeProject != null) worktreeProject.close();
    }

    @Test
    @DisplayName("Analyzer in WorktreeProject should find and read declarations from MainProject dependencies")
    void testAnalyzerResolvesDependenciesInWorktree() throws IOException {
        // 1. Setup MainProject dependency
        String depName = "test-dep";
        Path depDir = mainRoot.resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.DEPENDENCIES_DIR)
                .resolve(depName);
        Files.createDirectories(depDir);

        String javaSource =
                """
                package com.test.dep;

                public class DepClass {
                    public void depMethod() {
                        System.out.println("Hello from dependency");
                    }
                }
                """;
        Files.writeString(depDir.resolve("DepClass.java"), javaSource);

        // 2. Create WorktreeProject
        worktreeProject = new WorktreeProject(worktreeRoot, mainProject);

        // 3. Enable dependency in worktree
        worktreeProject.addLiveDependency(depName, null).join();

        // 4. Run JavaAnalyzer on the WorktreeProject
        JavaAnalyzer analyzer = new JavaAnalyzer(worktreeProject);

        // 5. Verify discovery
        List<CodeUnit> allDeclarations = analyzer.getAllDeclarations();

        boolean foundClass = allDeclarations.stream().anyMatch(cu -> cu.fqName().equals("com.test.dep.DepClass"));
        boolean foundMethod =
                allDeclarations.stream().anyMatch(cu -> cu.fqName().equals("com.test.dep.DepClass.depMethod"));

        assertTrue(foundClass, "Should find DepClass in dependency");
        assertTrue(foundMethod, "Should find depMethod in dependency");

        // 6. Verify source retrieval
        CodeUnit methodCu = allDeclarations.stream()
                .filter(cu -> cu.fqName().equals("com.test.dep.DepClass.depMethod"))
                .findFirst()
                .orElseThrow();

        var sourceOpt = analyzer.getSource(methodCu, false);
        assertTrue(sourceOpt.isPresent(), "Should be able to get source for dependency code unit");

        String expectedMethodSource =
                """
                public void depMethod() {
                        System.out.println("Hello from dependency");
                    }
                """;

        AssertionHelperUtil.assertCodeEquals(expectedMethodSource, sourceOpt.get());

        // 7. Verify ProjectFile properties
        ProjectFile pf = methodCu.source();
        assertEquals(mainRoot, pf.getRoot(), "ProjectFile root should be main project root");
        assertTrue(pf.absPath().startsWith(mainRoot), "Absolute path should be inside main project");
        assertFalse(pf.absPath().startsWith(worktreeRoot), "Absolute path should NOT be inside worktree");
    }
}
