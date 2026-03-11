import { contextBridge, ipcRenderer } from "electron";
import type { InitialShellState } from "./types";

export type RendererBridge = {
  getInitialShellState(): Promise<InitialShellState>;
};

const bridge: RendererBridge = {
  getInitialShellState() {
    return ipcRenderer.invoke("threads:get-initial-shell-state");
  }
};

contextBridge.exposeInMainWorld("brokkThreads", bridge);
