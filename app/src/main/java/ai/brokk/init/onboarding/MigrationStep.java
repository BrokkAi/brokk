package ai.brokk.init.onboarding;

import ai.brokk.AbstractProject;
import ai.brokk.git.GitRepo;
import ai.brokk.init.StyleGuideMigrator;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Onboarding step for migrating legacy .brokk/style.md to AGENTS.md.
 * <p>
 * Uses StyleGuideMigrator to perform the actual migration.
 * This step has no dependencies and runs first if applicable.
 */
public class MigrationStep implements OnboardingStep {
    private static final Logger logger = LoggerFactory.getLogger(MigrationStep.class);

    public static final String STEP_ID = "MIGRATION";

    @Override
    public String id() {
        return STEP_ID;
    }

    @Override
    public List<String> dependsOn() {
        return List.of(); // No dependencies
    }

    @Override
    public boolean isApplicable(ProjectState state) {
        return state.needsMigration();
    }

    @Override
    public CompletableFuture<StepResult> execute(ProjectState state) {
        logger.info("Executing migration step");

        return CompletableFuture.supplyAsync(() -> {
            try {
                var brokkDir = state.configRoot().resolve(AbstractProject.BROKK_DIR);
                var agentsFile = state.configRoot().resolve(AbstractProject.STYLE_GUIDE_FILE);

                // Get GitRepo if available
                var repo = state.project().getRepo();
                var gitRepo = (repo instanceof GitRepo) ? (GitRepo) repo : null;

                // Perform migration
                var result = StyleGuideMigrator.migrate(brokkDir, agentsFile, gitRepo);

                if (result.performed()) {
                    logger.info("Migration successful: {}", result.message());
                    // Migration succeeded - no user dialog needed, operation is complete
                    return StepResult.success(STEP_ID, result.message());
                } else {
                    logger.warn("Migration not performed: {}", result.message());
                    return StepResult.failure(STEP_ID, result.message());
                }

            } catch (Exception e) {
                logger.error("Error during migration step", e);
                return StepResult.failure(STEP_ID, "Migration failed: " + e.getMessage());
            }
        });
    }
}
