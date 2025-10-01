package io.github.jbellis.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IContextManager;

import java.awt.image.BufferedImage;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;

public class PasteFragmentsComputedValueTest {

    // Simple wrapper that counts get() calls
    static final class CountingFuture<T> implements Future<T> {
        private final Future<T> delegate;
        private final AtomicInteger counter;

        CountingFuture(Future<T> delegate, AtomicInteger counter) {
            this.delegate = delegate;
            this.counter = counter;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) { return delegate.cancel(mayInterruptIfRunning); }
        @Override public boolean isCancelled() { return delegate.isCancelled(); }
        @Override public boolean isDone() { return delegate.isDone(); }
        @Override public T get() throws InterruptedException, ExecutionException {
            counter.incrementAndGet();
            return delegate.get();
        }
        @Override public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            counter.incrementAndGet();
            return delegate.get(timeout, unit);
        }
    }

    private static IContextManager cmWithExecutor(ThreadPoolExecutor exec) {
        return new IContextManager() {
            @Override public ExecutorService getBackgroundTasks() {
                return exec;
            }
        };
    }

    @Test
    public void pasteText_eagerStart_singleComputation_and_placeholders() throws Exception {
        var exec = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

        var descCF = new CompletableFuture<String>();
        var syntaxCF = new CompletableFuture<String>();
        var descGets = new AtomicInteger(0);

        var cm = cmWithExecutor(exec);

        var fragment = new Fragments.PasteTextFragment(
                "hash-text-1",
                cm,
                "some text",
                new CountingFuture<>(descCF, descGets),
                syntaxCF
        );

        // Non-blocking getters return placeholders initially
        assertEquals("(Loading...)", fragment.description());
        assertEquals(SyntaxConstants.SYNTAX_STYLE_MARKDOWN, fragment.syntaxStyle());
        assertEquals("some text", fragment.text()); // text is available immediately

        // Eager start: the description supplier should already be blocking on the future
        // Wait briefly for the worker to invoke get() exactly once
        for (int i = 0; i < 100 && descGets.get() == 0; i++) {
            Thread.sleep(10);
        }
        assertEquals(1, descGets.get());

        // Complete the futures
        syntaxCF.complete(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
        descCF.complete("short summary");

        // Values should resolve
        assertEquals("Paste of short summary", fragment.computedDescription().future().get(1, TimeUnit.SECONDS));
        assertEquals(SyntaxConstants.SYNTAX_STYLE_MARKDOWN, fragment.computedSyntaxStyle().future().get(1, TimeUnit.SECONDS));

        // Single computation: description future get() called exactly once
        assertEquals(1, descGets.get());
    }

    @Test
    public void pasteImage_eagerStart_imageBytesAndPlaceholder() throws Exception {
        var exec = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        var cm = cmWithExecutor(exec);

        var descCF = new CompletableFuture<String>();
        var img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);

        var fragment = new Fragments.AnonymousImageFragment(
                "hash-img-1",
                cm,
                img,
                descCF
        );

        // Initially, description shows placeholder, image bytes may or may not be ready
        assertEquals("(Loading...)", fragment.description());
        var maybeBytes = fragment.computedImageBytes().tryGet();
        maybeBytes.ifPresent(bytes -> {
            assertNotNull(bytes);
            assertTrue(bytes.length > 0);
        });

        // complete description
        descCF.complete("image paste");

        // image bytes should resolve from eager computation
        var bytes = fragment.computedImageBytes().future().get(1, TimeUnit.SECONDS);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
    }
}
