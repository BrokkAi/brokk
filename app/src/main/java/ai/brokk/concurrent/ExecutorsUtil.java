package ai.brokk.concurrent;

import ai.brokk.exception.GlobalExceptionHandler;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility for creating specialized executors.
 *
 * <p><b>Note on Interruption Path:</b>
 * When a user cancels an LLM action (e.g., via the "Stop" button), {@link UserActionManager#cancelActiveAction()}
 * interrupts the worker thread. If that thread is currently computing a {@link ai.brokk.context.ContextDelta}
 * (triggered by {@link ai.brokk.agents.SearchAgent#applyPinning}), it may call {@link #newVirtualThreadExecutor}.
 *
 * <p>The call chain is:
 * 1. {@code InstructionsPanel.executeSearchInternal}
 * 2. {@code SearchAgent.execute} -> {@code applyPinning}
 * 3. {@code ContextDelta.between}
 * 4. {@code ExecutorsUtil.newVirtualThreadExecutor}
 * 5. {@code Semaphore.acquire()} (inside the ThreadFactory)
 *
 * <p>If interrupted at step 5, the {@code InterruptedException} is caught, the interrupt flag is re-set,
 * and a {@link CancellationException} is thrown. This is intended to be interpreted by callers
 * (especially {@link UserActionManager} and {@link GlobalExceptionHandler}) as a normal
 * cancellation rather than a client crash.
 */
public final class ExecutorsUtil {

    private ExecutorsUtil() {}

    public static LoggingExecutorService newFixedThreadExecutor(int parallelism, String threadPrefix) {
        assert parallelism >= 1 : "parallelism must be >= 1";
        var factory = new ThreadFactory() {
            private final ThreadFactory delegate = Executors.defaultThreadFactory();
            private int count = 0;

            @Override
            public synchronized Thread newThread(Runnable r) {
                var t = delegate.newThread(r);
                t.setName(threadPrefix + ++count);
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thr, ex) -> {
                    GlobalExceptionHandler.handle(thr, ex, s -> {});
                });
                return t;
            }
        };
        var delegate = Executors.newFixedThreadPool(parallelism, factory);
        return new LoggingExecutorService(delegate, th -> GlobalExceptionHandler.handle(th, st -> {}));
    }

    public static LoggingExecutorService newVirtualThreadExecutor(String threadPrefix, int maxConcurrentThreads) {
        if (maxConcurrentThreads <= 0) {
            throw new IllegalArgumentException("maxConcurrentThreads must be > 0");
        }

        final Semaphore permits = new Semaphore(maxConcurrentThreads);

        var factory = new ThreadFactory() {
            private int count = 0;

            @Override
            public synchronized Thread newThread(Runnable r) {
                try {
                    // Block creation if we've reached the cap. If interrupted (e.g. via
                    // UserActionManager.cancelActiveAction), we preserve interrupt status
                    // and throw CancellationException to signal a benign cancellation.
                    permits.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Interrupted while acquiring virtual-thread permit");
                }

                // Ensure the permit is released when the task completes
                Runnable wrapped = () -> {
                    try {
                        r.run();
                    } finally {
                        permits.release();
                    }
                };

                var t = Thread.ofVirtual().name(threadPrefix + ++count).unstarted(wrapped);
                t.setUncaughtExceptionHandler((thr, ex) -> {
                    GlobalExceptionHandler.handle(thr, ex, s -> {});
                });
                return t;
            }
        };
        var delegate = Executors.newThreadPerTaskExecutor(factory);
        return new LoggingExecutorService(delegate, th -> GlobalExceptionHandler.handle(th, st -> {}));
    }

    public static ThreadFactory createNamedThreadFactory(String prefix) {
        var counter = new AtomicInteger(0);
        return r -> {
            var thread = new Thread(r);
            thread.setName(prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
