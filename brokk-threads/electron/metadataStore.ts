import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import type { ThreadMetadata, ThreadMetadataStore } from "./types";

type MetadataFileShape = {
  threads?: ThreadMetadata[];
};

export class FileThreadMetadataStore implements ThreadMetadataStore {
  constructor(private readonly metadataFilePath: string) {}

  async readThreadMetadata(): Promise<ThreadMetadata[]> {
    try {
      const content = await readFile(resolve(this.metadataFilePath), "utf-8");
      const parsed: MetadataFileShape = JSON.parse(content);
      return Array.isArray(parsed.threads) ? parsed.threads : [];
    } catch (error: unknown) {
      const code = (error as { code?: string })?.code;
      if (code === "ENOENT") {
        return [];
      }
      throw error;
    }
  }
}
