# Running a local Brokk ACP server in JetBrains IDEs

This guide explains how to build the Brokk Java ACP server from source and wire
it into a JetBrains IDE (IntelliJ IDEA, PyCharm, etc.) for local development
work — for example, when you want the IDE's Brokk Code panel to use your
in-progress branch instead of the published release.

The general design of the ACP server is described in
[acp-native-java-server.md](acp-native-java-server.md); this document covers
only the JetBrains wiring.

## Prerequisites

- A working JetBrains IDE with the Brokk Code plugin installed.
- The Brokk PyPI release available on `PATH` (the `uvx brokk acp` wrapper). Most
  developers already have it from `uvx brokk` for the headless CLI.
- A local checkout of `BrokkAi/brokk` and the standard build environment
  (Java 21, Gradle wrapper).

You do **not** need a Python environment beyond what `uvx` already manages.

## Step 1: Build the shadow jar

The shadow jar is gated behind an explicit task name — running `./gradlew build`
or `./gradlew :app:check` will not produce it. Run the task directly:

```bash
./gradlew :app:shadowJar
```

The output lands at `app/build/libs/brokk-${git-describe}.jar`, where
`${git-describe}` is the result of `git describe` against the nearest version
tag (for example `0.23.5.beta8-2-g3c724bef9`). The version embeds the commit
SHA, so each rebuild on a different commit produces a distinctly named jar.

The first build is slow (~2 min on a warm machine); incremental rebuilds when
only application code changed are fast (~20 s) because Gradle caches the
component jars.

## Step 2: Wire the jar into `~/.jetbrains/acp.json`

The JetBrains plugin reads agent server definitions from
`~/.jetbrains/acp.json`. The schema is a top-level object with a single
`agent_servers` map keyed by display name. Each entry specifies a `command`,
optional `args`, and optional `env`.

To run your local jar, add (or update) a `Brokk Code (Local Jar)` entry that
delegates to the `uvx brokk acp` wrapper with `--jar` pointing at the absolute
path of the shadow jar you just built:

```json
{
  "default_mcp_settings": {},
  "agent_servers": {
    "Brokk Code (Local Jar)": {
      "command": "/opt/homebrew/bin/uvx",
      "args": [
        "brokk",
        "acp",
        "--jar",
        "/absolute/path/to/brokk/app/build/libs/brokk-0.23.5.betaN-K-gSHA.jar"
      ],
      "env": {}
    }
  }
}
```

Replace `/absolute/path/to/brokk` with your checkout path and the version
fragment with the actual filename produced by Step 1. `command` is whatever
absolute path your `uvx` lives at — `which uvx` will tell you (`/opt/homebrew/bin/uvx`
on Apple Silicon Homebrew, `/usr/local/bin/uvx` on Intel Homebrew, etc.).

You can keep multiple entries in `agent_servers` simultaneously; the plugin
shows them all in the agent server picker.

## Step 3: Select the local jar in the IDE

1. Restart the Brokk Code panel (or the IDE) so the plugin re-reads `acp.json`.
2. In the Brokk Code panel's agent server selector, choose `Brokk Code (Local Jar)`.
3. Start a new session. The first prompt should connect to your local jar — you
   can confirm by tailing `~/.brokk/debug.log` and looking for the `acp_server`
   entries.

## Why route through `uvx brokk acp --jar` instead of `java -jar` directly

The `uvx brokk acp` wrapper handles three things on your behalf that a raw
`java -jar` invocation does not:

1. **Stdio hygiene** — the wrapper redirects logging away from stdout, which is
   reserved for JSON-RPC traffic. Any stray stdout output corrupts the protocol
   stream and crashes the client (see
   [acp-native-java-server.md § Logging must avoid stdout](acp-native-java-server.md)).
2. **Brokk key plumbing** — the wrapper passes your stored Brokk API key to the
   subprocess via the appropriate flags, without you having to thread it
   through `acp.json` `env` blocks (which would land plaintext in a config file).
3. **Proxy/vendor flag forwarding** — the wrapper accepts the same
   `--proxy-setting` and `--vendor` flags as the published release and translates
   them into `AcpServerMain` arguments, so the local jar inherits the same
   defaults as the production binary.

It is technically possible to bypass the wrapper with a `java -jar
<path-to-jar> --workspace-dir … --brokk-api-key …` invocation, but you would
have to replicate all three concerns above by hand, and any mistake is silent
(the IDE just hangs on the first response).

## Rebuilding after code changes

After a `git pull` or local commit, re-run `./gradlew :app:shadowJar`. The new
jar will land at a new path because the git-describe fragment changes. Either:

- Update the `--jar` path in `~/.jetbrains/acp.json` to the new filename, or
- Symlink (or copy) the new jar to a stable name and reference that in
  `acp.json` so you do not have to edit it on every rebuild. For example:
  ```bash
  ln -sf "$(ls -t app/build/libs/brokk-*.jar | head -1)" \
      app/build/libs/brokk-local.jar
  ```

The plugin only reads `acp.json` on (re)load, so after editing the file you
must restart the Brokk Code panel.
