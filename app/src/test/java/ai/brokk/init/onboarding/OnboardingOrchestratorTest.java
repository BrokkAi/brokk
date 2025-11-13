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
    // Platform-independent absolute path for testing
    private static final Path TEST_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "test");

    /**
     * Minimal test implementation of IProject for testing.
     */
    private static class TestProject implements IProject {
        private final Path root;
        private boolean hasGit = false;

        TestProject(Path root) {
            this.root = root;
        }

        void setHasGit(boolean hasGit) {
            this.hasGit = hasGit;
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
            return hasGit;
        }

        @Override
        public void close() {}
    }

    /** Builder for creating ProjectState in tests with sensible defaults. */
    private static class StateBuilder {
        private TestProject project = new TestProject(TEST_ROOT);
        private boolean agentsMdExists = false;
        private boolean agentsMdHasContent = false;
        private boolean legacyStyleMdExists = false;
        private boolean legacyStyleMdHasContent = false;
        private boolean styleGenerationSkippedDueToNoGit = false;
        private boolean projectPropertiesExists = false;
        private boolean projectPropertiesHasContent = false;
        private boolean buildDetailsAvailable = false;
        private boolean gitignoreExists = false;
        private boolean gitignoreConfigured = false;

        StateBuilder withProject(TestProject p) {
            this.project = p;
            return this;
        }

        StateBuilder withAgentsMd() {
            agentsMdExists = true;
            agentsMdHasContent = true;
            return this;
        }

        StateBuilder withLegacyStyleMd() {
            legacyStyleMdExists = true;
            legacyStyleMdHasContent = true;
            return this;
        }

        StateBuilder withEmptyLegacyStyleMd() {
            legacyStyleMdExists = true;
            return this;
        }

        StateBuilder withStyleSkippedDueToNoGit() {
            styleGenerationSkippedDueToNoGit = true;
            return this;
        }

        StateBuilder withProjectProperties() {
            projectPropertiesExists = true;
            projectPropertiesHasContent = true;
            return this;
        }

        StateBuilder withBuildDetails() {
            buildDetailsAvailable = true;
            return this;
        }

        StateBuilder withGitIgnoreConfigured() {
            gitignoreExists = true;
            gitignoreConfigured = true;
            return this;
        }

        ProjectState build() {
            return new ProjectState(
                    project,
                    project.getRoot(),
                    agentsMdExists,
                    agentsMdHasContent,
                    legacyStyleMdExists,
                    legacyStyleMdHasContent,
                    styleGenerationSkippedDueToNoGit,
                    projectPropertiesExists,
                    projectPropertiesHasContent,
                    buildDetailsAvailable,
                    gitignoreExists,
                    gitignoreConfigured,
                    null,
                    null);
        }
    }

    @Test
    void testFreshProject_AllStepsExceptMigration() {
        var project = new TestProject(TEST_ROOT);
        project.setHasGit(true);
        var state = new StateBuilder().withProject(project).build();

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

    @Test
    void testLegacyProject_NeedsMigration() {
        var project = new TestProject(TEST_ROOT);
        project.setHasGit(true);
        var state = new StateBuilder().withProject(project).withLegacyStyleMd().build();

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

    @Test
    void testFullyConfigured_NoSteps() {
        var state = new StateBuilder()
                .withAgentsMd()
                .withProjectProperties()
                .withBuildDetails()
                .withGitIgnoreConfigured()
                .build();

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // No steps needed
        assertEquals(0, plan.size(), "Fully configured project should have no steps");
        assertTrue(plan.isEmpty());
    }

    @Test
    void testGitConfigured_OnlyBuildSettings() {
        var state = new StateBuilder().withAgentsMd().withGitIgnoreConfigured().build();

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // Only build settings needed
        assertEquals(1, plan.size());
        assertTrue(plan.hasStep(BuildSettingsStep.STEP_ID));
        assertFalse(plan.hasStep(GitConfigStep.STEP_ID), "Git already configured");
    }

    @Test
    void testPostGitStyleRegeneration_Included() {
        var project = new TestProject(TEST_ROOT);
        project.setHasGit(true);
        var state = new StateBuilder()
                .withProject(project)
                .withAgentsMd()
                .withStyleSkippedDueToNoGit()
                .withProjectProperties()
                .withBuildDetails()
                .build();

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // Should have GIT_CONFIG and POST_GIT_STYLE_REGENERATION
        assertTrue(plan.hasStep(GitConfigStep.STEP_ID), "Should configure git");
        assertTrue(plan.hasStep(PostGitStyleRegenerationStep.STEP_ID), "Should offer style regeneration");

        // POST_GIT_STYLE_REGENERATION should come after GIT_CONFIG
        var steps = plan.getSteps();
        var gitConfigIndex = steps.stream().map(OnboardingStep::id).toList().indexOf(GitConfigStep.STEP_ID);
        var regenIndex = steps.stream().map(OnboardingStep::id).toList().indexOf(PostGitStyleRegenerationStep.STEP_ID);

        assertTrue(gitConfigIndex < regenIndex, "Post-git regen should come after git config");
    }

    @Test
    void testPostGitStyleRegeneration_NotIncluded() {
        var project = new TestProject(TEST_ROOT);
        project.setHasGit(true);
        var state = new StateBuilder()
                .withProject(project)
                .withAgentsMd()
                .withProjectProperties()
                .withBuildDetails()
                .build();

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // Should have GIT_CONFIG but NOT POST_GIT_STYLE_REGENERATION
        assertTrue(plan.hasStep(GitConfigStep.STEP_ID));
        assertFalse(
                plan.hasStep(PostGitStyleRegenerationStep.STEP_ID), "Should NOT offer regen when style wasn't skipped");
    }

    @Test
    void testEmptyLegacyFile_NoMigration() {
        var state = new StateBuilder().withEmptyLegacyStyleMd().build();

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // Should NOT have migration step (empty legacy file)
        assertFalse(plan.hasStep(MigrationStep.STEP_ID), "Empty legacy file shouldn't trigger migration");
    }

    @Test
    void testBothFilesExist_NoMigration() {
        var state = new StateBuilder()
                .withAgentsMd()
                .withLegacyStyleMd()
                .withProjectProperties()
                .withBuildDetails()
                .withGitIgnoreConfigured()
                .build();

        var orchestrator = new OnboardingOrchestrator();
        var plan = orchestrator.buildPlan(state);

        // Fully configured, no migration needed
        assertEquals(0, plan.size());
        assertFalse(plan.hasStep(MigrationStep.STEP_ID), "Migration not needed when AGENTS.md exists");
    }

    @Test
    void testBuildProjectState_Helper() {
        var project = new TestProject(TEST_ROOT);
        var styleFuture = CompletableFuture.completedFuture("# Style Guide");
        var buildFuture = CompletableFuture.completedFuture(null);

        var state = OnboardingOrchestrator.buildProjectState(
                project, styleFuture, buildFuture, true // styleGenerationSkippedDueToNoGit
                );

        assertNotNull(state);
        assertEquals(project, state.project());
        assertEquals(project.getRoot(), state.configRoot());
        assertTrue(state.styleGenerationSkippedDueToNoGit());
        assertEquals(styleFuture, state.styleGuideFuture());
        assertEquals(buildFuture, state.buildDetailsFuture());
    }

    @Test
    void testStepDependencies_CorrectOrder() {
        // Create state where all steps are applicable
        var project = new TestProject(TEST_ROOT);
        project.setHasGit(true);
        var state = new StateBuilder()
                .withProject(project)
                .withLegacyStyleMd()
                .withStyleSkippedDueToNoGit()
                .build();

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
