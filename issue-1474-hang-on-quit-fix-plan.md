# Fix Plan: Issue #1474 - Quit Exits Slowly or Not at All

## Problem Analysis

The application is hanging on quit because non-daemon threads are preventing the JVM from exiting. Investigation of the codebase has identified **one critical non-daemon thread**:

### The Culprit: SessionManager (lines 58-64)

**Location:** `SessionManager.java:58-64`

```java
this.sessionExecutor = Executors.newFixedThreadPool(3, r -> {
    var t = Executors.defaultThreadFactory().newThread(r);
    t.setDaemon(false);  // ❌ NON-DAEMON THREAD
    t.setName("session-io-" + t.threadId());
    return t;
});
```

This creates **3 non-daemon threads** for session I/O operations. When the application quits, these threads remain alive even if there's no pending work, preventing the JVM from shutting down.

### Related Components

Several other components create threads that are already properly configured as daemon threads:

1. **ExecutorServiceUtil** ✅ Already sets `setDaemon(true)` (line 27)
2. **ClasspathHttpServer** ✅ Already sets `setDaemon(true)` (line 65)
3. **ProjectWatchService** ⚠️ Creates a non-daemon thread (line 45-48), BUT properly terminates via `close()` method
4. **GitHubDeviceFlowService** ✅ Uses a ScheduledExecutorService passed in from outside

### Why This Happens

The shutdown sequence in `Brokk.exit()` (lines 1097-1119):
1. Calls `closeAsync(1_000)` on each Chrome window's ContextManager
2. Waits for all futures to complete
3. Calls `System.exit(0)`

However, the `ContextManager.closeAsync()` method (lines 1320-1338):
- Closes various executors (userActionExecutor, contextActionExecutor, backgroundTasks, etc.)
- BUT does **not** close the project's `SessionManager`
- The `SessionManager.close()` method exists (lines 653-665) and properly shuts down the executor, but it's never called during the quit sequence

## Root Cause

**The SessionManager's non-daemon thread pool is never explicitly shut down during application quit**, leaving 3 non-daemon threads running indefinitely and preventing JVM exit.

## Solution

### Option 1: Make SessionManager Threads Daemon (RECOMMENDED)

**Change:** `SessionManager.java:61`

```java
// Before:
t.setDaemon(false);

// After:
t.setDaemon(true);
```

**Rationale:**
- Session I/O operations are background tasks that should not prevent application shutdown
- The shutdown timeout mechanism (`awaitTermination(30, TimeUnit.SECONDS)`) in `SessionManager.close()` already protects against data loss
- If the application is quitting, there's no need to block on pending session writes
- Daemon threads will be automatically terminated when all non-daemon threads complete

**Risk:** Low. Session writes are fire-and-forget operations; if the app quits before a write completes, the next session load will handle it gracefully.

### Option 2: Explicitly Close SessionManager During Shutdown

**Changes Required:**

1. **In `ContextManager.closeAsync()`** (after line 1333):
   ```java
   // Close session manager to shut down its thread pool
   if (project instanceof MainProject mp) {
       mp.getSessionManager().close();
   }
   ```

2. **Ensure `AbstractProject.close()` includes:**
   ```java
   @Override
   public void close() {
       if (sessionManager != null) {
           sessionManager.close();
       }
       // ... other cleanup
   }
   ```

**Rationale:**
- Provides explicit control over shutdown sequence
- Ensures all pending session I/O completes before shutdown
- Follows best practices for resource management

**Risk:** Medium. Requires careful coordination of shutdown sequence to avoid race conditions.

### Option 3: Hybrid Approach (MOST ROBUST)

Combine both approaches:
1. Make threads daemon (so JVM can exit even if something goes wrong)
2. Explicitly close SessionManager during shutdown (to properly complete pending I/O)

**Risk:** Lowest. Provides both fail-safe behavior and proper cleanup.

## Recommended Implementation: Option 1

**Make the simple fix first** - change line 61 in `SessionManager.java` from `t.setDaemon(false)` to `t.setDaemon(true)`.

