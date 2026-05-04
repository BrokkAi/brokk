# Python Exported-Symbol Usage Plan

This plan tracks a scoped extension of the existing exported-symbol reference graph to Python. JS/TS already proves the
shape of the approach: flow-insensitive, declaration-first usage analysis over structured import/export facts. The next
work should reuse that machinery where it helps Python, without turning this into a broad graph rewrite.

## Scope

- Add Python exported-symbol usages for in-project code.
- Extract reusable graph pieces only when Python needs them.
- Preserve the existing JS/TS behavior as the compatibility baseline.
- Keep language-specific code focused on extraction and module resolution.
- Use tests as the proof of maturity, especially for Python import and re-export behavior.

## Non-goals

- Reworking JS/TS behavior beyond what is needed to preserve compatibility during extraction.
- Full Python type inference, runtime import execution, import hooks, or virtualenv/package inspection.
- CFG, branch-sensitive narrowing, SSA, or speculative widening to all matching names.
- CommonJS or broader JS/TS feature expansion as part of the Python work.
- UI redesign. App-layer work should only route and preserve usage results/frontier metadata.

## Existing Baseline

The current JS/TS implementation already provides the important precedent:

- language-neutral records such as `ReferenceCandidate`, `ReferenceHit`, `ExportIndex`, `ImportBinder`, and
  `ReferenceGraphResult`;
- fixed-point traversal over `ProjectFile`s with candidate-file limits and frontier tracking;
- structured import/export handling, including barrel re-exports;
- graph-backed routing through `UsageAnalyzerSelector` when an exported-symbol seed is available;
- regex fallback when graph analysis cannot prove a target.

Treat this as working behavior to protect. Do not add new JS/TS capability unless it is required to extract a reusable
component or prove Python parity.

## Stage 1: Define The Minimal Python Adapter Boundary

Goal: identify the smallest reusable seam needed to let Python plug into the existing reference graph.

Progress:

- 2026-05-04: Added `ExportUsageGraphLanguageAdapter`, `ExportResolutionData`, and a JS/TS adapter. The graph now owns
  traversal through the adapter seam while existing JS/TS public entrypoints remain unchanged.
- 2026-05-04: Added fake-adapter tests for proven import hits, candidate-file restriction, and external frontier
  propagation, plus reran JS/TS graph and strategy regression tests.

Implementation:

- Introduce an adapter interface only if it removes concrete duplication for Python. A minimal shape is:
  - `ExportIndex exportIndexOf(ProjectFile file)`;
  - `ImportBinder importBinderOf(ProjectFile file)`;
  - `Set<ReferenceCandidate> usageCandidatesOf(ProjectFile file, ImportBinder binder)`;
  - `ResolutionOutcome resolveModule(ProjectFile importingFile, String moduleSpecifier)`.
- Keep JS/TS as the first adapter or wrapper around current behavior, but avoid churn outside the methods Python needs.
- Reuse existing graph limits, candidate-file restriction, cycle detection, and frontier behavior.
- Keep member/receiver hooks optional and default-empty. Python can start with exported functions/classes before member
  receiver inference.

Tests:

- Add adapter-neutral tests only for behavior Python needs immediately: re-export traversal, cycles, candidate-file
  restriction, unresolved modules, and external frontier propagation.
- Re-run existing JS/TS usage tests unchanged to prove the extraction did not change current behavior.

Exit criteria:

- JS/TS behavior is unchanged.
- Python can implement the adapter without copying fixed-point traversal logic.

## Stage 2: Build Python Import And Export Extraction

Goal: produce structured Python facts without resolving usages yet.

Progress:

- 2026-05-04: Added Python extraction methods for `ExportIndex`, `ImportBinder`, and reference candidates on
  `PythonAnalyzer`, backed by `PythonExportUsageExtractor`.
- 2026-05-04: Added extractor tests for top-level exports, static `__all__`, aliases, relative imports, namespace
  imports, wildcard import non-binding, and import-declaration true negatives.

Implementation:

- Extract exports from:
  - top-level `def`;
  - top-level `class`;
  - statically visible `__all__` entries;
  - package `__init__.py` re-exports where imports are statically visible.
- Extract imports from:
  - `from mod import x`;
  - `from mod import x as y`;
  - `import mod`;
  - `import mod as m`;
  - relative forms such as `from .mod import x` and `from ..pkg import x`.
- Represent Python package barrels with the same `ExportIndex` and `ImportBinder` concepts rather than adding a
  Python-only graph model.
- Treat wildcard imports conservatively. Only produce provenance-backed bindings when `__all__` or another static source
  makes the exported names clear.

Tests:

- Add extractor tests for top-level exports, `__all__`, aliases, absolute imports, relative imports, and
  package `__init__.py` re-exports.
- Add true-negative extractor tests for nested functions/classes, dynamic `__all__`, wildcard imports without static
  provenance, and local assignments that merely share an exported name.

