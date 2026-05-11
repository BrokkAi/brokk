// Svelte 5 runes-backed state for the live ACP session pane.
// Streams from Tauri events; the Session route subscribes once on mount.

import type {
  AcpStderr,
  AcpUpdate,
  PermissionRequest,
  SessionStatus,
} from "../events";

interface SessionState {
  status: SessionStatus | null;
  updates: AcpUpdate[];
  pendingPermission: PermissionRequest | null;
  stderrTail: string[];
}

function createSessionStore() {
  let state = $state<SessionState>({
    status: null,
    updates: [],
    pendingPermission: null,
    stderrTail: [],
  });

  return {
    get status() {
      return state.status;
    },
    get updates() {
      return state.updates;
    },
    get pendingPermission() {
      return state.pendingPermission;
    },
    get stderrTail() {
      return state.stderrTail;
    },
    pushUpdate(u: AcpUpdate) {
      state.updates = [...state.updates, u];
    },
    setStatus(s: SessionStatus) {
      state.status = s;
      // Starting a fresh session clears prior updates.
      if (s.kind === "starting") {
        state.updates = [];
        state.pendingPermission = null;
        state.stderrTail = [];
      }
    },
    setPermission(p: PermissionRequest | null) {
      state.pendingPermission = p;
    },
    pushStderr(s: AcpStderr) {
      // Keep only the last 100 lines so the UI doesn't drown.
      const tail = [...state.stderrTail, s.line];
      state.stderrTail = tail.slice(-100);
    },
  };
}

export const sessionStore = createSessionStore();
