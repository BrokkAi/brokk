package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.ContextManager;
import ai.brokk.agents.LutzAgent;
import ai.brokk.agents.SearchAgent;
import ai.brokk.prompts.SearchPrompts.Objective;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.BuildVerifier;
import ai.brokk.util.Environment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ai.brokk.util.Environment.shellCommandRunnerFactory")
class HostCommandToolTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        Environment.shellCommandRunnerFactory = Environment.DEFAULT_SHELL_COMMAND_RUNNER_FACTORY;
    }

    @Test
    void runShellCommand_success_includesCommandAndCapturedOutput() {
        AtomicReference<String> seenCommand = new AtomicReference<>();
        AtomicReference<Path> seenRoot = new AtomicReference<>();
        AtomicReference<Duration> seenTimeout = new AtomicReference<>();

        Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
            seenCommand.set(command);
            seenRoot.set(root);
            seenTimeout.set(timeout);
            outputConsumer.accept("hello");
            outputConsumer.accept("world");
            return "hello\nworld";
        };

        var tool = new HostCommandTool(new TestProject(tempDir));
        ToolOutput output = tool.runShellCommand("echo hi");
        String text = output.llmText();

        assertEquals("echo hi", seenCommand.get());
        assertEquals(tempDir, seenRoot.get());
        assertEquals(Environment.DEFAULT_TIMEOUT, seenTimeout.get());
        assertInstanceOf(HostCommandTool.CommandOutput.class, output);
        assertTrue(text.contains("runShellCommand: ok"));
        assertTrue(text.contains("command: echo hi"));
        assertTrue(text.contains("exit_code: 0"));
        assertTrue(text.contains("output:"));
        assertTrue(text.contains("hello"));
        assertTrue(text.contains("world"));
        assertFalse(text.contains("```"));
    }

    @Test
    void runShellCommand_success_fallsBackToReturnedOutputWhenNoLinesAreStreamed() {
        Environment.shellCommandRunnerFactory =
                (command, root) -> (outputConsumer, timeout) -> "returned only\nstill visible";

        var tool = new HostCommandTool(new TestProject(tempDir));
        String text = tool.runShellCommand("git status").llmText();

        assertTrue(text.contains("runShellCommand: ok"));
        assertTrue(text.contains("command: git status"));
        assertTrue(text.contains("exit_code: 0"));
        assertTrue(text.contains("returned only"));
        assertTrue(text.contains("still visible"));
        assertFalse(text.contains("### "));
    }

    @Test
    void runShellCommand_failure_includesExitCodeAndPreservedOutputText() {
        Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
            throw new Environment.FailureException(
                    "process 'bad command' signaled error code 42", "stdout:\nboom\n\nstderr:\nkaput", 42);
        };

        var tool = new HostCommandTool(new TestProject(tempDir));
        ToolOutput output = tool.runShellCommand("bad command");
        String text = output.llmText();

        assertInstanceOf(HostCommandTool.CommandOutput.class, output);
        assertTrue(text.contains("runShellCommand: failed"));
        assertTrue(text.contains("command: bad command"));
        assertTrue(text.contains("exit_code: 42"));
        assertTrue(text.contains("process 'bad command' signaled error code 42"));
        assertTrue(text.contains("stdout:"));
        assertTrue(text.contains("boom"));
        assertTrue(text.contains("stderr:"));
        assertTrue(text.contains("kaput"));
        assertFalse(text.contains("null"));
        assertFalse(text.contains("```"));
    }

    @Test
    void runShellCommand_outputIsBoundedToBuildVerifierLimit() {
        int totalLines = BuildVerifier.MAX_OUTPUT_LINES + 5;
        Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
            for (int i = 0; i < totalLines; i++) {
                outputConsumer.accept("line-" + i);
            }
            return "";
        };

        var tool = new HostCommandTool(new TestProject(tempDir));
        String text = tool.runShellCommand("many-lines").llmText();

        assertTrue(text.contains("runShellCommand: ok"));
        assertTrue(text.contains("command: many-lines"));
        assertTrue(text.contains("line-" + (totalLines - 1)));
        assertTrue(text.contains("line-5"));
        assertFalse(text.contains("line-0"));
        assertFalse(text.contains("line-4"));
    }

    @Test
    void toolRegistry_executesAnnotatedRunShellCommandTool() throws Exception {
        Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
            outputConsumer.accept("ok from runner");
            return "ok from runner";
        };

        var registry = new ToolRegistry()
                .builder()
                .register(new HostCommandTool(new TestProject(tempDir)))
                .build();
        Method method = HostCommandTool.class.getDeclaredMethod("runShellCommand", String.class);
        var request = ToolExecutionRequest.builder()
                .id("tool-1")
                .name("runShellCommand")
                .arguments(jsonArgs(method, "pwd"))
                .build();

        var result = registry.executeTool(request);

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertInstanceOf(HostCommandTool.CommandOutput.class, result.result());
        assertTrue(result.resultText().contains("runShellCommand: ok"));
        assertTrue(result.resultText().contains("command: pwd"));
        assertTrue(result.resultText().contains("exit_code: 0"));
        assertTrue(result.resultText().contains("ok from runner"));
    }

    @Test
    void toolRegistry_executesAnnotatedRunShellCommandTool_failurePathPreservesDiagnostics() throws Exception {
        Environment.shellCommandRunnerFactory = (command, root) -> (outputConsumer, timeout) -> {
            throw new Environment.FailureException(
                    "process 'false' signaled error code 9", "stdout:\nregistry boom\n\nstderr:\nregistry kaput", 9);
        };

        var registry = new ToolRegistry()
                .builder()
                .register(new HostCommandTool(new TestProject(tempDir)))
                .build();
        Method method = HostCommandTool.class.getDeclaredMethod("runShellCommand", String.class);
        var request = ToolExecutionRequest.builder()
                .id("tool-2")
                .name("runShellCommand")
                .arguments(jsonArgs(method, "false"))
                .build();

        var result = registry.executeTool(request);

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertInstanceOf(HostCommandTool.CommandOutput.class, result.result());
        assertTrue(result.resultText().contains("runShellCommand: failed"));
        assertTrue(result.resultText().contains("command: false"));
        assertTrue(result.resultText().contains("exit_code: 9"));
        assertTrue(result.resultText().contains("registry boom"));
        assertTrue(result.resultText().contains("registry kaput"));
        assertFalse(result.resultText().contains("null"));
    }

    @Test
    void contextManagerBaseRegistry_registersRunShellCommand() {
        var project = new TestProject(tempDir);
        try (var contextManager = new ContextManager(project)) {
            assertTrue(contextManager.getToolRegistry().isRegistered("runShellCommand"));
        }
    }

    @Test
    void lutzAgentStaticTools_includeRunShellCommand() {
        var project = new TestProject(tempDir);
        List<String> toolNames = LutzAgent.initStaticTools(project, List.of());

        assertTrue(toolNames.contains("runShellCommand"));
    }

    @Test
    void searchAgentAllowedToolNames_includeRunShellCommand() {
        var project = new TestProject(tempDir);
        List<String> toolNames = SearchAgent.allowedToolNames(project, Objective.WORKSPACE_ONLY);

        assertTrue(toolNames.contains("runShellCommand"));
    }

    private static String jsonArgs(Method method, Object... values) throws JsonProcessingException {
        Parameter[] parameters = method.getParameters();
        assertEquals(parameters.length, values.length);
        var arguments = new LinkedHashMap<String, Object>();
        for (int i = 0; i < parameters.length; i++) {
            arguments.put(parameters[i].getName(), values[i]);
        }
        return OBJECT_MAPPER.writeValueAsString(arguments);
    }
}
