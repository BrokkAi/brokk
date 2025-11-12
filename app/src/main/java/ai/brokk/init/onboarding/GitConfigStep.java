package ai.brokk.init.onboarding;

import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Onboarding step for git configuration confirmation.
 * <p>
 * This step flags that a git config dialog should be shown.
 * The UI layer (Chrome) handles showing the confirm dialog and performing
 * the actual configuration via GitIgnoreConfigurator.
 * <p>
 * This step runs after build settings (if build settings was needed).
 */
public class GitConfigStep implements OnboardingStep {
    private static final Logger logger = LogManager.getLogger(GitConfigStep.class);

    public static final String STEP_ID = "GIT_CONFIG";

    @Override
    public String id() {
        return STEP_ID;
    }

    @Override
    public List<String> dependsOn() {
        // Git config should run after build settings (if build settings was needed)
        return List.of(BuildSettingsStep.STEP_ID);
    }

    @Override
    public boolean isApplicable(ProjectState state) {
        return state.project().hasGit() && state.needsGitConfig();
    }

    @Override
    public StepResult execute(ProjectState state) {
        logger.info("Executing git config step (flagging UI dialog)");

        // Don't perform git config here - let UI handle user confirmation
        // Return dialog data so Chrome can show confirm dialog and perform configuration
        return StepResult.successWithDialog(STEP_ID, "Git config dialog required", new GitConfigDialogData());
    }

    /**
     * Marker data for git config dialog.
     * No additional data needed - just signals that git config dialog should be shown.
     */
    public record GitConfigDialogData() implements OnboardingDialogData {}
}
