## Tree-sitter Analyzer Guidelines

### 1. Prefer Tree-sitter Over Regex
1. **Tree-sitter queries**: Use Tree-sitter queries (`.scm` files) for parsing structured code; they handle whitespace, comments, and edge cases that regex cannot.
1. **Regex usage**: When regex IS the better solution (e.g., simple string patterns, non-AST text processing), always compile patterns as `private static final Pattern` fields - never inline `Pattern.compile()` calls.
   - Example:
     ```java
     private static final Pattern WILDCARD_IMPORT_PATTERN = Pattern.compile("^from\\s+(.+?)\\s+import\\s+\\*");
     ```
1. **CST and TSNode over string splicing**: Prefer traversing the concrete syntax tree with `TSNode` (child relationships, `getChildByFieldName`, `getNamedChild`, `getPrevSibling` / `getNextSibling` when appropriate, and helpers like `collectNodesByType`) instead of parsing or classifying code by stitching, normalizing, or scanning raw source text. Reserve `SourceContent.substringFrom(TSNode)` for **leaf** or token-shaped nodes (identifiers, literals, small spans) where the grammar does not expose a finer structure, not as a substitute for structural analysis of expressions or statements.

### 2. Tree-sitter Query Predicates
1. **Predicates supported**: Our Tree-sitter Java binding supports query predicates such as `#eq?`, `#any-of?`, `#match?`, and `#is?`.

### 3. Working with SourceContent
1. **Creation**: Use `SourceContent.of(String source)` to create a content wrapper that handles UTF-8 byte offset conversions.
1. **Extraction**: Extract text from AST nodes using `sourceContent.substringFrom(TSNode node)` - this correctly handles multi-byte Unicode characters.
1. **Avoid manual calculations**: Avoid manual byte-to-char offset calculations; the `SourceContent` helper methods (`substringFromBytes`, `byteOffsetToCharPosition`, `charPositionToByteOffset`) handle edge cases.

### 4. Analyzer Snapshot Architecture
1. **Immutability**: Analyzers are immutable snapshots of the project state at a point in time.
1. **Update behavior**: Calling `analyzer.update()` returns a new analyzer instance; it does not mutate the existing one. Instance fields on an analyzer represent the state of that specific snapshot and should not be modified.
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
   - `DEFINITIONS`: Primary query for capturing classes, functions, and fields. This is compulsory for every analyzer.
   - `IMPORTS`: Specific query for capturing import statements and related symbols. This is optional; if not provided, the analyzer will skip import-specific AST passes.
   - `IDENTIFIERS`: Specific query for capturing type identifiers and references. This is optional; used for advanced symbol resolution.
1. **Resource Loading**: The `TreeSitterAnalyzer` base class handles the discovery and loading of these queries based on the language name and `QueryType`. Analyzers should transition away from the single `getQueryResource()` string toward this structured multi-query approach.

### Tree-sitter Generated Constants (Preferred)

The `tree-sitter-ng` Java bindings now generate strongly-typed per-language constants for:
- node types: `org.treesitter.<Language>NodeType` (e.g., `JavaNodeType`)
- field names: `org.treesitter.<Language>NodeField` (e.g., `JavaNodeField`)
- schema helpers: `org.treesitter.<Language>NodeSchema` (e.g., `JavaNodeSchema`)

Prefer these generated constants over hard-coded strings and over our hand-maintained `*TreeSitterNodeTypes` files when they exist for the language you are working on.

1. **Node type comparisons**: Use `nodeType(<Language>NodeType.X)` (or `X.getType()` directly) instead of string literals like `"method_declaration"`.
2. **Field name access**: Use `nodeField(<Language>NodeField.X)` (or `X.getName()` directly) instead of string literals like `"name"`, `"body"`, etc.
3. **Schema helpers**: Use `<Language>NodeSchema` when you need to reason about which fields/children exist on which node types, or to avoid "stringly-typed" field navigation. Prefer it over ad-hoc assumptions.

**Static import guidance**
- It is OK to `import static org.treesitter.<Language>NodeType.*;` to avoid `NodeType.X` noise.
- Avoid static-importing BOTH `NodeType.*` and `NodeField.*` in the same file because many names collide (`TYPE`, `MODIFIERS`, `TYPE_PARAMETERS`, `PATTERN`, etc.). Prefer:
  - static import `NodeType.*`, and
  - refer to fields as `JavaNodeField.NAME` (or `nodeField(JavaNodeField.NAME)`), or keep fields qualified.

**Example (Java)**
```java
import static ai.brokk.analyzer.java.Constants.nodeField;
import static ai.brokk.analyzer.java.Constants.nodeType;
import static org.treesitter.JavaNodeType.*;

import org.treesitter.JavaNodeField;

if (nodeType(METHOD_DECLARATION).equals(node.getType())) {
    // handle field declaration
}

TSNode nameNode = methodNode.getChildByFieldName(nodeField(JavaNodeField.NAME));
```

**Migration guidance**
1. When editing analyzers, replace string node type checks (`"foo_bar".equals(node.getType())`) with the generated `*NodeType` / `*NodeField` constants where available.
2. If the language does not have generated schema/constants yet, use the existing `*TreeSitterNodeTypes` / `CommonTreeSitterNodeTypes` constants as a fallback (still avoid raw strings).
3. Query capture names (the names in `.scm` files like `import.declaration` or `test_marker`) are not node types. Keep those as explicit string constants (e.g., `IMPORT_DECLARATION_CAPTURE`), do not try to model them as `NodeType`s.

