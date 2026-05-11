// Svelte 5 runes-based reactive state for the agents list. Re-loaded
// after install/uninstall/add-custom; the rest of the UI reads via
// `agentsStore.list`.

import { api, type AgentRecord } from "../api";

interface AgentsState {
  list: AgentRecord[];
  loading: boolean;
  error: string | null;
}

function createAgentsStore() {
  let state = $state<AgentsState>({ list: [], loading: false, error: null });

  async function refresh() {
    state.loading = true;
    state.error = null;
    try {
      state.list = await api.listAgents();
    } catch (e) {
      state.error = `${e}`;
    } finally {
      state.loading = false;
    }
  }

  return {
    get list() {
      return state.list;
    },
    get loading() {
      return state.loading;
    },
    get error() {
      return state.error;
    },
    refresh,
  };
}

export const agentsStore = createAgentsStore();
