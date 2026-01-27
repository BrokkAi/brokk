package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.Context;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Basic unit tests for the TaskScope abstraction declared on IContextManager.
 *
 * Verifies that a test-friendly implementation can be written and that append/publish/close
 * behave in a minimal, predictable way.
 */
public class TaskScopeAbstractionTest {

    static class FakeScope implements TaskScope {
        final AtomicBoolean closed = new AtomicBoolean(false);
        volatile boolean appended = false;
        volatile boolean published = false;

        @Override
        public Context append(TaskResult result) throws InterruptedException {
            if (closed.get()) throw new IllegalStateException("TaskScope is closed");
            appended = true;
            return result.context();
        }

        @Override
        public void publish(Context context) {
            if (closed.get()) throw new IllegalStateException("TaskScope is closed");
            published = true;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    @Test
    void basicAppendPublishClose() throws InterruptedException {
        // Use TestContextManager to obtain a valid Context instance for TaskResult wiring.
        var tcm = new TestContextManager(Path.of("."), new TestConsoleIO());
        Context ctx = tcm.liveContext();

        var tr = TaskResult.humanResult(tcm, "act", List.of(), ctx, TaskResult.StopReason.SUCCESS);

        var scope = new FakeScope();

        // append should return the provided context and mark appended
        Context returned = scope.append(tr);
        assertSame(ctx, returned);
        assertTrue(scope.appended, "append should have been invoked");

        // publish should mark published
        scope.publish(ctx);
        assertTrue(scope.published, "publish should have been invoked");

        // close should transition to closed state
        scope.close();
        assertTrue(scope.closed.get(), "close should mark scope closed");

        // after close, operations should fail
        assertThrows(IllegalStateException.class, () -> scope.append(tr));
        assertThrows(IllegalStateException.class, () -> scope.publish(ctx));
    }
}
