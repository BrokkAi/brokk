package io.github.jbellis.brokk.gui.mop.webview;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for MOPWebViewHost initialization, specifically focusing on preventing JavaFX/Swing deadlocks during concurrent
 * initialization.
 */
class MOPWebViewHostTest {

    @BeforeAll
    static void checkHeadlessMode() {
        // Verify we're running in headless mode as expected by build configuration
        assertTrue(GraphicsEnvironment.isHeadless(), "Tests should run in headless mode (java.awt.headless=true)");
    }

    @Test
    @DisplayName("Multiple concurrent MOPWebViewHost instantiations should be safe")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testConcurrentInstantiation() throws InterruptedException {
        var threadCount = 5;
        var executorService = Executors.newFixedThreadPool(threadCount);
        var completedCount = new AtomicInteger(0);
        var latch = new CountDownLatch(threadCount);
        var exceptions = new ConcurrentLinkedQueue<Exception>();

        try {
            // Start multiple threads that simultaneously create MOPWebViewHost instances
            for (int i = 0; i < threadCount; i++) {
                executorService.submit(() -> {
                    try {
                        var host = new MOPWebViewHost();
                        completedCount.incrementAndGet();

                        // Simulate some brief usage
                        Thread.sleep(50);

                        assertNotNull(host);
                    } catch (Exception e) {
                        exceptions.offer(e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait for all threads to complete
            assertTrue(latch.await(10, TimeUnit.SECONDS), "All instantiation threads should complete within timeout");

            // Verify no exceptions occurred
            if (!exceptions.isEmpty()) {
                var firstException = exceptions.poll();
                fail("Concurrent instantiation failed with exception: " + firstException.getMessage(), firstException);
            }

            // Verify all instances were created successfully
            assertEquals(
                    threadCount, completedCount.get(), "All MOPWebViewHost instances should be created successfully");

        } finally {
            executorService.shutdown();
            assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("Repeated Platform.startup calls should handle already initialized state")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testRepeatedPlatformStartup() {
        // Platform.startup() can only be called once per JVM
        // We test that the "already initialized" state is handled gracefully
        assertDoesNotThrow(
                () -> {
                    // The first call might succeed or fail depending on test execution order
                    // Subsequent calls will throw IllegalStateException which is expected

                    for (int i = 0; i < 3; i++) {
                        try {
                            Platform.startup(() -> {});
                        } catch (IllegalStateException e) {
                            if (e.getMessage().contains("Toolkit already initialized")) {
                                // This is expected after the first successful call
                                continue;
                            } else {
                                throw e;
                            }
                        }
                    }

                    // Create a MOPWebViewHost instance - should work regardless
                    var host = new MOPWebViewHost();
                    assertNotNull(host);
                },
                "Handling already initialized JavaFX toolkit should not cause issues");
    }
}
