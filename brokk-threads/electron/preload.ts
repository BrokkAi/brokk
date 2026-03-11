import { contextBridge, ipcRenderer } from "electron";
import type { InitialShellState, ProvisionedThreadResult, ThreadMetadata } from "./types";

export type RendererBridge = {
  getInitialShellState(): Promise<InitialShellState>;
  createThread(title: string): Promise<ThreadMetadata>;
  renameThread(threadId: string, title: string): Promise<ThreadMetadata>;
  selectThread(threadId: string): Promise<void>;
  ensureThreadProvisionedForPrompt(threadId: string): Promise<ProvisionedThreadResult>;
  sendPrompt(threadId: string, prompt: string): Promise<void>;
  debugActiveExecutors(): Promise<string[]>;
};

const bridge: RendererBridge = {
  getInitialShellState() {
    return ipcRenderer.invoke("threads:get-initial-shell-state");
  },
  createThread(title: string) {
    return ipcRenderer.invoke("threads:create-thread", title);
  },
  renameThread(threadId: string, title: string) {
    return ipcRenderer.invoke("threads:rename-thread", threadId, title);
  },
  selectThread(threadId: string) {
    return ipcRenderer.invoke("threads:select-thread", threadId);
  },
  ensureThreadProvisionedForPrompt(threadId: string) {
    return ipcRenderer.invoke("threads:ensure-thread-provisioned-for-prompt", threadId);
  },
  sendPrompt(threadId: string, prompt: string) {
    return ipcRenderer.invoke("threads:send-prompt", threadId, prompt);
  },
  debugActiveExecutors() {
    return ipcRenderer.invoke("threads:debug-active-executors");
  }
};

contextBridge.exposeInMainWorld("brokkThreads", bridge);
