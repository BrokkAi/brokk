package ai.brokk.tools;

import ai.brokk.IConsoleIO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * Utility for executing tools with approval gating.
 *
 * <p>Replaces the common three-line agent pattern:
 * <pre>
 *   io.beforeToolCall(req);
 *   ToolExecutionResult result = registry.executeTool(req);
 *   io.afterToolOutput(result);
 * </pre>
 * with a single call that checks approval before execution and returns a
 * {@code REQUEST_ERROR} result (with "denied by user" message) if the user declines.
 */
public final class ToolExecutionHelper {

    private ToolExecutionHelper() {}

    /**
     * Executes a tool with approval gating.
     *
     * <p>Checks whether the tool is annotated {@link Destructive} and passes this
     * to {@link IConsoleIO#beforeToolCall}. If approved, executes the tool and calls
     * {@link IConsoleIO#afterToolOutput}. If denied, returns a {@link ToolExecutionResult}
     * with {@code REQUEST_ERROR} status so the LLM receives feedback that the call was rejected.
     */
    public static ToolExecutionResult executeWithApproval(
            IConsoleIO io, ToolRegistry registry, ToolExecutionRequest request) throws InterruptedException {
        boolean destructive = registry.isToolAnnotated(request.name(), Destructive.class);
        var approval = io.beforeToolCall(request, destructive);
        if (!approval.isApproved()) {
            var result = ToolExecutionResult.requestError(
                    request, "Tool call '%s' was denied by user.".formatted(request.name()));
            io.afterToolOutput(result);
            return result;
        }
        ToolExecutionResult result = registry.executeTool(request);
        io.afterToolOutput(result);
        return result;
    }
}
