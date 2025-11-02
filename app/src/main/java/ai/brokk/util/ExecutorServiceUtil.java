package ai.brokk.util;

import ai.brokk.exception.GlobalExceptionHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class ExecutorServiceUtil {

    private ExecutorServiceUtil() {}

    public static ExecutorService newFixedThreadExecutor(int parallelism, String threadPrefix) {
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
        return Executors.newFixedThreadPool(parallelism, factory);
    }

    public static ExecutorService newVirtualThreadExecutor(String threadPrefix, int maxConcurrentThreads) {
        if (maxConcurrentThreads <= 0) {
            throw new IllegalArgumentException("maxConcurrentThreads must be > 0");
        }

        final Semaphore permits = new Semaphore(maxConcurrentThreads);

        var factory = new ThreadFactory() {
            private int count = 0;

            @Override
            public synchronized Thread newThread(Runnable r) {
                try {
                    // Block creation if we've reached the cap
                    permits.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while acquiring virtual-thread permit", e);
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
        return Executors.newThreadPerTaskExecutor(factory);
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
