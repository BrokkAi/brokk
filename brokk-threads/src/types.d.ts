import type { InitialShellState } from "../electron/types";

declare global {
  interface Window {
    brokkThreads: {
      getInitialShellState(): Promise<InitialShellState>;
    };
  }
}

export {};
