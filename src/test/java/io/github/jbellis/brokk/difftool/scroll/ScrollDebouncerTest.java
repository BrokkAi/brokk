package io.github.jbellis.brokk.difftool.scroll;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ScrollDebouncer} timer and debouncing logic.
 */
class ScrollDebouncerTest
{
    private ScrollDebouncer debouncer;

    @BeforeEach
    void setUp()
    {
        debouncer = new ScrollDebouncer(50); // 50ms debounce for faster tests
    }

    @AfterEach
    void tearDown()
    {
        debouncer.dispose();
    }

    @Test
    void basicDebouncing() throws InterruptedException
    {
        AtomicInteger executeCount = new AtomicInteger(0);
        AtomicReference<String> lastValue = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        var request = new ScrollDebouncer.DebounceRequest<>(
            "test-value",
            value -> {
                executeCount.incrementAndGet();
                lastValue.set(value);
            },
            latch::countDown
        );

        debouncer.submit(request);
        assertTrue(debouncer.hasPending(), "Should have pending action");

        // Wait for execution
        assertTrue(latch.await(200, TimeUnit.MILLISECONDS), "Should execute within timeout");
        
        assertEquals(1, executeCount.get(), "Should execute exactly once");
        assertEquals("test-value", lastValue.get(), "Should pass correct value");
        assertFalse(debouncer.hasPending(), "Should not have pending action after execution");
    }

    @Test
    void debouncingCancellation() throws InterruptedException
    {
        AtomicInteger executeCount = new AtomicInteger(0);
        CountDownLatch executionLatch = new CountDownLatch(1);

        // Submit first request
        var firstRequest = new ScrollDebouncer.DebounceRequest<>(
            "first",
            value -> executeCount.incrementAndGet()
        );
        debouncer.submit(firstRequest);

        // Small delay to ensure first request is queued
        Thread.sleep(10);

        // Submit second request before first executes (should cancel first)
        var secondRequest = new ScrollDebouncer.DebounceRequest<>(
            "second", 
            value -> executeCount.incrementAndGet(),
            executionLatch::countDown
        );
        debouncer.submit(secondRequest);

        // Only second should execute
        assertTrue(executionLatch.await(300, TimeUnit.MILLISECONDS), "Second request should execute");
        
        // Give some extra time to see if first executes (it shouldn't)
        Thread.sleep(100);
        
        assertEquals(1, executeCount.get(), "Should execute exactly once (second request only)");
        assertFalse(debouncer.hasPending(), "Should not have pending action");
    }

    @Test
    void multipleRapidSubmissions() throws InterruptedException
    {
        AtomicInteger executeCount = new AtomicInteger(0);
        AtomicReference<Integer> lastValue = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Submit multiple requests rapidly
        for (int i = 0; i < 10; i++) {
            final int value = i;
            var request = new ScrollDebouncer.DebounceRequest<>(
                value,
                lastValue::set,
                i == 9 ? latch::countDown : null // Only count down on last submission
            );
            debouncer.submit(request);
            
            // Small delay to simulate rapid user input
            Thread.sleep(5);
        }

        // Wait for final execution
        assertTrue(latch.await(200, TimeUnit.MILLISECONDS), "Should execute final request");
        
        // Should only execute the last request
        assertEquals(Integer.valueOf(9), lastValue.get(), "Should execute with last submitted value");
        assertFalse(debouncer.hasPending(), "Should not have pending action");
    }

    @Test
    void manualCancellation() throws InterruptedException
    {
        AtomicInteger executeCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        var request = new ScrollDebouncer.DebounceRequest<>(
            "test",
            value -> {
                executeCount.incrementAndGet();
                latch.countDown();
            }
        );

        debouncer.submit(request);
        assertTrue(debouncer.hasPending(), "Should have pending action");

        debouncer.cancel();
        assertFalse(debouncer.hasPending(), "Should not have pending action after cancel");

        // Wait to ensure it doesn't execute
        assertFalse(latch.await(100, TimeUnit.MILLISECONDS), "Should not execute after cancel");
        assertEquals(0, executeCount.get(), "Should not have executed");
    }

    @Test
    void disposeCleanup()
    {
        AtomicInteger executeCount = new AtomicInteger(0);
        
        var request = new ScrollDebouncer.DebounceRequest<>(
            "test",
            value -> executeCount.incrementAndGet()
        );

        debouncer.submit(request);
        assertTrue(debouncer.hasPending(), "Should have pending action");

        debouncer.dispose();
        assertFalse(debouncer.hasPending(), "Should not have pending action after dispose");
    }

    @Test
    void requestWithoutOnComplete() throws InterruptedException
    {
        AtomicInteger executeCount = new AtomicInteger(0);
        AtomicReference<String> value = new AtomicReference<>();
        
        // Create request without onComplete callback
        var request = new ScrollDebouncer.DebounceRequest<>("test", val -> {
            executeCount.incrementAndGet();
            value.set(val);
        });

        debouncer.submit(request);
        
        // Wait for execution (no latch to wait on, so use polling)
        long start = System.currentTimeMillis();
        while (executeCount.get() == 0 && System.currentTimeMillis() - start < 200) {
            Thread.sleep(10);
        }
        
        assertEquals(1, executeCount.get(), "Should execute without onComplete");
        assertEquals("test", value.get(), "Should pass correct value");
    }
}