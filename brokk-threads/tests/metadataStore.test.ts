import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { FileThreadMetadataStore } from "../electron/metadataStore";

describe("FileThreadMetadataStore", () => {
  it("loads normalized state from disk", async () => {
    const dir = await mkdtemp(join(tmpdir(), "brokk-threads-"));
    const filePath = join(dir, "thread-metadata.json");

    await writeFile(
      filePath,
      JSON.stringify({
        selectedThreadId: "t-1",
        threads: [{ id: "t-1", title: "Disk Thread", updatedAt: "2026-01-01T00:00:00.000Z" }]
      }),
      "utf-8"
    );

    const store = new FileThreadMetadataStore(filePath);
    const state = await store.loadState();

    expect(state.selectedThreadId).toBe("t-1");
    expect(state.threads).toHaveLength(1);
    expect(state.threads[0]?.createdAt).toBe("2026-01-01T00:00:00.000Z");
  });

  it("creates default state file when metadata file does not exist", async () => {
    const dir = await mkdtemp(join(tmpdir(), "brokk-threads-"));
    const filePath = join(dir, "missing.json");
    const store = new FileThreadMetadataStore(filePath);

    const state = await store.loadState();

    expect(state.threads).toEqual([]);
    const persisted = JSON.parse(await readFile(filePath, "utf-8")) as { version: number };
    expect(persisted.version).toBe(1);
  });

  it("persists provisioning attachment for a thread", async () => {
    const dir = await mkdtemp(join(tmpdir(), "brokk-threads-"));
    const filePath = join(dir, "thread-metadata.json");
    const store = new FileThreadMetadataStore(filePath);

    const created = await store.createThread("Thread A");
    const updated = await store.attachProvisioning(created.id, {
      branch: "feature-thread-a",
      worktreePath: "/tmp/wt1",
      brokkSessionId: null
    });

    expect(updated.provisioning?.branch).toBe("feature-thread-a");
    const reloaded = await store.loadState();
    expect(reloaded.threads[0]?.provisioning?.worktreePath).toBe("/tmp/wt1");
  });
});
