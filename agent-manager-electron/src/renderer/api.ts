import type { AgentManagerState, PromptSubmission } from '../shared/types';

interface AgentManagerApi {
  getState: () => Promise<AgentManagerState>;
  createThread: (seedPrompt: string) => Promise<AgentManagerState>;
  switchThread: (threadId: string) => Promise<AgentManagerState>;
  submitPrompt: (payload: PromptSubmission) => Promise<AgentManagerState>;
  onState: (listener: (state: AgentManagerState) => void) => () => void;
}

const createMockApi = (): AgentManagerApi => {
  let state: AgentManagerState = { activeThreadId: null, threads: [] };
  const listeners = new Set<(value: AgentManagerState) => void>();

  const emit = () => {
    for (const listener of listeners) {
      listener(state);
    }
  };

  return {
    getState: async () => state,
    createThread: async seedPrompt => {
      const id = `${Date.now()}`;
      const slug = seedPrompt.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 20) || 'thread';
      const thread = {
        id,
        title: seedPrompt.split(/\s+/).slice(0, 8).join(' ') || 'Untitled thread',
        sessionName: `session-${slug}-${Date.now()}`,
        worktreeName: `wt-${slug}`,
        executorStatus: 'stopped' as const,
        outputs: [
          {
            timestamp: new Date().toISOString(),
            message: 'Mock mode in browser preview. Run Electron to use real executors.'
          }
        ]
      };
      state = { activeThreadId: id, threads: [thread, ...state.threads] };
      emit();
      return state;
    },
    switchThread: async threadId => {
      state = { ...state, activeThreadId: threadId };
      emit();
      return state;
    },
    submitPrompt: async payload => {
      state = {
        ...state,
        threads: state.threads.map(thread =>
          thread.id === payload.threadId
            ? {
                ...thread,
                executorStatus: 'running',
                outputs: [
                  ...thread.outputs,
                  { timestamp: new Date().toISOString(), message: `User prompt: ${payload.prompt}` },
                  { timestamp: new Date().toISOString(), message: 'Mock replay: executor response chunk.' }
                ]
              }
            : thread
        )
      };
      emit();
      return state;
    },
    onState: listener => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    }
  };
};

export const getAgentManagerApi = (): AgentManagerApi => {
  if (typeof window !== 'undefined' && window.agentManager) {
    return window.agentManager;
  }
  return createMockApi();
};
