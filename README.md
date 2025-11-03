<p align="center">
  <img src="docs/brokk.png" alt="Brokk – the forge god" width="600">
</p>

## Table of Contents
- [Overview](#overview)
- [Running Brokk](#running-brokk)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [Increasing JVM heap when running via Gradle](#increasing-jvm-heap-when-running-via-gradle)

# Overview

Brokk (the [Norse god of the forge](https://en.wikipedia.org/wiki/Brokkr))
is the first code assistant that understands code semantically, not just
as chunks of text.  Brokk is designed to allow LLMs to work effectively
on large codebases that cannot be jammed entirely into working context.

There is a [Brokk Discord](https://discord.gg/QjhQDK8kAj) for questions and suggestions.

# Running Brokk

1. Sign up at [Brokk.ai](https://brokk.ai/)
1. Follow the instructions to download and run Brokk

# Documentation

Brokk documentation is at https://brokk.ai/documentation/.

# Running the Headless Executor

The Headless Executor runs Brokk sessions in a server mode, controllable via HTTP+JSON API. It's designed for remote execution, CI/CD pipelines, and programmatic task automation.

## Configuration

The executor requires the following configuration, provided via **environment variables** or **command-line arguments** (arguments take precedence):

| Configuration | Env Var | Argument | Required | Description |
|--------------|---------|----------|----------|-------------|
| Executor ID | `EXEC_ID` | `--exec-id` | Yes | UUID identifying this executor instance |
| Listen Address | `LISTEN_ADDR` | `--listen-addr` | Yes | Host:port to bind (e.g., `0.0.0.0:8080`) |
| Auth Token | `AUTH_TOKEN` | `--auth-token` | Yes | Bearer token for API authentication |
| Workspace Dir | `WORKSPACE_DIR` | `--workspace-dir` | Yes | Path to the project workspace |
| Sessions Dir | `SESSIONS_DIR` | `--sessions-dir` | No | Path to store sessions (defaults to `<workspace>/.brokk/sessions`) |

## Running from Source

Run the headless executor with Gradle:

```bash
./gradlew :app:runHeadlessExecutor
```

### Examples

**Using environment variables:**
```bash
export EXEC_ID="550e8400-e29b-41d4-a716-446655440000"
export LISTEN_ADDR="localhost:8080"
export AUTH_TOKEN="my-secret-token"
export WORKSPACE_DIR="/path/to/workspace"
./gradlew :app:runHeadlessExecutor
```

**Using command-line arguments:**
```bash
./gradlew :app:runHeadlessExecutor -- \
  --exec-id 550e8400-e29b-41d4-a716-446655440000 \
  --listen-addr localhost:8080 \
  --auth-token my-secret-token \
  --workspace-dir /path/to/workspace
```

**Mixed (args override env):**
```bash
export AUTH_TOKEN="my-secret-token"
./gradlew :app:runHeadlessExecutor -- \
  --exec-id 550e8400-e29b-41d4-a716-446655440000 \
  --listen-addr localhost:8080 \
  --workspace-dir /path/to/workspace
```

## API Endpoints

Once running, the executor exposes the following endpoints:

### Health & Info (Unauthenticated)

- **`GET /health/live`** - Liveness check, returns `200` if server is running
- **`GET /health/ready`** - Readiness check, returns `200` if session loaded, `503` otherwise
- **`GET /v1/executor`** - Returns executor info (ID, version, protocol version)

### Session Management (Authenticated)

- **`POST /v1/session`** - Upload session zip file
  - Content-Type: `application/zip`
  - Returns: `{ "sessionId": "<uuid>" }`

### Job Management (Authenticated)

- **`POST /v1/jobs`** - Create and execute a job
  - Requires `Idempotency-Key` header for safe retries
  - Body: `JobSpec` JSON with task input
  - Returns: `{ "jobId": "<uuid>", "state": "running", ... }`

- **`GET /v1/jobs/{jobId}`** - Get job status
  - Returns: `JobStatus` JSON with current state and metadata

- **`GET /v1/jobs/{jobId}/events`** - Get job events (supports polling)
  - Query params: `?after={seq}&limit={n}`
  - Returns: Array of `JobEvent` objects

- **`POST /v1/jobs/{jobId}/cancel`** - Cancel a running job
  - Returns: Updated `JobStatus`

- **`GET /v1/jobs/{jobId}/diff`** - Get git diff of job changes
  - Returns: Plain text diff

### Authentication

Authenticated endpoints require the `Authorization` header:

```
Authorization: Bearer <AUTH_TOKEN>
```

Requests without a valid token receive `401 Unauthorized`.

## Production Deployment

Build the shadow JAR:

```bash
./gradlew :app:shadowJar -PenableShadowJar=true
```

Run the JAR:

```bash
java -jar app/build/libs/brokk-<version>.jar \
  ai.brokk.executor.HeadlessExecutorMain \
  --exec-id 550e8400-e29b-41d4-a716-446655440000 \
  --listen-addr 0.0.0.0:8080 \
  --auth-token my-secret-token \
  --workspace-dir /path/to/workspace
```

**Note:** The JAR requires the fully-qualified main class (`ai.brokk.executor.HeadlessExecutorMain`) as the first argument.

# Contributing

Brokk uses Gradle with Scala support. To build Brokk,
1. Ensure you have JDK 21 or newer. Note the JetBrains Runtime is the preferred JDK.
2. Run Gradle commands directly: `./gradlew <command>`
3. Available commands: `run`, `test`, `build`, `shadowJar`, `tidy`, etc.

The frontend uses **pnpm** for package management. Gradle automatically handles pnpm installation and dependency management during builds.

## Increasing JVM heap when running via Gradle

When running Brokk from source with Gradle, increase the application JVM heap using standard `-Xmx` flags. The recommended approach is to set `JAVA_TOOL_OPTIONS` so the setting is inherited by the forked application JVM.

Examples:
- macOS/Linux:
  - `JAVA_TOOL_OPTIONS="-Xmx8G" ./gradlew run`
  - Or:
    - `export JAVA_TOOL_OPTIONS="-Xmx8G"`
    - `./gradlew run`
- Windows (PowerShell):
  - `$env:JAVA_TOOL_OPTIONS="-Xmx8G"; ./gradlew run`
- Windows (cmd.exe):
  - `set JAVA_TOOL_OPTIONS=-Xmx8G && gradlew run`

Notes:
- Do not use `-Dorg.gradle.jvmargs` or `GRADLE_OPTS` for application memory. These configure Gradle's own JVM and do not affect the forked application JVM.

There are documents on specific aspects of the code in [development.md](https://github.com/BrokkAi/brokk/tree/master/app/src/main/development.md).
