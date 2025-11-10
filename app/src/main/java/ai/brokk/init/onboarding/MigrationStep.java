package ai.brokk.init.onboarding;

import ai.brokk.AbstractProject;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Onboarding step for migration confirmation from legacy .brokk/style.md to AGENTS.md.
 * <p>
 * This step flags that a migration dialog should be shown.
 * The UI layer (Chrome) handles showing the confirm dialog and performing
 * the actual migration via StyleGuideMigrator.
 * <p>
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
    public StepResult execute(ProjectState state) {
        logger.info("Executing migration step (flagging UI dialog)");

        // Don't perform migration here - let UI handle user confirmation
        // Return dialog data so Chrome can show confirm dialog and perform migration
        return StepResult.successWithDialog(
                STEP_ID,
                "Migration dialog required",
                new MigrationDialogData(
                        state.configRoot().resolve(AbstractProject.BROKK_DIR),
                        state.configRoot().resolve(AbstractProject.STYLE_GUIDE_FILE)));
    }

    /**
     * Data for migration confirmation dialog.
     * Contains paths needed to perform the migration after user confirms.
     */
    public record MigrationDialogData(Path brokkDir, Path agentsFile) implements OnboardingDialogData {}
}
