# CLI Search Workspace Mode

The `--search-workspace` mode runs SearchAgent to find relevant context and outputs detailed JSON metrics.

## Basic Usage

```bash
./cli --project /path/to/project --search-workspace "your query here"
```

## Options

### `--disable-context-scan`
Skip the initial ContextAgent deep scan (enabled by default).

```bash
./cli --project ~/myproject --search-workspace "query" --disable-context-scan
```

### `--planmodel "Model Alias"`
Override the search model. Uses model aliases from MainProject.DEFAULT_FAVORITE_MODELS: `"Flash 2.5"`, `"Gemini Pro 2.5"`, `"GPT-5"`, `"GPT-5 mini"`, `"Sonnet 4"`.

```bash
./cli --project ~/myproject --search-workspace "query" --planmodel "Gemini Pro 2.5"
```

### `--commit <hash>`
Checkout specific commit before search (for benchmark reproducibility).

```bash
./cli --project ~/myproject --search-workspace "query" --commit abc123def
```

## JSON Output

Search results are output to stdout with this structure:

```json
{
  "query": "How does authentication work?",
  "found_files": ["src/main/java/auth/AuthManager.java", "src/main/java/auth/User.java", ...],
  "turns": 3,
  "elapsed_ms": 15420,
  "success": true,
  "context_scan": {
    "files_added": 5,
    "scan_time_ms": 2341,
    "skipped": false,
    "files_added_paths": [...]
  },
  "turns_detail": [
    {
      "turn": 1,
      "tool_calls": ["findReferences", "addFile"],
      "files_added": 2,
      "files_added_paths": [...],
      "files_removed_paths": [],
      "time_ms": 5234
    }
  ],
  "failure_type": null,
  "stop_reason": "SUCCESS",
  "final_workspace_size": 2,
  "final_workspace_files": ["src/main/java/auth/AuthManager.java"],
  "final_workspace_fragments": [
    {
      "type": "PROJECT_PATH",
      "id": "abc123...",
      "description": "AuthManager.java",
      "files": ["src/main/java/auth/AuthManager.java"]
    }
  ]
}
```

### Key Fields

- `found_files`: All files added during context scan and search turns (includes files later removed)
- `final_workspace_files`: Files present in the final workspace at search completion (subset of found_files)
- `turns`: Number of search turns (AI messages)
- `success`: Whether the search completed successfully
- `context_scan`: Initial scan metrics (files added, time, whether skipped)
- `turns_detail`: Per-turn metrics with tool calls and file changes
- `failure_type`: Classification if unsuccessful (`null` on success)
- `stop_reason`: Why the search stopped (e.g., `"SUCCESS"`, `"LLM_ERROR"`)
- `final_workspace_size`: Number of fragments in the final workspace
- `final_workspace_fragments`: Fragments from files in the final workspace (filtered to match final_workspace_files)

## SearchAgent Usage Modes

SearchAgent is used in multiple modes across UI and CLI:

| Mode | Agent | Use Case |
|------|-------|----------|
| **CLI --search-workspace** | SearchAgent | Find relevant files with detailed metrics |
| **CLI --search-answer** | SearchAgent | Answer question based on codebase |
| **CLI --lutz** | SearchAgent | Generate task list then execute tasks |
| **UI Search** | SearchAgent | Find relevant files + optionally create task list |
| **UI Ask** | None (direct LLM) | Simple Q&A without context changes |
| **UI Code** | CodeAgent | Generate/modify code |
| **UI Architect** | ArchitectAgent | Multi-step orchestration with search + code |

### Context Scan Behavior

- **Always enabled**: UI Search, CLI --search-answer, CLI --lutz
- **Conditionally enabled**: CLI --search-workspace (skipped if --disable-context-scan)
- **Never used**: Modes that don't use SearchAgent (Ask, Code, Architect)

### Metrics Tracking

Only `--search-workspace` uses `SearchMetrics.tracking()` for detailed benchmarking. All other SearchAgent modes use `SearchMetrics.noOp()` (zero overhead).

## Integration with SearchBench

SearchBench uses this mode for benchmarking:

```bash
./cli --project /path/to/target \
      --search-workspace "benchmark question" \
      --planmodel "Flash 2.5" \
      --commit abc123 > result.json
```
