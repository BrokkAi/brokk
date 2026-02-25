import { contextBridge, ipcRenderer } from 'electron';
import type { AgentManagerState, PromptSubmission } from '../shared/types';

type Unsubscribe = () => void;

const api = {
  getState: (): Promise<AgentManagerState> => ipcRenderer.invoke('agent:get-state'),
  createThread: (seedPrompt: string): Promise<AgentManagerState> => ipcRenderer.invoke('agent:create-thread', seedPrompt),
  switchThread: (threadId: string): Promise<AgentManagerState> => ipcRenderer.invoke('agent:switch-thread', threadId),
  submitPrompt: (payload: PromptSubmission): Promise<AgentManagerState> => ipcRenderer.invoke('agent:submit-prompt', payload),
  onState: (listener: (state: AgentManagerState) => void): Unsubscribe => {
    const wrapped = (_event: Electron.IpcRendererEvent, state: AgentManagerState) => listener(state);
    ipcRenderer.on('agent-state', wrapped);
    return () => ipcRenderer.removeListener('agent-state', wrapped);
  }
};

contextBridge.exposeInMainWorld('agentManager', api);
