# Plan: Migrate brokk to a native Rust launcher (delete Java)

Replace the JVM stack (Gradle modules `app/`, `brokk-core/`, `brokk-shared/`,
`errorprone-checks/`, `treesitter-provider/`) and the Python `brokk-code/`
wrapper that fronts it with a small native Rust launcher. End state: a single
`brokk` binary that dispatches to `brokk-acp-rust` (the only ACP server) and
`brokk-tui-rust` (a pure ACP client), with editor-config, auth, git, GitHub,
session, and provider subcommands ported to Rust. Zero Java in the tree. MCP
shim is external to brokk-code and out of scope.

In this document, "brokk-code (Java)" is shorthand for the JVM stack above
plus the Python `brokk-code/` package that currently wraps it. All of it goes.

## Status

Not started. The plan below lands in five phases over multiple PRs. Each
phase boundary leaves the repo shippable; the launcher is introduced as a
no-op pass-through, gradually takes over subcommands, becomes the sole path,
then Java is deleted in one PR with a `pre-java-deletion` tag for revert.

## Current state

Gathered from a focused 10-minute inventory of the repo at the time of
writing.

### Build and packaging

- Root Gradle build (`build.gradle.kts`, `settings.gradle.kts`), toolchain
  JBR 21, JavaFX, `shadow` + `application` plugins.
- JVM modules: `app`, `brokk-core`, `brokk-shared`, `errorprone-checks`,
  `treesitter-provider`.
- Output: `app/build/libs/brokk-<ver>.jar` (shadowJar), released to
  `BrokkAi/brokk-releases`.

### `brokk` command entry point today

- Pip-installed script from `brokk-code/pyproject.toml`:
  `brokk = "brokk_code.__main__:main"`.
- Subcommands argparse advertises today: `resume`, `sessions`, `acp`,
  `acp-native`, `mcp`, `mcp-core`, `bifrost`, `install`, `provider`
  (incl. `custom`), `commit`, `exec`, `issue` (`create`/`diagnose`/`solve`),
  `pr` (`create`/`review`), `login`, `logout`, `github`
  (`login`/`status`/`logout`), `version`.
- The Python CLI invokes the JVM via jbang
  (`ai.brokk.executor.HeadlessExecutorMain`,
  `ai.brokk.mcpserver.BrokkExternalMcpServer`) and uses
  `_EXECUTOR_JAR_BASE_URL = https://github.com/BrokkAi/brokk-releases/releases/download`.
- Repo root also ships `cli` (dev helper -- runs `ai.brokk.cli.BprCli`
  out of the shadow jar) and `run-tui` (rebuilds shadowJar, then
  `uv run brokk ... --jar <JAR>`).

### Java ACP server (to delete in Phase 5)

Lives at `app/src/main/java/ai/brokk/acp/`:

- `AcpServerMain.java`, `BrokkAcpAgent.java`, `BrokkAcpRuntime.java`
- `AcpProtocol.java`, `AcpConsoleIO.java`, `AcpFileBridge.java`
- `AcpPromptContext.java`, `AcpRequestContext.java`
- `PermissionGate.java`, `PermissionMode.java`, `PermissionRules.java`
- `DangerousCommand.java`, `SafeCommand.java`, `ThreadLocalScope.java`
- `package-info.java`

### Rust modules already in tree

- `brokk-acp-rust/` -- crate `brokk-acp-rust` 0.2.0, binary `brokk-acp`,
  edition 2024, LGPL-3.0. Has its own `xtask/`.
- `brokk-tui-rust/` -- crate `brokk-tui` 0.0.1, binary `brokk-tui`,
  edition 2024, LGPL-3.0. ACP client only.

### Install and release surface

- `installer/install.sh` and `installer/install.ps1` already install
  `bifrost` + `brokk-acp` from GitHub releases (`BrokkAi/bifrost`,
  `BrokkAi/brokk` tag prefix `brokk-acp-rust-`). Default install dir
  `$HOME/.local/bin` on Unix, `$LOCALAPPDATA\brokk\bin` on Windows.
