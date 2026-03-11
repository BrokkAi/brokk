import type {
  ExecutorProvisioning,
  LazyExecutorService,
  ThreadExecutorClient,
  ThreadExecutorManager,
  ThreadMetadata,
  ThreadMetadataStore
} from "./types";

type ManagerDeps = {
  metadataStore: ThreadMetadataStore;
  executorService: LazyExecutorService;
};

type ExecutorState = {
  provisioning: ExecutorProvisioning | null;
  sessionId: string | null;
  started: boolean;
};

class PerThreadExecutorClient implements ThreadExecutorClient {
  constructor(
    private readonly thread: ThreadMetadata,
    private readonly state: ExecutorState,
    private readonly executorService: LazyExecutorService
  ) {}

  async ensureReady(): Promise<ExecutorProvisioning> {
    if (this.state.provisioning) {
      return this.state.provisioning;
    }
    this.state.provisioning = await this.executorService.startExecutor(this.thread);
    this.state.started = true;
    return this.state.provisioning;
  }

  async ensureSessionForThread(thread: ThreadMetadata): Promise<{ sessionId: string | null }> {
    if (this.state.sessionId) {
      return { sessionId: this.state.sessionId };
    }
    this.state.sessionId = thread.provisioning?.brokkSessionId ?? null;
    return { sessionId: this.state.sessionId };
  }

  async sendPrompt(_prompt: string): Promise<void> {
    await this.ensureReady();
  }
}

export class InMemoryPerThreadExecutorManager implements ThreadExecutorManager {
  private readonly executorsByThreadId = new Map<string, PerThreadExecutorClient>();
  private readonly stateByThreadId = new Map<string, ExecutorState>();

  constructor(private readonly deps: ManagerDeps) {}

  async ensureExecutorForThread(thread: ThreadMetadata): Promise<ThreadExecutorClient> {
    const existing = this.executorsByThreadId.get(thread.id);
    if (existing) {
      return existing;
    }

    const state: ExecutorState = this.stateByThreadId.get(thread.id) ?? {
      provisioning: thread.provisioning?.executor ?? null,
      sessionId: thread.provisioning?.brokkSessionId ?? null,
      started: Boolean(thread.provisioning?.executor)
    };

    this.stateByThreadId.set(thread.id, state);
    const client = new PerThreadExecutorClient(thread, state, this.deps.executorService);
    this.executorsByThreadId.set(thread.id, client);
    return client;
  }

  getActiveExecutorThreadIds(): string[] {
    return [...this.stateByThreadId.entries()]
      .filter(([, state]) => state.started)
      .map(([threadId]) => threadId);
  }
}

export function createMainProcessThreadExecutorManager(deps: ManagerDeps): ThreadExecutorManager {
  return new InMemoryPerThreadExecutorManager(deps);
}
