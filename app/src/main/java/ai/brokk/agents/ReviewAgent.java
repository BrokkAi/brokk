package ai.brokk.agents;

import ai.brokk.ContextManager;
import ai.brokk.ICodeReview;
import ai.brokk.IContextManager;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.project.ModelProperties.ModelType;
import java.util.Objects;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Blocking;
import org.jspecify.annotations.NullMarked;

/**
 * ReviewAgent wraps SearchAgent to perform a guided code review on a specific diff.
 */
@NullMarked
public class ReviewAgent {

    private final String diff;
    private final IContextManager cm;

    public ReviewAgent(String diff, IContextManager cm) {
        this.diff = diff;
        this.cm = cm;
    }

    @Blocking
    public ICodeReview.GuidedReview execute() throws InterruptedException {
        String goal = "Perform a comprehensive code review of the provided diff, focusing on design, correctness, and simplicity.";

        // Prepare the initial context with the diff pinned
        var diffFragment = new ContextFragments.StringFragment(
                cm,
                diff,
                "Proposed Changes (Diff)",
                SyntaxConstants.SYNTAX_STYLE_NONE);

        Context initialContext = cm.liveContext()
                .addFragments(diffFragment)
                .withPinned(diffFragment, true);

        // Configure SearchAgent with ARCHITECT model and noAppend scan config
        var model = cm.getService().getModel(ModelType.ARCHITECT);
        var scanConfig = SearchAgent.ScanConfig.noAppend();

        try (ContextManager.TaskScope scope = ((ContextManager) cm).beginTask(goal, false, "Code Review")) {
            SearchAgent agent = new SearchAgent(
                    initialContext,
                    goal,
                    model,
                    scope,
                    cm.getIo(),
                    scanConfig);

            // Perform context scanning
            agent.scanContext();

            // Execute the agent turn(s)
            TaskResult result = agent.execute();

            if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
                throw new RuntimeException("Review agent failed: " + result.stopDetails().explanation());
            }

            // Extract GuidedReview from the JSON result of the terminal tool (createReview)
            String jsonOutput = result.stopDetails().explanation();
            return ICodeReview.GuidedReview.fromJson(jsonOutput);
        }
    }
}
