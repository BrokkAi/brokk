package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

public class ComputedValueTest {

    @Test
    public void lazyStart_doesNotStartUntilAccessed() throws Exception {
        var started = new CountDownLatch(1);
        var cv = new ComputedValue<Integer>("lazy", () -> {
            started.countDown();
            return 42;
        });

        // Not started yet (lazy constructor must not start)
        assertEquals(1L, started.getCount(), "supplier should not have started yet");

        // tryGet should not start computation
        assertTrue(cv.tryGet().isEmpty());
        assertEquals(1L, started.getCount(), "supplier should still not have started");

        // future() should start computation
        var fut = cv.future();
        assertTrue(
                started.await(500, java.util.concurrent.TimeUnit.MILLISECONDS), "supplier should start after future()");
        assertEquals(42, fut.get().intValue());
    }

    @Test
    public void eagerStart_startsImmediately() throws Exception {
        var started = new CountDownLatch(1);
        var cv = new ComputedValue<Integer>("eager", () -> {
            started.countDown();
            return 7;
        });

        // Explicitly request eager start to avoid race with constructor return
        cv.start();

        assertTrue(started.await(500, java.util.concurrent.TimeUnit.MILLISECONDS), "supplier should start eagerly");
        assertEquals(7, cv.future().get().intValue());
    }

    @Test
    public void awaitOnNonEdt_timesOutAndReturnsEmpty() {
        var cv = new ComputedValue<>("slow", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            return 1;
        });

        Optional<Integer> res = cv.await(Duration.ofMillis(50));
        assertTrue(res.isEmpty(), "await should time out and return empty");
    }

    @Test
    public void awaitOnEdt_returnsEmptyImmediately() throws Exception {
        var cv = new ComputedValue<>("edt", () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            return 2;
        });

        var ref = new AtomicReference<Optional<Integer>>();
        SwingUtilities.invokeAndWait(() -> {
            Optional<Integer> got = cv.await(Duration.ofSeconds(2));
            ref.set(got);
        });
        assertTrue(ref.get().isEmpty(), "await on EDT must not block and return empty");
    }

    @Test
    public void exception_propagatesToFuture() {
        var cv = new ComputedValue<Integer>("fail", () -> {
            throw new IllegalStateException("boom");
        });

        var fut = cv.future();
        var ex = assertThrows(java.util.concurrent.CompletionException.class, fut::join);
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertEquals("boom", ex.getCause().getMessage());
    }

    @Test
    public void threadName_hasPredictablePrefix() throws Exception {
        var threadName = new AtomicReference<String>();
        var cv = new ComputedValue<>("nameCheck", () -> {
            threadName.set(Thread.currentThread().getName());
            return 99;
        });

        cv.future().get();
        assertNotNull(threadName.get());
        assertTrue(threadName.get().startsWith("cv-nameCheck-"));
    }
}
