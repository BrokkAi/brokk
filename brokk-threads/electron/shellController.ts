import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import type {
  InitialShellState,
  LazyWorktreeProvisioningService,
  ProvisionedThreadResult,
  ShellControllerDeps,
  ThreadMetadata,
  ThreadProvisioning
} from "./types";

type ProvisioningRecord = {
  threadId: string;
  branch: string;
  worktreePath: string;
  brokkSessionId: string | null;
};

type ProvisioningState = {
  version: 1;
  mappings: ProvisioningRecord[];
};

export interface WorktreeGitOps {
  sanitizeBranchName(proposed: string): string;
  getNextWorktreePath(storageRoot: string): Promise<string>;
  addWorktree(branch: string, worktreePath: string): Promise<void>;
  worktreeExists(worktreePath: string): Promise<boolean>;
}

const defaultState = (): ProvisioningState => ({ version: 1, mappings: [] });

function stableBaseBranchName(thread: ThreadMetadata): string {
  const titlePart = thread.title.trim().toLowerCase().replace(/\s+/g, "-").replace(/[^a-z0-9-/_]/g, "");
  const fallback = `thread-${thread.id}`;
  return titlePart.length > 0 ? `${titlePart}-${thread.id}` : fallback;
}

export class LazyThreadWorktreeProvisioningService implements LazyWorktreeProvisioningService {
  constructor(
    private readonly mappingFilePath: string,
    private readonly worktreeStorageRoot: string,
    private readonly gitOps: WorktreeGitOps
  ) {}

  private async readState(): Promise<ProvisioningState> {
    try {
      const raw = await readFile(resolve(this.mappingFilePath), "utf-8");
      const parsed = JSON.parse(raw) as Partial<ProvisioningState>;
      const mappings = Array.isArray(parsed.mappings) ? parsed.mappings : [];
      return { version: 1, mappings };
    } catch (error: unknown) {
      const code = (error as { code?: string })?.code;
      if (code === "ENOENT") {
        await mkdir(dirname(resolve(this.mappingFilePath)), { recursive: true });
        await writeFile(resolve(this.mappingFilePath), JSON.stringify(defaultState(), null, 2), "utf-8");
        return defaultState();
      }
      throw error;
    }
  }

  private async writeState(state: ProvisioningState): Promise<void> {
    await writeFile(resolve(this.mappingFilePath), JSON.stringify(state, null, 2), "utf-8");
  }

  async createWorktreeForThread(thread: ThreadMetadata): Promise<ThreadProvisioning> {
    if (thread.provisioning?.branch && thread.provisioning?.worktreePath) {
      return thread.provisioning;
    }

    const state = await this.readState();
    const existing = state.mappings.find((mapping) => mapping.threadId === thread.id);
    if (existing) {
      const exists = await this.gitOps.worktreeExists(existing.worktreePath);
      if (!exists) {
        await this.gitOps.addWorktree(existing.branch, existing.worktreePath);
      }
      return {
        branch: existing.branch,
        worktreePath: existing.worktreePath,
        brokkSessionId: existing.brokkSessionId
      };
    }

    const proposed = stableBaseBranchName(thread);
    const branch = this.gitOps.sanitizeBranchName(proposed);
    const worktreePath = await this.gitOps.getNextWorktreePath(this.worktreeStorageRoot);
    await this.gitOps.addWorktree(branch, worktreePath);

    const record: ProvisioningRecord = {
      threadId: thread.id,
      branch,
      worktreePath,
      brokkSessionId: null
    };
    const nextState: ProvisioningState = {
      version: 1,
      mappings: [...state.mappings, record]
    };
    await this.writeState(nextState);

    return {
      branch,
      worktreePath,
      brokkSessionId: null
    };
  }
}

export async function loadInitialShellState(deps: ShellControllerDeps): Promise<InitialShellState> {
  const state = await deps.metadataStore.loadState();
  return {
    threads: state.threads,
    selectedThreadId: state.selectedThreadId
  };
}

export async function createThreadMetadataOnly(
  deps: ShellControllerDeps,
  title: string
): Promise<ThreadMetadata> {
  return deps.metadataStore.createThread(title);
}

export async function renameThreadMetadataOnly(
  deps: ShellControllerDeps,
  threadId: string,
  title: string
): Promise<ThreadMetadata> {
  return deps.metadataStore.renameThread(threadId, title);
}

export async function selectThreadMetadataOnly(deps: ShellControllerDeps, threadId: string): Promise<void> {
  await deps.metadataStore.selectThread(threadId);
}

export async function provisionThreadForPromptIfNeeded(
  deps: ShellControllerDeps,
  threadId: string
): Promise<ProvisionedThreadResult> {
  const state = await deps.metadataStore.loadState();
  const thread = state.threads.find((t) => t.id === threadId);
  if (!thread) {
    throw new Error(`Thread ${threadId} not found`);
  }

  if (thread.provisioning?.branch && thread.provisioning?.worktreePath) {
    return { thread, created: false };
  }

  const provisioning = await deps.worktreeService.createWorktreeForThread(thread);
  const updated = await deps.metadataStore.attachProvisioning(threadId, provisioning);
  return { thread: updated, created: true };
}
