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

## Implementation notes

- JS/TS extraction logic intentionally lives outside `JsTsAnalyzer` in
  `ai.brokk.analyzer.javascript.JsTsExportUsageExtractor` so the analyzer stays compositional.
- `ImportBinder` is derived from the existing `importInfoOf(...)` pipeline (structured `ImportInfo`),
  so the same import analysis drives both import-graph resolution and export-usage binding.

## Follow-ups / Roadmap

- Module resolution: `node:` built-ins, package imports, and `tsconfig` paths/aliases.
- CommonJS interop: `require(...)`, `module.exports`, `exports.*`.
- Richer member resolution: `import()` dynamic, destructuring patterns, and non-identifier property access.
- TS-only: type-only exports/imports coverage gaps and `export type { ... }`.

## App wiring

- `BRK_USAGE_GRAPH=1` enables the JS/TS exported-symbol reference-graph usage analyzer in the app-layer
  `ai.brokk.usages.UsageFinder` for JavaScript and TypeScript.
- When enabled, JS/TS usage results are graph-only (no LLM fallback). Frontier is tracked but not yet surfaced in the UI.

## Python follow-up (planned)

Goal: support Python exported-symbol usages without duplicating orchestration logic.

Proposed design:

- Introduce a language-plugin seam for exported-symbol usage graphs:
  - `ExportUsageGraphLanguageAdapter` (new interface) with hooks:
    - `boolean supports(ProjectFile)` (or `Language supportedLanguage()`)
    - `ExportIndex exportIndexOf(ProjectFile)`
    - `ImportBinder importBinderOf(ProjectFile)`
    - `Set<ReferenceCandidate> usageCandidatesOf(ProjectFile, ImportBinder)`
    - `ResolutionOutcome resolveModule(ProjectFile importingFile, String moduleSpecifier)` returning
      `{resolvedInProject?: ProjectFile, externalFrontier?: String}`
    - optional: `Set<CodeUnit> polymorphicMatches(CodeUnit target, IAnalyzer analyzer)` (default empty)
- Move the fixed-point orchestration into a reusable base:
  - `AbstractExportUsageReferenceGraph` (or a generic static helper) that implements:
    - fixed-point traversal over `ProjectFile`s, limits, cycle detection
    - optional `candidateFiles` restriction (`@Nullable Set<ProjectFile> candidateFiles`)
    - frontier + external-frontier accumulation, returned as `ReferenceGraphResult`
- JS/TS becomes a small adapter implementation of the interface.
- Python support becomes a second adapter that only implements extraction + module resolution:
  - exports: treat top-level `def`/`class` and `__all__` (best-effort) as exports
  - imports: `from mod import x as y`, `import mod as m`
  - resolution: in-project module resolution only; external modules go to external frontier
