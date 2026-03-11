import { app, BrowserWindow, ipcMain } from "electron";
import { join } from "node:path";
import { FileThreadMetadataStore } from "./metadataStore";
import { loadInitialShellState } from "./shellController";
import type {
  LazyExecutorService,
  LazyWorktreeProvisioningService,
  ShellControllerDeps
} from "./types";

class NoopLazyExecutorService implements LazyExecutorService {
  async startExecutor(): Promise<void> {
    throw new Error("Lazy executor start is not wired in initial shell increment");
  }
}

class NoopLazyWorktreeProvisioningService implements LazyWorktreeProvisioningService {
  async createWorktreeForThread(): Promise<void> {
    throw new Error("Lazy worktree provisioning is not wired in initial shell increment");
  }
}

const deps: ShellControllerDeps = {
  metadataStore: new FileThreadMetadataStore(
    join(app.getPath("userData"), "thread-metadata.json")
  ),
  executorService: new NoopLazyExecutorService(),
  worktreeService: new NoopLazyWorktreeProvisioningService()
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

app.whenReady().then(createWindow);
