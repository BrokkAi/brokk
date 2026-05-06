# Rust Export Usage Analysis Plan

The Python receiver/type plan that previously lived here is implemented. This file now records the current
JS/TS and Python usage-analysis model, the differences that matter for Rust, and the test parity target for a Rust
implementation.

## What Exists Today

The shared usage path is `ExportUsageReferenceGraphEngine` plus one language adapter per analyzer. The engine is
language-neutral: it starts from a defining file and exported name, resolves that export to one or more `CodeUnit`
targets, walks import/re-export frontiers, collects `ReferenceCandidate` and `ResolvedReceiverCandidate` facts from the
language adapter, then matches candidates against the query target. `ReferenceCandidate`, `ReferenceHit`,
`ExportIndex`, `ImportBinder`, `ExportResolutionData`, and `LocalUsageInference` are the reusable contract.

JS/TS already uses this path through `JsTsExportUsageGraphStrategy` and `JsTsExportUsageGraphAdapter`. It has the
widest coverage:

- named, aliased, namespace, and default imports;
- direct exports, aliased exports, default exports, re-export chains, and local barrel re-exports;
- `tsconfig` baseUrl/path alias resolution and extended `tsconfig` inheritance;
- import shadowing and duplicate owner names across files;
- class, member, static member, constructed receiver, typed receiver, and alias-chain receiver matching;
- inheritance-aware class/member matching through adapter-provided heritage edges;
- graph-result and export-resolution caching;
- strategy routing from `UsageAnalyzerSelector`, including graph miss/fallback behavior.

Python now uses the same engine through `PythonExportUsageGraphStrategy` and `PythonExportUsageGraphAdapter`. It borrows
the JS/TS graph but adapts the language surface:

- absolute imports, relative imports, and package-barrel resolution through `__init__.py`;
- `__all__`-backed and nested package re-exports;
- import cycles, local import shadowing, imported submodule qualifiers, and namespace-qualified imports;
- explicit receiver facts from annotations, constructor-created locals, alias propagation, and `self.x: Foo` attributes;
- Python heritage edges and ancestor lookup for inherited members and overrides;
- guardrails for ambiguous receiver sets, object/dict literal lookalikes, scope leakage, and unrelated same-name
  members;
- selector integration with both `PythonAnalyzer` and `MultiAnalyzer`.

The important lesson is that neither implementation relies on grep once a structured graph seed exists. Each language
does its own syntax extraction, but then hands normalized import/export/reference/receiver facts to the shared engine.

## Rust Is Different

Rust should use the same engine, but it should not pretend that Rust modules are ESM modules or Python packages.
The adapter boundary should translate Rust concepts into the existing IR:

- Exports are visibility-filtered items: `pub struct`, `pub enum`, `pub trait`, `pub fn`, `pub const`, `pub static`,
  `pub type`, public enum variants, and public associated items when the owning type/trait is exported. Private items can
  still be searched by regex fallback, but they should not seed the exported-symbol graph.
- Modules are path-based, not file-extension specifiers. `crate::`, `self::`, `super::`, nested `mod` blocks,
  file modules (`foo.rs`), directory modules (`foo/mod.rs`), and crate roots (`lib.rs`, `main.rs`) need explicit module
  path resolution.
- `use` declarations combine import and re-export semantics. `use crate::x::Y;` is an import, `pub use crate::x::Y;`
  is a re-export, grouped imports flatten into several bindings, `as` creates local aliases, `self` imports the module
  itself, and glob imports are high risk unless the exported names of the target module are known.
- There is no default export. The closest special cases are module-level re-exports, `pub use foo::Bar as Baz`, enum
  variants, trait methods, and associated functions/constants.
- Receiver analysis is more explicit than Python but different from JS/TS. Useful high-confidence facts come from
  `let x: Foo`, `let x = Foo::new()`, `let x = Foo { ... }`, tuple/unit struct constructors, `let y = x`, and method
  calls like `x.bar()`. Associated calls such as `Foo::bar()` or `Trait::method(&x)` should be modeled separately from
  instance receivers.
- Trait semantics are the main new wrinkle. An inherent impl method belongs to the concrete type. A trait signature
  belongs to the trait. A trait impl method can be a usage of the trait member, but a call `x.bar()` can only be resolved
  to a trait member when there is enough provenance to connect `x` to a concrete type and that type to an impl of the
  trait. V1 should prefer concrete/inherent methods and explicit trait paths over broad trait fan-out.
- Generics and type aliases should be conservative. Strip generic arguments for owner keys like `Vec<T>` -> `Vec` when
  the base type is resolvable, but also emit reference candidates for type arguments in type positions, such as
  `Vec<Foo>` counting as a usage of `Foo`. Follow simple `type Alias = Foo` when the analyzer already marks aliases, but
  do not try to solve bounds, where clauses, or inference across function calls in v1.
