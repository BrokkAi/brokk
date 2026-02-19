import { mkdir, readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import {
  ExistingBrokkCodeEntryError,
  atomicWriteSettings,
  loadJsonOrJsonc
} from "./jsonc.js";

export async function configureIntellijAcpSettings(options?: {
  force?: boolean;
  settingsPath?: string;
}): Promise<string> {
  const path = options?.settingsPath ?? join(homedir(), ".jetbrains", "acp.json");
  const force = options?.force ?? false;

  let settings: Record<string, unknown> = {};
  try {
    const raw = await readFile(path, "utf-8");
    const parsed = loadJsonOrJsonc(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error(`Expected JSON object in ${path}`);
    }
    settings = parsed as Record<string, unknown>;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") {
      throw error;
    }
  }

  for (const key of ["default_mcp_settings", "agent_servers"]) {
    const value = settings[key];
    if (value === undefined) {
      settings[key] = {};
      continue;
    }
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw new Error(`Expected '${key}' to be a JSON object`);
    }
  }

  const agentServers = settings.agent_servers as Record<string, unknown>;
  if (agentServers["Brokk Code"] && !force) {
    throw new ExistingBrokkCodeEntryError(
      "agent_servers['Brokk Code'] already exists; use --force to overwrite it"
    );
  }

  agentServers["Brokk Code"] = {
    command: "brokk-code",
    args: ["acp"],
    env: {}
  };

  await mkdir(dirname(path), { recursive: true });
  await atomicWriteSettings(path, settings);
  return path;
}
