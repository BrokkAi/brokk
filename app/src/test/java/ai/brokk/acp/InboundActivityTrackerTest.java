package ai.brokk.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AcpServerMain.InboundActivityTracker}. The tracker timestamps every
 * successful read from {@code System.in} so {@link AcpServerMain}'s watchdog can detect a quiet
 * stdin while permission requests are outstanding. Bugs here would silently disable the watchdog —
 * EOF or a zero-byte read must NOT register as activity.
 */
class InboundActivityTrackerTest {

    @Test
    void singleByteReadUpdatesTimestamp() throws IOException {
        var tracker = new AcpServerMain.InboundActivityTracker(new ByteArrayInputStream(new byte[] {42}));
        long before = tracker.lastReadAtMillis();
        sleep(5);
        int b = tracker.read();
        assertEquals(42, b);
        // Strictly greater: the test sleeps before the read, so a tracker that never updates
        // would still satisfy `>= before`. We need the >`>` to actually catch a regression that
        // drops the timestamp write inside read().
        assertTrue(tracker.lastReadAtMillis() > before, "tracker timestamp must advance after a successful read");
    }

    @Test
    void bulkReadUpdatesTimestamp() throws IOException {
        var tracker = new AcpServerMain.InboundActivityTracker(new ByteArrayInputStream("hello".getBytes()));
        long before = tracker.lastReadAtMillis();
        sleep(5);
        var buf = new byte[8];
        int n = tracker.read(buf, 0, buf.length);
        assertEquals(5, n);
        assertTrue(tracker.lastReadAtMillis() > before, "tracker timestamp must advance after a bulk read");
    }

    @Test
    void eofDoesNotUpdateTimestamp() throws IOException {
        var tracker = new AcpServerMain.InboundActivityTracker(new ByteArrayInputStream(new byte[0]));
        long t0 = tracker.lastReadAtMillis();
        sleep(5);
        // single-byte read at EOF returns -1 and must NOT register as activity, otherwise a
        // closed parent process would look indistinguishable from an active client.
        assertEquals(-1, tracker.read());
        assertEquals(t0, tracker.lastReadAtMillis());
    }

    @Test
    void zeroByteBulkReadDoesNotUpdateTimestamp() throws IOException {
        // Wraps an underlying stream that always returns 0 from read(byte[], off, len) — a
        // legal but rare contract. The watchdog must treat this as silence, not activity.
        var alwaysZero = new InputStream() {
            @Override
            public int read() {
                return 0;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return 0;
            }
        };
        var tracker = new AcpServerMain.InboundActivityTracker(alwaysZero);
        long t0 = tracker.lastReadAtMillis();
        sleep(5);
        int n = tracker.read(new byte[4], 0, 4);
        assertEquals(0, n);
        assertEquals(t0, tracker.lastReadAtMillis());
    }

    @Test
    void singleByteReadOfZeroValueStillCountsAsActivity() throws IOException {
        // The ByteArrayInputStream returns the literal byte value 0 (not -1), which the
        // tracker must treat as "received a byte". Earlier code checked `if (b > 0)` instead
        // of `b != -1` — guard against that regression.
        var tracker = new AcpServerMain.InboundActivityTracker(new ByteArrayInputStream(new byte[] {0}));
        long before = tracker.lastReadAtMillis();
        sleep(5);
        assertEquals(0, tracker.read());
        assertTrue(tracker.lastReadAtMillis() > before);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
