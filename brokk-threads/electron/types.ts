export type ExecutorProvisioning = {
  executorId: string;
  startedAt: string;
};

export type ThreadProvisioning = {
  branch: string;
  worktreePath: string;
  brokkSessionId: string | null;
  executor?: ExecutorProvisioning;
};

export type ThreadMetadata = {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  provisioning?: ThreadProvisioning;
};

export type ThreadState = {
  version: 1;
  selectedThreadId: string | null;
  threads: ThreadMetadata[];
};

export type InitialShellState = {
  threads: ThreadMetadata[];
  selectedThreadId: string | null;
};

export type ProvisionedThreadResult = {
  thread: ThreadMetadata;
  created: boolean;
};

export interface ThreadMetadataStore {
  loadState(): Promise<ThreadState>;
  createThread(title: string): Promise<ThreadMetadata>;
  renameThread(threadId: string, title: string): Promise<ThreadMetadata>;
  selectThread(threadId: string): Promise<void>;
  attachProvisioning(threadId: string, provisioning: ThreadProvisioning): Promise<ThreadMetadata>;
}

export interface ThreadExecutorClient {
  ensureReady(): Promise<ExecutorProvisioning>;
  ensureSessionForThread(thread: ThreadMetadata): Promise<{ sessionId: string | null }>;
  sendPrompt(prompt: string): Promise<void>;
}

export interface ThreadExecutorManager {
  ensureExecutorForThread(thread: ThreadMetadata): Promise<ThreadExecutorClient>;
  getActiveExecutorThreadIds(): string[];
}

export interface LazyExecutorService {
  startExecutor(thread: ThreadMetadata): Promise<ExecutorProvisioning>;
}

export interface LazyWorktreeProvisioningService {
  createWorktreeForThread(thread: ThreadMetadata): Promise<ThreadProvisioning>;
}

export type ShellControllerDeps = {
  metadataStore: ThreadMetadataStore;
  executorService: LazyExecutorService;
  worktreeService: LazyWorktreeProvisioningService;
};
