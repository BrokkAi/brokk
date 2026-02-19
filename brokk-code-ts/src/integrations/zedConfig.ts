import { mkdir, readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import {
  ExistingBrokkCodeEntryError,
  atomicWriteSettings,
  loadJsonOrJsonc,
  splitLeadingJsonPrefix
} from "./jsonc.js";

function brokkCodeAgentServerConfig(): Record<string, unknown> {
  return {
    favorite_config_option_values: {
      reasoning: ["medium"],
      mode: ["LUTZ"],
      model: ["gpt-5.2"]
    },
    type: "custom",
    command: "brokk-code",
    args: ["acp", "--ide", "zed"],
    env: {}
  };
}

export async function configureZedAcpSettings(options?: {
  force?: boolean;
  settingsPath?: string;
}): Promise<string> {
  const path = options?.settingsPath ?? join(homedir(), ".config", "zed", "settings.json");
  const force = options?.force ?? false;

  let settings: Record<string, unknown> = {};
  if (await readFile(path, "utf-8").catch(() => "")) {
    const raw = await readFile(path, "utf-8");
    const { jsonText } = splitLeadingJsonPrefix(raw);
    const parsed = loadJsonOrJsonc(jsonText || "{}");
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error(`Expected JSON object in ${path}`);
    }
    settings = parsed as Record<string, unknown>;
  }

  const existing = settings.agent_servers;
  if (existing === undefined) {
    settings.agent_servers = {};
  }
  if (!settings.agent_servers || typeof settings.agent_servers !== "object") {
    throw new Error("Expected 'agent_servers' to be a JSON object");
  }

  const agentServers = settings.agent_servers as Record<string, unknown>;
  if (agentServers["Brokk Code"] && !force) {
    throw new ExistingBrokkCodeEntryError(
      "agent_servers['Brokk Code'] already exists; use --force to overwrite it"
    );
  }

  agentServers["Brokk Code"] = brokkCodeAgentServerConfig();
  await mkdir(dirname(path), { recursive: true });
  await atomicWriteSettings(path, settings);
  return path;
}
