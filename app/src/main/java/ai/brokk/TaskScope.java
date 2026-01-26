package ai.brokk;

import ai.brokk.context.Context;

/**
 * Lightweight task-scope abstraction intended to be implemented by ContextManager.TaskScope and test fakes.
 *
 * Implementations must be safe for use in tests (no UI or Analyzer dependencies).
 */
public interface TaskScope extends AutoCloseable {
    /**
     * Appends a TaskResult to the context history and returns the updated Context.
     *
     * @throws InterruptedException if the operation is interrupted
     */
    @org.jetbrains.annotations.Blocking
    Context append(TaskResult result) throws InterruptedException;

    /** Publishes an intermediate Context snapshot to history without finalizing a TaskResult. */
    void publish(Context context);

    /** Close the scope, performing any necessary finalization work. */
    @Override
    void close() throws InterruptedException;
}
