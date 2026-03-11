import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it, vi } from "vitest";
import { LazyThreadWorktreeProvisioningService } from "../electron/worktreeProvisioningService";

describe("LazyThreadWorktreeProvisioningService", () => {
  it("creates exactly one mapping for first provision and reuses it on second call", async () => {
    const dir = await mkdtemp(join(tmpdir(), "brokk-threads-"));
    const mappingFilePath = join(dir, "thread-worktree-map.json");

    const gitOps = {
      sanitizeBranchName: vi.fn((name: string) => name.replace(/\s+/g, "-")),
      getNextWorktreePath: vi.fn().mockResolvedValue("/tmp/wt1"),
      addWorktree: vi.fn().mockResolvedValue(undefined),
      worktreeExists: vi
        .fn()
        .mockResolvedValueOnce(true)
        .mockResolvedValueOnce(true)
    };

    const service = new LazyThreadWorktreeProvisioningService(mappingFilePath, "/tmp/worktrees", gitOps);

    const thread = {
      id: "thread-1",
      title: "My Thread",
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    };

    const first = await service.createWorktreeForThread(thread);
    const second = await service.createWorktreeForThread(thread);

    expect(first.branch).toBe(second.branch);
    expect(first.worktreePath).toBe(second.worktreePath);
    expect(gitOps.addWorktree).toHaveBeenCalledTimes(1);

    const persisted = JSON.parse(await readFile(mappingFilePath, "utf-8")) as {
      mappings: Array<{ threadId: string }>;
    };
    expect(persisted.mappings).toHaveLength(1);
    expect(persisted.mappings[0]?.threadId).toBe("thread-1");
  });
});
