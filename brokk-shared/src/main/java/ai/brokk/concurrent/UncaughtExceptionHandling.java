package ai.brokk.concurrent;

import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Pluggable uncaught exception handler for brokk-core.
 * The default handler simply logs. The full app wires in GlobalExceptionHandler at startup.
 */
public final class UncaughtExceptionHandling {
    private static final Logger logger = LogManager.getLogger(UncaughtExceptionHandling.class);

    private static volatile Consumer<Throwable> handler = th -> logger.error("Uncaught exception", th);

    private UncaughtExceptionHandling() {}

    public static void setHandler(Consumer<Throwable> h) {
        handler = h;
    }

    public static Consumer<Throwable> getHandler() {
        return handler;
    }

    public static void handle(Throwable th) {
        handler.accept(th);
    }

    public static void handle(Thread thread, Throwable th) {
        handler.accept(th);
    }
}
