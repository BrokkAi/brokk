import { randomUUID } from 'node:crypto';
const INACTIVITY_MS = 5 * 60 * 1000;
const toSlug = (input) => input
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9\s-]/g, '')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .slice(0, 40) || 'thread';
const summarizePrompt = (prompt) => prompt.trim().split(/\s+/).slice(0, 8).join(' ');
const stamp = () => new Date().toISOString().replace(/[:.]/g, '-').toLowerCase();
export class AgentManager {
    state = { activeThreadId: null, threads: [] };
    runtimes = new Map();
    windows = new Set();
    registerWindow(window) {
        this.windows.add(window);
        window.on('closed', () => this.windows.delete(window));
        window.webContents.on('did-finish-load', () => {
            window.webContents.send('agent-state', this.state);
        });
    }
    getState() {
        return this.state;
    }
    createThread(seedPrompt) {
        const summary = summarizePrompt(seedPrompt);
        const slug = toSlug(summary);
        const threadId = randomUUID();
        const sessionName = `session-${slug}-${stamp()}`;
        const worktreeName = `wt-${slug}`;
        const thread = {
            id: threadId,
            title: summary || 'Untitled thread',
            sessionName,
            worktreeName,
            executorStatus: 'stopped',
            outputs: [
                {
                    timestamp: new Date().toISOString(),
                    message: `Thread created with session ${sessionName} and worktree ${worktreeName}`
                }
            ]
        };
        this.state = {
            activeThreadId: threadId,
            threads: [thread, ...this.state.threads]
        };
        this.emitState();
        return this.state;
    }
    switchThread(threadId) {
        if (this.state.threads.some(t => t.id === threadId)) {
            this.state = { ...this.state, activeThreadId: threadId };
            this.emitState();
        }
        return this.state;
    }
    submitPrompt(payload) {
        const thread = this.state.threads.find(t => t.id === payload.threadId);
        if (!thread) {
            return this.state;
        }
        this.ensureExecutorRunning(thread.id);
        this.appendOutput(thread.id, `User prompt: ${payload.prompt}`);
        this.appendOutput(thread.id, `Executor handling prompt for session ${thread.sessionName}`);
        this.simulateExecutorRun(thread.id, payload.prompt);
        return this.state;
    }
    simulateExecutorRun(threadId, prompt) {
        const runtime = this.getRuntime(threadId);
        if (runtime.generationTimer) {
            clearTimeout(runtime.generationTimer);
        }
        runtime.generationTimer = setTimeout(() => {
            this.appendOutput(threadId, `Replay chunk: ${prompt.slice(0, 72)}`);
            this.appendOutput(threadId, 'Replay chunk: completed response generation.');
            this.bumpIdleTimeout(threadId);
        }, 800);
    }
    ensureExecutorRunning(threadId) {
        const thread = this.state.threads.find(t => t.id === threadId);
        if (!thread) {
            return;
        }
        if (thread.executorStatus === 'stopped') {
            this.updateThread(threadId, current => ({
                ...current,
                executorStatus: 'running'
            }));
            this.appendOutput(threadId, `Executor launched for ${thread.sessionName}`);
        }
        this.bumpIdleTimeout(threadId);
    }
    bumpIdleTimeout(threadId) {
        const runtime = this.getRuntime(threadId);
        if (runtime.idleTimer) {
            clearTimeout(runtime.idleTimer);
        }
        runtime.idleTimer = setTimeout(() => {
            this.updateThread(threadId, current => ({ ...current, executorStatus: 'stopped' }));
            this.appendOutput(threadId, 'Executor stopped after 5 minutes of inactivity.');
        }, INACTIVITY_MS);
    }
    getRuntime(threadId) {
        const current = this.runtimes.get(threadId);
        if (current) {
            return current;
        }
        const runtime = { idleTimer: null, generationTimer: null };
        this.runtimes.set(threadId, runtime);
        return runtime;
    }
    appendOutput(threadId, message) {
        const output = { timestamp: new Date().toISOString(), message };
        this.updateThread(threadId, current => ({ ...current, outputs: [...current.outputs, output] }));
    }
    updateThread(threadId, updater) {
        const nextThreads = this.state.threads.map(thread => {
            if (thread.id !== threadId) {
                return thread;
            }
            return updater(thread);
        });
        this.state = { ...this.state, threads: nextThreads };
        this.emitState();
    }
    emitState() {
        for (const window of this.windows) {
            if (!window.isDestroyed()) {
                window.webContents.send('agent-state', this.state);
            }
        }
    }
}
