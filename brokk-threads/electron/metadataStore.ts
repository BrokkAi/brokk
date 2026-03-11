import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import type { ThreadMetadata, ThreadMetadataStore, ThreadProvisioning, ThreadState } from "./types.js";

function nowIso(): string {
  return new Date().toISOString();
}

function defaultState(): ThreadState {
  return {
    version: 1,
    selectedThreadId: null,
    threads: []
  };
}

function normalizeThread(input: Partial<ThreadMetadata>): ThreadMetadata {
  const createdAt = input.createdAt ?? input.updatedAt ?? nowIso();
  const updatedAt = input.updatedAt ?? createdAt;
  return {
    id: input.id ?? "",
    title: input.title ?? "New Thread",
    createdAt,
    updatedAt,
    provisioning: input.provisioning
  };
}

function normalizeState(raw: unknown): ThreadState {
  if (!raw || typeof raw !== "object") {
    return defaultState();
  }

  const asRecord = raw as Record<string, unknown>;
  const rawThreads = Array.isArray(asRecord.threads) ? asRecord.threads : [];
  const threads = rawThreads
    .filter((value): value is Partial<ThreadMetadata> => Boolean(value && typeof value === "object"))
    .map(normalizeThread)
    .filter((thread) => thread.id.length > 0);

  const selectedThreadId =
    typeof asRecord.selectedThreadId === "string" ? asRecord.selectedThreadId : null;

  return {
    version: 1,
    selectedThreadId,
    threads
  };
}

export class FileThreadMetadataStore implements ThreadMetadataStore {
  constructor(private readonly metadataFilePath: string) {}

  private async writeState(state: ThreadState): Promise<void> {
    const filePath = resolve(this.metadataFilePath);
    await writeFile(filePath, JSON.stringify(state, null, 2), "utf-8");
  }

  async loadState(): Promise<ThreadState> {
    try {
      const filePath = resolve(this.metadataFilePath);
      const content = await readFile(filePath, "utf-8");
      return normalizeState(JSON.parse(content));
    } catch (error: unknown) {
      const code = (error as { code?: string })?.code;
      if (code === "ENOENT") {
        const initial = defaultState();
        await writeFile(resolve(this.metadataFilePath), JSON.stringify(initial, null, 2), "utf-8");
        return initial;
      }
      throw error;
    }
  }

  async createThread(title: string): Promise<ThreadMetadata> {
    const state = await this.loadState();
    const timestamp = nowIso();
    const thread: ThreadMetadata = {
      id: `thread-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
      title: title.trim() || "New Thread",
      createdAt: timestamp,
      updatedAt: timestamp
    };
    const nextState: ThreadState = {
      ...state,
      threads: [thread, ...state.threads],
      selectedThreadId: thread.id
    };
    await this.writeState(nextState);
    return thread;
  }

  async renameThread(threadId: string, title: string): Promise<ThreadMetadata> {
    const state = await this.loadState();
    const nextTitle = title.trim();
    const nextThreads = state.threads.map((thread) =>
      thread.id === threadId ? { ...thread, title: nextTitle || thread.title, updatedAt: nowIso() } : thread
    );
    const updated = nextThreads.find((thread) => thread.id === threadId);
    if (!updated) {
      throw new Error(`Thread ${threadId} not found`);
    }
    await this.writeState({ ...state, threads: nextThreads });
    return updated;
  }

  async selectThread(threadId: string): Promise<void> {
    const state = await this.loadState();
    const exists = state.threads.some((thread) => thread.id === threadId);
    if (!exists) {
      throw new Error(`Thread ${threadId} not found`);
    }
    await this.writeState({ ...state, selectedThreadId: threadId });
  }

  async attachProvisioning(
    threadId: string,
    provisioning: ThreadProvisioning
  ): Promise<ThreadMetadata> {
    const state = await this.loadState();
    const nextThreads = state.threads.map((thread) =>
      thread.id === threadId ? { ...thread, provisioning, updatedAt: nowIso() } : thread
    );
    const updated = nextThreads.find((thread) => thread.id === threadId);
    if (!updated) {
      throw new Error(`Thread ${threadId} not found`);
    }
    await this.writeState({ ...state, threads: nextThreads });
    return updated;
  }
}