- `scripts/install-cli.sh` -- legacy jbang-based JVM install
  (`jbang app install brokk@brokkai/brokk-releases`).
- `scripts/update-jbang-catalog.sh`, `jbang-catalog.json` -- JVM release
  catalog.
- GitHub Actions: `release.yml`, `jdeploy.yml`, `java-ci.yml`,
  `brokk-code-ci.yml`, `brokk-code-pypi.yml`, `brokk-tui-ci.yml`,
  `rust-acp.yml`, `rust-acp-release.yml`, `foreman.yml`,
  `daily-full-test.yml`, `daily-perf-test.yml`, `sync-snapshot.yml`.
- No Homebrew formula found in this repo -- confirm tap location (likely
  `BrokkAi/homebrew-tap`).

### Config and state

- `~/.brokk/brokk.properties` (mirrors
  `ai.brokk.util.BrokkConfigPaths.java`).
- `~/.brokk/settings.json`, prompt history.
- Editor configs written by Python: `avante_config.py`,
  `intellij_config.py`, `nvim_config.py`, `zed_config.py`.

## Goal

A single `brokk` binary on the user's PATH that:

1. Is built from a Rust crate `brokk-launcher` in this repo.
2. For every existing subcommand, runs natively (Rust) or `exec`s a
   sibling Rust binary (`brokk-acp`, `brokk-tui`, `bifrost`, the external
   MCP shim).
3. Reads and writes the same on-disk config and state as today
   (`~/.brokk/brokk.properties`, `~/.brokk/settings.json`, session
   files, editor configs).
4. Ships from the same `installer/install.sh` and `installer/install.ps1`
   that already install `brokk-acp` and `bifrost`.
5. Emits the same telemetry event names and properties (with an added
   `runtime: "rust"` property for side-by-side comparison through the
   cutover, then optional).

## Non-goals

- Any change to the external MCP shim. It lives outside `brokk-code` and
  is untouched.
- Porting `brokk-code`'s Python Textual TUI to Rust feature-for-feature.
  `brokk-tui-rust` is a pure ACP client; whatever it gives the user is
  what the user gets.
- `bifrost`. It already ships as a separate Rust binary from
  `BrokkAi/bifrost`. The launcher's `bifrost` subcommand `exec`s it.
- `brokk-vscode/`, `brokk-foreman/`, `claude-plugin/`, `frontend-mop/`.
  Out of scope. Phase 4 keeps their editor-config touchpoints working.
- Concurrent JVM and Rust paths long-term. Phase 4 keeps a legacy
  fallback for one release as a support escape hatch; Phase 5 removes it.

---

## Phase 1: Land the launcher as a no-op pass-through

### 1.1 Goal

Introduce a Rust crate that builds a binary named `brokk` and forwards
every invocation to today's Python/JVM `brokk` via `execvp`.

### 1.2 Deliverables

- New crate `brokk-launcher/`:
  - `Cargo.toml` -- `name = "brokk-launcher"`, `[[bin]] name = "brokk"`,
    edition 2024, LGPL-3.0.
  - `src/main.rs` -- argv parse; locate the legacy `brokk` (search
    `$BROKK_LEGACY_BIN`, then `uv run brokk` inside `brokk-code/`, then
    a hashed location under `~/.local/share/brokk/legacy-brokk`);
    `execvp` it with all argv passed through.
  - `README.md` -- what the launcher is and how to add a subcommand
    dispatch.
- Root `Cargo.toml` workspace listing `brokk-acp-rust`, `brokk-tui-rust`,
  `brokk-launcher`. Keep `brokk-acp-rust`'s nested `xtask` working
  (`default-members = ["."]`).
