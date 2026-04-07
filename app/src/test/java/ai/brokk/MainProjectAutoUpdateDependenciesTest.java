package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.AbstractProject;
import ai.brokk.project.MainProject;
import ai.brokk.project.WorktreeProject;
import ai.brokk.util.FileUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MainProjectAutoUpdateDependenciesTest {

    private Path tempRoot;

    @BeforeEach
    void setUp() throws IOException {
        tempRoot = Files.createTempDirectory("brokk-main-project-auto-update-");
        Files.createDirectories(tempRoot.resolve(AbstractProject.BROKK_DIR));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempRoot != null && Files.exists(tempRoot)) {
            FileUtil.deleteRecursively(tempRoot);
        }
    }

    @Test
    void defaultFlags_shouldBeFalse() {
        var project = new MainProject(tempRoot);
        assertFalse(project.getAutoUpdateLocalDependencies(), "Local auto-update should default to false");
        assertFalse(project.getAutoUpdateGitDependencies(), "Git auto-update should default to false");
    }

    @Test
    void flags_shouldPersistAcrossProjectReloads() {
        var project = new MainProject(tempRoot);
        project.setAutoUpdateLocalDependencies(true);
        project.setAutoUpdateGitDependencies(true);

        var reloaded = new MainProject(tempRoot);
        assertTrue(reloaded.getAutoUpdateLocalDependencies(), "Local auto-update should persist as true");
        assertTrue(reloaded.getAutoUpdateGitDependencies(), "Git auto-update should persist as true");

        reloaded.setAutoUpdateLocalDependencies(false);
        reloaded.setAutoUpdateGitDependencies(false);

        var reloadedAgain = new MainProject(tempRoot);
        assertFalse(reloadedAgain.getAutoUpdateLocalDependencies(), "Local auto-update should persist as false");
        assertFalse(reloadedAgain.getAutoUpdateGitDependencies(), "Git auto-update should persist as false");
    }

    @Test
    void worktreeProject_shouldShareParentScheduler() throws IOException {
        var mainProject = new MainProject(tempRoot);

        // Create a "worktree" directory (simulated - doesn't need actual git worktree for this test)
        var worktreePath = Files.createTempDirectory("brokk-worktree-");
        Files.createDirectories(worktreePath.resolve(AbstractProject.BROKK_DIR));

        try {
            var worktreeProject = new WorktreeProject(worktreePath, mainProject);

            // Worktree should delegate to parent's scheduler (same instance)
            assertSame(
                    mainProject.getDependencyUpdateScheduler(),
                    worktreeProject.getMainProject().getDependencyUpdateScheduler(),
                    "WorktreeProject should use parent MainProject's scheduler");
        } finally {
            FileUtil.deleteRecursively(worktreePath);
        }
    }

    @Test
    void separateMainProjects_shouldHaveSeparateSchedulers() throws IOException {
        var project1 = new MainProject(tempRoot);

        var tempRoot2 = Files.createTempDirectory("brokk-main-project-2-");
        Files.createDirectories(tempRoot2.resolve(AbstractProject.BROKK_DIR));

        try {
            var project2 = new MainProject(tempRoot2);

            assertNotSame(
                    project1.getDependencyUpdateScheduler(),
                    project2.getDependencyUpdateScheduler(),
                    "Separate MainProjects should have separate schedulers");
        } finally {
            FileUtil.deleteRecursively(tempRoot2);
        }
    }
}
