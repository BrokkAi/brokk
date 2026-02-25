export type ExecutorStatus = 'running' | 'stopped';

export interface SessionOutput {
  timestamp: string;
  message: string;
}

export interface ConversationThread {
  id: string;
  title: string;
  sessionName: string;
  worktreeName: string;
  executorStatus: ExecutorStatus;
  outputs: SessionOutput[];
}

export interface AgentManagerState {
  activeThreadId: string | null;
  threads: ConversationThread[];
}

export interface PromptSubmission {
  threadId: string;
  prompt: string;
}