- `.github/workflows/launcher-ci.yml` -- `cargo fmt --check`,
  `cargo clippy -D warnings`, `cargo build --release` on
  `x86_64-unknown-linux-gnu`, `aarch64-apple-darwin`,
  `x86_64-pc-windows-msvc`. (Intel mac added if and when `bifrost` does;
  today's installer rejects it.)

### 1.3 Exit criteria

- `cargo build --release -p brokk-launcher` succeeds on all three CI
  targets.
- On a host with the legacy install, `target/release/brokk version`
  produces the same output as `uv run brokk version`.
- Existing CI workflows (`java-ci.yml`, `brokk-code-ci.yml`,
  `rust-acp.yml`, `brokk-tui-ci.yml`) all stay green; no changes to the
  existing release pipeline.

### 1.4 Rollback

Revert the new crate commit. Nothing else in the pipeline touches it.

### 1.5 User-visible

Nothing. The launcher binary exists in CI artifacts only.

---

## Phase 2: Distribute the launcher; still delegating

### 2.1 Goal

`installer/install.sh` and `installer/install.ps1` fetch the new `brokk`
binary from GitHub releases and place it on PATH alongside `brokk-acp`
and `bifrost`. The launcher continues to delegate to the JVM/Python
stack.

### 2.2 Deliverables

- New `.github/workflows/launcher-release.yml` -- triggered on tags
  `brokk-launcher-*`; builds release binaries for the three targets;
  uploads to the `BrokkAi/brokk` releases page as
  `brokk-<ver>-<target>(.exe)?`.
- macOS notarization in `launcher-release.yml` using `xcrun notarytool`
  with a stored Apple Developer ID secret; hardened runtime;
  entitlements file at `brokk-launcher/macos/brokk.entitlements`.
- Windows: ship unsigned for the first release behind a docs note about
  SmartScreen; sign with Authenticode in a follow-up once a cert is
  procured.
- Extend `installer/install.sh` and `installer/install.ps1`:
  1. Fetch `brokk-<ver>-<target>` and place at `<install-dir>/brokk`
     (or `brokk.exe`).
  2. Detect a pre-existing `brokk` on PATH that points at jbang
     (`~/.jbang/bin/brokk*`) or at a `brokk.cmd` from a prior install,
     and print a one-line warning telling the user to remove or shadow
     it.
- `installer/README.md` updated to list the three binaries.

### 2.3 Exit criteria

- A fresh `curl -fsSL .../install.sh | bash` on macOS aarch64 and Linux
  x86_64 yields three binaries on PATH: `brokk`, `brokk-acp`, `bifrost`.
- `brokk version` works on a clean machine that also has the legacy
  Python `brokk` reachable (launcher locates and delegates).
- macOS binary passes `spctl --assess --type execute` after
  notarization.
- Windows binary runs from `cmd.exe` and `pwsh`; documented SmartScreen
  behavior is acceptable.

### 2.4 Rollback

Pin `installer/install.sh` and `installer/install.ps1` to the previous
tag; the launcher download step is removed; bifrost + brokk-acp install
path unchanged.

### 2.5 User-visible

The installer drops a `brokk` binary, but runtime behavior is identical
because the launcher delegates.

---

## Phase 3: Native dispatch for the hot path

### 3.1 Goal

The launcher stops delegating for subcommands that already have Rust
implementations. Default invocation (`brokk` with no args, `brokk acp`,
`brokk acp-native`, `brokk resume`, `brokk sessions`, `brokk version`)
becomes pure Rust: `brokk-launcher` spawns `brokk-acp` and `execs`
`brokk-tui` (or wires them via stdio per existing brokk-acp-rust
expectations).

### 3.2 Deliverables

- `brokk-launcher/src/dispatch.rs` -- table mapping subcommand to a
  handler:

  ```rust
  enum Dispatch {
      Native(fn(&Args) -> Result<()>),
      Spawn { bin: SiblingBinary, transform: fn(&Args) -> Vec<OsString> },
      Legacy, // forward to the Python/JVM brokk
  }

  enum SiblingBinary { BrokkAcp, BrokkTui, Bifrost, McpShim }
  ```

  Initial native/spawn entries: default, `acp`, `acp-native`, `resume`,
  `sessions`, `version`.
- `brokk-launcher/src/discovery.rs` -- locate sibling binaries (dir of
  `argv[0]` first, then PATH).
- `BROKK_FORCE_LEGACY=1` env override that flips every entry back to
  `Legacy` for support escalations.
- `~/.brokk/launcher.toml` per-subcommand override:

  ```toml
  [dispatch]
  acp = "legacy"
  ```

- Integration test in `launcher-ci.yml`: against a published `brokk-acp`
  release binary, run `brokk acp --help` and `brokk version` and assert
  exit 0 plus expected substrings.

### 3.3 Exit criteria

- `brokk` with no args launches `brokk-tui` connected to `brokk-acp`
  with no Java or Python in the process tree
  (`pgrep -f 'java|python|uv'` returns nothing for the session).
- `BROKK_FORCE_LEGACY=1 brokk` restores the legacy path identically.
- All non-migrated subcommands (`install`, `provider`, `commit`, `exec`,
  `issue`, `pr`, `login`, `logout`, `github`, `bifrost`, `mcp`,
  `mcp-core`) still work via `Legacy`.

### 3.4 Rollback

Ship a patch release that sets every dispatch entry back to `Legacy`,
or instruct affected users to set `BROKK_FORCE_LEGACY=1`. No installer
changes required.

### 3.5 User-visible

The TUI experience is now end-to-end Rust for the hot path. Behavior
should be indistinguishable; startup latency improves measurably.

---

## Phase 4: Port remaining subcommands; sever the JVM/Python wire

### 4.1 Goal

Every `brokk` subcommand runs without invoking `java`, `python`, `uv`,
or `jbang`. The legacy backend stops being on the runtime path.

### 4.2 Deliverables

Per-subcommand Rust modules under `brokk-launcher/src/cmd/`:

| Module | Replaces |
|---|---|
| `install.rs` | Python `install` subcommand + editor-config writers |
| `provider.rs` | `provider` (incl. `provider custom`) |
| `commit.rs` | `commit` |
| `exec.rs` | `exec` |
| `issue.rs` | `issue create` / `issue diagnose` / `issue solve` |
| `pr.rs` | `pr create` / `pr review` |
| `auth.rs` | `login`, `logout`, `github login`, `github status`, `github logout` |
| `bifrost.rs` | thin wrapper that `exec`s `bifrost` with the existing version pin |
| `mcp.rs`, `mcp_core.rs` | thin wrappers that `exec` the external MCP shim |
| `version.rs` | `version` |

Editor-config ports under `brokk-launcher/src/editor_config/`:

- `avante.rs`, `intellij.rs`, `nvim.rs` (incl. `nvim_init_patch.rs`,
  `nvim_avante.rs`), `zed.rs`.

Shared modules:

- `brokk-launcher/src/settings.rs` -- owns `~/.brokk/brokk.properties`
  and `~/.brokk/settings.json` read/write. Must be byte-compatible with
  files written by today's Python (preserve property ordering /
  comments where possible -- evaluate the `java-properties` crate vs. a
  thin hand-rolled parser).
