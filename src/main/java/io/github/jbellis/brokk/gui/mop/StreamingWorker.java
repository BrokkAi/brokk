package io.github.jbellis.brokk.gui.mop;

import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.*;

/**
 * Handles parsing and rendering of streaming Markdown content in a background thread.
 * Prevents UI freezes by moving heavy work off the EDT and throttling updates.
 * 
 * Thread-safety features:
 * 1. Heavy parsing work happens off EDT
 * 2. UI updates always on EDT via SwingUtilities.invokeLater
 * 3. Epoch tracking to prevent stale results from being applied
 * 4. Memory-bounded operation by clearing chunks after snapshot
 */
final class StreamingWorker {
    private static final Logger logger = LogManager.getLogger(StreamingWorker.class);

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "Markdown-Parse-Thread");
        t.setDaemon(true);
        return t;
    });

    // --- shared state -------------------------------------------------------
    private final StringBuilder fullText = new StringBuilder();
    private final AtomicBoolean contentAddedSinceLastParse = new AtomicBoolean(false);
    private final AtomicBoolean parseRunning = new AtomicBoolean(false);
    private final AtomicBoolean updatePending = new AtomicBoolean(false);
    private final AtomicInteger epochGen = new AtomicInteger();
    private final AtomicInteger lastApplied = new AtomicInteger();
    private final AtomicReference<CompletableFuture<Void>> inFlight = 
            new AtomicReference<>(CompletableFuture.completedFuture(null));

    private final IncrementalBlockRenderer renderer;

    StreamingWorker(IncrementalBlockRenderer renderer) {
        this.renderer = renderer;
    }

    /* called from EDT */ 
    void appendChunk(String text) {
        if (text == null || text.isEmpty()) { // an empty string append is used by flush to trigger a parse
            if (fullText.length() > 0) { // only set flag if there's actual content that might need parsing
                contentAddedSinceLastParse.set(true);
            }
        } else {
            fullText.append(text);
            contentAddedSinceLastParse.set(true);
        }
        tryScheduleParse();
    }
    
    /**
     * Waits until all currently scheduled parses and renders are complete.
     * Can be called from any thread.
     */
    void flush() {
        assert !SwingUtilities.isEventDispatchThread() : "StreamingWorker.flush() must not be called on EDT";
        // Make sure anything still in chunks becomes a task
        appendChunk("");
        var future = inFlight.get();
        
        // Safe to block on a background thread
        try {
            future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                logger.trace("Flush interrupted while waiting for rendering", e.getCause());
            } else {
                logger.trace("Error waiting for rendering to complete during flush", e);
            }
        } catch (CancellationException e) {
            logger.trace("Flush cancelled while waiting for rendering", e);
        }
    }

    /**
     * Returns a future that completes when the current batch of rendering is finished on the EDT.
     * This method does not block.
     *
     * @return a CompletableFuture that will be completed on the EDT.
     */
    CompletableFuture<Void> flushAsync() {
        appendChunk(""); // Ensure any pending data is scheduled for processing
        return inFlight.get();
    }

    /* ---------------------------------------------------------------------- */

    private void tryScheduleParse() {
        // Only proceed if a parse isn't running AND there's new content or it's the first run
        if (!contentAddedSinceLastParse.get() && lastApplied.get() > 0) { // lastApplied > 0 means at least one parse happened
             // No new content since last parse completed, and it's not the initial parse
            if (parseRunning.get()) { // if a parse is running, it will pick up the contentAddedSinceLastParse flag later
                return;
            }
            // if no parse is running and no content added, nothing to do.
            // This prevents scheduling empty parses if appendChunk("") is called multiple times by flush when idle.
            if (!inFlight.get().isDone()) { // If there's an active future (e.g. from a previous flush), let it complete.
                return;
            }
        }

        if (!parseRunning.compareAndSet(false, true)) {
            return; // Another thread just started a parse, or a parse is already running.
                    // The contentAddedSinceLastParse flag will ensure this new text is picked up.
        }

        // At this point, parseRunning is true, and this thread is responsible for the parse.
        // Take a snapshot of the CharSequence. Flexmark parser can handle CharSequence directly.
        // This avoids creating a new String for the entire document content each time.
        CharSequence snapshot = fullText;
        contentAddedSinceLastParse.set(false); // Reset flag, current parse will process this content

        int myEpoch = epochGen.incrementAndGet();
        
        // Create a new future to track this task
        var taskDone = new CompletableFuture<Void>();
        inFlight.set(taskDone);

        if (exec.isShutdown()) {
            parseRunning.set(false); // Release the lock as we are not proceeding
            taskDone.completeExceptionally(new CancellationException("Executor is shutdown, task not scheduled"));
            return;
        }

        try {
            exec.submit(() -> parseAndRender(snapshot, myEpoch, taskDone));
        } catch (RejectedExecutionException e) {
            parseRunning.set(false); // Release the lock
            taskDone.completeExceptionally(new CancellationException("Executor was shut down before task could be scheduled"));
            logger.trace("Task rejected due to executor shutdown", e);
        }
    }

    private void parseAndRender(CharSequence markdownContent, int myEpoch, CompletableFuture<Void> done) {
        try {
            // Heavy work off EDT: parse markdown to HTML and build component data
            // The markdownContent is a CharSequence (our StringBuilder instance)
            String html = renderer.createHtml(markdownContent);
            List<ComponentData> components = renderer.buildComponentData(html);
            
            // Queue UI update on EDT with epoch check
            if (updatePending.getAndSet(true)) {
                parseRunning.set(false);  // EDT will clear updatePending
                done.complete(null);      // Mark this task done
                return;                   // another update already queued
            }
            
            SwingUtilities.invokeLater(() -> {
                try {
                    if (myEpoch <= lastApplied.get()) {
                        // Newer epoch seen, cancel this update
                        updatePending.set(false);
                        parseRunning.set(false);
                        done.complete(null);
                        return;
                    }
                    
                    // Apply UI changes on EDT
                    renderer.applyUI(components);
                    lastApplied.set(myEpoch);
                    
                    updatePending.set(false);
                    parseRunning.set(false);
                    
                    // If text changed while we were painting (or new text arrived), kick another parse
                    if (contentAddedSinceLastParse.get()) {
                        tryScheduleParse();
                    }
                } finally {
                    // Always complete the future, even if an exception occurs
                    done.complete(null);
                }
            });
        } catch (Throwable t) {
            // Log and swallow, never crash the worker
            logger.warn("Streaming parse failed", t);
            parseRunning.set(false);
            updatePending.set(false);
            done.completeExceptionally(t);
        }
    }

    void shutdown() {
        // Complete any waiting futures before shutting down
        inFlight.get().completeExceptionally(new CancellationException("Worker shutdown"));
        exec.shutdownNow();
        fullText.setLength(0);
        fullText.trimToSize(); // Release memory held by the StringBuilder
    }
}
