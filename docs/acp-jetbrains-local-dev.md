# Running a local Brokk ACP server in Zed and JetBrains IDEs

This guide explains how to build the Brokk Java ACP server from your local
checkout and wire it into Zed or a JetBrains IDE (IntelliJ IDEA, PyCharm, etc.)
for local development work — for example, when you want the editor's Brokk
Code panel to use your in-progress branch instead of the published release.

The general design of the ACP server is described in
[acp-native-java-server.md](acp-native-java-server.md); this document covers
only the editor wiring.

## Prerequisites

- Zed and/or a JetBrains IDE with the Brokk Code agent installed.
- The Brokk PyPI release available on `PATH` (the `uvx brokk acp` wrapper).
  Most developers already have it from `uvx brokk` for the headless CLI; if
  not, install with `pipx install brokk` or `pip install --user brokk`.
- A local checkout of `BrokkAi/brokk` and the standard build environment
  (Java 21, Gradle wrapper).

## One-command setup

There are two Gradle tasks that build the shadow jar **and** rewrite a
`Brokk Code (Local Jar)` entry in your editor's config to point at the just-
built jar. Run the one you want:

```bash
# Wire the jar into Zed (~/.config/zed/settings.json)
./gradlew :buildAcpServerJarForZed

# Wire the jar into JetBrains (~/.jetbrains/acp.json)
./gradlew :buildAcpServerJarForJetbrains
```

Each task:

1. Runs `:app:shadowJar` to produce `app/build/libs/brokk-${git-describe}.jar`.
2. Resolves the absolute path of `uvx` on your `PATH` (so the editor's spawned
   subprocess does not depend on inherited shell `PATH`, which GUI apps on
   macOS do not get).
3. Rewrites a single `Brokk Code (Local Jar)` entry under `agent_servers` in
   the editor's config, replacing any prior entry with the same name and
   leaving every other entry intact.

Re-run the task after `git pull` or any code change. The new jar lands at a
new filename (the version embeds the `git describe` output) and the entry
gets updated to match — no manual edit, no symlink.

## Selecting the local jar in your editor

After running one of the tasks, the editor's `Brokk Code (Local Jar)` agent
server entry is current. To activate it:

- **Zed**: open the agent server picker in the Brokk Code panel and pick
  `Brokk Code (Local Jar)`. Restart the panel if it does not appear yet.
- **JetBrains**: restart the Brokk Code panel (or the IDE) so the plugin
  re-reads `~/.jetbrains/acp.json`, then pick `Brokk Code (Local Jar)` in the
  agent server selector.

You can confirm the local jar is in use by tailing `~/.brokk/debug.log` and
looking for entries from the new build.

## Custom config locations

Both tasks honor a Gradle property that points at an alternative config file,
which is useful for testing or for non-default editor installations:

```bash
./gradlew :buildAcpServerJarForJetbrains -PacpJetbrainsConfig=/some/other/acp.json
./gradlew :buildAcpServerJarForZed       -PacpZedConfig=/some/other/settings.json
```

## Why route through `uvx brokk acp --jar`

The tasks point the editor at `uvx brokk acp --jar <path>` rather than
`java -jar <path>`. The wrapper handles three things that a raw `java -jar`
invocation does not:

1. **Stdio hygiene** — the wrapper redirects logging away from stdout, which is
   reserved for JSON-RPC traffic. Any stray stdout output corrupts the protocol
   stream and crashes the client (see
   [acp-native-java-server.md § Logging must avoid stdout](acp-native-java-server.md)).
2. **Brokk key plumbing** — the wrapper passes your stored Brokk API key to the
   subprocess via the appropriate flags, without threading it through the
   editor config's `env` block (which would land plaintext in a config file).
3. **Proxy/vendor flag forwarding** — the wrapper accepts the same
   `--proxy-setting` and `--vendor` flags as the published release and
   translates them into `AcpServerMain` arguments, so the local jar inherits
   the same defaults as the production binary.

A raw `java -jar` invocation can be made to work, but you would have to
replicate all three concerns by hand and any mistake is silent (the editor
just hangs on the first response).

## Schema reference

If you want to inspect or hand-edit the entries the tasks write, this is the
shape they produce.

JetBrains (`~/.jetbrains/acp.json`):

```json
{
  "default_mcp_settings": {},
  "agent_servers": {
    "Brokk Code (Local Jar)": {
      "command": "/absolute/path/to/uvx",
      "args": ["brokk", "acp", "--jar", "/absolute/path/to/brokk-<version>.jar"],
      "env": {}
    }
  }
}
```

Zed (`~/.config/zed/settings.json`, under the same `agent_servers` key the
existing built-in entries use):

```json
{
  "agent_servers": {
    "Brokk Code (Local Jar)": {
      "type": "custom",
      "command": "/absolute/path/to/uvx",
      "args": ["brokk", "acp", "--jar", "/absolute/path/to/brokk-<version>.jar"],
      "env": {}
    }
  }
}
```

The Zed entry needs `"type": "custom"`; JetBrains does not use that field.
