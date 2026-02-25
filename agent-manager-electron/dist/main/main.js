import { app, BrowserWindow, ipcMain } from 'electron';
import { join } from 'node:path';
import { AgentManager } from './agent-manager';
const agentManager = new AgentManager();
const createWindow = async () => {
    const window = new BrowserWindow({
        width: 1360,
        height: 900,
        backgroundColor: '#0f172a',
        webPreferences: {
            preload: join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false
        }
    });
    agentManager.registerWindow(window);
    const devServerUrl = process.env.VITE_DEV_SERVER_URL;
    if (devServerUrl) {
        await window.loadURL(devServerUrl);
        window.webContents.openDevTools({ mode: 'detach' });
    }
    else {
        await window.loadFile(join(__dirname, '../renderer/index.html'));
    }
};
ipcMain.handle('agent:get-state', () => agentManager.getState());
ipcMain.handle('agent:create-thread', (_, seedPrompt) => agentManager.createThread(seedPrompt));
ipcMain.handle('agent:switch-thread', (_, threadId) => agentManager.switchThread(threadId));
ipcMain.handle('agent:submit-prompt', (_, payload) => agentManager.submitPrompt(payload));
app.whenReady().then(() => {
    createWindow().catch(error => {
        console.error('Unable to create main window', error);
        app.quit();
    });
    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) {
            createWindow().catch(error => {
                console.error('Unable to re-create main window', error);
            });
        }
    });
});
app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});
