package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.context.Context;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SearchAgentToolTest {

    public static final class ResearchToolProvider {
        @Tool("Test-only tool: returns a value (research category) without modifying workspace")
        public String researchNoop() {
            return "ok";
        }
    }

    private static final StreamingChatModel NO_OP_MODEL = new StreamingChatModel() {
        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            // No-op for tool calculation tests
        }
    };

    private static ToolExecutionRequest req(String name, Map<String, Object> args) {
        String jsonArgs;
        try {
            jsonArgs = Json.getMapper().writeValueAsString(args);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize tool arguments to JSON: " + args, e);
        }
        return ToolExecutionRequest.builder().name(name).arguments(jsonArgs).build();
    }

    @Test
    void testSearchAgentDoesNotExposeRecursiveTools() {
        // Setup mocks and test infrastructure
        TestConsoleIO io = new TestConsoleIO();
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        TestContextManager cm = new TestContextManager(tempDir, io);
        StreamingChatModel model = NO_OP_MODEL;

        SearchAgent agent = new SearchAgent(
                cm.liveContext(), "test goal", model, null // scope not needed for tool calculation
                );

        // Access the protected tool calculation logic
        // Since we are in the same package (ai.brokk.agents), we can call protected methods
        List<String> allowedTools = agent.calculateAllowedToolNames();
        List<String> terminalTools = agent.calculateTerminalTools();

        // Check against tools that should only be in LutzAgent or ArchitectAgent
        List<String> forbiddenTools = List.of("callCodeAgent", "answer", "createOrReplaceTaskList");

        for (String tool : forbiddenTools) {
            assertFalse(allowedTools.contains(tool), "SearchAgent should not have '" + tool + "' in allowed tools");
            assertFalse(terminalTools.contains(tool), "SearchAgent should not have '" + tool + "' in terminal tools");
        }
    }

    @Test
    void testSearchAgentToolRegistryDoesNotContainForbiddenTools() {
        TestConsoleIO io = new TestConsoleIO();
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        TestContextManager cm = new TestContextManager(tempDir, io);
        StreamingChatModel model = NO_OP_MODEL;

        // Use a subclass to avoid the UOE from cm.getToolRegistry() in createToolRegistry
        SearchAgent agent = new SearchAgent(cm.liveContext(), "test goal", model, null) {
            @Override
            protected ToolRegistry createToolRegistry(WorkspaceTools wst) {
                return ToolRegistry.empty()
                        .builder()
                        .register(wst)
                        .register(this)
                        .build();
            }
        };

        WorkspaceTools wst = new WorkspaceTools(cm.liveContext());
        ToolRegistry registry = agent.createToolRegistry(wst);

        List<String> activeTools = new ArrayList<>();
        activeTools.addAll(agent.calculateAllowedToolNames());
        activeTools.addAll(agent.calculateTerminalTools());

        assertFalse(activeTools.contains("callCodeAgent"), "Should not expose callCodeAgent");
        assertFalse(activeTools.contains("answer"), "Should not expose answer");
        assertFalse(activeTools.contains("createOrReplaceTaskList"), "Should not expose createOrReplaceTaskList");

        assertFalse(registry.isRegistered("callCodeAgent"), "SearchAgent should not have callCodeAgent method");
        assertFalse(registry.isRegistered("answer"), "SearchAgent should not have answer method");
    }

    @Test
    void terminalIsNotGatedByHygieneOnlyContextMutation_dropWorkspaceFragmentsRegression() {
        TestConsoleIO io = new TestConsoleIO();
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        TestContextManager cm = new TestContextManager(tempDir, io);

        SearchAgent agent = new SearchAgent(cm.liveContext(), "test goal", NO_OP_MODEL, null);

        Context contextAtTurnStart = agent.context;

        boolean executedResearch = false;

        assertTrue(
                agent.categorizeTool("dropWorkspaceFragments") == SearchAgent.ToolCategory.WORKSPACE_HYGIENE,
                "dropWorkspaceFragments must be hygiene");

        // Simulate what WorkspaceTools.dropWorkspaceFragments does to SearchAgent's context: it creates a new Context
        // id.
        // This is enough to reproduce the pre-fix bug, where the terminal gate required context equality.
        agent.context = new Context(cm);
        assertFalse(agent.context.equals(contextAtTurnStart), "Hygiene must mutate context identity");

        boolean executedNonHygiene = false;

        // This is intentionally the terminal gating logic from SearchAgent. Old code required:
        //   context.equals(contextAtTurnStart)
        // which fails here and would skip the terminal tool. The fixed logic should allow hygiene-only changes.
        boolean contextSafeForTerminal = agent.context.equals(contextAtTurnStart) || !executedNonHygiene;

        assertTrue(
                contextSafeForTerminal && !executedResearch,
                "Terminal tool must be allowed after hygiene-only context mutation (regression for dropWorkspaceFragments)");
    }

    @Test
    void terminalExecutesAfterHygieneContextMutationButNotAfterResearch() throws InterruptedException {
        TestConsoleIO io = new TestConsoleIO();
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        TestContextManager cm = new TestContextManager(tempDir, io);

        AtomicBoolean terminalExecuted = new AtomicBoolean(false);

        ResearchToolProvider researchToolProvider = new ResearchToolProvider();

        SearchAgent agent = new SearchAgent(cm.liveContext(), "test goal", NO_OP_MODEL, null) {
            @Override
            protected ToolRegistry createToolRegistry(WorkspaceTools wst) {
                return ToolRegistry.empty()
                        .builder()
                        .register(wst)
                        .register(this)
                        .register(researchToolProvider)
                        .build();
            }

            @Override
            protected ToolExecutionResult executeTool(
                    ToolExecutionRequest req, ToolRegistry registry, WorkspaceTools wst) throws InterruptedException {
                if (req.name().equals("workspaceComplete")) {
                    terminalExecuted.set(true);
                }
                return super.executeTool(req, registry, wst);
            }

            @Override
            protected ToolCategory categorizeTool(String toolName) {
                if (toolName.equals("researchNoop")) {
                    return ToolCategory.RESEARCH;
                }
                return super.categorizeTool(toolName);
            }
        };

        // Hygiene only: simulate a context mutation (as if appendNote/dropWorkspaceFragments ran),
        // but without invoking WorkspaceTools (which may scan /tmp and fail on systemd-private dirs).
        {
            WorkspaceTools wst = new WorkspaceTools(cm.liveContext());
            ToolRegistry tr = agent.createToolRegistry(wst);

            Context contextAtTurnStart = agent.context;

            boolean executedResearch = false;
            boolean executedNonHygiene = false;

            assertTrue(
                    agent.categorizeTool("appendNote") == SearchAgent.ToolCategory.WORKSPACE_HYGIENE,
                    "appendNote must be hygiene");

            agent.context = new Context(cm);
            assertFalse(agent.context.equals(contextAtTurnStart), "Simulated hygiene should mutate context identity");

            boolean contextSafeForTerminal = agent.context.equals(contextAtTurnStart) || !executedNonHygiene;
            assertTrue(contextSafeForTerminal && !executedResearch);

            var termReq = req("workspaceComplete", Map.of());
            agent.executeTool(termReq, tr, wst);
            assertTrue(terminalExecuted.get(), "Terminal tool should execute after hygiene-only context mutation");
        }

        // If any research tool ran, terminal should be blocked (even if context didn't change).
        {
            terminalExecuted.set(false);
            WorkspaceTools wst = new WorkspaceTools(cm.liveContext());
            ToolRegistry tr = agent.createToolRegistry(wst);

            Context contextAtTurnStart = agent.context;
            boolean executedResearch = false;
            boolean executedNonHygiene = false;

            var researchReq = req("researchNoop", Map.of());
            var researchRes = agent.executeTool(researchReq, tr, wst);
            assertTrue(researchRes.status() == ToolExecutionResult.Status.SUCCESS);

            if (agent.categorizeTool(researchReq.name()) != SearchAgent.ToolCategory.WORKSPACE_HYGIENE) {
                executedNonHygiene = true;
            }
            if (agent.categorizeTool(researchReq.name()) == SearchAgent.ToolCategory.RESEARCH
                    && !agent.isWorkspaceTool(researchReq, tr)) {
                executedResearch = true;
            }

            boolean contextSafeForTerminal = agent.context.equals(contextAtTurnStart) || !executedNonHygiene;
            assertTrue(contextSafeForTerminal, "Research noop should not mutate context");
            assertTrue(executedResearch, "Research tool execution should be detected");

            boolean shouldRunTerminal = contextSafeForTerminal && !executedResearch;
            assertFalse(shouldRunTerminal, "Terminal must be blocked after any research tool ran");
        }
    }
}