- `brokk-launcher/src/git.rs` -- port of `git_utils.py`
  (`infer_github_repo_from_remote`, etc.).
- `brokk-launcher/src/session.rs` -- read existing session files; if a
  session was written by Java's `SessionManager` / `SessionRegistry`,
  convert on first read (one-shot) and rewrite in the Rust format.
- `brokk-launcher/src/telemetry.rs` -- emit the same event names and
  properties as the JVM and Python paths. Add a property
  `runtime: "rust"` for the cutover window.

Compatibility golden tests in `brokk-launcher/tests/compat/`:

- 20-30 captured `brokk.properties` and `settings.json` snapshots:
  round-trip parse plus write equals input.
- Captured editor-config snapshots (zed, intellij, nvim/codecompanion,
  nvim/avante): write produces byte-equal output.

### 4.3 Exit criteria

- Every `brokk <subcommand> --help` runs without spawning a JVM or a
  Python interpreter. Verify by stracing/dtracing the process tree on
  Linux and macOS, and by `Get-Process` snapshots on Windows.
- All compat golden tests pass.
- Telemetry parity verified against a captured Java baseline; only the
  `runtime` property differs.
- `BROKK_FORCE_LEGACY=1` still works as escape hatch -- legacy path is
  kept buildable for one release.
- `README.md`, `installer/README.md`, and `docs/**` no longer instruct
  users to install Java or jbang.

