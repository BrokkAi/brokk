# Tooling Notes

## `extract_turn.py`

Use `extract_turn.py` to inspect one archived Brokk code turn from either:
- an extracted worktree directory
- a `.zip` archive containing the worktree

Run it with `uv`:

```bash
uv run python /home/jonathan/Projects/brokk/tools/extract_turn.py WORKTREE_OR_ZIP TURN
```

Example:

```bash
uv run python /home/jonathan/Projects/brokk/tools/extract_turn.py /path/to/archive.zip 2
```

### Output modes

Default output is pretty JSON. This is the canonical format and should be preferred for any downstream analysis.

Use `--tagged` for a human-readable plaintext view:

```bash
uv run python /home/jonathan/Projects/brokk/tools/extract_turn.py --tagged /path/to/archive.zip 2
```

Use `--message N` to extract a single normalized message:

```bash
uv run python /home/jonathan/Projects/brokk/tools/extract_turn.py /path/to/archive.zip 2 --message 5
uv run python /home/jonathan/Projects/brokk/tools/extract_turn.py --tagged /path/to/archive.zip 2 --message 5
```

### JSON shape

The extractor returns one top-level object:

```json
{
  "worktree": "/abs/path/to/archive.zip",
  "turn": 2,
  "metadata": {},
  "messages": []
}
```

Normalized message types are:
- `system`
- `user`
- `assistant`
- `tool_result`

Assistant messages may include optional:
- `reasoning`
- `toolExecutionRequests`

Turn-level `metadata` is top-level, not attached to the final assistant message.

### Tool results

Tool results are normalized through one shared path, but they may come from two places:

- historical tool/function result messages already present in `request.json`
- same-turn `NNN-tools.jsonl` records written after the assistant response

This matters for terminal tool-call turns. If the model ends the turn by making a tool call, there may be no later `request.json` carrying the tool result. In that case, `extract_turn.py` reads the same-turn `*-tools.jsonl` files and appends those `tool_result` messages after the final assistant message.

### Response sections

From the final `.log` file:
- `## text` is required and becomes the final assistant `text`
- `## reasoningContent` becomes assistant `reasoning`
- `## toolExecutionRequests` becomes assistant `toolExecutionRequests`
- `## metadata` becomes top-level `metadata`

Invalid JSON in `toolExecutionRequests`, `metadata`, or `tools.jsonl` is treated as an error.
