package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

public class ComputedValueTest {

    @Test
    public void compute_startsImmediately() throws Exception {
        var cv = new ComputedValue<>("eager", CompletableFuture.supplyAsync(() -> 7));

        assertEquals(7, cv.future().get().intValue());
    }

    @Test
    public void awaitOnNonEdt_timesOutAndReturnsEmpty() {
        var cv = new ComputedValue<>("slow", CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            return 1;
        }));

        Optional<Integer> res = cv.await(Duration.ofMillis(50));
        assertTrue(res.isEmpty(), "await should time out and return empty");
    }

    @Test
    public void awaitOnEdt_returnsEmptyImmediately() throws Exception {
        var cv = new ComputedValue<>("edt", CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            return 2;
        }));

        var ref = new AtomicReference<Optional<Integer>>();
        SwingUtilities.invokeAndWait(() -> {
            Optional<Integer> got = cv.await(Duration.ofSeconds(2));
            ref.set(got);
        });
        assertTrue(ref.get().isEmpty(), "await on EDT must not block and return empty");
    }

    @Test
    public void exception_propagatesToFuture() {
        var cv = new ComputedValue<Integer>("fail", CompletableFuture.supplyAsync(() -> {
            throw new IllegalStateException("boom");
        }));

        var fut = cv.future();
        var ex = assertThrows(java.util.concurrent.CompletionException.class, fut::join);
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertEquals("boom", ex.getCause().getMessage());
    }

    @Test
    public void threadName_hasPredictablePrefix() throws Exception {
        var threadName = new AtomicReference<String>();
        var cv = new ComputedValue<>("nameCheck", CompletableFuture.supplyAsync(() -> {
            threadName.set(Thread.currentThread().getName());
            return 99;
        }));

        cv.future().get();
        assertNotNull(threadName.get());
        assertTrue(threadName.get().startsWith("cv-nameCheck-"));
    }

    @Test
    public void join_isIdempotent() {
        var cv = new ComputedValue<>("joinTest", CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            return "Hello, world!";
        }));

        assertEquals("Hello, world!", cv.join());
        // Check for same result again
        assertEquals("Hello, world!", cv.join());
    }

    @Test
    public void completed_returnsValueImmediately() {
        var cv = ComputedValue.completed("test", 42);

        assertTrue(cv.tryGet().isPresent());
        assertEquals(42, cv.tryGet().get().intValue());
        assertEquals(42, cv.join().intValue());
    }

    @Test
    public void map_transformsValue() throws Exception {
        var cv = ComputedValue.completed("base", 10);
        var mapped = cv.map(v -> v * 2);

        assertEquals(20, mapped.future().get().intValue());
    }

    @Test
    public void flatMap_chainsComputations() throws Exception {
        var cv = ComputedValue.completed("base", 5);
        var chained = cv.flatMap(v -> ComputedValue.completed("derived", v + 3));

        assertEquals(8, chained.future().get().intValue());
    }
}
