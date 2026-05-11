import { invoke } from "@tauri-apps/api/core";

// Mirrors the Rust types in src-tauri/src/acp/agents.rs and config.rs.
// Kept loose (Record-shaped distributions) because the registry schema is
// driven by the upstream CDN; tight typing would force a frontend release
// for every new ACP distribution kind.

export type AgentSource = "registry" | "custom";

export interface BinaryTarget {
  archive: string;
  cmd: string;
  args?: string[];
  env?: Record<string, string>;
}

export type Platform =
  | "darwin-aarch64"
  | "darwin-x86_64"
  | "linux-aarch64"
  | "linux-x86_64"
  | "windows-aarch64"
  | "windows-x86_64";

export interface Distribution {
  binary?: Partial<Record<Platform, BinaryTarget>>;
  npx?: { package: string; args?: string[]; env?: Record<string, string> };
  uvx?: { package: string; args?: string[]; env?: Record<string, string> };
}

export interface AgentRecord {
  id: string;
  source: AgentSource;
  name: string;
  version?: string | null;
  distribution: Distribution;
  installed_path?: string | null;
  enabled: boolean;
}

export interface RegistryAgent {
  id: string;
  name: string;
  version: string;
  description: string;
  repository?: string;
  website?: string;
  authors?: string[];
  license?: string;
  icon?: string;
  distribution: Distribution;
}

export interface CustomAgentSpec {
  id: string;
  name: string;
  version?: string;
  cmd: string;
  args?: string[];
  env?: Record<string, string>;
}

export interface Config {
  repo_path?: string | null;
  default_agent_id?: string | null;
}

export const api = {
  listAgents: (): Promise<AgentRecord[]> => invoke("list_agents"),
  addCustomAgent: (spec: CustomAgentSpec): Promise<AgentRecord> =>
    invoke("add_custom_agent", { spec }),
  uninstallAgent: (id: string): Promise<void> =>
    invoke("uninstall_agent", { id }),
  fetchRegistry: (forceRefresh: boolean): Promise<RegistryAgent[]> =>
    invoke("fetch_registry", { forceRefresh }),
  installAgent: (registryId: string): Promise<AgentRecord> =>
    invoke("install_agent", { registryId }),
  getConfig: (): Promise<Config> => invoke("get_config"),
  setConfig: (config: Config): Promise<void> => invoke("set_config", { config }),
  startSession: (agentId: string): Promise<void> =>
    invoke("start_session", { agentId }),
  sendPrompt: (text: string): Promise<void> => invoke("send_prompt", { text }),
  cancelSession: (): Promise<void> => invoke("cancel_session"),
  stopSession: (): Promise<void> => invoke("stop_session"),
  respondPermission: (
    requestId: string,
    accept: boolean,
    optionId?: string,
  ): Promise<void> =>
    invoke("respond_permission", { requestId, accept, optionId }),
};