**Justification:**
1. **Simplicity:** One-line change, minimal risk
2. **Correctness:** Session I/O operations are background tasks that should not block shutdown
3. **Consistency:** Aligns with other thread pools in the codebase (ExecutorServiceUtil, ClasspathHttpServer)
4. **Safety net exists:** The `SessionManager.close()` method with 30-second timeout already handles graceful shutdown if explicitly called
5. **Low impact:** If a session write is interrupted during shutdown, the next load will work correctly due to the session management design

## Reproduction Steps

Based on analysis, the SessionManager thread pool is created **immediately when a project is opened** (MainProject.java:172), so the threads exist from the start. However, the hang may only be noticeable under certain conditions:

**To reproduce the hanging quit behavior:**

1. Open the application with a project
2. **Trigger session I/O activity** by:
   - Creating or switching sessions (triggers `newSession()` or `loadHistory()`)
   - Making changes that trigger context saves (triggers `saveHistory()`)
   - Renaming a session (triggers `renameSession()`)
   - Working with task lists (triggers `writeTaskList()` or `readTaskList()`)
3. Quit the application immediately (Cmd+Q or File → Quit)

**Expected without fix:** Application may hang for 30+ seconds (or indefinitely if threads are waiting)

**Expected with fix:** Application exits within 1-2 seconds

**Note:** If you open the app and immediately quit without triggering any session operations, the thread pool threads may be idle and the quit might appear fast even without the fix. The issue manifests when:
- Session I/O tasks are queued or executing
- Threads are waiting on I/O operations
- The ExecutorService has active keep-alive threads

## Testing Plan

1. **Baseline test (may not show the issue):**
   - Open the application
   - Open a project
   - **Immediately quit** without doing anything
   - **Current behavior:** Probably quits quickly even without fix (threads are idle)

2. **Active session I/O test (should show the issue):**
   - Open the application and project
   - Create a new session or switch sessions
   - Add some context or make changes
   - Rename a session (triggers async write)
   - **Immediately quit** while the I/O is happening
   - **Without fix:** Hang for several seconds or more
   - **With fix:** Exits promptly (daemon threads terminate immediately)

3. **Heavy I/O stress test:**
   - Perform rapid operations: create sessions, rename them, switch between them
   - Trigger multiple context saves by making changes
   - Quit during this heavy activity
   - **Expected with fix:** Application still exits promptly

4. **Verify session integrity after fix:**
   - Make changes in a session
   - Quit immediately after the change (while I/O may be pending)
   - Reopen the application and project
   - **Expected:** Session loads correctly (last change may or may not be saved depending on timing, but no corruption)

## Additional Investigation Needed

If the fix doesn't completely resolve the issue, investigate:

1. **JavaFX Platform threads:**
   - `Brokk.java:169-178` initializes JavaFX with `Platform.setImplicitExit(false)`
   - This prevents JavaFX from exiting automatically, which might contribute to hang
   - Consider calling `Platform.exit()` during `Brokk.exit()`

2. **ProjectWatchService thread:**
   - Creates a non-daemon thread at line 45-48
   - Verify `watchService.close()` is always called during ContextManager shutdown
   - Consider making this daemon as well for safety

3. **Other thread dumps:**
   - Use `jstack` or VisualVM during a hang to capture thread states
   - Look for any threads in RUNNABLE or WAITING state that are not daemon

## Implementation Steps

1. Change `SessionManager.java:61` from `setDaemon(false)` to `setDaemon(true)`
2. Test the quit behavior thoroughly
3. If issue persists, investigate JavaFX platform threads
4. If still problematic, consider the hybrid approach (Option 3)

## Alternative: Bisecting Approach (if problem persists)

As suggested in the original issue, use git bisect to identify when the slow quit behavior was introduced:

```bash
git bisect start
git bisect bad HEAD
git bisect good <known-good-commit>
# Test each commit for quit speed
git bisect good/bad (based on test results)
```

This would help identify exactly which commit introduced the non-daemon thread or other blocking behavior.