package ai.brokk.executor.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.brokk.TaskResult;
import ai.brokk.tools.ToolExecutionResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

class ParallelCustomAgentTest {

    private static ToolExecutionRequest request() {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name("callCustomAgent")
                .arguments("{}")
                .build();
    }

    @Test
    void toToolExecutionResult_throwsOnInterruptedStopReason() {
        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED, "Cancelled by user.");

        assertThrows(
                InterruptedException.class,
                () -> ParallelCustomAgent.toToolExecutionResult(request(), stopDetails, stopDetails.explanation()));
    }

    @Test
    void toToolExecutionResult_returnsFatalForLlmError() throws InterruptedException {
        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, "LLM failed");

        var result = ParallelCustomAgent.toToolExecutionResult(request(), stopDetails, "LLM failed");

        assertEquals(ToolExecutionResult.Status.FATAL, result.status());
        assertEquals("LLM failed", result.resultText());
    }

    @Test
    void toToolExecutionResult_preservesNonFatalStopReasonsAsSuccess() throws InterruptedException {
        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ABORTED, "Need more context");

        var result = ParallelCustomAgent.toToolExecutionResult(request(), stopDetails, "Need more context");

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("Need more context", result.resultText());
    }
}
