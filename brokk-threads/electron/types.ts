export type ThreadMetadata = {
  id: string;
  title: string;
  updatedAt: string;
};

export type InitialShellState = {
  threads: ThreadMetadata[];
  selectedThreadId: string | null;
};

export interface ThreadMetadataStore {
  readThreadMetadata(): Promise<ThreadMetadata[]>;
}

export interface LazyExecutorService {
  startExecutor(): Promise<void>;
}

export interface LazyWorktreeProvisioningService {
  createWorktreeForThread(threadId: string): Promise<void>;
}

export type ShellControllerDeps = {
  metadataStore: ThreadMetadataStore;
  executorService: LazyExecutorService;
  worktreeService: LazyWorktreeProvisioningService;
};
