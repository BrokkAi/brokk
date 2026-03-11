import type { InitialShellState, ProvisionedThreadResult, ThreadMetadata } from "../electron/types";

declare global {
  interface Window {
    brokkThreads: {
      getInitialShellState(): Promise<InitialShellState>;
      createThread(title: string): Promise<ThreadMetadata>;
      renameThread(threadId: string, title: string): Promise<ThreadMetadata>;
      selectThread(threadId: string): Promise<void>;
      ensureThreadProvisionedForPrompt(threadId: string): Promise<ProvisionedThreadResult>;
      sendPrompt(threadId: string, prompt: string): Promise<void>;
      subscribeOutput(listener: (payload: { threadId: string; text: string }) => void): Promise<void>;
      debugActiveExecutors(): Promise<string[]>;
    };
  }
}

export {};
