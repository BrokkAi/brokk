//! Tauri commands surfaced to the Svelte frontend.
//!
//! Each handler returns `Result<T, String>` because Tauri serializes the
//! error variant to the frontend as a plain string. Internal modules use
//! typed `thiserror` errors; we map to strings here at the boundary.

use std::path::PathBuf;
use std::sync::Mutex;

use rusqlite::Connection;
use tauri::{AppHandle, State};

use crate::acp::agents::{self, AgentRecord, CustomAgentSpec};
use crate::acp::client::SessionManager;
use crate::acp::installer::{self, InstallOutcome};
use crate::acp::registry::{Registry, RegistryAgent, RegistryCache};
use crate::config::{self, Config};

/// Connection handle shared across commands. The Mutex protects against
/// concurrent invocations from independent frontend command calls.
pub type DbState<'a> = State<'a, Mutex<Connection>>;

fn err<E: std::fmt::Display>(e: E) -> String {
    format!("{e}")
}

#[tauri::command]
pub fn list_agents(db: DbState<'_>) -> Result<Vec<AgentRecord>, String> {
    let conn = db.lock().map_err(|e| format!("db mutex poisoned: {e}"))?;
    agents::list(&conn).map_err(err)
}

#[tauri::command]
pub fn add_custom_agent(spec: CustomAgentSpec, db: DbState<'_>) -> Result<AgentRecord, String> {
    let conn = db.lock().map_err(|e| format!("db mutex poisoned: {e}"))?;
    agents::upsert_custom(&conn, &spec).map_err(err)
}

#[tauri::command]
pub fn uninstall_agent(id: String, db: DbState<'_>) -> Result<(), String> {
    let conn = db.lock().map_err(|e| format!("db mutex poisoned: {e}"))?;
    agents::delete(&conn, &id).map_err(err)
}

#[tauri::command]
pub async fn fetch_registry(force_refresh: bool) -> Result<Vec<RegistryAgent>, String> {
    let data_dir = config::data_dir().ok_or_else(|| "no user data dir on this host".to_string())?;
    let cache = RegistryCache::new(&data_dir);
    let registry: Registry = crate::acp::registry::fetch(&cache, force_refresh)
        .await
        .map_err(err)?;
    Ok(registry.agents)
}

#[tauri::command]
pub async fn install_agent(
    registry_id: String,
    db: State<'_, Mutex<Connection>>,
) -> Result<AgentRecord, String> {
    // Load the matching registry entry from the cache (which must exist —
    // the frontend always calls fetch_registry before install).
    let data_dir = config::data_dir().ok_or_else(|| "no user data dir on this host".to_string())?;
    let cache = RegistryCache::new(&data_dir);
    let registry = crate::acp::registry::fetch(&cache, false)
        .await
        .map_err(err)?;
    let agent = registry
        .agents
        .into_iter()
        .find(|a| a.id == registry_id)
        .ok_or_else(|| format!("agent {registry_id:?} not found in registry"))?;

    let install_root = config::agents_install_dir()
        .ok_or_else(|| "no agents install dir on this host".to_string())?;
    let outcome = installer::install(&agent, &install_root)
        .await
        .map_err(err)?;

    let conn = db.lock().map_err(|e| format!("db mutex poisoned: {e}"))?;
    let record = agents::upsert_registry(&conn, &agent).map_err(err)?;
    if let InstallOutcome::Binary { installed_path } = outcome {
        agents::set_installed_path(&conn, &record.id, &installed_path.to_string_lossy())
            .map_err(err)?;
    }
    agents::get(&conn, &record.id).map_err(err)
}

#[tauri::command]
pub fn get_config(db: DbState<'_>) -> Result<Config, String> {
    let conn = db.lock().map_err(|e| format!("db mutex poisoned: {e}"))?;
    Config::load(&conn).map_err(err)
}

#[tauri::command]
pub fn set_config(config: Config, db: DbState<'_>) -> Result<(), String> {
    let conn = db.lock().map_err(|e| format!("db mutex poisoned: {e}"))?;
    config.save(&conn).map_err(err)
}

#[tauri::command]
pub async fn start_session(
    agent_id: String,
    db: State<'_, Mutex<Connection>>,
    session: State<'_, SessionManager>,
    app: AppHandle,
) -> Result<(), String> {
    // Resolve record + repo path under the DB lock, drop it, then hand off
    // to the async session manager — we don't hold a std::sync::Mutex
    // across await points.
    let (record, repo_path) = {
        let conn = db.lock().map_err(|e| format!("db mutex poisoned: {e}"))?;
        let record = agents::get(&conn, &agent_id).map_err(err)?;
        let cfg = Config::load(&conn).map_err(err)?;
        let repo_path = cfg
            .repo_path
            .ok_or_else(|| "no repo_path configured; set one in Settings first".to_string())?;
        (record, PathBuf::from(repo_path))
    };
    session.start(record, repo_path, app).await.map_err(err)
}

#[tauri::command]
pub async fn send_prompt(text: String, session: State<'_, SessionManager>) -> Result<(), String> {
    session.send_prompt(text).await.map_err(err)
}

#[tauri::command]
pub async fn cancel_session(session: State<'_, SessionManager>) -> Result<(), String> {
    session.cancel().await.map_err(err)
}

#[tauri::command]
pub async fn stop_session(session: State<'_, SessionManager>) -> Result<(), String> {
    session.stop().await.map_err(err)
}

#[tauri::command]
pub async fn respond_permission(
    request_id: String,
    accept: bool,
    option_id: Option<String>,
    session: State<'_, SessionManager>,
) -> Result<(), String> {
    session
        .respond_permission(&request_id, accept, option_id)
        .await
        .map_err(err)
}
