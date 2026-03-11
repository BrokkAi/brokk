import { app, BrowserWindow, ipcMain } from "electron";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { FileThreadMetadataStore } from "./metadataStore.js";
import {
  createThreadMetadataOnly,
  LazyThreadWorktreeProvisioningService,
  loadInitialShellState,
  provisionThreadForPromptIfNeeded,
  renameThreadMetadataOnly,
  selectThreadMetadataOnly
} from "./shellController.js";
import { createMainProcessThreadExecutorManager } from "./threadExecutorManager.js";
import type {
  ExecutorProvisioning,
  LazyExecutorService,
  ShellControllerDeps,
  ThreadMetadata
} from "./types.js";

class NoopLazyExecutorService implements LazyExecutorService {
  async startExecutor(thread: ThreadMetadata): Promise<ExecutorProvisioning> {
    return {
      executorId: `noop-executor-${thread.id}`,
      startedAt: new Date().toISOString()
    };
  }
}

const userDataPath = app.getPath("userData");
const metadataStore = new FileThreadMetadataStore(join(userDataPath, "thread-metadata.json"));
const threadExecutorManager = createMainProcessThreadExecutorManager({
  metadataStore,
  executorService: new NoopLazyExecutorService()
});

const deps: ShellControllerDeps = {
  metadataStore,
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

ipcMain.handle("threads:send-prompt", async (_event, threadId: string, prompt: string) => {
  try {
    // 1. ensure thread metadata exists & worktree/branch provisioning
    const provisioned = await provisionThreadForPromptIfNeeded(deps, threadId);

    // 2. ensure dedicated per-thread executor exists
    const executor = await threadExecutorManager.ensureExecutorForThread(provisioned.thread);

    // 3. ensure executor is ready (lazy start)
    await executor.ensureReady();

    // 4. ensure session exists/is active for that thread
    await executor.ensureSessionForThread(provisioned.thread);

    // 5. submit prompt and stream output
    await executor.sendPrompt(prompt);
  } catch (error: unknown) {
    console.error(`Failed to send prompt for thread ${threadId}:`, error);
    throw error; // Propagate to renderer
  }
});

ipcMain.handle("threads:subscribe-output", (event) => {
  const unsubscribe = threadExecutorManager.onOutput((payload) => {
    event.sender.send("threads:output", payload);
  });
  event.sender.once("destroyed", unsubscribe);
});

ipcMain.handle("threads:debug-active-executors", () => {
  return threadExecutorManager.getActiveExecutorThreadIds();
});

app.whenReady().then(createWindow);
