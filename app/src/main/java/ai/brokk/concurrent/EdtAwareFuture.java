package ai.brokk.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.swing.*;

/**
 * A CompletableFuture that asserts blocking operations are not called on the EDT.
 * Not intended for public use; use LoggingFuture instead.
 */
class EdtAwareFuture<T> extends CompletableFuture<T> {
    private void assertNotOnEdt() {
        assert isDone() || !SwingUtilities.isEventDispatchThread()
                : "Blocking call on EDT - use async callbacks instead";
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        assertNotOnEdt();
        return super.get();
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        assertNotOnEdt();
        return super.get(timeout, unit);
    }

    @Override
    public T join() {
        assertNotOnEdt();
        return super.join();
    }

    @Override
    public <U> CompletableFuture<U> newIncompleteFuture() {
        return new EdtAwareFuture<>();
    }
}
