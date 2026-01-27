## Tree-sitter Analyzer Guidelines

### 1. Prefer Tree-sitter Over Regex
1. **Tree-sitter queries**: Use Tree-sitter queries (`.scm` files) for parsing structured code; they handle whitespace, comments, and edge cases that regex cannot.
1. **Regex usage**: When regex IS the better solution (e.g., simple string patterns, non-AST text processing), always compile patterns as `private static final Pattern` fields — never inline `Pattern.compile()` calls.
   - Example:
     ```java
     private static final Pattern WILDCARD_IMPORT_PATTERN = Pattern.compile("^from\\s+(.+?)\\s+import\\s+\\*");
     ```

### 2. Tree-sitter Query Predicate Limitations
1. **No predicates**: Our Tree-sitter Java binding does NOT support query predicates such as `#eq?`, `#any-of?`, `#match?`, or `#is?`.
1. **Java-side filtering**: If you need to filter nodes by exact literal values (e.g., match only nodes where the text equals a specific string), perform this filtering in Java code after executing the query.
   - Example: Instead of `(identifier) @id (#eq? @id "foo")` in the query, use `(identifier) @id` and filter in Java:
     ```java
     if (sourceContent.substringFrom(node).equals("foo")) { ... }
     ```

### 3. Working with SourceContent
1. **Creation**: Use `SourceContent.of(String source)` to create a content wrapper that handles UTF-8 byte offset conversions.
1. **Extraction**: Extract text from AST nodes using `sourceContent.substringFrom(TSNode node)` — this correctly handles multi-byte Unicode characters.
1. **Avoid manual calculations**: Avoid manual byte-to-char offset calculations; the `SourceContent` helper methods (`substringFromBytes`, `byteOffsetToCharPosition`, `charPositionToByteOffset`) handle edge cases.

### 4. Writing Analyzer Tests
1. **No reflection**: Do not use reflection in tests to access analyzer internals or invoke methods. If a method needs to be tested but is not accessible, ask to relax its visibility in the source file instead of using reflection to work around it.
2. **Disable failing tests, do not remove them**: If a test does not pass due to a known limitation or pending implementation, annotate it with `@Disabled("reason")` rather than deleting it. This preserves the test as documentation of expected behavior and ensures it can be re-enabled once the underlying issue is fixed.

### 5. Analyzer Snapshot Architecture
1. **Immutability**: Analyzers are **immutable snapshots** of the project state at a point in time.
1. **Update behavior**: Calling `analyzer.update()` returns a **new** analyzer instance; it does not mutate the existing one. Instance fields on an analyzer represent the state of that specific snapshot and should not be modified.
1. **Persistent State**: If state needs to persist across `update()` calls (e.g., caches, indexes), it must be stored in the `AnalyzerState` record.
1. **PMap usage**: `AnalyzerState` uses `PMap` (persistent/immutable maps from `pcollections`) to enable efficient structural sharing and avoid unnecessary garbage collection.
1. **Lazy caches**: Lazy caches (like `LazyTypeHierarchyCache`, `LazyImportCache`) are instance-level and reset on `update()`; their computed values are merged into `AnalyzerState` via `snapshotState()` for serialization.
