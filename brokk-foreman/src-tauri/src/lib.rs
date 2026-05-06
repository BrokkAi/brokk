pub mod acp;
pub mod config;
pub mod db;
pub mod issues;
pub mod llm;
pub mod worktree;

use std::sync::Mutex;

use tauri::Manager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info,brokk_foreman=debug")),
        )
        .init();

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .setup(|app| {
            let db_path = config::data_dir()
                .ok_or("could not resolve user data dir for foreman")?
                .join("state.db");
            tracing::info!(path = %db_path.display(), "opening foreman database");
            let conn = db::open(&db_path)?;
            app.manage(Mutex::new(conn));
            Ok(())
        })
        .run(tauri::generate_context!())
        .unwrap_or_else(|e| {
            tracing::error!(error = ?e, "foreman failed to start");
            std::process::exit(1);
        });
}
