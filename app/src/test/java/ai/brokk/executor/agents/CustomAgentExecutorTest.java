package ai.brokk.executor.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CustomAgentExecutorTest {

    @Test
    void formatTurnDirective_repeatsTaskOnlyOnFirstTurn() {
        var task = "Audit the payment workflow for race conditions.";
        var toc = "<workspace_toc>PaymentService.java</workspace_toc>";
        var previousAddition =
                """
                <fragment description="PaymentService">
                class PaymentService {}
                </fragment>
                """
                        .stripIndent();

        var firstTurn = CustomAgentExecutor.formatTurnDirective(1, 3, task, List.of(), toc);
        var secondTurn = CustomAgentExecutor.formatTurnDirective(2, 3, task, List.of(previousAddition), toc);

        assertTrue(firstTurn.contains("<task>" + task + "</task>"));
        assertFalse(secondTurn.contains(task));
        assertTrue(secondTurn.contains("<goal>Continue the custom agent task.</goal>"));
        assertTrue(secondTurn.contains("<previous_turn_additions>"));
        assertTrue(secondTurn.contains(previousAddition.trim()));
        assertTrue(
                secondTurn.contains("Call as many next tools in parallel as will most effectively advance your work."));
        assertTrue(secondTurn.contains(toc));
    }

    @Test
    void formatTurnDirective_preservesFinalTurnTerminalGuidance() {
        var directive = CustomAgentExecutor.formatTurnDirective(3, 3, "Do the thing.", List.of(), "<workspace_toc />");

        assertTrue(directive.contains("This is the final turn. Call 'answer' or 'abortSearch' to finish."));
    }
}
