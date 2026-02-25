import { BrowserWindow } from 'electron';
import { randomUUID } from 'node:crypto';
import type { AgentManagerState, ConversationThread, PromptSubmission, SessionOutput } from '../shared/types';

type ExecutorRuntime = {
  idleTimer: NodeJS.Timeout | null;
  generationTimer: NodeJS.Timeout | null;
};

const INACTIVITY_MS = 5 * 60 * 1000;

const toSlug = (input: string): string =>
  input
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9\s-]/g, '')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .slice(0, 40) || 'thread';

const summarizePrompt = (prompt: string): string => prompt.trim().split(/\s+/).slice(0, 8).join(' ');

const stamp = (): string => new Date().toISOString().replace(/[:.]/g, '-').toLowerCase();

export class AgentManager {
  private state: AgentManagerState = { activeThreadId: null, threads: [] };

  private runtimes = new Map<string, ExecutorRuntime>();

  private windows = new Set<BrowserWindow>();

  public registerWindow(window: BrowserWindow): void {
    this.windows.add(window);
    window.on('closed', () => this.windows.delete(window));
    window.webContents.on('did-finish-load', () => {
      window.webContents.send('agent-state', this.state);
    });
  }

  public getState(): AgentManagerState {
    return this.state;
  }

  public createThread(seedPrompt: string): AgentManagerState {
    const summary = summarizePrompt(seedPrompt);
    const slug = toSlug(summary);
    const threadId = randomUUID();
    const sessionName = `session-${slug}-${stamp()}`;
    const worktreeName = `wt-${slug}`;
    const thread: ConversationThread = {
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

  public switchThread(threadId: string): AgentManagerState {
    if (this.state.threads.some(t => t.id === threadId)) {
      this.state = { ...this.state, activeThreadId: threadId };
      this.emitState();
    }
    return this.state;
  }

  public submitPrompt(payload: PromptSubmission): AgentManagerState {
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

  private simulateExecutorRun(threadId: string, prompt: string): void {
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

  private ensureExecutorRunning(threadId: string): void {
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

  private bumpIdleTimeout(threadId: string): void {
    const runtime = this.getRuntime(threadId);
    if (runtime.idleTimer) {
      clearTimeout(runtime.idleTimer);
    }

    runtime.idleTimer = setTimeout(() => {
      this.updateThread(threadId, current => ({ ...current, executorStatus: 'stopped' }));
      this.appendOutput(threadId, 'Executor stopped after 5 minutes of inactivity.');
    }, INACTIVITY_MS);
  }

  private getRuntime(threadId: string): ExecutorRuntime {
    const current = this.runtimes.get(threadId);
    if (current) {
      return current;
    }
    const runtime: ExecutorRuntime = { idleTimer: null, generationTimer: null };
    this.runtimes.set(threadId, runtime);
    return runtime;
  }

  private appendOutput(threadId: string, message: string): void {
    const output: SessionOutput = { timestamp: new Date().toISOString(), message };
    this.updateThread(threadId, current => ({ ...current, outputs: [...current.outputs, output] }));
  }

  private updateThread(threadId: string, updater: (thread: ConversationThread) => ConversationThread): void {
    const nextThreads = this.state.threads.map(thread => {
      if (thread.id !== threadId) {
        return thread;
      }
      return updater(thread);
    });
    this.state = { ...this.state, threads: nextThreads };
    this.emitState();
  }

  private emitState(): void {
    for (const window of this.windows) {
      if (!window.isDestroyed()) {
        window.webContents.send('agent-state', this.state);
      }
    }
  }
}
