package ai.brokk.init.onboarding;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IProject;
import ai.brokk.git.GitRepo;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Tests for OnboardingOrchestrator.
 * Validates step selection, ordering, and plan generation.
 */
class OnboardingOrchestratorTest {

    /**
     * Minimal test implementation of IProject for testing.
     */
    private static class TestProject implements IProject {
        private final Path root;

        TestProject(Path root) {
            this.root = root;
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public Path getMasterRootPathForConfig() {
            return root;
        }

        @Override
        public GitRepo getRepo() {
            return null;
        }

        @Override
        public boolean hasGit() {
            return false;
        }

        @Override
        public void close() {}
    }

    /**
     * Test 1: Fresh project with no files - all steps needed except migration
     */
    @Test
    void testFreshProject_AllStepsExceptMigration() {
        var project = new TestProject(Path.of("/tmp/test"));
        var state = new ProjectState(
                project,
                project.getRoot(),
                false, false, // no AGENTS.md
                false, false, // no legacy style.md
                false, // styleGenerationSkippedDueToNoGit
                false, false, // no project.properties
                false, // buildDetailsAvailable
                false, false, // no .gitignore
                null, null
        );

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // Should have BUILD_SETTINGS and GIT_CONFIG (but not MIGRATION)
        assertEquals(2, plan.size(), "Should have 2 steps");
        assertTrue(plan.hasStep(BuildSettingsStep.STEP_ID), "Should have build settings");
        assertTrue(plan.hasStep(GitConfigStep.STEP_ID), "Should have git config");
        assertFalse(plan.hasStep(MigrationStep.STEP_ID), "Should NOT have migration (no legacy file)");
        assertFalse(plan.hasStep(PostGitStyleRegenerationStep.STEP_ID), "Should NOT have post-git regen");

        // Verify order: BUILD_SETTINGS before GIT_CONFIG
        var steps = plan.getSteps();
        assertEquals(BuildSettingsStep.STEP_ID, steps.get(0).id());
        assertEquals(GitConfigStep.STEP_ID, steps.get(1).id());
    }

    /**
     * Test 2: Legacy project needing migration - all steps needed
     */
    @Test
    void testLegacyProject_NeedsMigration() {
        var project = new TestProject(Path.of("/tmp/test"));
        var state = new ProjectState(
                project,
                project.getRoot(),
                false, false, // no AGENTS.md
                true, true, // legacy style.md exists with content
                false, // styleGenerationSkippedDueToNoGit
                false, false, // no project.properties
                false, // buildDetailsAvailable
                false, false, // no .gitignore
                null, null
        );

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // Should have MIGRATION, BUILD_SETTINGS, and GIT_CONFIG
        assertEquals(3, plan.size(), "Should have 3 steps");
        assertTrue(plan.hasStep(MigrationStep.STEP_ID));
        assertTrue(plan.hasStep(BuildSettingsStep.STEP_ID));
        assertTrue(plan.hasStep(GitConfigStep.STEP_ID));

        // Verify order: MIGRATION → BUILD_SETTINGS → GIT_CONFIG
        var steps = plan.getSteps();
        assertEquals(MigrationStep.STEP_ID, steps.get(0).id());
        assertEquals(BuildSettingsStep.STEP_ID, steps.get(1).id());
        assertEquals(GitConfigStep.STEP_ID, steps.get(2).id());
    }

    /**
     * Test 3: Fully configured project - no steps needed
     */
    @Test
    void testFullyConfigured_NoSteps() {
        var project = new TestProject(Path.of("/tmp/test"));
        var state = new ProjectState(
                project,
                project.getRoot(),
                true, true, // AGENTS.md exists with content
                false, false, // no legacy style.md
                false, // styleGenerationSkippedDueToNoGit
                true, true, // project.properties exists with content
                true, // buildDetailsAvailable
                true, true, // .gitignore configured
                null, null
        );

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // No steps needed
        assertEquals(0, plan.size(), "Fully configured project should have no steps");
        assertTrue(plan.isEmpty());
    }

    /**
     * Test 4: Git configured but not project - only build settings needed
     */
    @Test
    void testGitConfigured_OnlyBuildSettings() {
        var project = new TestProject(Path.of("/tmp/test"));
        var state = new ProjectState(
                project,
                project.getRoot(),
                true, true, // AGENTS.md exists with content
                false, false, // no legacy style.md
                false, // styleGenerationSkippedDueToNoGit
                false, false, // no project.properties
                false, // buildDetailsAvailable
                true, true, // .gitignore configured
                null, null
        );

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // Only build settings needed
        assertEquals(1, plan.size());
        assertTrue(plan.hasStep(BuildSettingsStep.STEP_ID));
        assertFalse(plan.hasStep(GitConfigStep.STEP_ID), "Git already configured");
    }

