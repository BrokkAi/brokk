import { listen, type UnlistenFn } from "@tauri-apps/api/event";

// Channel names mirror src-tauri/src/events.rs.
export const ACP_UPDATE_CHANNEL = "acp:update";
export const ACP_SESSION_STATUS_CHANNEL = "acp:session_status";
export const ACP_PERMISSION_CHANNEL = "acp:permission";
export const ACP_STDERR_CHANNEL = "acp:stderr";

export interface AcpUpdate {
  session_id: string;
  update: unknown; // Raw SessionUpdate JSON from the agent.
}

export interface PermissionRequest {
  request_id: string;
  session_id: string;
  tool_call: unknown;
  options: unknown;
}

export type SessionStatus =
  | { kind: "starting"; agent_id: string }
  | { kind: "ready"; agent_id: string; session_id: string }
  | { kind: "auth_required"; agent_id: string; auth_kind: string }
  | { kind: "stopped"; agent_id: string; reason: string }
  | { kind: "failed"; agent_id: string; error: string };

export interface AcpStderr {
  agent_id: string;
  line: string;
}

export function onAcpUpdate(
  handler: (payload: AcpUpdate) => void,
): Promise<UnlistenFn> {
  return listen<AcpUpdate>(ACP_UPDATE_CHANNEL, (event) => handler(event.payload));
}

export function onSessionStatus(
  handler: (payload: SessionStatus) => void,
): Promise<UnlistenFn> {
  return listen<SessionStatus>(ACP_SESSION_STATUS_CHANNEL, (event) =>
    handler(event.payload),
  );
}

export function onPermissionRequest(
  handler: (payload: PermissionRequest) => void,
): Promise<UnlistenFn> {
  return listen<PermissionRequest>(ACP_PERMISSION_CHANNEL, (event) =>
    handler(event.payload),
  );
}

export function onStderr(
  handler: (payload: AcpStderr) => void,
): Promise<UnlistenFn> {
  return listen<AcpStderr>(ACP_STDERR_CHANNEL, (event) => handler(event.payload));
}
