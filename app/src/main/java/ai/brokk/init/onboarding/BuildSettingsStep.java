package ai.brokk.init.onboarding;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Onboarding step for showing the build settings dialog.
 * <p>
 * This step doesn't perform any operations itself - it simply flags that
 * the UI layer should show the build settings dialog.
 * <p>
 * Depends on migration completing first (if migration was needed).
 */
public class BuildSettingsStep implements OnboardingStep {
    private static final Logger logger = LogManager.getLogger(BuildSettingsStep.class);

    public static final String STEP_ID = "BUILD_SETTINGS";

    @Override
    public String id() {
        return STEP_ID;
    }

    @Override
    public List<String> dependsOn() {
        // Build settings should show after migration (if migration was needed)
        return List.of(MigrationStep.STEP_ID);
    }

    @Override
    public boolean isApplicable(ProjectState state) {
        return state.needsBuildSettings();
    }

    @Override
    public StepResult execute(ProjectState state) {
        logger.info("Executing build settings step (flagging UI dialog)");

        return StepResult.successWithDialog(
                STEP_ID, "Build settings dialog required", new BuildSettingsDialogData(state.styleGuideFuture()));
    }

    /**
     * Data for build settings dialog.
     * Contains the style guide future so UI can access generated content.
     */
    public record BuildSettingsDialogData(@Nullable CompletableFuture<String> styleGuideFuture)
            implements OnboardingDialogData {}
}
