import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { configureIntellijAcpSettings } from "./integrations/intellijConfig.js";
import { ExistingBrokkCodeEntryError } from "./integrations/jsonc.js";
import { configureZedAcpSettings } from "./integrations/zedConfig.js";
import { runAcpServer } from "./acp/server.js";
import { runInteractiveApp } from "./ui/runInteractiveApp.js";
import { resolveWorkspaceDir } from "./workspace.js";
import { hasTasks, loadLastSessionId, sessionZipPath } from "./state/sessionPersistence.js";

export interface RuntimeArgs {
  workspace: string;
  vendor?: string;
  jar?: string;
  executorVersion?: string;
  executorSnapshot: boolean;
  session?: string;
  resumeSession: boolean;
}

interface ParsedArgs {
  command?: "acp" | "install" | "resume";
  runtime: RuntimeArgs;
  ide?: "intellij" | "zed";
  installTarget?: "zed" | "intellij";
  force?: boolean;
  resumeSessionId?: string;
}

function parseArgs(argv: string[]): ParsedArgs {
  const runtime: RuntimeArgs = {
    workspace: ".",
    executorSnapshot: true,
    resumeSession: false
  };

  const parsed: ParsedArgs = { runtime };
  let i = 0;
  while (i < argv.length) {
    const token = argv[i];
    if (token === "acp") {
      parsed.command = "acp";
      i += 1;
      continue;
    }
    if (token === "install") {
      parsed.command = "install";
      parsed.installTarget = (argv[i + 1] as "zed" | "intellij") ?? undefined;
      i += 2;
      continue;
    }
    if (token === "resume") {
      parsed.command = "resume";
      parsed.resumeSessionId = argv[i + 1];
      i += 2;
      continue;
    }

    if (token === "--workspace") {
      runtime.workspace = argv[i + 1] ?? runtime.workspace;
      i += 2;
      continue;
    }
    if (token === "--vendor") {
      runtime.vendor = argv[i + 1];
      i += 2;
      continue;
    }
    if (token === "--jar") {
      runtime.jar = argv[i + 1];
      i += 2;
      continue;
    }
    if (token === "--executor-version") {
      runtime.executorVersion = argv[i + 1];
      i += 2;
      continue;
    }
    if (token === "--executor-snapshot") {
      runtime.executorSnapshot = true;
      i += 1;
      continue;
    }
    if (token === "--executor-stable") {
      runtime.executorSnapshot = false;
      i += 1;
      continue;
    }
    if (token === "--session") {
      runtime.session = argv[i + 1];
      i += 2;
      continue;
    }
    if (token === "--resume") {
      runtime.resumeSession = true;
      i += 1;
      continue;
    }
    if (token === "--ide") {
      parsed.ide = (argv[i + 1] as "intellij" | "zed") ?? "intellij";
      i += 2;
      continue;
    }
    if (token === "--force") {
      parsed.force = true;
      i += 1;
      continue;
    }

    throw new Error(`Unknown argument: ${token}`);
  }

  return parsed;
}

export async function main(argv: string[]): Promise<void> {
  const args = parseArgs(argv);

  if (args.command === "install") {
    if (!args.installTarget) {
      throw new Error("Missing install target: zed|intellij");
    }
    try {
      if (args.installTarget === "zed") {
        const path = await configureZedAcpSettings({ force: args.force ?? false });
        console.log(`Configured Zed ACP integration in ${path}`);
      } else {
        const path = await configureIntellijAcpSettings({ force: args.force ?? false });
        console.log(`Configured IntelliJ ACP integration in ${path}`);
      }
      return;
    } catch (error) {
      if (error instanceof ExistingBrokkCodeEntryError || error instanceof Error) {
        throw error;
      }
      throw new Error(String(error));
    }
  }

  const workspacePath = resolve(args.runtime.workspace);
  if (!existsSync(workspacePath)) {
    throw new Error(`Workspace path does not exist: ${workspacePath}`);
  }
  const workspaceDir = resolveWorkspaceDir(workspacePath);

  if (args.command === "acp") {
    await runAcpServer({
      workspaceDir,
      jarPath: args.runtime.jar,
      executorVersion: args.runtime.executorVersion,
      executorSnapshot: args.runtime.executorSnapshot,
      ide: args.ide,
      vendor: args.runtime.vendor
    });
    return;
  }

  let sessionId = args.runtime.session;
  let resumeSession = args.runtime.resumeSession;
  if (args.command === "resume") {
    sessionId = args.resumeSessionId;
    resumeSession = false;
  }

  if (resumeSession && !sessionId) {
    sessionId = await loadLastSessionId(workspaceDir);
  }

  await runInteractiveApp({
    workspaceDir,
    jarPath: args.runtime.jar,
    executorVersion: args.runtime.executorVersion,
    executorSnapshot: args.runtime.executorSnapshot,
    sessionId,
    resumeSession,
    vendor: args.runtime.vendor
  });

  const lastId = await loadLastSessionId(workspaceDir);
  if (lastId) {
    const zipPath = await sessionZipPath(workspaceDir, lastId);
    if (await hasTasks(zipPath)) {
      console.log(`brokk-code resume ${lastId}`);
    }
  }
}