    /**
     * Test 5: Post-git style regeneration step included when applicable
     */
    @Test
    void testPostGitStyleRegeneration_Included() {
        var project = new TestProject(Path.of("/tmp/test"));
        var state = new ProjectState(
                project,
                project.getRoot(),
                true, true, // AGENTS.md exists with content
                false, false, // no legacy style.md
                true, // styleGenerationSkippedDueToNoGit = TRUE
                true, true, // project.properties exists
                true, // buildDetailsAvailable
                false, false, // .gitignore NOT configured
                null, null
        );

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // Should have GIT_CONFIG and POST_GIT_STYLE_REGENERATION
        assertTrue(plan.hasStep(GitConfigStep.STEP_ID), "Should configure git");
        assertTrue(plan.hasStep(PostGitStyleRegenerationStep.STEP_ID), "Should offer style regeneration");

        // POST_GIT_STYLE_REGENERATION should come after GIT_CONFIG
        var steps = plan.getSteps();
        var gitConfigIndex = steps.stream()
                .map(OnboardingStep::id)
                .toList()
                .indexOf(GitConfigStep.STEP_ID);
        var regenIndex = steps.stream()
                .map(OnboardingStep::id)
                .toList()
                .indexOf(PostGitStyleRegenerationStep.STEP_ID);

        assertTrue(gitConfigIndex < regenIndex, "Post-git regen should come after git config");
    }

    /**
     * Test 6: Post-git style regeneration NOT included when not applicable
     */
    @Test
    void testPostGitStyleRegeneration_NotIncluded() {
        var project = new TestProject(Path.of("/tmp/test"));
        var state = new ProjectState(
                project,
                project.getRoot(),
                true, true, // AGENTS.md exists with content
                false, false, // no legacy style.md
                false, // styleGenerationSkippedDueToNoGit = FALSE
                true, true, // project.properties exists
                true, // buildDetailsAvailable
                false, false, // .gitignore NOT configured
                null, null
        );

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // Should have GIT_CONFIG but NOT POST_GIT_STYLE_REGENERATION
        assertTrue(plan.hasStep(GitConfigStep.STEP_ID));
        assertFalse(plan.hasStep(PostGitStyleRegenerationStep.STEP_ID),
                "Should NOT offer regen when style wasn't skipped");
    }

    /**
     * Test 7: Empty legacy file - migration not needed
     */
    @Test
    void testEmptyLegacyFile_NoMigration() {
        var project = new TestProject(Path.of("/tmp/test"));
        var state = new ProjectState(
                project,
                project.getRoot(),
                false, false, // no AGENTS.md
                true, false, // legacy style.md exists but EMPTY
                false, // styleGenerationSkippedDueToNoGit
                false, false, // no project.properties
                false, // buildDetailsAvailable
                false, false, // no .gitignore
                null, null
        );

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // Should NOT have migration step (empty legacy file)
        assertFalse(plan.hasStep(MigrationStep.STEP_ID), "Empty legacy file shouldn't trigger migration");
    }

    /**
     * Test 8: Both AGENTS.md and legacy exist - migration not needed
     */
    @Test
    void testBothFilesExist_NoMigration() {
        var project = new TestProject(Path.of("/tmp/test"));
        var state = new ProjectState(
                project,
                project.getRoot(),
                true, true, // AGENTS.md exists with content
                true, true, // legacy style.md exists with content
                false, // styleGenerationSkippedDueToNoGit
                true, true, // project.properties exists
                true, // buildDetailsAvailable
                true, true, // .gitignore configured
                null, null
        );

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // Fully configured, no migration needed
        assertEquals(0, plan.size());
        assertFalse(plan.hasStep(MigrationStep.STEP_ID), "Migration not needed when AGENTS.md exists");
    }

    /**
     * Test 9: buildProjectState helper creates correct state
     */
    @Test
    void testBuildProjectState_Helper() {
        var project = new TestProject(Path.of("/tmp/test"));
        var styleFuture = CompletableFuture.completedFuture("# Style Guide");
        var buildFuture = CompletableFuture.completedFuture(null);

        var state = OnboardingOrchestrator.buildProjectState(
                project,
                styleFuture,
                buildFuture,
                true // styleGenerationSkippedDueToNoGit
        );

        assertNotNull(state);
        assertEquals(project, state.project());
        assertEquals(project.getRoot(), state.configRoot());
        assertTrue(state.styleGenerationSkippedDueToNoGit());
        assertEquals(styleFuture, state.styleGuideFuture());
        assertEquals(buildFuture, state.buildDetailsFuture());
    }

    /**
     * Test 10: Verify step dependency ordering is correct
     */
    @Test
    void testStepDependencies_CorrectOrder() {
        // Create state where all steps are applicable
        var project = new TestProject(Path.of("/tmp/test"));
        var state = new ProjectState(
                project,
                project.getRoot(),
                false, false, // no AGENTS.md
                true, true, // legacy style.md exists
                true, // styleGenerationSkippedDueToNoGit = true
                false, false, // no project.properties
                false, // buildDetailsAvailable
                false, false, // .gitignore NOT configured
                null, null
        );

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // Should have all 4 steps
        assertEquals(4, plan.size());

        // Verify correct order based on dependencies:
        // MIGRATION (no deps) → BUILD_SETTINGS (deps on MIGRATION) →
        // GIT_CONFIG (deps on BUILD_SETTINGS) → POST_GIT_REGEN (deps on GIT_CONFIG)
        var steps = plan.getSteps();
        assertEquals(MigrationStep.STEP_ID, steps.get(0).id());
        assertEquals(BuildSettingsStep.STEP_ID, steps.get(1).id());
        assertEquals(GitConfigStep.STEP_ID, steps.get(2).id());
        assertEquals(PostGitStyleRegenerationStep.STEP_ID, steps.get(3).id());
    }
}
