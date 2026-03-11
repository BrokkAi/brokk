import { describe, expect, it, vi } from "vitest";
import {
  createThreadMetadataOnly,
  loadInitialShellState,
  renameThreadMetadataOnly,
  selectThreadMetadataOnly
} from "../electron/shellController";
import type {
  LazyExecutorService,
  LazyWorktreeProvisioningService,
  ThreadMetadataStore
} from "../electron/types";

describe("thread shell metadata controller", () => {
  it("hydrates from metadata store only and does not invoke lazy services", async () => {
    const metadataStore: ThreadMetadataStore = {
      loadState: vi.fn().mockResolvedValue({
        version: 1,
        selectedThreadId: null,
        threads: [
          {
            id: "t1",
            title: "Thread 1",
            createdAt: "2026-01-10T10:00:00.000Z",
            updatedAt: "2026-01-10T10:00:00.000Z"
          },
          {
            id: "t2",
            title: "Thread 2",
            createdAt: "2026-02-10T10:00:00.000Z",
            updatedAt: "2026-02-10T10:00:00.000Z"
          }
        ]
      }),
      createThread: vi.fn(),
      renameThread: vi.fn(),
      selectThread: vi.fn()
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

    expect(metadataStore.loadState).toHaveBeenCalledTimes(1);
    expect(executorService.startExecutor).not.toHaveBeenCalled();
    expect(worktreeService.createWorktreeForThread).not.toHaveBeenCalled();
    expect(state.threads).toHaveLength(2);
    expect(state.selectedThreadId).toBe("t2");
  });

  it("prefers persisted selectedThreadId when valid without provisioning", async () => {
    const metadataStore: ThreadMetadataStore = {
      loadState: vi.fn().mockResolvedValue({
        version: 1,
        selectedThreadId: "t1",
        threads: [
          {
            id: "t1",
            title: "Thread 1",
            createdAt: "2026-01-10T10:00:00.000Z",
            updatedAt: "2026-01-10T10:00:00.000Z"
          },
          {
            id: "t2",
            title: "Thread 2",
            createdAt: "2026-02-10T10:00:00.000Z",
            updatedAt: "2026-02-10T10:00:00.000Z"
          }
        ]
      }),
      createThread: vi.fn(),
      renameThread: vi.fn(),
      selectThread: vi.fn()
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

    expect(state.selectedThreadId).toBe("t1");
    expect(executorService.startExecutor).not.toHaveBeenCalled();
    expect(worktreeService.createWorktreeForThread).not.toHaveBeenCalled();
  });

  it("create/rename/select metadata-only operations do not invoke provisioning services", async () => {
    const createdThread = {
      id: "t-new",
      title: "New Thread",
      createdAt: "2026-03-01T00:00:00.000Z",
      updatedAt: "2026-03-01T00:00:00.000Z"
    };

    const renamedThread = {
      ...createdThread,
      title: "Renamed Thread",
      updatedAt: "2026-03-01T00:01:00.000Z"
    };

    const metadataStore: ThreadMetadataStore = {
      loadState: vi.fn().mockResolvedValue({
        version: 1,
        selectedThreadId: null,
        threads: [createdThread]
      }),
      createThread: vi.fn().mockResolvedValue(createdThread),
      renameThread: vi.fn().mockResolvedValue(renamedThread),
      selectThread: vi.fn().mockResolvedValue(undefined)
    };

    const executorService: LazyExecutorService = {
      startExecutor: vi.fn().mockResolvedValue(undefined)
    };

    const worktreeService: LazyWorktreeProvisioningService = {
      createWorktreeForThread: vi.fn().mockResolvedValue(undefined)
    };

    const deps = { metadataStore, executorService, worktreeService };

    const created = await createThreadMetadataOnly(deps, "New Thread");
    const renamed = await renameThreadMetadataOnly(deps, created.id, "Renamed Thread");
    await selectThreadMetadataOnly(deps, created.id);

    expect(created.provisioning).toBeUndefined();
    expect(renamed.provisioning).toBeUndefined();
    expect(metadataStore.createThread).toHaveBeenCalledWith("New Thread");
    expect(metadataStore.renameThread).toHaveBeenCalledWith(created.id, "Renamed Thread");
    expect(metadataStore.selectThread).toHaveBeenCalledWith(created.id);
    expect(executorService.startExecutor).not.toHaveBeenCalled();
    expect(worktreeService.createWorktreeForThread).not.toHaveBeenCalled();
  });
});
