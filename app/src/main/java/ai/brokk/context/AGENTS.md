# Context Subsystem Design Guidance

## Data Modeling and Identification
- **Prefer Domain Objects over IDs**: Use domain objects (e.g., `Context`) directly as keys in maps or caches rather than adding UUID indirection. This simplifies lookups, improves type safety, and avoids manual management of ID-to-object mappings.
- **Identity via Identity**: When caching relationships between specific object instances (like diffs between two `Context` versions), use `System.identityHashCode` or reference equality (via `Objects.equals`) in key wrappers if the objects are logically "snapshots" but share the same identity for a specific computation.

## Concurrency and Performance
- **Avoid Complex Batching**: Avoid complex, stateful batching patterns (e.g., manual EDT timers/queues for coalescing). Prefer simple executor-based patterns or non-blocking queues when asynchronous processing is required.
- **Async by Default**: Leverage `CompletableFuture` and `ComputedValue` for long-running computations. UI-facing code should peek at caches or use non-blocking callbacks rather than blocking the Event Dispatch Thread (EDT).

## Memory Management
- **Bounded Caches**: Use bounded caches with clear eviction policies (e.g., **Caffeine** with `maximumSize` or `expireAfterAccess`) for long-running processes. 
- **Avoid Unbounded Maps**: Never use unbounded `HashMap` or `ConcurrentHashMap` for data that grows over time (like history or diffs), as this eventually leads to memory leaks.