Exit criteria:

- Python files produce structured export/import facts.
- Ambiguous or dynamic Python constructs do not create false bindings.

## Stage 3: Resolve Python Modules And Barrels

Goal: make Python import resolution good enough for in-project usage analysis, including relative imports and package
re-exports.

Progress:

- 2026-05-04: Added `PythonExportUsageGraphAdapter` with in-project absolute module resolution, relative import
  resolution, package `__init__.py` resolution, external frontier reporting, and analyzer-backed referencing files.
- 2026-05-04: Added Python graph tests for absolute imports, relative imports, package-barrel imports through
  `__init__.py`, sibling same-name true negatives, and external frontier/no-hit behavior.
- 2026-05-04: Tightened exact graph target matching to require source-file equality as well as FQN equality, preventing
  same-name declarations in different files from matching.

Implementation:

- Resolve only in-project Python modules to `ProjectFile`.
- Support module paths such as `pkg.mod`, package files such as `pkg/__init__.py`, and sibling modules.
- Support relative import levels from the importing file's package context.
- Resolve package barrels through `__init__.py` without executing Python code.
- Send external modules and unresolved package roots to the external frontier.
- Keep unresolved in-project-looking imports from becoming hits unless resolution proves the target file.

Tests:

- Add true-positive graph tests for:
  - direct absolute import;
  - direct relative import;
  - alias import;
  - package barrel import through `__init__.py`;
  - nested package barrel import.
- Add true-negative graph tests for:
  - same exported name in a sibling module;
  - unresolved relative import;
  - external package import;
  - import cycle with no proven path to the target;
  - local shadowing of an imported name.
- Assert frontier behavior for external and unresolved imports separately from usage hits.

Exit criteria:

- Python can resolve common in-project imports and package barrels.
- The test suite distinguishes proven usages from lookalike names and unresolved imports.

## Stage 4: Add Python Graph Strategy And App Routing

Goal: make Python exported-symbol usages available through the same app-layer surfaces as JS/TS.

Progress:

- 2026-05-04: Added `PythonExportUsageGraphStrategy` and routed seeded Python targets through
  `UsageAnalyzerSelector`, with regex fallback preserved for unseeded Python targets.
- 2026-05-04: Added selector/strategy tests proving Python graph routing, app-layer usage results, and unseeded fallback.
- 2026-05-04: Made Python adapter definition lookup include declaration identifiers so graph-resolved targets match the
  app-layer query `CodeUnit`.

Implementation:

- Add a Python graph strategy that seeds from Python declarations and delegates traversal to the reusable graph.
- Update `UsageAnalyzerSelector` so Python uses the graph when the target can be seeded from declarations.
- Preserve regex fallback for unseedable Python targets and unsupported languages.
- Keep environment flags such as `BRK_FUZZY_USAGES_ONLY` respected if they apply to the selected usage path.
- Preserve `ReferenceGraphResult` frontier metadata through the app boundary, even if the UI does not surface it yet.

Tests:

- Add selector tests proving:
  - Java still uses JDT;
  - JS/TS still uses the existing graph path;
  - Python uses the graph when seeded;
  - Python falls back when unseeded;
  - unsupported languages still use regex.
- Add app-layer tests for Python empty graph results, frontier-only results, candidate-file restriction, and max-hit
  limits.

Exit criteria:

- Python exported-symbol usages work through the main usage selection path.
- Fallback behavior is explicit and tested.

## Stage 5: Extend Python Receiver Inference Only If Needed

Goal: avoid premature member-analysis work, but leave a clear path if Python method/member usages become the next target.

Implementation:

- Start with exported functions/classes and direct imported-symbol usages.
- Only add Python receiver inference after function/class usage is solid and tests show the missing member cases matter.
- If added, express Python receiver facts through the existing generic local-inference model:
  - seed facts from imports, annotations, constructor calls, and declarations;
  - transfer facts for same-scope aliases and bounded destructuring;
  - receiver access sites such as `obj.foo` and `obj.foo()`.
- Keep guardrails: cap targets per local symbol, stop on ambiguity, and prefer explicit provenance over name-only matches.

Tests:

- Before implementing receiver inference, add failing tests that describe the exact member usages needed.
- Add true positives for explicit import or constructor provenance.
- Add true negatives for unknown receivers, same member name on unrelated classes, object/dict literal collisions, and
  ambiguous aliases.

Exit criteria:

- Member support is added only with concrete motivating tests.
- Precision is proven by true negatives, not just additional hits.

## Ongoing Test Standard

Every Python stage should include:

- true positives for intended declaration/import/export paths;
- true negatives for lookalike text, shadowing, unrelated same-name symbols, unresolved modules, and external modules;
- bounded traversal tests for cycles, frontier, and limits;
- candidate-file restriction tests where graph APIs accept candidate sets;
- confidence assertions only at intentional coarse levels.
