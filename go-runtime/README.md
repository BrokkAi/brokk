# Brokk Go Runtime

This module is the bootstrap of a Java-free runtime for Brokk deployments.

It currently provides:

- `brokk-go-executor`: a compatibility-oriented HTTP executor with session zip storage, job/event streaming, and the endpoints used by `brokk-code`
- `brokk-go-mcp`: a stdio MCP server with the current Brokk base tool names and lightweight workspace-backed implementations

Build locally:

```powershell
go build -o .\bin\brokk-go-executor.exe .\cmd\brokk-go-executor
go build -o .\bin\brokk-go-mcp.exe .\cmd\brokk-go-mcp
```

The Python client will prefer these binaries automatically when they exist in `go-runtime/bin/` or are pointed to by `BROKK_GO_EXECUTOR` / `BROKK_GO_MCP`.
