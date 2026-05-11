pub mod acp;
pub mod commands;
pub mod config;
pub mod db;
pub mod events;

use std::sync::Mutex;

use tauri::Manager;

use crate::acp::client::SessionManager;

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
        .manage(SessionManager::new())
        .setup(|app| {
            let db_path = config::data_dir()
                .ok_or("could not resolve user data dir for foreman")?
                .join("state.db");
            tracing::info!(path = %db_path.display(), "opening foreman database");
            let conn = db::open(&db_path)?;
            app.manage(Mutex::new(conn));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::list_agents,
            commands::add_custom_agent,
            commands::uninstall_agent,
            commands::fetch_registry,
            commands::install_agent,
            commands::get_config,
            commands::set_config,
            commands::start_session,
            commands::send_prompt,
            commands::cancel_session,
            commands::stop_session,
            commands::respond_permission,
        ])
        .run(tauri::generate_context!())
        .unwrap_or_else(|e| {
            tracing::error!(error = ?e, "foreman failed to start");
            std::process::exit(1);
        });
}
