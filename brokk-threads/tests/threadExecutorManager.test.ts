import { describe, expect, it, vi } from "vitest";
import { InMemoryPerThreadExecutorManager } from "../electron/threadExecutorManager";
import type { ThreadMetadata, ThreadMetadataStore } from "../electron/types";

function createThread(id: string, title: string, provisioned = false): ThreadMetadata {
  return {
    id,
    title,
    createdAt: "2026-01-01T00:00:00.000Z",
    updatedAt: "2026-01-01T00:00:00.000Z",
    provisioning: provisioned
      ? {
          branch: `feature/${id}`,
          worktreePath: `/tmp/${id}`,
          brokkSessionId: `${id}-session`
        }
      : undefined
  };
}

const metadataStoreStub: ThreadMetadataStore = {
  loadState: vi.fn(),
  createThread: vi.fn(),
  renameThread: vi.fn(),
  selectThread: vi.fn(),
  attachProvisioning: vi.fn()
};

describe("InMemoryPerThreadExecutorManager", () => {
  it("starts zero executors at startup", () => {
    const startExecutor = vi.fn();
    const manager = new InMemoryPerThreadExecutorManager({
      metadataStore: metadataStoreStub,
      executorService: { startExecutor }
    });

    expect(manager.getActiveExecutorThreadIds()).toEqual([]);
    expect(startExecutor).not.toHaveBeenCalled();
  });

  it("ensuring/selecting a thread does not start all executors", async () => {
    const startExecutor = vi.fn().mockResolvedValue({
      executorId: "exec-a",
      startedAt: new Date().toISOString()
    });

    const manager = new InMemoryPerThreadExecutorManager({
      metadataStore: metadataStoreStub,
      executorService: { startExecutor }
    });

    const threadA = createThread("a", "A");
    const threadB = createThread("b", "B");

    await manager.ensureExecutorForThread(threadA);
    await manager.ensureExecutorForThread(threadB);

    expect(startExecutor).not.toHaveBeenCalled();
    expect(manager.getActiveExecutorThreadIds()).toEqual([]);
  });

  it("sending prompt starts only that thread executor and reuses provisioned mapping", async () => {
    const startExecutor = vi
      .fn()
      .mockResolvedValueOnce({ executorId: "exec-a", startedAt: new Date().toISOString() })
      .mockResolvedValueOnce({ executorId: "exec-b", startedAt: new Date().toISOString() });

    const manager = new InMemoryPerThreadExecutorManager({
      metadataStore: metadataStoreStub,
      executorService: { startExecutor }
    });

    const threadA = createThread("a", "A", true);
    const threadB = createThread("b", "B", true);

    const outputEvents: Array<{ threadId: string; text: string }> = [];
    const unsubscribe = manager.onOutput((event) => outputEvents.push(event));

    const executorAFirst = await manager.ensureExecutorForThread(threadA);
    await executorAFirst.ensureSessionForThread(threadA);
    await executorAFirst.sendPrompt("run A");

    expect(startExecutor).toHaveBeenCalledTimes(1);
    expect(manager.getActiveExecutorThreadIds()).toEqual(["a"]);

    // Ensure session retrieval doesn't restart
    await executorAFirst.ensureSessionForThread(threadA);
    expect(startExecutor).toHaveBeenCalledTimes(1);
    expect(manager.getActiveExecutorThreadIds()).toEqual(["a"]);

    const executorASecond = await manager.ensureExecutorForThread(threadA);
    await executorASecond.ensureSessionForThread(threadA);
    expect(executorASecond).toBe(executorAFirst);
    expect(startExecutor).toHaveBeenCalledTimes(1);

    const executorB = await manager.ensureExecutorForThread(threadB);
    await executorB.sendPrompt("run B");

    expect(startExecutor).toHaveBeenCalledTimes(2);
    expect(manager.getActiveExecutorThreadIds().sort()).toEqual(["a", "b"]);
    expect(outputEvents.some((event) => event.threadId === "a")).toBe(true);
    expect(outputEvents.some((event) => event.threadId === "b")).toBe(true);
    unsubscribe();
  });
});
