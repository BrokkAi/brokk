/// <reference types="vite/client" />

import type { AgentManagerState, PromptSubmission } from '../shared/types';

declare global {
  interface Window {
    agentManager: {
      getState: () => Promise<AgentManagerState>;
      createThread: (seedPrompt: string) => Promise<AgentManagerState>;
      switchThread: (threadId: string) => Promise<AgentManagerState>;
      submitPrompt: (payload: PromptSubmission) => Promise<AgentManagerState>;
      onState: (listener: (state: AgentManagerState) => void) => () => void;
    };
  }
}

export {};
