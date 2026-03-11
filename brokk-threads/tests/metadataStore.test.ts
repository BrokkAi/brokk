import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { FileThreadMetadataStore } from "../electron/metadataStore";

describe("FileThreadMetadataStore", () => {
  it("loads local metadata JSON from disk", async () => {
    const dir = await mkdtemp(join(tmpdir(), "brokk-threads-"));
    const filePath = join(dir, "thread-metadata.json");

    await writeFile(
      filePath,
      JSON.stringify({
        threads: [{ id: "t-1", title: "Disk Thread", updatedAt: "2026-01-01T00:00:00.000Z" }]
      }),
      "utf-8"
    );

    const store = new FileThreadMetadataStore(filePath);
    const threads = await store.readThreadMetadata();

    expect(threads).toEqual([
      { id: "t-1", title: "Disk Thread", updatedAt: "2026-01-01T00:00:00.000Z" }
    ]);
  });

  it("returns empty array when metadata file does not exist", async () => {
    const dir = await mkdtemp(join(tmpdir(), "brokk-threads-"));
    const store = new FileThreadMetadataStore(join(dir, "missing.json"));

    const threads = await store.readThreadMetadata();
    expect(threads).toEqual([]);
  });
});
