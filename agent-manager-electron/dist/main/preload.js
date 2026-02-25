import { contextBridge, ipcRenderer } from 'electron';
const api = {
    getState: () => ipcRenderer.invoke('agent:get-state'),
    createThread: (seedPrompt) => ipcRenderer.invoke('agent:create-thread', seedPrompt),
    switchThread: (threadId) => ipcRenderer.invoke('agent:switch-thread', threadId),
    submitPrompt: (payload) => ipcRenderer.invoke('agent:submit-prompt', payload),
    onState: (listener) => {
        const wrapped = (_event, state) => listener(state);
        ipcRenderer.on('agent-state', wrapped);
        return () => ipcRenderer.removeListener('agent-state', wrapped);
    }
};
contextBridge.exposeInMainWorld('agentManager', api);
