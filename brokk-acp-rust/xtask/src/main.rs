//! Workspace developer helpers. Run via the `cargo xtask` alias defined in
//! `../.cargo/config.toml`. Mirrors the `:buildAcpServerJarFor{Jetbrains,Zed}` Gradle tasks
//! in the Java side of the brokk repo: build the Rust ACP binary in release mode and
//! rewrite a `Brokk Code (Rust Local)` entry under `agent_servers` in the editor's config.

use anyhow::{Context, Result, ensure};
use clap::{Parser, Subcommand};
use serde_json::{Map, Value, json};
use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Parser)]
#[command(
    name = "xtask",
    about = "Brokk-acp-rust developer helpers (build + editor wiring)."
)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Build the Rust ACP binary in release mode and wire it into ~/.config/zed/settings.json.
    BuildAcpForZed(BuildArgs),
    /// Build the Rust ACP binary in release mode and wire it into ~/.jetbrains/acp.json.
    BuildAcpForJetbrains(BuildArgs),
}

#[derive(Parser)]
struct BuildArgs {
    /// Override the editor config path. Defaults to the editor's standard location under $HOME.
    #[arg(long)]
    config: Option<PathBuf>,
    /// Path or PATH name of the bifrost analyzer binary to pass via --bifrost-binary in the
    /// editor entry's args. The `brokk-acp` binary needs this to find its analyzer backend.
    #[arg(long, default_value = "bifrost")]
    bifrost_binary: String,
}

#[derive(Copy, Clone)]
enum EditorKind {
    Zed,
    Jetbrains,
}

const ENTRY_NAME: &str = "Brokk Code (Rust Local)";

fn main() -> Result<()> {
    let cli = Cli::parse();
    let (kind, args) = match cli.command {
        Commands::BuildAcpForZed(a) => (EditorKind::Zed, a),
        Commands::BuildAcpForJetbrains(a) => (EditorKind::Jetbrains, a),
    };
    run(kind, args)
}

fn workspace_root() -> &'static Path {
    // CARGO_MANIFEST_DIR is `<workspace>/xtask`; its parent is the workspace root.
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("xtask manifest dir must have a parent")
}

fn run(kind: EditorKind, args: BuildArgs) -> Result<()> {
    let root = workspace_root();
    let cargo = std::env::var_os("CARGO").unwrap_or_else(|| "cargo".into());
    let status = Command::new(&cargo)
        .args(["build", "--release", "--bin", "brokk-acp"])
        .current_dir(root)
        .status()
        .context("failed to invoke cargo build")?;
    ensure!(
        status.success(),
        "cargo build --release --bin brokk-acp failed (status: {status})"
    );

    let binary = root.join("target/release/brokk-acp");
    ensure!(
        binary.exists(),
        "expected binary not found at {}",
        binary.display()
    );

    let config_path = args.config.clone().unwrap_or_else(|| match kind {
        EditorKind::Zed => home_dir().join(".config/zed/settings.json"),
        EditorKind::Jetbrains => home_dir().join(".jetbrains/acp.json"),
    });

    write_entry(&config_path, kind, &binary, &args.bifrost_binary)?;
    println!(
        "Updated '{ENTRY_NAME}' in {} -> {}",
        config_path.display(),
        binary.display()
    );
    Ok(())
}

fn home_dir() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .expect("HOME env var must be set")
}

fn write_entry(
    config_path: &Path,
    kind: EditorKind,
    binary: &Path,
    bifrost_binary: &str,
) -> Result<()> {
    let mut root: Value = if config_path.exists() && std::fs::metadata(config_path)?.len() > 0 {
        let txt = std::fs::read_to_string(config_path)
            .with_context(|| format!("reading {}", config_path.display()))?;
        serde_json::from_str(&txt)
            .with_context(|| format!("parsing JSON in {}", config_path.display()))?
    } else {
        json!({})
    };
    let root_obj = root.as_object_mut().with_context(|| {
        format!(
            "config root in {} is not a JSON object",
            config_path.display()
        )
    })?;
    let agent_servers = root_obj
        .entry("agent_servers")
        .or_insert_with(|| json!({}))
        .as_object_mut()
        .context("agent_servers is not a JSON object")?;

    let mut entry = Map::new();
    if matches!(kind, EditorKind::Zed) {
        // Zed requires `type: "custom"` on user-defined agent server entries; JetBrains ignores it.
        entry.insert("type".into(), json!("custom"));
    }
    entry.insert(
        "command".into(),
        json!(binary.to_str().context("binary path is not valid UTF-8")?),
    );
    entry.insert("args".into(), json!(["--bifrost-binary", bifrost_binary]));
    entry.insert("env".into(), json!({}));
    agent_servers.insert(ENTRY_NAME.into(), Value::Object(entry));

    if let Some(parent) = config_path.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("creating parent dir {}", parent.display()))?;
    }
    let pretty = serde_json::to_string_pretty(&root)? + "\n";
    std::fs::write(config_path, pretty)
        .with_context(|| format!("writing {}", config_path.display()))?;
    Ok(())
}
