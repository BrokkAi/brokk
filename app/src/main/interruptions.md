## Cancellation Patterns in Brokk

Brokk generally adheres to standard interruption and cancellation practices using Java's built-in interruption mechanism.

This means that in general, **`InterruptedException` is Propagated:** Methods that perform blocking operations (like `Coder.doSingleStreamingCallInternal` awaiting a `CountDownLatch`, `Environment.runShellCommand` waiting for a process, or agents calling `Future.get`) generally allow InterruptedException to propagate up to the UI. Occasionally it's necessary to catch and re-throw, e.g. Coder.doSingleStreamingCallInternal does this to supress langchain4j's continued processing of a async requests associated with the canceled operation.

Here's how Brokk wires this up to the Stop button:

* User-initiated tasks (`Code`, `Ask`, `Search`, `Run`, `Agent`, context actions) are submitted to a single-threaded `userActionExecutor` in `ContextManager`.  
* The "Stop" button ( \-\> `ContextManager.interruptUserActionThread()`) now directly calls `interrupt()` on the specific thread currently assigned to the `userActionExecutor`.  
* The running user task (e.g., `CodeAgent.runSession`, `SearchAgent.execute`) must detect this interruption:  
  * By checking `Thread.currentThread().isInterrupted()` periodically in long-running loops.  
  * By handling `InterruptedException` thrown from blocking calls it makes (like calls to `Coder` or `getAnalyzer()`).  
* Interrupted tasks typically clean up and exit, letting the `InterruptedException` (or a `CancellationException` wrapping it) propagate back to the `submitUserTask` wrapper in `ContextManager`, which logs the cancellation appropriately.

### **Agent-Specific Handling**

* **`CodeAgent`:**
    * This is a special pony! The main `runSession` method DOES NOT throw `InterruptedException`. Instead, it supplies a `StopReason` of `INTERRUPTED`.
    * This allows capturing changes to files in the SessionResult for undo/redo.

* **`SearchAgent`:**  
  * Checks `Thread.interrupted()` at the start of its main loop.  
  * If running interactively, the first interruption triggers "Beast Mode" to attempt a final answer. A second interruption (or any interruption in non-interactive mode) causes it to throw `InterruptedException`.  
  * Calls to `determineNextActions` and `executeToolCalls` now propagate `InterruptedException`. The main loop catches this, re-interrupts, and relies on the check at the top of the next iteration.  
* **`ArchitectAgent`:**  
  * Catches `InterruptedException` when waiting for `SearchAgent` results (`future.get()`). It cancels all outstanding `Future`s and sets a flag to terminate gracefully.  

### **Background Tasks**

* Tasks submitted via `ContextManager.submitBackgroundTask` (e.g., analyzer rebuilds, style guide generation, test suggestions) run on a separate `backgroundTasks` executor.  These tasks are generally **not** intended to be cancelled by the user's "Stop" button.  
* Methods called by these tasks often use `getAnalyzerUninterrupted()` as they don't expect or typically handle user-driven interruption signals.  
* The `backgroundTasks` executor service is configured to ignore `InterruptedException` in its uncaught exception handler, supressing unnecessary UI spam if a background task *is* somehow interrupted.

### **Handling `InterruptedException` in asynchronous tasks**

* Tasks running asynchronously (e.g. within `CompletableFuture.supplyAsync`, like summarization in `SearchAgent`) often catch `InterruptedException` and wrap it in a `RuntimeException`. This is because there is no mechanism wired up to actually interrupt these threads, so it's best to stop the checked exception declarations from spreading throughout the codebase.

### **Key Takeaways**

1. **Embrace Interruption:** Use `Thread.currentThread().isInterrupted()` in loops and between long operations within cancellable tasks (primarily the Agents running on the `userActionExecutor`).  
2. **Handle `InterruptedException` Correctly:** When catching `InterruptedException` from blocking calls:  
   * **Re-interrupt:** Call `Thread.currentThread().interrupt()` if you cannot fully handle the interruption locally and need to signal callers.  
   * **Propagate:** Declare `throws InterruptedException` if your method is part of a larger interruptible operation.  
   * **Avoid Swallowing:** Do not catch `InterruptedException` and simply ignore it or log it without re-interrupting/propagating.  
3. **Cancellation Target:** User cancellations target the `userActionExecutor` thread via `ContextManager.interruptUserActionThread()`.  
4. **Executor Awareness:** Be mindful of which executor your code is running on (`userActionExecutor`, `contextActionExecutor`, `backgroundTasks`, or EDT) and handle interruption accordingly. Background tasks are not interrupted by user actions.  
5. **Blocking Calls:** Calls like `IAnalyzer.getAnalyzer()` can block and throw `InterruptedException`. You can use `getAnalyzerUninterrupted()` when you are certain the calling thread should not be interrupted or when a generic interruption handler is preinstalled (e.g., in background tasks).
