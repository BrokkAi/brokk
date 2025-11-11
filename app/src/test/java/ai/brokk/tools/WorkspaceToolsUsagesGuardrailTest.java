package ai.brokk.tools;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.usages.FuzzyResult;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import dev.langchain4j.data.message.ChatMessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class WorkspaceToolsUsagesGuardrailTest {

    static class StubIo implements IConsoleIO {
        boolean called;
        String msg;
        String title;

        @Override
        public void toolError(String msg, String title) {
            this.called = true;
            this.msg = msg;
            this.title = title;
        }

        @Override
        public void llmOutput(String token, ChatMessageType type, boolean isNewMessage, boolean isReasoning) {
            // no-op for tests
        }
    }

    static class StubContextManager implements IContextManager {
        private final StubIo io = new StubIo();
        private Context live = new Context(this, null);

        @Override
        public Context liveContext() {
            return live;
        }

        @Override
        public IConsoleIO getIo() {
            return io;
        }

        @Override
        public void addVirtualFragments(java.util.Collection<? extends ContextFragment.VirtualFragment> fragments) {
            if (fragments.isEmpty()) return;
            live = live.addVirtualFragments(fragments);
        }
    }

    static class TestWorkspaceTools extends WorkspaceTools {
        private final FuzzyResult preflight;

        TestWorkspaceTools(Context initialContext, FuzzyResult preflight) {
            super(initialContext);
            this.preflight = preflight;
        }

        @Override
        protected FuzzyResult preflightUsages(String symbol) {
            return preflight;
        }
    }

    @Test
    public void addSymbolUsages_showsToolError_andDoesNotAddFragment_onTooManyCallsites() {
        var cm = new StubContextManager();
        var initialContext = cm.liveContext();
        var tooMany = new FuzzyResult.TooManyCallsites("Foo", 150, 100);
        var tools = new TestWorkspaceTools(initialContext, tooMany);

        String result = tools.addSymbolUsagesToWorkspace("com.example.Foo");

        // Verify modal error was shown
        var io = (StubIo) cm.getIo();
        assertTrue(io.called, "Expected toolError to be called");
        assertNotNull(io.msg);
        assertTrue(io.msg.contains("Too many call sites"), "Unexpected error message: " + io.msg);
        assertEquals("Usages limit reached", io.title);

        // Verify no UsageFragment was added
        var ctx = tools.getContext();
        boolean hasUsageFragment = ctx.virtualFragments().anyMatch(vf -> vf.getType() == ContextFragment.FragmentType.USAGE);
        assertFalse(hasUsageFragment, "UsageFragment should not be added when TooManyCallsites");

        // Verify return string indicates abort
        assertTrue(result.contains("Aborted adding usages"), "Unexpected return: " + result);
    }
}
