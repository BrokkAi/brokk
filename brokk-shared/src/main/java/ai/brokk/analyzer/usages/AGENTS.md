# JS/TS Exported-Symbol Usages (Flow-Insensitive)

This package contains the "usages" infrastructure. For JS/TS, we are adding a language-agnostic reference-graph
representation and a small orchestration layer that can answer "where is this exported symbol used?" better than grep.

## Goals

- Language-agnostic IR (`ReferenceCandidate`, `ReferenceHit`) that other languages can plug into.
- Flow-insensitive resolution with confidence scoring.
- Scalable orchestration bounded to `ProjectFile` compilation units, with on-demand frontier expansion.
- JS/TS v1 focuses on ESM imports/exports including re-exports (`export * from`, `export {x as y} from`).

## Non-goals (v1)

- CommonJS: `require()`, `module.exports`, `exports.*`.
- Full TypeScript type-checker inference / structural typing.
- Control flow, data flow, or CFG/SSA.

## IR

- `ReferenceCandidate`: a syntactic outgoing reference with local context (identifier, optional qualifier, kind, range, enclosing unit).
- `ReferenceHit`: a resolved edge (usage site -> resolved `CodeUnit`) with confidence.

## JS/TS approach (v1)

We build and cache two per-file indexes:

- `ExportIndex`: what a file exports (direct exports + re-exports + `export *`), plus a minimal class `extends` edge list
  to support class-inheritance-only polymorphism.
- `ImportBinder`: what local binding names are introduced by imports, including aliases and namespace imports.

Resolution proceeds by:

1) Resolving a module specifier to a `ProjectFile` using the existing JS/TS module resolution logic in `JsTsAnalyzer`.
2) Resolving exported names by consulting the target file's `ExportIndex`, following re-export chains transitively.
3) Extracting usage candidates from importing files by matching identifier/member expressions against the `ImportBinder`.

The orchestration runs as a fixed-point expansion over `ProjectFile`s, stopping on budget and returning a frontier of
additional files needed to continue resolution.

