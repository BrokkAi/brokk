import { app, BrowserWindow, ipcMain } from "electron";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { FileThreadMetadataStore } from "./metadataStore";
import {
  createThreadMetadataOnly,
  loadInitialShellState,
  provisionThreadForPromptIfNeeded,
  renameThreadMetadataOnly,
  selectThreadMetadataOnly
} from "./shellController";
import { LazyThreadWorktreeProvisioningService } from "./worktreeProvisioningService";
import type {
  ExecutorProvisioning,
  LazyExecutorService,
  ShellControllerDeps
} from "./types";

class NoopLazyExecutorService implements LazyExecutorService {
  async startExecutor(): Promise<ExecutorProvisioning> {
    return {
      executorId: "noop-executor",
      startedAt: new Date().toISOString()
    };
  }
}

const userDataPath = app.getPath("userData");

const deps: ShellControllerDeps = {
  metadataStore: new FileThreadMetadataStore(join(userDataPath, "thread-metadata.json")),
  executorService: new NoopLazyExecutorService(),
  worktreeService: new LazyThreadWorktreeProvisioningService(
    join(userDataPath, "thread-worktree-map.json"),
    join(userDataPath, "worktrees"),
    {
      sanitizeBranchName(proposed: string): string {
        const cleaned = proposed.trim().toLowerCase().replace(/\s+/g, "-").replace(/[^a-z0-9-/_]/g, "");
        return cleaned.length > 0 ? cleaned : "thread-branch";
      },
      async getNextWorktreePath(storageRoot: string): Promise<string> {
        let index = 1;
        while (true) {
          const candidate = join(storageRoot, `wt${index}`);
          if (!existsSync(candidate)) {
            return candidate;
          }
          index += 1;
        }
      },
      async addWorktree(): Promise<void> {},
      async worktreeExists(worktreePath: string): Promise<boolean> {
        return existsSync(worktreePath);
      }
    }
  )
};

async function createWindow(): Promise<void> {
  const window = new BrowserWindow({
    width: 1200,
    height: 800,
    webPreferences: {
      preload: join(__dirname, "preload.js"),
      contextIsolation: true
    }
  });

  if (process.env.VITE_DEV_SERVER_URL) {
    await window.loadURL(process.env.VITE_DEV_SERVER_URL);
  } else {
    await window.loadFile(join(__dirname, "../dist/index.html"));
  }
}

ipcMain.handle("threads:get-initial-shell-state", async () => {
  return loadInitialShellState(deps);
});

ipcMain.handle("threads:create-thread", async (_event, title: string) => {
  return createThreadMetadataOnly(deps, title);
});

ipcMain.handle("threads:rename-thread", async (_event, threadId: string, title: string) => {
  return renameThreadMetadataOnly(deps, threadId, title);
});

ipcMain.handle("threads:select-thread", async (_event, threadId: string) => {
  await selectThreadMetadataOnly(deps, threadId);
});

ipcMain.handle("threads:ensure-thread-provisioned-for-prompt", async (_event, threadId: string) => {
  return provisionThreadForPromptIfNeeded(deps, threadId);
});

app.whenReady().then(createWindow);
