package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchAgentToolTest {

    private static final StreamingChatModel NO_OP_MODEL = new StreamingChatModel() {
        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            // No-op for tool calculation tests
        }
    };

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

        // Verify that even if the registry is built, the agent's active tool selection logic
        // excludes the forbidden tools.

        List<String> activeTools = new java.util.ArrayList<>();
        activeTools.addAll(agent.calculateAllowedToolNames());
        activeTools.addAll(agent.calculateTerminalTools());

        assertFalse(activeTools.contains("callCodeAgent"), "Should not expose callCodeAgent");
        assertFalse(activeTools.contains("answer"), "Should not expose answer");
        assertFalse(activeTools.contains("createOrReplaceTaskList"), "Should not expose createOrReplaceTaskList");

        // Verify that SearchAgent itself doesn't define callCodeAgent or answer methods
        // (createOrReplaceTaskList is in WorkspaceTools which is fine - SearchAgent just doesn't expose it)
        assertFalse(registry.isRegistered("callCodeAgent"), "SearchAgent should not have callCodeAgent method");
        assertFalse(registry.isRegistered("answer"), "SearchAgent should not have answer method");
    }
}