### 4.4 Rollback

Pin installer to the previous launcher version. Users on the new
launcher set `BROKK_FORCE_LEGACY=1` while a fix is cut.

### 4.5 User-visible

Last release where `~/.local/bin/brokk` still has a working JVM
fallback. No required behavior change.

---

## Phase 5: Scorched earth

### 5.1 Goal

Delete every Java module, every Python `brokk-code` source file, every
JVM-era release artifact, every JVM-era CI workflow, and every doc
referencing them. Pick a single license/SPDX for the released binary.

### 5.2 Deletions

Gradle modules and build files (entire directories):

- `app/`
- `brokk-core/`
- `brokk-shared/`
- `errorprone-checks/`
- `treesitter-provider/`
- `frontend-mop/` -- confirm `brokk-tui-rust` does not depend on it;
  delete if so.
- Root: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`,
  `gradle/`, `gradlew`, `gradlew.bat`, `.gradle/`.

Python `brokk-code`:

- `brokk-code/` (entire directory: `pyproject.toml`, `uv.lock`,
  `brokk_code/`, `tests/`, `pytest`, `pytest_output.txt`).

JVM/Python dev helpers in repo root:

- `cli` (BprCli launcher)
- `run-tui` (rebuild-shadowJar-then-`uv run brokk`)
- `jbang-catalog.json`
- `hello.py`
- `installsplash.png`, `launcher-splash.html`, `splash.gif`, `icon.png`
  -- keep only if reused by the new launcher's brand assets.

Installer and scripts:

- `scripts/install-cli.sh` (jbang-based)
- `scripts/update-jbang-catalog.sh`
- In `installer/install.sh` and `installer/install.ps1`: remove every
  JVM/jbang/Python reference; keep only the three Rust binaries.

GitHub Actions workflows:

- `.github/workflows/java-ci.yml`
- `.github/workflows/release.yml` (replace with a Rust-only `release.yml`
  if not already done)
- `.github/workflows/jdeploy.yml`
- `.github/workflows/brokk-code-ci.yml`
- `.github/workflows/brokk-code-pypi.yml`
- `.github/workflows/daily-full-test.yml` and `daily-perf-test.yml` --
  audit; delete if JVM-bound, port to Rust otherwise.
- `.github/workflows/sync-snapshot.yml` -- audit; likely JVM-specific.

Docs and repo metadata:

- `AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md`, `README.md` -- rewrite to
  remove all JVM/Python references.
- `docs/**` -- sweep.
- `.idea/` -- IntelliJ project files for the JVM tree.
- `NOTICE.txt` -- regenerate from the Rust dep tree.
- `LICENSE.txt` -- align with the chosen final license. The Java/Python
  surface declares GPL-3.0-only (`brokk-code/pyproject.toml` classifier);
  the Rust crates declare LGPL-3.0. Pick one before this phase merges.

Launcher tidy-up:

- Remove `BROKK_FORCE_LEGACY` env handling in
  `brokk-launcher/src/main.rs`.
- Remove `~/.brokk/launcher.toml` `dispatch.* = "legacy"` parsing.
- Phase 4 session-format migrator -- keep for one more release after
  the deletion, then remove.

### 5.3 Exit criteria

Filesystem checks:

```
find . -name '*.java' -o -name '*.kt' -o -name '*.kts' \
  -o -name '*.gradle' -o -name 'pyproject.toml' \
  -not -path './brokk-acp-rust/*' \
  -not -path './brokk-tui-rust/*' \
  -not -path './brokk-launcher/*'
# expected: empty

find . -name 'jbang-catalog.json'
# expected: empty

find .github/workflows \( -name '*java*' -o -name '*jdeploy*' \
  -o -name '*pypi*' \)
# expected: empty
```

Reviewer greps (all should return only `CHANGELOG*` / `docs/history/**`
matches):

```
git grep -inE '\bjava\b'                  -- ':!CHANGELOG*' ':!docs/history/**'
git grep -inE 'gradle|gradlew'            -- ':!CHANGELOG*' ':!docs/history/**'
git grep -inE 'jbang'                     -- ':!CHANGELOG*' ':!docs/history/**'
git grep -inE 'openjdk|JetBrainsRuntime|JBR'
git grep -inE 'javafx|shadowJar|errorprone'
git grep -inE 'jdeploy'
git grep -inE 'pyproject|uv\.lock|\buv run\b|hatchling'
git grep -inE 'brokk-code'                -- ':!CHANGELOG*' ':!docs/history/**'
git grep -inE 'BprCli|HeadlessExecutorMain|BrokkExternalMcpServer'
git grep -inE 'ai\.brokk\.'
git grep -inE '\.jar\b'
git grep -inE 'JAVA_HOME|BROKK_JVM_ARGS|BROKK_DEBUG'
```

Pipeline:

- The release workflow produces only Rust binaries (`brokk`, `brokk-acp`)
  and a source tarball.
- No workflow references `setup-java`, `gradle/actions/setup-gradle`,
  `actions/setup-python`, `astral-sh/setup-uv`, `jdeploy`, or
  `pypa/gh-action-pypi-publish`.

### 5.4 Rollback

Tag `pre-java-deletion` immediately before the deletion PR merges. A
single revert PR restores the previous tree intact. The release pipeline
returns from a frozen snapshot.

### 5.5 User-visible

Nothing changes at runtime -- by this point the Rust path has been the
only path for a release. Binaries are smaller, installs no longer pull a
JDK or Python, the SBOM clears out a couple hundred Java/Python deps.

---

## Risks and open questions

1. **macOS signing and notarization.** The new `brokk` is a Mach-O
   replacing what was effectively a JAR plus the user's JDK. Gatekeeper
   will block on first run unless we notarize. Need: Apple Developer ID
   in GitHub secrets, `notarytool` step, hardened runtime entitlements
   (we likely do not need `allow-jit`; verify which JIT/codesign flags
   the JNI/JavaFX bundle relied on so we know what we are dropping).
2. **Windows binary naming and SmartScreen.** Today the jbang-installed
   shim is `brokk.cmd`. The Rust binary is `brokk.exe`. If both end up
   on PATH in different directories, lookup order decides. Phase 2
   installer detects `brokk.cmd` and prompts to remove. Plan unsigned
   for the first release behind a docs note; sign with Authenticode
   later.
3. **Homebrew formula.** No formula was found in this repo -- confirm
   whether `BrokkAi/homebrew-tap` exists. If it does, the formula
   switches from `depends_on "openjdk@21"` plus jbang to a `url`
   pointing at the new Rust release tarball with `sha256` and
   `bin.install "brokk"`. If it does not, decide whether to add one in
   Phase 2 or rely on `installer/install.sh`.
4. **Config and state dir compatibility.** `~/.brokk/brokk.properties`
   is a Java-style properties file; round-tripping comments and key
   ordering is non-obvious. Use a library that preserves layout or
   document that comments may be lost. `~/.brokk/settings.json` is
   trivial. Session state (`SessionManager`/`SessionRegistry`) may have
   a non-trivial format -- inspect before Phase 4 and write a migrator.
5. **JVM-era environment variables.** `JAVA_HOME`, `BROKK_DEBUG`,
   `BROKK_DEBUG_PORT`, `BROKK_DEBUG_SUSPEND`, any `BROKK_JVM_ARGS` users
   may have set become dead. Print a one-line warning on startup for one
   release after Phase 4, then drop silently.
6. **Telemetry continuity.** Confirm the telemetry sink and event schema
   for the JVM app (search `app/src/main/java/ai/brokk/metrics/`). The
   Rust port must emit identical event names and properties; otherwise
   dashboards break across the cutover. Add `runtime: "rust" | "jvm"`
   property for side-by-side comparison during Phases 3-4.
7. **Autoupdate.** jbang auto-updates via catalog refresh. Native
   binaries do not. Options: (a) `brokk update` subcommand that re-runs
   `installer/install.sh`; (b) rely on Homebrew/winget/apt; (c)
   background self-update (rejected -- surprise updates are user-hostile
   in a CLI). Default to (a); document (b) as the happy path.
8. **License and SPDX.** Java/Python tree declares GPL-3.0-only; the
   existing Rust crates declare LGPL-3.0. Pick one for the unified
   binary before Phase 5 lands. Update `LICENSE.txt`, `NOTICE.txt`, and
   every Cargo.toml together.
9. **jbang catalog and legacy installs in the wild.** Many users have
   `brokk` via `jbang app install brokk@brokkai/brokk-releases`. After
   deletion, the catalog still resolves to old JARs. Decide: freeze the
   catalog at the last JVM tag (it lives in `BrokkAi/brokk-releases`,
   not here) vs. publish a final "deprecation notice" JAR that prints a
   migration message and exits. The catalog file in this repo is
   deleted regardless.
10. **jDeploy native installers.** `jdeploy.yml` produces `.dmg`,
    `.msi`, `.appimage` today. These channels shut down in Phase 5. If
    users get brokk via them, plan a deprecation notice one release
    before deletion.
11. **IDE plugin integration points.** `brokk-vscode/`, `brokk-foreman/`,
    `claude-plugin/` may shell out to the Python `install` subcommand or
    to specific config writers. Phase 4 ports the editor-config writers
    verbatim; verify no plugin shells out to `java` or `python`
    directly.

## Verification (end-to-end)

Per phase, in addition to the per-phase exit criteria above:

1. After Phase 1: build the launcher locally, verify `brokk version`
   delegates correctly on a host with the legacy install.
2. After Phase 2: fresh install from `installer/install.sh` on macOS
   aarch64 and Linux x86_64; `brokk` is on PATH; `brokk acp` and
   `brokk version` work; macOS binary survives `spctl --assess`.
3. After Phase 3: `pgrep -f 'java|python|uv'` is empty during a TUI
   session; `BROKK_FORCE_LEGACY=1 brokk` restores the legacy path
   identically.
4. After Phase 4: every documented subcommand works without a JVM or
   Python in the process tree; `brokk install --rust` (or equivalent
   updated invocation) writes byte-identical editor configs vs. the
   captured baseline; telemetry events match the JVM baseline.
5. After Phase 5: all greps and `find` checks in section 5.3 pass;
   release workflow uploads only Rust binaries; documentation contains
   no JVM/Python install instructions outside `CHANGELOG*` and
   `docs/history/**`.

Automated, per PR:

- `cargo build --release -p brokk-launcher`,
  `cargo clippy --all-targets -- -D warnings`, `cargo test` all clean on
  the three target triples.
- Compat golden tests under `brokk-launcher/tests/compat/` pass.

## Scope cut summary

Keep: Rust launcher binary; native dispatch for ACP/TUI/version/resume/
sessions; full Rust port of remaining subcommands; byte-compatible
config and state; preserved install layout; deletion of all Java and
Python `brokk-code`.

Cut (out of scope here): MCP shim changes; `bifrost` changes;
brokk-foreman / brokk-vscode / claude-plugin changes beyond
editor-config compatibility; Intel mac support beyond what `bifrost`
already provides; in-app autoupdate UI beyond a `brokk update`
subcommand.
