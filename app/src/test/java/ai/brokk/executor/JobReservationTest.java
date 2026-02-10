package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class JobReservationTest {

    @Test
    void tryReserve_allowsOnlyOneConcurrentReservation() throws Exception {
        var reservation = new JobReservation();

        var startGate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> f1 = pool.submit(() -> {
                startGate.await();
                return reservation.tryReserve("job-A");
            });
            Future<Boolean> f2 = pool.submit(() -> {
                startGate.await();
                return reservation.tryReserve("job-B");
            });

            startGate.countDown();

            boolean r1 = f1.get(5, TimeUnit.SECONDS);
            boolean r2 = f2.get(5, TimeUnit.SECONDS);

            // Exactly one should succeed
            assertNotEquals(r1, r2, "Exactly one reservation should succeed");

            var current = reservation.current();
            assertTrue("job-A".equals(current) || "job-B".equals(current), "Current should be the winning job id");
            assertEquals(current, r1 ? "job-A" : "job-B");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void release_isGuardedByOwnership() {
        var reservation = new JobReservation();
        assertTrue(reservation.tryReserve("job-1"));
        assertFalse(reservation.releaseIfOwner("job-2"), "Non-owner should not be able to release");
        assertEquals("job-1", reservation.current());
        assertTrue(reservation.releaseIfOwner("job-1"));
        assertNull(reservation.current(), "Reservation should be cleared after owner releases");
    }
}
