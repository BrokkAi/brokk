package io.github.jbellis.brokk.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ExecutorServiceUtil {

    private static final Logger logger = LoggerFactory.getLogger(ExecutorServiceUtil.class);

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
                t.setUncaughtExceptionHandler(
                        (thr, ex) -> logger.error("Unhandled exception in {}", thr.getName(), ex));
                return t;
            }
        };
        return Executors.newFixedThreadPool(parallelism, factory);
    }
}
