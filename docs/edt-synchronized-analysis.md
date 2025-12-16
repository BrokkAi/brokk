# EDT Synchronized Methods Analysis Tool

## Overview

This document describes the EDT (Event Dispatch Thread) Synchronized Methods Analysis tool - a static analysis system designed to detect potential UI freezes and deadlocks caused by calling synchronized methods from the Swing EDT.

## The Problem

In Swing applications, calling synchronized methods from the EDT can cause:

1. **UI Freezes** - If the synchronized method performs blocking operations (I/O, waiting, etc.)
2. **Deadlocks** - If another thread holds the lock and tries to update UI
3. **Poor Responsiveness** - If the synchronized block takes significant time to execute

This is particularly problematic because:
- The EDT is responsible for all UI updates and event handling
- Any blocking on the EDT makes the entire UI unresponsive
- Users perceive even brief freezes as application hangs

## Tool Components

The analysis system consists of three components:

### 1. SynchronizedCallGraphAnalyzer

**Location:** `errorprone-checks/src/main/java/ai/brokk/errorprone/SynchronizedCallGraphAnalyzer.java`

**Purpose:** Analyzes compiled bytecode using ASM to build a complete call graph and identify all methods that transitively call synchronized methods.

**How it works:**
- Scans `.class` files and JAR files
- Detects directly synchronized methods (via `ACC_SYNCHRONIZED` flag)
- Detects synchronized blocks (via `MONITORENTER` bytecode)
- Builds a call graph mapping each method to the methods it calls
- Computes transitive closure to find all methods that indirectly call synchronized

**Output:** `build/edt-analysis/synchronized-methods.txt` - A list of all methods (with any depth) that call synchronized methods

### 2. EdtSynchronizedReportGenerator

**Location:** `errorprone-checks/src/main/java/ai/brokk/errorprone/EdtSynchronizedReportGenerator.java`

**Purpose:** Combines synchronized call graph analysis with EDT context detection to identify actual violations.

**EDT Context Detection:**
- Methods inside `SwingUtilities.invokeLater()` or `EventQueue.invokeLater()`
- Classes implementing Swing listener interfaces (ActionListener, MouseListener, etc.)
- Lambdas passed to event listener registration methods

**Output:** `build/edt-analysis/edt-synchronized-violations.txt` - A report of EDT methods that call synchronized

### 3. SynchronizedOnEdtChecker

**Location:** `errorprone-checks/src/main/java/ai/brokk/errorprone/SynchronizedOnEdtChecker.java`

**Purpose:** Error Prone compiler plugin for build-time detection.

**Features:**
- Detects direct synchronized method calls from EDT contexts
- Can load pre-computed transitive analysis for unlimited depth detection
- Provides compiler warnings during development

## Usage

### Running the Analysis

To generate a complete analysis report:

```bash
# Ensure app is compiled first
./gradlew :app:compileJava

# Run the analysis (this is opt-in, doesn't break builds)
./gradlew :errorprone-checks:generateEdtReport
```

This will:
1. Analyze all compiled classes to build the synchronized call graph
2. Identify EDT contexts in the code
3. Generate reports in `build/edt-analysis/`:
   - `synchronized-methods.txt` - All methods calling synchronized (transitively)
   - `edt-synchronized-violations.txt` - EDT methods calling synchronized

### Running Individual Tasks

```bash
# Just build the call graph (step 1)
./gradlew :errorprone-checks:analyzeSynchronizedCalls

# Generate EDT report from existing call graph (step 2)
./gradlew :errorprone-checks:generateEdtReport
```

### Interpreting Results

#### synchronized-methods.txt Format

```
# Methods that call synchronized methods (directly or indirectly)
# Format: fully.qualified.ClassName#methodName
# Generated: [timestamp]

fully.qualified.ClassName#methodName
another.package.Class#anotherMethod [DIRECT]
```

- Methods marked `[DIRECT]` are themselves synchronized or contain synchronized blocks
- Methods without `[DIRECT]` call synchronized methods indirectly

#### edt-synchronized-violations.txt Format

```
# EDT methods that call synchronized methods
# These methods may cause UI freezes or deadlocks
# Generated: [timestamp]
# Found X violations

ai.brokk.gui.SomePanel#buttonClicked // inside invokeLater -> calls some.pkg.Service#synchronizedMethod
ai.brokk.gui.Handler#actionPerformed // in EDT listener class -> calls another.Service#indirectlySynchronized
```

Each violation shows:
- The EDT method that's problematic
- Why it's considered EDT context (inside invokeLater, listener class, etc.)
- What synchronized method it calls

## Example Violation

From the actual analysis that found Issue #2126:

```
ai.brokk.gui.terminal.TaskListPanel#syncToContext // inside invokeLater -> calls ai.brokk.ContextManager#setTaskList
```

**Call Chain:**
```
EDT: TaskListPanel.syncToContext()
  ↓
  ContextManager.setTaskList()
  ↓
  ContextManager.pushContext()
  ↓
  ContextHistory.push() ← SYNCHRONIZED
  ↓
  ContextHistory.pushContextInternal() ← SYNCHRONIZED
  ↓
  snapshotContext() ← BLOCKS WITH cf.await()
```

