# Brokk Headless Executor CLI Tool

The Brokk Headless Executor CLI (`HeadlessExecCli`) is a standalone command-line tool that automates end-to-end workflows by starting a local headless executor in-process, creating a session, submitting a job, and streaming results to stdout.

This tool is ideal for:
- **Testing and development** of headless executor workflows locally
- **Scripting and automation** of Brokk tasks in CI/CD pipelines
- **Rapid prototyping** without managing external server processes

## Quick Start

Run the CLI with a prompt and a planner model:

```bash
./gradlew :app:runHeadlessCli --args "--planner-model gpt-5 'Find all classes in the service package'"
```

The CLI will:
1. Start a local executor on an ephemeral port
2. Create a session
3. Submit your prompt as a job
4. Stream results to stdout
5. Clean up resources on exit

## Installation

Build the shadow JAR containing the CLI:

```bash
./gradlew shadowJar
```

Run directly:

```bash
java -cp app/build/libs/brokk-<version>.jar ai.brokk.tools.HeadlessExecCli [options] <prompt>
```

Or via Gradle:

```bash
./gradlew :app:runHeadlessCli --args "[options] <prompt>"
```

## Command-Line Options

| Option | Type | Required | Default | Description |
|--------|------|----------|---------|-------------|
| `--mode MODE` | String | No | `ARCHITECT` | Execution mode: `ASK`, `CODE`, `ARCHITECT`, or `LUTZ` |
| `--planner-model MODEL` | String | **Yes** | N/A | LLM model for planning and reasoning (e.g., `gpt-5`, `claude-3-opus`) |
| `--code-model MODEL` | String | No | Project default | LLM model for code generation (CODE and ARCHITECT modes) |
| `--token TOKEN` | String | No | Random UUID | Authentication token for the executor (defaults to a randomly generated UUID if not provided) |
| `--auto-commit` | Flag | No | `false` | Enable automatic git commits after task completion |
| `--auto-compress` | Flag | No | `false` | Enable automatic context compression to reduce token usage |
| `--help` | Flag | No | N/A | Display usage information and exit |
| `<prompt>` | Positional | **Yes** | N/A | The task or question to submit to the executor |

## Usage Examples

### ASK Mode: Read-Only Codebase Search

Search and explore code without making modifications:

```bash
./gradlew :app:runHeadlessCli --args "--mode ASK --planner-model gpt-5 'Find the UserService class and explain its responsibilities'"
```

