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

### 4. Analyzer Snapshot Architecture
1. **Immutability**: Analyzers are **immutable snapshots** of the project state at a point in time.
1. **Update behavior**: Calling `analyzer.update()` returns a **new** analyzer instance; it does not mutate the existing one. Instance fields on an analyzer represent the state of that specific snapshot and should not be modified.
1. **Persistent State**: If state needs to persist across `update()` calls (e.g., caches, indexes), it must be stored in the `AnalyzerState` record.
1. **PMap usage**: `AnalyzerState` uses `PMap` (persistent/immutable maps from `pcollections`) to enable efficient structural sharing and avoid unnecessary garbage collection.
1. **Lazy caches**: Lazy caches (like `LazyTypeHierarchyCache`, `LazyImportCache`) are instance-level and reset on `update()`; their computed values are merged into `AnalyzerState` via `snapshotState()` for serialization.

### 5. Expanding Analyzer Capabilities
1. **Default Implementations**: When adding a new `IAnalyzer` API or capability, add it with a default implementation so that the project compiles.
1. **Incremental Implementation**: Plan tasks to handle each subclass one at a time. Bringing all subclasses into the context at once will fill up the context and result in either exceeding model context or general context confusion.

### 6. Tree-sitter Query Architecture
1. **Multi-Query Structure**: Monolithic `.scm` files (e.g., `treesitter/java.scm`) are being deprecated in favor of a directory-based multi-query structure:
   - `resources/treesitter/<language>/definitions.scm`
   - `resources/treesitter/<language>/imports.scm`
1. **QueryType Enum**:
   - `DEFINITIONS`: Primary query for capturing classes, functions, and fields. This is **compulsory** for every analyzer.
   - `IMPORTS`: Specific query for capturing import statements and related symbols. This is **optional**; if not provided, the analyzer will skip import-specific AST passes.
1. **Resource Loading**: The `TreeSitterAnalyzer` base class handles the discovery and loading of these queries based on the language name and `QueryType`. Analyzers should transition away from the single `getQueryResource()` string toward this structured multi-query approach.