**Problem:** EDT holds the ContextHistory lock while waiting, causing UI freeze.

## Fixing Violations

### Strategy 1: Move to Background Thread (Recommended)

Move the synchronized operation off the EDT:

```java
private void syncToContext(String action) {
    // Collect data on EDT (fast, UI interaction)
    var dtos = Collections.list(model.elements()).stream()
            .filter(it -> !it.text().isBlank())
            .toList();
    var data = new TaskList.TaskListData(dtos);

    // Move synchronized operation to background
    CompletableFuture.runAsync(() -> {
        cm.setTaskList(data, action);
    });
}
```

### Strategy 2: Remove Blocking from Synchronized Block

Reduce what's done while holding the lock:

```java
private void pushContextInternal(Context ctx, boolean snapshotNow) {
    // Only hold lock for critical section
    synchronized(this) {
        history.addLast(ctx);
        truncateHistory();
        redo.clear();
        selected = ctx;
    }

    // Snapshot outside synchronized block (no lock held during blocking)
    if (snapshotNow) {
        snapshotContext(ctx);
    }
}
```

### Strategy 3: Use Lock-Free Data Structures

Replace synchronized methods with concurrent collections:

```java
// Instead of synchronized ArrayList
private final List<Context> history = Collections.synchronizedList(new ArrayList<>());

// Use concurrent collections
private final CopyOnWriteArrayList<Context> history = new CopyOnWriteArrayList<>();
```

### Strategy 4: Use ReadWriteLock

For read-heavy, write-light scenarios:

```java
private final ReadWriteLock lock = new ReentrantReadWriteLock();

public Context getContext() {
    lock.readLock().lock();
    try {
        return selected;
    } finally {
        lock.readLock().unlock();
    }
}

public void pushContext(Context ctx) {
    lock.writeLock().lock();
    try {
        history.add(ctx);
    } finally {
        lock.writeLock().unlock();
    }
}
```

## Implementation Notes

### Why Bytecode Analysis?

We use ASM bytecode analysis instead of source-based analysis because:

1. **Unlimited Depth** - Can trace through any number of method call levels
2. **Handles Dependencies** - Analyzes third-party libraries without source
3. **Performance** - Faster than recompiling with full Error Prone analysis
4. **Accuracy** - Sees actual compiled code, not just source

### Why Separate Tool?

The analysis is opt-in (not automatic during builds) because:

1. **Performance** - Full call graph analysis is expensive
2. **False Positives** - Not all synchronized calls from EDT are bugs
3. **Gradual Fixing** - Allow fixing violations incrementally without breaking CI
4. **Flexibility** - Can run analysis on-demand when investigating issues

### Error Prone Integration

The `SynchronizedOnEdtChecker` can optionally load pre-computed analysis results to provide warnings during compilation. However, it's not enabled by default to avoid breaking builds.

To enable during development, run the analysis first, then compile:

```bash
./gradlew :errorprone-checks:generateEdtReport
./gradlew :app:compileJavaErrorProne
```

## Maintenance

### Adding EDT Context Patterns

To detect new EDT contexts, update `EdtSynchronizedReportGenerator.java`:

```java
private static final Set<String> EDT_LISTENER_INTERFACES = Set.of(
    "java.awt.event.ActionListener",
    "java.awt.event.MouseListener",
    // Add new listener interfaces here
    "javax.swing.event.YourNewListener"
);
```

### Excluding False Positives

If the analysis reports false positives (synchronized calls that are actually safe), you can:

1. Document why it's safe in code comments
2. Filter specific methods in the report generator
3. Refactor to make the safety more obvious to the analyzer

## Performance

Typical analysis times on Brokk codebase:

- **Call Graph Analysis** - ~5-10 seconds
- **EDT Report Generation** - ~2-3 seconds
- **Total** - Under 15 seconds

Memory usage is proportional to codebase size. The call graph for Brokk (~1,668 methods) uses approximately 50-100MB RAM.

## Troubleshooting

### "No classes found to analyze"

Ensure the app is compiled first:
```bash
./gradlew :app:compileJava
```

### "ClassNotFoundException" during analysis

The analyzer needs ASM on the classpath. This is handled automatically by Gradle tasks.

### Error Prone checker not finding violations

The Error Prone integration requires pre-computed results in a specific location. Run `generateEdtReport` first, or rely on the standalone report.

## References

- [Swing Threading](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/index.html) - Official Oracle guide
- [ASM Library](https://asm.ow2.io/) - Bytecode manipulation framework
- [Error Prone](https://errorprone.info/) - Static analysis framework
- [Issue #2126](https://github.com/BrokkAi/brokk/issues/2126) - The bug that motivated this tool

## Future Enhancements

Potential improvements:

1. **Blocking Operation Detection** - Detect I/O, network, and other blocking calls
2. **Lock Hierarchy Analysis** - Detect potential lock ordering deadlocks
3. **Performance Annotations** - Allow marking methods as @EdtSafe or @MayBlock
4. **IDE Integration** - Real-time warnings in IntelliJ/Eclipse
5. **Automated Refactoring** - Suggest specific fixes for each violation
