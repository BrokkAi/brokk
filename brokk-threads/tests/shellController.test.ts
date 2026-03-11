import { describe, expect, it, vi } from "vitest";
import { loadInitialShellState } from "../electron/shellController";
import type {
  LazyExecutorService,
  LazyWorktreeProvisioningService,
  ThreadMetadataStore
} from "../electron/types";

describe("loadInitialShellState", () => {
  it("hydrates from metadata store only and does not invoke lazy services", async () => {
    const metadataStore: ThreadMetadataStore = {
      readThreadMetadata: vi.fn().mockResolvedValue([
        { id: "t1", title: "Thread 1", updatedAt: "2026-01-10T10:00:00.000Z" },
        { id: "t2", title: "Thread 2", updatedAt: "2026-02-10T10:00:00.000Z" }
      ])
    };

    const executorService: LazyExecutorService = {
      startExecutor: vi.fn().mockResolvedValue(undefined)
    };

    const worktreeService: LazyWorktreeProvisioningService = {
      createWorktreeForThread: vi.fn().mockResolvedValue(undefined)
    };

    const state = await loadInitialShellState({
      metadataStore,
      executorService,
      worktreeService
    });

    expect(metadataStore.readThreadMetadata).toHaveBeenCalledTimes(1);
    expect(executorService.startExecutor).not.toHaveBeenCalled();
    expect(worktreeService.createWorktreeForThread).not.toHaveBeenCalled();
    expect(state.threads).toHaveLength(2);
    expect(state.selectedThreadId).toBe("t2");
  });
});
