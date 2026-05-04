# Python Receiver And Type Usage Plan

This document tracks the active Python receiver/type inference work for this PR. The import/export rollout is now
baseline context: it proves the reference graph can be reused for Python without copying traversal logic, but the next
useful maturity step is member usage precision.

The v1 scope is deliberately pragmatic: seed receiver facts from explicit Python syntax, reuse `LocalUsageInference`,
and keep false positives low. This is not a broad Python type checker and should not drift into JS/TS parity work that
is unrelated to Python member usages.

## Completed Baseline

This PR already has:

- adapter-based graph traversal through `ExportUsageGraphLanguageAdapter`;
- Python export/import extraction through `PythonExportUsageExtractor`;
- Python module and package-barrel resolution, including package `__init__.py` re-exports;
- app routing through `UsageAnalyzerSelector` with regex fallback preserved;
- hardening for nested barrels, import cycles, local import shadowing, and explicit `Foo.bar()` static class access.

Keep the existing import/export graph tests as regression coverage. They are no longer the active roadmap.

## Active Scope

Implement Python receiver/type inference v1 for:

- simple annotations: `x: Foo`, `def f(x: Foo)`, and `self.x: Foo` where the type resolves through imports or same-file
  declarations;
- qualified annotations such as `x: p.Foo` only when `p` is a namespace import with resolvable in-project provenance;
- constructor-created locals such as `x = Foo()` and `x = imported_alias()`;
- same-scope aliases such as `y = x`;
- local shadowing, where `Foo = ...` or `x = ...` blocks stale imported/type facts;
- explicit receiver access such as `obj.foo` and `obj.foo()`.

Do not implement broad flow inference, runtime type inference, dynamic imports, monkeypatching, return-type propagation,
interprocedural calls, wildcard provenance beyond static imports, or JS/TS parity work that is not needed for Python.

## Stage 1: Python Local Usage Events

Goal: emit the Python facts needed by the existing local inference engine.

Implementation:

- Add Python extraction of `LocalUsageEvent` facts.
- Use `ImportBinder` as a resolver for imported class/function names, but do not globally seed imported symbols as
  receivers. Imports should seed locals only through annotations or constructor assignments.
- Emit `DeclareSymbol` for local assignments, function parameters, class/function declarations, and local declarations
  that shadow imports.
- Emit `ReceiverAccess` for `obj.foo` and `obj.foo()`.

Progress:

- 2026-05-04: Added Python receiver event extraction for assignments, typed parameters, constructor calls, aliases, and
  attribute access.
- 2026-05-04: Kept static `Foo.bar()` on the existing qualified-member path by avoiding global receiver seeds for
  imported names.

## Stage 2: Type Annotation Seeds

Goal: use explicit annotations as high-confidence local receiver seeds without parsing Python's full type system.

Implementation:

- Seed locals and parameters from simple annotations such as `x: Foo` and `def f(x: Foo)` only when `Foo` resolves
  through imports or same-file declarations.
- Support qualified annotations like `pkg.Foo` only when `pkg` is a namespace import with a resolvable module.
- For union or generic-looking annotations, only extract explicit simple or qualified type identifiers. Do not interpret
  union/generic semantics beyond those identifiers.

Progress:

- 2026-05-04: Added annotation-backed receiver seeds for local variables, function parameters, annotated instance
  attributes, same-file declarations, named imports, and namespace-qualified imports.
- 2026-05-04: Ambiguous annotations rely on `LocalUsageInference` target caps instead of adding Python-specific
  widening behavior.

## Stage 3: Constructor And Alias Propagation

Goal: cover the common explicit cases where Python code constructs or copies a typed local.

Implementation:

- Seed `x = Foo()` and `x = imported_alias()` when the callee resolves to an exported class.
- Add simple same-scope alias propagation such as `y = x`.
- Respect local shadowing so `Foo = ...` or `x = ...` blocks stale imported/type facts.

Progress:

- 2026-05-04: Added constructor-created receiver seeds and simple alias events.
- 2026-05-04: Added local assignment shadowing so a later `Foo()` does not reuse an imported `Foo` after `Foo = ...`.

## Stage 4: Graph Integration

Goal: make Python receiver facts participate in member usage queries through the reusable graph.

Implementation:

- Add `PythonAnalyzer.resolvedReceiverCandidatesOf(file, binder)`.
- Have `PythonExportUsageGraphAdapter` return Python receiver candidates through the existing adapter hook.
- Keep receiver inference active only for member queries, matching existing graph behavior.
- Reuse `LocalUsageInference` limits and confidence behavior. Do not add Python-only inference machinery.

Progress:

- 2026-05-04: Wired Python receiver candidates through `PythonAnalyzer` caching and
  `PythonExportUsageGraphAdapter.resolvedReceiverCandidatesOf`.

## Stage 5: Guardrails And Non-Goals

Goal: prove the useful positives while explicitly blocking common false positives.

Implementation:

- Cap ambiguous receiver targets through existing `LocalUsageInference` limits.
- Do not fan out by member name alone.
- Do not infer from runtime assignments, dynamic imports, monkeypatching, return types, or interprocedural calls in this
  PR.
- Keep object and dict literals from producing class-member usage hits.

Progress:

- 2026-05-04: Added true-positive and true-negative Python receiver/type graph tests for annotation, constructor,
  alias, namespace-qualified annotation, unknown receivers, unknown constructors, local shadowing, unrelated same-name
  members, ambiguity caps, and dict/object literal collisions.

## Test Plan

Receiver/type tests:

- true positives: `x: Foo; x.bar()`, `def f(x: Foo): x.bar()`, `self.x: Foo; self.x.bar()`,
  `x = Foo(); x.bar()`, `y = x; y.bar()`, `import pkg as p; x: p.Foo; x.bar()`, and existing `Foo.bar()` static
  class access;
- true negatives: `x.bar()` with no seed, `x = Unknown(); x.bar()`, local `Foo = ...` shadowing imported `Foo`,
  unrelated classes with the same `bar` member, ambiguous receiver target sets beyond the cap, and object/dict literal
  lookalikes.

Regression tests:

- existing Python import/export graph tests;
- existing Python graph strategy and app-routing tests;
- existing JS/TS usage graph and strategy tests.

Acceptance criteria:

- targeted Python receiver/type tests pass;
- existing Python import/export tests pass;
- existing JS/TS usage graph tests pass;
- `./gradlew fix tidy` and final `./gradlew analyze` pass before commit.
