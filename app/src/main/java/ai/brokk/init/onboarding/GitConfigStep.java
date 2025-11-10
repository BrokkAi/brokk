package ai.brokk.init.onboarding;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger logger = LoggerFactory.getLogger(GitConfigStep.class);

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
        return state.needsGitConfig();
    }

    @Override
    public CompletableFuture<StepResult> execute(ProjectState state) {
        logger.info("Executing git config step (flagging UI dialog)");

        // Don't perform git config here - let UI handle user confirmation
        // Return dialog data so Chrome can show confirm dialog and perform configuration
        return CompletableFuture.completedFuture(
                StepResult.successWithDialog(
                        STEP_ID,
                        "Git config dialog required",
                        null // No additional data needed for git config
                )
        );
    }
}
