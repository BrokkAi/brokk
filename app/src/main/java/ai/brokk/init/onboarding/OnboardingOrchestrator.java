package ai.brokk.init.onboarding;

import ai.brokk.AbstractProject;
import ai.brokk.IProject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Orchestrates the onboarding process by building an OnboardingPlan
 * from ProjectState and managing step execution.
 * <p>
 * This class has NO UI dependencies - it's pure coordination logic.
 * The UI layer calls this orchestrator and interprets the results.
 * <p>
 * Steps produced include:
 * - MigrationStep (uses StyleGuideMigrator)
 * - BuildSettingsStep (flags UI dialog is needed)
 * - GitConfigStep (uses GitIgnoreConfigurator)
 * - PostGitStyleRegenerationStep (offers style regeneration after Git setup)
 */
public class OnboardingOrchestrator {
    private static final Logger logger = LogManager.getLogger(OnboardingOrchestrator.class);

    /**
     * Builds an onboarding plan from the current project state.
     * Evaluates all available steps, filters to those that are applicable,
     * and orders them by dependencies.
     */
    public OnboardingPlan buildPlan(ProjectState state) {
        logger.debug("Building onboarding plan for project: {}", state.project().getRoot());

        // Get all available steps
        List<OnboardingStep> allSteps = createAllSteps();

        // Filter to applicable steps
        List<OnboardingStep> applicableSteps = allSteps.stream()
                .filter(step -> {
                    boolean applicable = step.isApplicable(state);
                    logger.debug("Step {} applicable: {}", step.id(), applicable);
                    return applicable;
                })
                .toList();

        // Order by dependencies
        List<OnboardingStep> orderedSteps = OnboardingPlan.orderByDependencies(applicableSteps);

        logger.info(
                "Created onboarding plan with {} steps: {}",
                orderedSteps.size(),
                orderedSteps.stream().map(OnboardingStep::id).toList());

        return new OnboardingPlan(orderedSteps);
    }

    /**
     * Creates a ProjectState from the given project and async handles.
     * This helper probes the project's current state to build the ProjectState record.
     */
    public static ProjectState buildProjectState(
            IProject project,
            @Nullable CompletableFuture<String> styleGuideFuture,
            @Nullable CompletableFuture<?> buildDetailsFuture,
            boolean styleGenerationSkippedDueToNoGit) {

        try {
            var configRoot = project.getMasterRootPathForConfig();

            // Check style guide files
            var agentsMdPath = configRoot.resolve(AbstractProject.STYLE_GUIDE_FILE);
            boolean agentsMdExists = Files.exists(agentsMdPath);
            boolean agentsMdHasContent = agentsMdExists && Files.size(agentsMdPath) > 0;

            var legacyPath =
                    configRoot.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.LEGACY_STYLE_GUIDE_FILE);
            boolean legacyExists = Files.exists(legacyPath);
            boolean legacyHasContent =
                    legacyExists && !Files.readString(legacyPath).isBlank();

            // Check project.properties
            var propsPath =
                    configRoot.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.PROJECT_PROPERTIES_FILE);
            boolean propsExists = Files.exists(propsPath);
            boolean propsHasContent = propsExists && Files.size(propsPath) > 0;

            // Check .gitignore
            var gitignorePath = configRoot.resolve(".gitignore");
            boolean gitignoreExists = Files.exists(gitignorePath);
            var gitignoreFile = new ai.brokk.analyzer.ProjectFile(configRoot, ".gitignore");
            boolean gitignoreConfigured = GitIgnoreUtils.isBrokkIgnored(gitignoreFile);

            // Check build details availability
            boolean buildDetailsAvailable = buildDetailsFuture != null
                    && buildDetailsFuture.isDone()
                    && !buildDetailsFuture.isCompletedExceptionally();

            logger.debug(
                    "Project state: agentsMd={}/{}, legacy={}/{}, props={}/{}, git={}/{}, buildAvailable={}",
                    agentsMdExists,
                    agentsMdHasContent,
                    legacyExists,
                    legacyHasContent,
                    propsExists,
                    propsHasContent,
                    gitignoreExists,
                    gitignoreConfigured,
                    buildDetailsAvailable);

            return new ProjectState(
                    project,
                    configRoot,
                    agentsMdExists,
                    agentsMdHasContent,
                    legacyExists,
                    legacyHasContent,
                    styleGenerationSkippedDueToNoGit,
                    propsExists,
                    propsHasContent,
                    buildDetailsAvailable,
                    gitignoreExists,
                    gitignoreConfigured,
                    styleGuideFuture,
                    buildDetailsFuture);

        } catch (IOException e) {
            logger.error("Error building project state", e);
            // Return a minimal state on error
            return new ProjectState(
                    project,
                    project.getMasterRootPathForConfig(),
                    false,
                    false,
                    false,
                    false,
                    styleGenerationSkippedDueToNoGit,
                    false,
                    false,
                    false,
                    false,
                    false,
                    styleGuideFuture,
                    buildDetailsFuture);
        }
    }

    /**
     * Creates all available onboarding steps.
     * This is where we instantiate concrete step implementations.
     * Steps will be filtered later based on applicability.
     */
    private List<OnboardingStep> createAllSteps() {
        var steps = new ArrayList<OnboardingStep>();

        steps.add(new MigrationStep());
        steps.add(new BuildSettingsStep());
        steps.add(new GitConfigStep());
        steps.add(new PostGitStyleRegenerationStep());

        return steps;
    }
}
