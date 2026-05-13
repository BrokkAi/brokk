//! Compiles `brokk-acp-sandbox` to `wasm32-wasip2` and exposes the
//! resulting artifact path via the `BROKK_ACP_SANDBOX_WASM` env var so
//! `src/sandbox_backend.rs` can `include_bytes!` it.
//!
//! Why a build script rather than a checked-in `.wasm`:
//!   - The wasm artifact must stay in lockstep with the library half of
//!     `brokk-acp-sandbox`. Checking in a `.wasm` invites drift between
//!     the native fallback (linked code) and the sandbox path (loaded
//!     bytes), where a parser fix lands in one but not the other.
//!   - It keeps the repository binary-free, so a `cargo install` from
//!     git always rebuilds both halves from the same source tree.
//!
//! Why we invoke `cargo build` recursively instead of letting cargo
//! resolve the wasm target on its own:
//!   - The host crate (`brokk-acp-rust`) targets the build machine's
//!     architecture, not wasm. Cross-targeting from one cargo invocation
//!     requires per-target dependency tables and would push us into
//!     resolver hell, especially with `wasmtime` (host-only) and
//!     `brokk-acp-sandbox` (both host and wasm). Two invocations keep
//!     the dependency graphs cleanly separated.
//!
//! Failure modes:
//!   - `rustup target add wasm32-wasip2` not run on this host: the build
//!     script fails with a clear message asking the user to install it.
//!   - The sandbox sub-crate fails to compile (e.g. a non-portable dep
//!     was added): the error propagates with the offending stderr from
//!     the nested cargo invocation.

use std::env;
use std::path::PathBuf;
use std::process::Command;

const SANDBOX_CRATE: &str = "brokk-acp-sandbox";
const WASM_TARGET: &str = "wasm32-wasip2";

fn main() {
    println!("cargo:rerun-if-changed=brokk-acp-sandbox/Cargo.toml");
    println!("cargo:rerun-if-changed=brokk-acp-sandbox/src");

    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let sandbox_manifest = manifest_dir.join(SANDBOX_CRATE).join("Cargo.toml");

    // Build to a dedicated target dir so the wasm artifact does not
    // collide with the host build's `target/`. Placing it next to the
    // sub-crate keeps the directory tree intuitive.
    let target_dir = manifest_dir.join(SANDBOX_CRATE).join("target");

    let status = Command::new(env::var("CARGO").unwrap_or_else(|_| "cargo".to_string()))
        .args([
            "build",
            "--release",
            "--bin",
            SANDBOX_CRATE,
            "--target",
            WASM_TARGET,
            "--manifest-path",
        ])
        .arg(&sandbox_manifest)
        .arg("--target-dir")
        .arg(&target_dir)
        // Clear cargo env vars inherited from the outer build so the
        // nested invocation picks its own target/profile.
        .env_remove("CARGO_TARGET_DIR")
        .env_remove("CARGO_BUILD_TARGET")
        .env_remove("CARGO_ENCODED_RUSTFLAGS")
        .env_remove("RUSTFLAGS")
        .status()
        .expect("invoke `cargo build` for brokk-acp-sandbox wasm target");

    if !status.success() {
        eprintln!(
            "cargo:warning=building {SANDBOX_CRATE} for {WASM_TARGET} failed. \
             Make sure `rustup target add {WASM_TARGET}` has been run on this host."
        );
        std::process::exit(1);
    }

    let wasm_path = target_dir
        .join(WASM_TARGET)
        .join("release")
        .join(format!("{SANDBOX_CRATE}.wasm"));

    if !wasm_path.exists() {
        eprintln!(
            "cargo:warning=expected wasm artifact at {} but it was not produced",
            wasm_path.display()
        );
        std::process::exit(1);
    }

    println!(
        "cargo:rustc-env=BROKK_ACP_SANDBOX_WASM={}",
        wasm_path.display()
    );
}
