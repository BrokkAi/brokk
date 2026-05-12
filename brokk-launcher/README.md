# brokk-launcher

Native Rust launcher binary, packaged as `brokk`. This is **Phase 1** of
the migration from the JVM + Python `brokk-code` stack to a single Rust
launcher (see `../PLANS.md`). In Phase 1 the launcher does nothing
interesting on its own: it locates the legacy `brokk` install and
forwards every invocation to it via `execvp` (Unix) or process spawn
(Windows).

This crate is intentionally **standalone** -- it has its own
`Cargo.toml` and `Cargo.lock` and is not part of any cargo workspace.
The other Rust crates in this repo (`brokk-acp-rust`, `brokk-tui-rust`)
are likewise independent.

## Build

From this directory:

```
cargo build --release
```

Output: `target/release/brokk` (or `brokk.exe` on Windows).

## Legacy-brokk discovery order

Resolved at startup, first match wins:

1. **`$BROKK_LEGACY_BIN`** -- absolute path to the legacy executable.
   Set this when you have a packaged legacy install in an unusual
   location, or to override the other fallbacks.
2. **`uv run brokk` inside a sibling `brokk-code/`** -- found by
   walking up from `argv[0]` until a `brokk-code/pyproject.toml` is
   located. The launcher invokes `uv run --directory <dir> brokk`.
   This is the dev-checkout path; build the launcher inside the repo
   and `target/release/brokk` resolves the source tree automatically.
3. **`$HOME/.local/share/brokk/legacy-brokk`** -- a fixed path the
   installer will populate in Phase 2 once the launcher ships in
   release artifacts.

If none of the three match, the launcher prints an error and exits
`127`.

## Adding a subcommand dispatch (Phase 3 onwards)

Phase 1 keeps this crate intentionally tiny: there is no native
subcommand handling. Phase 3 introduces `src/dispatch.rs` (see
PLANS.md §3.2) where each subcommand is mapped to a handler:

```text
Native(fn(&Args) -> Result<()>)
Spawn { bin: SiblingBinary, transform: fn(&Args) -> Vec<OsString> }
Legacy
```

The default for new subcommands is `Legacy` (the current Phase 1
behavior). Native and Spawn entries are added one at a time with a CI
test that asserts identical output against the legacy path.

## License

GPL-3.0 (matches the rest of the brokk tree per PLANS.md risk #8).
