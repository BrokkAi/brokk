package ai.brokk.init.onboarding;

import ai.brokk.IConsoleIO;
import ai.brokk.init.GitIgnoreConfigurator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Onboarding step for configuring .gitignore with Brokk patterns.
 * <p>
 * Uses GitIgnoreConfigurator to set up .gitignore and stage files.
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
        logger.info("Executing git config step");

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Set up .gitignore and stage files
                // Note: consoleIO is null here since we're in non-UI context
                // The orchestrator can provide an IConsoleIO if needed
                var result = GitIgnoreConfigurator.setupGitIgnoreAndStageFiles(state.project(), null);

                if (result.errorMessage().isPresent()) {
                    logger.error("Git config failed: {}", result.errorMessage().get());
                    return StepResult.failure(STEP_ID, result.errorMessage().get());
                }

                var message = String.format("Git configured: gitignore=%s, staged=%d files",
                        result.gitignoreUpdated() ? "updated" : "unchanged",
                        result.stagedFiles().size());

                logger.info(message);
                return StepResult.success(STEP_ID, message);

            } catch (Exception e) {
                logger.error("Error during git config step", e);
                return StepResult.failure(STEP_ID, "Git config failed: " + e.getMessage());
            }
        });
    }
}
