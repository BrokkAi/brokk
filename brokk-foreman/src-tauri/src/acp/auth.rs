//! Authentication handling for ACP agents.
//!
//! v1 detects the two flavors of `AUTH_REQUIRED` responses from
//! `session/new` and reports them back to the UI; the actual auth flow is
//! deferred:
//!
//! - **Agent Auth** (`type: "agent"`): the agent owns its OAuth flow.
//!   Foreman calls `authenticate` per the ACP spec and waits for the
//!   agent to come back to a usable state.
//! - **Terminal Auth** (`type: "terminal"`): foreman re-launches the
//!   agent with the response's `args`/`env` *replacing* the defaults,
//!   attached to a TTY. v1 spawns Terminal Auth in the user's default
//!   terminal app via `tauri-plugin-shell` and waits for the child to
//!   exit before resuming.
//!
//! This module currently exposes the typed helpers; concrete wiring lands
//! once the basic session lifecycle is verified end-to-end.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum AuthFlow {
    /// The agent will handle its own OAuth flow once `authenticate` is sent.
    Agent,
    /// The agent must be re-launched attached to a TTY with the supplied
    /// argv/env.
    Terminal {
        args: Vec<String>,
        env: std::collections::HashMap<String, String>,
    },
    /// Anything else; the message is surfaced verbatim to the user.
    Other { message: String },
}

/// Map an arbitrary JSON `AUTH_REQUIRED` payload from the agent into our
/// typed enum. Unknown shapes fall through to `Other` so the user still
/// gets some visibility while we add coverage.
pub fn classify(payload: &serde_json::Value) -> AuthFlow {
    match payload.get("type").and_then(|v| v.as_str()) {
        Some("agent") => AuthFlow::Agent,
        Some("terminal") => {
            let args = payload
                .get("args")
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|v| v.as_str().map(|s| s.to_string()))
                        .collect()
                })
                .unwrap_or_default();
            let env = payload
                .get("env")
                .and_then(|v| v.as_object())
                .map(|m| {
                    m.iter()
                        .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                        .collect()
                })
                .unwrap_or_default();
            AuthFlow::Terminal { args, env }
        }
        _ => AuthFlow::Other {
            message: payload.to_string(),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn classifies_agent_flow() {
        assert!(matches!(
            classify(&json!({"type": "agent"})),
            AuthFlow::Agent
        ));
    }

    #[test]
    fn classifies_terminal_flow_with_args_and_env() {
        let payload = json!({
            "type": "terminal",
            "args": ["login", "--device"],
            "env": {"FOO": "bar"}
        });
        match classify(&payload) {
            AuthFlow::Terminal { args, env } => {
                assert_eq!(args, vec!["login", "--device"]);
                assert_eq!(env.get("FOO").map(String::as_str), Some("bar"));
            }
            other => panic!("expected Terminal, got {other:?}"),
        }
    }

    #[test]
    fn unknown_kind_falls_through() {
        assert!(matches!(
            classify(&json!({"type": "unknown"})),
            AuthFlow::Other { .. }
        ));
    }
}
