package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AbstractService.OfflineStreamingModel;
import ai.brokk.context.Context;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchAgentToolTest {

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    private static SearchAgent newAgent(TestContextManager cm, StreamingChatModel model) {
        return new SearchAgent(cm.liveContext(), "test goal", model, null);
    }

    @Test
    void calculateAllowedToolNames_dropOnlyMode_requiresDroppableFragments() {
        TestConsoleIO io = new TestConsoleIO();
        TestContextManager cm = new TestContextManager(tempDir, io);

        SearchAgent agent = newAgent(cm, new OfflineStreamingModel());
        Context context = agent.currentState.context();

        List<String> allowed = agent.calculateAllowedToolNames(context, true);
        assertEquals(
                List.of(), allowed, "dropOnlyMode with no droppable fragments should expose no non-terminal tools");
    }

    @Test
    void calculateAllowedToolNames_normalMode_includesDropWhenDroppableFragmentsExist() {
        TestConsoleIO io = new TestConsoleIO();
        TestContextManager cm = new TestContextManager(tempDir, io);

        SearchAgent agent = newAgent(cm, new OfflineStreamingModel());

        // Manually add a fragment to make it droppable (originalPinnedFragments is empty in this test)
        Context context = agent.currentState
                .context()
                .addFragments(
                        new ai.brokk.context.ContextFragments.StringFragment(cm, "some content", "desc", "text/plain"));

        assertTrue(
                agent.hasDroppableFragments(context),
                "Workspace should have droppable fragments after adding a new one");
        List<String> allowed = agent.calculateAllowedToolNames(context, false);
        assertTrue(allowed.contains("dropWorkspaceFragments"), "Allowed tools should include drop tool");
    }

    @Test
    void categorizeTool_classifiesDropAsWorkspaceHygiene() {
        TestConsoleIO io = new TestConsoleIO();
        TestContextManager cm = new TestContextManager(tempDir, io);

        SearchAgent agent = newAgent(cm, new OfflineStreamingModel());
        assertEquals(SearchAgent.ToolCategory.WORKSPACE_HYGIENE, agent.categorizeTool("dropWorkspaceFragments"));
    }
}
