# Usages Package Guide

This package contains the usages infrastructure shared by context fragments, MCP tools, and analyzer-specific usage
strategies.

## Package model

- `UsageAnalyzerSelector` chooses the strategy for a `CodeUnit`.
- Java targets use `JdtUsageAnalyzerStrategy`.
- JS/TS exported symbols use the reference-graph strategy when the target can be seeded from structured exports.
- Remaining targets fall back to `RegexUsageAnalyzer`.
- `ReferenceCandidate`, `ReferenceHit`, `ExportIndex`, `ImportBinder`, and `ReferenceGraphResult` are intended to stay
  language-neutral.

## Reference-graph conventions

- The graph is flow-insensitive and declaration-first. Prefer proving a usage through import/export declarations before
  considering local receiver inference.
- Preserve confidence scoring at coarse levels only; tests should not depend on incidental floating-point ordering.
- JS/TS extraction logic lives outside `JsTsAnalyzer` in
  `ai.brokk.analyzer.javascript.JsTsExportUsageExtractor` so the analyzer stays compositional.
- `ImportBinder` is derived from structured import analysis, not text search.
- Barrel/local re-exports should be modeled from structured export/import state, not grep.
- Receiver inference should only run for member queries, not plain exported symbol lookups.
- Local receiver inference should stay reusable by using generic fact/event records rather than language-specific AST
  state.
- False-positive guardrails for local inference are mandatory:
  - cap possible targets per local symbol;
  - prefer import-derived or explicit-provenance targets;
  - stop propagating once ambiguity exceeds the cap;
  - do not fan out to every class with a matching member name.

## Planning

Longer-term implementation plans live in `PLANS.md` in this package. Keep this file focused on package orientation and
local conventions.
