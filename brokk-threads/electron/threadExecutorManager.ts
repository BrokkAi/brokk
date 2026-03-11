import { EventEmitter } from "node:events";
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

type OutputEvent = {
  threadId: string;
  text: string;
};

class PerThreadExecutorClient implements ThreadExecutorClient {
  constructor(
    private readonly thread: ThreadMetadata,
    private readonly state: ExecutorState,
    private readonly executorService: LazyExecutorService,
    private readonly metadataStore: ThreadMetadataStore,
    private readonly outputEmitter: EventEmitter
  ) {}

  async ensureReady(): Promise<ExecutorProvisioning> {
    if (this.state.provisioning) {
      return this.state.provisioning;
    }
    this.state.provisioning = await this.executorService.startExecutor(this.thread);
    this.state.started = true;
    const mergedProvisioning = {
      ...(this.thread.provisioning ?? {
        branch: "",
        worktreePath: "",
        brokkSessionId: null
      }),
      executor: this.state.provisioning
    };
    await this.metadataStore.attachProvisioning(this.thread.id, mergedProvisioning);
    return this.state.provisioning;
  }

  async ensureSessionForThread(thread: ThreadMetadata): Promise<{ sessionId: string | null }> {
    if (this.state.sessionId) {
      return { sessionId: this.state.sessionId };
    }
    const existing = thread.provisioning?.brokkSessionId ?? null;
    if (existing) {
      this.state.sessionId = existing;
      return { sessionId: existing };
    }
    const sessionId = `session-${thread.id}`;
    this.state.sessionId = sessionId;
    await this.metadataStore.attachProvisioning(thread.id, {
      ...(thread.provisioning ?? { branch: "", worktreePath: "", brokkSessionId: null }),
      brokkSessionId: sessionId,
      executor: this.state.provisioning ?? undefined
    });
    return { sessionId };
  }

  async sendPrompt(prompt: string): Promise<void> {
    await this.ensureReady();
    this.outputEmitter.emit("output", {
      threadId: this.thread.id,
      text: `> ${prompt}\nAssistant: job submitted`
    } satisfies OutputEvent);
  }
}

export class InMemoryPerThreadExecutorManager implements ThreadExecutorManager {
  private readonly executorsByThreadId = new Map<string, PerThreadExecutorClient>();
  private readonly stateByThreadId = new Map<string, ExecutorState>();
  private readonly outputEmitter = new EventEmitter();

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
    const client = new PerThreadExecutorClient(
      thread,
      state,
      this.deps.executorService,
      this.deps.metadataStore,
      this.outputEmitter
    );
    this.executorsByThreadId.set(thread.id, client);
    return client;
  }

  onOutput(listener: (event: OutputEvent) => void): () => void {
    this.outputEmitter.on("output", listener);
    return () => this.outputEmitter.off("output", listener);
  }

  getActiveExecutorThreadIds(): string[] {
    return [...this.stateByThreadId.entries()]
      .filter(([, state]) => state.started)
      .map(([threadId]) => threadId);
  }
}

export function createMainProcessThreadExecutorManager(deps: ManagerDeps): InMemoryPerThreadExecutorManager {
  return new InMemoryPerThreadExecutorManager(deps);
}