**Characteristics:**
- Read-only: no code changes or commits
- Uses `SearchAgent` for intelligent codebase exploration
- `--code-model` is ignored (SearchAgent doesn't generate code)
- Streams search results and code summaries

### CODE Mode: Single-Shot Code Generation

Generate code quickly for a specific task:

```bash
./gradlew :app:runHeadlessCli --args "--mode CODE --planner-model gpt-5 --code-model gpt-5-mini 'Create a utility class to sanitize filenames'"
```

**Characteristics:**
- Generates code for a single objective (no decomposition)
- Uses `plannerModel` for reasoning, `codeModel` for code generation
- No automatic commits unless `--auto-commit` is specified
- Useful for quick, isolated code additions

### ARCHITECT Mode: Multi-Step Planning and Implementation

Full multi-step planning and implementation workflow (default):

```bash
./gradlew :app:runHeadlessCli --args "--mode ARCHITECT --planner-model gpt-5 --code-model gpt-5-mini --auto-commit 'Refactor the authentication module to improve error handling and add comprehensive logging'"
```

**Characteristics:**
- Implicit per-task reasoning and planning
- User provides task via `--prompt` (tasks are not auto-decomposed)
- Uses `plannerModel` for reasoning, `codeModel` for implementation
- Respects `--auto-commit` and `--auto-compress` settings
- Best for iterative, multi-step objectives

### LUTZ Mode: Intelligent Task Decomposition

Auto-decompose complex objectives into tasks, then execute each:

```bash
./gradlew :app:runHeadlessCli --args "--mode LUTZ --planner-model gpt-5 --code-model gpt-5-mini --auto-commit --auto-compress 'Add comprehensive error handling to the UserService class and ensure all exceptions are properly logged with context'"
```

**Characteristics:**
- Two-phase execution: planning → task decomposition → sequential execution
- `SearchAgent` generates a structured task list from your objective
- `ArchitectAgent` executes each subtask sequentially
- Respects `--auto-commit` and `--auto-compress` per task
- Streams planning and execution events
- Ideal for complex objectives that benefit from structured decomposition

## Authentication and Tokens

### Token Usage

By default, the CLI generates a random UUID as the authentication token:

```bash
./gradlew :app:runHeadlessCli --args "--planner-model gpt-5 'Your task here'"
# Token automatically generated and used for authentication
```

To specify a custom token:

```bash
./gradlew :app:runHeadlessCli --args "--token my-custom-token --planner-model gpt-5 'Your task here'"
```

The token is used in the `Authorization: Bearer <token>` header for all HTTP requests to the local executor.

### Security Notes

- Tokens are used only for local in-process communication
- Each CLI invocation uses its own ephemeral executor instance
- No tokens are persisted or transmitted outside the local machine
- For remote executor deployments, use secure tokens managed externally

## Ephemeral Port Detection

The CLI automatically detects and uses an ephemeral port:

1. **HeadlessExecutorMain** binds to `127.0.0.1:0` (OS assigns a random available port)
2. **CLI discovers the port** via `executor.getPort()`
3. **All HTTP requests** are routed to `http://127.0.0.1:<discovered-port>`

This approach ensures:
- No port conflicts with other services
- Multiple CLI instances can run concurrently
- No manual port configuration needed

Example trace:

```
INFO  HeadlessExecutorMain initialized successfully
INFO  Executor started on port 54321
INFO  Created session: <uuid>
INFO  Submitted job: <uuid>
INFO  [streaming events...]
INFO  Job completed successfully
INFO  Executor stopped
INFO  Deleted temp workspace: /tmp/brokk-headless-xxxxxx
```

## Resource Cleanup

The CLI automatically manages cleanup on exit:

### Temporary Workspace

A temporary directory is created for each run:

```
/tmp/brokk-headless-<random>/
```

This directory is **recursively deleted** on exit, whether the job succeeds, fails, or is interrupted.

### Executor Process

The `HeadlessExecutorMain` is started in-process (not as a subprocess) and is **automatically stopped** on:
- Successful job completion
- Job failure or cancellation
- CLI termination (Ctrl+C)
- Unhandled exception

### HTTP Client

The OkHttp HTTP client's executor service is **gracefully shut down** to release thread pool resources.

### Graceful Shutdown

Example cleanup sequence:

```bash
# Ctrl+C during execution
^C
INFO  Interrupted while streaming events
INFO  Executor stopped
INFO  Deleted temp workspace: /tmp/brokk-headless-xxxxxx
```

## Advanced Usage

### Enabling Context Compression

Compress context history after task completion to reduce token usage in subsequent tasks:

```bash
./gradlew :app:runHeadlessCli --args "--mode ARCHITECT --planner-model gpt-5 --auto-compress 'Implement a caching layer for frequently accessed data'"
```

Context compression reduces the size of the context window, making it more efficient for subsequent analysis.

### Combining Auto-Commit and Auto-Compress

For fully automated workflows:

```bash
./gradlew :app:runHeadlessCli --args "--mode LUTZ --planner-model gpt-5 --code-model gpt-5-mini --auto-commit --auto-compress 'Refactor core modules for performance and maintainability'"
```

This enables:
- Automatic git commits after each task
- Automatic context compression to manage token usage
- Full end-to-end automation suitable for CI/CD

### Using Different Models for Planning and Code Generation

Optimize cost and performance by using different models:

```bash
./gradlew :app:runHeadlessCli --args "--mode ARCHITECT --planner-model gpt-5 --code-model gpt-5-mini 'Add comprehensive error handling'"
```

- `--planner-model`: More capable model for reasoning (typically more expensive)
- `--code-model`: More cost-effective model for code generation (typically faster)

## Troubleshooting

### Issue: "plannerModel is required"

**Cause:** The `--planner-model` flag was not provided.

**Solution:** Add the flag:

```bash
./gradlew :app:runHeadlessCli --args "--planner-model gpt-5 'Your task'"
```

### Issue: "Failed to create session"

**Cause:** The local executor failed to initialize or respond.

**Symptoms:**
- Network errors or timeout
- Port binding failure

**Solution:**
- Ensure no other services are running on local ports
- Check available disk space for the temporary workspace
- Verify OkHttp timeouts (default: 10s connect, 30s read)

### Issue: "Job streaming timeout"

**Cause:** The LLM took too long to respond or the CLI polling timed out.

**Symptoms:**
- Job events stop appearing after some time
- CLI exits with "Job streaming timeout"

**Solution:**
- Increase the polling deadline in `HeadlessExecCli.java` (default: 1 hour)
- Use a faster LLM model for `--code-model`
- Reduce context size with `--auto-compress` to improve LLM response time

### Issue: "Temporary workspace was not cleaned up"

**Cause:** The CLI crashed or was forcibly terminated before cleanup.

**Solution:**
- Manually delete stale temporary directories:
  ```bash
  rm -rf /tmp/brokk-headless-*
  ```
- Check `/tmp` periodically for orphaned directories

### Issue: Multiple CLI instances interfere with each other

**Cause:** Unlikely, as each instance uses ephemeral ports and isolated workspaces.

**Solution:**
- Verify that each CLI invocation reports a different port
- Check that temporary directories are distinct
- If issues persist, stagger CLI starts by a few seconds

## Environment Variables (Not Required)

The CLI does not require environment variables. All configuration is via command-line flags. However, the underlying `HeadlessExecutorMain` respects:

- `EXEC_ID` (optional; CLI generates a random UUID)
- `AUTH_TOKEN` (overridden by CLI's `--token` flag)

These are set internally by the CLI and do not need manual configuration.

## Exit Codes

The CLI returns the following exit codes:

| Code | Meaning |
|------|---------|
| `0` | Job completed successfully |
| `1` | Job failed, CLI error, or internal exception |
| `2` | Job cancelled or CLI interrupted (Ctrl+C) |

Example:

```bash
./gradlew :app:runHeadlessCli --args "--planner-model gpt-5 'Task'"
echo $?  # Prints 0, 1, or 2
```

## See Also

- [Headless Executor API](docs/headless-executor.md) — HTTP API documentation and job modes
- [Headless Executor Testing with curl](docs/headless-executor-testing-with-curl.md) — curl examples for the HTTP API
- [Brokk CLI](docs/cli-search-workspace.md) — The main Brokk CLI for interactive use
