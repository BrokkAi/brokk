# SftServer Usage

## Endpoints

The SFT server runs on port `7999` by default and exposes:

- `POST /format_workspace`
- `POST /format-workspace`
- `POST /format_patch`
- `POST /format-patch`

The request/response payload for `/format_workspace` and `/format_patch` is JSON.

## `/format_patch`

`format_patch` accepts:

- `from` (required): source revision
- `to` (required): target revision
- `filenames` (optional): list of project-relative paths to include

When `filenames` is omitted or empty, the response includes all changed files between
`from` and `to`.

Example:

```bash
curl -s http://localhost:7999/format_patch \
  -H 'Content-Type: application/json' \
  -d '{
    "from": "HEAD~1",
    "to": "HEAD",
    "filenames": ["src/main/java/example/Foo.java"]
  }'
```

Response:

- `result` is a map keyed by filename, e.g. `{ "src/main/java/example/Foo.java": "...SRB..." }`.