- Macros should not be expanded. Macro invocations can be reference candidates when the macro itself is imported or
  exported, but code generated by macros is out of scope for the graph.

## Implementation Shape

Add Rust support by mirroring the JS/TS and Python adapter pattern:

- Add a `RustExportUsageGraphStrategy` selected for Rust `CodeUnit` targets only when the target's defining file has an
  export seed. Keep regex fallback for unseeded/private/local targets.
- Add a `RustExportUsageGraphAdapter` that implements `ExportUsageGraphLanguageAdapter` and delegates to structured
  methods on `RustAnalyzer`.
- Add Rust extraction methods on `RustAnalyzer` or helper classes in a Rust-specific package, but return only shared IR
  types to the usages package:
  - `exportIndexOf(ProjectFile)`;
  - `importBinderOf(ProjectFile)`;
  - `exportUsageCandidatesOf(ProjectFile, ImportBinder)`;
  - `resolvedReceiverCandidatesOf(ProjectFile, ImportBinder)`;
  - `resolveRustModuleOutcome(ProjectFile, String)`;
  - `reverseReexportIndex()`;
  - `heritageIndex()` for trait/inheritance-like owner relationships where they are proven.
- Prefer Tree-sitter traversal and generated Rust node constants/fields over text parsing. Existing Rust import tests
  show that grouped, aliased, wildcard, and `self` imports already have behavior worth preserving; use those as the
  starting point rather than inventing a separate parser.
- Keep module resolution and export/import caches in `AnalyzerCache` or analyzer-owned cache hooks, matching the JS/TS
  and Python export-resolution cache pattern.

## Rust Test Parity Target

Write tests in `brokk-shared/src/test/java/ai/brokk/analyzer/usages`, using the JS/TS and Python graph tests as the
template. The Rust suite should prove the same categories, not necessarily the same syntax.

Export/import graph tests:

- `use crate::service::Service; Service::new()` resolves to `pub struct Service`;
- `use crate::service::Service as S; S::new()` resolves through the alias;
- grouped imports such as `use crate::service::{Service, Helper};` flatten into discrete bindings;
- `use crate::service::{self, Service}; service::factory()` resolves namespace/module-qualified references;
- `pub use crate::service::Service;` re-export chains are followed through one or more modules;
- `pub use crate::service::Service as PublicService;` preserves the exported alias;
- `foo.rs` and `foo/mod.rs` module layouts both resolve;
- `crate::`, `self::`, and `super::` paths resolve from nested files;
- type arguments in type positions count as usages of the argument type, such as `Vec<Foo>`, `Option<Foo>`,
  `HashMap<String, Foo>`, and nested forms like `Result<Vec<Foo>, Error>`;
- external crates and unresolved paths are recorded as external frontier, not false local hits;
- local definitions shadow imported names and do not count as import usages.

Member and receiver tests:

- `let x: Foo; x.bar();` resolves a method target on `Foo`;
- `let x = Foo::new(); x.bar();` and `let x = Foo { ... }; x.bar();` seed constructor-created receivers;
- `let y = x; y.bar();` reuses `LocalUsageInference` alias propagation and confidence degradation;
- `Foo::bar()` resolves associated/static methods without requiring receiver inference;
- duplicate `Foo::bar` methods in different modules do not cross-match;
- `impl Foo { pub fn bar(&self) {} }` is distinct from `impl Other { pub fn bar(&self) {} }`;
- trait method signatures, trait impl methods, and concrete inherent methods have explicit tests for what is counted in
  v1 and what is intentionally not counted;
- enum variants and associated constants are covered as field-like exported targets;
- type alias receiver seeds work only for simple resolvable aliases.

Guardrail tests:

- private Rust items do not seed the graph strategy;
- glob imports do not fan out unless the target module's exports are known and bounded;
- generic bounds, return types, closures, dynamic dispatch through `dyn Trait`, macro-generated methods, and
  interprocedural inference do not create graph hits in v1;
- ambiguous receiver target sets are capped by the existing `LocalUsageInference` limits;
- receiver inference is skipped for plain exported-symbol queries, matching the JS/TS caching/skip tests;
- `UsageAnalyzerSelector` chooses the Rust graph for seeded exported Rust targets and falls back to regex for misses.

Acceptance criteria for the Rust work:

- the new Rust graph, strategy, and selector tests pass;
- existing JS/TS and Python usage graph tests still pass;
- `./gradlew fix tidy` passes during editing;
- final `./gradlew analyze` passes once code editing is complete.
