package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.usages.FuzzyResult;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import dev.langchain4j.data.message.ChatMessageType;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
        private final Optional<FuzzyResult.TooManyCallsites> preflight;

        TestWorkspaceTools(Context initialContext, Optional<FuzzyResult.TooManyCallsites> preflight) {
            super(initialContext);
            this.preflight = preflight;
        }

        @Override
        protected Optional<FuzzyResult.TooManyCallsites> preflightUsages(String symbol) {
            return preflight;
        }
    }

    @Test
    public void addSymbolUsages_showsToolError_andDoesNotAddFragment_onTooManyCallsites() {
        var cm = new StubContextManager();
        var initialContext = cm.liveContext();
        var tooMany = new FuzzyResult.TooManyCallsites("Foo", 150, 100);
        var tools = new TestWorkspaceTools(initialContext, Optional.of(tooMany));

        String result = tools.addSymbolUsagesToWorkspace("com.example.Foo");

        // Verify modal error was shown
        var io = (StubIo) cm.getIo();
        assertTrue(io.called, "Expected toolError to be called");
        assertNotNull(io.msg);
        assertTrue(io.msg.contains("Too many call sites"), "Unexpected error message: " + io.msg);
        assertEquals("Usages limit reached", io.title);
        // Stronger style checks: include symbol and comparative counts
        assertTrue(io.msg.contains("com.example.Foo"), "Expected symbol in error message: " + io.msg);
        assertTrue(io.msg.contains("(150 > limit 100)"), "Expected comparative counts in error message: " + io.msg);

        // Verify no UsageFragment was added
        var ctx = tools.getContext();
        boolean hasUsageFragment =
                ctx.virtualFragments().anyMatch(vf -> vf.getType() == ContextFragment.FragmentType.USAGE);
        assertFalse(hasUsageFragment, "UsageFragment should not be added when TooManyCallsites");

        // Verify return string indicates abort
        assertTrue(result.contains("Aborted adding usages"), "Unexpected return: " + result);
    }

    @Test
    public void addSymbolUsages_addsUsageFragment_onSuccess() {
        var cm = new StubContextManager();
        var initialContext = cm.liveContext();
        var tools = new TestWorkspaceTools(initialContext, Optional.empty());

        String result = tools.addSymbolUsagesToWorkspace("com.example.Foo");

        // Verify no modal error was shown
        var io = (StubIo) cm.getIo();
        assertFalse(io.called, "toolError should not be called for Success");

        // Verify a UsageFragment was added
        var ctx = tools.getContext();
        boolean hasUsageFragment =
                ctx.virtualFragments().anyMatch(vf -> vf.getType() == ContextFragment.FragmentType.USAGE);
        assertTrue(hasUsageFragment, "Expected UsageFragment to be added on Success");

        // Verify return string indicates addition
        assertTrue(result.contains("Added dynamic usage analysis"), "Unexpected return: " + result);
    }
}
