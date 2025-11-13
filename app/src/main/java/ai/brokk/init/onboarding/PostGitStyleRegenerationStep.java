package ai.brokk.init.onboarding;

import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Onboarding step for offering style guide regeneration after Git is configured.
 * <p>
 * This step is applicable when:
 * - Style guide generation was previously skipped due to missing Git
 * - Git is now available/configured
 * <p>
 * The step flags that UI should offer to regenerate the style guide now that
 * Git is available for proper analysis.
 * <p>
 * This is optional and non-blocking - user can decline and continue.
 */
public class PostGitStyleRegenerationStep implements OnboardingStep {
    private static final Logger logger = LogManager.getLogger(PostGitStyleRegenerationStep.class);

    public static final String STEP_ID = "POST_GIT_STYLE_REGENERATION";

    @Override
    public String id() {
        return STEP_ID;
    }

    @Override
    public List<String> dependsOn() {
        // This runs after git config completes
        return List.of(GitConfigStep.STEP_ID);
    }

    @Override
    public boolean isApplicable(ProjectState state) {
        return state.needsPostGitStyleRegeneration();
    }

    @Override
    public StepResult execute(ProjectState state) {
        logger.info("Executing post-git style regeneration step");

        // This step just flags that UI should offer regeneration
        // The actual regeneration is triggered by the UI layer if user accepts
        return StepResult.successWithDialog(
                STEP_ID,
                "Style guide regeneration available now that Git is configured",
                new RegenerationOfferData(
                        "Style guide was generated without Git repository access. "
                                + "Would you like to regenerate it now for better quality?",
                        state.project()));
    }

    /**
     * Data for style regeneration offer dialog.
     */
    public record RegenerationOfferData(String message, Object project) implements OnboardingDialogData {}
}
