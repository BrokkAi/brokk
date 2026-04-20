Analyzer tests that exercise `brokk-shared` implementation (Tree-sitter analyzers, ranking, imports/usages, caches, etc.)
should live in `brokk-shared`.

Tests that primarily exercise app wiring (UI, persistence, `AnalyzerWrapper`, sessions/MCP, build-agent integration)
should remain in `app`.

